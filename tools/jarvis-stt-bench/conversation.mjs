#!/usr/bin/env node
/**
 * Pré-banc de conversation (F-239 / SF-239-08) — rejoue hors passerelle le jeu `conversation-scenarios.json` sur le
 * vrai modèle, avec la consigne du cerveau rapide lue dans `JarvisFastBrainPrompt.java` et le juge lu dans
 * `JarvisConversationBench.java`, pour régler la consigne AVANT de la livrer. Approximation : les garde-fous
 * déterministes de la passerelle (phrases creuses retirées, ancrage…) ne sont pas appliqués ici ; la porte qui fait foi
 * reste `POST /api/admin/jarvis/fast-brain/bench {"set":"CONVERSATION"}` après déploiement.
 *
 * Usage : ANTHROPIC=… node conversation.mjs [modele] [juge]
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const KEY = process.env.ANTHROPIC;
const [model = 'claude-sonnet-5-5', judge = 'claude-opus-5-5'] = process.argv.slice(2);
const SRC = path.join(HERE, '../../backend/src/main');
const PROMPT = fs.readFileSync(path.join(SRC, 'java/fr/claudegateway/jarvis/fastbrain/JarvisFastBrainPrompt.java'), 'utf8');
const BENCH = fs.readFileSync(path.join(SRC, 'java/fr/claudegateway/jarvis/fastbrain/JarvisConversationBench.java'), 'utf8');
const SET = JSON.parse(fs.readFileSync(path.join(SRC, 'resources/jarvis/conversation-scenarios.json'), 'utf8'));

function textBlock(java, name) {
  const start = java.indexOf(`${name} = """`);
  const body = java.slice(java.indexOf('\n', start) + 1, java.indexOf('""";', start));
  const lines = body.split('\n');
  const indent = Math.min(...lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length));
  return lines.map((l) => l.slice(indent).replace(/\s+$/, (m) => (m.includes('\\') ? m : '')))
    .join('\n').replace(/\\\n/g, '').replace(/\\"/g, '"').replace(/\\\\/g, '\\');
}
const SYSTEM = textBlock(PROMPT, 'CORE') + textBlock(PROMPT, 'PERSONALITY_LIGHT');
const JUDGE = textBlock(BENCH, 'JUDGE_SYSTEM');
const DELEGATE = { name: 'deleguer', description: 'Confie la demande à Opus.', input_schema: { type: 'object',
  properties: { demande: { type: 'string' }, label: { type: 'string' }, annonce: { type: 'string' },
    sujet: { type: 'string' }, chantier: { type: ['integer', 'null'] } },
  required: ['demande', 'label', 'annonce', 'sujet', 'chantier'] } };
const NOTE = { name: 'noter_tour', description: 'Rend la note du tour.', input_schema: { type: 'object', properties: {
  naturel: { type: 'integer' }, concis: { type: 'boolean' }, formule_robotique: { type: 'boolean' },
  delegation_inutile: { type: 'boolean' }, sens_juste: { type: 'boolean' }, question_en_retour: { type: 'boolean' },
  fait_non_ancre: { type: 'boolean' }, action_non_deleguee: { type: 'boolean' }, commentaire: { type: 'string' } },
  required: ['naturel', 'concis', 'formule_robotique', 'delegation_inutile', 'sens_juste', 'question_en_retour',
    'fait_non_ancre', 'action_non_deleguee', 'commentaire'] } };

async function call(body) {
  for (let attempt = 0; ; attempt++) {
    let res = null;
    try {
      res = await fetch('https://api.anthropic.com/v1/messages', { method: 'POST', headers: { 'x-api-key': KEY,
        'anthropic-version': '2023-06-01', 'content-type': 'application/json' }, body: JSON.stringify(body) });
    } catch (e) {
      if (attempt >= 4) throw e; // réseau : on réessaie
    }
    if (res?.ok) return res.json();
    if (res && attempt >= 4) throw new Error(`${res.status} ${(await res.text()).slice(0, 200)}`);
    await new Promise((r) => setTimeout(r, 2_000 * (attempt + 1)));
  }
}

/** Ce que le cerveau rapide dit (sans la ligne « compris: ») et sa route. */
async function fast(context, said) {
  const b = await call({ model, max_tokens: 600, system: SYSTEM, tools: [DELEGATE],
    messages: [{ role: 'user', content: `[Contexte]\n${context}\n\n[Parole de l'utilisateur]\n${said}` }] });
  const text = b.content.filter((c) => c.type === 'text').map((c) => c.text).join('')
    .split('\n').filter((l) => !/^\s*compris\s*:/i.test(l)).join(' ').trim();
  const tool = b.content.find((c) => c.type === 'tool_use' && c.name === 'deleguer');
  return tool ? { route: 'deleguer', said: tool.input.annonce ?? '' } : { route: 'repondre', said: text };
}

function contextFor(previous, cut) {
  let c = SET.context + `\n\n## Conversation du jour\nPremière salutation du jour : ${previous.length === 0 ? 'oui' : 'non'}\n`
    + `Question de politesse déjà posée : ${previous.some(([, j]) => j.includes('?')) ? 'oui' : 'non'}`;
  if (previous.length) c += '\n\n## Derniers échanges de la conversation\n' + previous.map(([u, j]) => `- Utilisateur : ${u}\n- Jarvis : ${j}`).join('\n');
  if (cut) c += `\n\n## Coupure\nJarvis a été coupé après : « ${cut} »`;
  return c;
}

let passed = 0;
let delegations = 0;
let turnsCount = 0;
const failures = [];
const lines = [];
for (const s of SET.scenarios) {
  const previous = [];
  const reasons = [];
  let questions = 0;
  for (let i = 0; i < s.turns.length; i++) {
    const t = s.turns[i];
    const context = contextFor(previous, i === 0 ? s.heardBeforeCut : null);
    const r = await fast(context, t.said);
    turnsCount++;
    if (r.route === 'deleguer') delegations++;
    const j = await call({ model: judge, max_tokens: 600, system: JUDGE, tools: [NOTE],
      messages: [{ role: 'user', content: `## Contexte fourni à l'assistant\n${context}\n\n## Parole de l'utilisateur\n${t.said}\n\n## Ce que l'assistant a fait\n${r.route === 'deleguer' ? `DÉLÉGATION à l'agent lent et coûteux — annonce dite : « ${r.said} »` : `RÉPONSE DITE : « ${r.said} »`}\n\nCatégorie du scénario : ${s.category} ; route attendue : ${t.expect}` }] });
    const v = j.content.find((c) => c.type === 'tool_use')?.input ?? {};
    const w = `${s.id}#${i + 1}`;
    if (t.expect !== 'any' && t.expect !== r.route) reasons.push(`${w} route ${r.route}`);
    if ((v.naturel ?? 0) < 3) reasons.push(`${w} naturel ${v.naturel}`);
    if (!v.concis) reasons.push(`${w} long`);
    if (v.formule_robotique) reasons.push(`${w} robotique`);
    if (v.delegation_inutile) reasons.push(`${w} délégation inutile`);
    if (!v.sens_juste) reasons.push(`${w} sens`);
    if (v.fait_non_ancre) reasons.push(`${w} non ancré`);
    if (v.action_non_deleguee) reasons.push(`${w} action`);
    if (v.question_en_retour) questions++;
    lines.push(`${w} [${s.category}] « ${t.said} » ⇒ ${r.route} « ${r.said} » (${v.naturel}/5 ${v.commentaire ?? ''})`);
    previous.push([t.said, r.said]);
    process.stdout.write('.');
  }
  if (questions > 1) reasons.push(`${s.id} ${questions} questions en retour`);
  if (reasons.length === 0) passed++;
  else failures.push(reasons.join(' ; '));
}
const rate = Math.round(passed * 1000 / SET.scenarios.length) / 10;
const out = path.join(HERE, 'out', `conversation-${Date.now()}.txt`);
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, [...lines, '', ...failures].join('\n'));
console.log(`\nScénarios réussis : ${passed}/${SET.scenarios.length} (${rate} %) ; délégations ${delegations}/${turnsCount}`);
console.log(failures.join('\n'));
console.log(`Détail : ${out}`);
