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
const { compose } = require("./cadre-composition.js");
const { routeAll } = require("./cadre-routage.js");

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

/**
 * Le sens du flux. Donné : TB/BT ⇒ de haut en bas, LR/RL ⇒ de gauche à droite. Absent (cadrage §19 Q2) : de
 * haut en bas pour un schéma multi-AZ (AZ en colonnes, la forme des schémas de référence AWS), sinon de gauche
 * à droite.
 */
function directionOf(prepared) {
  const raw = String(prepared.direction || "").toUpperCase();
  if (raw === "TB" || raw === "BT") {
    return "TB";
  }
  if (raw === "LR" || raw === "RL") {
    return "LR";
  }
  return (prepared.groups || []).filter((g) => g.kind === "az").length >= 2 ? "TB" : "LR";
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
        "elk.nodeSize.minimum": `(${snapUp(textWidth(g.label, ZONE_FONT) + GROUP_ICON + 32)}, 64)`,
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
    resources: prepared.resources || "",
  };
}

// ------------------------------------------------------------------------------- composition + routeur

/**
 * La mise en page CADRÉE (SF-238-03/04) : zones composées par nous (AZ parallèles, bande régionale, flux),
 * nœuds placés par ELK dans leur zone sur la grille de 8 px, liaisons routées par notre routeur orthogonal.
 */
async function layoutCadre(prepared) {
  const direction = directionOf(prepared);
  const comp = await compose(prepared, direction);
  const groups = new Map(prepared.groups.map((g) => [g.id, g]));
  const top = MARGIN + (prepared.title ? TITLE_H : 0);
  const left = MARGIN;
  const shift = (b) => ({ x: b.x + left, y: b.y + top, w: b.w, h: b.h });
  const depthOf = (gid) => {
    let depth = 0;
    let current = groups.get(gid);
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
    box: shift(comp.zones.get(g.id)),
  }));
  const nodes = prepared.nodes.map((n) => ({
    ...n,
    group: n.group && groups.has(n.group) ? n.group : "",
    box: shift(comp.nodes.get(n.id)),
  }));
  const bounds = {
    w: snapUp(Math.max(comp.w + 2 * MARGIN, textWidth(prepared.title, TITLE_FONT) + 2 * MARGIN)),
    h: snapUp(comp.h + top + MARGIN),
  };
  const routed = routeAll({
    bounds,
    direction,
    zones: zones.map((z) => ({ ...z, header: headerRect(z) })),
    nodes,
    edges: prepared.edges,
  });
  const model = {
    title: prepared.title,
    direction,
    bounds,
    zones,
    nodes,
    edges: prepared.edges.map((e, i) => ({
      id: "e" + i,
      from: e.from,
      to: e.to,
      label: e.label,
      bidirectional: e.bidirectional === true,
      points: routed.points[i],
      labelBox: null,
    })),
    notices: [...(prepared.notices || []), ...routed.notices],
    resources: prepared.resources || "",
  };
  placeLabels(model);
  return model;
}

function rectsOverlap(a, b) {
  return Math.max(a.x, b.x) < Math.min(a.x + a.w, b.x + b.w) - 0.5
    && Math.max(a.y, b.y) < Math.min(a.y + a.h, b.y + b.h) - 0.5;
}

/** Combien de segments (d'AUTRES liaisons) traversent un rectangle : une étiquette ne doit pas masquer un trait. */
function segmentsUnder(box, segments) {
  let count = 0;
  for (const [a, b] of segments) {
    const r = { x: Math.min(a.x, b.x) - 0.5, y: Math.min(a.y, b.y) - 0.5,
      w: Math.abs(a.x - b.x) + 1, h: Math.abs(a.y - b.y) + 1 };
    if (rectsOverlap(box, r)) {
      count += 1;
    }
  }
  return count;
}

/**
 * Les étiquettes de flux (SF-238-04, cadrage §4.2 étape 4) : sur le plus long segment de leur liaison, centrées ;
 * déplacées le long du segment, puis sur le segment suivant, puis à côté du trait, si elles chevauchent quelque
 * chose (icône, libellé de nœud, bandeau de zone, autre étiquette — garde G3). Une seule étiquette par liaison.
 * À collision égale, celle qui masque le moins d'autres traits.
 */
function placeLabels(model) {
  const hard = [];
  for (const n of model.nodes) {
    const s = shapeOf(n);
    hard.push(s.icon, s.label);
  }
  for (const z of model.zones) {
    hard.push(headerRect(z));
  }
  const segmentsOf = (edge) => edge.points.slice(1).map((p, i) => [edge.points[i], p]);
  const W = model.bounds.w;
  const H = model.bounds.h;
  for (const edge of model.edges) {
    if (!edge.label) {
      continue;
    }
    const w = textWidth(edge.label, EDGE_FONT) + 8;
    const h = LINE_H;
    const others = model.edges.filter((e) => e !== edge).flatMap(segmentsOf);
    const segments = segmentsOf(edge)
      .map(([a, b], i) => ({ a, b, i, length: Math.abs(a.x - b.x) + Math.abs(a.y - b.y) }))
      .sort((u, v) => v.length - u.length || u.i - v.i);
    const candidates = [];
    for (const s of segments) {
      const horizontal = s.a.y === s.b.y;
      const extent = horizontal ? w : h;
      const half = Math.max(0, Math.floor((s.length - extent) / 2 / GRID));
      const cx = (s.a.x + s.b.x) / 2;
      const cy = (s.a.y + s.b.y) / 2;
      for (let k = 0; k <= half; k += 1) {
        for (const sign of k ? [-1, 1] : [1]) {
          const d = sign * k * GRID;
          const x = horizontal ? cx + d : cx;
          const y = horizontal ? cy : cy + d;
          candidates.push({ x: Math.round(x - w / 2), y: Math.round(y - h / 2), w, h });
        }
      }
    }
    for (const s of segments) {
      // À côté du trait, au milieu du segment : au-dessus / dessous, ou à gauche / droite.
      const cx = (s.a.x + s.b.x) / 2;
      const cy = (s.a.y + s.b.y) / 2;
      if (s.a.y === s.b.y) {
        candidates.push({ x: Math.round(cx - w / 2), y: Math.round(cy - h - 2), w, h });
        candidates.push({ x: Math.round(cx - w / 2), y: Math.round(cy + 2), w, h });
      } else {
        candidates.push({ x: Math.round(cx + 4), y: Math.round(cy - h / 2), w, h });
        candidates.push({ x: Math.round(cx - w - 4), y: Math.round(cy - h / 2), w, h });
      }
    }
    let chosen = null;
    let chosenSoft = Infinity;
    for (const c of candidates) {
      if (c.x < 0 || c.y < 0 || c.x + c.w > W || c.y + c.h > H || hard.some((r) => rectsOverlap(c, r))) {
        continue;
      }
      const soft = segmentsUnder(c, others);
      if (soft < chosenSoft) {
        chosen = c;
        chosenSoft = soft;
        if (soft === 0) {
          break;
        }
      }
    }
    edge.labelBox = chosen || candidates[0];
    hard.push(edge.labelBox);
  }
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

/**
 * Les conventions des groupes AWS (paquet officiel « AWS Architecture Icons », section Groups ; cadrage §4.3).
 * `icon` : `png:<chemin sous resources/>` (icône officielle livrée par `diagrams`, jamais recolorée) ou
 * `drawn:<nom>` (glyphe vectoriel blanc sur carré de la couleur de bordure, pour les icônes absentes de la lib).
 * La garde G6 compare le SVG à CETTE table : une seule source.
 */
const ZONE_STYLES = {
  cloud: { stroke: "#232F3E", dash: "", fill: "none", icon: "drawn:aws" },
  account: { stroke: "#E7157B", dash: "", fill: "none", icon: "png:aws/management/organizations-account" },
  region: { stroke: "#00A4A6", dash: "6 4", fill: "none", icon: "drawn:drapeau" },
  vpc: { stroke: "#8C4FFF", dash: "", fill: "none", icon: "png:aws/network/vpc" },
  az: { stroke: "#00A4A6", dash: "6 4", fill: "none", icon: null },
  subnet_public: { stroke: "#7AA116", dash: "", fill: "#F2F6E8", icon: "png:aws/network/public-subnet" },
  subnet_private: { stroke: "#00A4A6", dash: "", fill: "#E6F6F7", icon: "png:aws/network/private-subnet" },
  asg: { stroke: "#ED7100", dash: "6 4", fill: "none", icon: "png:aws/compute/ec2-auto-scaling" },
  security_group: { stroke: "#DD344C", dash: "", fill: "none", icon: null },
  onprem: { stroke: "#7D8998", dash: "", fill: "none", icon: "png:aws/general/office-building" },
  generic: { stroke: "#7D8998", dash: "6 4", fill: "none", icon: null },
};

const GROUP_ICON = 32;
const ZONE_FONT = 13;

/** Le style d'une zone selon son `kind` (inconnu ⇒ générique). */
function zoneStyle(zone) {
  return ZONE_STYLES[zone.kind] || ZONE_STYLES.generic;
}

/** Le bandeau d'une zone (icône de groupe + libellé, en haut à gauche) : rien ne doit le chevaucher. */
function headerRect(zone) {
  const s = zoneStyle(zone);
  const textX = s.icon ? GROUP_ICON + 8 : 12;
  return { x: zone.box.x, y: zone.box.y, w: textX + textWidth(zone.label, ZONE_FONT) + 4, h: GROUP_ICON };
}

/** Les glyphes dessinés (blancs) des icônes de groupe absentes de la lib. */
function drawnGlyph(name, x, y) {
  if (name === "aws") {
    return `<text x="${x + 16}" y="${y + 19}" font-size="11" font-weight="bold" text-anchor="middle"`
      + ` fill="#FFFFFF">aws</text><path d="M${x + 8},${y + 23} q8,4 16,0" stroke="#FF9900" stroke-width="1.6"`
      + ` fill="none"/>`;
  }
  // Le drapeau de la Région.
  return `<path d="M${x + 10},${y + 7} v19 M${x + 10},${y + 8} h12 l-3,4 l3,4 h-12" stroke="#FFFFFF"`
    + ` stroke-width="1.8" fill="none"/>`;
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

function drawZone(zone, parts, resources) {
  const s = zoneStyle(zone);
  const b = zone.box;
  parts.push(`<g id="zone-${esc(zone.id)}" class="zone" data-kind="${esc(zone.kind)}">`);
  parts.push(`<rect class="zone-cadre" x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" fill="${s.fill}"`
    + ` stroke="${s.stroke}" stroke-width="1.5"${s.dash ? ` stroke-dasharray="${s.dash}"` : ""}/>`);
  let textX = b.x + 12;
  if (s.icon) {
    const [how, name] = s.icon.split(":");
    const uri = how === "png" && resources ? dataUri(`${resources}/${name}.png`) : null;
    if (uri) {
      parts.push(`<image class="zone-icone" x="${b.x}" y="${b.y}" width="${GROUP_ICON}" height="${GROUP_ICON}"`
        + ` xlink:href="${uri}"/>`);
    } else {
      parts.push(`<rect class="zone-icone" x="${b.x}" y="${b.y}" width="${GROUP_ICON}" height="${GROUP_ICON}"`
        + ` fill="${s.stroke}"/>` + (how === "drawn" ? drawnGlyph(name, b.x, b.y) : ""));
    }
    textX = b.x + GROUP_ICON + 8;
  }
  parts.push(`<text class="zone-libelle" x="${textX}" y="${b.y + 21}" font-size="${ZONE_FONT}" fill="${s.stroke}">`
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
    parts.push(`<image x="${i.x}" y="${i.y}" width="${i.w}" height="${i.h}" xlink:href="${uri}"/>`);
  } else if (node.neutral) {
    // Moteur drawio (SF-238-05) : un nœud SANS type est une boîte nommée, pas un type inconnu.
    parts.push(`<rect class="neutre" x="${i.x}" y="${i.y}" width="${i.w}" height="${i.h}" rx="8"`
      + ` fill="#FFFFFF" stroke="${INK}" stroke-width="1.5"/>`);
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
  parts.push(`<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}"`
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
    drawZone(zone, parts, model.resources);
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
async function build(prepared, options = {}) {
  // `mode: "elk"` garde la mise en page ELK du graphe entier (SF-238-01) : le repli du cadrage §22.
  const model = options.mode === "elk" ? await layoutElk(prepared) : await layoutCadre(prepared);
  return { svg: toSvg(model), graph: toGraph(model), model };
}

/**
 * Moteur `drawio` (SF-238-05) : un nœud SANS type est une boîte nommée (le contrat de SF-142-13), pas un type
 * inconnu — il n'est ni signalé, ni dessiné en pointillé rouge.
 */
function neutralizeUntyped(prepared) {
  const nodes = prepared.nodes.map((n) => (n.type ? n : { ...n, unknown: false, neutral: true, family: "generic" }));
  const unknown = (prepared.unknown || []).filter((k) => k);
  return { ...prepared, nodes, unknown, unknownSaid: unknown.length ? prepared.unknownSaid : "" };
}

module.exports = {
  build, toSvg, toGraph, shapeOf, headerRect, textWidth, densityNotice, neutralizeUntyped, dataUri, zoneStyle,
  ZONE_STYLES, GRID, esc,
};
