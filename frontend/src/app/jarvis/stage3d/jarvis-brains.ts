import { MotionCurve, ease } from './jarvis-motion';

/**
 * F-236 / SF-236-03 — **les deux cerveaux** (cadrage §10, D8) — la scène signature. Fichier <b>pur</b>.
 *
 * <p>Le petit cœur (cerveau rapide, F-235) ne disparaît jamais : quand le cerveau principal (Opus) travaille, un
 * <b>champ neuronal violet</b> se déploie autour de lui (aller T-B1, 1,6 s), respire pendant le travail (T-B2), puis
 * se replie (retour T-B3, 1,2 s ; 0,8 s sans éclat si annulé). Ce fichier dit <b>quand</b> (machine d'état) et
 * <b>comment</b> (chorégraphie, valeurs à chaque instant) ; le moteur ne fait que dessiner ces valeurs.</p>
 */

export type BrainTarget = 'folded' | 'deployed';
/** Comment se termine le travail : normal (flash doux, la réponse est prête), annulé (sans éclat), échec (perle rouge). */
export type BrainEnd = 'normal' | 'cancel' | 'fail';

/** Ce que l'écran sait des cerveaux (H8, H5) — lu, jamais écrit. */
export interface BrainsInput {
  /** Délégations vivantes à Opus (F-235). */
  delegations: number;
  /** Le cerveau rapide répond (sinon coupe-circuit : chaque tour est un tour Opus). */
  fastBrainOn: boolean;
  /** Un tour est en cours (bus F-234). */
  turnActive: boolean;
  /** Comment s'est terminé le dernier travail (posé par l'écran au moment où il se termine). */
  end: BrainEnd;
}

export interface BrainsState {
  target: BrainTarget;
  /** Fin du dernier repli. */
  variant: BrainEnd;
  /** Une perle par délégation vivante (au moins une pendant un tour Opus direct). */
  pearls: number;
}

export const FOLDED: BrainsState = { target: 'folded', variant: 'normal', pearls: 0 };

/**
 * La règle (§10.1) : cerveau rapide actif ⇒ déployé tant qu'une délégation vit (n → n+1 déploie, la dernière finie
 * replie) ; coupe-circuit (`fastBrainOn` faux) ⇒ chaque tour est un tour Opus : déployé pendant `turnActive`.
 */
export function brainsFor(input: BrainsInput): BrainsState {
  const deployed = input.fastBrainOn ? input.delegations > 0 : input.turnActive;
  if (!deployed) {
    return { target: 'folded', variant: input.end, pearls: 0 };
  }
  return { target: 'deployed', variant: 'normal', pearls: Math.max(1, Math.min(6, input.delegations)) };
}

/** Durées (§7.4). */
export const BRAIN_UNFOLD_MS = 1600;
export const BRAIN_FOLD_MS = 1200;
export const BRAIN_CANCEL_MS = 800;

/** Ce que le moteur dessine à un instant donné. */
export interface BrainFrame {
  /** Déploiement des particules : 0 = rayon 1,4 (dans le cœur), 1 = rayon 3,2. */
  deploy: number;
  /** Teinte : 0 = normale (orange), 1 = violet profond. */
  hue: number;
  /** Anneaux du gyroscope (0 = 3 anneaux, 1 = 5). */
  rings: number;
  /** Connexions révélées du centre vers l'extérieur (0-1). */
  reveal: number;
  /** Échelle du petit cœur (contraction 0,85, rebond 1,03). */
  coreScale: number;
  /** Lumière du petit cœur (multiplicateur). */
  coreLight: number;
  /** Surcroît de bloom apporté par les cerveaux (ajouté à celui du cœur : 0 au repos, +0,5 à l'étincelle). */
  bloom: number;
  /** Onde de choc violette (avancement 0-1 ; −1 = aucune). */
  shock: number;
  /** Flash au centre (0-1). */
  flash: number;
  /** Les éclairs convergent vers le centre (retour, 0-1). */
  gather: number;
  /** Écart de l'univers et recul de la caméra (0-1). */
  spread: number;
  /** Perle de délégation : 0 = absente, 1 = vivante ; couleur selon la fin. */
  pearl: number;
}

const REST: BrainFrame = { deploy: 0, hue: 0, rings: 0, reveal: 0, coreScale: 1, coreLight: 1, bloom: 0, shock: -1,
  flash: 0, gather: 0, spread: 0, pearl: 0 };

/** Avancement d'une phase [a, b] (ms) à `t`, selon `curve`. */
export function phase(t: number, a: number, b: number, curve: MotionCurve): number {
  return ease(curve, (t - a) / (b - a));
}

/** T-B1 — l'aller, à `t` ms du début (§10.2). */
export function unfoldFrame(t: number, out: BrainFrame = { ...REST }): BrainFrame {
  if (t >= BRAIN_UNFOLD_MS) {
    return deployedFrame(out);
  }
  const inhale = phase(t, 0, 200, 'entree');
  const spark = t >= 200 && t < 450 ? 1 - Math.abs((t - 325) / 125) : 0;
  const bloomMs = phase(t, 450, 1300, 'sortie');
  const settle = phase(t, 1300, BRAIN_UNFOLD_MS, 'rebond');
  out.deploy = bloomMs;
  out.hue = phase(t, 450, 1300, 'glisse');
  out.rings = bloomMs;
  out.reveal = bloomMs;
  // Contraction 1 → 0,85, puis éclosion jusqu'au léger dépassement 1,03, puis stabilisation à 1.
  out.coreScale = t < 450 ? 1 - 0.15 * inhale : t < 1300 ? 0.85 + 0.18 * bloomMs : 1.03 - 0.03 * settle;
  out.coreLight = t < 450 ? 1 - 0.2 * inhale : 0.8 + 0.2 * bloomMs;
  // Bloom : éclat à l'étincelle (1,6), montée pendant l'éclosion, stabilisation à 1,3.
  out.bloom = t < 1300 ? 0.5 * Math.max(spark, bloomMs) : 0.5 - 0.3 * settle;
  out.shock = t >= 200 && t < 1300 ? phase(t, 200, 1300, 'sortie') : -1;
  out.flash = Math.max(0, spark);
  out.gather = 0;
  out.spread = phase(t, 450, 1300, 'glisse');
  out.pearl = t >= 200 ? 1 : 0;
  return out;
}

/** T-B2 — le régime déployé. */
export function deployedFrame(out: BrainFrame = { ...REST }): BrainFrame {
  out.deploy = 1;
  out.hue = 1;
  out.rings = 1;
  out.reveal = 1;
  out.coreScale = 1;
  out.coreLight = 1;
  out.bloom = 0.2;
  out.shock = -1;
  out.flash = 0;
  out.gather = 0;
  out.spread = 1;
  out.pearl = 1;
  return out;
}

/** Repos (replié). */
export function foldedFrame(out: BrainFrame = { ...REST }): BrainFrame {
  Object.assign(out, REST);
  return out;
}

/**
 * T-B3 — le retour, à `t` ms du début (§10.4), depuis un déploiement `from` (0-1 : une transition interrompue repart
 * de l'état courant, jamais du début). `cancel` : 0,8 s, sans éclat, perle qui s'éteint.
 */
export function foldFrame(t: number, variant: BrainEnd, from: number, out: BrainFrame = { ...REST }): BrainFrame {
  const total = foldDuration(variant);
  if (t >= total) {
    return foldedFrame(out);
  }
  if (variant === 'cancel') {
    const p = phase(t, 0, total, 'glisse');
    const left = from * (1 - p);
    out.deploy = left;
    out.hue = left;
    out.rings = left;
    out.reveal = left;
    out.coreScale = 1;
    out.coreLight = 1;
    out.bloom = 0.2 * left;
    out.shock = -1;
    out.flash = 0;
    out.gather = 0;
    out.spread = left;
    out.pearl = 1 - p;
    return out;
  }
  const fold = phase(t, 300, 900, 'glisse');
  const left = from * (1 - fold);
  out.deploy = left;
  out.hue = left;
  out.rings = left;
  // Les connexions disparaissent de l'extérieur vers l'intérieur : le seuil de révélation baisse.
  out.reveal = left;
  out.coreScale = 1;
  const deposit = t >= 900 ? 1 - Math.abs((t - 1000) / 200) : 0;
  out.flash = variant === 'normal' ? Math.max(0, deposit) * 0.6 : 0;
  out.coreLight = 1 + out.flash * 0.3;
  out.bloom = 0.2 * left + out.flash * 0.3;
  out.shock = -1;
  out.gather = t < 300 ? phase(t, 0, 300, 'glisse') : 1 - phase(t, 300, 600, 'glisse');
  out.spread = left;
  // La perle entre dans le noyau au dépôt ; en échec, elle reste rouge jusqu'au repli.
  out.pearl = t < 900 ? 1 : 1 - phase(t, 900, total, 'entree');
  return out;
}

/** L'avancement `x` tel que `ease('sortie', x) = y` (bissection). */
export function inverseEase(y: number): number {
  let lo = 0;
  let hi = 1;
  for (let i = 0; i < 30; i++) {
    const mid = (lo + hi) / 2;
    if (ease('sortie', mid) < y) {
      lo = mid;
    } else {
      hi = mid;
    }
  }
  return (lo + hi) / 2;
}

export function foldDuration(variant: BrainEnd): number {
  return variant === 'cancel' ? BRAIN_CANCEL_MS : BRAIN_FOLD_MS;
}

/**
 * L'horloge des cerveaux : un changement de cible part de l'image courante (aller interrompu ⇒ retour depuis le
 * déploiement atteint). Pure : l'heure est passée à chaque appel ; aucune allocation par image.
 */
export class BrainsClock {
  private target: BrainTarget = 'folded';
  private variant: BrainEnd = 'normal';
  private start = 0;
  private from = 0;
  readonly frame: BrainFrame = foldedFrame();

  constructor(initial: BrainTarget = 'folded') {
    this.target = initial;
    if (initial === 'deployed') {
      this.from = 1;
      deployedFrame(this.frame);
    }
  }

  get current(): BrainTarget {
    return this.target;
  }

  /** Nouvelle cible à `now` ; sans effet si elle ne change pas. */
  set(target: BrainTarget, variant: BrainEnd, now: number): void {
    if (target === this.target) {
      return;
    }
    this.from = this.frame.deploy;
    this.target = target;
    this.variant = variant;
    this.start = now;
  }

  /** Les valeurs à `now`, écrites dans {@link frame}. */
  at(now: number): BrainFrame {
    const t = Math.max(0, now - this.start);
    if (this.target === 'deployed') {
      if (this.from >= 0.999) {
        return deployedFrame(this.frame);
      }
      // Un retour interrompu : l'aller reprend là où l'éclosion en est (jamais de retour au début, aucun saut).
      const skip = this.from > 0.001 ? 450 + 850 * inverseEase(this.from) : 0;
      return unfoldFrame(t + skip, this.frame);
    }
    if (this.from <= 0.001) {
      return foldedFrame(this.frame);
    }
    return foldFrame(t, this.variant, this.from, this.frame);
  }
}

/**
 * Paires de plus proches voisins (k par point) par grille spatiale, sans doublon, au plus `budget` paires. Pur et
 * déterministe (même nuage ⇒ mêmes paires). Exporté pour les tests.
 */
export function nearestPairs(points: Float32Array, count: number, budget: number, k: number): Uint32Array {
  let min = Infinity;
  let max = -Infinity;
  for (let i = 0; i < count * 3; i++) {
    min = Math.min(min, points[i]);
    max = Math.max(max, points[i]);
  }
  const cells = Math.max(1, Math.round(Math.cbrt(count / 2)));
  const size = (max - min) / cells || 1;
  const cellOf = (v: number): number => Math.min(cells - 1, Math.max(0, Math.floor((v - min) / size)));
  const grid = new Map<number, number[]>();
  const keyOf = (x: number, y: number, z: number): number => (x * cells + y) * cells + z;
  for (let i = 0; i < count; i++) {
    const key = keyOf(cellOf(points[i * 3]), cellOf(points[i * 3 + 1]), cellOf(points[i * 3 + 2]));
    const bucket = grid.get(key);
    if (bucket) {
      bucket.push(i);
    } else {
      grid.set(key, [i]);
    }
  }
  const out: number[] = [];
  const seen = new Set<number>();
  const best: { j: number; d: number }[] = [];
  for (let i = 0; i < count && out.length / 2 < budget; i++) {
    const cx = cellOf(points[i * 3]);
    const cy = cellOf(points[i * 3 + 1]);
    const cz = cellOf(points[i * 3 + 2]);
    best.length = 0;
    for (let x = cx - 1; x <= cx + 1; x++) {
      for (let y = cy - 1; y <= cy + 1; y++) {
        for (let z = cz - 1; z <= cz + 1; z++) {
          if (x < 0 || y < 0 || z < 0 || x >= cells || y >= cells || z >= cells) {
            continue;
          }
          for (const j of grid.get(keyOf(x, y, z)) ?? []) {
            if (j === i) {
              continue;
            }
            const d = (points[i * 3] - points[j * 3]) ** 2 + (points[i * 3 + 1] - points[j * 3 + 1]) ** 2
              + (points[i * 3 + 2] - points[j * 3 + 2]) ** 2;
            best.push({ j, d });
          }
        }
      }
    }
    best.sort((a, b) => a.d - b.d || a.j - b.j);
    for (let n = 0; n < Math.min(k, best.length) && out.length / 2 < budget; n++) {
      const j = best[n].j;
      const key = Math.min(i, j) * count + Math.max(i, j);
      if (!seen.has(key)) {
        seen.add(key);
        out.push(i, j);
      }
    }
  }
  return Uint32Array.from(out);
}
