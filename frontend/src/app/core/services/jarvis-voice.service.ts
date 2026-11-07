import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from './auth.service';
import type { Alignment } from '../../jarvis/face/jarvis-alignment';

/** Ce qui peut empêcher Jarvis de parler (F-231 / SF-231-01) — l'écran retombe alors sur le texte. */
export type JarvisVoiceError = 'budget' | 'non-configure' | 'echec' | 'interrompu';

/** L'état de la voix (F-231 / SF-231-01), montants en euros. */
export interface JarvisVoiceStatus {
  enabled: boolean;
  voice: string;
  monthlyBudgetEur: number;
  monthSpentEur: number;
  budgetReached: boolean;
  /** F-233 / SF-233-06 — le fournisseur de la voix (`openai`, `elevenlabs`). */
  provider?: string;
}

/** F-233 / SF-233-01 — ce que la lecture signale au chronomètre du tour. */
export interface SpeakHooks {
  /** La réponse audio commence à arriver. */
  onFirstByte?: () => void;
  /** Le premier son est joué. */
  onPlaying?: () => void;
}

/** F-250 — ce que l'écran sait du visage : allumé, et lèvres alignées pour ce compte. */
export interface JarvisFaceStatus {
  enabled: boolean;
  aligned: boolean;
}

/** F-250 / SF-250-02 — une phrase reçue : de quoi la jouer, et son alignement (rempli au fil de l'arrivée). */
interface Fetched {
  url: string;
  alignment: Alignment | null;
}

/** SF-233-03 — une phrase en file : son audio est demandé à l'avance (`url`). */
interface QueuedSentence {
  text: string;
  workspaceId: string | null;
  hooks?: SpeakHooks;
  abort: AbortController;
  url: Promise<Fetched> | null;
}

/** Ce que l'écran garde d'une lecture : de quoi l'arrêter. */
interface Playback {
  audio: HTMLAudioElement;
  abort: AbortController;
  url: string;
  /** SF-233-08 — la phrase jouée (pour savoir ce qui a été entendu) ; absente pour un clip. */
  text?: string;
  /** F-250 — l'alignement de la phrase (voix alignée), ou `null` (repli amplitude). */
  alignment: Alignment | null;
  done: (error?: JarvisVoiceError) => void;
}

/**
 * F-233 / SF-233-08 (D10) — ce que Jarvis avait dit au moment d'être coupé : la part **entendue** (phrases finies +
 * début de la phrase coupée, estimé par l'horloge audio) et ce qu'il lui restait à dire (la phrase coupée, entière,
 * puis la file).
 */
export interface CutSnapshot {
  heard: string;
  resume: string[];
}

/** SF-233-08 — volume de la voix pendant qu'un humain semble parler (baisse, avant la coupure). */
export const DUCK_GAIN = 0.25;
/** SF-233-08 — débit moyen de la voix (caractères par seconde), pour estimer la part entendue d'une phrase coupée. */
export const SPOKEN_CHARS_PER_SECOND = 15;

/** La part entendue d'une phrase coupée après `seconds` de lecture (mots entiers ; `duration` si connue). */
export function heardPart(text: string, seconds: number, duration: number): string {
  const words = text.split(/\s+/).filter((w) => w.length > 0);
  if (words.length === 0 || seconds <= 0) {
    return '';
  }
  const fraction = Number.isFinite(duration) && duration > 0
    ? seconds / duration
    : (seconds * SPOKEN_CHARS_PER_SECOND) / Math.max(1, text.length);
  const count = Math.min(words.length, Math.floor(words.length * Math.min(1, fraction)));
  return words.slice(0, count).join(' ');
}

/**
 * **La voix de Jarvis, côté écran** (F-231 / SF-231-02) : lit une phrase par `POST /api/jarvis/speech` et la joue
 * **pendant qu'elle arrive** (MediaSource quand le navigateur sait lire l'mp3 en flux, sinon l'audio entier).
 *
 * <p>Le niveau sonore de la lecture est exposé (`level()`) pour que l'orbe parle avec la voix. Une seule lecture à
 * la fois : en lancer une autre, ou `stop()`, coupe la précédente — c'est l'interruption.</p>
 */
@Injectable({ providedIn: 'root' })
export class JarvisVoiceService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  private context: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private samples: Uint8Array | null = null;
  /** SF-233-08 — le gain de sortie (baisse de volume pendant une interruption possible). */
  private gain: GainNode | null = null;
  /** SF-233-08 — les phrases entendues en entier depuis `resetHeard()`. */
  private heard: string[] = [];
  private current: Playback | null = null;
  /** SF-233-03 — la file de phrases à dire, et l'attente de sa fin. */
  private queue: QueuedSentence[] = [];
  private queueRunning = false;
  private idleWaiters: (() => void)[] = [];
  /** SF-233-03 — ce qui a arrêté la file (voix absente, plafond), ou `null`. */
  private queueError: JarvisVoiceError | null = null;
  /** SF-233-02 — les clips de réaction chargés (URL locales), par nom. */
  private readonly clips = new Map<string, string>();
  /** SF-233-11 — la fin du clip en cours, que la file attend avant de dire sa première phrase. */
  private clipPlaying: Promise<void> | null = null;
  /** F-250 / SF-250-02 — demander l'alignement (visage allumé, voix alignable). */
  private alignedVoice = false;
  /** F-250 / SF-250-06 — les alignements des clips (fichier compagnon), par nom. */
  private readonly clipAlignments = new Map<string, Alignment>();

  /** F-250 — le visage est-il allumé, et les lèvres seront-elles alignées ? */
  faceStatus(): Observable<JarvisFaceStatus> {
    return this.http.get<JarvisFaceStatus>('/api/jarvis/face');
  }

  /** F-250 / SF-250-02 — demande (ou non) l'alignement à la passerelle ; sans effet sur la phrase en cours. */
  setAligned(on: boolean): void {
    this.alignedVoice = on;
  }

  /**
   * F-250 / SF-250-02 — **l'horloge des lèvres** : la position de lecture de la phrase en cours (secondes,
   * `audio.currentTime` — jamais l'heure murale, les lèvres restent calées si le réseau hoquette), ou −1 en silence.
   */
  playhead(): number {
    const playback = this.current;
    return playback && !playback.audio.paused ? playback.audio.currentTime : -1;
  }

  /** F-250 / SF-250-02 — l'alignement de la phrase en cours (rempli au fil de l'arrivée), ou `null`. */
  alignment(): Alignment | null {
    return this.current?.alignment ?? null;
  }

  /** La voix est-elle là, et combien a-t-elle coûté ce mois ? */
  status(): Observable<JarvisVoiceStatus> {
    return this.http.get<JarvisVoiceStatus>('/api/jarvis/voice');
  }

  /** Vrai pendant qu'une phrase est jouée. */
  get speaking(): boolean {
    return this.current !== null;
  }

  /**
   * Débloque la lecture audio. À appeler dans un geste de l'utilisateur (un appui) : les navigateurs refusent le son
   * avant le premier geste.
   */
  unlock(): void {
    const context = this.ensureContext();
    if (context && context.state === 'suspended') {
      void context.resume().catch(() => undefined);
    }
  }

  /**
   * SF-233-12 — le navigateur laisse-t-il Jarvis parler **sans geste** ? (PWA installée, site à fort engagement média,
   * geste déjà fait.) La politique d'autoplay si le navigateur l'expose ; sinon on tente de réveiller l'`AudioContext`
   * (Chrome laisse `resume()` en suspens sans geste : délai borné) puis on lit son état. Sans `AudioContext` : permis.
   */
  async audioAllowed(timeoutMs = 400): Promise<boolean> {
    const policy = (typeof navigator !== 'undefined'
      ? (navigator as unknown as { getAutoplayPolicy?: (type: string) => string }).getAutoplayPolicy : undefined);
    if (policy) {
      try {
        return policy.call(navigator, 'audiocontext') === 'allowed';
      } catch {
        // Politique illisible : on teste le contexte.
      }
    }
    const context = this.ensureContext();
    if (!context || context.state === 'running') {
      return true;
    }
    await Promise.race([
      context.resume().catch(() => undefined),
      new Promise<void>((resolve) => setTimeout(resolve, timeoutMs)),
    ]);
    return (context.state as string) === 'running';
  }

  /**
   * Dit `text` à voix haute. La promesse se résout à la fin de la phrase (ou à son interruption) ; elle est rejetée
   * avec un {@link JarvisVoiceError} si la voix n'a pas pu parler.
   */
  async speak(text: string, workspaceId: string | null = null, hooks?: SpeakHooks): Promise<void> {
    // SF-233-11 — un clip en cours finit son mot ; une phrase en cours, elle, est coupée.
    this.clearQueue();
    if (this.current?.text !== undefined) {
      this.stopCurrent();
    }
    const abort = new AbortController();
    const fetched = await this.fetchAudio(text, workspaceId, abort, hooks);
    if (this.clipPlaying) {
      await this.clipPlaying;
    }
    return this.play(fetched, abort, hooks, true, text);
  }

  /**
   * F-233 / SF-233-03 (D4) — met une phrase dans la **file** : elle sera dite après celles qui la précèdent, et sa
   * synthèse est demandée dès que la phrase d'avant joue (préchargement d'une phrase). Coupe une phrase isolée en
   * cours (l'accusé), jamais la file.
   */
  enqueue(text: string, workspaceId: string | null = null, hooks?: SpeakHooks): void {
    if (!this.queueRunning && this.queue.length === 0) {
      this.queueError = null;
      // F-233 / SF-233-11 — un clip (formule courte) n'est pas coupé au milieu d'un mot : la file attend sa fin.
      if (this.current?.text !== undefined) {
        this.stopCurrent();
      }
    }
    this.queue.push({ text, workspaceId, hooks, abort: new AbortController(), url: null });
    this.prefetch();
    if (!this.queueRunning) {
      void this.runQueue();
    }
  }

  /** SF-233-03 — se résout quand la file est vide (tout dit, ou interrompu). */
  idle(): Promise<void> {
    if (!this.queueRunning && this.queue.length === 0) {
      return Promise.resolve();
    }
    return new Promise((resolve) => this.idleWaiters.push(resolve));
  }

  /** SF-233-03 — ce qui a arrêté la dernière file (voix absente, plafond, échec), ou `null`. */
  get lastQueueError(): JarvisVoiceError | null {
    return this.queueError;
  }

  private prefetch(): void {
    for (const item of this.queue.slice(0, 2)) {
      if (!item.url) {
        item.url = this.fetchAudio(item.text, item.workspaceId, item.abort, item.hooks);
        item.url.catch(() => undefined);
      }
    }
  }

  private async runQueue(): Promise<void> {
    this.queueRunning = true;
    while (this.queue.length > 0) {
      const item = this.queue[0];
      this.prefetch();
      try {
        const fetched = await item.url!;
        if (this.queue[0] !== item) {
          continue;
        }
        if (this.clipPlaying) {
          await this.clipPlaying;
        }
        if (this.queue[0] !== item) {
          continue;
        }
        await this.play(fetched, item.abort, item.hooks, true, item.text);
      } catch (error) {
        if (this.queue[0] === item) {
          // Voix absente ou plafond : inutile d'essayer les suivantes, elles restent écrites.
          this.queueError = (error as JarvisVoiceError) ?? 'echec';
          this.clearQueue();
          break;
        }
      }
      if (this.queue[0] === item) {
        this.queue.shift();
      }
    }
    this.queueRunning = false;
    const waiters = this.idleWaiters;
    this.idleWaiters = [];
    waiters.forEach((resolve) => resolve());
  }

  private clearQueue(): void {
    const pending = this.queue;
    this.queue = [];
    pending.forEach((item) => item.abort.abort());
  }

  /**
   * Demande l'audio d'une phrase ; rend une URL jouable (flux MediaSource si possible). F-250 / SF-250-02 : si
   * l'alignement est demandé et que la passerelle répond en NDJSON, l'audio (base64) va au même `SourceBuffer` et
   * l'alignement est gardé à côté ; sinon le mp3 de toujours (repli amplitude).
   */
  private async fetchAudio(text: string, workspaceId: string | null, abort: AbortController,
                           hooks?: SpeakHooks): Promise<Fetched> {
    const token = this.auth.token();
    let response: Response;
    try {
      response = await fetch('/api/jarvis/speech', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: this.alignedVoice ? `${NDJSON}, audio/mpeg` : 'audio/mpeg',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ text, ...(workspaceId ? { workspaceId } : {}) }),
        signal: abort.signal,
      });
    } catch {
      throw (abort.signal.aborted ? 'interrompu' : 'echec') as JarvisVoiceError;
    }
    if (!response.ok) {
      throw voiceErrorOf(response.status);
    }
    hooks?.onFirstByte?.();
    if ((response.headers.get('Content-Type') ?? '').includes(NDJSON)) {
      const alignment: Alignment = { chars: [], starts: [], ends: [] };
      const url = canStream(response) ? this.streamAligned(response, alignment)
        : URL.createObjectURL(await readAligned(response, alignment));
      return { url, alignment };
    }
    return {
      url: canStream(response) ? this.streamInto(response) : URL.createObjectURL(await response.blob()),
      alignment: null,
    };
  }

  /**
   * F-233 / SF-233-02 — charge les clips de réaction (phrases fixes, voix de Jarvis) pour les jouer **localement**,
   * sans appel réseau, au moment de réagir. Best-effort : un clip absent (voix non configurée, plafond) est ignoré.
   */
  async preloadClips(names: readonly string[]): Promise<void> {
    const token = this.auth.token();
    await Promise.all(names.filter((name) => !this.clips.has(name)).map(async (name) => {
      try {
        const response = await fetch(`/api/jarvis/clips/${encodeURIComponent(name)}`, {
          headers: { Accept: 'audio/mpeg', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        });
        if (response.ok) {
          this.clips.set(name, URL.createObjectURL(await response.blob()));
        }
      } catch {
        // Pas de clip : Jarvis réagira par la phrase suivante.
      }
    }));
  }

  /** SF-233-02 — le clip est-il chargé ? */
  hasClip(name: string): boolean {
    return this.clips.has(name);
  }

  /**
   * SF-233-02 — joue un clip chargé (coupe la phrase en cours). Rend `false` sans rien jouer si le clip n'est pas
   * chargé ; la promesse se résout à la fin du clip ou à son interruption.
   */
  async playClip(name: string, hooks?: SpeakHooks): Promise<boolean> {
    const url = this.clips.get(name);
    if (!url) {
      return false;
    }
    this.stopCurrent();
    const playing = this.play({ url, alignment: this.clipAlignments.get(name) ?? null }, new AbortController(), hooks,
      false);
    const settled = playing.then(() => undefined, () => undefined);
    this.clipPlaying = settled;
    void settled.then(() => {
      if (this.clipPlaying === settled) {
        this.clipPlaying = null;
      }
    });
    try {
      await playing;
      return true;
    } catch {
      return false;
    }
  }

  /** Joue `url` ; une seule lecture à la fois. `revoke` : l'URL est rendue à la fin (une phrase, pas un clip). */
  private play(fetched: Fetched, abort: AbortController, hooks: SpeakHooks | undefined, revoke: boolean,
               text?: string): Promise<void> {
    const { url, alignment } = fetched;
    const audio = new Audio();
    audio.src = url;
    this.connect(audio);
    this.duck(false);
    return new Promise<void>((resolve, reject) => {
      const playback: Playback = {
        audio, abort, url, text, alignment,
        done: (error) => {
          if (!error && text) {
            this.heard.push(text);
          }
          if (this.current === playback) {
            this.current = null;
          }
          audio.pause();
          if (revoke) {
            URL.revokeObjectURL(url);
          }
          // Une interruption n'est pas un échec : la phrase s'arrête, l'écran continue.
          if (error && error !== 'interrompu') {
            reject(error);
          } else {
            resolve();
          }
        },
      };
      this.current = playback;
      audio.onplaying = () => hooks?.onPlaying?.();
      audio.onended = () => playback.done();
      audio.onerror = () => playback.done('echec');
      audio.play().catch(() => playback.done('echec'));
    });
  }

  /**
   * F-233 / SF-233-08 — baisse (ou rétablit) le volume de la voix : un humain semble parler, Jarvis se fait discret
   * avant de décider s'il se tait.
   */
  duck(on: boolean): void {
    const target = on ? DUCK_GAIN : 1;
    if (this.gain && this.context) {
      this.gain.gain.setTargetAtTime(target, this.context.currentTime, 0.03);
    } else if (this.current) {
      this.current.audio.volume = target;
    }
  }

  /** SF-233-08 — oublie ce qui a été entendu (une nouvelle réponse commence). */
  resetHeard(): void {
    this.heard = [];
  }

  /**
   * SF-233-08 (D8, D10) — **la coupure** : arrête la phrase en cours et vide la file, et rend ce qui a été entendu et
   * ce qu'il restait à dire. `null` si Jarvis ne disait rien.
   */
  interrupt(): CutSnapshot | null {
    const playback = this.current;
    // La phrase jouée depuis la file y reste en tête tant qu'elle joue : on ne la compte qu'une fois.
    const pending = playback?.text && this.queue[0]?.text === playback.text ? this.queue.slice(1) : this.queue;
    const queued = pending.map((item) => item.text);
    if (!playback?.text && queued.length === 0) {
      this.stop();
      return null;
    }
    const partial = playback?.text
      ? heardPart(playback.text, playback.audio.currentTime || 0, playback.audio.duration) : '';
    const snapshot: CutSnapshot = {
      heard: [...this.heard, partial].filter((t) => t.length > 0).join(' '),
      resume: [...(playback?.text ? [playback.text] : []), ...queued],
    };
    this.stop();
    return snapshot;
  }

  /** Coupe la phrase en cours **et vide la file** (interruption). Sans effet s'il n'y a rien. */
  stop(): void {
    this.clearQueue();
    this.stopCurrent();
  }

  /** Coupe la seule phrase en cours. */
  private stopCurrent(): void {
    const playback = this.current;
    if (playback) {
      playback.abort.abort();
      playback.done('interrompu');
    }
  }

  /** Le niveau sonore de la phrase jouée, de 0 à 1 (0 en silence). */
  level(): number {
    if (!this.analyser || !this.samples || !this.current) {
      return 0;
    }
    this.analyser.getByteTimeDomainData(this.samples);
    return rmsLevel(this.samples);
  }

  private ensureContext(): AudioContext | null {
    if (this.context) {
      return this.context;
    }
    const Ctor = typeof window !== 'undefined'
      ? (window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext)
      : undefined;
    if (!Ctor) {
      return null;
    }
    try {
      this.context = new Ctor();
      this.analyser = this.context.createAnalyser();
      this.analyser.fftSize = 512;
      this.samples = new Uint8Array(this.analyser.fftSize);
      // SF-233-08 — analyseur → gain → sortie : la baisse de volume passe par le gain.
      this.gain = this.context.createGain();
      this.analyser.connect(this.gain);
      this.gain.connect(this.context.destination);
    } catch {
      this.context = null;
    }
    return this.context;
  }

  /** Branche l'audio sur l'analyseur (l'orbe lit son niveau) ; sans contexte audio, il joue directement. */
  private connect(audio: HTMLAudioElement): void {
    const context = this.ensureContext();
    if (!context || !this.analyser) {
      return;
    }
    try {
      context.createMediaElementSource(audio).connect(this.analyser);
    } catch {
      // Déjà branché ou refusé : la voix joue quand même, l'orbe reste calme.
    }
  }

  /**
   * F-250 / SF-250-02 — lit le flux NDJSON au fil de son arrivée : l'audio de chaque ligne va au `SourceBuffer`
   * (comme l'mp3), son alignement s'ajoute à `alignment`. Une ligne illisible est ignorée (repli amplitude).
   */
  private streamAligned(response: Response, alignment: Alignment): string {
    const source = new MediaSource();
    const url = URL.createObjectURL(source);
    source.addEventListener('sourceopen', () => {
      void (async () => {
        try {
          const buffer = source.addSourceBuffer('audio/mpeg');
          const reader = response.body!.getReader();
          const lines = new NdjsonLines();
          for (;;) {
            const { value, done } = await reader.read();
            for (const line of done ? lines.end() : lines.push(value)) {
              const audio = takeAlignedLine(line, alignment);
              if (audio && audio.length > 0) {
                await appendChunk(buffer, audio);
              }
            }
            if (done) {
              break;
            }
          }
          if (source.readyState === 'open') {
            source.endOfStream();
          }
        } catch {
          if (source.readyState === 'open') {
            source.endOfStream('network');
          }
        }
      })();
    }, { once: true });
    return url;
  }

  /** Lit l'mp3 au fil de son arrivée : chaque morceau reçu est ajouté à la source pendant que l'audio joue. */
  private streamInto(response: Response): string {
    const source = new MediaSource();
    const url = URL.createObjectURL(source);
    source.addEventListener('sourceopen', () => {
      void (async () => {
        try {
          const buffer = source.addSourceBuffer('audio/mpeg');
          const reader = response.body!.getReader();
          for (;;) {
            const { value, done } = await reader.read();
            if (done) {
              break;
            }
            await appendChunk(buffer, value);
          }
          if (source.readyState === 'open') {
            source.endOfStream();
          }
        } catch {
          if (source.readyState === 'open') {
            source.endOfStream('network');
          }
        }
      })();
    }, { once: true });
    return url;
  }
}

/** F-250 — le type du flux aligné. */
export const NDJSON = 'application/x-ndjson';

/** F-250 / SF-250-02 — découpe un flux d'octets en lignes NDJSON (les lignes peuvent arriver en morceaux). */
export class NdjsonLines {
  private readonly decoder = new TextDecoder();
  private pending = '';

  /** Ajoute des octets ; rend les lignes complètes (non vides). */
  push(bytes: Uint8Array | undefined): string[] {
    if (bytes) {
      this.pending += this.decoder.decode(bytes, { stream: true });
    }
    const parts = this.pending.split('\n');
    this.pending = parts.pop() ?? '';
    return parts.filter((line) => line.trim().length > 0);
  }

  /** Fin du flux : la dernière ligne, si elle n'avait pas de saut. */
  end(): string[] {
    const rest = (this.pending + this.decoder.decode()).trim();
    this.pending = '';
    return rest.length > 0 ? [rest] : [];
  }
}

/**
 * F-250 / SF-250-02 — une ligne `{a, c, s, e}` : ajoute son alignement (s'il est cohérent) à `alignment` et rend son
 * audio décodé ; `null` si la ligne est illisible.
 */
export function takeAlignedLine(line: string, alignment: Alignment): Uint8Array | null {
  let parsed: { a?: unknown; c?: unknown; s?: unknown; e?: unknown };
  try {
    parsed = JSON.parse(line);
  } catch {
    return null;
  }
  const { c, s, e } = parsed;
  if (Array.isArray(c) && Array.isArray(s) && Array.isArray(e) && c.length === s.length && s.length === e.length
      && c.every((x) => typeof x === 'string') && s.every(Number.isFinite) && e.every(Number.isFinite)) {
    for (let k = 0; k < c.length; k++) {
      alignment.chars.push(c[k] as string);
      alignment.starts.push(s[k] as number);
      alignment.ends.push(e[k] as number);
    }
  }
  return typeof parsed.a === 'string' ? base64Bytes(parsed.a) : null;
}

/** Décode du base64 (l'audio d'une ligne) ; vide si illisible. */
export function base64Bytes(text: string): Uint8Array {
  try {
    const binary = atob(text);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      out[i] = binary.charCodeAt(i);
    }
    return out;
  } catch {
    return new Uint8Array(0);
  }
}

/** Sans lecture en flux : lit tout le NDJSON, rend l'audio entier et remplit l'alignement. */
async function readAligned(response: Response, alignment: Alignment): Promise<Blob> {
  const lines = new NdjsonLines();
  const parts: Uint8Array[] = [];
  for (const line of [...lines.push(new Uint8Array(await response.arrayBuffer())), ...lines.end()]) {
    const audio = takeAlignedLine(line, alignment);
    if (audio) {
      parts.push(audio);
    }
  }
  return new Blob(parts as BlobPart[], { type: 'audio/mpeg' });
}

/** Le refus de la passerelle, en mots de l'écran. */
export function voiceErrorOf(status: number): JarvisVoiceError {
  return status === 429 ? 'budget' : status === 503 ? 'non-configure' : 'echec';
}

/** Le navigateur sait-il lire cet mp3 pendant qu'il arrive ? */
function canStream(response: Response): boolean {
  return !!response.body && typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported?.('audio/mpeg');
}

function appendChunk(buffer: SourceBuffer, chunk: Uint8Array): Promise<void> {
  return new Promise((resolve, reject) => {
    buffer.addEventListener('updateend', () => resolve(), { once: true });
    buffer.addEventListener('error', () => reject(new Error('append')), { once: true });
    buffer.appendBuffer(chunk as BufferSource);
  });
}

/** Niveau RMS d'un échantillon temporel (octets centrés sur 128), ramené de 0 à 1. */
export function rmsLevel(samples: Uint8Array): number {
  if (samples.length === 0) {
    return 0;
  }
  let sum = 0;
  for (const s of samples) {
    const v = (s - 128) / 128;
    sum += v * v;
  }
  return Math.min(1, Math.sqrt(sum / samples.length) * 3);
}
