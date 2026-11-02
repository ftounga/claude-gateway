/**
 * F-238 / SF-238-04 — routage inter-zones et étiquettes : garde G3 (aucune étiquette sur une icône, un libellé, un
 * bandeau ou une autre étiquette ; aucune liaison sous une icône ; aucune liaison qui longe une bordure sur plus de
 * 16 px), faisceaux, bidirectionnel, et performance (60 nœuds / 120 liens en 2 s au plus).
 *
 *   cd diagram-renderer && npm ci && npm test   (en série : le test de performance mesure un temps)
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

/** Un grand schéma DÉTERMINISTE aux bornes de F-142 : 60 nœuds, 120 liens, 3 AZ, 24 zones. */
function grand() {
  let seed = 238;
  const rnd = (n) => {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    return seed % n;
  };
  const groups = [{ id: "cloud", label: "AWS Cloud", kind: "cloud" },
    { id: "region", label: "Région eu-west-3", kind: "region", parent: "cloud" },
    { id: "vpc", label: "VPC", kind: "vpc", parent: "region" }];
  const leaves = [];
  for (const az of ["a", "b", "c"]) {
    groups.push({ id: "az" + az, label: "Availability Zone " + az.toUpperCase(), kind: "az", parent: "vpc" });
    for (const [kind, name] of [["subnet_public", "public"], ["subnet_private", "privé"]]) {
      groups.push({ id: kind + az, label: `Sous-réseau ${name} ${az.toUpperCase()}`, kind, parent: "az" + az });
      leaves.push(kind + az);
    }
  }
  while (groups.length < 24) {
    const id = "svc" + groups.length;
    groups.push({ id, label: "Service " + groups.length, kind: "generic", parent: "region" });
    leaves.push(id);
  }
  const types = ["aws.ec2", "aws.rds", "aws.lambda", "aws.s3", "aws.sqs", "aws.alb", "aws.elasticache", "aws.ecs"];
  const nodes = Array.from({ length: 60 }, (_, i) => ({
    id: "n" + i, type: types[i % types.length], label: "Composant " + i, group: leaves[i % leaves.length],
  }));
  const edges = [];
  const seen = new Set();
  while (edges.length < 120) {
    const a = rnd(60);
    const b = rnd(60);
    if (a === b || seen.has(a + ">" + b)) {
      continue;
    }
    seen.add(a + ">" + b);
    edges.push({ from: "n" + a, to: "n" + b, label: edges.length % 5 === 0 ? "flux " + edges.length : "" });
  }
  return { title: "Grand schéma", direction: "TB", groups, nodes, edges };
}

for (const name of ["trois-tiers", "eks", "serverless", "tout"]) {
  test(`G3 — « ${name} » : 0 collision d'étiquette, aucune liaison sous une icône ni le long d'une bordure`, async () => {
    const out = await cadre.build(prepare(fixture(name)));
    assert.deepEqual(gardes.g3Collisions(out.svg, out.graph, out.model).defects, []);
    for (const e of out.graph.edges) {
      if (e.label) {
        assert.ok(e.labelBox, "chaque étiquette est posée : " + e.id);
      }
    }
  });
}

test("3-tiers TB aussi : 0 collision (G3)", async () => {
  const out = await cadre.build(prepare({ ...fixture("trois-tiers"), direction: "TB" }));
  assert.deepEqual(gardes.g3Collisions(out.svg, out.graph, out.model).defects, []);
});

test("étiquette posée SUR sa liaison (sur un de ses segments, ou juste à côté)", async () => {
  const out = await cadre.build(prepare(fixture("serverless")));
  for (const e of out.graph.edges.filter((x) => x.label)) {
    const b = e.labelBox;
    const near = e.points.slice(1).some((p, i) => {
      const a = e.points[i];
      const box = { x: Math.min(a.x, p.x) - 24, y: Math.min(a.y, p.y) - 24,
        w: Math.abs(a.x - p.x) + 48, h: Math.abs(a.y - p.y) + 48 };
      return b.x < box.x + box.w && b.x + b.w > box.x && b.y < box.y + box.h && b.y + b.h > box.y;
    });
    assert.ok(near, "étiquette de " + e.id + " près de sa liaison");
  }
});

test("faisceau : deux liaisons parallèles entre les mêmes nœuds ne se superposent jamais", async () => {
  const spec = {
    direction: "LR",
    nodes: [{ id: "a", type: "aws.ec2", label: "A" }, { id: "b", type: "aws.rds", label: "B" }],
    edges: [{ from: "a", to: "b", label: "lecture" }, { from: "a", to: "b", label: "écriture" },
      { from: "b", to: "a", label: "réplique" }],
  };
  const out = await cadre.build(prepare(spec));
  const occupied = new Map();
  for (const e of out.graph.edges) {
    for (let k = 1; k < e.points.length; k += 1) {
      const a = e.points[k - 1];
      const b = e.points[k];
      const steps = Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y)) / 8;
      for (let s = 0; s < steps; s += 1) {
        const x = a.x + Math.sign(b.x - a.x) * 8 * s;
        const y = a.y + Math.sign(b.y - a.y) * 8 * s;
        const key = `${x},${y},${a.x === b.x ? "v" : "h"}`;
        assert.ok(!occupied.has(key) || occupied.get(key) === e.id, `superposition en ${key}`);
        occupied.set(key, e.id);
      }
    }
  }
  assert.deepEqual(gardes.g3Collisions(out.svg, out.graph, out.model).defects, []);
});

test("bidirectionnel : deux pointes sur UNE liaison", async () => {
  const spec = {
    nodes: [{ id: "a", type: "onprem.server", label: "ERP" }, { id: "b", type: "aws.alb", label: "ALB" }],
    edges: [{ from: "a", to: "b", label: "VPN", bidirectional: true }],
  };
  const out = await cadre.build(prepare(spec));
  assert.equal((out.svg.match(/class="lien"/g) || []).length, 1);
  assert.match(out.svg, /marker-start="url\(#cg-fleche\)"[^>]*marker-end="url\(#cg-fleche\)"/);
  assert.equal(out.graph.edges[0].bidirectional, true);
});

test("performance : 60 nœuds / 120 liens rendus en 2 s au plus, orthogonaux et attachés", async () => {
  const prepared = prepare(grand());
  // Service en régime : le premier appel d'ELK compile son code (≈ 0,7 s, une fois par processus).
  await cadre.build(prepare(fixture("serverless")));
  const t0 = Date.now();
  const out = await cadre.build(prepared);
  const ms = Date.now() - t0;
  assert.ok(ms <= 2000, `${ms} ms`);
  assert.equal(out.graph.edges.length, 120);
  assert.deepEqual(gardes.g1Orthogonalite(out.svg).defects, []);
  assert.deepEqual(gardes.g2Extremites(out.graph, out.model.nodes).defects, []);
  const under = gardes.g3Collisions(out.svg, out.graph, out.model).defects.filter((d) => /sous l'icône/.test(d));
  assert.deepEqual(under, [], "aucune liaison sous une icône, même dense");
});

test("G3 sait voir une étiquette sur une icône, une liaison sous une icône, une bordure longée", () => {
  const node = { id: "n", type: "aws.ec2", lines: ["n"], box: { x: 0, y: 0, w: 128, h: 112 } };
  const graph = {
    zones: [{ id: "z", kind: "generic", label: "z", box: { x: 200, y: 200, w: 300, h: 300 } }],
    nodes: [{ id: "n", box: node.box }],
    edges: [
      { id: "e0", points: [{ x: 0, y: 30 }, { x: 150, y: 30 }], labelBox: { x: 40, y: 10, w: 30, h: 16 } },
      { id: "e1", points: [{ x: 210, y: 196 }, { x: 400, y: 196 }], labelBox: null },
    ],
  };
  const defects = gardes.g3Collisions("", graph, { nodes: [node] }).defects;
  assert.ok(defects.some((d) => /sur l'icône/.test(d)));
  assert.ok(defects.some((d) => /sous l'icône/.test(d)));
  assert.ok(defects.some((d) => /longe la bordure/.test(d)));
});
