import { InjectionToken } from '@angular/core';

import { EngineClock } from './jarvis-banc';
import { FrameHost } from './jarvis-frame-loop';
import { QualityLevel } from './jarvis-render-capability';
import { StagePlan } from './jarvis-stage-director';

/**
 * F-236 / SF-236-01 — **le contrat du moteur 3D**, sans `three` : l'écran ne connaît que lui ; le moteur (et `three`)
 * vit dans un <b>second morceau paresseux</b>, chargé par `import()` seulement quand la décision est « 3D ».
 */
export interface JarvisEngineOptions {
  canvas: HTMLCanvasElement;
  width: number;
  height: number;
  pixelRatio: number;
  level: QualityLevel;
  clock: EngineClock;
  random: () => number;
  frameHost: FrameHost;
  /**
   * Dessiner le cœur 3D (SF-236-02). Quand il est dessiné, l'écran efface l'orbe 2D F-231 (pas de cœur en
   * double) ; son bouton reste en place pour « parler ».
   */
  withCore: boolean;
  /** SF-236-02 — le plan de départ (le banc le pose avant la première image). */
  plan: StagePlan;
  /** SF-236-02 — qualité adaptative active (pas au banc : captures reproductibles). */
  adaptive: boolean;
  /** SF-236-02 — banc : les transitions du plan de départ se rejouent depuis t = 0 (séquences de captures). */
  replayFromZero: boolean;
  /** SF-236-02 — le niveau du micro ou de la voix (0-1), lu à chaque image, hors zone. */
  voiceLevel(): number;
  /** SF-236-02 — la qualité adaptative a changé de niveau (T-Q). */
  onQualityChange(level: QualityLevel): void;
  /** SF-236-02 — niveau Bas sous 30 i/s pendant 5 s : repli 2D (`low_perf`). */
  onLowPerf(): void;
  /** Première image dessinée : l'écran lance le fondu 2D → 3D (T-F). */
  onFirstFrame(): void;
  /** Contexte WebGL perdu (T11). */
  onContextLost(): void;
  /** Contexte rendu après une perte. */
  onContextRestored(): void;
}

export interface JarvisEngine {
  start(): void;
  resize(width: number, height: number): void;
  /** SF-236-02 — un nouveau plan : le moteur interpole vers ses cibles, jamais de saut. */
  update(plan: StagePlan): void;
  /** Images dessinées depuis l'ouverture. */
  readonly frames: number;
  /** Libère tout (géométries, matières, rendu, contexte, écouteurs) ; idempotent. */
  dispose(): void;
}

export type JarvisEngineFactory = (options: JarvisEngineOptions) => JarvisEngine;

/** Charge le moteur ; injectable pour prouver qu'en repli 2D le morceau 3D n'est jamais demandé. */
export const JARVIS_3D_LOADER = new InjectionToken<() => Promise<JarvisEngineFactory>>('JARVIS_3D_LOADER', {
  providedIn: 'root',
  factory: () => () => import('./engine/jarvis-engine').then((m) => m.createJarvisEngine),
});
