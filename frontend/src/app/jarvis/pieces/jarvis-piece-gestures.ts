/**
 * F-238 / SF-238-11 (retour PO 2026-11-16) — **les gestes de la scène d'architecture**, purs : un doigt ou la souris
 * qui glisse fait pivoter ; deux doigts qui s'écartent ou se rapprochent zooment / dézooment (pincement, écran tactile
 * PC et téléphone) ; un toucher bref vise un élément. Le composant transmet les événements de pointeur, ce module rend
 * les demandes (les mêmes que la voix et l'outil `afficher`).
 */

export type GestureEvent =
  | { kind: 'orbit'; dAz: number; dEl: number }
  /** SF-238-15 — zoom continu (facteur) vers un point de la fenêtre (milieu des doigts, pointeur de la souris). */
  | { kind: 'zoom'; factor: number; x: number; y: number }
  | { kind: 'tap'; x: number; y: number }
  | { kind: 'none' };

/** Un glissement commence au-delà de ce déplacement (px). */
export const DRAG_PX = 6;
/** Radians d'orbite par pixel glissé. */
export const ORBIT_PER_PX = 0.006;
/** Pincement : on émet dès que l'écart des deux doigts a varié de ce facteur (lissage). */
export const PINCH_STEP = 1.04;
/**
 * SF-238-15 — sensibilité du pincement : zoom = (variation de l'écart)^0,75 ; un pincement normal (écart ×2) ≈ ×1,7,
 * pas ×4 ni plus.
 */
export const PINCH_EXPONENT = 0.75;
/** SF-238-15 — molette : ×1,15 par cran (100 px), borné par événement (pavés tactiles, molettes rapides). */
export const WHEEL_STEP = 1.15;
export const WHEEL_MAX = 1.5;
/** Un toucher plus long n'est pas un toucher bref (le toucher long replie, géré à part). */
export const TAP_MAX_MS = 500;

interface Pointer {
  x: number;
  y: number;
}

export class ArchGestures {
  private readonly pointers = new Map<number, Pointer>();
  private start: { x: number; y: number; at: number } | null = null;
  private dragged = false;
  private pinchBase: number | null = null;
  /** Un pincement a eu lieu : la levée des doigts n'est pas un toucher. */
  private pinched = false;

  get dragging(): boolean {
    return this.dragged;
  }

  get pinching(): boolean {
    return this.pinchBase !== null;
  }

  down(id: number, x: number, y: number, at: number): GestureEvent {
    this.pointers.set(id, { x, y });
    if (this.pointers.size === 1) {
      this.start = { x, y, at };
      this.dragged = false;
      this.pinched = false;
    } else if (this.pointers.size === 2) {
      this.pinchBase = this.spread();
      this.pinched = true;
    }
    return { kind: 'none' };
  }

  move(id: number, x: number, y: number): GestureEvent {
    const p = this.pointers.get(id);
    if (!p) {
      return { kind: 'none' };
    }
    const dx = x - p.x;
    const dy = y - p.y;
    p.x = x;
    p.y = y;
    if (this.pointers.size >= 2 && this.pinchBase !== null) {
      const spread = this.spread();
      if (this.pinchBase <= 0 || spread <= 0) {
        this.pinchBase = spread;
        return { kind: 'none' };
      }
      const ratio = spread / this.pinchBase;
      if (ratio < PINCH_STEP && ratio > 1 / PINCH_STEP) {
        return { kind: 'none' };
      }
      this.pinchBase = spread;
      const [a, b] = [...this.pointers.values()];
      return { kind: 'zoom', factor: Math.pow(ratio, PINCH_EXPONENT), x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
    }
    if (!this.start || this.pinched) {
      return { kind: 'none' };
    }
    if (!this.dragged && Math.abs(x - this.start.x) + Math.abs(y - this.start.y) < DRAG_PX) {
      return { kind: 'none' };
    }
    this.dragged = true;
    return { kind: 'orbit', dAz: -dx * ORBIT_PER_PX, dEl: dy * ORBIT_PER_PX };
  }

  up(id: number, x: number, y: number, at: number): GestureEvent {
    this.pointers.delete(id);
    if (this.pointers.size < 2) {
      this.pinchBase = null;
    }
    if (this.pointers.size > 0) {
      return { kind: 'none' };
    }
    const start = this.start;
    this.start = null;
    const tap = !!start && !this.dragged && !this.pinched && at - start.at <= TAP_MAX_MS;
    this.dragged = false;
    this.pinched = false;
    return tap ? { kind: 'tap', x, y } : { kind: 'none' };
  }

  cancel(): void {
    this.pointers.clear();
    this.start = null;
    this.dragged = false;
    this.pinched = false;
    this.pinchBase = null;
  }

  /**
   * Molette : ×{@link WHEEL_STEP} par cran (100 px ; `deltaMode` 1 = lignes, 2 = pages) vers le pointeur, dans le sens
   * de la roue, borné à ×{@link WHEEL_MAX} par événement.
   */
  static wheel(deltaY: number, x: number, y: number, deltaMode = 0): GestureEvent {
    if (deltaY === 0) {
      return { kind: 'none' };
    }
    const notches = deltaMode === 1 ? deltaY / 3 : deltaMode === 2 ? deltaY : deltaY / 100;
    const factor = Math.min(WHEEL_MAX, Math.max(1 / WHEEL_MAX, Math.pow(WHEEL_STEP, -notches)));
    return { kind: 'zoom', factor, x, y };
  }

  private spread(): number {
    const [a, b] = [...this.pointers.values()];
    return a && b ? Math.hypot(a.x - b.x, a.y - b.y) : 0;
  }
}
