/**
 * F-250 / SF-250-04 — **la géométrie du visage**, générée par code. Fichier <b>pur</b> (aucun `three`).
 *
 * <p><b>Visage générique</b> (cadrage §1.1) : un ovale, des proportions moyennes (règle des tiers), des reliefs
 * gaussiens. Jamais une personne réelle, jamais un personnage existant, aucun maillage importé, aucune photo.</p>
 *
 * <p>Repère : Y vers le haut (front 1,08 → menton −0,74), X latéral, Z vers la caméra. Tout est calculé <b>une
 * fois</b> à la construction ; la bouche, les paupières, le regard et les sourcils se déforment ensuite <b>dans le
 * shader de sommets</b> (uniformes), d'où zéro calcul par image côté processeur.</p>
 */

/** Hauteur de la ligne des lèvres. */
export const LIP_Y = -0.34;
/** Les yeux : centre (±x, y) et demi-largeur. */
export const EYE = { x: 0.28, y: 0.235, hw: 0.105 } as const;
/** Demi-largeur de la bouche au repos. */
export const MOUTH_HALF = 0.165;

/** Pas de la grille par niveau : ≈ 5 900 points au PC, ≈ 2 900 au téléphone (cadrage §4). */
export const FACE_STEP = { high: 0.019, medium: 0.022, low: 0.028 } as const;

const clamp = (x: number, a: number, b: number): number => Math.min(b, Math.max(a, x));
const smoothstep = (a: number, b: number, x: number): number => {
  const t = clamp((x - a) / (b - a), 0, 1);
  return t * t * (3 - 2 * t);
};
const gauss = (dx: number, sx: number, dy: number, sy: number): number =>
  Math.exp(-(dx * dx) / (sx * sx) - (dy * dy) / (sy * sy));

/** La demi-largeur du visage à la hauteur Y (ovale, mâchoire, menton). */
export function halfWidth(y: number): number {
  if (y > 0.1) {
    const k = (y - 0.1) / 0.98;
    return k >= 1 ? 0 : 0.76 * Math.pow(1 - Math.pow(k, 2.6), 1 / 2.2);
  }
  const t = Math.min(1, (0.1 - y) / 0.84);
  const tail = Math.max(0, t - 0.84) / 0.16;
  return (0.76 - 0.06 * t - 0.42 * Math.pow(t, 2.3)) * Math.sqrt(Math.max(0, 1 - tail * tail));
}

/** La profondeur de la surface en (X, Y) : ovale + reliefs gaussiens (arcades, orbites, nez, lèvres, menton…). */
export function surfaceZ(x: number, y: number): number {
  const w = Math.max(1e-3, halfWidth(y));
  const e = Math.min(1, Math.abs(x) / w);
  const ax = Math.abs(x);
  const vf = y > 0.35 ? 0.4 + 0.6 * Math.sqrt(Math.max(0, 1 - ((y - 0.35) / 0.76) ** 2))
    : y < -0.42 ? 0.86 + 0.14 * (1 - smoothstep(-0.42, -0.74, y)) : 1;
  const z = 0.6 * vf * Math.pow(Math.max(0, 1 - e * e), 0.5) - 0.1;
  const front = 1 - e ** 4;
  let f = 0;
  f += 0.05 * gauss(ax - 0.27, 0.2, y - 0.37, 0.06); // arcades
  f -= 0.10 * gauss(ax - 0.28, 0.15, y - 0.24, 0.085); // orbites
  f += 0.05 * gauss(ax - 0.28, 0.08, y - 0.235, 0.045); // globes
  if (y < 0.34 && y > -0.26) {
    // Nez : arête qui monte vers la pointe.
    const t = clamp((0.30 - y) / 0.42, 0, 1);
    const h = 0.035 + 0.25 * Math.pow(t, 1.6);
    const s = 0.048 + 0.05 * t;
    const fall = y > -0.12 ? 1 : Math.exp(-(((y + 0.12) / 0.035) ** 2));
    f += h * Math.exp(-((x / s) ** 2)) * fall * smoothstep(0.34, 0.27, y);
  }
  f += 0.06 * gauss(ax - 0.085, 0.05, y + 0.115, 0.04); // ailes du nez
  f -= 0.014 * gauss(x, 0.12, y + 0.22, 0.04); // sillon sous le nez
  f += 0.07 * gauss(x, 0.27, y - LIP_Y, 0.17); // avancée de la bouche
  f += 0.026 * gauss(x, 0.15, y - (LIP_Y + 0.026), 0.022); // lèvre supérieure
  f += 0.032 * gauss(x, 0.13, y - (LIP_Y - 0.04), 0.028); // lèvre inférieure
  f += 0.05 * gauss(x, 0.14, y + 0.6, 0.08); // menton
  f += 0.05 * gauss(ax - 0.42, 0.13, y - 0.03, 0.1); // pommettes
  f -= 0.03 * gauss(ax - 0.42, 0.12, y + 0.24, 0.12); // joues
  f -= 0.03 * gauss(ax - 0.62, 0.1, y - 0.35, 0.15); // tempes
  return z + f * front;
}

/**
 * La déformation de la bouche (référence CPU de ce que fait le shader, utilisée par les tests et la planche) :
 * `above` = le point est au-dessus de la fente des lèvres.
 */
export function deformMouth(x: number, y: number, z: number, above: boolean,
                            m: { open: number; wide: number; round: number; press: number },
                            out: number[]): void {
  const dyL = y - LIP_Y;
  const xr = x / MOUTH_HALF;
  const slit = Math.pow(Math.max(0, 1 - xr * xr), 0.75);
  const open = m.open * (1 - 0.92 * m.press);
  const wm = Math.exp(-(x * x) / 0.1024) * Math.exp(-(dyL * dyL) / 0.0256);
  const dx = x * (0.24 * m.wide - 0.42 * m.round) * wm;
  let dz = (0.075 * m.round - 0.012 * m.press) * Math.exp(-(x * x) / 0.04) * Math.exp(-(dyL * dyL) / 0.0081);
  let dy = 0;
  if (above) {
    dy += open * 0.06 * slit * Math.exp(-(dyL * dyL) / 0.0049);
  } else {
    const lowJaw = Math.exp(-(x * x) / 0.1764) * smoothstep(0, -0.14, dyL);
    dy -= open * (0.13 * slit * Math.exp(-(dyL * dyL) / 0.0081) + 0.12 * lowJaw);
    dz -= open * 0.04 * lowJaw;
  }
  out[0] = x + dx;
  out[1] = y + dy;
  out[2] = z + dz;
}

/** La grille du visage : points + fil de fer (indices) partageant les mêmes sommets. */
export interface FaceGrid {
  /** Positions de repos (x, y, z). */
  rest: Float32Array;
  /** Graine par point (scintillement, dissolution). */
  seed: Float32Array;
  /** Distance au bord (0 centre → 1 bord). */
  edge: Float32Array;
  /** Zones incandescentes (lèvres, yeux, arête du nez). */
  hot: Float32Array;
  /** Éclairage factice précalculé : (lumière haut-gauche, liseré de bord). */
  lit: Float32Array;
  /** 1 si le point est au-dessus de la fente des lèvres. */
  above: Float32Array;
  /** Position de départ sur la sphère du cœur (transition T-F1 cœur → visage). */
  from: Float32Array;
  /** Paires d'indices du fil de fer (une maille sur `wireEvery`), coupées sur la fente de la bouche. */
  wire: Uint32Array;
  count: number;
}

/**
 * Génère la grille au pas `step` ; `wireEvery` = une maille sur combien pour le fil de fer ; `heartRadius` = rayon
 * de la sphère d'où les points partent (T-F1). `random` = générateur graine (même graine ⇒ même visage).
 */
export function buildFaceGrid(step: number, wireEvery: number, heartRadius: number, random: () => number): FaceGrid {
  const nx = Math.round(1.6 / step) + 1;
  const ny = Math.round(1.86 / step) + 1;
  const index = new Int32Array(nx * ny).fill(-1);
  const rest: number[] = [];
  const seed: number[] = [];
  const edge: number[] = [];
  const hot: number[] = [];
  const lit: number[] = [];
  for (let j = 0; j < ny; j++) {
    const y = -0.76 + j * step;
    const w = halfWidth(y);
    for (let i = 0; i < nx; i++) {
      const x0 = -0.8 + i * step;
      const jitter = (random() - 0.5) * step * 0.18;
      if (w <= 0.02 || Math.abs(x0) > w * 1.02) {
        continue;
      }
      index[j * nx + i] = rest.length / 3;
      const x = x0 + jitter;
      const z = surfaceZ(x, y);
      rest.push(x, y, z);
      seed.push(random());
      edge.push(Math.min(1, Math.abs(x) / w));
      const ax = Math.abs(x);
      const h = 0.75 * gauss(x, 0.2, y - LIP_Y, 0.06) + 0.55 * gauss(ax - EYE.x, 0.12, y - EYE.y, 0.06)
        + 0.35 * Math.exp(-((x / 0.035) ** 2)) * smoothstep(0.3, 0.2, y) * smoothstep(-0.16, -0.08, y);
      hot.push(Math.min(1, h));
      const ex = 0.006;
      const gx = (surfaceZ(x + ex, y) - surfaceZ(x - ex, y)) / (2 * ex);
      const gy = (surfaceZ(x, y + ex) - surfaceZ(x, y - ex)) / (2 * ex);
      const nl = Math.hypot(gx, gy, 1);
      const nX = -gx / nl;
      const nY = -gy / nl;
      const nZ = 1 / nl;
      lit.push(clamp(nX * -0.42 + nY * 0.5 + nZ * 0.76, 0, 1), clamp(1 - nZ, 0, 1));
    }
  }
  const count = rest.length / 3;
  const restArr = new Float32Array(rest);
  const above = new Float32Array(count);
  const from = new Float32Array(count * 3);
  const golden = Math.PI * (3 - Math.sqrt(5));
  for (let k = 0; k < count; k++) {
    above[k] = restArr[k * 3 + 1] >= LIP_Y ? 1 : 0;
    // Le cœur : une sphère de Fibonacci, le même nombre de points, en désordre (la graine les mélange).
    const yy = 1 - (k / Math.max(count - 1, 1)) * 2;
    const ring = Math.sqrt(1 - yy * yy);
    const a = golden * k;
    const r = heartRadius * (0.3 + 0.7 * Math.cbrt(random()));
    from[k * 3] = Math.cos(a) * ring * r;
    from[k * 3 + 1] = yy * r;
    from[k * 3 + 2] = Math.sin(a) * ring * r;
  }
  const wire: number[] = [];
  const crossesMouth = (a: number, b: number): boolean => above[a] !== above[b]
    && Math.min(Math.abs(restArr[a * 3]), Math.abs(restArr[b * 3])) < 0.23
    && Math.abs(restArr[a * 3 + 1] - LIP_Y) < 0.1;
  const link = (i0: number, j0: number, i1: number, j1: number): void => {
    if (i1 >= nx || j1 >= ny || i1 < 0) {
      return;
    }
    const a = index[j0 * nx + i0];
    const b = index[j1 * nx + i1];
    if (a < 0 || b < 0 || crossesMouth(a, b)) {
      return;
    }
    wire.push(a, b);
  };
  const l = wireEvery;
  for (let j = 0; j < ny; j += l) {
    for (let i = 0; i < nx; i += l) {
      link(i, j, i + l, j);
      link(i, j, i, j + l);
      if ((i / l + j / l) % 2 === 0) {
        link(i, j, i + l, j + l);
      } else {
        link(i + l, j, i, j + l);
      }
    }
  }
  return {
    rest: restArr, seed: new Float32Array(seed), edge: new Float32Array(edge), hot: new Float32Array(hot),
    lit: new Float32Array(lit), above, from, wire: new Uint32Array(wire), count,
  };
}

/**
 * Les familles de traits lumineux. Le shader déplace chaque sommet selon sa famille (paupières avec `uLid`, iris
 * avec le regard, sourcils avec `uBrow`, lèvres avec la bouche) : la géométrie ne change jamais.
 */
export const FEATURE_KIND = {
  lip: 0, upperLid: 1, lowerLid: 2, crease: 3, iris: 4, pupil: 5, brow: 6, nose: 7, mouthGlow: 8,
} as const;

/** Les traits : segments (paires de sommets) avec leurs paramètres de déformation. */
export interface FaceFeatures {
  /** Position de repos (paupière ouverte, sourcils au repos, regard au centre). */
  position: Float32Array;
  /** Pour la lueur de bouche : le point de la lèvre inférieure intérieure (mélangé selon `param.x`). */
  other: Float32Array;
  /** Famille ({@link FEATURE_KIND}). */
  kind: Float32Array;
  /** 1 au-dessus de la fente des lèvres. */
  above: Float32Array;
  /** Paramètres par famille (voir le shader). */
  param: Float32Array;
  /** Opacité de base. */
  alpha: Float32Array;
  /** Part incandescente (0-1). */
  hot: Float32Array;
  /** Nombre de sommets (2 par segment). */
  count: number;
}

class FeatureWriter {
  readonly pos: number[] = [];
  readonly other: number[] = [];
  readonly kind: number[] = [];
  readonly above: number[] = [];
  readonly param: number[] = [];
  readonly alpha: number[] = [];
  readonly hot: number[] = [];

  vertex(p: readonly number[], kind: number, above: number, param: readonly number[], alpha: number, hot: number,
         other: readonly number[] = p): void {
    this.pos.push(p[0], p[1], p[2]);
    this.other.push(other[0], other[1], other[2]);
    this.kind.push(kind);
    this.above.push(above);
    this.param.push(param[0], param[1], param[2]);
    this.alpha.push(alpha);
    this.hot.push(hot);
  }

  /** Une courbe de `n` segments : `at(s)` rend le sommet (position, paramètres, autre point) pour s ∈ [0, 1]. */
  curve(n: number, kind: number, above: number, alpha: number, hot: number,
        at: (s: number) => { p: number[]; param?: number[]; other?: number[] }): void {
    let prev = at(0);
    for (let i = 1; i <= n; i++) {
      const next = at(i / n);
      this.vertex(prev.p, kind, above, prev.param ?? [0, 0, 0], alpha, hot, prev.other ?? prev.p);
      this.vertex(next.p, kind, above, next.param ?? [0, 0, 0], alpha, hot, next.other ?? next.p);
      prev = next;
    }
  }
}

const onFace = (x: number, y: number, lift = 0.012): number[] => [x, y, surfaceZ(x, y) + lift];

/** Les lèvres : 0 contour supérieur, 1 intérieur haut, 2 intérieur bas, 3 contour inférieur (s ∈ [−1, 1]). */
function lipRest(s: number, kind: 0 | 1 | 2 | 3): number[] {
  const x = s * MOUTH_HALF;
  const u = 1 - s * s;
  const y = kind === 0 ? LIP_Y + 0.012 + 0.03 * Math.pow(u, 0.6) - 0.013 * Math.exp(-((s / 0.16) ** 2))
    : kind === 1 ? LIP_Y + 0.004 * u
      : kind === 2 ? LIP_Y - 0.004 * u
        : LIP_Y - 0.012 - 0.045 * Math.pow(u, 0.7);
  return [x, y, surfaceZ(x, y) + 0.012];
}

/** Construit les traits lumineux (lèvres, lueur de bouche, paupières, pli, iris, pupille, sourcils, nez). */
export function buildFaceFeatures(detail: number): FaceFeatures {
  const w = new FeatureWriter();
  const n = (k: number): number => Math.max(6, Math.round(k * detail));
  const L = FEATURE_KIND;
  // Lèvres (arc de Cupidon sur le contour supérieur).
  w.curve(n(36), L.lip, 1, 0.7, 0.45, (s) => ({ p: lipRest(s * 2 - 1, 0) }));
  w.curve(n(30), L.lip, 1, 0.75, 0.36, (s) => ({ p: lipRest(s * 2 - 1, 1) }));
  w.curve(n(30), L.lip, 0, 0.75, 0.36, (s) => ({ p: lipRest(s * 2 - 1, 2) }));
  w.curve(n(36), L.lip, 0, 0.7, 0.45, (s) => ({ p: lipRest(s * 2 - 1, 3) }));
  // Lueur intérieure : trois fils entre les lèvres, visibles quand la bouche s'ouvre.
  for (let k = 1; k <= 3; k++) {
    const f = k / 4;
    w.curve(n(18), L.mouthGlow, 1, 0.25, 1, (s) => {
      const t = s * 1.6 - 0.8;
      const up = lipRest(t, 1);
      const lo = lipRest(t, 2);
      return { p: [up[0], up[1], up[2] - 0.02], other: [lo[0], lo[1], lo[2] - 0.02], param: [f, 0, 0] };
    });
  }
  for (const side of [-1, 1]) {
    // Paupière supérieure : Y(lid) = Y(1) + (lid − 1) × pente.
    w.curve(n(24), L.upperLid, 1, 0.85, 0.5, (s) => {
      const t = s * 2 - 1;
      const x = side * EYE.x + t * EYE.hw;
      const u = 1 - t * t;
      const open = 0.044 * Math.pow(u, 0.8) + 0.006 * t * side;
      return { p: onFace(x, EYE.y - 0.004 + open, 0.014), param: [open + 0.012, 0, 0] };
    });
    w.curve(n(22), L.lowerLid, 1, 0.45, 0.3, (s) => {
      const t = s * 2 - 1;
      const x = side * EYE.x + t * EYE.hw;
      return { p: onFace(x, EYE.y - 0.004 - 0.03 * Math.pow(1 - t * t, 0.9), 0.014) };
    });
    // Pli de la paupière, discret ; suit le sourcil à moitié.
    w.curve(n(16), L.crease, 1, 0.22, 0.2, (s) => {
      const t = s * 2 - 1;
      const x = side * EYE.x + t * EYE.hw * 0.92;
      return { p: onFace(x, EYE.y + 0.052 * Math.pow(1 - t * t, 0.7) + 0.012, 0.01), param: [0.5, 0, 0] };
    });
    // Iris et pupille : centre de l'œil + décalage (le shader ajoute le regard et écrase en Y avec la paupière).
    const centre = onFace(side * EYE.x, EYE.y - 0.006, 0.02);
    for (const [kind, r, segments, alpha, hot] of [[L.iris, 0.031, 28, 0.75, 0.55], [L.pupil, 0.031 * 0.32, 12, 0.8, 1]]) {
      w.curve(n(segments), kind, 1, alpha, hot, (s) => {
        const a = s * Math.PI * 2;
        return { p: centre, param: [Math.cos(a) * r, Math.sin(a) * r, kind === L.pupil ? 0.002 : 0] };
      });
    }
    // Sourcil : Y += brow + (côté droit) browR × u.
    w.curve(n(20), L.brow, 1, 0.6, 0.35, (s) => {
      const x = side * (0.13 + s * 0.32);
      const u = Math.sin(s * Math.PI);
      return { p: onFace(x, 0.335 + 0.035 * u, 0.012), param: [u, side > 0 ? 1 : 0, 0] };
    });
  }
  // Arête du nez et narines.
  for (let i = 1; i <= 14; i++) {
    w.vertex(onFace(0, 0.26 - (i - 1) * 0.027, 0.008), L.nose, 1, [0, 0, 0], 0.35, 0.3);
    w.vertex(onFace(0, 0.26 - i * 0.027, 0.008), L.nose, 1, [0, 0, 0], 0.35, 0.3);
  }
  w.curve(n(14), L.nose, 1, 0.4, 0.3, (s) => {
    const a = Math.PI * (0.15 + 0.7 * s);
    return { p: onFace(Math.cos(a) * 0.075, -0.13 - Math.sin(a) * 0.025, 0.006) };
  });
  return {
    position: new Float32Array(w.pos), other: new Float32Array(w.other), kind: new Float32Array(w.kind),
    above: new Float32Array(w.above), param: new Float32Array(w.param), alpha: new Float32Array(w.alpha),
    hot: new Float32Array(w.hot), count: w.kind.length,
  };
}
