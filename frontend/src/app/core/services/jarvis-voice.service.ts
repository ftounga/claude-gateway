import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from './auth.service';

/** Ce qui peut empêcher Jarvis de parler (F-231 / SF-231-01) — l'écran retombe alors sur le texte. */
export type JarvisVoiceError = 'budget' | 'non-configure' | 'echec' | 'interrompu';

/** L'état de la voix (F-231 / SF-231-01), montants en euros. */
export interface JarvisVoiceStatus {
  enabled: boolean;
  voice: string;
  monthlyBudgetEur: number;
  monthSpentEur: number;
  budgetReached: boolean;
}

/** F-233 / SF-233-01 — ce que la lecture signale au chronomètre du tour. */
export interface SpeakHooks {
  /** La réponse audio commence à arriver. */
  onFirstByte?: () => void;
  /** Le premier son est joué. */
  onPlaying?: () => void;
}

/** SF-233-03 — une phrase en file : son audio est demandé à l'avance (`url`). */
interface QueuedSentence {
  text: string;
  workspaceId: string | null;
  hooks?: SpeakHooks;
  abort: AbortController;
  url: Promise<string> | null;
}

/** Ce que l'écran garde d'une lecture : de quoi l'arrêter. */
interface Playback {
  audio: HTMLAudioElement;
  abort: AbortController;
  url: string;
  done: (error?: JarvisVoiceError) => void;
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
  private current: Playback | null = null;
  /** SF-233-03 — la file de phrases à dire, et l'attente de sa fin. */
  private queue: QueuedSentence[] = [];
  private queueRunning = false;
  private idleWaiters: (() => void)[] = [];
  /** SF-233-03 — ce qui a arrêté la file (voix absente, plafond), ou `null`. */
  private queueError: JarvisVoiceError | null = null;
  /** SF-233-02 — les clips de réaction chargés (URL locales), par nom. */
  private readonly clips = new Map<string, string>();

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
   * Dit `text` à voix haute. La promesse se résout à la fin de la phrase (ou à son interruption) ; elle est rejetée
   * avec un {@link JarvisVoiceError} si la voix n'a pas pu parler.
   */
  async speak(text: string, workspaceId: string | null = null, hooks?: SpeakHooks): Promise<void> {
    this.stop();
    const abort = new AbortController();
    const url = await this.fetchAudio(text, workspaceId, abort, hooks);
    return this.play(url, abort, hooks, true);
  }

  /**
   * F-233 / SF-233-03 (D4) — met une phrase dans la **file** : elle sera dite après celles qui la précèdent, et sa
   * synthèse est demandée dès que la phrase d'avant joue (préchargement d'une phrase). Coupe une phrase isolée en
   * cours (l'accusé), jamais la file.
   */
  enqueue(text: string, workspaceId: string | null = null, hooks?: SpeakHooks): void {
    if (!this.queueRunning && this.queue.length === 0) {
      this.queueError = null;
      this.stopCurrent();
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
        const url = await item.url!;
        if (this.queue[0] !== item) {
          continue;
        }
        await this.play(url, item.abort, item.hooks, true);
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

  /** Demande l'audio d'une phrase ; rend une URL jouable (flux MediaSource si possible). */
  private async fetchAudio(text: string, workspaceId: string | null, abort: AbortController,
                           hooks?: SpeakHooks): Promise<string> {
    const token = this.auth.token();
    let response: Response;
    try {
      response = await fetch('/api/jarvis/speech', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'audio/mpeg',
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
    return canStream(response) ? this.streamInto(response) : URL.createObjectURL(await response.blob());
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
    try {
      await this.play(url, new AbortController(), hooks, false);
      return true;
    } catch {
      return false;
    }
  }

  /** Joue `url` ; une seule lecture à la fois. `revoke` : l'URL est rendue à la fin (une phrase, pas un clip). */
  private play(url: string, abort: AbortController, hooks: SpeakHooks | undefined, revoke: boolean): Promise<void> {
    const audio = new Audio();
    audio.src = url;
    this.connect(audio);
    return new Promise<void>((resolve, reject) => {
      const playback: Playback = {
        audio, abort, url,
        done: (error) => {
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
      this.analyser.connect(this.context.destination);
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
