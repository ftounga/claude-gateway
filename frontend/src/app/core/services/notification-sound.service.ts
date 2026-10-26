import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';

/** Les deux familles de son (F-217, D5) : ce qui attend un geste, et ce qui informe. */
export type SoundCategory = 'action' | 'info';

/** Les réglages du son, propres à l'appareil (F-217, D8). */
export interface SoundSettings {
  /** Interrupteur général. */
  enabled: boolean;
  /** Volume, de 0 à 1. */
  volume: number;
  /** « Action requise » : accord, question, plan à approuver. */
  action: boolean;
  /** « Information » : réponse prête, tâche terminée, notification de la cloche. */
  info: boolean;
}

/** Clef du réglage, propre à cet appareil. */
export const SOUND_SETTINGS_KEY = 'cg.notif.sound';

/** **Son activé par défaut** (décision PO du 2026-10-25). */
export const DEFAULT_SOUND_SETTINGS: SoundSettings = { enabled: true, volume: 0.7, action: true, info: true };

/** Une demande d'action restée sans geste est rejouée une fois après ce délai. */
export const ACTION_REPEAT_MS = 30_000;

/** Deux carillons à moins de ce délai : le second se tait (une rafale d'événements ne fait qu'un son). */
export const SOUND_COOLDOWN_MS = 2_000;

/** Ce qui compte pour un premier geste : la règle des navigateurs pour autoriser l'audio. */
const GESTURES = ['pointerdown', 'keydown', 'touchstart'] as const;

type AudioContextCtor = new () => AudioContext;

/** Une note : fréquence (Hz), départ et durée (s), forme d'onde, crête relative. */
interface Note {
  frequency: number;
  at: number;
  duration: number;
  type: OscillatorType;
  peak: number;
}

/** « Action requise » : trois notes montantes (la, ré, sol), franches — on doit l'entendre d'une autre pièce. */
export const ACTION_NOTES: readonly Note[] = [
  { frequency: 880, at: 0, duration: 0.16, type: 'triangle', peak: 0.5 },
  { frequency: 1174.66, at: 0.15, duration: 0.16, type: 'triangle', peak: 0.5 },
  { frequency: 1567.98, at: 0.3, duration: 0.3, type: 'triangle', peak: 0.55 },
];

/** « Information » : deux notes douces (do, sol) — le son de fin de tâche de F-191. */
export const INFO_NOTES: readonly Note[] = [
  { frequency: 523.25, at: 0, duration: 0.15, type: 'sine', peak: 0.3 },
  { frequency: 783.99, at: 0.15, duration: 0.2, type: 'sine', peak: 0.3 },
];

/** Lit les réglages ; tout ce qui est illisible reprend la valeur par défaut. */
export function readSoundSettings(raw: string | null): SoundSettings {
  if (!raw) {
    return { ...DEFAULT_SOUND_SETTINGS };
  }
  try {
    const parsed = JSON.parse(raw) as Partial<SoundSettings>;
    const volume = typeof parsed.volume === 'number' && Number.isFinite(parsed.volume)
      ? Math.min(1, Math.max(0, parsed.volume))
      : DEFAULT_SOUND_SETTINGS.volume;
    return {
      enabled: typeof parsed.enabled === 'boolean' ? parsed.enabled : DEFAULT_SOUND_SETTINGS.enabled,
      volume,
      action: typeof parsed.action === 'boolean' ? parsed.action : DEFAULT_SOUND_SETTINGS.action,
      info: typeof parsed.info === 'boolean' ? parsed.info : DEFAULT_SOUND_SETTINGS.info,
    };
  } catch {
    return { ...DEFAULT_SOUND_SETTINGS };
  }
}

/**
 * **Le carillon de l'application** (F-217 / SF-217-02, D5) — quand l'application est ouverte, c'est elle qui
 * sonne : le Web Push se tait volontairement devant un terminal regardé (D7 de F-153), et le son d'une
 * notification système dépend des réglages du système.
 *
 * <p>Deux sons générés en Web Audio — aucun fichier, aucune dépendance, aucun CDN : « action requise »
 * (rejoué **une fois** après 30 s si la demande attend toujours) et « information ». L'audio n'est permis
 * qu'après un **premier geste** sur la page (règle des navigateurs) : le service le débloque au premier
 * clic ou à la première touche ; avant, il se tait sans erreur. Réglages propres à l'appareil (comme un
 * volume) ; tout échec — stockage indisponible, audio bloqué — se tait.</p>
 */
@Injectable({ providedIn: 'root' })
export class NotificationSoundService {
  private readonly doc = inject(DOCUMENT);

  /** Les réglages en vigueur (lus au démarrage, écrits à chaque changement). */
  readonly settings = signal<SoundSettings>(this.read());

  private context?: AudioContext;
  private unlocked = false;
  private lastPlayedAt = Number.NEGATIVE_INFINITY;
  /** Rejeux programmés, par demande (clef choisie par l'appelant : `confirm:<id>`, `question:<id>`…). */
  private readonly repeats = new Map<string, ReturnType<typeof setTimeout>>();

  constructor() {
    const view = this.doc.defaultView as (Window & { navigator: Navigator & { userActivation?: { hasBeenActive?: boolean } } }) | null;
    // Un geste a déjà eu lieu avant ce service (navigation par un clic) : l'audio est déjà permis.
    if (view?.navigator?.userActivation?.hasBeenActive) {
      this.unlocked = true;
    }
    const unlock = () => {
      this.unlocked = true;
      this.resume();
      for (const type of GESTURES) {
        this.doc.removeEventListener(type, unlock, true);
      }
    };
    for (const type of GESTURES) {
      this.doc.addEventListener(type, unlock, { capture: true, passive: true });
    }
  }

  /** Change un ou plusieurs réglages, aussitôt appliqués et mémorisés sur l'appareil. */
  update(change: Partial<SoundSettings>): void {
    const next = readSoundSettings(JSON.stringify({ ...this.settings(), ...change }));
    this.settings.set(next);
    try {
      this.doc.defaultView?.localStorage.setItem(SOUND_SETTINGS_KEY, JSON.stringify(next));
    } catch {
      // Stockage indisponible : le réglage ne tient que pour la page.
    }
  }

  /**
   * **Action requise** (accord, question, plan) : joue le son marqué, et le rejoue **une fois** après
   * {@link ACTION_REPEAT_MS} si {@link settle} n'a pas été appelé pour cette clef entre-temps. Une même
   * clef déjà en attente ne reprogramme rien (un rejeu d'état n'est pas une nouvelle demande).
   */
  actionRequired(key: string): void {
    if (this.repeats.has(key)) {
      return;
    }
    this.play('action');
    this.repeats.set(key, setTimeout(() => {
      this.repeats.delete(key);
      this.play('action', true);
    }, ACTION_REPEAT_MS));
  }

  /** La demande a été tranchée (ou a expiré) : son rejeu est annulé. Sans effet si rien n'attend. */
  settle(key: string): void {
    const timer = this.repeats.get(key);
    if (timer !== undefined) {
      clearTimeout(timer);
      this.repeats.delete(key);
    }
  }

  /** Annule tous les rejeux en attente (fin de tour, sortie du terminal). */
  settleAll(): void {
    for (const timer of this.repeats.values()) {
      clearTimeout(timer);
    }
    this.repeats.clear();
  }

  /** **Information** : réponse prête, tâche terminée, notification de la cloche. */
  info(): void {
    this.play('info');
  }

  /** « Tester le son » : joue le son demandé quels que soient l'interrupteur et l'anti-rafale. */
  test(category: SoundCategory = 'action'): void {
    this.unlocked = true;
    this.emit(category);
  }

  /** Joue un son si les réglages le permettent, hors anti-rafale (sauf `bypassCooldown`). */
  play(category: SoundCategory, bypassCooldown = false): void {
    const settings = this.settings();
    if (!settings.enabled || !settings[category] || settings.volume <= 0 || !this.unlocked) {
      return;
    }
    const now = Date.now();
    if (!bypassCooldown && now - this.lastPlayedAt < SOUND_COOLDOWN_MS) {
      return;
    }
    this.lastPlayedAt = now;
    this.emit(category);
  }

  /** Synthétise le son. Silence si l'audio est indisponible ou bloqué. */
  private emit(category: SoundCategory): void {
    try {
      const ctx = this.audio();
      if (!ctx) {
        return;
      }
      this.resume();
      const volume = this.settings().volume;
      const start = ctx.currentTime + 0.01;
      for (const note of category === 'action' ? ACTION_NOTES : INFO_NOTES) {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        const at = start + note.at;
        osc.type = note.type;
        osc.frequency.setValueAtTime(note.frequency, at);
        gain.gain.setValueAtTime(0.0001, at);
        gain.gain.exponentialRampToValueAtTime(Math.max(0.0002, note.peak * volume), at + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, at + note.duration);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(at);
        osc.stop(at + note.duration + 0.02);
      }
    } catch {
      // Audio bloqué ou indisponible : silence.
    }
  }

  private audio(): AudioContext | undefined {
    if (this.context) {
      return this.context;
    }
    const scope = globalThis as unknown as { AudioContext?: AudioContextCtor; webkitAudioContext?: AudioContextCtor };
    const Ctor = scope.AudioContext ?? scope.webkitAudioContext;
    if (!Ctor) {
      return undefined;
    }
    this.context = new Ctor();
    return this.context;
  }

  private resume(): void {
    try {
      const ctx = this.audio();
      if (ctx && ctx.state === 'suspended') {
        void ctx.resume().catch(() => undefined);
      }
    } catch {
      // Audio indisponible : rien à reprendre.
    }
  }

  private read(): SoundSettings {
    try {
      return readSoundSettings(this.doc.defaultView?.localStorage.getItem(SOUND_SETTINGS_KEY) ?? null);
    } catch {
      return { ...DEFAULT_SOUND_SETTINGS };
    }
  }
}
