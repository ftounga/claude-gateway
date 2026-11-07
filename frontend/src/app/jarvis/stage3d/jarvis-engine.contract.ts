import { InjectionToken } from '@angular/core';

import { EngineClock } from './jarvis-banc';
import { FrameHost } from './jarvis-frame-loop';
import { QualityLevel } from './jarvis-render-capability';
import { EntryKind } from './jarvis-camera-rules';
import { StagePlan } from './jarvis-stage-director';
import { UniverseTouch } from './jarvis-layout-3d';
import type { MouthShape } from '../face/jarvis-visemes';

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
  /** SF-236-04 — où poser les étiquettes DOM (au-dessus de l'écran) ; `null` = aucune étiquette. */
  labelHost: HTMLElement | null;
  /** SF-236-04 — un nœud touché (hors zone : l'appelant y revient). */
  onTouch(touch: UniverseTouch): void;
  /** SF-236-05 — l'entrée cinématique à jouer (T-E1 la première fois du jour, T-E2 ensuite, aucune au banc). */
  entry: EntryKind;
  /** SF-236-05 — le cœur touché sur le canevas : « parler » (même méthode que l'orbe). */
  onTalk(): void;
  /**
   * F-250 — dessiner le **visage** à la place du cœur quand Jarvis dialogue (coupe-circuit `APP_JARVIS_FACE_ENABLED`
   * et réglage « visage / cœur » de l'appareil). Absent ou faux : le cœur F-236, inchangé.
   */
  face?: boolean;
  /**
   * F-250 / SF-250-06 — la bouche à l'instant (visèmes calés sur `audio.currentTime`, sinon niveau sonore), lue à
   * chaque image hors zone ; écrit dans `out`, n'alloue rien.
   */
  mouth?(out: MouthShape): void;
  /** F-250 — mouvements réduits (`prefers-reduced-motion`) : le visage ne balance ni ne scintille. */
  reducedMotion?: boolean;
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
  /** SF-236-06 — la mesure du rendu : histogramme d'i/s (6 classes) et gestes commencés. */
  stats(): { fps: ArrayLike<number>; gestures: number };
  /** SF-236-05 — saute l'entrée (fondu de 300 ms vers l'état final). */
  skipEntry(): void;
  /** SF-236-05 — joue la sortie T-S (0,5 s) ; l'écran navigue ensuite. */
  exit(): void;
  /**
   * SF-236-15 — les panneaux latéraux du cockpit couvrent `left` / `right` px : l'univers se recentre dans la zone
   * libre (glissement, jamais de saut) et aucun hologramme ne se pose dessous.
   */
  setInsets(left: number, right: number): void;
  /** SF-236-15 — un nœud à l'écran (filaments liste ↔ nœud) ; `null` s'il n'est pas posé ou derrière la caméra. */
  project(key: string): { x: number; y: number } | null;
  /** SF-236-19 — regarder un nœud (recherche, inspecteur) : même cadrage qu'un toucher, sans naviguer. */
  look(key: string): void;
  /** Images dessinées depuis l'ouverture. */
  readonly frames: number;
  /** F-250 — le visage est-il affiché (présence > 0,5) ? Pour la mesure et le banc. */
  readonly faceShown?: boolean;
  /** F-250 — allume (construit au premier appel) ou éteint le visage ; le cœur reprend sa place en fondu. */
  setFace?(on: boolean): void;
  /** Libère tout (géométries, matières, rendu, contexte, écouteurs) ; idempotent. */
  dispose(): void;
}

export type JarvisEngineFactory = (options: JarvisEngineOptions) => JarvisEngine;

/** Charge le moteur ; injectable pour prouver qu'en repli 2D le morceau 3D n'est jamais demandé. */
export const JARVIS_3D_LOADER = new InjectionToken<() => Promise<JarvisEngineFactory>>('JARVIS_3D_LOADER', {
  providedIn: 'root',
  factory: () => () => import('./engine/jarvis-engine').then((m) => m.createJarvisEngine),
});
