import { QualityLevel } from './jarvis-render-capability';

/**
 * F-236 / SF-236-02 — **la qualité adaptative à trois niveaux** (cadrage §6.3). Fichier <b>pur</b> : l'horloge et
 * l'intervalle entre images sont passés à chaque appel (tests à horloge simulée).
 *
 * <ul>
 *   <li>moyenne glissante de l'intervalle entre images sur <b>2 s</b> ;</li>
 *   <li><b>descend</b> d'un niveau si la moyenne dépasse 20 ms pendant 2 s ;</li>
 *   <li><b>remonte</b> si elle reste sous 13 ms pendant 10 s (jamais au-dessus du niveau de départ) ;</li>
 *   <li><b>au plus un changement toutes les 5 s</b> ;</li>
 *   <li>au niveau Bas, sous 30 i/s pendant 5 s ⇒ <b>repli 2D</b> (compté `low_perf`).</li>
 * </ul>
 * Aucune allocation par image : tampon circulaire préalloué.
 */

export const QUALITY_WINDOW_MS = 2_000;
export const DOWN_OVER_MS = 20;
export const DOWN_AFTER_MS = 2_000;
export const UP_UNDER_MS = 13;
export const UP_AFTER_MS = 10_000;
export const MIN_CHANGE_GAP_MS = 5_000;
export const LOW_FALLBACK_MS = 1000 / 30;
export const LOW_FALLBACK_AFTER_MS = 5_000;

/** Au-delà, l'intervalle n'est pas une image lente mais une interruption (onglet caché, débogueur). */
const MAX_SPAN_MS = 250;
const ORDER: readonly QualityLevel[] = ['low', 'medium', 'high'];
const CAPACITY = 512;

export type QualityVerdict = { kind: 'keep' } | { kind: 'change'; level: QualityLevel } | { kind: 'fallback2d' };

export class QualityGovernor {
  private readonly times = new Float64Array(CAPACITY);
  private readonly spans = new Float64Array(CAPACITY);
  private head = 0;
  private size = 0;
  private sum = 0;
  private overSince: number | null = null;
  private underSince: number | null = null;
  private lowSince: number | null = null;
  private lastChange: number;
  private current: QualityLevel;
  private gaveUp = false;

  constructor(private readonly ceiling: QualityLevel, now: number) {
    this.current = ceiling;
    this.lastChange = now;
  }

  get level(): QualityLevel {
    return this.current;
  }

  /** Moyenne glissante de l'intervalle entre images (ms) sur la fenêtre de 2 s. */
  get average(): number {
    return this.size === 0 ? 0 : this.sum / this.size;
  }

  /** Une image vient d'être dessinée, `frameMs` après la précédente. */
  sample(frameMs: number, now: number): QualityVerdict {
    if (this.gaveUp || !Number.isFinite(frameMs) || frameMs <= 0) {
      return { kind: 'keep' };
    }
    // Un onglet revenu au premier plan produit un intervalle énorme : ce n'est pas une mesure de performance ; la
    // continuité est rompue, la fenêtre repart de zéro.
    if (frameMs > MAX_SPAN_MS) {
      this.size = 0;
      this.sum = 0;
      this.overSince = null;
      this.underSince = null;
      this.lowSince = null;
      return { kind: 'keep' };
    }
    this.push(frameMs, now);
    const avg = this.average;
    // La fenêtre doit être pleine (2 s d'images) avant de juger.
    const windowFull = now - this.times[(this.head - this.size + CAPACITY) % CAPACITY] >= QUALITY_WINDOW_MS * 0.9;
    if (!windowFull) {
      return { kind: 'keep' };
    }
    this.overSince = avg > DOWN_OVER_MS ? (this.overSince ?? now) : null;
    this.underSince = avg < UP_UNDER_MS ? (this.underSince ?? now) : null;
    this.lowSince = this.current === 'low' && avg > LOW_FALLBACK_MS ? (this.lowSince ?? now) : null;

    if (this.lowSince !== null && now - this.lowSince >= LOW_FALLBACK_AFTER_MS) {
      this.gaveUp = true;
      return { kind: 'fallback2d' };
    }
    if (now - this.lastChange < MIN_CHANGE_GAP_MS) {
      return { kind: 'keep' };
    }
    const index = ORDER.indexOf(this.current);
    if (this.overSince !== null && now - this.overSince >= DOWN_AFTER_MS && index > 0) {
      return this.change(ORDER[index - 1], now);
    }
    if (this.underSince !== null && now - this.underSince >= UP_AFTER_MS && index < ORDER.indexOf(this.ceiling)) {
      return this.change(ORDER[index + 1], now);
    }
    return { kind: 'keep' };
  }

  private change(level: QualityLevel, now: number): QualityVerdict {
    this.current = level;
    this.lastChange = now;
    this.overSince = null;
    this.underSince = null;
    this.lowSince = null;
    this.size = 0;
    this.sum = 0;
    return { kind: 'change', level };
  }

  private push(span: number, now: number): void {
    // Retire ce qui sort de la fenêtre de 2 s.
    while (this.size > 0) {
      const oldest = (this.head - this.size + CAPACITY) % CAPACITY;
      if (now - this.times[oldest] <= QUALITY_WINDOW_MS && this.size < CAPACITY) {
        break;
      }
      this.sum -= this.spans[oldest];
      this.size--;
    }
    this.times[this.head] = now;
    this.spans[this.head] = span;
    this.head = (this.head + 1) % CAPACITY;
    this.size++;
    this.sum += span;
  }
}
