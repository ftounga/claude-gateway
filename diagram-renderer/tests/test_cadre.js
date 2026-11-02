/**
 * Tests du moteur cadré (F-238, itération 0 « Le schéma 2D cadré »).
 *
 * Ils rendent les trois schémas types de l'essai du 2026-11-02 (`fixtures-cadre/`) pour de vrai : la
 * description passe par `cloud.py --prepare` (validation, icônes résolues), puis par `cadre.js`. Ils ont
 * besoin de python3 + `diagrams` (présents dans l'image) et d'elkjs (`npm ci` dans diagram-renderer/).
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
const TYPES = ["trois-tiers", "eks", "serverless"];

function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, name + ".json"), "utf8"));
}

function prepare(spec) {
  const out = execFileSync(process.env.PYTHON_BIN || "python3", [path.join(ROOT, "cloud.py"), "--prepare"],
    { input: JSON.stringify(spec), encoding: "utf8" });
  return JSON.parse(out);
}

function refusal(spec) {
  try {
    prepare(spec);
  } catch (e) {
    return String(e.stderr || e.message);
  }
  return "";
}

async function render(spec) {
  return cadre.build(prepare(spec));
}

for (const name of TYPES) {
  test(`G1 + G2 — « ${name} » : aucun segment oblique, aucune extrémité détachée`, async () => {
    const out = await render(fixture(name));
    const g1 = gardes.g1Orthogonalite(out.svg);
    assert.ok(g1.segments > 0, "des liaisons sont tracées");
    assert.deepEqual(g1.defects, []);
    assert.deepEqual(gardes.g2Extremites(out.graph, out.model.nodes).defects, []);
    assert.equal(out.graph.edges.length, fixture(name).edges.length, "chaque lien est tracé");
  });

  test(`déterminisme — « ${name} » : même entrée, même SVG octet pour octet`, async () => {
    const a = await render(fixture(name));
    const b = await render(fixture(name));
    assert.equal(a.svg, b.svg);
    assert.deepEqual(a.graph, b.graph);
  });

  test(`identifiants stables — « ${name} » : zone-<id> et node-<id> pour chaque zone et chaque nœud`, async () => {
    const spec = fixture(name);
    const out = await render(spec);
    for (const g of spec.groups) {
      assert.ok(out.svg.includes(`id="zone-${g.id}"`), "zone " + g.id);
    }
    for (const n of spec.nodes) {
      assert.ok(out.svg.includes(`id="node-${n.id}"`), "nœud " + n.id);
    }
  });
}

test("SVG auto-contenu : icônes officielles inlinées en data:, aucun chemin fichier", async () => {
  const out = await render(fixture("serverless"));
  assert.match(out.svg, /href="data:image\/png;base64,/);
  assert.doesNotMatch(out.svg, /href="\//);
  assert.doesNotMatch(out.svg, /file:\/\//);
});

test("type inconnu : signalé, forme en pointillé visible, jamais une icône approchante", async () => {
  const spec = {
    title: "Inconnu",
    nodes: [{ id: "a", type: "aws.ec2", label: "App" }, { id: "x", type: "aws.nexistepas", label: "Mystère" }],
    edges: [{ from: "a", to: "x" }],
  };
  const prepared = prepare(spec);
  assert.deepEqual(prepared.unknown, ["aws.nexistepas"]);
  assert.match(prepared.unknownSaid, /aws\.nexistepas/);
  const out = await cadre.build(prepared);
  const x = out.graph.nodes.find((n) => n.id === "x");
  assert.equal(x.unknown, true);
  assert.equal(x.resolved, null);
  const bloc = out.svg.slice(out.svg.indexOf('id="node-x"'));
  assert.match(bloc.slice(0, 400), /class="inconnu"/);
  assert.doesNotMatch(bloc.slice(0, 400), /<image/);
});

test("échappement : un libellé hostile ne s'exécute pas dans le SVG", async () => {
  const spec = {
    title: "<script>alert(1)</script>",
    groups: [{ id: "g\"><x", label: "Zone <b>" }],
    nodes: [{ id: "n1", type: "aws.ec2", label: "A & <img src=x onerror=1>", group: "g\"><x" },
      { id: "n2", type: "aws.rds", label: "B" }],
    edges: [{ from: "n1", to: "n2", label: "<i>flux</i>" }],
  };
  const out = await render(spec);
  assert.doesNotMatch(out.svg, /<script/);
  assert.doesNotMatch(out.svg, /<img/);
  assert.doesNotMatch(out.svg, /<b>|<i>/);
  assert.match(out.svg, /id="zone-g&quot;&gt;&lt;x"/);
});

test("refus : mêmes raisons que graphviz (lien pendant, borne)", () => {
  assert.match(refusal({ nodes: [{ id: "a", type: "aws.ec2" }], edges: [{ from: "a", to: "b" }] }),
    /non déclaré/);
  const trop = { nodes: Array.from({ length: 61 }, (_, i) => ({ id: "n" + i, type: "aws.ec2" })) };
  assert.match(refusal(trop), /Trop de nœuds/);
});

test("la topologie AWS canonique à 5 niveaux est acceptée (profondeur 6)", () => {
  const prepared = prepare(fixture("trois-tiers"));
  assert.equal(prepared.groups.length, 9);
});

test("graphe : coordonnées du SVG, polylignes orthogonales, familles résolues", async () => {
  const out = await render(fixture("trois-tiers"));
  const g = out.graph;
  assert.equal(g.version, 1);
  const rds = g.nodes.find((n) => n.id === "rdsa");
  assert.equal(rds.family, "database");
  assert.match(rds.resolved, /^aws\/database\//);
  for (const e of g.edges) {
    for (let i = 1; i < e.points.length; i += 1) {
      const a = e.points[i - 1];
      const b = e.points[i];
      assert.ok(a.x === b.x || a.y === b.y, `segment orthogonal ${e.id}`);
    }
  }
});

test("G1 sait voir une liaison oblique ou courbe (la garde n'est pas aveugle)", () => {
  const oblique = '<polyline class="lien" points="0,0 10,10"/>';
  assert.equal(gardes.g1Orthogonalite(oblique).defects.length, 1);
  const courbe = '<path class="lien" d="M0,0 C10,0 10,10 20,10"/>';
  assert.equal(gardes.g1Orthogonalite(courbe).defects.length, 1);
});

test("densité : la note de F-142 est reprise (dense, puis large)", () => {
  assert.match(cadre.densityNotice({ w: 4100, h: 900 }), /dense/);
  assert.match(cadre.densityNotice({ w: 3300, h: 900 }), /large/);
  assert.equal(cadre.densityNotice({ w: 1200, h: 800 }), "");
});
