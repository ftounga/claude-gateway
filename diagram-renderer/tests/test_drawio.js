/**
 * Tests du module `.drawio` (F-142 / SF-142-13).
 *
 * Le module est PUR : ces tests tournent avec `node --test`, sans conteneur, sans chromium, sans
 * réseau. C'est précisément l'intérêt d'avoir laissé la rasterisation au serveur.
 *
 *   node --test diagram-renderer/tests/
 */

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const drawio = require("../drawio.js");

const NOMINAL = {
  title: "Architecture cible",
  direction: "LR",
  groups: [{ id: "vpc", label: "VPC production" }],
  nodes: [
    { id: "u", label: "Utilisateur" },
    { id: "alb", label: "Répartiteur de charge", group: "vpc" },
    { id: "app", label: "Application", group: "vpc" },
    { id: "db", label: "PostgreSQL", group: "vpc" },
  ],
  edges: [
    { from: "u", to: "alb", label: "HTTPS" },
    { from: "alb", to: "app" },
    { from: "app", to: "db", label: "SQL" },
  ],
};

/** Une lecture volontairement naïve des géométries, pour vérifier des positions sans dépendance. */
function geometries(xml) {
  return Array.from(xml.matchAll(/<mxGeometry x="(-?\d+)" y="(-?\d+)" width="(\d+)" height="(\d+)"/g))
    .map((m) => ({ x: +m[1], y: +m[2], w: +m[3], h: +m[4] }));
}

function cell(xml, id) {
  const start = xml.indexOf(`id="${id}"`);
  assert.notEqual(start, -1, `cellule « ${id} » absente`);
  const geo = xml.slice(start).match(/<mxGeometry x="(-?\d+)" y="(-?\d+)" width="(\d+)" height="(\d+)"/);
  return geo ? { x: +geo[1], y: +geo[2], w: +geo[3], h: +geo[4] } : null;
}

test("nominal : un mxfile bien formé, un sommet par nœud, une arête par lien", () => {
  const out = drawio.build(NOMINAL);
  assert.match(out.xml, /^<\?xml version="1\.0" encoding="UTF-8"\?>\n<mxfile /);
  assert.match(out.xml, /<mxGraphModel [^>]*pageWidth="\d+"/);
  assert.match(out.xml, /<root>\s*<mxCell id="0" \/>\s*<mxCell id="1" parent="0" \/>/);
  assert.equal((out.xml.match(/vertex="1"/g) || []).length, 1 + 1 + 4, "titre + groupe + 4 nœuds");
  assert.equal((out.xml.match(/edge="1"/g) || []).length, 3);
  assert.match(out.xml, /<\/mxfile>\n$/);
  assert.ok(out.width > 0 && out.height > 0);
});

test("déterminisme : deux appels rendent exactement les mêmes octets", () => {
  const a = drawio.build(NOMINAL);
  const b = drawio.build(JSON.parse(JSON.stringify(NOMINAL)));
  assert.equal(a.xml, b.xml);
  assert.equal(a.svg, b.svg);
  assert.equal(a.width, b.width);
  assert.equal(a.height, b.height);
  // Rien d'horodaté : un `modified` rendrait le fichier non diffable.
  assert.ok(!/modified=/.test(a.xml));
});

test("échappement : un libellé hostile ne casse ni n'injecte rien", () => {
  const out = drawio.build({
    nodes: [{ id: "a", label: 'A & <b>B</b> "c" \'d\'' }, { id: "b", label: "B" }],
    edges: [{ from: "a", to: "b", label: "<script>" }],
  });
  assert.ok(out.xml.includes("A &amp; &lt;b&gt;B&lt;/b&gt; &quot;c&quot; &#39;d&#39;"));
  assert.ok(!out.xml.includes("<b>B</b>"));
  assert.ok(!out.xml.includes('value="<script>"'));
  assert.ok(out.svg.includes("&lt;script&gt;"));
  // Les seules balises restantes sont celles qu'on a écrites : le compte des « < » le dit.
  assert.equal((out.xml.match(/<mxCell /g) || []).length, 2 + 2 + 1);
});

test("groupes : le cadre englobe la géométrie de tous ses membres", () => {
  const out = drawio.build(NOMINAL);
  const box = cell(out.xml, "g-0");
  // Les nœuds du groupe sont alb (n-1), app (n-2), db (n-3).
  for (const id of ["n-1", "n-2", "n-3"]) {
    const n = cell(out.xml, id);
    assert.ok(n.x >= box.x && n.x + n.w <= box.x + box.w, `${id} déborde du cadre en x`);
    assert.ok(n.y >= box.y && n.y + n.h <= box.y + box.h, `${id} déborde du cadre en y`);
  }
  // Le nœud hors groupe, lui, est en dehors du cadre.
  const loose = cell(out.xml, "n-0");
  assert.ok(loose.y >= box.y + box.h || loose.y + loose.h <= box.y,
    "le nœud sans groupe ne doit pas tomber dans le cadre");
});

test("deux groupes : les cadres ne se chevauchent jamais", () => {
  const out = drawio.build({
    direction: "LR",
    groups: [{ id: "g1", label: "Zone 1" }, { id: "g2", label: "Zone 2" }],
    nodes: [
      { id: "a", label: "A", group: "g1" }, { id: "b", label: "B", group: "g1" },
      { id: "c", label: "C", group: "g2" }, { id: "d", label: "D", group: "g2" },
    ],
    edges: [{ from: "a", to: "b" }, { from: "c", to: "d" }, { from: "a", to: "c" }],
  });
  const one = cell(out.xml, "g-0");
  const two = cell(out.xml, "g-1");
  const overlap = one.x < two.x + two.w && two.x < one.x + one.w
    && one.y < two.y + two.h && two.y < one.y + one.h;
  assert.equal(overlap, false, "deux cadres de groupe se chevauchent");
});

test("lien pendant : refus qui NOMME l'identifiant manquant", () => {
  assert.throws(
    () => drawio.build({ nodes: [{ id: "a", label: "A" }], edges: [{ from: "a", to: "fantome" }] }),
    (e) => e instanceof drawio.DrawioRejected && e.message.includes("fantome"));
  assert.throws(
    () => drawio.build({ nodes: [{ id: "a", label: "A" }], edges: [{ from: "", to: "a" }] }),
    /Lien pendant/);
});

test("groupe non déclaré : refus nommé", () => {
  assert.throws(
    () => drawio.build({ nodes: [{ id: "a", label: "A", group: "absent" }] }),
    (e) => e.message.includes("absent") && e.message.includes("groups"));
});

test("bornes : zéro nœud, trop de nœuds, trop de liens, libellé trop long", () => {
  assert.throws(() => drawio.build({ nodes: [] }), /au moins un nœud/);
  assert.throws(() => drawio.build({}), /au moins un nœud/);
  const many = Array.from({ length: drawio.MAX_NODES + 1 }, (_, i) => ({ id: "n" + i, label: "N" }));
  assert.throws(() => drawio.build({ nodes: many }), new RegExp(`${drawio.MAX_NODES + 1} nœuds`));
  const two = [{ id: "a", label: "A" }, { id: "b", label: "B" }];
  const edges = Array.from({ length: drawio.MAX_EDGES + 1 }, () => ({ from: "a", to: "b" }));
  assert.throws(() => drawio.build({ nodes: two, edges }), /Trop de liens/);
  assert.throws(
    () => drawio.build({ nodes: [{ id: "a", label: "x".repeat(drawio.MAX_LABEL + 1) }] }),
    /Libellé trop long/);
  const groups = Array.from({ length: drawio.MAX_GROUPS + 1 }, (_, i) => ({ id: "g" + i, label: "G" }));
  assert.throws(() => drawio.build({ nodes: two, groups }), /Trop de groupes/);
});

test("doublons : deux nœuds ou deux groupes de même identifiant sont refusés", () => {
  assert.throws(() => drawio.build({ nodes: [{ id: "a" }, { id: "a" }] }), /Nœud en double/);
  assert.throws(
    () => drawio.build({ nodes: [{ id: "a" }], groups: [{ id: "g" }, { id: "g" }] }),
    /Groupe en double/);
});

test("LR et TB rendent deux géométries distinctes, toutes deux cohérentes", () => {
  const lr = drawio.build({ ...NOMINAL, direction: "LR" });
  const tb = drawio.build({ ...NOMINAL, direction: "TB" });
  assert.notEqual(lr.xml, tb.xml);
  // En LR le flux va vers la droite : le nœud de la couche 3 est plus à droite que celui de la 0.
  assert.ok(cell(lr.xml, "n-3").x > cell(lr.xml, "n-0").x);
  // En TB il descend.
  assert.ok(cell(tb.xml, "n-3").y > cell(tb.xml, "n-0").y);
  assert.ok(lr.width > lr.height);
  assert.ok(tb.height > tb.width);
});

test("le SVG porte les mêmes coordonnées que le .drawio", () => {
  const out = drawio.build(NOMINAL);
  assert.match(out.svg, new RegExp(`width="${out.width}" height="${out.height}"`));
  for (const g of geometries(out.xml)) {
    if (g.w === 180 && g.h === 60) {
      assert.ok(out.svg.includes(`x="${g.x}" y="${g.y}" width="${g.w}" height="${g.h}"`),
        `le nœud en (${g.x},${g.y}) manque dans le SVG`);
    }
  }
});

test("un cycle ne fait pas tourner le service : il rend une mise en page", () => {
  const out = drawio.build({
    nodes: [{ id: "a" }, { id: "b" }, { id: "c" }],
    edges: [{ from: "a", to: "b" }, { from: "b", to: "c" }, { from: "c", to: "a" }],
  });
  assert.equal((out.xml.match(/edge="1"/g) || []).length, 3);
  assert.ok(out.width > 0);
});

test("un nœud sans libellé prend son identifiant, et un schéma sans titre reste valide", () => {
  const out = drawio.build({ nodes: [{ id: "solo" }] });
  assert.ok(out.xml.includes('value="solo"'));
  assert.ok(!out.xml.includes('id="title"'));
  assert.equal((out.xml.match(/vertex="1"/g) || []).length, 1);
});

test("un lien pointillé sort pointillé dans les deux artefacts", () => {
  const out = drawio.build({
    nodes: [{ id: "a" }, { id: "b" }], edges: [{ from: "a", to: "b", dashed: true }],
  });
  assert.ok(out.xml.includes("dashed=1;\" edge=\"1\""));
  assert.ok(out.svg.includes('stroke-dasharray="6 4"'));
});
