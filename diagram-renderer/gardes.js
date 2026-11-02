/**
 * Les gardes du schéma cadré (F-238, cadrage §4.6). Pures : elles lisent le SVG et le graphe produits par
 * `cadre.js`, et rendent la liste des défauts trouvés (vide = la garde passe).
 *
 * Elles tournent dans les tests du renderer ET, pour G1/G2, à chaque rendu en production : un échec n'empêche
 * pas le livrable (le SVG part), il est COMPTÉ et écrit dans le journal.
 */

"use strict";

const { shapeOf } = require("./cadre.js");

const TOLERANCE = 0.5;

function pointsOf(attr) {
  return (attr.match(/-?[\d.]+,-?[\d.]+/g) || []).map((pair) => {
    const [x, y] = pair.split(",").map(Number);
    return { x, y };
  });
}

/**
 * G1 — Orthogonalité : chaque segment de liaison du SVG est horizontal ou vertical (0,5 px), et aucune
 * liaison n'est une courbe (`C`, `Q`, `A`, `S`, `T` dans un `path`).
 */
function g1Orthogonalite(svg) {
  const defects = [];
  let segments = 0;
  for (const m of svg.matchAll(/<polyline\b[^>]*class="lien"[^>]*points="([^"]+)"/g)) {
    const pts = pointsOf(m[1]);
    for (let i = 1; i < pts.length; i += 1) {
      segments += 1;
      const a = pts[i - 1];
      const b = pts[i];
      if (Math.abs(a.x - b.x) > TOLERANCE && Math.abs(a.y - b.y) > TOLERANCE) {
        defects.push(`segment oblique (${a.x},${a.y}) → (${b.x},${b.y})`);
      }
    }
  }
  for (const m of svg.matchAll(/<path\b[^>]*class="lien"[^>]*\sd="([^"]+)"/g)) {
    if (/[CcQqAaSsTt]/.test(m[1])) {
      defects.push("liaison courbe : " + m[1].slice(0, 40));
    }
  }
  return { defects, segments };
}

/** Distance d'un point au BORD d'un rectangle (dedans comme dehors). */
function distanceToBorder(p, r) {
  const inside = p.x >= r.x && p.x <= r.x + r.w && p.y >= r.y && p.y <= r.y + r.h;
  if (inside) {
    return Math.min(p.x - r.x, r.x + r.w - p.x, p.y - r.y, r.y + r.h - p.y);
  }
  const dx = Math.max(r.x - p.x, 0, p.x - (r.x + r.w));
  const dy = Math.max(r.y - p.y, 0, p.y - (r.y + r.h));
  return Math.hypot(dx, dy);
}

/** Les bords auxquels une liaison peut s'attacher : la boîte du nœud, son icône, son libellé. */
function attachRects(node) {
  const shape = shapeOf(node);
  return [node.box, shape.icon, shape.label];
}

/** G2 — Extrémités : une liaison commence et finit à ≤ 2 px du bord de son nœud (jamais « dans le vide »). */
function g2Extremites(graph, nodesWithLines) {
  const defects = [];
  const byId = new Map(nodesWithLines.map((n) => [n.id, n]));
  for (const edge of graph.edges) {
    const pts = edge.points;
    if (!pts || pts.length < 2) {
      defects.push(`liaison ${edge.id} sans tracé`);
      continue;
    }
    const ends = [[pts[0], edge.from], [pts[pts.length - 1], edge.to]];
    for (const [p, id] of ends) {
      const node = byId.get(id);
      const d = node ? Math.min(...attachRects(node).map((r) => distanceToBorder(p, r))) : Infinity;
      if (d > 2) {
        defects.push(`liaison ${edge.id} détachée de « ${id} » (${d.toFixed(1)} px)`);
      }
    }
  }
  return { defects };
}

const MIN_MARGIN = 24;

function inside(inner, outer, margin) {
  return inner.x - outer.x >= margin && inner.y - outer.y >= margin
    && outer.x + outer.w - (inner.x + inner.w) >= margin && outer.y + outer.h - (inner.y + inner.h) >= margin;
}

/** G4 — Imbrication : un nœud ou une zone tient dans sa zone parente, à 24 px au moins de chaque bord. */
function g4Imbrication(svg, graph) {
  const defects = [];
  const zones = new Map(graph.zones.map((z) => [z.id, z]));
  for (const z of graph.zones) {
    const parent = z.parent ? zones.get(z.parent) : null;
    if (parent && !inside(z.box, parent.box, MIN_MARGIN)) {
      defects.push(`zone « ${z.id} » déborde de « ${parent.id} » ou marge < ${MIN_MARGIN} px`);
    }
  }
  for (const n of graph.nodes) {
    const parent = n.zone ? zones.get(n.zone) : null;
    if (parent && !inside(n.box, parent.box, MIN_MARGIN)) {
      defects.push(`nœud « ${n.id} » déborde de « ${parent.id} » ou marge < ${MIN_MARGIN} px`);
    }
  }
  return { defects };
}

function attr(tag, name) {
  const m = tag.match(new RegExp(`\\s${name}="([^"]*)"`));
  return m ? m[1] : null;
}

/** G6 — Conventions : chaque `kind` est rendu avec la bordure, le trait, le fond et la couleur de libellé de §4.3. */
function g6Conventions(svg) {
  const { ZONE_STYLES } = require("./cadre.js");
  const defects = [];
  const blocks = svg.split('<g id="zone-').slice(1);
  for (const block of blocks) {
    const head = block.slice(0, block.indexOf("</g>"));
    const kind = (head.match(/data-kind="([^"]+)"/) || [])[1];
    const expected = ZONE_STYLES[kind];
    const rect = (head.match(/<rect class="zone-cadre"[^>]*>/) || [])[0] || "";
    const text = (head.match(/<text class="zone-libelle"[^>]*>/) || [])[0] || "";
    if (!expected || !rect) {
      defects.push(`zone sans convention (${kind})`);
      continue;
    }
    if (attr(rect, "stroke") !== expected.stroke) {
      defects.push(`${kind} : bordure ${attr(rect, "stroke")} au lieu de ${expected.stroke}`);
    }
    if ((attr(rect, "stroke-dasharray") || "") !== expected.dash) {
      defects.push(`${kind} : trait « ${attr(rect, "stroke-dasharray") || "continu"} »`);
    }
    if (attr(rect, "fill") !== expected.fill) {
      defects.push(`${kind} : fond ${attr(rect, "fill")} au lieu de ${expected.fill}`);
    }
    if (attr(text, "fill") !== expected.stroke) {
      defects.push(`${kind} : libellé ${attr(text, "fill")} au lieu de ${expected.stroke}`);
    }
    if (Boolean(expected.icon) !== /class="zone-icone"/.test(head)) {
      defects.push(`${kind} : icône de groupe ${expected.icon ? "absente" : "en trop"}`);
    }
  }
  return { defects };
}

module.exports = { g1Orthogonalite, g2Extremites, g4Imbrication, g6Conventions, distanceToBorder, pointsOf };
