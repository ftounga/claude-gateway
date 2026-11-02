/**
 * Le moteur « cadre » des schémas d'architecture (F-238, itération 0 « Le schéma 2D cadré »).
 *
 * Constat du PO (2026-11-02) sur les schémas F-142 : les liaisons se tordent, elles sont obliques ; « dans
 * les schémas AWS, les lignes sont verticales ou horizontales ». Mesuré : 12/15, 21/23 et 4/9 segments non
 * orthogonaux sur trois schémas types (graphviz `splines=spline`, SF-142-17).
 *
 * Ce module DESSINE lui-même : une description préparée (`cloud.py --prepare` : validation, icônes
 * résolues) entre ; un SVG et le graphe d'architecture (`ArchitectureGraph`, cadrage §5) sortent.
 *
 * - Mise en page : ELK `layered`, liaisons ORTHOGONALES (SF-238-01).
 * - Pur et DÉTERMINISTE : aucun horodatage, aucun tirage ; même entrée ⇒ même SVG, octet pour octet.
 * - Chaque zone et chaque nœud porte un identifiant stable (`zone-<id>`, `node-<id>`) : la visionneuse de
 *   F-237 vise ses cibles par ces noms.
 * - Les libellés viennent du modèle : ils sont ÉCHAPPÉS partout (contenu et attributs).
 */

"use strict";

const fs = require("node:fs");
const ELK = require("elkjs/lib/elk.bundled.js");

/** Pas de la grille : toutes les coordonnées utiles en sont des multiples. */
const GRID = 8;
const ICON = 64;
const LABEL_GAP = 8;
const LINE_H = 16;
const NODE_FONT = 13;
const EDGE_FONT = 12;
const TITLE_FONT = 20;
const MARGIN = 24;
const TITLE_H = 40;
const EDGE_COLOR = "#545B64";
const INK = "#232F3E";
/** Repli visible d'un type sans icône (F-142 / SF-142-12) : jamais une icône approchante. */
const UNKNOWN_STROKE = "#B03A2E";
const UNKNOWN_FILL = "#FDECEA";

const ZONE_PAD = { top: 48, left: 24, right: 24, bottom: 24 };

/** Échappement XML de tout ce qui vient du modèle — contenu comme attributs. */
function esc(value) {
  return String(value === undefined || value === null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** Largeur estimée d'un texte en Arial/Helvetica (moyenne 0,56 em) : assez pour réserver la place. */
function textWidth(text, px) {
  return Math.ceil(String(text || "").length * px * 0.56) + 4;
}

function snapUp(value) {
  return Math.ceil(value / GRID) * GRID;
}

function round(value) {
  return Math.round(value);
}

/** La largeur du libellé d'un nœud : sa plus longue ligne. */
function labelWidth(node) {
  return Math.max(0, ...node.lines.map((line) => textWidth(line, NODE_FONT)));
}

/** La forme d'un nœud : l'icône (64 px) puis son libellé centré dessous. */
function shapeOf(node) {
  const cx = node.box.x + node.box.w / 2;
  const top = node.box.y;
  const lw = labelWidth(node);
  const lines = Math.max(1, node.lines.length);
  return {
    icon: { x: cx - ICON / 2, y: top, w: ICON, h: ICON },
    label: { x: cx - lw / 2, y: top + ICON + LABEL_GAP, w: lw, h: lines * LINE_H },
  };
}

function nodeSize(node) {
  const lines = Math.max(1, node.lines.length);
  return {
    w: snapUp(Math.max(ICON, labelWidth(node))),
    h: snapUp(ICON + LABEL_GAP + lines * LINE_H),
  };
}

function directionOf(prepared) {
  const raw = String(prepared.direction || "").toUpperCase();
  if (raw === "TB" || raw === "BT") {
    return "TB";
  }
  return "LR";
}

// ------------------------------------------------------------------------------------------------ ELK

const ELK_ROOT_OPTIONS = {
  "elk.algorithm": "layered",
  "elk.edgeRouting": "ORTHOGONAL",
  "elk.hierarchyHandling": "INCLUDE_CHILDREN",
  "elk.spacing.nodeNode": "48",
  "elk.layered.spacing.nodeNodeBetweenLayers": "64",
  "elk.spacing.edgeNode": "24",
  "elk.spacing.edgeEdge": "16",
  "elk.layered.spacing.edgeNodeBetweenLayers": "24",
  "elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
  "elk.layered.nodePlacement.strategy": "NETWORK_SIMPLEX",
  "elk.edgeLabels.inline": "false",
  "elk.padding": "[top=0,left=0,bottom=0,right=0]",
};

/**
 * La mise en page ELK du schéma entier (SF-238-01) : imbrication native, liaisons orthogonales,
 * étiquettes posées par ELK sur leur liaison. Rend le modèle dessinable (coordonnées absolues).
 */
async function layoutElk(prepared) {
  const direction = directionOf(prepared);
  const elk = new ELK();
  const groups = new Map(prepared.groups.map((g) => [g.id, g]));
  const root = {
    id: "__racine__",
    layoutOptions: { ...ELK_ROOT_OPTIONS, "elk.direction": direction === "TB" ? "DOWN" : "RIGHT" },
    children: [],
    edges: [],
  };
  const elkNodes = new Map();
  for (const g of prepared.groups) {
    elkNodes.set(g.id, {
      id: "z:" + g.id,
      children: [],
      edges: [],
      layoutOptions: {
        "elk.padding": `[top=${ZONE_PAD.top},left=${ZONE_PAD.left},bottom=${ZONE_PAD.bottom},right=${ZONE_PAD.right}]`,
        "elk.nodeSize.minimum": `(${snapUp(textWidth(g.label, 13) + 60)}, 64)`,
        "elk.nodeSize.constraints": "[MINIMUM_SIZE]",
      },
    });
  }
  const containerOf = (groupId) => (groupId && elkNodes.has(groupId) ? elkNodes.get(groupId) : root);
  for (const g of prepared.groups) {
    containerOf(g.parent).children.push(elkNodes.get(g.id));
  }
  for (const n of prepared.nodes) {
    const size = nodeSize(n);
    containerOf(n.group).children.push({ id: "n:" + n.id, width: size.w, height: size.h });
  }
  // Chaque lien est déclaré dans le plus proche ancêtre commun de ses extrémités : c'est là qu'ELK le route.
  const parentOf = new Map();
  for (const g of prepared.groups) {
    parentOf.set("z:" + g.id, g.parent && groups.has(g.parent) ? "z:" + g.parent : root.id);
  }
  for (const n of prepared.nodes) {
    parentOf.set("n:" + n.id, n.group && groups.has(n.group) ? "z:" + n.group : root.id);
  }
  const chain = (id) => {
    const out = [];
    let current = parentOf.get(id);
    while (current) {
      out.push(current);
      current = current === root.id ? null : parentOf.get(current);
    }
    return out;
  };
  const lca = (a, b) => {
    const mine = new Set(chain(b));
    return chain(a).find((x) => mine.has(x)) || root.id;
  };
  prepared.edges.forEach((e, i) => {
    const host = lca("n:" + e.from, "n:" + e.to);
    const container = host === root.id ? root : elkNodes.get(host.slice(2));
    container.edges.push({
      id: "e" + i,
      sources: ["n:" + e.from],
      targets: ["n:" + e.to],
      labels: e.label ? [{ text: e.label, width: textWidth(e.label, EDGE_FONT) + 8, height: LINE_H }] : [],
    });
  });

  const out = await elk.layout(root);
  const top = MARGIN + (prepared.title ? TITLE_H : 0);
  const left = MARGIN;
  const boxes = new Map();
  const edgeHosts = [];
  const walk = (container, ox, oy) => {
    for (const e of container.edges || []) {
      edgeHosts.push([e, ox, oy]);
    }
    for (const child of container.children || []) {
      const x = child.x + ox;
      const y = child.y + oy;
      boxes.set(child.id, { x: round(x), y: round(y), w: round(child.width), h: round(child.height) });
      walk(child, x, y);
    }
  };
  walk(out, left, top);

  const depthOf = (groupId) => {
    let depth = 0;
    let current = groups.get(groupId);
    while (current && current.parent && groups.has(current.parent)) {
      depth += 1;
      current = groups.get(current.parent);
    }
    return depth;
  };
  const zones = prepared.groups.map((g) => ({
    id: g.id,
    kind: g.kind || "generic",
    label: g.label,
    parent: g.parent && groups.has(g.parent) ? g.parent : "",
    depth: depthOf(g.id),
    inferred: g.inferred !== false,
    box: boxes.get("z:" + g.id),
  }));
  const nodes = prepared.nodes.map((n) => ({ ...n, box: boxes.get("n:" + n.id) }));
  const edges = [];
  edgeHosts.sort((a, b) => Number(a[0].id.slice(1)) - Number(b[0].id.slice(1)));
  for (const [e, ox, oy] of edgeHosts) {
    const index = Number(e.id.slice(1));
    const spec = prepared.edges[index];
    const points = [];
    for (const section of e.sections || []) {
      const raw = [section.startPoint, ...(section.bendPoints || []), section.endPoint];
      for (const p of raw) {
        const point = { x: round(p.x + ox), y: round(p.y + oy) };
        const last = points[points.length - 1];
        if (!last || last.x !== point.x || last.y !== point.y) {
          points.push(point);
        }
      }
    }
    const label = (e.labels || [])[0];
    edges.push({
      id: "e" + index,
      from: spec.from,
      to: spec.to,
      label: spec.label,
      bidirectional: spec.bidirectional === true,
      points: simplify(points),
      labelBox: label && spec.label
        ? { x: round(label.x + ox), y: round(label.y + oy), w: round(label.width), h: round(label.height) }
        : null,
    });
  }
  const width = round(out.width) + 2 * MARGIN;
  const height = round(out.height) + top + MARGIN;
  return {
    title: prepared.title,
    direction,
    bounds: { w: Math.max(width, textWidth(prepared.title, TITLE_FONT) + 2 * MARGIN), h: height },
    zones,
    nodes,
    edges,
    notices: [...(prepared.notices || [])],
  };
}

/** Retire les points intermédiaires alignés : une polyligne n'a que ses coudes. */
function simplify(points) {
  const out = [];
  for (const p of points) {
    out.push(p);
    while (out.length >= 3) {
      const [a, b, c] = out.slice(-3);
      const collinear = (a.x === b.x && b.x === c.x) || (a.y === b.y && b.y === c.y);
      if (!collinear) {
        break;
      }
      out.splice(out.length - 2, 1);
    }
  }
  return out;
}

// ------------------------------------------------------------------------------------------------ SVG

/** Le style d'une zone (SF-238-01 : style neutre ; les conventions AWS arrivent en SF-238-02). */
function zoneStyle() {
  return { stroke: "#7D8998", dash: "6 4", fill: "none", text: "#5A6B86", icon: null };
}

const iconCache = new Map();

/** Le PNG officiel en `data:` (le SVG voyage seul, SF-142-18), ou null si illisible. */
function dataUri(file) {
  if (!file) {
    return null;
  }
  if (iconCache.has(file)) {
    return iconCache.get(file);
  }
  let uri = null;
  try {
    uri = "data:image/png;base64," + fs.readFileSync(file).toString("base64");
  } catch (e) {
    uri = null;
  }
  iconCache.set(file, uri);
  return uri;
}

function drawZone(zone, parts) {
  const s = zoneStyle(zone);
  const b = zone.box;
  parts.push(`<g id="zone-${esc(zone.id)}" class="zone" data-kind="${esc(zone.kind)}">`);
  parts.push(`<rect class="zone-cadre" x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" fill="${s.fill}"`
    + ` stroke="${s.stroke}" stroke-width="1.5"${s.dash ? ` stroke-dasharray="${s.dash}"` : ""}/>`);
  parts.push(`<text class="zone-libelle" x="${b.x + 12}" y="${b.y + 21}" font-size="13" fill="${s.text}">`
    + `${esc(zone.label)}</text>`);
  parts.push("</g>");
}

function drawNode(node, parts) {
  const shape = shapeOf(node);
  const cx = node.box.x + node.box.w / 2;
  parts.push(`<g id="node-${esc(node.id)}" class="noeud" data-type="${esc(node.type)}">`);
  const uri = node.unknown ? null : dataUri(node.icon);
  const i = shape.icon;
  if (uri) {
    parts.push(`<image x="${i.x}" y="${i.y}" width="${i.w}" height="${i.h}" href="${uri}"/>`);
  } else {
    // Aucun composant inventé : une forme en pointillé, nommée par son libellé (SF-142-12).
    parts.push(`<rect class="inconnu" x="${i.x}" y="${i.y}" width="${i.w}" height="${i.h}" rx="6"`
      + ` fill="${UNKNOWN_FILL}" stroke="${UNKNOWN_STROKE}" stroke-width="2" stroke-dasharray="6 4"/>`);
    parts.push(`<text x="${cx}" y="${i.y + 41}" font-size="24" font-weight="bold" text-anchor="middle"`
      + ` fill="${UNKNOWN_STROKE}">?</text>`);
  }
  const color = node.unknown ? UNKNOWN_STROKE : INK;
  node.lines.forEach((line, k) => {
    parts.push(`<text x="${cx}" y="${shape.label.y + 12 + k * LINE_H}" font-size="${NODE_FONT}"`
      + ` text-anchor="middle" fill="${color}">${esc(line)}</text>`);
  });
  parts.push("</g>");
}

function drawEdge(edge, parts) {
  const pts = edge.points.map((p) => p.x + "," + p.y).join(" ");
  const start = edge.bidirectional ? ' marker-start="url(#cg-fleche)"' : "";
  parts.push(`<polyline id="lien-${esc(edge.id)}" class="lien" points="${pts}" fill="none"`
    + ` stroke="${EDGE_COLOR}" stroke-width="1.5" stroke-linejoin="miter"${start} marker-end="url(#cg-fleche)"/>`);
}

function drawEdgeLabel(edge, parts) {
  const b = edge.labelBox;
  if (!b || !edge.label) {
    return;
  }
  parts.push(`<g class="etiquette"><rect x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" fill="#FFFFFF"`
    + ` fill-opacity="0.9"/><text x="${b.x + b.w / 2}" y="${b.y + 12}" font-size="${EDGE_FONT}"`
    + ` text-anchor="middle" fill="${EDGE_COLOR}">${esc(edge.label)}</text></g>`);
}

/** Le SVG du modèle : zones (parents d'abord), liaisons, nœuds, étiquettes. */
function toSvg(model) {
  const { w, h } = model.bounds;
  const parts = [];
  parts.push(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}"`
    + ` font-family="Arial, Helvetica, sans-serif" data-moteur="cadre">`);
  parts.push('<defs><marker id="cg-fleche" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="8"'
    + ` markerHeight="8" markerUnits="userSpaceOnUse" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z"`
    + ` fill="${EDGE_COLOR}"/></marker></defs>`);
  parts.push(`<rect width="100%" height="100%" fill="#FFFFFF"/>`);
  if (model.title) {
    parts.push(`<text class="titre" x="${MARGIN}" y="${MARGIN + 20}" font-size="${TITLE_FONT}" font-weight="bold"`
      + ` fill="${INK}">${esc(model.title)}</text>`);
  }
  const zones = [...model.zones].sort((a, b) => a.depth - b.depth);
  for (const zone of zones) {
    drawZone(zone, parts);
  }
  for (const edge of model.edges) {
    drawEdge(edge, parts);
  }
  for (const node of model.nodes) {
    drawNode(node, parts);
  }
  for (const edge of model.edges) {
    drawEdgeLabel(edge, parts);
  }
  parts.push("</svg>");
  return parts.join("\n") + "\n";
}

// ------------------------------------------------------------------------------------------------ graphe

/** Le graphe d'architecture v1 (cadrage §5.1) : structure ET mise en page, coordonnées du SVG. */
function toGraph(model) {
  return {
    version: 1,
    title: model.title,
    direction: model.direction,
    bounds: { ...model.bounds },
    zones: model.zones.map((z) => ({
      id: z.id, kind: z.kind, label: z.label, parent: z.parent, depth: z.depth, box: { ...z.box },
      inferred: z.inferred,
    })),
    nodes: model.nodes.map((n) => ({
      id: n.id, type: n.type, resolved: n.resolved || null, label: n.label, zone: n.group || "",
      family: n.family || "generic", unknown: n.unknown === true, box: { ...n.box },
    })),
    edges: model.edges.map((e) => ({
      id: e.id, from: e.from, to: e.to, label: e.label || "", bidirectional: e.bidirectional === true,
      points: e.points.map((p) => ({ x: p.x, y: p.y })), labelBox: e.labelBox ? { ...e.labelBox } : null,
    })),
    notices: [...model.notices],
  };
}

/** Bornes de densité de F-142 (SF-142-17/19), reprises telles quelles : on DIT, on ne rogne pas. */
const MAX_DIMENSION = 4000;
const WIDE_ASPECT_RATIO = 3.0;
const WIDE_MIN_WIDTH = 2000;

/** La note de densité (ASCII, elle finit en en-tête HTTP), ou "" si le schéma se lit. */
function densityNotice(bounds) {
  const { w, h } = bounds;
  if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
    return `Schema tres dense : ${w} x ${h} pixels, au-dela de la borne de ${MAX_DIMENSION}. `
      + "Scinde-le en plusieurs vues : a cette taille il ne se lit plus.";
  }
  if (h > 0 && w >= WIDE_MIN_WIDTH && w >= h * WIDE_ASPECT_RATIO) {
    return `Schema large : ${w} x ${h} pixels (ratio ${(w / h).toFixed(1)}:1). Prefere le scinder en `
      + "plusieurs vues, ou une disposition verticale (TB) : etale ainsi, il devient minuscule quand la page "
      + "l'affiche.";
  }
  return "";
}

/** Une description préparée entre ; `{svg, graph, model}` sort. */
async function build(prepared) {
  const model = await layoutElk(prepared);
  return { svg: toSvg(model), graph: toGraph(model), model };
}

module.exports = { build, toSvg, toGraph, shapeOf, textWidth, densityNotice, GRID, esc };
