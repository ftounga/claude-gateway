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

/** Ce que l'écoute signale à l'écran. */
export interface StreamHandlers {
  /** Le texte provisoire de la phrase en cours. */
  onPartial(text: string): void;
  /** Un segment validé par le fournisseur (fin de tour). */
  onCommitted(text: string): void;
  /** La session ne peut pas continuer (réseau, refus, jeton) : l'écran retombe sur la dictée. */
  onError(): void;
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
  // F-233 / SF-233-13 — `invalid_request` (ex. plus de 50 keyterms) et tout message porteur d'un `error` sont des erreurs.
  if (type.includes('error') || type === 'invalid_request' || type === 'quota_exceeded'
    || type === 'session_time_limit_exceeded' || (typeof data.error === 'string' && data.error !== '')) {
    return { kind: 'error', text: '' };
  }
  return { kind: 'other', text: '' };
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

/**
 * Une session d'écoute en flux, sur un flux micro déjà ouvert. `start()` se résout quand le fournisseur a accepté la
 * session ; il est rejeté sinon (l'écran retombe alors sur la dictée).
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

  constructor(private readonly ticket: JarvisSttTicket, private readonly stream: MediaStream,
              private readonly handlers: StreamHandlers) {}

  /**
   * @param preRoll F-239 / SF-239-01 (D2) — l'audio de la phrase qui contenait « Jarvis » (16 kHz mono), lu une fois la
   *                session acceptée et envoyé **avant** le micro ; `null`/absent : rien avant le micro.
   */
  async start(preRoll?: (() => Float32Array | null) | null): Promise<void> {
    this.openedAt = performance.now();
    const socket = new WebSocket(sessionUrl(this.ticket));
    this.socket = socket;
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout')), START_TIMEOUT_MS);
      let started = false;
      socket.onmessage = (event) => {
        const message = parseSttMessage(event.data);
        if (message.kind === 'started' && !started) {
          started = true;
          clearTimeout(timer);
          resolve();
        } else if (message.kind === 'partial') {
          this.handlers.onPartial(message.text);
        } else if (message.kind === 'committed') {
          this.handlers.onCommitted(message.text);
        } else if (message.kind === 'error') {
          if (!started) {
            clearTimeout(timer);
            reject(new Error('refus'));
          } else {
            this.handlers.onError();
          }
        }
      };
      socket.onerror = () => {
        if (!started) {
          clearTimeout(timer);
          reject(new Error('reseau'));
        }
      };
      socket.onclose = () => {
        if (!started) {
          clearTimeout(timer);
          reject(new Error('ferme'));
        } else if (!this.closed) {
          this.handlers.onError();
        }
      };
    });
    if (preRoll) {
      this.sendPreRoll(preRoll);
    }
    try {
      await this.capture();
    } catch {
      throw new StreamOpenError('capture');
    }
  }

  /** Envoie la pré-écoute par morceaux de 100 ms, au débit de la session. */
  private sendPreRoll(preRoll: () => Float32Array | null): void {
    let audio: Float32Array | null = null;
    try {
      audio = preRoll();
    } catch {
      audio = null;
    }
    if (!audio || audio.length === 0) {
      return;
    }
    const samples = downsample(audio, PRE_ROLL_RATE, this.ticket.sampleRate);
    const step = Math.max(1, Math.round(this.ticket.sampleRate / 10));
    for (let i = 0; i < samples.length; i += step) {
      const pcm = toPcm16(samples.subarray(i, Math.min(samples.length, i + step)));
      this.keep(pcm);
      this.send({ message_type: 'input_audio_chunk', audio_base_64: toBase64(pcm), commit: false,
        sample_rate: this.ticket.sampleRate });
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
    const pcm = new Uint8Array(this.keptBytes);
    let at = 0;
    for (const part of this.kept) {
      pcm.set(part, at);
      at += part.length;
    }
    return wavBlob(pcm, this.ticket.sampleRate);
  }

  /** La durée de l'audio gardé, en secondes. */
  audioSeconds(): number {
    return this.keptBytes / 2 / this.ticket.sampleRate;
  }

  private keep(pcm: Uint8Array): void {
    this.kept.push(pcm);
    this.keptBytes += pcm.length;
    const max = MAX_KEPT_SECONDS * this.ticket.sampleRate * 2;
    while (this.keptBytes > max && this.kept.length > 1) {
      this.keptBytes -= this.kept.shift()!.length;
    }
  }

  /** Force la fin de tour (silence détecté par l'écran) : le fournisseur valide ce qu'il a entendu. */
  commit(): void {
    this.send({ message_type: 'input_audio_chunk', audio_base_64: '', commit: true, sample_rate: this.ticket.sampleRate });
  }

  /** Ferme la session et le micro ; rend la durée de session en secondes (comptée par la passerelle). */
  close(): number {
    if (!this.closed) {
      this.closed = true;
      try {
        this.socket?.close();
      } catch {
        // déjà fermée
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

  private send(message: object): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    }
  }

  private push(samples: Float32Array, inRate: number): void {
    const pcm = toPcm16(downsample(samples, inRate, this.ticket.sampleRate));
    if (pcm.length > 0) {
      this.keep(pcm);
      this.send({ message_type: 'input_audio_chunk', audio_base_64: toBase64(pcm), commit: false,
        sample_rate: this.ticket.sampleRate });
    }
  }

  private async capture(): Promise<void> {
    const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext })
      .webkitAudioContext;
    const context = new Ctor();
    this.context = context;
    this.source = context.createMediaStreamSource(this.stream);
    const rate = context.sampleRate;
    if (context.audioWorklet) {
      this.tapUrl = URL.createObjectURL(new Blob([TAP_SOURCE], { type: 'application/javascript' }));
      // F-233 / SF-233-16 — borné : un module audio qui ne se charge pas ne fige plus l'ouverture.
      await timed(context.audioWorklet.addModule(this.tapUrl), WORKLET_TIMEOUT_MS);
      const node = new AudioWorkletNode(context, 'jarvis-pcm-tap');
      node.port.onmessage = (event: MessageEvent<Float32Array>) => this.push(event.data, rate);
      this.source.connect(node);
      this.node = node;
    } else {
      // Navigateur sans AudioWorklet : l'ancien ScriptProcessor fait le même travail.
      const node = context.createScriptProcessor(4096, 1, 1);
      node.onaudioprocess = (event) => this.push(event.inputBuffer.getChannelData(0).slice(0), rate);
      this.source.connect(node);
      node.connect(context.destination);
      this.node = node;
    }
  }
}

/** Ce que l'écran garde d'une écoute en flux. */
export interface LiveListening {
  readonly stream: MediaStream;
  commit(): void;
  close(): number;
  /** F-239 / SF-239-04 — l'énoncé entendu (WAV), et sa durée en secondes. */
  audio(): Blob | null;
  audioSeconds(): number;
}

/**
 * SF-233-05 — ouvre le micro (contraintes d'écho) et la session chez le fournisseur ; rejeté si l'un ou l'autre
 * échoue (le micro est alors rendu). Injectable : l'écran est testé sans micro ni WebSocket.
 */
@Injectable({ providedIn: 'root' })
export class JarvisStreamOpener {
  async open(ticket: JarvisSttTicket, handlers: StreamHandlers,
             preRoll?: (() => Float32Array | null) | null): Promise<LiveListening> {
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
}
