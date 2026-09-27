/**
 * Le schéma RÉOUVRABLE dans draw.io (F-142 / SF-142-13).
 *
 * <b>Pourquoi ce module existe</b> : depuis SF-142-06/07, un schéma sort en PNG. Un PNG est un
 * cul-de-sac — le client qui veut déplacer une boîte doit tout refaire. `.drawio` est un fichier XML
 * (`mxGraphModel`) : on sait donc l'ÉCRIRE, sans embarquer draw.io (Electron + X virtuel) dans l'image.
 *
 * <b>Pur, et c'est la décision</b> : une description entre, `{xml, svg, width, height}` sort. Aucun
 * appel système, aucune dépendance, aucune écriture disque. Le rendu PNG de l'aperçu est fait par
 * l'appelant (`server.js`) à partir du SVG produit ici — les deux artefacts portent donc LA MÊME
 * géométrie, et ce qu'on voit dans l'image est exactement ce qui s'ouvre dans draw.io.
 *
 * <b>Déterministe</b> : aucun horodatage, aucun identifiant tiré au sort. La même description rend
 * deux fois le même octet — c'est ce qui rend le fichier diffable et le test possible.
 */

"use strict";

/** Bornes — dites, et appliquées avant de fabriquer quoi que ce soit. */
const MAX_NODES = 60;
const MAX_EDGES = 120;
const MAX_GROUPS = 12;
const MAX_LABEL = 120;

/** Géométrie. Les boîtes ont toutes la même taille : une mise en page lisible, pas une œuvre d'art. */
const NODE_W = 180;
const NODE_H = 60;
/** Écart entre deux couches (le sens du flux) et entre deux voisins d'une même couche. */
const GAP_MAIN = 90;
const GAP_CROSS = 30;
/** Marge du cadre de groupe autour de ses membres, et hauteur de son bandeau de titre. */
const GROUP_PAD = 22;
const GROUP_HEADER = 26;
/** Écart entre deux bandes de groupe — sans lui, deux cadres se toucheraient. */
const BAND_GAP = 26;
const MARGIN = 40;
const TITLE_H = 44;

/** La charte (DESIGN_SYSTEM) : navy structurel, orange réservé à l'accent, gris pour les cadres. */
const NAVY = "#0B1020";
const INK = "#0F172A";
const MUTED = "#5A6478";
const RULE = "#94A3B8";
const ORANGE = "#E07B39";
const WHITE = "#FFFFFF";

/** L'échec du module : une raison, pas un code. L'appelant la rend telle quelle à l'agent. */
class DrawioRejected extends Error {}

function fail(message) {
  throw new DrawioRejected(message);
}

/** Échappement XML — appliqué à TOUT ce qui vient du modèle, attributs comme contenu. */
function xml(value) {
  return String(value === undefined || value === null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function label(raw, fallback, what) {
  const text = typeof raw === "string" ? raw.trim() : "";
  if (!text) {
    return fallback;
  }
  if (text.length > MAX_LABEL) {
    fail(`Libellé trop long pour ${what} : ${text.length} caractères pour un maximum de ${MAX_LABEL}.`);
  }
  return text;
}

function identifier(raw, what) {
  const id = typeof raw === "string" ? raw.trim() : "";
  if (!id) {
    fail(`${what} : « id » manquant. Chaque nœud et chaque groupe porte un identifiant.`);
  }
  return id;
}

/**
 * Lit la description et refuse tout ce qui ne tient pas — AVANT toute mise en page. Un lien pendant
 * qui passerait ici produirait un fichier ouvrable mais faux : c'est exactement ce qu'on ne veut pas
 * dans un livrable client.
 */
function readSpec(spec) {
  if (!spec || typeof spec !== "object" || Array.isArray(spec)) {
    fail("La description du schéma est manquante.");
  }
  const rawNodes = Array.isArray(spec.nodes) ? spec.nodes : [];
  if (rawNodes.length === 0) {
    fail("La description doit porter au moins un nœud (« nodes »), chacun avec son « id » et son « label ».");
  }
  if (rawNodes.length > MAX_NODES) {
    fail(`Schéma trop grand : ${rawNodes.length} nœuds pour un maximum de ${MAX_NODES}. `
      + "Découpe-le en plusieurs schémas — un schéma illisible n'aide personne.");
  }
  const rawGroups = Array.isArray(spec.groups) ? spec.groups : [];
  if (rawGroups.length > MAX_GROUPS) {
    fail(`Trop de groupes : ${rawGroups.length} pour un maximum de ${MAX_GROUPS}.`);
  }
  const rawEdges = Array.isArray(spec.edges) ? spec.edges : [];
  if (rawEdges.length > MAX_EDGES) {
    fail(`Trop de liens : ${rawEdges.length} pour un maximum de ${MAX_EDGES}.`);
  }

  const groups = [];
  const groupIndex = new Map();
  rawGroups.forEach((raw, i) => {
    const g = raw && typeof raw === "object" ? raw : {};
    const id = identifier(g.id, `Groupe n°${i + 1}`);
    if (groupIndex.has(id)) {
      fail(`Groupe en double : « ${id} ». Chaque groupe porte un identifiant unique.`);
    }
    groupIndex.set(id, groups.length);
    groups.push({ id, label: label(g.label, id, `le groupe « ${id} »`) });
  });

  const nodes = [];
  const nodeIndex = new Map();
  rawNodes.forEach((raw, i) => {
    const n = raw && typeof raw === "object" ? raw : {};
    const id = identifier(n.id, `Nœud n°${i + 1}`);
    if (nodeIndex.has(id)) {
      fail(`Nœud en double : « ${id} ». Chaque nœud porte un identifiant unique.`);
    }
    const group = typeof n.group === "string" && n.group.trim() ? n.group.trim() : "";
    if (group && !groupIndex.has(group)) {
      fail(`Le nœud « ${id} » désigne le groupe « ${group} », qui n'est pas déclaré dans « groups ».`);
    }
    nodeIndex.set(id, nodes.length);
    nodes.push({ id, label: label(n.label, id, `le nœud « ${id} »`), group });
  });

  const edges = [];
  rawEdges.forEach((raw, i) => {
    const e = raw && typeof raw === "object" ? raw : {};
    const from = typeof e.from === "string" ? e.from.trim() : "";
    const to = typeof e.to === "string" ? e.to.trim() : "";
    if (!nodeIndex.has(from)) {
      fail(`Lien pendant n°${i + 1} : « ${from || "(vide)"} » ne désigne aucun nœud déclaré.`);
    }
    if (!nodeIndex.has(to)) {
      fail(`Lien pendant n°${i + 1} : « ${to || "(vide)"} » ne désigne aucun nœud déclaré.`);
    }
    edges.push({
      from: nodeIndex.get(from),
      to: nodeIndex.get(to),
      label: typeof e.label === "string" && e.label.trim()
        ? label(e.label, "", `le lien « ${from} » → « ${to} »`) : "",
      dashed: e.dashed === true,
    });
  });

  return {
    title: typeof spec.title === "string" && spec.title.trim()
      ? label(spec.title, "", "le titre") : "",
    direction: String(spec.direction || "LR").trim().toUpperCase() === "TB" ? "TB" : "LR",
    groups,
    groupIndex,
    nodes,
    edges,
  };
}

/**
 * La couche de chaque nœud : le plus long chemin depuis une racine. Relaxation bornée par le nombre
 * de nœuds — un cycle ne doit pas faire tourner le service, il doit rendre une mise en page.
 */
function layers(nodes, edges) {
  const layer = new Array(nodes.length).fill(0);
  for (let pass = 0; pass < nodes.length; pass++) {
    let moved = false;
    for (const e of edges) {
      if (e.from !== e.to && layer[e.to] < layer[e.from] + 1) {
        layer[e.to] = layer[e.from] + 1;
        moved = true;
      }
    }
    if (!moved) {
      break;
    }
  }
  return layer;
}

/**
 * La mise en page. Chaque groupe reçoit une BANDE de couloirs qui n'appartient qu'à lui : c'est ce qui
 * garantit que deux cadres de groupe ne se chevauchent jamais, quelle que soit la description. Les
 * nœuds sans groupe forment une bande de plus, à la fin.
 */
function layout(model) {
  const { nodes, edges, groups, direction } = model;
  const layer = layers(nodes, edges);

  // Les bandes, dans l'ordre de déclaration des groupes ; la bande « sans groupe » ferme la marche.
  const bands = groups.map((g) => ({ group: g, members: [] }));
  const loose = { group: null, members: [] };
  nodes.forEach((n, i) => {
    const band = n.group ? bands[model.groupIndex.get(n.group)] : loose;
    band.members.push(i);
  });
  const ordered = bands.filter((b) => b.members.length > 0);
  if (loose.members.length > 0) {
    ordered.push(loose);
  }
  const hasBox = ordered.some((b) => b.group !== null);

  const horizontal = direction === "LR";
  const sizeMain = horizontal ? NODE_W : NODE_H;
  const sizeCross = horizontal ? NODE_H : NODE_W;
  // Le titre occupe TOUJOURS le bandeau du haut : en LR il décale l'axe transverse (y), en TB l'axe
  // du flux (y aussi). Le bandeau d'un cadre de groupe, lui, se prend toujours vers le haut.
  const mainStart = horizontal
    ? MARGIN + (hasBox ? GROUP_PAD : 0)
    : MARGIN + TITLE_H + (hasBox ? GROUP_PAD + GROUP_HEADER : 0);
  let cross = horizontal ? MARGIN + TITLE_H : MARGIN;

  const placed = new Array(nodes.length).fill(null);
  const boxes = [];
  for (const band of ordered) {
    const perLayer = new Map();
    for (const i of band.members) {
      const list = perLayer.get(layer[i]) || [];
      list.push(i);
      perLayer.set(layer[i], list);
    }
    const width = Math.max(...Array.from(perLayer.values(), (l) => l.length));
    const grouped = band.group !== null;
    // La bande d'un groupe réserve la place de son cadre : c'est CE calcul qui garantit que deux
    // cadres ne se chevauchent jamais, quelle que soit la description.
    const before = grouped ? (horizontal ? GROUP_PAD + GROUP_HEADER : GROUP_PAD) : 0;
    const start = cross + before;
    for (const [, list] of Array.from(perLayer.entries()).sort((a, b) => a[0] - b[0])) {
      list.forEach((i, slot) => {
        const mainPos = mainStart + layer[i] * (sizeMain + GAP_MAIN);
        const crossPos = start + slot * (sizeCross + GAP_CROSS);
        placed[i] = horizontal
          ? { x: mainPos, y: crossPos, w: NODE_W, h: NODE_H }
          : { x: crossPos, y: mainPos, w: NODE_W, h: NODE_H };
      });
    }
    const bandEnd = start + width * (sizeCross + GAP_CROSS) - GAP_CROSS;
    if (grouped) {
      const mine = band.members.map((i) => placed[i]);
      const minX = Math.min(...mine.map((b) => b.x));
      const maxX = Math.max(...mine.map((b) => b.x + b.w));
      const minY = Math.min(...mine.map((b) => b.y));
      const maxY = Math.max(...mine.map((b) => b.y + b.h));
      boxes.push({
        group: band.group,
        x: minX - GROUP_PAD,
        y: minY - GROUP_PAD - GROUP_HEADER,
        w: maxX - minX + 2 * GROUP_PAD,
        h: maxY - minY + 2 * GROUP_PAD + GROUP_HEADER,
      });
      cross = bandEnd + GROUP_PAD + BAND_GAP;
    } else {
      cross = bandEnd + BAND_GAP;
    }
  }

  const all = placed.concat(boxes);
  const width = Math.max(...all.map((b) => b.x + b.w)) + MARGIN;
  const height = Math.max(...all.map((b) => b.y + b.h)) + MARGIN;
  return { placed, boxes, width, height };
}

/**
 * Le tracé d'un lien, en coudes — le même que celui de draw.io (`orthogonalEdgeStyle`), pour que
 * l'aperçu ressemble à ce que le client verra en ouvrant le fichier.
 */
function route(a, b, direction) {
  if (direction === "LR") {
    const sx = a.x + a.w;
    const sy = a.y + a.h / 2;
    const tx = b.x;
    const ty = b.y + b.h / 2;
    const mx = Math.round((sx + tx) / 2);
    return { points: [[sx, sy], [mx, sy], [mx, ty], [tx, ty]], mid: [mx, Math.round((sy + ty) / 2)] };
  }
  const sx = a.x + a.w / 2;
  const sy = a.y + a.h;
  const tx = b.x + b.w / 2;
  const ty = b.y;
  const my = Math.round((sy + ty) / 2);
  return { points: [[sx, sy], [sx, my], [tx, my], [tx, ty]], mid: [Math.round((sx + tx) / 2), my] };
}

/** Coupe un libellé en lignes courtes — une boîte de 180 px ne tient pas une phrase sur une ligne. */
function wrap(text, maxChars) {
  const words = text.split(/\s+/).filter(Boolean);
  const lines = [];
  let current = "";
  for (const word of words) {
    if (!current) {
      current = word;
    } else if (current.length + 1 + word.length <= maxChars) {
      current += " " + word;
    } else {
      lines.push(current);
      current = word;
    }
    if (lines.length === 2) {
      break;
    }
  }
  if (current && lines.length < 3) {
    lines.push(current);
  }
  return lines.length ? lines : [text];
}

const NODE_STYLE = `rounded=1;whiteSpace=wrap;html=1;fillColor=${WHITE};strokeColor=${NAVY};`
  + `fontColor=${INK};fontSize=13;arcSize=12;strokeWidth=1.5;`;
const GROUP_STYLE = `rounded=1;whiteSpace=wrap;html=1;dashed=1;fillColor=none;strokeColor=${RULE};`
  + `fontColor=${MUTED};fontSize=12;verticalAlign=top;align=left;spacingLeft=10;spacingTop=4;arcSize=6;`;
const TITLE_STYLE = `text;html=1;align=left;verticalAlign=middle;fontSize=18;fontStyle=1;fontColor=${NAVY};`;
const EDGE_STYLE = `edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;strokeColor=${NAVY};`
  + `fontSize=11;fontColor=${MUTED};endArrow=block;endFill=1;jettySize=auto;orthogonalLoop=1;`;

/** Le fichier `.drawio` : un `mxfile` minimal, sans horodatage — donc reproductible. */
function toDrawio(model, geometry) {
  const out = [];
  out.push('<?xml version="1.0" encoding="UTF-8"?>');
  out.push('<mxfile host="claude-gateway" type="device">');
  out.push(`  <diagram id="cg-diagram" name="${xml(model.title || "Schéma")}">`);
  out.push('    <mxGraphModel dx="1200" dy="800" grid="1" gridSize="10" guides="1" tooltips="1" '
    + 'connect="1" arrows="1" fold="1" page="1" pageScale="1" '
    + `pageWidth="${geometry.width}" pageHeight="${geometry.height}" math="0" shadow="0">`);
  out.push("      <root>");
  out.push('        <mxCell id="0" />');
  out.push('        <mxCell id="1" parent="0" />');
  if (model.title) {
    out.push(`        <mxCell id="title" value="${xml(model.title)}" style="${TITLE_STYLE}" `
      + 'vertex="1" parent="1">');
    out.push(`          <mxGeometry x="${MARGIN}" y="${MARGIN - 6}" `
      + `width="${Math.max(200, geometry.width - 2 * MARGIN)}" height="30" as="geometry" />`);
    out.push("        </mxCell>");
  }
  // Les cadres AVANT les nœuds : dans draw.io, l'ordre du document est l'ordre d'empilement.
  geometry.boxes.forEach((box, i) => {
    out.push(`        <mxCell id="g-${i}" value="${xml(box.group.label)}" style="${GROUP_STYLE}" `
      + 'vertex="1" parent="1">');
    out.push(`          <mxGeometry x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}" `
      + 'as="geometry" />');
    out.push("        </mxCell>");
  });
  model.nodes.forEach((node, i) => {
    const b = geometry.placed[i];
    out.push(`        <mxCell id="n-${i}" value="${xml(node.label)}" style="${NODE_STYLE}" `
      + 'vertex="1" parent="1">');
    out.push(`          <mxGeometry x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" `
      + 'as="geometry" />');
    out.push("        </mxCell>");
  });
  model.edges.forEach((edge, i) => {
    const style = EDGE_STYLE + (edge.dashed ? "dashed=1;" : "");
    out.push(`        <mxCell id="e-${i}" value="${xml(edge.label)}" style="${style}" edge="1" `
      + `parent="1" source="n-${edge.from}" target="n-${edge.to}">`);
    out.push('          <mxGeometry relative="1" as="geometry" />');
    out.push("        </mxCell>");
  });
  out.push("      </root>");
  out.push("    </mxGraphModel>");
  out.push("  </diagram>");
  out.push("</mxfile>");
  return out.join("\n") + "\n";
}

/** L'aperçu, rendu depuis LA MÊME géométrie : l'image ne peut pas mentir sur le fichier. */
function toSvg(model, geometry) {
  const { width, height, placed, boxes } = geometry;
  const out = [];
  out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" `
    + `viewBox="0 0 ${width} ${height}" font-family="Helvetica, Arial, sans-serif">`);
  out.push('  <defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" '
    + `markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="${NAVY}"/>`
    + "</marker></defs>");
  out.push(`  <rect x="0" y="0" width="${width}" height="${height}" fill="${WHITE}"/>`);
  if (model.title) {
    out.push(`  <text x="${MARGIN}" y="${MARGIN + 12}" font-size="18" font-weight="700" `
      + `fill="${NAVY}">${xml(model.title)}</text>`);
    out.push(`  <rect x="${MARGIN}" y="${MARGIN + 20}" width="48" height="3" fill="${ORANGE}"/>`);
  }
  for (const box of boxes) {
    out.push(`  <rect x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}" rx="8" `
      + `fill="none" stroke="${RULE}" stroke-width="1.5" stroke-dasharray="6 4"/>`);
    out.push(`  <text x="${box.x + 10}" y="${box.y + 17}" font-size="12" fill="${MUTED}">`
      + `${xml(box.group.label)}</text>`);
  }
  model.edges.forEach((edge) => {
    const { points, mid } = route(placed[edge.from], placed[edge.to], model.direction);
    const d = points.map((p, i) => `${i === 0 ? "M" : "L"} ${p[0]} ${p[1]}`).join(" ");
    out.push(`  <path d="${d}" fill="none" stroke="${NAVY}" stroke-width="1.5"`
      + (edge.dashed ? ' stroke-dasharray="6 4"' : "") + ' marker-end="url(#arrow)"/>');
    if (edge.label) {
      const text = xml(edge.label);
      out.push(`  <rect x="${mid[0] - Math.min(90, edge.label.length * 3.4 + 6)}" y="${mid[1] - 9}" `
        + `width="${Math.min(180, edge.label.length * 6.8 + 12)}" height="16" fill="${WHITE}"/>`);
      out.push(`  <text x="${mid[0]}" y="${mid[1] + 3}" font-size="11" fill="${MUTED}" `
        + `text-anchor="middle">${text}</text>`);
    }
  });
  model.nodes.forEach((node, i) => {
    const b = placed[i];
    out.push(`  <rect x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" rx="8" fill="${WHITE}" `
      + `stroke="${NAVY}" stroke-width="1.5"/>`);
    const lines = wrap(node.label, 24);
    const top = b.y + b.h / 2 - (lines.length - 1) * 8;
    lines.forEach((line, k) => {
      out.push(`  <text x="${b.x + b.w / 2}" y="${top + k * 16 + 4}" font-size="13" fill="${INK}" `
        + `text-anchor="middle">${xml(line)}</text>`);
    });
  });
  out.push("</svg>");
  return out.join("\n") + "\n";
}

/**
 * Construit les deux artefacts depuis une description.
 *
 * @param {object} spec {title, direction, groups[{id,label}], nodes[{id,label,group}], edges[{from,to,label,dashed}]}
 * @returns {{xml: string, svg: string, width: number, height: number}}
 * @throws {DrawioRejected} la description est refusée — la raison est dite, et elle permet de corriger
 */
function build(spec) {
  const model = readSpec(spec);
  const geometry = layout(model);
  return {
    xml: toDrawio(model, geometry),
    svg: toSvg(model, geometry),
    width: geometry.width,
    height: geometry.height,
  };
}

module.exports = { build, DrawioRejected, MAX_NODES, MAX_EDGES, MAX_GROUPS, MAX_LABEL };
