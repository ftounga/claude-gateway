#!/usr/bin/env node
// F-236 / SF-236-01 — garde d'accident du morceau 3D de Jarvis (cadrage §6.4).
//
// Le poids n'est PAS un objectif d'économie (consigne PO P5) : cette garde ne fait qu'attraper un accident.
//   1. `three` ne doit JAMAIS être dans le bundle initial (scripts et préchargements d'index.html) : +0 ko au départ.
//   2. Le morceau qui porte `three` doit exister (sinon le chargement paresseux est cassé) et peser < 800 ko gzip
//      (au-delà : import involontaire, p. ex. tout `three/examples` tiré par erreur).
//
// Usage : node scripts/check-jarvis-3d-budget.mjs [dist/frontend/browser]
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

const dir = process.argv[2] ?? 'dist/frontend/browser';
const LIMIT_GZIP = 800 * 1024;
// Chaîne littérale conservée par la minification : présente dans le rendu WebGL de three, et nulle part ailleurs.
const MARKER = 'THREE.WebGLRenderer';

if (!existsSync(join(dir, 'index.html'))) {
  console.error(`check-jarvis-3d-budget : ${dir}/index.html introuvable (lancer après ng build).`);
  process.exit(2);
}

const index = readFileSync(join(dir, 'index.html'), 'utf8');
const initial = new Set();
for (const m of index.matchAll(/<script[^>]*\ssrc="([^"]+\.js)"/g)) initial.add(m[1]);
for (const m of index.matchAll(/<link[^>]*rel="modulepreload"[^>]*href="([^"]+\.js)"/g)) initial.add(m[1]);

const files = readdirSync(dir).filter((f) => f.endsWith('.js'));
const withThree = [];
let failed = false;

for (const file of files) {
  const source = readFileSync(join(dir, file));
  if (!source.includes(MARKER)) continue;
  const gz = gzipSync(source).length;
  withThree.push({ file, gz });
  if (initial.has(file)) {
    console.error(`ÉCHEC : three est dans le bundle INITIAL (${file}) — le moteur 3D doit rester paresseux.`);
    failed = true;
  }
}

if (withThree.length === 0) {
  console.error('ÉCHEC : aucun morceau ne contient three — le moteur 3D de Jarvis n’est plus construit.');
  failed = true;
}
const total = withThree.reduce((sum, c) => sum + c.gz, 0);
for (const c of withThree) {
  console.log(`morceau 3D : ${c.file} — ${(c.gz / 1024).toFixed(1)} ko gzip`);
}
if (total > LIMIT_GZIP) {
  console.error(`ÉCHEC : morceau 3D ${(total / 1024).toFixed(1)} ko gzip > ${LIMIT_GZIP / 1024} ko (import involontaire ?).`);
  failed = true;
}
if (failed) process.exit(1);
console.log(`OK : three hors du bundle initial (${initial.size} fichiers initiaux), morceau 3D ${(total / 1024).toFixed(1)} ko gzip ≤ 800 ko.`);
