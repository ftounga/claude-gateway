// F-256 — BANC À VRAIE VOIX de Jarvis : la vraie page /jarvis (build de développement) dans Chrome sans tête, contre la
// PASSERELLE LOCALE et les VRAIS fournisseurs (passerelle.sh), à qui l'on fait ENTENDRE des enregistrements (micro
// virtuel, micro.js). Rien n'est simulé côté Jarvis : détection de voix, flux ElevenLabs et repli batch, cerveau
// rapide, voix, affichage.
//
//   node banc.mjs --dist <dist>/browser --token <fichier du jeton> [--scenarios a,b] [--out <dossier>]
//                 [--gateway http://localhost:18256] [--corpus <dossier>] [--deps <dossier de puppeteer-core>]
//
// Sortie (hors dépôt) : `rapport.json` (tour par tour, et les sondes brutes) + `resume.txt` ; code 1 si la porte
// (`porte.mjs`) n'est pas franchie. UNE seule session de transcription à la fois (compte ElevenLabs partagé avec la
// prod) : les scénarios passent l'un après l'autre.
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { pcmOf, wav } from './corpus.mjs';
import { analyse, summary } from './mesure.mjs';
import { gate } from './porte.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]?.startsWith('--') ? 'true' : all[i + 1]]);
  return acc;
}, []));
const DIST = args.dist;
const GATEWAY = args.gateway ?? 'http://localhost:18256';
const CORPUS = args.corpus ?? join(homedir(), '.local/share/claude-gateway/jarvis-voice-corpus');
const OUT = args.out ?? join(homedir(), '.local/share/claude-gateway/banc-jarvis-voix', new Date().toISOString().replace(/[:.]/g, '-'));
const DEPS = args.deps ?? join(homedir(), '.local/share/claude-gateway/banc-jarvis-voix-deps');
const PORT = Number(args.port ?? 4356);
const VIEW = args.vue === '3d' ? '3d' : '2d';
const TOKEN = (await readFile(args.token, 'utf8')).trim();
const puppeteer = createRequire(join(DEPS, 'package.json'))('puppeteer-core');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const spec = JSON.parse(await readFile(join(HERE, 'phrases.json'), 'utf8'));
const index = JSON.parse(await readFile(join(CORPUS, 'index.json'), 'utf8'));
const MICRO = await readFile(join(HERE, 'micro.js'), 'utf8');

// ---------------------------------------------------------------------------------------------------------------
// Serveur du banc : la page (dist), l'audio du corpus (`/__banc/audio?f=`), et /api relayé vers la passerelle locale,
// en gardant ce qui sert à la mesure (corps des requêtes Jarvis, événements SSE du cerveau rapide).
// ---------------------------------------------------------------------------------------------------------------
const TYPES = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.woff2': 'font/woff2', '.json': 'application/json', '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json', '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.wasm': 'application/wasm',
  '.onnx': 'application/octet-stream', '.mjs': 'text/javascript' };
const api = [];
const allowedAudio = new Set([...Object.values(index.synthetic), ...Object.values(index.captures)].map((e) => e.file));

const KEEP_BODY = /^\/api\/jarvis\/(converse|timings|listen-trace|speech|utterance|transcri)|^\/api\/atelier\/.*(messages|send|stream)/;
function relay(req, res, path) {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    const entry = { t: Date.now(), method: req.method, path, status: null, ms: null, sse: [] };
    if (KEEP_BODY.test(path) && body.length && /json/.test(req.headers['content-type'] ?? '')) {
      try { entry.body = JSON.parse(body.toString('utf8')); } catch { /* corps illisible : ignoré */ }
    }
    api.push(entry);
    const url = new URL(path, GATEWAY);
    const headers = { ...req.headers, host: url.host };
    delete headers['accept-encoding'];
    // Même origine que la passerelle (comme derrière l'ingress de la prod) : sinon CORS refuse les POST (403).
    delete headers.origin;
    delete headers.referer;
    const upstream = fetch(url, { method: req.method, headers, body: ['GET', 'HEAD'].includes(req.method) ? undefined : body,
      redirect: 'manual' });
    upstream.then(async (r) => {
      entry.status = r.status;
      const out = {};
      r.headers.forEach((v, k) => { if (!['content-encoding', 'content-length', 'transfer-encoding'].includes(k)) out[k] = v; });
      res.writeHead(r.status, out);
      if (!r.body) {
        entry.ms = Date.now() - entry.t;
        return res.end();
      }
      const sse = /event-stream/.test(r.headers.get('content-type') ?? '');
      let buffer = '';
      const decoder = new TextDecoder();
      for await (const chunk of r.body) {
        res.write(chunk);
        if (sse) {
          buffer += decoder.decode(chunk, { stream: true });
          let sep;
          while ((sep = buffer.indexOf('\n\n')) >= 0) {
            const block = buffer.slice(0, sep);
            buffer = buffer.slice(sep + 2);
            const name = /^event:\s*(.*)$/m.exec(block)?.[1]?.trim() ?? 'message';
            const data = /^data:\s*(.*)$/m.exec(block)?.[1] ?? '';
            let parsed = data;
            try { parsed = JSON.parse(data); } catch { /* texte brut */ }
            entry.sse.push({ t: Date.now(), event: name, data: parsed });
          }
        }
      }
      entry.ms = Date.now() - entry.t;
      res.end();
    }).catch((e) => {
      entry.status = 0;
      entry.error = String(e?.message ?? e).slice(0, 120);
      if (!res.headersSent) res.writeHead(502);
      res.end();
    });
  });
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const path = decodeURIComponent(url.pathname);
  if (path.startsWith('/api/')) {
    return relay(req, res, req.url);
  }
  if (path === '/__banc/audio') {
    const file = url.searchParams.get('f');
    if (!allowedAudio.has(file)) {
      res.writeHead(404);
      return res.end();
    }
    res.writeHead(200, { 'Content-Type': 'audio/wav' });
    return res.end(await readFile(file));
  }
  let file = join(DIST, path);
  if (!existsSync(file) || path === '/' || !extname(path)) {
    file = join(DIST, 'index.html');
  }
  try {
    const data = await readFile(file);
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream' });
    res.end(data);
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((r) => server.listen(PORT, r));
await mkdir(OUT, { recursive: true });

async function gatewayCosts() {
  try {
    const r = await fetch(`${GATEWAY}/api/jarvis/costs`, { headers: { Authorization: `Bearer ${TOKEN}` } });
    return r.ok ? await r.json() : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Un scénario : une page neuve, la conversation mains libres démarrée seule (SF-233-12), puis les phrases jouées
// dans le micro quand Jarvis écoute (ou à l'instant prévu pour un enchaînement ou une coupure).
// ---------------------------------------------------------------------------------------------------------------
const component = () => window.ng.getComponent(document.querySelector('app-jarvis'));

/** Origine (ms epoch) du journal d'écoute de chaque page ouverte (`pageId`) : ses `t` y sont relatifs. */
let traceOrigins = {};

async function pageState(page) {
  try {
    const s = await page.evaluate((c) => {
      try {
        const cmp = (0, eval)(c)();
        const b = window.__banc;
        const last = [...b.events].reverse().find((e) => e.kind === 'sound_start' || e.kind === 'sound_end');
        return { state: cmp.state(), sounding: last?.kind === 'sound_start', lastSound: last?.t ?? 0,
          now: performance.timeOrigin + performance.now(), tracePage: cmp.trace.pageId,
          traceOrigin: Math.round(performance.timeOrigin + cmp.trace.started) };
      } catch (e) {
        return { state: null, error: String(e).slice(0, 80) };
      }
    }, component.toString());
    if (s.tracePage) traceOrigins[s.tracePage] = s.traceOrigin;
    return s;
  } catch (e) {
    // La page se recharge (contexte détruit) : on réessaie au tour de boucle suivant.
    return { state: null, error: String(e?.message ?? e).slice(0, 80) };
  }
}

/** Joue une phrase ; `null` si la page s'est rechargée pendant ce temps. */
async function say(page, url, id) {
  try {
    return await page.evaluate((u, i) => window.__banc.play(u, 1, i), url, id);
  } catch {
    return null;
  }
}

/** Attend que Jarvis écoute et se taise depuis `quietMs` (ou expire). */
async function waitListening(page, timeoutMs, quietMs = 1200) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const s = await pageState(page);
    if (s.state === 'ecoute' && !s.sounding && s.now - s.lastSound > quietMs) {
      return true;
    }
    await sleep(200);
  }
  return false;
}

/** Attend que Jarvis quitte l'écoute (réflexion, parole) — la phrase est prise — ou expire. */
async function waitLeaving(page, timeoutMs) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const s = await pageState(page);
    if (s.state && s.state !== 'ecoute') {
      return true;
    }
    await sleep(150);
  }
  return false;
}

/** Attend le début de la voix de Jarvis (pour une coupure). */
async function waitVoice(page, since, timeoutMs) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const t = probes.find((e) => e.kind === 'sound_start' && e.what === 'voix' && e.t > since)?.t ?? null;
    if (t) return t;
    await sleep(100);
  }
  return null;
}

/** Les sondes de la page, reçues au fil de l'eau (survivent à un rechargement). */
let probes = [];

function recordings(scenario) {
  if (scenario.captures) {
    // Les captures à texte d'abord, les vides ensuite : sept captures presque muettes à la suite font 60 s sans parole,
    // et la conversation s'arrête d'elle-même (SF-233-11) — la phrase suivante ne serait plus écoutée.
    return Object.entries(index.captures)
      .sort((a, b) => Number(!a[1].expected) - Number(!b[1].expected) || a[1].at.localeCompare(b[1].at))
      .map(([id, e]) => ({ step: { say: id }, id, ...e }));
  }
  return scenario.steps.map((step) => {
    const e = index.synthetic[`${scenario.voice}/${step.say}`];
    if (!e) throw new Error(`phrase absente du corpus : ${scenario.voice}/${step.say} (lancer corpus.mjs)`);
    return { step, id: step.say, ...e };
  });
}

/** Témoin : la phrase dans un fichier lu par le micro factice de Chrome (12 s de silence avant, 25 s après). */
async function temoinFile(scenario) {
  const e = index.synthetic[`${scenario.voice}/${scenario.chromeMic}`];
  const pcm = pcmOf(await readFile(e.file));
  const file = join(OUT, `temoin-${scenario.chromeMic}.wav`);
  await writeFile(file, wav(Buffer.concat([Buffer.alloc(16000 * 2 * 12), pcm, Buffer.alloc(16000 * 2 * 25)])));
  return { file, entry: e };
}

async function runScenario(scenario) {
  const temoin = scenario.chromeMic ? await temoinFile(scenario) : null;
  const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
    args: [...(temoin ? [`--use-file-for-fake-audio-capture=${temoin.file}%noloop`] : []), '--no-sandbox', '--window-size=1366,768', '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
      '--autoplay-policy=no-user-gesture-required', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
      '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  const pageErrors = [];
  probes = [];
  traceOrigins = {};
  page.on('pageerror', (e) => pageErrors.push(String(e.message).slice(0, 160)));
  await page.exposeFunction('__bancLog', (event) => { probes.push(event); });
  // Un rechargement de la page en plein scénario est un fait mesuré (la conversation repart de zéro).
  page.on('framenavigated', (frame) => {
    if (frame === page.mainFrame()) probes.push({ t: Date.now(), kind: 'page_navigated', url: frame.url().slice(0, 120) });
  });
  await page.setViewport({ width: 1366, height: 768 });
  // Vue 2D par défaut : sans GPU, la 3D en rendu logiciel (swiftshader) occupe le fil principal et gonfle toutes les
  // latences de plusieurs secondes — un biais du banc, pas de Jarvis. `--vue 3d` pour mesurer la 3D quand même.
  await page.evaluateOnNewDocument((token, view) => {
    localStorage.setItem('cg_token', token);
    if (view === '2d') localStorage.setItem('cg.jarvis.view', '2d');
  }, TOKEN, VIEW);
  if (temoin) {
    await page.evaluateOnNewDocument(() => { window.__bancChromeMic = true; });
  }
  await page.evaluateOnNewDocument(MICRO);
  const apiFrom = api.length;
  const started = Date.now();
  await page.goto(`http://localhost:${PORT}/jarvis`, { waitUntil: 'load' });
  if (scenario.echo) {
    await page.evaluate((g) => { window.__banc.echo = g; }, scenario.echo).catch(() => undefined);
  }
  const ready = await waitListening(page, 45_000, 1500);
  if (scenario.noise) {
    await page.evaluate((g) => window.__banc.background(g), scenario.noise).catch(() => undefined);
  }
  const turns = [];
  if (temoin) {
    // Le fichier part à l'ouverture du micro : la phrase commence 12 s après le premier `getUserMedia`.
    const opened = probes.find((e) => e.kind === 'mic_get')?.t ?? null;
    const start = (opened ?? Date.now()) + 12_000;
    turns.push({ id: scenario.chromeMic, kind: 'temoin', expected: temoin.entry.expected, show: false, after: 'ecoute',
      start, end: start + temoin.entry.durationMs, speechEnd: start + temoin.entry.speechEndMs,
      activeMs: temoin.entry.activeMs });
    await sleep(Math.max(0, start - Date.now()) + 25_000);
  }
  const list = temoin ? [] : recordings(scenario);
  for (const [i, rec] of list.entries()) {
    const after = rec.step.after;
    if (after === 'voix') {
      // Coupure : la phrase part `gapMs` après le début de la voix de Jarvis (réponse au tour précédent).
      await waitVoice(page, turns.at(-1)?.end ?? 0, 20_000);
      await sleep(rec.step.gapMs ?? 1500);
    } else if (after === 'fin') {
      await sleep(rec.step.gapMs ?? 800); // enchaînement : sans attendre la réponse
    } else {
      const ok = await waitListening(page, 40_000);
      if (!ok) turns.push({ id: rec.id, note: 'jarvis_n_ecoutait_pas' });
    }
    const url = `/__banc/audio?f=${encodeURIComponent(rec.file)}`;
    const played = await say(page, url, rec.id);
    if (!played) {
      turns.push({ id: rec.id, note: 'page_rechargee' });
      continue;
    }
    turns.push({ id: rec.id, kind: rec.kind, expected: rec.expected, stream: rec.stream ?? null, show: !!rec.show,
      after: after ?? 'ecoute', start: played.start, end: played.end,
      speechEnd: rec.speechEndMs === null ? null : played.start + rec.speechEndMs, activeMs: rec.activeMs });
    // Le tour doit pouvoir commencer : on attend que Jarvis QUITTE l'écoute (il a pris la phrase), au plus 8 s ; sinon
    // la phrase suivante partirait pendant que celle-ci est encore en fin de tour.
    if (list[i + 1]?.step.after !== 'fin') {
      await waitLeaving(page, 8_000);
    }
  }
  // La dernière réponse, puis le journal d'écoute envoyé (sortie de page).
  await waitListening(page, 40_000);
  await sleep(1500);
  await pageState(page);
  await page.evaluate((c) => { try { (0, eval)(c)().trace.flush(); } catch { /* best-effort */ } }, component.toString())
    .catch(() => undefined);
  await sleep(800);
  const screen = await page.evaluate((c) => {
    try {
      const cmp = (0, eval)(c)();
      return { state: cmp.state(), notice: cmp.notice?.() ?? null };
    } catch {
      return null;
    }
  }, component.toString()).catch(() => null);
  await page.screenshot({ path: join(OUT, `${scenario.id}.png`) }).catch(() => undefined);
  await browser.close();
  return { id: scenario.id, label: scenario.label ?? scenario.id, ready, started, ended: Date.now(), turns,
    probes, api: api.slice(apiFrom), pageErrors, screen, traceOrigins: { ...traceOrigins } };
}

const only = args.scenarios?.split(',');
const scenarios = spec.scenarios.filter((s) => !only || only.includes(s.id));
// Mise en température (la prod est chaude) : un échange et une phrase de voix, hors mesure.
async function warmUp() {
  const headers = { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' };
  try {
    const r = await fetch(`${GATEWAY}/api/jarvis/converse`, { method: 'POST', headers,
      body: JSON.stringify({ text: 'Bonjour Jarvis.' }) });
    await r.text();
    const s = await fetch(`${GATEWAY}/api/jarvis/speech`, { method: 'POST', headers, body: JSON.stringify({ text: 'Bonjour.' }) });
    await s.arrayBuffer();
  } catch {
    // la mesure dira ce qui ne marche pas
  }
}
await warmUp();
const before = await gatewayCosts();
const runs = [];
for (const scenario of scenarios) {
  process.stderr.write(`scénario ${scenario.id}…\n`);
  runs.push(await runScenario(scenario));
}
const afterCosts = await gatewayCosts();
const report = analyse(runs, { before, after: afterCosts });
report.meta = { at: new Date().toISOString(), gateway: GATEWAY, dist: DIST, view: VIEW, scenarios: scenarios.map((s) => s.id),
  commit: process.env.BANC_COMMIT ?? null };
report.gate = gate(report);
await writeFile(join(OUT, 'rapport.json'), JSON.stringify({ ...report, runs }, null, 2));
const text = summary(report);
await writeFile(join(OUT, 'resume.txt'), text);
console.log(text);
console.log(`\nrapport : ${join(OUT, 'rapport.json')}`);
server.close();
process.exit(report.gate.pass ? 0 : 1);
