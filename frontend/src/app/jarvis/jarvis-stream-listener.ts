import { Injectable } from '@angular/core';

import { JarvisSttTicket, TurnTimingReport } from '../core/models/jarvis.models';
import { MIC_OPEN_TIMEOUT_MS, stopTracks, timed } from '../core/timed';

/**
 * **Écouter en flux** (F-233 / SF-233-05, D5, D6) — le navigateur parle **directement** au fournisseur de
 * transcription (WebSocket, jeton éphémère émis par la passerelle : la clé ne quitte jamais le serveur). Le micro
 * est capté en PCM 16 bits mono au débit demandé, envoyé par morceaux ; le texte partiel revient pendant la parole,
 * et la **fin de tour** est donnée par le fournisseur (`committed_transcript`) ou forcée par l'écran (`commit`).
 *
 * Protocole ElevenLabs Scribe v2 Realtime : `input_audio_chunk` (base64) vers le fournisseur ; `session_started`,
 * `partial_transcript`, `committed_transcript` en retour. Les fonctions pures sont exportées et testées seules.
 */

/**
 * F-258 / SF-258-01 — pourquoi une session ouverte s'est arrêtée : le type d'erreur du fournisseur (`message_type`,
 * liste fermée {@link STT_ERROR_TYPES}) et/ou la fermeture (code, raison). Jamais le texte libre de l'erreur.
 */
export interface StreamFailure {
  /** `queue_overflow`, `rate_limited`… ; `null` : fermeture sans message d'erreur. */
  type: string | null;
  code: number | null;
  reason: string | null;
}

/** Ce que l'écoute signale à l'écran. */
export interface StreamHandlers {
  /** Le texte provisoire de la phrase en cours. */
  onPartial(text: string): void;
  /** Un segment validé par le fournisseur (fin de tour). */
  onCommitted(text: string): void;
  /** La session ne peut pas continuer (réseau, refus, jeton) : l'écran retombe sur la dictée. */
  onError(failure?: StreamFailure): void;
  /**
   * F-258 / SF-258-01 — un ticket neuf pour **une** réouverture après une coupure du fournisseur (`queue_overflow`,
   * fermeture serveur…). `previous`/`seconds` : la session coupée, à clore côté passerelle. Absent : pas de réouverture.
   */
  renewTicket?(previous: JarvisSttTicket, seconds: number): Promise<JarvisSttTicket>;
  /** F-258 / SF-258-01 — le journal : `stream_error`, `stream_close`, `stream_reopen` (détail technique, sans contenu). */
  onTrace?(event: string, detail: string): void;
}

/**
 * F-233 / SF-233-13 — pourquoi l'écoute n'a pas été faite en flux (liste fermée, même liste que la passerelle
 * `JarvisLatencyService.STREAM_FALLBACKS`). Jamais de contenu.
 */
export type StreamFallback = NonNullable<TurnTimingReport['streamFallback']>;

/** SF-233-13 — un échec d'ouverture du flux, classé. */
export class StreamOpenError extends Error {
  constructor(readonly kind: StreamFallback) {
    super(kind);
  }
}

/** SF-233-13 — la raison d'un refus de `/jarvis/stt-session` selon son statut HTTP. */
export function ticketFailureOf(status: number | null | undefined): StreamFallback {
  if (status === 503) {
    return 'ticket_indisponible';
  }
  if (status === 401 || status === 403 || status === 429) {
    return 'ticket_refuse';
  }
  return 'ticket_echec';
}

/** SF-233-13 — la raison d'un échec d'ouverture (micro, session du fournisseur, capture). */
export function streamFailureOf(error: unknown): StreamFallback {
  if (error instanceof StreamOpenError) {
    return error.kind;
  }
  const message = error instanceof Error ? error.message : '';
  switch (message) {
    case 'timeout': return 'ouverture_delai';
    case 'refus': return 'ouverture_refusee';
    case 'reseau': return 'ouverture_reseau';
    case 'ferme': return 'ouverture_fermee';
    default: return 'autre';
  }
}

/** Les contraintes d'écoute (D8) : sans elles, la voix de Jarvis au haut-parleur s'entend elle-même. */
export const LISTEN_AUDIO: MediaTrackConstraints = {
  echoCancellation: true,
  noiseSuppression: true,
  autoGainControl: true,
};

/** Un message du fournisseur, lu. */
export interface SttMessage {
  kind: 'started' | 'partial' | 'committed' | 'error' | 'other';
  text: string;
  /** F-258 / SF-258-01 — pour une erreur : son type documenté, `error` (type générique), ou `other` (inconnu). */
  errorType?: string;
}

/**
 * F-258 / SF-258-01 — les types d'erreur documentés de Scribe v2 Realtime (référence des événements :
 * https://elevenlabs.io/docs/eleven-api/guides/how-to/speech-to-text/realtime/event-reference). Le fournisseur
 * envoie l'un d'eux puis ferme la session.
 */
export const STT_ERROR_TYPES = ['error', 'auth_error', 'quota_exceeded', 'commit_throttled', 'unaccepted_terms',
  'rate_limited', 'queue_overflow', 'resource_exhausted', 'session_time_limit_exceeded', 'input_error',
  'invalid_request', 'chunk_size_exceeded', 'insufficient_audio_activity', 'transcriber_error'] as const;

/**
 * F-258 / SF-258-01 — les coupures **passagères** (charge, débit, durée) : une session neuve a ses chances. Les autres
 * (jeton, quota, conditions, requête invalide) échoueraient pareil : repli direct.
 */
const TRANSIENT_ERRORS = new Set<string>(['error', 'commit_throttled', 'rate_limited', 'queue_overflow',
  'resource_exhausted', 'session_time_limit_exceeded', 'insufficient_audio_activity', 'transcriber_error']);

/** F-258 / SF-258-01 — vrai si une réouverture a du sens après cette coupure (fermeture sans erreur : oui). */
export function isTransientFailure(failure: StreamFailure): boolean {
  return failure.type === null || TRANSIENT_ERRORS.has(failure.type);
}

/** F-258 / SF-258-01 — le détail de journal d'une coupure : `type=… code=… reason=…` (raison bornée, sans espace). */
export function failureDetail(failure: StreamFailure): string {
  const parts = [`type=${failure.type ?? '-'}`];
  if (failure.code !== null) {
    parts.push(`code=${failure.code}`);
  }
  if (failure.reason) {
    parts.push(`reason=${failure.reason.replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 40)}`);
  }
  return parts.join(' ');
}

/** Lit un message du fournisseur ; un message illisible ne casse rien (`other`). */
export function parseSttMessage(raw: unknown): SttMessage {
  let data: { message_type?: unknown; text?: unknown; error?: unknown };
  try {
    data = typeof raw === 'string' ? JSON.parse(raw) : {};
  } catch {
    return { kind: 'other', text: '' };
  }
  const type = typeof data.message_type === 'string' ? data.message_type : '';
  const text = typeof data.text === 'string' ? data.text : '';
  if (type === 'session_started') {
    return { kind: 'started', text: '' };
  }
  if (type === 'partial_transcript') {
    return { kind: 'partial', text };
  }
  if (type.startsWith('committed_transcript')) {
    return { kind: 'committed', text };
  }
  // F-258 / SF-258-01 — tous les types d'erreur documentés (dont `queue_overflow`, jusque-là lu « autre ») ;
  // F-233 / SF-233-13 — et tout message porteur d'un `error` non vide.
  if ((STT_ERROR_TYPES as readonly string[]).includes(type)) {
    return { kind: 'error', text: '', errorType: type };
  }
  if (type.includes('error') || (typeof data.error === 'string' && data.error !== '')) {
    return { kind: 'error', text: '', errorType: 'other' };
  }
  return { kind: 'other', text: '' };
}

/** F-258 / SF-258-01 — la durée d'un bloc envoyé au fournisseur (sa recommandation : ≈ 100 ms, voir la mini-spec). */
export const CHUNK_MS = 100;
/**
 * F-258 / SF-258-01 — l'écart minimal entre deux envois : un retard (tampon d'ouverture, rafale d'un navigateur
 * occupé, rattrapage après réouverture) part au plus à 4× le temps réel, jamais d'un coup.
 */
export const SEND_GAP_MS = 25;
/** F-258 / SF-258-01 — au plus autant de messages d'un coup quand l'envoi rattrape une minuterie en retard. */
export const SEND_CATCH_UP = 4;

/**
 * F-258 / SF-258-01 — **regroupe** les petits blocs du micro (128 échantillons ≈ 2,7 ms à 48 kHz avec AudioWorklet)
 * en blocs de {@link CHUNK_MS}, au débit du micro : le rééchantillonnage se fait sur un bloc entier (plus
 * d'échantillons perdus à chaque petit bloc).
 */
export class AudioChunker {
  private parts: Float32Array[] = [];
  private size = 0;
  private rate = 0;

  constructor(private readonly chunkMs = CHUNK_MS) {}

  /** Ajoute un bloc ; rend les blocs complets (0, 1 ou plusieurs). Un changement de débit vide d'abord le reste. */
  add(samples: Float32Array, rate: number): { samples: Float32Array; rate: number }[] {
    const out: { samples: Float32Array; rate: number }[] = [];
    if (this.rate !== rate && this.size > 0) {
      out.push(this.flush()!);
    }
    this.rate = rate;
    this.parts.push(samples);
    this.size += samples.length;
    const target = Math.max(1, Math.round(rate * this.chunkMs / 1000));
    while (this.size >= target) {
      out.push({ samples: this.take(target), rate });
    }
    return out;
  }

  /** Le reste (bloc incomplet), ou `null` s'il n'y en a pas. */
  flush(): { samples: Float32Array; rate: number } | null {
    return this.size > 0 ? { samples: this.take(this.size), rate: this.rate } : null;
  }

  get pending(): number {
    return this.size;
  }

  private take(count: number): Float32Array {
    const out = new Float32Array(count);
    let at = 0;
    while (at < count) {
      const head = this.parts[0];
      const need = count - at;
      if (head.length <= need) {
        out.set(head, at);
        at += head.length;
        this.parts.shift();
      } else {
        out.set(head.subarray(0, need), at);
        this.parts[0] = head.subarray(need);
        at += need;
      }
    }
    this.size -= count;
    return out;
  }
}

/** F-258 / SF-258-01 — les minuteries de l'envoi lissé (remplacées en test). */
export interface OutboxTimers {
  now(): number;
  set(fn: () => void, ms: number): unknown;
  clear(handle: unknown): void;
}

const REAL_TIMERS: OutboxTimers = {
  now: () => performance.now(),
  set: (fn, ms) => setTimeout(fn, ms),
  clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

/**
 * F-258 / SF-258-01 — **l'envoi lissé** : les messages partent dans l'ordre, au plus un tous les {@link SEND_GAP_MS} ;
 * un message arrivé quand la file est vide et l'écart écoulé part tout de suite (le flux vivant n'attend pas).
 */
export class PacedOutbox {
  private queue: string[] = [];
  /** L'heure à partir de laquelle le prochain message peut partir. */
  private next = Number.NEGATIVE_INFINITY;
  private timer: unknown = null;

  constructor(private readonly write: (message: string) => void, private readonly gapMs = SEND_GAP_MS,
              private readonly timers: OutboxTimers = REAL_TIMERS) {}

  enqueue(message: string): void {
    this.queue.push(message);
    this.drain();
  }

  /** Oublie ce qui n'est pas parti (session coupée). */
  clear(): void {
    this.queue = [];
    if (this.timer !== null) {
      this.timers.clear(this.timer);
      this.timer = null;
    }
  }

  get waiting(): number {
    return this.queue.length;
  }

  /** @param late appelé par la minuterie : la file attendait (rattrapage permis). */
  private drain(late = false): void {
    if (this.timer !== null) {
      return;
    }
    while (this.queue.length > 0) {
      const now = this.timers.now();
      if (now < this.next) {
        this.timer = this.timers.set(() => {
          this.timer = null;
          this.drain(true);
        }, this.next - now);
        return;
      }
      // Au repos : le message part et le suivant attend un écart. En retard (minuterie tardive d'un fil principal
      // occupé) : rattrapage borné à SEND_CATCH_UP messages d'un coup — la file ne prend jamais de retard sur le
      // temps réel, et ne repart jamais en rafale.
      this.next = (late ? Math.max(this.next, now - (SEND_CATCH_UP - 1) * this.gapMs) : now) + this.gapMs;
      this.write(this.queue.shift()!);
    }
  }
}

/** Rééchantillonne (moyenne par paquet) de `inRate` vers `outRate` ; inchangé si `outRate >= inRate`. */
export function downsample(input: Float32Array, inRate: number, outRate: number): Float32Array {
  if (outRate >= inRate) {
    return input;
  }
  const ratio = inRate / outRate;
  const length = Math.floor(input.length / ratio);
  const out = new Float32Array(length);
  for (let i = 0; i < length; i++) {
    const start = Math.floor(i * ratio);
    const end = Math.min(input.length, Math.floor((i + 1) * ratio));
    let sum = 0;
    for (let j = start; j < end; j++) {
      sum += input[j];
    }
    out[i] = end > start ? sum / (end - start) : 0;
  }
  return out;
}

/** Float [-1, 1] → PCM 16 bits petit-boutiste. */
export function toPcm16(input: Float32Array): Uint8Array {
  const bytes = new Uint8Array(input.length * 2);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < input.length; i++) {
    const s = Math.max(-1, Math.min(1, input[i]));
    view.setInt16(i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return bytes;
}

/** Octets → base64. */
export function toBase64(bytes: Uint8Array): string {
  let binary = '';
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) {
    binary += String.fromCharCode(...bytes.subarray(i, i + step));
  }
  return btoa(binary);
}

/** L'URL de session avec son jeton. */
export function sessionUrl(ticket: Pick<JarvisSttTicket, 'url' | 'token'>): string {
  return `${ticket.url}${ticket.url.includes('?') ? '&' : '?'}token=${encodeURIComponent(ticket.token)}`;
}

/** Le module de capture (AudioWorklet), chargé depuis une URL locale : il recopie chaque bloc du micro. */
const TAP_SOURCE = `class JarvisPcmTap extends AudioWorkletProcessor {
  process(inputs) { const ch = inputs[0] && inputs[0][0]; if (ch) { this.port.postMessage(ch.slice(0)); } return true; }
}
registerProcessor('jarvis-pcm-tap', JarvisPcmTap);`;

/** F-239 / SF-239-04 — l'audio gardé pour la retranscription ne dépasse pas cette durée. */
export const MAX_KEPT_SECONDS = 60;

/** PCM 16 bits petit-boutiste mono ⇒ WAV. */
export function wavBlob(pcm: Uint8Array, sampleRate: number): Blob {
  const header = new ArrayBuffer(44);
  const view = new DataView(header);
  const text = (offset: number, value: string) => {
    for (let i = 0; i < value.length; i++) {
      view.setUint8(offset + i, value.charCodeAt(i));
    }
  };
  text(0, 'RIFF');
  view.setUint32(4, 36 + pcm.length, true);
  text(8, 'WAVE');
  text(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  text(36, 'data');
  view.setUint32(40, pcm.length, true);
  return new Blob([header, pcm], { type: 'audio/wav' });
}

/** Le débit de la pré-écoute rendue par le mot d'éveil (`WAKE_RATE`). */
export const PRE_ROLL_RATE = 16_000;

/** Au-delà, une session qui ne démarre pas est abandonnée (repli sur la dictée). */
export const START_TIMEOUT_MS = 3_000;
/** F-233 / SF-233-16 — le chargement du module de capture (AudioWorklet), borné. */
export const WORKLET_TIMEOUT_MS = 4_000;

/** F-251 / SF-251-02 — le son gardé avant chaque écoute (le temps que la session s'ouvre, le début n'est plus perdu). */
export const PRE_ROLL_MS = 1_500;
/** F-251 / SF-251-02 — après la voix de Jarvis, le son reste jeté autant de temps (queue d'écho). */
export const ECHO_HOLD_MS = 150;

/** F-251 / SF-251-02 — un tampon circulaire d'échantillons : les plus anciens sont oubliés au-delà de `capacity`. */
export class PreRollRing {
  private blocks: Float32Array[] = [];
  private size = 0;

  constructor(private readonly capacity: number) {}

  push(block: Float32Array): void {
    this.blocks.push(block);
    this.size += block.length;
    while (this.blocks.length > 1 && this.size - this.blocks[0].length >= this.capacity) {
      this.size -= this.blocks.shift()!.length;
    }
  }

  /** Vide le tampon et rend son contenu, dans l'ordre (au plus `capacity` échantillons). */
  take(): Float32Array {
    const out = new Float32Array(Math.min(this.size, this.capacity));
    let skip = this.size - out.length;
    let at = 0;
    for (const block of this.blocks) {
      const part = skip > 0 ? block.subarray(Math.min(skip, block.length)) : block;
      skip = Math.max(0, skip - block.length);
      out.set(part, at);
      at += part.length;
    }
    this.clear();
    return out;
  }

  clear(): void {
    this.blocks = [];
    this.size = 0;
  }

  get length(): number {
    return Math.min(this.size, this.capacity);
  }
}

/** F-251 / SF-251-02 — ce que le micro gardé branche sur le module audio. */
export interface CaptureGraph {
  readonly rate: number;
  /** Vrai tant que le contexte audio et une piste au moins sont vivants. */
  alive(): boolean;
  /** Relance le contexte si le navigateur l'a suspendu. */
  resume(): void;
  close(): void;
}

/**
 * F-251 / SF-251-02 — **le micro de la conversation** : ouvert une fois, il alimente un tampon circulaire entre deux
 * écoutes et la session en cours pendant une écoute. Vidé tant que `silenced()` est vrai (Jarvis parle) et
 * {@link ECHO_HOLD_MS} après : la voix de Jarvis ne part jamais dans une session.
 */
export class ConversationMic {
  private readonly ring: PreRollRing;
  private sink: ((samples: Float32Array, rate: number) => void) | null = null;
  private holdUntil = 0;

  constructor(readonly stream: MediaStream, private readonly graph: CaptureGraph,
              private readonly silenced: () => boolean) {
    this.ring = new PreRollRing(Math.round(graph.rate * PRE_ROLL_MS / 1000));
  }

  /** Un bloc du module audio. */
  feed(samples: Float32Array): void {
    if (this.sink) {
      this.sink(samples, this.graph.rate);
      return;
    }
    const now = performance.now();
    if (this.silenced()) {
      this.ring.clear();
      this.holdUntil = now + ECHO_HOLD_MS;
      return;
    }
    if (now >= this.holdUntil) {
      this.ring.push(samples);
    }
  }

  /** Branche la session et rend le tampon (16 kHz) à envoyer d'abord : rien n'est perdu entre les deux. */
  attach(sink: (samples: Float32Array, rate: number) => void): Float32Array {
    const buffered = this.ring.take();
    this.sink = sink;
    this.graph.resume();
    return downsample(buffered, this.graph.rate, PRE_ROLL_RATE);
  }

  /** La session est finie : le tampon repart vide (la fin de la phrase précédente n'est jamais renvoyée). */
  detach(): void {
    this.sink = null;
    this.ring.clear();
  }

  get alive(): boolean {
    return this.graph.alive() && this.stream.getTracks().some((track) => track.readyState === 'live');
  }

  close(): void {
    this.sink = null;
    this.ring.clear();
    this.graph.close();
    this.stream.getTracks().forEach((track) => track.stop());
  }
}

/** F-251 / SF-251-02 — le micro à garder pour la conversation (`null`/absent à `open` : un micro par écoute). */
export interface MicKeep {
  /** Jarvis parle-t-il ? */
  silenced(): boolean;
  /** Journal : `open ms=…`, `reuse`, `fail`, `close`. */
  report(detail: string): void;
}

/**
 * Une session d'écoute en flux, sur un flux micro déjà ouvert. `start()` se résout quand le fournisseur a accepté la
 * session ; il est rejeté sinon (l'écran retombe alors sur la dictée).
 *
 * F-258 / SF-258-01 — le son part **par blocs de {@link CHUNK_MS}** ({@link AudioChunker}), **lissé**
 * ({@link PacedOutbox}) : plus de rafale de petits messages qui fait fermer la session (`queue_overflow`). Une coupure
 * passagère du fournisseur rouvre **une fois** la session avec un ticket neuf et renvoie le son non validé.
 */
export class StreamingListener {
  private socket: WebSocket | null = null;
  private context: AudioContext | null = null;
  private node: AudioNode | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private tapUrl: string | null = null;
  private openedAt = 0;
  private closed = false;
  /** F-239 / SF-239-04 — l'audio envoyé (pré-écoute comprise), gardé pour la retranscription de l'énoncé complet. */
  private readonly kept: Uint8Array[] = [];
  private keptBytes = 0;
  /** F-258 / SF-258-01 — octets gardés depuis le début (oubliés compris) : la position dans l'énoncé. */
  private keptTotal = 0;
  /** F-258 / SF-258-01 — la position (octets gardés) du prochain octet à partir vers la session. */
  private sentTotal = 0;
  /** F-258 / SF-258-01 — la position (octets partis) au dernier segment validé : la suite est renvoyée à la réouverture. */
  private committedAt = 0;
  private readonly chunker = new AudioChunker();
  private readonly outbox: PacedOutbox;
  /** F-258 / SF-258-01 — les messages en file et les octets d'audio qu'ils portent (même ordre). */
  private readonly outboxBytes: number[] = [];
  /** F-258 / SF-258-01 — la session en cours est coupée (réouverture en cours ou repli signalé). */
  private failed = false;
  private reopening = false;
  private commitPending = false;
  /** F-258 / SF-258-01 — le micro gardé a été branché sur cette session. */
  private attached = false;
  /** F-251 / SF-251-02 — la durée du tampon du micro gardé envoyée en tête de cette session (ms). */
  preRollMs = 0;
  /** F-258 / SF-258-01 — réouvertures faites (au plus une). */
  reopens = 0;

  constructor(private ticket: JarvisSttTicket, private readonly stream: MediaStream,
              private readonly handlers: StreamHandlers, private readonly mic: ConversationMic | null = null,
              timers?: OutboxTimers) {
    this.outbox = new PacedOutbox((message) => this.write(message), SEND_GAP_MS, timers);
  }

  /**
   * @param preRoll F-239 / SF-239-01 (D2) — l'audio de la phrase qui contenait « Jarvis » (16 kHz mono), lu une fois la
   *                session acceptée et envoyé **avant** le micro ; `null`/absent : rien avant le micro.
   */
  async start(preRoll?: (() => Float32Array | null) | null): Promise<void> {
    this.openedAt = performance.now();
    await this.connect();
    if (this.mic) {
      // F-251 / SF-251-02 — micro gardé : le tampon d'abord, puis le son vivant (même tour de boucle, rien n'est perdu).
      const buffered = this.mic.attach((samples, rate) => this.push(samples, rate));
      this.attached = true;
      const wake = this.readPreRoll(preRoll);
      if (wake && wake.length > 0) {
        this.sendPreRoll(wake);
      } else {
        this.preRollMs = Math.round(buffered.length / PRE_ROLL_RATE * 1000);
        this.sendPreRoll(buffered);
      }
      return;
    }
    this.sendPreRoll(this.readPreRoll(preRoll));
    try {
      await this.capture();
    } catch {
      throw new StreamOpenError('capture');
    }
  }

  /**
   * Ouvre une session avec le ticket courant ; résolu à `session_started`. Avant : rejeté (`timeout`, `refus`,
   * `reseau`, `ferme`). Après : toute coupure passe par {@link fail}.
   */
  private connect(): Promise<void> {
    const socket = new WebSocket(sessionUrl(this.ticket));
    this.socket = socket;
    return new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout')), START_TIMEOUT_MS);
      let started = false;
      let errorType: string | null = null;
      socket.onmessage = (event) => {
        const message = parseSttMessage(event.data);
        if (message.kind === 'error') {
          errorType = message.errorType ?? 'other';
          this.trace('stream_error', failureDetail({ type: errorType, code: null, reason: null }));
          if (!started) {
            clearTimeout(timer);
            reject(new Error('refus'));
          } else {
            this.fail(socket, { type: errorType, code: null, reason: null });
          }
          return;
        }
        if (socket !== this.socket) {
          return;
        }
        if (message.kind === 'started' && !started) {
          started = true;
          clearTimeout(timer);
          resolve();
        } else if (message.kind === 'partial') {
          this.handlers.onPartial(message.text);
        } else if (message.kind === 'committed') {
          this.committedAt = this.sentTotal;
          this.handlers.onCommitted(message.text);
        }
      };
      socket.onerror = () => {
        if (!started) {
          clearTimeout(timer);
          reject(new Error('reseau'));
        }
      };
      socket.onclose = (event?: CloseEvent) => {
        const failure: StreamFailure = { type: errorType, code: event?.code ?? null, reason: event?.reason || null };
        if (!this.closed || socket !== this.socket) {
          this.trace('stream_close', failureDetail(failure));
        }
        if (!started) {
          clearTimeout(timer);
          reject(new Error('ferme'));
        } else {
          this.fail(socket, failure);
        }
      };
    });
  }

  /** F-258 / SF-258-01 — la session en cours est coupée : une réouverture si elle a du sens, sinon le repli. */
  private fail(socket: WebSocket, failure: StreamFailure): void {
    if (socket !== this.socket || this.closed || this.failed) {
      return;
    }
    this.failed = true;
    this.socket = null;
    this.clearOutbox();
    // Le fournisseur ferme lui-même après son message d'erreur ; sinon, on la ferme un peu plus tard.
    setTimeout(() => {
      try {
        socket.close();
      } catch {
        // déjà fermée
      }
    }, 5_000);
    if (this.reopens === 0 && this.handlers.renewTicket && isTransientFailure(failure)) {
      void this.reopen(failure);
      return;
    }
    this.handlers.onError(failure);
  }

  /** F-258 / SF-258-01 — une seule réouverture : ticket neuf, session neuve, puis le son non validé renvoyé (lissé). */
  private async reopen(failure: StreamFailure): Promise<void> {
    this.reopens++;
    this.reopening = true;
    const startedAt = performance.now();
    const seconds = Math.max(0, Math.round((startedAt - this.openedAt) / 1000));
    const rate = this.ticket.sampleRate;
    try {
      const ticket = await this.handlers.renewTicket!(this.ticket, seconds);
      if (this.closed) {
        return;
      }
      this.ticket = ticket;
      this.openedAt = performance.now();
      await this.connect();
    } catch (error) {
      if (!this.closed) {
        this.reopening = false;
        this.trace('stream_reopen', `fail ${error instanceof Error ? error.message.slice(0, 20) : 'autre'}`);
        this.handlers.onError(failure);
      }
      return;
    }
    if (this.closed) {
      return;
    }
    // Le son non validé (depuis le dernier segment validé) repart en tête de la session neuve.
    const from = this.ticket.sampleRate === rate ? Math.max(this.committedAt, this.keptTotal - this.keptBytes)
      : this.keptTotal;
    const resend = this.keptSince(from);
    this.sentTotal = from;
    this.committedAt = from;
    this.failed = false;
    this.reopening = false;
    this.trace('stream_reopen',
      `ok ms=${Math.round(performance.now() - startedAt)} resend_ms=${Math.round(resend.length / 2 / rate * 1000)}`);
    const step = Math.max(2, Math.round(this.ticket.sampleRate * CHUNK_MS / 1000) * 2);
    for (let i = 0; i < resend.length; i += step) {
      this.enqueueAudio(resend.subarray(i, Math.min(resend.length, i + step)));
    }
    if (this.commitPending) {
      this.commitPending = false;
      this.enqueueCommit();
    }
  }

  private readPreRoll(preRoll?: (() => Float32Array | null) | null): Float32Array | null {
    try {
      return preRoll ? preRoll() : null;
    } catch {
      return null;
    }
  }

  /** Envoie la pré-écoute par morceaux de {@link CHUNK_MS}, au débit de la session, lissée (F-258). */
  private sendPreRoll(audio: Float32Array | null): void {
    if (!audio || audio.length === 0) {
      return;
    }
    const samples = downsample(audio, PRE_ROLL_RATE, this.ticket.sampleRate);
    const step = Math.max(1, Math.round(this.ticket.sampleRate * CHUNK_MS / 1000));
    for (let i = 0; i < samples.length; i += step) {
      this.emit(toPcm16(samples.subarray(i, Math.min(samples.length, i + step))));
    }
  }

  /**
   * F-239 / SF-239-04 — l'énoncé entendu jusqu'ici, en WAV (PCM 16 bits mono, débit de la session), ou `null` s'il
   * n'y a rien. Borné à {@link MAX_KEPT_SECONDS} s (au-delà, le début est oublié).
   */
  audio(): Blob | null {
    if (this.keptBytes === 0) {
      return null;
    }
    return wavBlob(this.keptSince(0), this.ticket.sampleRate);
  }

  /** La durée de l'audio gardé, en secondes. */
  audioSeconds(): number {
    return this.keptBytes / 2 / this.ticket.sampleRate;
  }

  private keep(pcm: Uint8Array): void {
    this.kept.push(pcm);
    this.keptBytes += pcm.length;
    this.keptTotal += pcm.length;
    const max = MAX_KEPT_SECONDS * this.ticket.sampleRate * 2;
    while (this.keptBytes > max && this.kept.length > 1) {
      this.keptBytes -= this.kept.shift()!.length;
    }
  }

  /** F-258 / SF-258-01 — l'audio gardé à partir de la position `from` (octets depuis le début de l'énoncé). */
  private keptSince(from: number): Uint8Array {
    const forgotten = this.keptTotal - this.keptBytes;
    const skip = Math.max(0, from - forgotten);
    const out = new Uint8Array(Math.max(0, this.keptBytes - skip));
    let offset = -skip;
    for (const part of this.kept) {
      const start = Math.max(0, -offset);
      if (start < part.length) {
        out.set(part.subarray(start), offset + start);
      }
      offset += part.length;
    }
    return out;
  }

  /** Force la fin de tour (silence détecté par l'écran) : le fournisseur valide ce qu'il a entendu. */
  commit(): void {
    const rest = this.chunker.flush();
    if (rest) {
      this.emit(toPcm16(downsample(rest.samples, rest.rate, this.ticket.sampleRate)));
    }
    if (this.reopening) {
      this.commitPending = true;
      return;
    }
    this.enqueueCommit();
  }

  /** Ferme la session et le micro ; rend la durée de session en secondes (comptée par la passerelle). */
  close(): number {
    if (!this.closed) {
      this.closed = true;
      this.clearOutbox();
      try {
        this.socket?.close();
      } catch {
        // déjà fermée
      }
      if (this.mic) {
        // F-251 / SF-251-02 — le micro de la conversation survit à la session. F-258 / SF-258-01 — jamais attaché
        // (session refusée à l'ouverture) : son tampon est gardé pour la tentative suivante.
        if (this.attached) {
          this.mic.detach();
        }
        return this.openedAt ? Math.max(0, Math.round((performance.now() - this.openedAt) / 1000)) : 0;
      }
      this.source?.disconnect();
      this.node?.disconnect();
      void this.context?.close().catch(() => undefined);
      this.stream.getTracks().forEach((track) => track.stop());
      if (this.tapUrl) {
        URL.revokeObjectURL(this.tapUrl);
      }
    }
    return this.openedAt ? Math.max(0, Math.round((performance.now() - this.openedAt) / 1000)) : 0;
  }

  private trace(event: string, detail: string): void {
    try {
      this.handlers.onTrace?.(event, detail);
    } catch {
      // le journal ne gêne jamais l'écoute
    }
  }

  /** Un bloc PCM de la session : gardé, et mis en file sauf pendant une coupure (il sera renvoyé à la réouverture). */
  private emit(pcm: Uint8Array): void {
    if (pcm.length === 0) {
      return;
    }
    this.keep(pcm);
    if (!this.failed && !this.closed) {
      this.enqueueAudio(pcm);
    }
  }

  private enqueueAudio(pcm: Uint8Array): void {
    this.outboxBytes.push(pcm.length);
    this.outbox.enqueue(JSON.stringify({ message_type: 'input_audio_chunk', audio_base_64: toBase64(pcm),
      commit: false, sample_rate: this.ticket.sampleRate }));
  }

  private enqueueCommit(): void {
    this.outboxBytes.push(0);
    this.outbox.enqueue(JSON.stringify({ message_type: 'input_audio_chunk', audio_base_64: '', commit: true,
      sample_rate: this.ticket.sampleRate }));
  }

  private clearOutbox(): void {
    this.outbox.clear();
    this.outboxBytes.length = 0;
  }

  private write(message: string): void {
    const bytes = this.outboxBytes.shift() ?? 0;
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(message);
      this.sentTotal += bytes;
    }
  }

  private push(samples: Float32Array, inRate: number): void {
    for (const chunk of this.chunker.add(samples, inRate)) {
      this.emit(toPcm16(downsample(chunk.samples, chunk.rate, this.ticket.sampleRate)));
    }
  }

  private async capture(): Promise<void> {
    const opened = await openCaptureGraph(this.stream, (samples, rate) => this.push(samples, rate));
    this.context = opened.context;
    this.source = opened.source;
    this.node = opened.node;
    this.tapUrl = opened.tapUrl;
  }
}

/** Branche le module de capture sur `stream` : chaque bloc du micro part vers `onBlock`. */
async function openCaptureGraph(stream: MediaStream, onBlock: (samples: Float32Array, rate: number) => void): Promise<{
  context: AudioContext; source: MediaStreamAudioSourceNode; node: AudioNode; tapUrl: string | null;
}> {
  const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext })
    .webkitAudioContext;
  const context = new Ctor();
  const source = context.createMediaStreamSource(stream);
  const rate = context.sampleRate;
  if (context.audioWorklet) {
    const tapUrl = URL.createObjectURL(new Blob([TAP_SOURCE], { type: 'application/javascript' }));
    // F-233 / SF-233-16 — borné : un module audio qui ne se charge pas ne fige plus l'ouverture.
    await timed(context.audioWorklet.addModule(tapUrl), WORKLET_TIMEOUT_MS);
    const node = new AudioWorkletNode(context, 'jarvis-pcm-tap');
    node.port.onmessage = (event: MessageEvent<Float32Array>) => onBlock(event.data, rate);
    source.connect(node);
    return { context, source, node, tapUrl };
  }
  // Navigateur sans AudioWorklet : l'ancien ScriptProcessor fait le même travail.
  const node = context.createScriptProcessor(4096, 1, 1);
  node.onaudioprocess = (event) => onBlock(event.inputBuffer.getChannelData(0).slice(0), rate);
  source.connect(node);
  node.connect(context.destination);
  return { context, source, node, tapUrl: null };
}

/** F-251 / SF-251-02 — ouvre le module de capture du micro gardé (sa fermeture rend tout). */
async function openConversationMic(stream: MediaStream, silenced: () => boolean): Promise<ConversationMic> {
  const onBlock = { fn: (_samples: Float32Array) => undefined as void };
  const opened = await openCaptureGraph(stream, (samples) => onBlock.fn(samples));
  const graph: CaptureGraph = {
    rate: opened.context.sampleRate,
    alive: () => opened.context.state !== 'closed',
    resume: () => {
      if (opened.context.state === 'suspended') {
        void opened.context.resume().catch(() => undefined);
      }
    },
    close: () => {
      opened.source.disconnect();
      opened.node.disconnect();
      void opened.context.close().catch(() => undefined);
      if (opened.tapUrl) {
        URL.revokeObjectURL(opened.tapUrl);
      }
    },
  };
  const mic = new ConversationMic(stream, graph, silenced);
  onBlock.fn = (samples) => mic.feed(samples);
  return mic;
}

/** Ce que l'écran garde d'une écoute en flux. */
export interface LiveListening {
  readonly stream: MediaStream;
  commit(): void;
  close(): number;
  /** F-239 / SF-239-04 — l'énoncé entendu (WAV), et sa durée en secondes. */
  audio(): Blob | null;
  audioSeconds(): number;
  /** F-251 / SF-251-02 — la durée du tampon du micro gardé envoyée en tête de la session (ms). */
  preRollMs?(): number;
}

/**
 * SF-233-05 — ouvre le micro (contraintes d'écho) et la session chez le fournisseur ; rejeté si l'un ou l'autre
 * échoue (le micro est alors rendu). Injectable : l'écran est testé sans micro ni WebSocket.
 */
@Injectable({ providedIn: 'root' })
export class JarvisStreamOpener {
  /** F-251 / SF-251-02 — le micro gardé pendant la conversation. */
  private mic: ConversationMic | null = null;
  private micOpening: Promise<ConversationMic> | null = null;
  /** Incrémenté à chaque libération : un micro ouvert entre-temps est rendu aussitôt. */
  private micGeneration = 0;

  async open(ticket: JarvisSttTicket, handlers: StreamHandlers, preRoll?: (() => Float32Array | null) | null,
             keep?: MicKeep | null): Promise<LiveListening> {
    if (keep) {
      return this.openOnKeptMic(ticket, handlers, preRoll, keep);
    }
    let stream: MediaStream;
    try {
      // F-233 / SF-233-16 — borné : un micro qui ne répond pas ne fige plus l'ouverture de l'écoute.
      stream = await timed(navigator.mediaDevices.getUserMedia({ audio: LISTEN_AUDIO }), MIC_OPEN_TIMEOUT_MS,
        stopTracks);
    } catch {
      throw new StreamOpenError('micro');
    }
    const listener = new StreamingListener(ticket, stream, handlers);
    try {
      await listener.start(preRoll);
    } catch (error) {
      listener.close();
      throw error;
    }
    return { stream, commit: () => listener.commit(), close: () => listener.close(), audio: () => listener.audio(),
      audioSeconds: () => listener.audioSeconds() };
  }

  private async openOnKeptMic(ticket: JarvisSttTicket, handlers: StreamHandlers,
                              preRoll: (() => Float32Array | null) | null | undefined,
                              keep: MicKeep): Promise<LiveListening> {
    const mic = await this.keptMic(keep);
    const listener = new StreamingListener(ticket, mic.stream, handlers, mic);
    try {
      await listener.start(preRoll);
    } catch (error) {
      listener.close();
      throw error;
    }
    return { stream: mic.stream, commit: () => listener.commit(), close: () => listener.close(),
      audio: () => listener.audio(), audioSeconds: () => listener.audioSeconds(),
      preRollMs: () => listener.preRollMs };
  }

  private async keptMic(keep: MicKeep): Promise<ConversationMic> {
    if (this.mic?.alive) {
      keep.report('reuse');
      return this.mic;
    }
    if (this.mic) {
      this.mic.close();
      this.mic = null;
    }
    if (!this.micOpening) {
      const generation = this.micGeneration;
      const startedAt = performance.now();
      this.micOpening = this.openMic(keep.silenced, generation)
        .then((mic) => {
          keep.report(`open ms=${Math.round(performance.now() - startedAt)}`);
          return mic;
        })
        .finally(() => {
          this.micOpening = null;
        });
    }
    try {
      return await this.micOpening;
    } catch (error) {
      keep.report('fail');
      throw error instanceof StreamOpenError ? error : new StreamOpenError('micro');
    }
  }

  private async openMic(silenced: () => boolean, generation: number): Promise<ConversationMic> {
    const stream = await timed(navigator.mediaDevices.getUserMedia({ audio: LISTEN_AUDIO }), MIC_OPEN_TIMEOUT_MS,
      stopTracks);
    let mic: ConversationMic;
    try {
      mic = await openConversationMic(stream, silenced);
    } catch {
      stopTracks(stream);
      throw new StreamOpenError('capture');
    }
    if (generation !== this.micGeneration) {
      // Rendu pendant l'ouverture (pause, sortie) : rien ne doit rester ouvert.
      mic.close();
      throw new StreamOpenError('micro');
    }
    this.mic = mic;
    return mic;
  }

  /** F-251 / SF-251-05 — le flux du micro gardé (la détection de voix en prend un clone), s'il est vivant. */
  keptStream(): MediaStream | null {
    return this.mic?.alive ? this.mic.stream : null;
  }

  /** F-251 / SF-251-02 — rend le micro gardé (pause, veille, sortie, dictée de repli). Vrai s'il y en avait un. */
  releaseMic(): boolean {
    this.micGeneration++;
    if (!this.mic) {
      return false;
    }
    this.mic.close();
    this.mic = null;
    return true;
  }
}
