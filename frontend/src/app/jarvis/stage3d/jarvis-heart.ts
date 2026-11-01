import { HoloRole, MOTION_DURATIONS, MotionCurve } from './jarvis-motion';

/**
 * F-236 / SF-236-02 — **le cœur de Jarvis et ses états** (cadrage §9.1, transitions T-C1→C6, T-V1/V2). Fichier
 * <b>pur</b> : il dit à quoi ressemble le cœur dans chaque état ; le moteur ne fait qu'interpoler vers ces cibles.
 */

/** L'état de l'écran F-233 (inchangé). */
export type JarvisVoiceState = 'repos' | 'ecoute' | 'reflexion' | 'parole';

/** L'humeur du cœur : l'état de l'écran, plus la coupure, le « oui » attendu et la veille. */
export type HeartMood = 'repos' | 'ecoute' | 'reflexion' | 'parole' | 'coupure' | 'oui' | 'veille';

export const HEART_MOODS: readonly HeartMood[] = ['repos', 'ecoute', 'reflexion', 'parole', 'coupure', 'oui', 'veille'];

/** À quoi ressemble le cœur dans une humeur : des cibles, jamais des valeurs posées d'un coup. */
export interface HeartLook {
  /** La teinte (grammaire §23). */
  color: HoloRole;
  /** Vitesse de rotation des anneaux et du nuage (rad/s). */
  spin: number;
  /** Amplitude de la respiration (fraction du rayon). */
  breath: number;
  /** Période de la respiration (ms). */
  breathPeriod: number;
  /** Tourbillon du nuage intérieur (0-1). */
  swirl: number;
  /** Sens des ondes de voix : −1 entrantes (le PO parle), +1 sortantes (Jarvis parle), 0 aucune. */
  waveDir: -1 | 0 | 1;
  /** La lumière du cœur (0-1) ; la lueur du fond la suit. */
  glow: number;
  /** Intensité du bloom (1,1 de base, §7.1). */
  bloom: number;
  /** Les 64 rayons de la parole (0-1). */
  rays: number;
  /** Anneaux tournés vers la caméra (0 = inclinés, 1 = face). */
  facing: number;
  /** Le temps du cœur est gelé (coupure). */
  frozen: boolean;
}

export const HEART_LOOKS: Readonly<Record<HeartMood, HeartLook>> = {
  repos: { color: 'normal', spin: 0.25, breath: 0.04, breathPeriod: 4000, swirl: 0.15, waveDir: 0, glow: 0.8,
    bloom: 1.1, rays: 0, facing: 0, frozen: false },
  ecoute: { color: 'normal', spin: 0.18, breath: 0.03, breathPeriod: 4000, swirl: 0.1, waveDir: -1, glow: 0.95,
    bloom: 1.15, rays: 0, facing: 1, frozen: false },
  reflexion: { color: 'normal', spin: 0.9, breath: 0.05, breathPeriod: 2400, swirl: 1, waveDir: 0, glow: 1,
    bloom: 1.25, rays: 0, facing: 0.3, frozen: false },
  parole: { color: 'normal', spin: 0.45, breath: 0.02, breathPeriod: 4000, swirl: 0.3, waveDir: 1, glow: 1,
    bloom: 1.3, rays: 1, facing: 0.6, frozen: false },
  coupure: { color: 'normal', spin: 0, breath: 0, breathPeriod: 4000, swirl: 0, waveDir: 0, glow: 0.5, bloom: 0.8,
    rays: 0, facing: 0, frozen: true },
  oui: { color: 'wait', spin: 0.2, breath: 0.05, breathPeriod: 3000, swirl: 0.2, waveDir: 1, glow: 0.95, bloom: 1.3,
    rays: 0, facing: 1, frozen: false },
  veille: { color: 'normal', spin: 0.06, breath: 0.03, breathPeriod: 6000, swirl: 0.05, waveDir: 0, glow: 0.3,
    bloom: 0.6, rays: 0, facing: 0, frozen: false },
};

/** Comment on entre dans une humeur (catalogue §7.4). */
export const HEART_TRANSITIONS: Readonly<Record<HeartMood, { ms: number; curve: MotionCurve }>> = {
  repos: { ms: MOTION_DURATIONS.heartRest, curve: 'souffle' },
  ecoute: { ms: MOTION_DURATIONS.heartListen, curve: 'sortie' },
  reflexion: { ms: MOTION_DURATIONS.heartThink, curve: 'glisse' },
  parole: { ms: MOTION_DURATIONS.heartSpeak, curve: 'sortie' },
  coupure: { ms: MOTION_DURATIONS.heartCut, curve: 'entree' },
  oui: { ms: MOTION_DURATIONS.heartWait, curve: 'glisse' },
  veille: { ms: MOTION_DURATIONS.sleep, curve: 'souffle' },
};

/** Ce qui décide de l'humeur. */
export interface HeartInput {
  state: JarvisVoiceState;
  /** La voix a été coupée (F-234 D13, `ReaderState.frozen`). */
  frozen: boolean;
  /** Une autorisation (S2) attend sa réponse. */
  waiting: boolean;
  /** Jarvis est en veille (§N3). */
  asleep: boolean;
}

/** L'humeur, par priorité : coupure > « oui » attendu > veille > état de l'écran. */
export function heartMood(input: HeartInput): HeartMood {
  if (input.frozen) {
    return 'coupure';
  }
  if (input.waiting) {
    return 'oui';
  }
  if (input.asleep && input.state === 'repos') {
    return 'veille';
  }
  return input.state;
}

/** L'amplitude des ondes de voix : proportionnelle au niveau (micro ou voix, 0-1), nulle hors écoute / parole. */
export function waveAmplitude(mood: HeartMood, level: number): number {
  if (HEART_LOOKS[mood].waveDir === 0 && mood !== 'oui') {
    return 0;
  }
  const clamped = Number.isFinite(level) ? Math.min(1, Math.max(0, level)) : 0;
  // « Oui » attendu : des ondes lentes et régulières, sans voix.
  return mood === 'oui' ? 0.25 : clamped;
}

/** Le silence avant la veille (N3) : 60 s sans conversation active, Jarvis au repos. */
export const SLEEP_AFTER_MS = 60_000;

/**
 * **La veille** (N3, T-V1) et le **réveil** (N2, T-V2). Pure : l'horloge est passée à chaque appel.
 * Un réveil incrémente {@link wakes} — le moteur joue l'onde de choc à chaque incrément.
 */
export class SleepWatch {
  private quietSince: number | null = null;
  private sleeping = false;
  /** Réveils joués depuis l'ouverture (onde de choc à chaque incrément). */
  wakes = 0;

  /** Relit la situation. `conversing` = conversation mains libres active. */
  update(conversing: boolean, state: JarvisVoiceState, now: number): boolean {
    const quiet = !conversing && state === 'repos';
    if (!quiet) {
      this.quietSince = null;
      if (this.sleeping) {
        this.sleeping = false;
        this.wakes++;
      }
      return false;
    }
    if (this.quietSince === null) {
      this.quietSince = now;
    }
    if (!this.sleeping && now - this.quietSince >= SLEEP_AFTER_MS) {
      this.sleeping = true;
    }
    return this.sleeping;
  }

  /** Le mot « Jarvis » ou un toucher : réveil (onde de choc), même si Jarvis ne dormait pas encore. */
  wake(now: number): void {
    this.sleeping = false;
    this.quietSince = now;
    this.wakes++;
  }

  get asleep(): boolean {
    return this.sleeping;
  }
}
