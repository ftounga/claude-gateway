/**
 * Tests du lot PDF (F-184 / SF-184-01) — la partie PURE de `pdf.js`.
 *
 * Sans conteneur, sans chromium, sans réseau :
 *
 *   node --test diagram-renderer/tests/
 *
 * L'impression elle-même (chromium) est vérifiée par le test d'intégration de la mini-spec, sur
 * l'image construite.
 */

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const pdf = require("../pdf.js");

const HTML = "<!doctype html><html><body><h1>Radar</h1><img src=\"capture.png\"></body></html>";
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47]).toString("base64");

function status(fn) {
  try {
    fn();
  } catch (e) {
    assert.ok(e instanceof pdf.LotError, "LotError attendue, reçu : " + e);
    return e.status;
  }
  assert.fail("aucune erreur levée");
}

test("un lot nominal place la page à l'origine virtuelle et sert ses ressources", () => {
  const table = pdf.parseLot({
    html: HTML,
    resources: [{ url: pdf.ORIGIN + "/capture.png", contentType: "image/png", body: PNG }],
  });
  assert.equal(pdf.lookup(table, pdf.PAGE_URL).body.toString("utf-8"), HTML);
  const image = pdf.lookup(table, pdf.ORIGIN + "/capture.png");
  assert.equal(image.contentType, "image/png");
  assert.deepEqual([...image.body], [0x89, 0x50, 0x4e, 0x47]);
});

test("une ressource absente du lot n'est pas servie (elle sera refusée)", () => {
  const table = pdf.parseLot({ html: HTML });
  assert.equal(pdf.lookup(table, "https://evil.example/x.js"), null);
  assert.equal(pdf.lookup(table, "http://10.0.0.1/metadata"), null);
  assert.equal(pdf.lookup(table, "file:///etc/passwd"), null);
});

test("le fragment ne compte pas dans la correspondance", () => {
  const table = pdf.parseLot({
    html: HTML,
    resources: [{ url: "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js",
      contentType: "text/javascript", body: "" }],
  });
  assert.ok(pdf.lookup(table, "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js#x"));
});

test("type absent : repli sur application/octet-stream", () => {
  const table = pdf.parseLot({ html: HTML, resources: [{ url: pdf.ORIGIN + "/a", body: "" }] });
  assert.equal(pdf.lookup(table, pdf.ORIGIN + "/a").contentType, "application/octet-stream");
});

test("lot ou HTML manquant : 400", () => {
  assert.equal(status(() => pdf.parseLot(null)), 400);
  assert.equal(status(() => pdf.parseLot([])), 400);
  assert.equal(status(() => pdf.parseLot({})), 400);
  assert.equal(status(() => pdf.parseLot({ html: "   " })), 400);
});

test("page de plus de 8 Mo : 413", () => {
  assert.equal(status(() => pdf.parseLot({ html: "a".repeat(8 * 1024 * 1024 + 1) })), 413);
});

test("plus de 60 ressources : 413", () => {
  const resources = Array.from({ length: 61 }, (_, i) => ({ url: pdf.ORIGIN + "/r" + i, body: "" }));
  assert.equal(status(() => pdf.parseLot({ html: HTML, resources })), 413);
});

test("ressource mal formée : 400", () => {
  assert.equal(status(() => pdf.parseLot({ html: HTML, resources: "x" })), 400);
  assert.equal(status(() => pdf.parseLot({ html: HTML, resources: [null] })), 400);
  assert.equal(status(() => pdf.parseLot({ html: HTML, resources: [{ url: "ftp://x/y", body: "" }] })), 400);
  assert.equal(status(() => pdf.parseLot({ html: HTML, resources: [{ url: "pas une url", body: "" }] })), 400);
  assert.equal(status(() => pdf.parseLot({ html: HTML, resources: [{ url: pdf.ORIGIN + "/a", body: "%%%" }] })), 400);
  assert.equal(status(() => pdf.parseLot({ html: HTML, resources: [{ url: pdf.ORIGIN + "/a" }] })), 400);
});

test("l'en-tête des manquants est dédoublonné, ASCII et tronqué", () => {
  const one = pdf.missingHeader(["https://a.example/x.js", "https://a.example/x.js", "https://b.example/é.png"]);
  assert.equal(one, "https://a.example/x.js, https://b.example/%C3%A9.png");
  const long = pdf.missingHeader(Array.from({ length: 100 }, (_, i) => "https://a.example/" + i + "/" + "x".repeat(20)));
  assert.ok(long.length <= 1000);
  assert.match(long, /^[\x20-\x7E]*$/);
});

test("format A4, fonds imprimés, feuille anti-coupure", () => {
  assert.equal(pdf.PDF_OPTIONS.format, "A4");
  assert.equal(pdf.PDF_OPTIONS.printBackground, true);
  assert.match(pdf.PRINT_CSS, /print-color-adjust: exact/);
  assert.match(pdf.PRINT_CSS, /break-inside: avoid/);
  assert.match(pdf.PRINT_CSS, /break-after: avoid/);
});
