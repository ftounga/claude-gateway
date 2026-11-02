/**
 * F-236 / SF-236-05 — **les gestes** (cadrage §9.4) sur le canevas : un doigt / la souris tourne l'univers (lacet
 * libre, tangage borné), pincer / molette zoome, double toucher (ou double clic) dans le vide = vue d'ensemble,
 * `Échap` = vue d'ensemble, un toucher bref = sélection en espace écran. Aucun calcul de scène ici : les gestes
 * sont traduits en intentions et rendus au moteur.
 */
export interface GestureSink {
  /** Rotation (rad) demandée par un glissé. */
  orbit(dYaw: number, dPitch: number): void;
  /** Zoom multiplicatif (> 1 = s'éloigner). */
  zoom(factor: number): void;
  /** Retour à la vue d'ensemble. */
  overview(): void;
  /** Toucher bref à (x, y) en pixels CSS dans le canevas. */
  tap(x: number, y: number): void;
  /** Tout premier geste (l'entrée cinématique est sautée). */
  firstTouch(): void;
}

const TAP_MOVE_PX = 8;
const TAP_MS = 350;
const DOUBLE_TAP_MS = 320;
const ROTATE_PER_PX = 0.006;

export class Gestures {
  private readonly pointers = new Map<number, { x: number; y: number; startX: number; startY: number; at: number }>();
  private pinch = 0;
  private lastTap = -Infinity;
  private touchedOnce = false;
  private readonly listeners: [EventTarget, string, EventListener][] = [];

  constructor(private readonly canvas: HTMLCanvasElement, private readonly sink: GestureSink) {
    canvas.style.touchAction = 'none';
    this.on(canvas, 'pointerdown', (e) => this.down(e as PointerEvent));
    this.on(canvas, 'pointermove', (e) => this.move(e as PointerEvent));
    this.on(canvas, 'pointerup', (e) => this.up(e as PointerEvent));
    this.on(canvas, 'pointercancel', (e) => this.pointers.delete((e as PointerEvent).pointerId));
    this.on(canvas, 'wheel', (e) => this.wheel(e as WheelEvent));
    this.on(window, 'keydown', (e) => {
      if ((e as KeyboardEvent).key === 'Escape') {
        this.sink.overview();
      }
    });
  }

  dispose(): void {
    for (const [target, type, listener] of this.listeners) {
      target.removeEventListener(type, listener);
    }
    this.listeners.length = 0;
    this.pointers.clear();
  }

  private on(target: EventTarget, type: string, listener: EventListener): void {
    target.addEventListener(type, listener, { passive: type !== 'wheel' });
    this.listeners.push([target, type, listener]);
  }

  private first(): void {
    if (!this.touchedOnce) {
      this.touchedOnce = true;
      this.sink.firstTouch();
    }
  }

  private down(e: PointerEvent): void {
    this.first();
    this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, startX: e.clientX, startY: e.clientY,
      at: performance.now() });
    if (this.pointers.size === 2) {
      this.pinch = this.spread();
    }
  }

  private move(e: PointerEvent): void {
    const p = this.pointers.get(e.pointerId);
    if (!p) {
      return;
    }
    const dx = e.clientX - p.x;
    const dy = e.clientY - p.y;
    p.x = e.clientX;
    p.y = e.clientY;
    if (this.pointers.size === 1) {
      this.sink.orbit(-dx * ROTATE_PER_PX, dy * ROTATE_PER_PX);
    } else if (this.pointers.size === 2) {
      const spread = this.spread();
      if (this.pinch > 0 && spread > 0) {
        this.sink.zoom(this.pinch / spread);
      }
      this.pinch = spread;
    }
  }

  private up(e: PointerEvent): void {
    const p = this.pointers.get(e.pointerId);
    this.pointers.delete(e.pointerId);
    if (!p || this.pointers.size > 0) {
      return;
    }
    const now = performance.now();
    const still = Math.hypot(e.clientX - p.startX, e.clientY - p.startY) < TAP_MOVE_PX;
    if (!still || now - p.at > TAP_MS) {
      return;
    }
    if (now - this.lastTap < DOUBLE_TAP_MS) {
      this.lastTap = -Infinity;
      this.sink.overview();
      return;
    }
    this.lastTap = now;
    const rect = this.canvas.getBoundingClientRect();
    this.sink.tap(e.clientX - rect.left, e.clientY - rect.top);
  }

  private wheel(e: WheelEvent): void {
    e.preventDefault();
    this.first();
    this.sink.zoom(Math.exp(Math.max(-1, Math.min(1, e.deltaY / 400))));
  }

  private spread(): number {
    const [a, b] = [...this.pointers.values()];
    return a && b ? Math.hypot(a.x - b.x, a.y - b.y) : 0;
  }
}
