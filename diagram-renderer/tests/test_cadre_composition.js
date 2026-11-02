/**
 * F-238 / SF-238-03 — la composition des zones : AZ parallèles et de taille égale, gabarits identiques ⇒ même
 * mise en page, public avant privé, bande régionale, acteurs externes à l'entrée, grille de 8 px (G5).
 *
 *   cd diagram-renderer && npm ci && node --test tests/*.js
 */

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const cadre = require("../cadre.js");
const gardes = require("../gardes.js");

const ROOT = path.join(__dirname, "..");
const FIXTURES = path.join(__dirname, "fixtures-cadre");

function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, name + ".json"), "utf8"));
}

function prepare(spec) {
  return JSON.parse(execFileSync(process.env.PYTHON_BIN || "python3", [path.join(ROOT, "cloud.py"), "--prepare"],
    { input: JSON.stringify(spec), encoding: "utf8" }));
}

async function render(spec) {
  return cadre.build(prepare(spec));
}

const byId = (list, id) => list.find((x) => x.id === id);
const right = (b) => b.x + b.w;
const bottom = (b) => b.y + b.h;

/** Le 3-tiers décrit de haut en bas (la forme conseillée pour du multi-AZ). */
function troisTiersTB() {
  return { ...fixture("trois-tiers"), direction: "TB" };
}

for (const name of ["trois-tiers", "eks", "serverless", "tout"]) {
  test(`G1, G2, G4, G5, G6 — « ${name} »`, async () => {
    const out = await render(fixture(name));
    assert.deepEqual(gardes.g1Orthogonalite(out.svg).defects, []);
    assert.deepEqual(gardes.g2Extremites(out.graph, out.model.nodes).defects, []);
    assert.deepEqual(gardes.g4Imbrication(out.svg, out.graph).defects, []);
    assert.deepEqual(gardes.g5Grille(out.svg, out.graph).defects, []);
    assert.deepEqual(gardes.g6Conventions(out.svg).defects, []);
    assert.equal(out.graph.notices.filter((n) => /sans contournement/.test(n)).length, 0, "tout est routé");
  });
}

test("3-tiers (TB) : AZ A et B CÔTE À CÔTE, de taille égale, homologues sur la même ligne", async () => {
  const g = (await render(troisTiersTB())).graph;
  const a = byId(g.zones, "aza").box;
  const b = byId(g.zones, "azb").box;
  assert.equal(a.y, b.y, "même ligne");
  assert.ok(right(a) <= b.x || right(b) <= a.x, "côte à côte, sans recouvrement");
  assert.equal(a.w, b.w);
  assert.equal(a.h, b.h);
  for (const [x, y] of [["nata", "natb"], ["ec2a", "ec2b"], ["rdsa", "rdsb"]]) {
    assert.equal(byId(g.nodes, x).box.y, byId(g.nodes, y).box.y, `${x} et ${y} sur la même ligne`);
  }
});

test("3-tiers (LR) : AZ en bandes parallèles (jamais l'une après l'autre dans le sens du flux)", async () => {
  const g = (await render(fixture("trois-tiers"))).graph;
  const a = byId(g.zones, "aza").box;
  const b = byId(g.zones, "azb").box;
  assert.equal(a.x, b.x, "même colonne");
  assert.ok(bottom(a) <= b.y || bottom(b) <= a.y);
  assert.equal(byId(g.nodes, "ec2a").box.x, byId(g.nodes, "ec2b").box.x);
});

test("dans une AZ : sous-réseau public AVANT privé, dans le sens du flux", async () => {
  const tb = (await render(troisTiersTB())).graph;
  assert.ok(bottom(byId(tb.zones, "puba").box) <= byId(tb.zones, "priva").box.y);
  const lr = (await render(fixture("trois-tiers"))).graph;
  assert.ok(right(byId(lr.zones, "puba").box) <= byId(lr.zones, "priva").box.x);
});

test("bande régionale : S3 le long du VPC, hors du VPC, dans la Région", async () => {
  const g = (await render(troisTiersTB())).graph;
  const vpc = byId(g.zones, "vpc").box;
  const s3 = byId(g.nodes, "s3").box;
  const region = byId(g.zones, "region").box;
  assert.ok(s3.x >= right(vpc), "à côté du VPC (TB : à droite)");
  assert.ok(s3.y < bottom(vpc) && bottom(s3) > vpc.y, "le long du VPC");
  assert.ok(s3.x >= region.x && right(s3) <= right(region));
});

test("acteurs externes hors d'AWS Cloud, à l'entrée du flux", async () => {
  const g = (await render(troisTiersTB())).graph;
  const users = byId(g.nodes, "users").box;
  const cloud = byId(g.zones, "cloud").box;
  assert.ok(bottom(users) <= cloud.y, "au-dessus du cloud quand le flux descend");
  const lr = (await render(fixture("trois-tiers"))).graph;
  assert.ok(right(byId(lr.nodes, "users").box) <= byId(lr.zones, "cloud").box.x, "à gauche quand il va à droite");
});

test("sens par défaut (§19 Q2) : multi-AZ sans « direction » ⇒ de haut en bas ; sinon de gauche à droite", async () => {
  const spec = fixture("trois-tiers");
  delete spec.direction;
  assert.equal((await render(spec)).graph.direction, "TB");
  const sl = fixture("serverless");
  delete sl.direction;
  assert.equal((await render(sl)).graph.direction, "LR");
});

test("symétrie qui ne ment pas : deux AZ de gabarits différents ne sont pas forcées à se ressembler", async () => {
  const spec = troisTiersTB();
  spec.nodes.push({ id: "cache", type: "aws.elasticache", label: "Cache", group: "privb" });
  const g = (await render(spec)).graph;
  assert.equal(g.nodes.length, spec.nodes.length, "aucun composant ajouté");
  assert.equal(byId(g.nodes, "cache").zone, "privb", "aucun composant déplacé hors de sa zone");
  const a = byId(g.zones, "aza").box;
  const b = byId(g.zones, "azb").box;
  assert.equal(a.w, b.w, "toujours de taille égale");
  assert.equal(a.h, b.h);
});

test("trois AZ jumelles : trois colonnes alignées", async () => {
  const g = (await render(fixture("tout"))).graph;
  const [a, b, c] = ["aza", "azb", "azc"].map((id) => byId(g.zones, id).box);
  assert.equal(a.y, b.y);
  assert.equal(b.y, c.y);
  assert.ok(right(a) <= b.x && right(b) <= c.x);
  assert.equal(byId(g.nodes, "ec2a").box.y, byId(g.nodes, "ec2c").box.y);
});

test("G5 sait voir un nœud hors grille et des homologues désalignés", () => {
  const graph = {
    zones: [{ id: "v", kind: "vpc", box: { x: 0, y: 0, w: 900, h: 900 } },
      { id: "a", kind: "az", parent: "v", box: { x: 0, y: 0, w: 400, h: 400 } },
      { id: "b", kind: "az", parent: "v", box: { x: 450, y: 0, w: 400, h: 400 } }],
    nodes: [{ id: "n1", type: "aws.ec2", zone: "a", box: { x: 40, y: 40, w: 128, h: 112 } },
      { id: "n2", type: "aws.ec2", zone: "b", box: { x: 490, y: 56, w: 128, h: 112 } },
      { id: "n3", type: "aws.rds", zone: "", box: { x: 3, y: 0, w: 128, h: 112 } }],
  };
  const defects = gardes.g5Grille("", graph).defects;
  assert.ok(defects.some((d) => /hors grille/.test(d)));
  assert.ok(defects.some((d) => /homologues/.test(d)));
});

test("le repli ELK du graphe entier (cadrage §22) reste disponible et orthogonal", async () => {
  const out = await cadre.build(prepare(fixture("eks")), { mode: "elk" });
  assert.deepEqual(gardes.g1Orthogonalite(out.svg).defects, []);
});
