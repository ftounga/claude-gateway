/**
 * F-238 / SF-238-02 — les zones AWS conformes : `kind`, inférence signalée, imbrication stricte, gardes G4/G6.
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

function refusal(spec) {
  try {
    prepare(spec);
  } catch (e) {
    return String(e.stderr || e.message);
  }
  return "";
}

/** Le bloc SVG d'une zone (du `<g id="zone-…">` à sa fermeture). */
function zoneBlock(svg, id) {
  const start = svg.indexOf(`<g id="zone-${id}"`);
  assert.ok(start >= 0, "zone " + id);
  return svg.slice(start, svg.indexOf("</g>", start));
}

const ONE = (kind, parentChain) => {
  const groups = parentChain.map(([id, k], i) => ({ id, label: id, kind: k, parent: i ? parentChain[i - 1][0] : undefined }));
  groups.push({ id: "z", label: "zone", kind, parent: parentChain.length ? parentChain[parentChain.length - 1][0] : undefined });
  return { groups, nodes: [{ id: "n", type: "aws.ec2", label: "n", group: "z" }] };
};

test("le schéma « tout » : chaque kind rendu selon §4.3 (G6) et imbriqué à 24 px au moins (G4)", async () => {
  const out = await cadre.build(prepare(fixture("tout")));
  assert.deepEqual(gardes.g6Conventions(out.svg).defects, []);
  assert.deepEqual(gardes.g4Imbrication(out.svg, out.graph).defects, []);
  const kinds = new Set(out.graph.zones.map((z) => z.kind));
  for (const k of ["cloud", "account", "region", "vpc", "az", "subnet_public", "subnet_private", "asg",
    "security_group", "onprem"]) {
    assert.ok(kinds.has(k), "kind rendu : " + k);
  }
});

test("chaque kind : bordure, trait, fond, icône de groupe et libellé en haut à gauche", async () => {
  const out = await cadre.build(prepare(fixture("tout")));
  const expect = {
    cloud: ["#232F3E", null, "none", true], account: ["#E7157B", null, "none", true],
    paris: ["#00A4A6", "6 4", "none", true], vpc: ["#8C4FFF", null, "none", true],
    aza: ["#00A4A6", "6 4", "none", false], puba: ["#7AA116", null, "#F2F6E8", true],
    priva: ["#00A4A6", null, "#E6F6F7", true], asga: ["#ED7100", "6 4", "none", true],
    sg: ["#DD344C", null, "none", false], dc: ["#7D8998", null, "none", true],
  };
  const ids = { cloud: "cloud", account: "compte" };
  for (const [key, [stroke, dash, fill, icon]] of Object.entries(expect)) {
    const block = zoneBlock(out.svg, ids[key] || key);
    assert.match(block, new RegExp(`class="zone-cadre"[^>]*fill="${fill}"[^>]*stroke="${stroke}"`), key);
    assert.equal(/stroke-dasharray="6 4"/.test(block), dash !== null, key + " trait");
    assert.equal(/class="zone-icone"/.test(block), icon, key + " icône");
    assert.match(block, new RegExp(`class="zone-libelle"[^>]*fill="${stroke}"`), key + " libellé");
    const zone = out.graph.zones.find((z) => z.id === (ids[key] || key));
    const label = block.match(/class="zone-libelle" x="(\d+)" y="(\d+)"/);
    assert.ok(Number(label[1]) - zone.box.x <= 48 && Number(label[2]) - zone.box.y <= 24, key + " en haut à gauche");
  }
});

test("Cloud › Région › VPC › AZ › sous-réseau › ASG (profondeur 6) : accepté", () => {
  const spec = ONE("asg", [["c", "cloud"], ["r", "region"], ["v", "vpc"], ["a", "az"], ["s", "subnet_private"]]);
  assert.equal(prepare(spec).groups.length, 6);
});

test("une AZ hors VPC est refusée, avec la raison", () => {
  const msg = refusal(ONE("az", [["c", "cloud"], ["r", "region"]]));
  assert.match(msg, /mal placée/);
  assert.match(msg, /une Availability Zone se place dans un VPC/);
});

test("refus : VPC à la racine, security group hors VPC, kind inconnu", () => {
  assert.match(refusal(ONE("vpc", [])), /un VPC se place dans une Région/);
  assert.match(refusal(ONE("security_group", [["c", "cloud"], ["r", "region"]])), /security group/);
  assert.match(refusal(ONE("datacenter", [])), /Type de zone inconnu/);
});

test("inférence par libellé : kind supposé, signalé à l'agent, jamais imposé à l'imbrication", () => {
  const prepared = prepare({
    groups: [{ id: "a", label: "Availability Zone A" }, { id: "p", label: "Sous-réseau privé" },
      { id: "x", label: "Cluster EKS" }],
    nodes: [{ id: "n1", type: "aws.ec2", group: "a" }, { id: "n2", type: "aws.rds", group: "p" },
      { id: "n3", type: "aws.eks", group: "x" }],
  });
  const kinds = Object.fromEntries(prepared.groups.map((g) => [g.id, [g.kind, g.inferred]]));
  assert.deepEqual(kinds, { a: ["az", true], p: ["subnet_private", true], x: ["generic", true] });
  assert.equal(prepared.notices.length, 1);
  assert.match(prepared.notices[0], /NOTE DU RENDU/);
  assert.match(prepared.notices[0], /Availability Zone A/);
  assert.doesNotMatch(prepared.notices[0], /Cluster EKS/);
});

test("inférence : accents, anglais et sigles", () => {
  const label = (l) => prepare({ groups: [{ id: "g", label: l }], nodes: [{ id: "n", type: "aws.ec2", group: "g" }] })
    .groups[0].kind;
  assert.equal(label("Region eu-west-1"), "region");
  assert.equal(label("Public subnet 1"), "subnet_public");
  assert.equal(label("Private subnet"), "subnet_private");
  assert.equal(label("AZ b"), "az");
  assert.equal(label("Zone de disponibilité 2"), "az");
  assert.equal(label("Auto Scaling group web"), "asg");
  assert.equal(label("VPC prod"), "vpc");
  assert.equal(label("Public"), "generic");
});

test("une description d'avant F-238 (cadres à plat, sans kind) donne toujours un schéma valide (G7)", async () => {
  const spec = {
    groups: [{ id: "pub", label: "Sous-réseau public" }, { id: "vpc", label: "VPC" }],
    nodes: [{ id: "a", type: "aws.alb", group: "pub" }, { id: "b", type: "aws.ec2", group: "vpc" }],
    edges: [{ from: "a", to: "b" }],
  };
  const out = await cadre.build(prepare(spec));
  assert.deepEqual(gardes.g6Conventions(out.svg).defects, []);
  assert.deepEqual(gardes.g1Orthogonalite(out.svg).defects, []);
});

test("G6 et G4 savent voir un défaut (les gardes ne sont pas aveugles)", () => {
  const faux = '<g id="zone-v" class="zone" data-kind="vpc"><rect class="zone-cadre" x="0" y="0" width="9" '
    + 'height="9" fill="none" stroke="#000000" stroke-width="1.5"/><text class="zone-libelle" fill="#000000">v</text></g>';
  assert.ok(gardes.g6Conventions(faux).defects.length >= 2);
  const graph = {
    zones: [{ id: "p", box: { x: 0, y: 0, w: 100, h: 100 } }, { id: "c", parent: "p", box: { x: 10, y: 30, w: 50, h: 50 } }],
    nodes: [],
  };
  assert.equal(gardes.g4Imbrication("", graph).defects.length, 1);
});
