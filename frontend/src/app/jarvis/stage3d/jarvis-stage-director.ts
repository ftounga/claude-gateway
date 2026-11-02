import { BrainEnd, BrainsState, brainsFor } from './jarvis-brains';
import { HeartMood, JarvisVoiceState, heartMood } from './jarvis-heart';

/**
 * F-236 — **le directeur de scène** (cadrage §3). Fichier <b>pur</b> : il lit les signaux existants de l'écran et
 * du bus F-234 (jamais modifiés) et produit un <b>plan déclaratif</b> ; le moteur 3D ne fait que dessiner et
 * interpoler ce plan. Chaque SF y ajoute sa part (SF-236-02 : le cœur).
 */

/** Ce que l'écran sait (lu, jamais écrit). */
export interface StageSignals {
  /** H6 — l'état de l'écran. */
  state: JarvisVoiceState;
  /** H2 — `ReaderState.frozen` : la voix a été coupée. */
  frozen: boolean;
  /** H2 — une autorisation S2 attend sa réponse (`scene.id === 'S2' && pinned`). */
  waiting: boolean;
  /** N3 — Jarvis est en veille. */
  asleep: boolean;
  /** N2 — réveils joués (onde de choc à chaque incrément). */
  wakes: number;
  /** H8 — SF-236-03 : délégations vivantes, cerveau rapide, tour en cours, fin du dernier travail. */
  delegations: number;
  fastBrainOn: boolean;
  turnActive: boolean;
  brainEnd: BrainEnd;
}

/** Ce que l'écran `/jarvis` passe à la scène 3D (la veille et les réveils sont tenus par la scène). */
export interface ScreenSignals {
  state: JarvisVoiceState;
  frozen: boolean;
  waiting: boolean;
  /** H9 — la conversation mains libres est active. */
  conversing: boolean;
  /** H10 — appels du mot « Jarvis » (ou toucher de réveil) depuis l'ouverture. */
  wakeCalls: number;
  /** H8 / H5 — SF-236-03. */
  delegations: number;
  fastBrainOn: boolean;
  turnActive: boolean;
  brainEnd: BrainEnd;
}

export const IDLE_SCREEN: ScreenSignals = { state: 'repos', frozen: false, waiting: false, conversing: false,
  wakeCalls: 0, delegations: 0, fastBrainOn: false, turnActive: false, brainEnd: 'normal' };

/** Le plan que le moteur dessine. */
export interface StagePlan {
  mood: HeartMood;
  wakes: number;
  /** SF-236-03 — les deux cerveaux. */
  brains: BrainsState;
  /** Banc seulement : les cerveaux partent déployés (séquence du retour). */
  brainsFromDeployed?: boolean;
}

export const IDLE_SIGNALS: StageSignals = { state: 'repos', frozen: false, waiting: false, asleep: false, wakes: 0,
  delegations: 0, fastBrainOn: false, turnActive: false, brainEnd: 'normal' };

export function directStage(signals: StageSignals): StagePlan {
  return {
    mood: heartMood(signals),
    wakes: signals.wakes,
    brains: brainsFor({ delegations: signals.delegations, fastBrainOn: signals.fastBrainOn,
      turnActive: signals.turnActive, end: signals.brainEnd }),
  };
}

/** Deux plans identiques ⇒ le moteur n'a rien à faire. */
export function samePlan(a: StagePlan, b: StagePlan): boolean {
  return a.mood === b.mood && a.wakes === b.wakes && a.brains.target === b.brains.target
    && a.brains.variant === b.brains.variant && a.brains.pearls === b.brains.pearls;
}
