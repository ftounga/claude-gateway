import { Injectable } from '@angular/core';

import { errorName } from './jarvis-listen-trace';
import { LISTEN_AUDIO } from './jarvis-stream-listener';
import { TurnCounter } from './jarvis-turn-clock';

/**
 * **Se laisser couper** (F-233 / SF-233-08, D8) — pendant que Jarvis parle, le micro reste ouvert (annulation d'écho)
 * et une **détection de voix sur l'appareil** (Silero VAD, ONNX en WASM : rien ne sort) décide qu'un humain parle.
 *
 * Réaction en deux temps : (1) voix détectée → Jarvis **baisse le volume** ; (2) parole confirmée (durée minimale) →
 * il **coupe** sa phrase et vide sa file ; ce qui est dit est capté par la détection elle-même (aucun mot perdu) puis
 * transcrit. Non confirmé dans le délai → le volume revient et il continue.
 */

/** Au-delà, une voix détectée mais non confirmée rend le volume. */
export const RESTORE_AFTER_MS = 1_000;
/** Durée minimale de parole pour couper (une toux ne coupe pas). */
export const MIN_SPEECH_MS = 250;
/** Le réglage local : la coupure par la voix est-elle allumée sur cet appareil ? (défaut : oui) */
export const BARGE_IN_KEY = 'cg.jarvis.bargeIn';

export function bargeInEnabled(): boolean {
  try {
    return localStorage.getItem(BARGE_IN_KEY) !== '0';
  } catch {
    return true;
  }
}

/** Ce que le contrôleur fait faire à l'écran. */
export interface BargeInActions {
  /** Jarvis parle-t-il ? */
  speaking(): boolean;
  duck(on: boolean): void;
  /** Coupe Jarvis (phrase et file). */
  cut(): void;
  count(counter: TurnCounter): void;
}

/**
 * Le contrôleur de coupure — pur (les minuteries sont celles du navigateur), testé seul. Il reçoit les événements de
 * la détection de voix et décide : baisser, rétablir, couper.
 */
export class BargeInController {
  private ducked = false;
  private cutDone = false;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly actions: BargeInActions) {}

  /** Une voix commence (probabilité au-dessus du seuil). */
  voiceStart(): void {
    if (this.cutDone || this.ducked || !this.actions.speaking()) {
      return;
    }
    this.ducked = true;
    this.actions.duck(true);
    this.actions.count('interruptionsDetected');
    this.timer = setTimeout(() => this.restore(), RESTORE_AFTER_MS);
  }

  /** La parole est confirmée (durée minimale atteinte) : Jarvis se tait. */
  voiceConfirmed(): void {
    if (this.cutDone || (!this.ducked && !this.actions.speaking())) {
      return;
    }
    this.clearTimer();
    this.ducked = false;
    this.cutDone = true;
    this.actions.cut();
    this.actions.count('interruptionsConfirmed');
  }

  /** Fausse alerte (trop court) : le volume revient. */
  misfire(): void {
    if (this.ducked && !this.cutDone) {
      this.restore();
    }
  }

  /** Vrai entre la coupure et la fin de la parole de l'humain : ce qu'il dit est à transcrire. */
  get capturing(): boolean {
    return this.cutDone;
  }

  /** Remet à zéro (nouvelle parole de Jarvis, ou fin de la capture). */
  reset(): void {
    this.clearTimer();
    if (this.ducked) {
      this.actions.duck(false);
    }
    this.ducked = false;
    this.cutDone = false;
  }

  private restore(): void {
    this.clearTimer();
    this.ducked = false;
    this.actions.duck(false);
    this.actions.count('interruptionsRestored');
  }

  private clearTimer(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}

/** PCM float 16 kHz mono → WAV 16 bits (ce que la dictée accepte). */
export function encodeWav(samples: Float32Array, sampleRate = 16_000): Blob {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const text = (offset: number, value: string) => {
    for (let i = 0; i < value.length; i++) {
      view.setUint8(offset + i, value.charCodeAt(i));
    }
  };
  text(0, 'RIFF');
  view.setUint32(4, 36 + samples.length * 2, true);
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
  view.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Blob([buffer], { type: 'audio/wav' });
}

/** Les événements de la détection de voix. */
export interface VadHandlers {
  onSpeechStart(): void;
  onSpeechConfirmed(): void;
  onMisfire(): void;
  /** La parole est finie : l'audio (PCM float 16 kHz mono). */
  onSpeechEnd(audio: Float32Array): void;
}

/** Une détection de voix en marche. */
export interface RunningVad {
  /** F-233 / SF-233-16 — peut rendre la promesse de la libération du micro (attendue, bornée, avant la réécoute). */
  stop(): void | Promise<void>;
}

/** F-251 / SF-251-05 — l'étape du démarrage de la détection qui a échoué. */
export type VadStep = 'chargement' | 'modele' | 'micro' | 'demarrage';

/** F-251 / SF-251-05 — un échec de démarrage, avec son étape et l'erreur d'origine. */
export class VadStartError extends Error {
  constructor(readonly step: VadStep, readonly original: unknown) {
    super(step);
  }
}

/** F-251 / SF-251-05 — au-delà, la détection n'est plus retentée sur cette page. */
export const VAD_MAX_FAILURES = 3;

/**
 * F-251 / SF-251-05 — le paquet est en CommonJS : le bundle de production (esbuild) le présente sous `default`, là où
 * webpack donne l'espace de noms complet. `TypeError … reading 'new'` en production, invisible sous Karma.
 */
export function resolveMicVad<T>(module: { MicVAD?: T; default?: { MicVAD?: T } }): T {
  const micVad = module.MicVAD ?? module.default?.MicVAD;
  if (!micVad) {
    throw new TypeError('MicVAD introuvable');
  }
  return micVad;
}

/** F-251 / SF-251-05 — le détail du journal d'un échec : étape, nom, message court, première ligne de la pile. */
export function vadFailureDetail(error: unknown): string {
  const step = error instanceof VadStartError ? error.step : 'inconnue';
  const original = error instanceof VadStartError ? error.original : error;
  const { message, stack } = (original ?? {}) as { message?: unknown; stack?: unknown };
  const frame = typeof stack === 'string' ? stack.split('\n').find((line) => /:\d+:\d+/.test(line)) : undefined;
  const place = frame?.slice(frame.lastIndexOf('/') + 1).replace(/\)$/, '').slice(0, 30);
  return [step, errorName(original), typeof message === 'string' ? message.slice(0, 50) : '', place ? `at ${place}` : '']
    .filter(Boolean).join(' ');
}

/**
 * SF-233-08 — la détection de voix **sur l'appareil** (Silero VAD v5, ONNX en WASM). Chargée à la demande (rien au
 * démarrage de l'application), ressources servies sous `/vad/`. Injectable : l'écran est testé sans micro ni modèle.
 */
@Injectable({ providedIn: 'root' })
export class JarvisVadService {
  /** @param shared F-251 / SF-251-05 — le micro de la conversation : la détection en prend un clone. */
  async start(handlers: VadHandlers, shared: MediaStream | null = null): Promise<RunningVad> {
    let MicVAD: typeof import('@ricky0123/vad-web').MicVAD;
    try {
      MicVAD = resolveMicVad(await import('@ricky0123/vad-web') as never);
    } catch (error) {
      throw new VadStartError('chargement', error);
    }
    let micError: unknown = null;
    const getStream = async (): Promise<MediaStream> => {
      try {
        return shared ? shared.clone() : await navigator.mediaDevices.getUserMedia({ audio: LISTEN_AUDIO });
      } catch (error) {
        micError = error;
        throw error;
      }
    };
    let vad: Awaited<ReturnType<typeof MicVAD.new>>;
    try {
      vad = await MicVAD.new({
        model: 'v5',
        baseAssetPath: '/vad/',
        onnxWASMBasePath: '/vad/',
        getStream,
        positiveSpeechThreshold: 0.6,
        negativeSpeechThreshold: 0.45,
        redemptionMs: 600,
        minSpeechMs: MIN_SPEECH_MS,
        preSpeechPadMs: 300,
        onSpeechStart: () => handlers.onSpeechStart(),
        onSpeechRealStart: () => handlers.onSpeechConfirmed(),
        onVADMisfire: () => handlers.onMisfire(),
        onSpeechEnd: (audio: Float32Array) => handlers.onSpeechEnd(audio),
      });
    } catch (error) {
      throw new VadStartError('modele', error);
    }
    try {
      await vad.start();
    } catch (error) {
      throw new VadStartError(micError ? 'micro' : 'demarrage', micError ?? error);
    }
    return { stop: () => vad.destroy().catch(() => undefined) };
  }
}
