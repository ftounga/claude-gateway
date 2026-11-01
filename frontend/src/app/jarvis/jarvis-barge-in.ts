import { Injectable } from '@angular/core';

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
  stop(): void;
}

/**
 * SF-233-08 — la détection de voix **sur l'appareil** (Silero VAD v5, ONNX en WASM). Chargée à la demande (rien au
 * démarrage de l'application), ressources servies sous `/vad/`. Injectable : l'écran est testé sans micro ni modèle.
 */
@Injectable({ providedIn: 'root' })
export class JarvisVadService {
  async start(handlers: VadHandlers): Promise<RunningVad> {
    const { MicVAD } = await import('@ricky0123/vad-web');
    const vad = await MicVAD.new({
      model: 'v5',
      baseAssetPath: '/vad/',
      onnxWASMBasePath: '/vad/',
      getStream: () => navigator.mediaDevices.getUserMedia({ audio: LISTEN_AUDIO }),
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
    await vad.start();
    return { stop: () => void vad.destroy().catch(() => undefined) };
  }
}
