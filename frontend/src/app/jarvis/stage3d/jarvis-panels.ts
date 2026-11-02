import { MOTION_DURATIONS, ease } from './jarvis-motion';

/**
 * F-236 / SF-236-08 — **le placement des panneaux holographiques** (cadrage T6, §7.2). Fichier <b>pur</b>.
 *
 * <p>Un panneau est ancré à un nœud projeté à l'écran ; il se pose dans le <b>quadrant libre</b> le plus proche de
 * son ancre — <b>jamais sur le cœur, jamais sur les sous-titres</b> (bande basse de l'échange et des commandes) ni
 * sous la barre du haut, jamais sur un autre panneau, toujours dans l'écran. Le pool est borné (PC 8, téléphone 2) ;
 * un panneau « devant tout » (S2) passe en premier et n'est jamais écarté.</p>
 */

/** T6 — panneaux ancrés au plus. */
export const PANEL_POOL = { wide: 8, narrow: 2 } as const;
/** Marge au bord de l'écran (gouttière de 16 px). */
export const PANEL_MARGIN = 16;
/** Écart entre l'ancre (ou le bord du cœur) et le panneau. */
export const PANEL_GAP = 20;
/** La hauteur de l'étiquette posée sous un nœud : un panneau placé dessous la laisse lisible. */
export const LABEL_CLEARANCE = 24;

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** L'écran : sa taille, le cœur projeté (disque à éviter), les bandes réservées au HUD. */
export interface PanelViewport {
  width: number;
  height: number;
  narrow: boolean;
  core: { x: number; y: number; r: number };
  /** Zones interdites (barre du haut, sous-titres et commandes). */
  reserved: Rect[];
}

/** La barre du haut (fil d'Ariane, réglages). */
const TOP_BAR = 64;
/** La bande des sous-titres et des commandes : téléphone 34 %, PC 24 % du bas de l'écran. */
const SUBTITLES_SHARE = { narrow: 0.34, wide: 0.24 } as const;

/** L'écran par défaut : cœur au centre (rayon apparent donné), barre du haut et bande des sous-titres réservées. */
export function panelViewport(width: number, height: number, narrow: boolean,
    core: { x: number; y: number; r: number }, sides: { left: number; right: number } = { left: 0, right: 0 },
): PanelViewport {
  const band = Math.round(height * (narrow ? SUBTITLES_SHARE.narrow : SUBTITLES_SHARE.wide));
  const reserved: Rect[] = [{ x: 0, y: 0, w: width, h: TOP_BAR }, { x: 0, y: height - band, w: width, h: band }];
  // SF-236-15 — les panneaux latéraux du cockpit (ouverts) : aucun hologramme ne se pose dessous.
  if (sides.left > 0) {
    reserved.push({ x: 0, y: 0, w: sides.left, h: height });
  }
  if (sides.right > 0) {
    reserved.push({ x: width - sides.right, y: 0, w: sides.right, h: height });
  }
  return { width, height, narrow, core, reserved };
}

/** Ce que le moteur demande de placer : l'ancre projetée et la taille mesurée du panneau. */
export interface PanelRequest {
  id: string;
  /** Ancre à l'écran ; `null` si elle est derrière la caméra (le panneau se pose alors près du cœur). */
  anchor: { x: number; y: number } | null;
  /** L'ancre est le cœur lui-même. */
  onCore: boolean;
  w: number;
  h: number;
  front?: boolean;
}

export type Quadrant = 'n' | 'ne' | 'nw' | 'se' | 'sw' | 'e' | 'w' | 'clamp';

export interface PanelSpot {
  id: string;
  x: number;
  y: number;
  quadrant: Quadrant;
}

/** Borne le pool : « devant tout » d'abord, puis l'ordre du plan ; le reste n'est pas dessiné. */
export function boundPanels<T extends { front?: boolean }>(panels: readonly T[], narrow: boolean): T[] {
  const limit = narrow ? PANEL_POOL.narrow : PANEL_POOL.wide;
  return [...panels.filter((p) => p.front), ...panels.filter((p) => !p.front)].slice(0, limit);
}

/** Place les panneaux un à un (dans l'ordre reçu) ; positions entières (texte net, jamais entre deux pixels). */
export function placePanels(requests: readonly PanelRequest[], vp: PanelViewport): PanelSpot[] {
  const placed: Rect[] = [];
  const spots: PanelSpot[] = [];
  for (const req of requests) {
    const candidates = candidatesFor(req, vp);
    let best: { rect: Rect; quadrant: Quadrant; cost: number } | null = null;
    for (const c of candidates) {
      const rect = { x: Math.round(c.x), y: Math.round(c.y), w: req.w, h: req.h };
      const cost = overlapCost(rect, vp, placed);
      if (cost === 0) {
        best = { rect, quadrant: c.quadrant, cost };
        break;
      }
      if (!best || cost < best.cost) {
        best = { rect, quadrant: c.quadrant, cost };
      }
    }
    let rect = best!.rect;
    let quadrant = best!.quadrant;
    if (best!.cost > 0) {
      rect = clampInto(rect, vp);
      quadrant = 'clamp';
    }
    placed.push(rect);
    spots.push({ id: req.id, x: rect.x, y: rect.y, quadrant });
  }
  return spots;
}

function candidatesFor(req: PanelRequest, vp: PanelViewport): { x: number; y: number; quadrant: Quadrant }[] {
  const { w, h } = req;
  const core = vp.core;
  if (req.onCore || !req.anchor) {
    // Autour du cœur, jamais dessus : au-dessus (S2 « devant tout »), puis à droite, à gauche, en diagonale.
    const d = core.r + PANEL_GAP;
    const list: { x: number; y: number; quadrant: Quadrant }[] = [
      { x: core.x - w / 2, y: core.y - d - h, quadrant: 'n' },
      { x: core.x + d, y: core.y - h / 2, quadrant: 'e' },
      { x: core.x - d - w, y: core.y - h / 2, quadrant: 'w' },
      { x: core.x + d * 0.7, y: core.y - d * 0.7 - h, quadrant: 'ne' },
      { x: core.x - d * 0.7 - w, y: core.y - d * 0.7 - h, quadrant: 'nw' },
      { x: core.x + d * 0.7, y: core.y + d * 0.7, quadrant: 'se' },
      { x: core.x - d * 0.7 - w, y: core.y + d * 0.7, quadrant: 'sw' },
    ];
    return req.front ? list : [list[1], list[2], list[0], ...list.slice(3)];
  }
  const { x, y } = req.anchor;
  const g = PANEL_GAP;
  // Sous le nœud, son étiquette (SF-236-07) : le panneau la laisse lisible.
  const below = g + LABEL_CLEARANCE;
  // Le quadrant le plus éloigné du cœur d'abord : le panneau s'écarte du centre plutôt que de le couvrir.
  const right = x >= core.x;
  const up = y <= core.y;
  const ne = { x: x + g, y: y - g - h, quadrant: 'ne' as Quadrant };
  const nw = { x: x - g - w, y: y - g - h, quadrant: 'nw' as Quadrant };
  const se = { x: x + g, y: y + below, quadrant: 'se' as Quadrant };
  const sw = { x: x - g - w, y: y + below, quadrant: 'sw' as Quadrant };
  const order = right ? (up ? [ne, se, nw, sw] : [se, ne, sw, nw]) : (up ? [nw, sw, ne, se] : [sw, nw, se, ne]);
  return order;
}

/** Le coût d'un emplacement : 0 = libre ; sinon l'aire couverte (bords, cœur, bandes, autres panneaux). */
function overlapCost(rect: Rect, vp: PanelViewport, placed: readonly Rect[]): number {
  let cost = 0;
  const inner = { x: PANEL_MARGIN, y: PANEL_MARGIN, w: vp.width - 2 * PANEL_MARGIN, h: vp.height - 2 * PANEL_MARGIN };
  cost += rect.w * rect.h - intersection(rect, inner);
  if (circleHits(rect, vp.core)) {
    cost += rect.w * rect.h;
  }
  for (const r of vp.reserved) {
    cost += intersection(rect, r);
  }
  for (const p of placed) {
    cost += intersection(rect, p) * 2;
  }
  return cost;
}

/** Ramène un rectangle dans l'écran (marges), hors des bandes réservées si la hauteur le permet. */
function clampInto(rect: Rect, vp: PanelViewport): Rect {
  const top = Math.max(PANEL_MARGIN, ...vp.reserved.filter((r) => r.y === 0).map((r) => r.y + r.h));
  const bottomBand = vp.reserved.filter((r) => r.y > 0).map((r) => r.y);
  const bottom = Math.min(vp.height - PANEL_MARGIN, ...bottomBand);
  const x = Math.min(Math.max(rect.x, PANEL_MARGIN), Math.max(PANEL_MARGIN, vp.width - PANEL_MARGIN - rect.w));
  const y = Math.min(Math.max(rect.y, top), Math.max(top, bottom - rect.h));
  return { ...rect, x: Math.round(x), y: Math.round(y) };
}

export function intersection(a: Rect, b: Rect): number {
  const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
  const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
  return w > 0 && h > 0 ? w * h : 0;
}

/** Ce que le moteur applique à un panneau à un instant de sa vie (T-P1 / T-P2). */
export interface PanelLook {
  opacity: number;
  scale: number;
  /** Balayage lumineux : position 0-1 (de haut en bas), `null` = aucun. */
  sweep: number | null;
  /** Filament d'ancrage tracé depuis le nœud : 0-1. */
  filament: number;
}

/**
 * T-P1 (0,42 s, `rebond`) : échelle 0,92 → 1, opacité 0 → 1 (`sortie`), balayage de haut en bas (300 ms), filament
 * tracé depuis le nœud (250 ms) ; `rank` décale de 90 ms. T-P2 (0,28 s, `entree`) : échelle 1 → 0,96, balayage
 * inverse, filament qui se rétracte.
 */
export function panelLook(phase: 'in' | 'out', elapsedMs: number, rank = 0): PanelLook {
  const d = MOTION_DURATIONS;
  if (phase === 'in') {
    const t = elapsedMs - rank * d.panelStagger;
    if (t <= 0) {
      return { opacity: 0, scale: 0.92, sweep: null, filament: 0 };
    }
    const k = Math.min(1, t / d.panelIn);
    return {
      opacity: ease('sortie', k), scale: 0.92 + 0.08 * ease('rebond', k),
      sweep: t < d.panelSweep ? t / d.panelSweep : null,
      filament: ease('sortie', Math.min(1, t / d.panelFilament)),
    };
  }
  const k = Math.min(1, Math.max(0, elapsedMs) / d.panelOut);
  const e = ease('entree', k);
  return { opacity: 1 - e, scale: 1 - 0.04 * e, sweep: k < 1 ? 1 - k : null, filament: 1 - e };
}

/** T-P3 — un panneau qui vole vers « À envoyer » (ms). */
export const PANEL_FLY_MS = 1100;

/** T-P3 à `elapsed` : avancement (`glisse`), réduction 1 → 0,25, arc (levée), opacité qui s'éteint à l'arrivée. */
export function flyLook(elapsedMs: number): { k: number; scale: number; lift: number; opacity: number } {
  const t = Math.max(0, Math.min(1, elapsedMs / PANEL_FLY_MS));
  const k = ease('glisse', t);
  return { k, scale: 1 - 0.75 * k, lift: Math.sin(Math.PI * k), opacity: t < 0.85 ? 1 : (1 - t) / 0.15 };
}

/**
 * T-P3 — le compteur ne prend +1 qu'à l'arrivée de l'enveloppe : délai avant d'afficher la nouvelle valeur. Aucun
 * délai en 2D, ni quand le compteur baisse (relecture de l'univers).
 */
export function arrivalDelay(shown: number, next: number, flying3d: boolean): number {
  return flying3d && next > shown ? PANEL_FLY_MS : 0;
}

/** Le rectangle touche-t-il le disque du cœur ? */
export function circleHits(rect: Rect, c: { x: number; y: number; r: number }): boolean {
  const nx = Math.max(rect.x, Math.min(c.x, rect.x + rect.w));
  const ny = Math.max(rect.y, Math.min(c.y, rect.y + rect.h));
  return (nx - c.x) ** 2 + (ny - c.y) ** 2 < c.r * c.r;
}
