import { normalize } from '../jarvis-oral';

/**
 * F-237 / SF-237-07 — **les cibles nommées d'un schéma** (cadrage §10) : « zoome sur le VPC de prod » ⇒ la boîte du
 * groupe ou du nœud dont le libellé correspond, lue dans le SVG rendu (zones `<g id="zone-…">` du rendu maison,
 * grappes `<g class="cluster">` de Graphviz, nœuds). Le SVG est **analysé**, jamais inséré dans la page (il reste
 * affiché en `<img>`, sans script possible). Pur, hors analyse DOMParser.
 */

export interface SvgBox {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface SvgTarget {
  label: string;
  box: SvgBox;
}

/** Le cadrage d'une cible : zoom et décalage (en % de l'image) pour la centrer. */
export interface SvgView {
  zoom: number;
  tx: number;
  ty: number;
}

/** Part de l'écran qu'occupe la cible une fois cadrée. */
const FILL = 0.8;

function num(value: string | null | undefined): number {
  const n = Number.parseFloat(value ?? '');
  return Number.isFinite(n) ? n : 0;
}

/** Le décalage cumulé des `translate(...)` des ancêtres (Graphviz pose un translate racine). */
function offsetOf(el: Element): { dx: number; dy: number } {
  let dx = 0;
  let dy = 0;
  for (let node: Element | null = el; node; node = node.parentElement) {
    const match = /translate\(\s*(-?[\d.]+)[\s,]+(-?[\d.]+)\s*\)/.exec(node.getAttribute('transform') ?? '');
    if (match) {
      dx += num(match[1]);
      dy += num(match[2]);
    }
  }
  return { dx, dy };
}

/** La boîte du premier cadre d'un groupe : `rect`, `polygon` ou `image`. */
function boxOf(group: Element): SvgBox | null {
  const rect = group.querySelector(':scope > rect, :scope > image');
  const { dx, dy } = offsetOf(group);
  if (rect) {
    const w = num(rect.getAttribute('width'));
    const h = num(rect.getAttribute('height'));
    return w > 0 && h > 0 ? { x: num(rect.getAttribute('x')) + dx, y: num(rect.getAttribute('y')) + dy, w, h } : null;
  }
  const polygon = group.querySelector(':scope > polygon, :scope > path');
  const points = (polygon?.getAttribute('points') ?? '').trim();
  if (points) {
    const coords = points.split(/[\s,]+/).map(num);
    const xs = coords.filter((_, i) => i % 2 === 0);
    const ys = coords.filter((_, i) => i % 2 === 1);
    const x = Math.min(...xs);
    const y = Math.min(...ys);
    const w = Math.max(...xs) - x;
    const h = Math.max(...ys) - y;
    return w > 0 && h > 0 ? { x: x + dx, y: y + dy, w, h } : null;
  }
  return null;
}

/** Le cadre de tout le schéma (viewBox, sinon largeur × hauteur). */
export function svgFrame(svg: Element): SvgBox | null {
  const viewBox = (svg.getAttribute('viewBox') ?? '').trim().split(/[\s,]+/).map(num);
  if (viewBox.length === 4 && viewBox[2] > 0 && viewBox[3] > 0) {
    return { x: viewBox[0], y: viewBox[1], w: viewBox[2], h: viewBox[3] };
  }
  const w = num(svg.getAttribute('width'));
  const h = num(svg.getAttribute('height'));
  return w > 0 && h > 0 ? { x: 0, y: 0, w, h } : null;
}

/** Les cibles nommées d'un SVG : chaque groupe qui porte un libellé et un cadre. */
export function svgTargets(svgText: string): { frame: SvgBox | null; targets: SvgTarget[] } {
  const doc = new DOMParser().parseFromString(svgText, 'image/svg+xml');
  const svg = doc.querySelector('svg');
  if (!svg || doc.querySelector('parsererror')) {
    return { frame: null, targets: [] };
  }
  const targets: SvgTarget[] = [];
  for (const group of Array.from(svg.querySelectorAll('g'))) {
    const text = group.querySelector(':scope > text');
    const label = (text?.textContent ?? group.querySelector(':scope > title')?.textContent ?? '').trim();
    if (!label) {
      continue;
    }
    const box = boxOf(group);
    if (box) {
      targets.push({ label, box });
    }
  }
  return { frame: svgFrame(svg), targets };
}

/** La cible désignée : libellé identique, sinon le seul libellé qui le contient (ou qu'il contient). */
export function findTarget(targets: readonly SvgTarget[], wanted: string): SvgTarget | null {
  const key = normalize(wanted).replace(/^(le|la|les|l)\s+/, '');
  if (!key) {
    return null;
  }
  const exact = targets.filter((t) => normalize(t.label) === key);
  if (exact.length >= 1) {
    return exact[0];
  }
  const loose = targets.filter((t) => {
    const label = normalize(t.label);
    return label.includes(key) || key.includes(label);
  });
  return loose.length === 1 ? loose[0] : null;
}

/** Le cadrage qui centre `box` et la fait occuper ~80 % du schéma, zoom borné ×1..×8. */
export function viewOf(frame: SvgBox, box: SvgBox): SvgView {
  const ratio = Math.max(box.w / frame.w, box.h / frame.h);
  const zoom = Math.min(8, Math.max(1, Math.round((FILL / Math.max(ratio, 0.01)) * 10) / 10));
  const u = (box.x + box.w / 2 - frame.x) / frame.w - 0.5;
  const v = (box.y + box.h / 2 - frame.y) / frame.h - 0.5;
  return { zoom, tx: Math.round(-u * 1000) / 10, ty: Math.round(-v * 1000) / 10 };
}
