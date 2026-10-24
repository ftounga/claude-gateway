/**
 * Tests des icônes des cours vidéo (F-215 / SF-215-03) — `icons.js` sur un faux dossier `resources/`.
 *
 *   node --test diagram-renderer/tests/
 */

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "icons-"));
const put = (name, bytes) => {
  const file = path.join(dir, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, bytes || Buffer.from([0x89, 0x50, 0x4e, 0x47]));
};
put("aws/network/elb-application-load-balancer.png");
put("aws/network/transit-gateway.png");
put("azure/network/application-gateways.png");
put("k8s/compute/pod.png");
put("ibm/network/secret.png");
put("aws/network/huge.png", Buffer.alloc(600 * 1024));
fs.writeFileSync(path.join(dir, "secret.png"), "x");

process.env.ICONS_DIR = dir;
const icons = require("../icons.js");
icons.reset();

test("la recherche trouve par mots-clés, dans les fournisseurs permis seulement", () => {
  assert.deepEqual(icons.search("load balancer"), ["aws/network/elb-application-load-balancer"]);
  assert.deepEqual(icons.search("gateway"), ["aws/network/transit-gateway", "azure/network/application-gateways"]);
  assert.deepEqual(icons.search("secret"), []);
});

test("la recherche refuse un mot vide ou trop long", () => {
  assert.throws(() => icons.search("  "), /chercher/);
  assert.throws(() => icons.search("x".repeat(61)), /chercher/);
});

test("la lecture rend les PNG en base64, liste les manquantes et les invalides", () => {
  const out = icons.read(["aws/network/transit-gateway.png", "K8S/compute/pod", "aws/network/nope",
    "../secret", "ibm/network/secret", "aws/network/huge"]);
  assert.deepEqual(out.icones.map((i) => i.nom), ["aws/network/transit-gateway", "k8s/compute/pod"]);
  assert.equal(out.icones[0].png, Buffer.from([0x89, 0x50, 0x4e, 0x47]).toString("base64"));
  assert.deepEqual(out.manquantes, ["aws/network/nope", "aws/network/huge"]);
  assert.deepEqual(out.invalides, ["../secret", "ibm/network/secret"]);
});

test("les noms sont normalisés et jamais hors de resources", () => {
  assert.equal(icons.normalize("aws/network/../../etc/passwd"), null);
  assert.equal(icons.normalize("/aws/network/x"), null);
  assert.equal(icons.normalize("aws\\network\\transit-gateway.png"), "aws/network/transit-gateway");
  assert.equal(icons.normalize("aws"), null);
});

test("la lecture est bornée à 40 icônes", () => {
  assert.throws(() => icons.read(new Array(41).fill("aws/network/transit-gateway")), /40/);
  assert.throws(() => icons.read([]), /noms/);
});

test("handle aiguille recherche et lecture", () => {
  assert.deepEqual(icons.handle({ chercher: "pod" }), { trouvees: ["k8s/compute/pod"] });
  assert.equal(icons.handle({ noms: ["k8s/compute/pod"] }).icones.length, 1);
  assert.throws(() => icons.handle(null), /JSON/);
});

test("sans lib diagrams, la route le dit (503)", () => {
  process.env.ICONS_DIR = path.join(dir, "absent");
  icons.reset();
  assert.throws(() => icons.handle({ chercher: "pod" }), (e) => e.status === 503);
  process.env.ICONS_DIR = dir;
  icons.reset();
});
