import { HOLO_COLORS } from '../jarvis-motion';

/**
 * F-238 / SF-238-09 — **où la scène prend ses icônes** (cadrage §5.4) : une seule image en grille de cellules
 * carrées (un seul appel de dessin pour toutes les icônes). L'atlas réel des icônes officielles (SF-238-08,
 * `architecture-icon-atlas.ts`) remplira cette interface ; en attendant, {@link neutralIconSource} : une **boîte
 * neutre** par service — jamais une icône approchante (SF-142-12) —, et une boîte en pointillé pour un type inconnu.
 */
export interface ArchitectureIconSource {
  image: HTMLCanvasElement;
  columns: number;
  rows: number;
  /** La cellule d'un service (0 … columns × rows − 1). */
  cellOf(node: { resolved: string | null; unknown: boolean }): number;
}

const CELL = 128;

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/** Deux cellules : 0 = boîte neutre (service connu, icône pas encore servie), 1 = type inconnu (pointillé). */
export function neutralIconSource(doc: Document = document): ArchitectureIconSource {
  const image = doc.createElement('canvas');
  image.width = CELL * 2;
  image.height = CELL;
  const ctx = image.getContext('2d');
  if (ctx) {
    for (let cell = 0; cell < 2; cell++) {
      const x = cell * CELL + 14;
      ctx.save();
      roundRect(ctx, x, 14, CELL - 28, CELL - 28, 16);
      ctx.fillStyle = HOLO_COLORS.fog;
      ctx.globalAlpha = 0.85;
      ctx.fill();
      ctx.globalAlpha = 1;
      ctx.lineWidth = 6;
      ctx.strokeStyle = cell === 0 ? HOLO_COLORS.text : HOLO_COLORS.off;
      if (cell === 1) {
        ctx.setLineDash([14, 10]);
      }
      ctx.stroke();
      ctx.restore();
    }
  }
  return { image, columns: 2, rows: 1, cellOf: (node) => (node.unknown ? 1 : 0) };
}
