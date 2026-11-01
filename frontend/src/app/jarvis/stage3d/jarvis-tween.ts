import { MotionCurve, ease } from './jarvis-motion';

/**
 * F-236 / SF-236-02 — **les interpolations du moteur** (cadrage §7.3, règles 1 à 3). Fichier <b>pur</b>.
 *
 * <p>Le directeur produit des <b>cibles</b> ; seul le moteur interpole, et toujours par ces deux objets :
 * jamais de saut (règle 1), une transition interrompue repart <b>de la valeur courante</b> (règle 2), durées en
 * millisecondes sur l'horloge du moteur (règle 3 : même rendu à 30 et 60 i/s). Aucune allocation par image.</p>
 */
export class Tween {
  private from: number;
  private to: number;
  private start = 0;
  private duration = 0;
  private curve: MotionCurve = 'glisse';

  constructor(value: number) {
    this.from = value;
    this.to = value;
  }

  /** La cible visée. */
  get target(): number {
    return this.to;
  }

  /** Vise `to` en `durationMs` selon `curve`, en partant de la valeur affichée à `now`. */
  set(to: number, now: number, durationMs: number, curve: MotionCurve): void {
    if (to === this.to) {
      return;
    }
    this.from = this.value(now);
    this.to = to;
    this.start = now;
    this.duration = Math.max(0, durationMs);
    this.curve = curve;
  }

  /** Coupe la transition : la valeur saute (réservé aux coupures volontaires, jamais à l'affichage normal). */
  jump(to: number): void {
    this.from = to;
    this.to = to;
    this.duration = 0;
  }

  value(now: number): number {
    if (this.duration <= 0 || now >= this.start + this.duration) {
      return this.to;
    }
    if (now <= this.start) {
      return this.from;
    }
    return this.from + (this.to - this.from) * ease(this.curve, (now - this.start) / this.duration);
  }

  /** La transition est finie à `now`. */
  settled(now: number): boolean {
    return this.duration <= 0 || now >= this.start + this.duration;
  }
}

/** Une couleur interpolée composante par composante (le moteur écrit dans un tableau qu'il possède). */
export class ColorTween {
  private readonly r: Tween;
  private readonly g: Tween;
  private readonly b: Tween;

  constructor(rgb: readonly [number, number, number]) {
    this.r = new Tween(rgb[0]);
    this.g = new Tween(rgb[1]);
    this.b = new Tween(rgb[2]);
  }

  set(rgb: readonly [number, number, number], now: number, durationMs: number, curve: MotionCurve): void {
    this.r.set(rgb[0], now, durationMs, curve);
    this.g.set(rgb[1], now, durationMs, curve);
    this.b.set(rgb[2], now, durationMs, curve);
  }

  /** Écrit la couleur à `now` dans `out` (aucune allocation). */
  write(now: number, out: { x: number; y: number; z: number } | number[]): void {
    if (Array.isArray(out)) {
      out[0] = this.r.value(now);
      out[1] = this.g.value(now);
      out[2] = this.b.value(now);
    } else {
      out.x = this.r.value(now);
      out.y = this.g.value(now);
      out.z = this.b.value(now);
    }
  }
}

/**
 * Le plus grand écart d'une interpolation entre deux images espacées de `frameMs` (règle 1, testée) : une courbe
 * nommée ne fait jamais varier une propriété de plus que ce pas sur la durée prévue.
 */
export function maxStep(from: number, to: number, durationMs: number, curve: MotionCurve, frameMs = 16): number {
  const tween = new Tween(from);
  tween.set(to, 0, durationMs, curve);
  let previous = tween.value(0);
  let worst = 0;
  for (let t = frameMs; t <= durationMs + frameMs; t += frameMs) {
    const v = tween.value(t);
    worst = Math.max(worst, Math.abs(v - previous));
    previous = v;
  }
  return worst;
}
