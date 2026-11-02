/**
 * F-238 / SF-238-05 — le `.drawio` cadré et l'export du graphe d'architecture.
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
const drawioCadre = require("../drawio-cadre.js");
const gardes = require("../gardes.js");

const ROOT = path.join(__dirname, "..");
const FIXTURES = path.join(__dirname, "fixtures-cadre");
const SCHEMA = JSON.parse(fs.readFileSync(path.join(ROOT, "schemas", "architecture-graph-v1.schema.json"), "utf8"));

function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, name + ".json"), "utf8"));
}

function prepare(spec) {
  return JSON.parse(execFileSync(process.env.PYTHON_BIN || "python3", [path.join(ROOT, "cloud.py"), "--prepare"],
    { input: JSON.stringify(spec), encoding: "utf8" }));
}

/** Un validateur JSON Schema minimal (type, enum, required, properties, items, min/max, $ref local). */
function validate(value, schema, where = "$", errors = []) {
  if (schema.$ref) {
    return validate(value, SCHEMA.$defs[schema.$ref.split("/").pop()], where, errors);
  }
  const types = [].concat(schema.type || []);
  const typeOf = (v) => (v === null ? "null" : Array.isArray(v) ? "array"
    : Number.isInteger(v) ? "integer" : typeof v);
  if (types.length && !types.some((t) => t === typeOf(value) || (t === "number" && typeOf(value) === "integer"))) {
    errors.push(`${where} : type ${typeOf(value)} au lieu de ${types.join("|")}`);
    return errors;
  }
  if (schema.enum && !schema.enum.includes(value)) {
    errors.push(`${where} : ${JSON.stringify(value)} hors de ${schema.enum.join(",")}`);
  }
  if (schema.minimum !== undefined && value < schema.minimum) {
    errors.push(`${where} : ${value} < ${schema.minimum}`);
  }
  if (typeOf(value) === "object") {
    for (const key of schema.required || []) {
      if (!(key in value)) {
        errors.push(`${where}.${key} manquant`);
      }
    }
    for (const [key, sub] of Object.entries(schema.properties || {})) {
      if (key in value) {
        validate(value[key], sub, `${where}.${key}`, errors);
      }
    }
  }
  if (typeOf(value) === "array") {
    if (schema.maxItems !== undefined && value.length > schema.maxItems) {
      errors.push(`${where} : ${value.length} éléments > ${schema.maxItems}`);
    }
    if (schema.minItems !== undefined && value.length < schema.minItems) {
      errors.push(`${where} : ${value.length} éléments < ${schema.minItems}`);
    }
    value.forEach((v, i) => schema.items && validate(v, schema.items, `${where}[${i}]`, errors));
  }
  return errors;
}

/** Les cellules du XML : id, parent, géométrie, style, source/cible, points. */
function cells(xml) {
  const out = new Map();
  for (const m of xml.matchAll(/<mxCell id="([^"]+)"([^>]*?)(\/>|>([\s\S]*?)<\/mxCell>)/g)) {
    const attrs = m[2];
    const body = m[4] || "";
    const attr = (name) => (attrs.match(new RegExp(`\\s${name}="([^"]*)"`)) || [])[1];
    const geo = body.match(/<mxGeometry(?: x="([-\d.]+)" y="([-\d.]+)" width="([\d.]+)" height="([\d.]+)")?/);
    out.set(m[1], {
      parent: attr("parent"),
      style: attr("style") || "",
      source: attr("source"),
      target: attr("target"),
      geo: geo && geo[1] !== undefined ? { x: +geo[1], y: +geo[2], w: +geo[3], h: +geo[4] } : null,
      points: [...body.matchAll(/<mxPoint x="([-\d.]+)" y="([-\d.]+)"/g)].map((p) => ({ x: +p[1], y: +p[2] })),
    });
  }
  return out;
}

/** La position ABSOLUE d'une cellule (sa géométrie est relative à son conteneur). */
function absolute(all, id) {
  const c = all.get(id);
  let { x, y } = c.geo;
  let parent = c.parent;
  while (parent && parent !== "1" && parent !== "0") {
    const p = all.get(parent);
    x += p.geo.x;
    y += p.geo.y;
    parent = p.parent;
  }
  return { x, y, w: c.geo.w, h: c.geo.h };
}

async function both(name) {
  const built = await cadre.build(cadre.neutralizeUntyped(prepare(fixture(name))));
  return { built, xml: drawioCadre.fromGraph(built.graph, built.model) };
}

for (const name of ["trois-tiers", "eks", "serverless", "tout"]) {
  test(`graphe « ${name} » : valide contre son schéma (ArchitectureGraph v1)`, async () => {
    const { built } = await both(name);
    assert.deepEqual(validate(built.graph, SCHEMA), []);
  });

  test(`.drawio « ${name} » : mêmes positions que le SVG, cadres imbriqués, liaisons orthogonales`, async () => {
    const { built, xml } = await both(name);
    const all = cells(xml);
    const g = built.graph;
    g.zones.forEach((z, i) => {
      const c = all.get("z-" + i);
      assert.ok(c, "zone " + z.id);
      assert.deepEqual(absolute(all, "z-" + i), z.box, "position de la zone " + z.id);
      const parentIndex = g.zones.findIndex((p) => p.id === z.parent);
      assert.equal(c.parent, parentIndex >= 0 ? "z-" + parentIndex : "1", "imbrication de " + z.id);
      assert.match(c.style, /container=1/);
    });
    g.nodes.forEach((n, i) => {
      const r = drawioCadre.nodeRect(built.model.nodes[i]);
      const a = absolute(all, "n-" + i);
      assert.deepEqual([a.x, a.y, a.w, a.h].map(Math.round), [r.x, r.y, r.w, r.h].map(Math.round), "nœud " + n.id);
    });
    g.edges.forEach((e, i) => {
      const c = all.get("e-" + i);
      assert.match(c.style, /edgeStyle=orthogonalEdgeStyle/);
      assert.equal(c.source, "n-" + g.nodes.findIndex((n) => n.id === e.from));
      assert.deepEqual(c.points, e.points.slice(1, -1), "coudes de " + e.id);
      // Extrémités : le port calculé, exprimé relativement au nœud (exitX/exitY).
      const r = drawioCadre.nodeRect(built.model.nodes.find((n) => n.id === e.from));
      const exitX = Number(c.style.match(/exitX=([-\d.]+)/)[1]);
      const exitY = Number(c.style.match(/exitY=([-\d.]+)/)[1]);
      assert.ok(Math.abs(r.x + exitX * r.w - e.points[0].x) < 0.5 && Math.abs(r.y + exitY * r.h - e.points[0].y) < 0.5, "port de " + e.id);
    });
  });
}

test("polylignes du graphe = polylignes du SVG", async () => {
  const { built } = await both("trois-tiers");
  for (const e of built.graph.edges) {
    const m = built.svg.match(new RegExp(`id="lien-${e.id}"[^>]*points="([^"]+)"`));
    assert.ok(m, e.id);
    assert.deepEqual(gardes.pointsOf(m[1]), e.points);
  }
});

test("conventions AWS dans le .drawio : la même table que le SVG (bordure, pointillé, fond)", async () => {
  const { built, xml } = await both("tout");
  const all = cells(xml);
  built.graph.zones.forEach((z, i) => {
    const s = cadre.ZONE_STYLES[z.kind];
    const style = all.get("z-" + i).style;
    assert.match(style, new RegExp(`strokeColor=${s.stroke};`));
    assert.match(style, new RegExp(`fillColor=${s.fill};`));
    assert.equal(/dashed=1/.test(style), Boolean(s.dash), z.kind);
  });
});

test("drawio : un nœud sans type est une boîte nommée, ni signalée ni pointillée", async () => {
  const prepared = cadre.neutralizeUntyped(prepare({
    groups: [{ id: "vpc", label: "VPC" }],
    nodes: [{ id: "a", label: "Application", group: "vpc" }, { id: "b", type: "aws.rds", label: "Base", group: "vpc" }],
    edges: [{ from: "a", to: "b", label: "SQL" }],
  }));
  assert.deepEqual(prepared.unknown, []);
  assert.equal(prepared.unknownSaid, "");
  const built = await cadre.build(prepared);
  assert.match(built.svg, /class="neutre"/);
  assert.doesNotMatch(built.svg, /class="inconnu"/);
  const xml = drawioCadre.fromGraph(built.graph, built.model);
  assert.match(xml, /value="Application" style="rounded=1;[^"]*strokeColor=#232F3E/);
  assert.match(xml, /value="Base" style="shape=label;[^"]*image=data:image\/png,/);
});

test("drawio : libellés échappés, aucun identifiant du modèle dans les id de cellules", async () => {
  const built = await cadre.build(cadre.neutralizeUntyped(prepare({
    title: "A & <B>",
    groups: [{ id: "g\"x", label: "<zone>" }],
    nodes: [{ id: "n\"1", type: "aws.ec2", label: "<img src=x>", group: "g\"x" }, { id: "n2", type: "aws.s3" }],
    edges: [{ from: "n\"1", to: "n2", label: "\"flux\"" }],
  })));
  const xml = drawioCadre.fromGraph(built.graph, built.model);
  assert.doesNotMatch(xml, /<img|<zone>|<B>/);
  assert.match(xml, /value="&lt;img src=x&gt;"/);
  assert.match(xml, /id="n-0"/);
});

test("déterminisme du .drawio : même entrée, même octet", async () => {
  const a = await both("eks");
  const b = await both("eks");
  assert.equal(a.xml, b.xml);
});

test("image : chaque module local requis par server.js est copié par le Dockerfile", () => {
  const server = fs.readFileSync(path.join(ROOT, "server.js"), "utf8");
  const docker = fs.readFileSync(path.join(ROOT, "Dockerfile"), "utf8");
  const copy = docker.split("\n").find((l) => l.startsWith("COPY server.js"));
  const required = new Set();
  const visit = (file) => {
    const text = fs.readFileSync(path.join(ROOT, file), "utf8");
    for (const m of text.matchAll(/require\("\.\/([\w.-]+)"\)/g)) {
      if (!required.has(m[1])) {
        required.add(m[1]);
        visit(m[1]);
      }
    }
  };
  void server;
  visit("server.js");
  for (const file of required) {
    assert.ok(copy.split(/\s+/).includes(file), `${file} absent du COPY du Dockerfile`);
  }
});
