import { AtelierLane } from '../core/services/atelier.service';

/**
 * **Quel chantier est dans quel volet** (F-246 / SF-246-01) — l'état des volets du terminal, en fonctions pures.
 *
 * <p>Retour du PO (03/11) : « un coup le chantier 9 se retrouve à gauche, ensuite il se retrouve à droite, ensuite je
 * ne peux pas le rouvrir ». Cause : le volet de gauche suivait le chantier AU FOCUS côté serveur (qui bouge dès
 * qu'un chantier s'ouvre ou qu'on « met au focus »), et le volet de droite était recalculé à partir des fils actifs.
 * Désormais la disposition appartient à l'écran : elle ne change QUE par un geste de l'utilisateur (placer, fermer,
 * ouvrir ou lancer un chantier à côté), elle est gardée par sujet sur l'appareil et relue au rechargement.</p>
 */
export type PaneSide = 'left' | 'right';

export interface PaneLayout {
  /** Le chantier du volet de gauche (l'écran principal), ou `null` : le terminal suit le fil du sujet. */
  left: number | null;
  /** Le chantier du volet de droite, ou `null` : pas de split. */
  right: number | null;
}

export const NO_PANES: PaneLayout = { left: null, right: null };

export function otherSide(side: PaneSide): PaneSide {
  return side === 'left' ? 'right' : 'left';
}

/** Le volet où est le chantier, ou `null` s'il n'est pas affiché. */
export function sideOf(layout: PaneLayout, number: number): PaneSide | null {
  if (layout.left === number) {
    return 'left';
  }
  return layout.right === number ? 'right' : null;
}

/**
 * **Placer** un chantier dans un volet. Déjà dans l'autre volet : les deux chantiers échangent leurs places (rien ne
 * disparaît). Le chantier qui occupait le volet visé n'est pas fermé : il reste ouvert, dans la carte des volets.
 */
export function place(layout: PaneLayout, number: number, side: PaneSide): PaneLayout {
  const current = sideOf(layout, number);
  if (current === side) {
    return layout;
  }
  if (current !== null) {
    return { left: layout.right, right: layout.left };
  }
  return side === 'left' ? { left: number, right: layout.right } : { left: layout.left, right: number };
}

/**
 * Un chantier **ouvert ou lancé à côté** depuis le volet {@code from} : il arrive dans l'AUTRE volet — celui d'où on
 * l'a demandé ne bouge pas. Déjà affiché : rien ne bouge.
 */
export function placeBeside(layout: PaneLayout, number: number, from: PaneSide): PaneLayout {
  if (sideOf(layout, number) !== null) {
    return layout;
  }
  return place(layout, number, otherSide(from));
}

/**
 * [Fermer ce volet]. À droite : le split se referme. À gauche : le chantier de droite reste le seul affiché (il n'y a
 * plus qu'un volet). Le chantier fermé continue côté serveur et se rouvre depuis la carte des volets.
 */
export function closeSide(layout: PaneLayout, side: PaneSide): PaneLayout {
  if (layout.right === null) {
    return layout;
  }
  return side === 'right' ? { left: layout.left, right: null } : { left: layout.right, right: null };
}

/**
 * Les chantiers fermés (clos) quittent leur volet ; les autres ne bougent pas. Un volet de gauche vidé reprend le
 * focus du serveur s'il n'est pas déjà à droite, sinon un autre chantier ouvert non affiché, sinon celui de droite.
 */
export function prune(layout: PaneLayout, open: readonly number[], focus: number | null): PaneLayout {
  const right = layout.right !== null && open.includes(layout.right) ? layout.right : null;
  let left = layout.left !== null && open.includes(layout.left) ? layout.left : null;
  if (left === null && layout.left !== null) {
    const candidates = [focus, ...open].filter((n): n is number => n !== null && open.includes(n) && n !== right);
    left = candidates[0] ?? null;
    if (left === null && right !== null) {
      return { left: right, right: null };
    }
  }
  return { left, right: right === left ? null : right };
}

/**
 * **L'arrivée sur le sujet** : la disposition gardée sur l'appareil, si ses chantiers sont encore ouverts ; sinon le
 * chantier au focus à gauche et, si un autre chantier tourne, le plus ancien à droite (l'arrivée en split de F-221).
 *
 * @param open les chantiers ouverts, ou `null` s'ils ne sont pas encore connus (la disposition gardée est crue)
 */
export function arrivalLayout(saved: PaneLayout | null, focus: number | null, lanes: readonly AtelierLane[],
                              open: readonly number[] | null): PaneLayout {
  const isOpen = (n: number | null): n is number => n !== null && (open === null || open.includes(n));
  if (saved && isOpen(saved.left)) {
    return { left: saved.left, right: isOpen(saved.right) && saved.right !== saved.left ? saved.right : null };
  }
  if (focus === null) {
    return NO_PANES;
  }
  const other = lanes
    .filter((lane) => lane.chantier !== null && lane.chantier !== focus && (lane.live || lane.autonomy)
      && isOpen(lane.chantier))
    .sort((a, b) => a.startedAt - b.startedAt)[0];
  return { left: focus, right: other?.chantier ?? null };
}

const STORAGE_PREFIX = 'cg.atelier.panes.';

/** La disposition gardée pour ce sujet sur cet appareil, ou `null` (absente, illisible, stockage bloqué). */
export function readLayout(workspaceId: string): PaneLayout | null {
  try {
    const raw = localStorage.getItem(STORAGE_PREFIX + workspaceId);
    if (!raw) {
      return null;
    }
    const value = JSON.parse(raw) as Partial<PaneLayout>;
    const num = (v: unknown): number | null => (typeof v === 'number' && Number.isInteger(v) && v > 0 ? v : null);
    const left = num(value?.left);
    return left === null ? null : { left, right: num(value?.right) };
  } catch {
    return null;
  }
}

/** Garde la disposition de ce sujet ; un stockage bloqué n'empêche rien (elle vit alors le temps de la page). */
export function writeLayout(workspaceId: string, layout: PaneLayout): void {
  try {
    if (layout.left === null) {
      localStorage.removeItem(STORAGE_PREFIX + workspaceId);
    } else {
      localStorage.setItem(STORAGE_PREFIX + workspaceId, JSON.stringify(layout));
    }
  } catch {
    // Navigation privée, stockage plein ou bloqué : la disposition reste en mémoire.
  }
}
