/**
 * Le `.drawio` CADRÉ (F-238 / SF-238-05, cadrage §4.2 dernier alinéa, §5.2).
 *
 * Le moteur `drawio` (SF-142-13) avait sa propre mise en page : boîtes en couches, cadres FRÈRES (le `parent`
 * était ignoré). Il reprend désormais EXACTEMENT les coordonnées du schéma cadré : zones imbriquées (conteneurs
 * draw.io, aux conventions AWS), nœuds à la place de leur icône et de leur libellé, liaisons orthogonales
 * (`orthogonalEdgeStyle`) passant par les coudes calculés, extrémités aux ports calculés. Le fichier réouvert dans
 * draw.io montre ce que montre le SVG ; l'utilisateur peut ensuite tout déplacer.
 *
 * Pur et déterministe : graphe + modèle entrent, XML sort. Tout ce qui vient du modèle est échappé.
 */

"use strict";

const { shapeOf, dataUri, zoneStyle, esc } = require("./cadre.js");

const EDGE_STYLE = "edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;strokeColor=#545B64;strokeWidth=1.5;"
  + "fontSize=12;fontColor=#545B64;labelBackgroundColor=#FFFFFF;endArrow=block;endFill=1;"
  + "exitPerimeter=0;entryPerimeter=0;";

/** Le rectangle d'un nœud dans draw.io : son icône et son libellé (là où s'attachent les liaisons). */
function nodeRect(node) {
  const s = shapeOf(node);
  const x = Math.min(s.icon.x, s.label.x);
  const right = Math.max(s.icon.x + s.icon.w, s.label.x + s.label.w);
  return { x, y: s.icon.y, w: right - x, h: s.label.y + s.label.h - s.icon.y };
}

const fmt = (v) => String(Math.round(v * 1000) / 1000);

/**
 * @param graph le graphe d'architecture (coordonnées du SVG)
 * @param model le modèle de `cadre.build` (libellés coupés, icônes)
 * @returns le XML `.drawio`
 */
function fromGraph(graph, model) {
  const zoneIndex = new Map(graph.zones.map((z, i) => [z.id, i]));
  const zoneById = new Map(graph.zones.map((z) => [z.id, z]));
  const nodeIndex = new Map(graph.nodes.map((n, i) => [n.id, i]));
  const modelNodes = new Map(model.nodes.map((n) => [n.id, n]));
  const parentCell = (zoneId) => (zoneId && zoneIndex.has(zoneId) ? "z-" + zoneIndex.get(zoneId) : "1");
  const origin = (zoneId) => (zoneId && zoneById.has(zoneId) ? zoneById.get(zoneId).box : { x: 0, y: 0 });
  const out = [];
  out.push('<?xml version="1.0" encoding="UTF-8"?>');
  out.push('<mxfile host="claude-gateway" type="device">');
  out.push(`  <diagram id="cg-diagram" name="${esc(graph.title || "Schéma")}">`);
  out.push('    <mxGraphModel dx="1200" dy="800" grid="1" gridSize="8" guides="1" tooltips="1" connect="1" '
    + `arrows="1" fold="1" page="1" pageScale="1" pageWidth="${graph.bounds.w}" pageHeight="${graph.bounds.h}" `
    + 'math="0" shadow="0">');
  out.push("      <root>");
  out.push('        <mxCell id="0" />');
  out.push('        <mxCell id="1" parent="0" />');
  if (graph.title) {
    out.push(`        <mxCell id="titre" value="${esc(graph.title)}" style="text;html=1;align=left;`
      + 'verticalAlign=middle;fontSize=20;fontStyle=1;fontColor=#232F3E;" vertex="1" parent="1">');
    out.push(`          <mxGeometry x="24" y="24" width="${Math.max(200, graph.bounds.w - 48)}" height="30" `
      + 'as="geometry" />');
    out.push("        </mxCell>");
  }
  // Zones, parents d'abord (l'ordre du document est l'ordre d'empilement) ; géométrie RELATIVE au parent.
  const zones = [...graph.zones].sort((a, b) => a.depth - b.depth || zoneIndex.get(a.id) - zoneIndex.get(b.id));
  for (const z of zones) {
    const s = zoneStyle(z);
    const o = origin(z.parent);
    const style = "rounded=0;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;"
      + `fillColor=${s.fill};strokeColor=${s.stroke};strokeWidth=1.5;${s.dash ? "dashed=1;dashPattern=6 4;" : ""}`
      + `fontColor=${s.stroke};fontSize=13;verticalAlign=top;align=left;spacingLeft=${s.icon ? 40 : 12};spacingTop=4;`;
    out.push(`        <mxCell id="z-${zoneIndex.get(z.id)}" value="${esc(z.label)}" style="${esc(style)}" `
      + `vertex="1" parent="${parentCell(z.parent)}">`);
    out.push(`          <mxGeometry x="${fmt(z.box.x - o.x)}" y="${fmt(z.box.y - o.y)}" width="${fmt(z.box.w)}" `
      + `height="${fmt(z.box.h)}" as="geometry" />`);
    out.push("        </mxCell>");
  }
  for (const n of graph.nodes) {
    const m = modelNodes.get(n.id);
    const r = nodeRect(m);
    const o = origin(n.zone);
    const uri = !m.unknown && !m.neutral ? dataUri(m.icon) : null;
    // L'icône officielle en haut, le libellé dessous ; sans icône : une boîte nommée (pointillée si type inconnu).
    const style = uri
      ? `shape=label;html=1;whiteSpace=wrap;image=${uri.replace("data:image/png;base64,", "data:image/png,")};`
        + "imageWidth=64;imageHeight=64;imageAlign=center;imageVerticalAlign=top;verticalAlign=bottom;align=center;"
        + "fillColor=none;strokeColor=none;fontSize=13;fontColor=#232F3E;"
      : "rounded=1;whiteSpace=wrap;html=1;fillColor=#FFFFFF;fontSize=13;"
        + (m.unknown ? "strokeColor=#B03A2E;dashed=1;fontColor=#B03A2E;" : "strokeColor=#232F3E;fontColor=#232F3E;");
    out.push(`        <mxCell id="n-${nodeIndex.get(n.id)}" value="${esc(n.label || n.type)}" style="${esc(style)}" `
      + `vertex="1" parent="${parentCell(n.zone)}">`);
    out.push(`          <mxGeometry x="${fmt(r.x - o.x)}" y="${fmt(r.y - o.y)}" width="${fmt(r.w)}" height="${fmt(r.h)}" `
      + 'as="geometry" />');
    out.push("        </mxCell>");
  }
  graph.edges.forEach((e, i) => {
    const pts = e.points;
    const a = nodeRect(modelNodes.get(e.from));
    const b = nodeRect(modelNodes.get(e.to));
    const first = pts[0];
    const last = pts[pts.length - 1];
    const style = EDGE_STYLE
      + `exitX=${fmt((first.x - a.x) / a.w)};exitY=${fmt((first.y - a.y) / a.h)};`
      + `entryX=${fmt((last.x - b.x) / b.w)};entryY=${fmt((last.y - b.y) / b.h)};`
      + (e.bidirectional ? "startArrow=block;startFill=1;" : "");
    out.push(`        <mxCell id="e-${i}" value="${esc(e.label || "")}" style="${esc(style)}" edge="1" parent="1" `
      + `source="n-${nodeIndex.get(e.from)}" target="n-${nodeIndex.get(e.to)}">`);
    out.push('          <mxGeometry relative="1" as="geometry">');
    out.push('            <Array as="points">');
    for (const p of pts.slice(1, -1)) {
      out.push(`              <mxPoint x="${fmt(p.x)}" y="${fmt(p.y)}" />`);
    }
    out.push("            </Array>");
    out.push("          </mxGeometry>");
    out.push("        </mxCell>");
  });
  out.push("      </root>");
  out.push("    </mxGraphModel>");
  out.push("  </diagram>");
  out.push("</mxfile>");
  return out.join("\n") + "\n";
}

module.exports = { fromGraph, nodeRect };
