import { Injectable, signal } from '@angular/core';

/** Clef du réglage, propre à cet appareil. */
export const FINISH_SOUND_KEY = 'cg.fil.finishSound';

type AudioContextCtor = new () => AudioContext;

/**
 * **Le son de fin de tâche** (F-191 / SF-191-03) : deux notes courtes, générées par Web Audio — aucun
 * fichier tiers. **Coupé par défaut**, réglé par appareil (un son est un comportement de l'appareil,
 * comme son volume). Tout échec — stockage indisponible, audio bloqué — se tait.
 */
@Injectable({ providedIn: 'root' })
export class FinishSoundService {
  readonly enabled = signal(this.read());
  private context?: AudioContext;

  setEnabled(on: boolean): void {
    this.enabled.set(on);
    try {
      if (on) {
        localStorage.setItem(FINISH_SOUND_KEY, '1');
      } else {
        localStorage.removeItem(FINISH_SOUND_KEY);
      }
    } catch {
      // Stockage indisponible : le réglage ne tient que pour la page.
    }
  }

  /** Joue le son si le réglage est activé. */
  playIfEnabled(): void {
    if (this.enabled()) {
      this.play();
    }
  }

  /** Joue le son (essai depuis les préférences, ou fin de tâche). Silence si l'audio est indisponible. */
  play(): void {
    try {
      const Ctor = (globalThis as unknown as { AudioContext?: AudioContextCtor; webkitAudioContext?: AudioContextCtor })
        .AudioContext
        ?? (globalThis as unknown as { webkitAudioContext?: AudioContextCtor }).webkitAudioContext;
      if (!Ctor) {
        return;
      }
      this.context ??= new Ctor();
      const ctx = this.context;
      if (ctx.state === 'suspended') {
        void ctx.resume().catch(() => undefined);
      }
      const start = ctx.currentTime;
      // Deux notes montantes (do, sol), 0,15 s chacune, enveloppe douce.
      [523.25, 783.99].forEach((frequency, i) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        const at = start + i * 0.15;
        osc.type = 'sine';
        osc.frequency.setValueAtTime(frequency, at);
        gain.gain.setValueAtTime(0.0001, at);
        gain.gain.exponentialRampToValueAtTime(0.15, at + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.15);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(at);
        osc.stop(at + 0.16);
      });
    } catch {
      // Audio bloqué ou indisponible : silence.
    }
  }

  private read(): boolean {
    try {
      return localStorage.getItem(FINISH_SOUND_KEY) === '1';
    } catch {
      return false;
    }
  }
}
