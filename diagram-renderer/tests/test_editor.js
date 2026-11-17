/**
 * Tests de la preuve fichier (F-268 / SF-268-01) — la partie PURE de `editor.js`.
 *
 *   node --test diagram-renderer/tests/test_editor.js
 *
 * La photographie (chromium) est la même que celle du terminal ; elle est regardée sur l'image construite.
 */

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const e = require("../editor.js");

function kinds(line, lang, state) {
  return e.tokenize(line, e.LANGS[lang], state || { inBlock: false })
    .filter((t) => t.kind).map((t) => t.kind + ":" + t.text.trim());
}

test("langage par extension ou par nom ; texte brut à défaut", () => {
  assert.equal(e.languageOf("infra/prod/main.tf"), "hcl");
  assert.equal(e.languageOf("vars.tfvars"), "hcl");
  assert.equal(e.languageOf("k8s/deploy.yml"), "yaml");
  assert.equal(e.languageOf("src/App.java"), "java");
  assert.equal(e.languageOf("C:\\proj\\Dockerfile"), "docker");
  assert.equal(e.languageOf(".env.local"), "ini");
  assert.equal(e.languageOf("LISEZMOI"), "plain");
  assert.equal(e.languageOf("notes.weird"), "plain");
});

test("HCL : bloc, chaînes, attribut avant =, commentaires # et //, constantes", () => {
  assert.deepEqual(kinds("resource \"aws_s3_bucket\" \"logs\" {", "hcl"),
    ["keyword:resource", "string:\"aws_s3_bucket\"", "string:\"logs\"", "punct:{"]);
  assert.deepEqual(kinds("  acl = \"public-read\" # ouvert", "hcl"),
    ["attr:acl", "punct:=", "string:\"public-read\"", "comment:# ouvert"]);
  assert.deepEqual(kinds("  force_destroy = true // à revoir", "hcl"),
    ["attr:force_destroy", "punct:=", "constant:true", "comment:// à revoir"]);
  assert.ok(kinds("  count = 2", "hcl").includes("number:2"));
});

test("commentaire de bloc sur plusieurs lignes : l'état passe d'une ligne à l'autre", () => {
  const state = { inBlock: false };
  assert.deepEqual(kinds("x = 1 /* début", "hcl", state), ["attr:x", "punct:=", "number:1", "comment:/* début"]);
  assert.equal(state.inBlock, true);
  assert.deepEqual(kinds("milieu", "hcl", state), ["comment:milieu"]);
  assert.deepEqual(kinds("fin */ y = false", "hcl", state), ["comment:fin */", "attr:y", "punct:=", "constant:false"]);
  assert.equal(state.inBlock, false);
});

test("YAML : clés en attribut (y compris sous un tiret), valeurs, commentaire", () => {
  assert.deepEqual(kinds("image-pull: always # défaut", "yaml"), ["attr:image-pull", "punct::", "comment:# défaut"]);
  assert.ok(kinds("  - name: api", "yaml").includes("attr:name"));
  assert.ok(kinds("enabled: true", "yaml").includes("constant:true"));
});

test("JSON : clé en attribut, valeur chaîne en chaîne", () => {
  assert.deepEqual(kinds("  \"region\": \"eu-west-3\",", "json"),
    ["attr:\"region\"", "punct::", "string:\"eu-west-3\"", "punct:,"]);
});

test("Java : mots-clés, types, chaînes ; un tiret n'est pas mangé dans un mot", () => {
  const k = kinds("public static final String NAME = \"x\";", "java");
  assert.ok(k.includes("keyword:public") && k.includes("type:String") && k.includes("string:\"x\""));
  assert.ok(kinds("int y = a-b;", "java").includes("punct:-"));
});

test("parseFiles : bornes, valeurs par défaut, surlignage hors fenêtre ignoré", () => {
  const [f] = e.parseFiles({ files: [{ path: " main.tf ", firstLine: 10, lines: ["a", "\tb", "x".repeat(900)],
    highlights: [11, 11, 2, 99, "12"], note: "  Bucket public  ", totalLines: 40 }] });
  assert.equal(f.path, "main.tf");
  assert.equal(f.firstLine, 10);
  assert.equal(f.lines[1], "  b");
  assert.equal(f.lines[2].length, e.MAX_LINE_CHARS);
  assert.ok(f.lines[2].endsWith("…"));
  assert.deepEqual(f.highlights, [11, 12]);
  assert.equal(f.note, "Bucket public");
  assert.equal(f.totalLines, 40);
  const [g] = e.parseFiles({ files: [{ path: "a", lines: Array(200).fill("l"), firstLine: "x" }] });
  assert.equal(g.firstLine, 1);
  assert.equal(g.lines.length, e.MAX_LINES);
});

test("parseFiles : demandes mal formées refusées en 400", () => {
  for (const bad of [null, {}, { files: [] }, { files: Array(7).fill({ path: "a", lines: [] }) },
    { files: [{ path: "", lines: [] }] }, { files: [{ path: "a" }] }]) {
    assert.throws(() => e.parseFiles(bad), (err) => err instanceof e.EditorError && err.status === 400);
  }
});

test("HTML : une fenêtre par fichier, fond navy, numéros, lignes surlignées, note sous la dernière", () => {
  const files = e.parseFiles({ files: [
    { path: "infra/main.tf", firstLine: 5, lines: ["a = 1", "b = 2", "c = 3"], highlights: [6], note: "ici", totalLines: 30 },
    { path: "values.yaml", lines: ["k: v"] },
  ] });
  const { html, windows } = e.renderHtml(files);
  assert.equal(windows, 2);
  assert.equal((html.match(/data-shot=/g) || []).length, 2);
  assert.ok(html.includes("#0B1020"));
  assert.ok(html.includes("<span class=\"n\">5</span>") && html.includes("<span class=\"n\">7</span>"));
  assert.equal((html.match(/class="l hl"/g) || []).length, 1);
  assert.ok(/class="l hl"><span class="n">6<\/span>[\s\S]*?<\/div><div class="note">ici<\/div>/.test(html));
  assert.equal((html.match(/class="note"/g) || []).length, 1);
  // Coupures visibles en tête (commence à 5) et en pied (30 lignes au total).
  assert.equal((html.match(/class="l gap"/g) || []).length, 2);
  assert.ok(html.includes("infra › main.tf"));
  // Rien ne dit que l'image est générée.
  assert.ok(!/généré|generated|claude/i.test(html));
});

test("HTML : contenu, chemin et note échappés (aucune injection)", () => {
  const { html } = e.renderHtml(e.parseFiles({ files: [{ path: "<b>x</b>.html",
    lines: ["<script>alert(1)</script>"], highlights: [1], note: "<img src=x onerror=1>" }] }));
  assert.ok(!html.includes("<script>alert") && !html.includes("<img src=x") && !html.includes("<b>x</b>"));
  assert.ok(html.includes("&lt;img src=x onerror=1&gt;"));
});
