import { Node3dKind } from './jarvis-layout-3d';
import { Fallback2dReason, QualityLevel } from './jarvis-render-capability';

/**
 * F-236 / SF-236-06 — **la mesure du rendu d'une ouverture de l'écran** (cadrage §18). Fichier <b>pur</b> : l'heure
 * est passée à chaque appel. Elle ne garde <b>aucun contenu</b> : des compteurs aux clés d'un vocabulaire fermé,
 * le même que celui du serveur (`RenderCounts.KEYS`).
 */

/** Les classes d'i/s (histogramme à 6 classes). */
export const FPS_CLASSES = ['fps_0_20', 'fps_20_30', 'fps_30_40', 'fps_40_50', 'fps_50_60', 'fps_60p'] as const;
const FPS_BOUNDS = [20, 30, 40, 50, 60];

/** La classe d'un i/s instantané (0-5). */
export function fpsClass(fps: number): number {
  let k = 0;
  while (k < FPS_BOUNDS.length && fps >= FPS_BOUNDS[k] - 0.5) {
    k++;
  }
  return k;
}

/** La classe médiane d'un histogramme (celle où le cumul atteint 50 %) ; −1 s'il est vide. */
export function medianClass(histogram: ArrayLike<number>): number {
  let total = 0;
  for (let i = 0; i < histogram.length; i++) {
    total += histogram[i];
  }
  if (total === 0) {
    return -1;
  }
  let cumul = 0;
  for (let i = 0; i < histogram.length; i++) {
    cumul += histogram[i];
    if (cumul * 2 >= total) {
      return i;
    }
  }
  return histogram.length - 1;
}

export interface RenderReport {
  mode: '3d' | '2d';
  render: Record<string, number>;
}

const SECONDS_KEY: Record<QualityLevel, string> = { high: 'sec_high', medium: 'sec_medium', low: 'sec_low' };

export class RenderStats {
  private shownOnce = false;
  private reason: Fallback2dReason | null = null;
  private level: QualityLevel | null = null;
  private levelSince = 0;
  private readonly seconds: Record<QualityLevel, number> = { high: 0, medium: 0, low: 0 };
  private readonly counts: Record<string, number> = {};
  private reported = false;

  /** La décision de rendu a changé (2D ⇒ sa raison, retenue si la 3D n'a jamais été montrée). */
  decided(mode: '3d' | '2d', reason: Fallback2dReason | null, now: number): void {
    if (mode === '2d') {
      if (this.shownOnce && this.level !== null) {
        this.add('to2d');
        this.closeLevel(now);
        this.level = null;
      }
      this.reason ??= reason;
    }
  }

  /** La 3D est affichée, au niveau `level`. */
  shown(level: QualityLevel, now: number): void {
    this.shownOnce = true;
    this.levelAt(level, now);
  }

  /** La qualité adaptative change de niveau. */
  levelAt(level: QualityLevel, now: number): void {
    this.closeLevel(now);
    this.level = level;
    this.levelSince = now;
  }

  touch(kind: Node3dKind | 'core'): void {
    this.add(kind === 'core' ? 't_core' : `t_${kind}`);
  }

  brainPass(): void {
    this.add('brains');
  }

  entry(skipped: boolean): void {
    this.add(skipped ? 'entries_skipped' : 'entries');
  }

  /** Le compte-rendu de l'ouverture (une seule fois) ; `fps` = histogramme du moteur, `gestures` = gestes. */
  report(now: number, fps: ArrayLike<number> | null, gestures: number): RenderReport | null {
    if (this.reported) {
      return null;
    }
    this.reported = true;
    this.closeLevel(now);
    const render: Record<string, number> = { ...this.counts };
    if (this.shownOnce) {
      for (const level of Object.keys(this.seconds) as QualityLevel[]) {
        const s = Math.round(this.seconds[level]);
        if (s > 0) {
          render[SECONDS_KEY[level]] = Math.min(86_400, s);
        }
      }
      const median = fps ? medianClass(fps) : -1;
      if (median >= 0) {
        render[FPS_CLASSES[median]] = 1;
      }
      if (gestures > 0) {
        render['gestures'] = Math.min(86_400, gestures);
      }
      return { mode: '3d', render };
    }
    if (this.reason) {
      render[`r_${this.reason}`] = 1;
    }
    return { mode: '2d', render };
  }

  private closeLevel(now: number): void {
    if (this.level !== null) {
      this.seconds[this.level] += Math.max(0, now - this.levelSince) / 1000;
      this.levelSince = now;
    }
  }

  private add(key: string): void {
    this.counts[key] = Math.min(86_400, (this.counts[key] ?? 0) + 1);
  }
}
