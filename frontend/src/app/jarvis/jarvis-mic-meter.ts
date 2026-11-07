import { rmsLevel } from '../core/services/jarvis-voice.service';

/** Au-dessus, c'est de la voix ; en dessous, du silence (niveau RMS ramené de 0 à 1). */
export const SPEECH_LEVEL = 0.12;
export const SILENCE_LEVEL = 0.06;
/**
 * Après avoir parlé, autant de silence clôt la phrase. F-233 / SF-233-02 (D6) : 1,4 s → 0,7 s ; F-239 / SF-239-10 :
 * 0,7 s coupait le PO quand il réfléchit (banc `--vad` : 50/52 phrases à pause coupées) ⇒ 1,2 s, et une phrase en
 * suspens attend encore 1,5 s (`jarvis-turn-end.ts`).
 */
export const END_OF_SPEECH_MS = 1_200;
/** SF-233-02 — le réglage local du silence de fin de phrase (ms), borné. */
export const SILENCE_MS_KEY = 'cg.jarvis.silenceMs';
export const MIN_SILENCE_MS = 300;
export const MAX_SILENCE_MS = 3_000;

/** SF-233-02 — le silence de fin de phrase de cet appareil : réglé (borné), sinon {@link END_OF_SPEECH_MS}. */
export function silenceMs(): number {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(SILENCE_MS_KEY);
  } catch {
    raw = null;
  }
  const value = Number(raw);
  if (raw === null || raw.trim() === '' || !Number.isFinite(value)) {
    return END_OF_SPEECH_MS;
  }
  return Math.min(MAX_SILENCE_MS, Math.max(MIN_SILENCE_MS, Math.round(value)));
}
/** Sans un mot, l'écoute s'arrête d'elle-même au bout de ce temps. */
export const NO_SPEECH_MS = 8_000;

/**
 * **Fin de phrase au silence** (F-231 / SF-231-02) — pur : on lui donne un niveau et l'heure, il dit quand la phrase
 * est finie. Sert à l'écoute mains libres : on parle, on se tait, Jarvis comprend que c'est à lui.
 */
export class EndOfSpeech {
  private heard = false;
  private quietSince: number | null = null;

  constructor(private readonly startedAt: number, private readonly endMs: number = END_OF_SPEECH_MS) {}

  /** Vrai quand la phrase est finie (silence après de la voix), ou quand rien n'a été dit depuis trop longtemps. */
  update(level: number, now: number): boolean {
    if (level >= SPEECH_LEVEL) {
      this.heard = true;
      this.quietSince = null;
      return false;
    }
    if (!this.heard) {
      return now - this.startedAt >= NO_SPEECH_MS;
    }
    if (level <= SILENCE_LEVEL) {
      this.quietSince ??= now;
      return now - this.quietSince >= this.endMs;
    }
    return false;
  }

  /** Vrai si de la voix a été entendue. */
  get spoke(): boolean {
    return this.heard;
  }
}

/**
 * **Le niveau du micro** (F-231 / SF-231-02) : un analyseur posé sur le flux de la dictée. Rien n'est enregistré
 * ni envoyé par lui — il mesure, c'est tout.
 */
export class MicMeter {
  private context: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private samples: Uint8Array | null = null;

  constructor(stream: MediaStream) {
    const Ctor = typeof window !== 'undefined'
      ? (window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext)
      : undefined;
    if (!Ctor) {
      return;
    }
    try {
      this.context = new Ctor();
      this.analyser = this.context.createAnalyser();
      this.analyser.fftSize = 512;
      this.samples = new Uint8Array(this.analyser.fftSize);
      this.context.createMediaStreamSource(stream).connect(this.analyser);
    } catch {
      this.close();
    }
  }

  /** F-233 / SF-233-15 — l'état de l'`AudioContext` du niveau (`suspended` : l'écoute est sourde), ou `none`. */
  get contextState(): string {
    return this.context?.state ?? 'none';
  }

  /** F-239 — vrai si le niveau est réellement mesuré (sinon `level()` rend 0 sans rien dire de la voix). */
  get available(): boolean {
    return this.analyser !== null;
  }

  level(): number {
    if (!this.analyser || !this.samples) {
      return 0;
    }
    this.analyser.getByteTimeDomainData(this.samples);
    return rmsLevel(this.samples);
  }

  close(): void {
    void this.context?.close().catch(() => undefined);
    this.context = null;
    this.analyser = null;
    this.samples = null;
  }
}
