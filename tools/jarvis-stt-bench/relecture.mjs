#!/usr/bin/env node
/**
 * Banc de la relecture « compris: » (F-239 / SF-239-05).
 *
 * Rejoue les transcriptions d'un passage du banc d'écoute (`bench.mjs`, fichier `out/results-*.json`) dans le vrai
 * cerveau rapide (consigne lue dans `JarvisFastBrainPrompt.java`, modèle de prod), avec un contexte d'univers
 * réaliste ; compare le WER avant / après relecture, applique la même garde anti-invention que la passerelle
 * (`JarvisUnderstanding`) et compte les relectures refusées.
 *
 * Usage : ANTHROPIC=… node relecture.mjs out/results-XXXX.json [B1,B2,…] [modele]
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { norm, wer } from './bench.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const KEY = process.env.ANTHROPIC;
const [file, only = 'B1,B2', model = 'claude-sonnet-5-5'] = process.argv.slice(2);
const PROMPT_FILE = path.join(HERE, '../../backend/src/main/java/fr/claudegateway/jarvis/fastbrain/JarvisFastBrainPrompt.java');

/** Un bloc de texte Java (`"""…"""`) : indentation commune retirée, `\` en fin de ligne = ligne jointe. */
function textBlock(java, name) {
  const start = java.indexOf(`${name} = """`);
  const body = java.slice(java.indexOf('\n', start) + 1, java.indexOf('""";', start));
  const lines = body.split('\n');
  const indent = Math.min(...lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length));
  return lines.map((l) => l.slice(indent).replace(/\s+$/, (m) => (m.includes('\\') ? m : '')))
    .join('\n').replace(/\\\n/g, '').replace(/\\"/g, '"').replace(/\\\\/g, '\\');
}

const java = fs.readFileSync(PROMPT_FILE, 'utf8');
const SYSTEM = textBlock(java, 'CORE') + textBlock(java, 'PERSONALITY_LIGHT');

const CONTEXT = `## Maintenant
lundi 2 novembre 2026, 09:12
Sujet courant : agenor

## Univers (état à 09:00)
- Client CAGIP (poste connecté) : sujets agenor (chantiers 1, 6, 7), cicd-bff, cloudops-run, corp, data-platform
  (chantiers 3, 4, 6, 7, 9), gitlab-tfstate (chantier 2 « LDIC-223 rotation du jeton GitLab »), lzi ; personnes :
  René Martin (RSSI)
- Client EDENRED (poste connecté) : sujets alarm4tech, audit-secu-aws, chaine-marchand, EDENRED, pentest-opera,
  revue-zelda, secrets-azure-devops, security-assessment, spider-mfa, waf

## Derniers échanges du fil du sujet courant (état à 09:00)
- Utilisateur : Bonjour Jarvis.
- Jarvis : Bonjour, Monsieur.`;

const TOOL = { name: 'deleguer', description: 'Confie la demande à Opus.', input_schema: { type: 'object',
  properties: { demande: { type: 'string' }, label: { type: 'string' }, annonce: { type: 'string' },
    sujet: { type: 'string' }, chantier: { type: ['integer', 'null'] } },
  required: ['demande', 'label', 'annonce', 'sujet', 'chantier'] } };

// La garde de la passerelle (JarvisUnderstanding), à l'identique.
const ACTIONS = ['envo', 'lanc', 'supprim', 'cre', 'publi', 'pouss', 'valid', 'ferm', 'relanc', 'effac', 'deploi',
  'deploy', 'fusion', 'merg', 'annul', 'pai', 'pay', 'vir', 'achet', 'command', 'reserv', 'signe', 'sign', 'transfer',
  'partag', 'archiv', 'redemarr', 'arret', 'install', 'desinstall', 'repond', 'ecri', 'modifi', 'chang', 'renomm',
  'deplac', 'commit', 'push', 'planifi'];
const words = (t) => t.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^a-z0-9]+/g, ' ').trim()
  .split(' ').filter(Boolean);
function distance(a, b) {
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = cur;
  }
  return prev[b.length];
}
function accept(said, read) {
  if (!read || !read.trim()) return null;
  const raw = words(said);
  const r = words(read);
  if (r.length === 0 || distance(raw, r) > Math.max(2, Math.floor(raw.length * 0.4))) return null;
  const stem = (w) => w.slice(0, 5);
  if (r.some((w) => ACTIONS.some((a) => w.startsWith(a)) && !raw.some((x) => stem(x) === stem(w)))) return null;
  return read.trim();
}

async function relire(said) {
  const started = performance.now();
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'x-api-key': KEY, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
    body: JSON.stringify({ model, max_tokens: 400, system: SYSTEM, tools: [TOOL],
      messages: [{ role: 'user', content: `[Contexte]\n${CONTEXT}\n\n[Parole de l'utilisateur]\n${said}` }] }),
  });
  if (!res.ok) throw new Error(`${res.status} ${(await res.text()).slice(0, 200)}`);
  const body = await res.json();
  const text = body.content.filter((b) => b.type === 'text').map((b) => b.text).join('');
  const line = text.split('\n').find((l) => /^\s*compris\s*:/i.test(l));
  const read = line ? line.replace(/^\s*compris\s*:\s*/i, '').replace(/^[«"]+|[»"]+$/g, '').trim() : null;
  return { read, ms: Math.round(performance.now() - started), usage: body.usage };
}

const results = JSON.parse(fs.readFileSync(file, 'utf8'));
const report = {};
for (const id of only.split(',')) {
  const opt = results.options[id];
  if (!opt) continue;
  let before = 0;
  let after = 0;
  let total = 0;
  let missing = 0;
  let refused = 0;
  const rows = [];
  for (const row of opt.rows) {
    if (!row.hyp) continue;
    const { read, ms } = await relire(row.hyp).catch((e) => ({ read: null, ms: null, error: e.message }));
    const kept = accept(row.hyp, read);
    if (read === null) missing++;
    else if (kept === null) refused++;
    const final = kept ?? row.hyp;
    const b = wer(row.ref, row.hyp);
    const a = wer(row.ref, final);
    before += b.errors;
    after += a.errors;
    total += b.words;
    rows.push({ id: row.id, ref: row.ref, hyp: row.hyp, read, kept: kept !== null, werBefore: b.errors,
      werAfter: a.errors, ms });
    process.stdout.write('.');
  }
  report[id] = { label: opt.label, cases: rows.length, werBeforePct: +(100 * before / total).toFixed(1),
    werAfterPct: +(100 * after / total).toFixed(1), missingLine: missing, refused, rows };
  console.log(`\n${id} ${opt.label} : WER ${report[id].werBeforePct} % → ${report[id].werAfterPct} % après relecture `
    + `(ligne absente ${missing}, relectures refusées ${refused}, ${rows.length} cas)`);
}
const out = path.join(HERE, 'out', `relecture-${Date.now()}.json`);
fs.writeFileSync(out, JSON.stringify(report, null, 2));
console.log(`Résultats : ${out}`);
void norm;
