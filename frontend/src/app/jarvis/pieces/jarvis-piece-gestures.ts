/**
 * F-238 / SF-238-11 (retour PO 2026-11-16) — **les gestes de la scène d'architecture**, purs : un doigt ou la souris
 * qui glisse fait pivoter ; deux doigts qui s'écartent ou se rapprochent zooment / dézooment (pincement, écran tactile
 * PC et téléphone) ; un toucher bref vise un élément. Le composant transmet les événements de pointeur, ce module rend
 * les demandes (les mêmes que la voix et l'outil `afficher`).
 */

export type GestureEvent =
  | { kind: 'orbit'; dAz: number; dEl: number }
  | { kind: 'zoom'; step: '+' | '-' }
  | { kind: 'tap'; x: number; y: number }
  | { kind: 'none' };

/** Un glissement commence au-delà de ce déplacement (px). */
export const DRAG_PX = 6;
/** Radians d'orbite par pixel glissé. */
export const ORBIT_PER_PX = 0.006;
/** Pincement : un cran de zoom quand l'écart des deux doigts a varié de ce facteur. */
export const PINCH_STEP = 1.35;
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
      if (spread >= this.pinchBase * PINCH_STEP) {
        this.pinchBase = spread;
        return { kind: 'zoom', step: '+' };
      }
      if (spread <= this.pinchBase / PINCH_STEP) {
        this.pinchBase = spread;
        return { kind: 'zoom', step: '-' };
      }
      return { kind: 'none' };
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

  /** Molette : un cran par événement, dans le sens de la roue. */
  static wheel(deltaY: number): GestureEvent {
    return deltaY === 0 ? { kind: 'none' } : { kind: 'zoom', step: deltaY < 0 ? '+' : '-' };
  }

  private spread(): number {
    const [a, b] = [...this.pointers.values()];
    return a && b ? Math.hypot(a.x - b.x, a.y - b.y) : 0;
  }
}
