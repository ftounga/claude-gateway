import { HOLO_COLORS } from '../jarvis-motion';

/**
 * F-238 / SF-238-08 — **l'atlas des icônes officielles** de la scène d'architecture (cadrage §5.4, §14) : toutes les
 * icônes d'un graphe dans **une seule image** en grille de cellules carrées de 128 px (≤ 64 cellules ⇒ ≤ 1024 × 1024),
 * pour une seule texture (`new CanvasTexture(atlas.image)`) et un seul appel de dessin.
 *
 * Module **pur** (aucun import de `three`) : testable sous Karma sans WebGL. Les icônes viennent du proxy de la
 * passerelle (`GET /api/diagrams/icons/{chemin}.png`, sous JWT, cache immuable) et sont dessinées **non recolorées**
 * (licence, cadrage §4.3 : la teinte holographique est un halo et un socle, jamais une recoloration).
 *
 * **Jamais une icône approchante** (SF-142-12) : une icône absente, illisible ou au-delà de la borne laisse le nœud
 * sur la **boîte neutre** (cellule 0) ; un type inconnu est en **pointillé** (cellule 1) — le même contrat que
 * `neutralIconSource` (SF-238-09), que cet atlas remplace sans changer la scène.
 */

/** Taille d'une cellule (px). */
export const ATLAS_CELL = 128;
/** Cellule de la boîte neutre (service connu, icône absente). */
export const CELL_NEUTRAL = 0;
/** Cellule du type inconnu (boîte en pointillé). */
export const CELL_UNKNOWN = 1;
/** Cellules réservées avant les icônes. */
const RESERVED = 2;
/** Au plus 64 cellules (8 × 8 ⇒ 1024 × 1024) : 62 icônes distinctes + les 2 boîtes. */
export const ATLAS_MAX_ICONS = 62;
/** Marge intérieure d'une icône dans sa cellule (px). */
const PADDING = 8;

/** Le motif de `diagram-renderer/icons.js` (`NAME`) et sa liste blanche, appliqués avant toute requête. */
const ICON_NAME = /^[a-z0-9]+(\/[a-z0-9][a-z0-9._-]*){1,3}$/;
const PROVIDERS: ReadonlySet<string> = new Set(['aws', 'azure', 'gcp', 'k8s', 'onprem', 'generic']);

/** Ce que l'atlas lit d'un nœud du graphe (`ArchitectureGraph` v1, §5.1). */
export interface AtlasNode {
  resolved: string | null;
  unknown: boolean;
}

/** Une image d'icône dessinable (ImageBitmap, HTMLImageElement, canvas). */
export type IconImage = CanvasImageSource & { readonly width: number; readonly height: number };

/** Charge une icône par son nom ; `null` si elle manque. Ne doit pas lever (une erreur vaut `null`). */
export type IconFetcher = (name: string, signal?: AbortSignal) => Promise<IconImage | null>;

/** Le plan de l'atlas : quelles icônes, dans quelles cellules, sur quelle grille. */
export interface IconAtlasPlan {
  /** Icônes retenues, triées (déterministe) ; la i-ème occupe la cellule `RESERVED + i`. */
  icons: string[];
  /** Icônes distinctes au-delà de la borne : leurs nœuds restent sur la boîte neutre. */
  dropped: string[];
  columns: number;
  rows: number;
  /** Côté de l'image (px) = `columns × ATLAS_CELL`. */
  size: number;
}

/** Coordonnées de texture d'une cellule, convention `CanvasTexture.flipY = true` (v = 1 en haut de l'image). */
export interface AtlasUv {
  u0: number;
  v0: number;
  u1: number;
  v1: number;
}

/** L'atlas composé — conforme à `ArchitectureIconSource` (image, columns, rows, cellOf) de la scène. */
export interface ArchitectureIconAtlas {
  image: HTMLCanvasElement;
  columns: number;
  rows: number;
  /** La cellule d'un nœud : son icône si elle a été dessinée, sinon 0 (neutre) ou 1 (inconnu). */
  cellOf(node: AtlasNode): number;
  uv(cell: number): AtlasUv;
  /** Icônes demandées mais non dessinées (absentes, illisibles, au-delà de la borne). */
  missing: string[];
  /** Durée de la composition seule (ms). */
  composeMs: number;
  /** Durée totale chargement + composition (ms) ; égale à `composeMs` sans chargement. */
  totalMs: number;
}

/** Vrai si le nom d'icône est sûr (motif, fournisseur permis, aucun segment `.`/`..`). */
export function validIconName(name: string | null | undefined): name is string {
  if (typeof name !== 'string' || !ICON_NAME.test(name)) {
    return false;
  }
  const segments = name.split('/');
  return !segments.some((s) => s === '.' || s === '..') && PROVIDERS.has(segments[0]);
}

/** L'URL du proxy d'une icône, ou `null` si le nom n'est pas sûr (aucune requête alors). */
export function iconUrl(resolved: string | null | undefined): string | null {
  return validIconName(resolved) ? `/api/diagrams/icons/${resolved}.png` : null;
}

function nextPow2(n: number): number {
  let p = 1;
  while (p < n) {
    p *= 2;
  }
  return p;
}

/** Le plan : icônes distinctes des nœuds connus, triées, bornées ; grille carrée en puissance de deux. */
export function planIconAtlas(nodes: readonly AtlasNode[]): IconAtlasPlan {
  const distinct = new Set<string>();
  for (const node of nodes) {
    if (!node.unknown && validIconName(node.resolved)) {
      distinct.add(node.resolved);
    }
  }
  const sorted = [...distinct].sort();
  const icons = sorted.slice(0, ATLAS_MAX_ICONS);
  const dropped = sorted.slice(ATLAS_MAX_ICONS);
  const columns = Math.max(2, nextPow2(Math.ceil(Math.sqrt(RESERVED + icons.length))));
  return { icons, dropped, columns, rows: columns, size: columns * ATLAS_CELL };
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/** Les deux boîtes réservées : neutre (trait plein) et inconnu (pointillé). */
function drawReserved(ctx: CanvasRenderingContext2D, columns: number): void {
  for (const cell of [CELL_NEUTRAL, CELL_UNKNOWN]) {
    const x = (cell % columns) * ATLAS_CELL + 14;
    const y = Math.floor(cell / columns) * ATLAS_CELL + 14;
    ctx.save();
    roundRect(ctx, x, y, ATLAS_CELL - 28, ATLAS_CELL - 28, 16);
    ctx.fillStyle = HOLO_COLORS.fog;
    ctx.globalAlpha = 0.85;
    ctx.fill();
    ctx.globalAlpha = 1;
    ctx.lineWidth = 6;
    ctx.strokeStyle = cell === CELL_NEUTRAL ? HOLO_COLORS.text : HOLO_COLORS.off;
    if (cell === CELL_UNKNOWN) {
      ctx.setLineDash([14, 10]);
    }
    ctx.stroke();
    ctx.restore();
  }
}

function drawable(image: IconImage | null | undefined): image is IconImage {
  return !!image && image.width > 0 && image.height > 0;
}

/**
 * Compose l'atlas depuis un plan et les images chargées. Une icône sans image (absente, illisible) n'est pas dessinée
 * et ses nœuds restent sur la boîte neutre.
 */
export function composeIconAtlas(plan: IconAtlasPlan, images: ReadonlyMap<string, IconImage | null>,
  doc: Document = document, now: () => number = () => performance.now()): ArchitectureIconAtlas {
  const started = now();
  const image = doc.createElement('canvas');
  image.width = plan.size;
  image.height = plan.size;
  const ctx = image.getContext('2d');
  const cells = new Map<string, number>();
  const missing: string[] = [...plan.dropped];
  if (ctx) {
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    drawReserved(ctx, plan.columns);
  }
  plan.icons.forEach((name, index) => {
    const icon = images.get(name);
    if (!ctx || !drawable(icon)) {
      missing.push(name);
      return;
    }
    const cell = RESERVED + index;
    const box = ATLAS_CELL - 2 * PADDING;
    const scale = Math.min(box / icon.width, box / icon.height);
    const w = icon.width * scale;
    const h = icon.height * scale;
    const x = (cell % plan.columns) * ATLAS_CELL + (ATLAS_CELL - w) / 2;
    const y = Math.floor(cell / plan.columns) * ATLAS_CELL + (ATLAS_CELL - h) / 2;
    try {
      ctx.drawImage(icon, x, y, w, h);
      cells.set(name, cell);
    } catch {
      missing.push(name); // image illisible : la boîte neutre, jamais une autre icône
    }
  });
  const composeMs = now() - started;
  const columns = plan.columns;
  const rows = plan.rows;
  return {
    image,
    columns,
    rows,
    cellOf: (node) => {
      if (node.unknown) {
        return CELL_UNKNOWN;
      }
      return (node.resolved != null ? cells.get(node.resolved) : undefined) ?? CELL_NEUTRAL;
    },
    uv: (cell) => {
      const col = cell % columns;
      const row = Math.floor(cell / columns);
      return { u0: col / columns, u1: (col + 1) / columns, v0: 1 - (row + 1) / rows, v1: 1 - row / rows };
    },
    missing,
    composeMs,
    totalMs: composeMs,
  };
}

/** Charge (en parallèle, une fois par icône distincte) puis compose l'atlas d'un graphe. Ne lève jamais. */
export async function loadIconAtlas(nodes: readonly AtlasNode[], fetchIcon: IconFetcher, doc: Document = document,
  now: () => number = () => performance.now(), signal?: AbortSignal): Promise<ArchitectureIconAtlas> {
  const started = now();
  const plan = planIconAtlas(nodes);
  const loaded = await Promise.all(plan.icons.map(async (name) => {
    try {
      return [name, await fetchIcon(name, signal)] as const;
    } catch {
      return [name, null] as const;
    }
  }));
  const atlas = composeIconAtlas(plan, new Map(loaded), doc, now);
  return { ...atlas, totalMs: now() - started };
}

/**
 * Le chargeur réel : `fetch` du proxy sous JWT (en-têtes fournis par l'appelant, ex. `JarvisPieceClient`), puis
 * `createImageBitmap`. Nom non sûr, réponse non 200 ou image illisible ⇒ `null`.
 */
export function httpIconFetcher(headers: () => HeadersInit,
  fetchFn: typeof fetch = (input, init) => fetch(input, init),
  decode: (blob: Blob) => Promise<IconImage> = (blob) => createImageBitmap(blob)): IconFetcher {
  return async (name, signal) => {
    const url = iconUrl(name);
    if (!url) {
      return null;
    }
    try {
      const response = await fetchFn(url, { headers: headers(), signal });
      if (!response.ok) {
        return null;
      }
      return await decode(await response.blob());
    } catch {
      return null;
    }
  };
}
