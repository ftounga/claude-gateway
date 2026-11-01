import { InjectionToken } from '@angular/core';

import { EngineClock } from './jarvis-banc';
import { FrameHost } from './jarvis-frame-loop';
import { QualityLevel } from './jarvis-render-capability';

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
   * Dessiner le cœur provisoire. Sur `/jarvis`, l'orbe 2D F-231 reste le cœur tant que SF-236-02 n'a pas livré le
   * cœur 3D (pas de cœur en double) ; le banc le dessine.
   */
  withCore: boolean;
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
