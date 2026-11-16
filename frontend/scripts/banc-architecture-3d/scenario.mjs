// F-238 / SF-238-10 — banc « l'architecture en 3D dans /jarvis » : la VRAIE page /jarvis (build de développement),
// API simulée (`../banc-jarvis-montre/mock.mjs`), graphe réel du renderer (fixture 3-tiers), icônes officielles lues
// dans le paquet `diagrams` local si présent (sinon boîtes neutres). Chrome sans tête SANS --hide-scrollbars, rendu
// logiciel. Joue : Jarvis montre l'architecture (et cadre l'entrée), « zoome sur la base » (cerveau ⇒ focus),
// « éclate » (grammaire locale, sans IA), « replie la zone B » (cerveau ⇒ replier), « vue de dessus », « en 2D ».
// Vérifie et capture : la scène est au centre et grande, la ligne « Architecture » part dans le message, rien ne casse.
//
// Usage : `npx ng build --configuration development --output-path <dist>` puis, depuis le front (puppeteer-core) :
//   node scripts/banc-architecture-3d/scenario.mjs <dist>/browser <sortie>
// `BANC_ONLY=1920,390` : tailles jouées. `BANC_ICONS=<dossier resources de diagrams>`. Code 1 si une vérification échoue.
import { createServer as http } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { extname, join } from 'node:path';
import { homedir } from 'node:os';
import puppeteer from 'puppeteer-core';
import { createServer, SCRIPTS, PIECES, DATA_PLATFORM } from '../banc-jarvis-montre/mock.mjs';

const [dist, out = './out'] = process.argv.slice(2);
const HERE = new URL('.', import.meta.url).pathname;
const GRAPH = await readFile(join(HERE, '../../src/app/jarvis/stage3d/architecture/fixtures/trois-tiers.graph.json'), 'utf8');
const ICONS = process.env.BANC_ICONS ?? join(homedir(), '.local/lib/python3.8/site-packages/resources');
const TYPES = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.woff2': 'font/woff2', '.json': 'application/json', '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const findings = [];
const note = (ok, what) => findings.push(`${ok ? 'OK  ' : 'BUG '} ${what}`);

const files = http(async (req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  let file = join(dist, path);
  if (!existsSync(file) || path === '/' || !extname(path)) {
    file = join(dist, 'index.html');
  }
  try {
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream' });
    res.end(await readFile(file));
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((r) => files.listen(4366, r));
await mkdir(out, { recursive: true });

const now = Date.now();
PIECES[DATA_PLATFORM] = [
  { id: 'diag:arch-1', category: 'PRODUCTION', kind: 'ARCHITECTURE', title: 'Application 3-tiers multi-AZ',
    caption: 'Déploiement de production', captionSource: 'TITLE', at: new Date(now - 3600_000).toISOString(),
    subjectId: DATA_PLATFORM, chantier: 3, chantierLink: 'MESSAGE', thumb: null, open: null,
    meta: { graph: true }, available: 'ONLINE' },
  { id: 'video:1', category: 'PRODUCTION', kind: 'VIDEO', title: 'Kubernetes — leçon 1', captionSource: 'TITLE',
    at: new Date(now - 7200_000).toISOString(), subjectId: DATA_PLATFORM, chantier: 3, chantierLink: 'MESSAGE',
    thumb: null, open: null, meta: {}, available: 'ONLINE' },
];
SCRIPTS.length = 0;
SCRIPTS.push({ when: 'comment c\'est déployé', events: [
  ['show', { action: 'piece', pieces: ['diag:arch-1'], scope: { subjectId: DATA_PLATFORM }, reason: 'appui' }],
  ['show', { action: 'flux', target: 'entrée', reason: 'appui' }],
  ['text', { delta: 'Voici le schéma de production : le trafic entre par l\'ALB, puis se répartit sur les deux zones. ' }]] });
SCRIPTS.push({ when: 'zoome sur la base', events: [
  ['text', { delta: 'Il y en a deux, Monsieur : RDS primaire et RDS secours. Je cadre la primaire. ' }],
  ['show', { action: 'focus', target: 'RDS primaire', reason: 'demande' }]] });
SCRIPTS.push({ when: 'replie la zone b', events: [
  ['show', { action: 'focus', target: '', reason: 'demande' }],
  ['show', { action: 'replier', target: 'zone B', reason: 'demande' }], ['text', { delta: 'Zone B repliée. ' }]] });

async function measure(page) {
  return page.evaluate(() => {
    const viewer = document.querySelector('app-jarvis-piece-architecture');
    const canvas = viewer?.querySelector('canvas')?.getBoundingClientRect() ?? null;
    const stage = viewer?.querySelector('app-architecture-stage');
    const labels = [...(viewer?.querySelectorAll('.jv3d-label') ?? [])].filter((e) => {
      const r = e.getBoundingClientRect();
      return getComputedStyle(e).visibility !== 'hidden' && Number(e.style.opacity || 0) > 0.05 && r.width > 0;
    }).map((e) => e.textContent);
    const universe = document.querySelector('app-jarvis-stage-3d');
    const uCmp = universe && window.ng?.getComponent(universe);
    // SF-238-11 — l'emprise réelle du schéma à l'écran : les étiquettes et icônes posées (pas le canevas).
    const zones = [...(viewer?.querySelectorAll('.jv3d-label--arch-zone') ?? [])].filter((e) =>
      getComputedStyle(e).visibility !== 'hidden' && Number(e.style.opacity || 0) > 0.05).map((e) => e.textContent);
    return { viewer: !!viewer, svg: !!document.querySelector('.pv__zoom'), zones,
      universeFrames: uCmp?.engine?.frames ?? null, canvas: canvas && { x: canvas.left,
      y: canvas.top, w: canvas.width, h: canvas.height }, calls: stage?.getAttribute('data-calls') ?? null,
      lit: stage?.getAttribute('data-lit') ?? '', labels, vw: innerWidth, vh: innerHeight,
      scrollW: document.documentElement.scrollWidth, panel: document.querySelector('.pa__panel')?.textContent ?? '' };
  });
}

async function run({ width, height, tag }) {
  const server = createServer();
  const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
    ignoreDefaultArgs: ['--hide-scrollbars'],
    args: ['--no-sandbox', `--window-size=${width},${height}`, '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
      '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'] });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message.slice(0, 200)));
  await page.setViewport({ width, height, deviceScaleFactor: 1, isMobile: width < 768, hasTouch: width < 768 });
  await page.evaluateOnNewDocument(() => {
    localStorage.setItem('cg_token', 'banc');
    window.speechSynthesis && (window.speechSynthesis.speak = () => undefined);
  });
  await page.setRequestInterception(true);
  page.on('request', async (req) => {
    const p = decodeURIComponent(new URL(req.url()).pathname);
    if (p === '/api/jarvis/pieces/diag:arch-1/graph') {
      return req.respond({ status: 200, contentType: 'application/json', body: GRAPH });
    }
    const icon = p.match(/^\/api\/diagrams\/icons\/([a-z0-9/_-]+)\.png$/);
    if (icon) {
      const file = join(ICONS, `${icon[1]}.png`);
      return existsSync(file) ? req.respond({ status: 200, contentType: 'image/png', body: await readFile(file) })
        : req.respond({ status: 404 });
    }
    if (p.startsWith('/api/')) {
      return server.handle(req);
    }
    return req.continue();
  });
  await page.goto(`http://localhost:4366/jarvis?sujet=${DATA_PLATFORM}`, { waitUntil: 'load' });
  await sleep(7000);
  if (process.env.BANC_LOW === '1') {
    // Qualité basse de l'écran (ce que la séance du PO a parfois) : la scène d'architecture part au niveau bas.
    await page.evaluate(() => {
      const stage = document.querySelector('app-jarvis-stage-3d');
      const cmp = stage && window.ng.getComponent(stage);
      cmp?.level.set('low');
    });
  }
  const app = () => window.ng.getComponent(document.querySelector('app-jarvis'));
  const say = (text) => page.evaluate((a, t) => (0, eval)(a)().handle(t), app.toString(), text);
  const shot = async (name, wait) => {
    await sleep(wait);
    await page.screenshot({ path: join(out, `${name}-${tag}${process.env.BANC_LOW === '1' ? '-bas' : ''}.png`) });
    return measure(page);
  };

  await say('Comment c\'est déployé chez ce client ?');
  const a = await shot('sf10-1-montre', 4200);
  note(a.viewer, `${tag} l'architecture s'ouvre dans la visionneuse 3D (pas le SVG) : ${a.viewer} / svg ${a.svg}`);
  const area = a.canvas ? (a.canvas.w * a.canvas.h) / (a.vw * a.vh) : 0;
  const cx = a.canvas ? a.canvas.x + a.canvas.w / 2 : -1;
  note(area >= (width < 768 ? 0.35 : 0.3), `${tag} scène grande : ${(area * 100).toFixed(0)} % de l'écran`);
  note(Math.abs(cx - a.vw / 2) < 48, `${tag} scène centrée horizontalement (centre ${Math.round(cx)} / ${a.vw / 2})`);
  note(/e0|e1/.test(a.lit), `${tag} premier cadrage : le flux d'entrée est allumé (${a.lit || 'rien'})`);
  note(Number(a.calls) > 0 && Number(a.calls) <= 20, `${tag} appels de dessin ${a.calls} (≤ 20)`);

  // SF-238-11 — l'univers recouvert est suspendu (0 image) pendant la scène.
  const u0 = (await measure(page)).universeFrames;
  await sleep(1500);
  const u1 = (await measure(page)).universeFrames;
  note(u0 !== null && u0 === u1, `${tag} univers suspendu pendant la scène (${u0} → ${u1} images)`);
  if (tag === 'tel') {
    const held = ['AZ A', 'AZ B', 'Public A', 'Privé A', 'Public B', 'Privé B'];
    const seen = held.filter((z) => a.zones.some((l) => (l ?? '').toLowerCase() === z.toLowerCase()));
    note(seen.length === held.length, `${tag} libellés des zones porteuses visibles : ${seen.length}/${held.length} (${a.zones.join(' | ')})`);
  }

  // Retour PO 2026-11-16 — gestes : pivoter (glisser), pincer à deux doigts, recentrer.
  const cam = () => page.evaluate(() => {
    const v = document.querySelector('app-jarvis-piece-architecture');
    if (!v) {
      return { az: NaN, el: NaN, zoom: NaN };
    }
    const nav = window.ng.getComponent(v).nav();
    return { az: nav.arch.orbit.az, el: nav.arch.orbit.el, zoom: nav.zoom, folded: nav.arch.folded.map((r) => r.id).join(','), focus: nav.arch.focus?.id ?? '' };
  });
  const box = await page.evaluate(() => {
    const r = (document.querySelector('app-jarvis-piece-architecture .pa') ?? document.body).getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height };
  });
  const c0 = await cam();
  // Le glisser part du vide (coin du cadre) : en rendu logiciel la première image peut tarder plus que le toucher long
  // (600 ms), qui replierait alors la zone sous le doigt — sur un vrai écran le geste part tout de suite.
  const sx = box.x + box.w * 0.4;
  const sy = box.y - box.h * 0.4;
  await page.mouse.move(sx, sy);
  await page.mouse.down();
  for (let i = 1; i <= 12; i++) {
    await page.mouse.move(sx - i * 15, sy + i * 3);
  }
  await page.mouse.up();
  const r1 = await shot('sf11-g1-pivote', 900);
  const c1 = await cam();
  note(c1.az !== c0.az, `${tag} glisser fait pivoter (azimut ${c0.az.toFixed(2)} → ${c1.az.toFixed(2)})`);
  const cdp = await page.createCDPSession();
  const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y], id) => ({ x, y, id })) });
  await touch('touchStart', [[box.x - 40, box.y], [box.x + 40, box.y]]);
  for (let i = 1; i <= 10; i++) {
    await touch('touchMove', [[box.x - 40 - i * 12, box.y], [box.x + 40 + i * 12, box.y]]);
  }
  await touch('touchEnd', []);
  const r2 = await shot('sf11-g2-pince', 900);
  const c2 = await cam();
  note(c2.zoom > c1.zoom, `${tag} pincer (écarter) zoome (×${c1.zoom} → ×${c2.zoom})`);
  await page.evaluate(() => [...document.querySelectorAll('.pa__btn')].find((b) => b.textContent.trim() === 'Recentrer')?.click());
  await shot('sf11-g3-recentre', 1800);
  const c3 = await cam();
  note(c3.zoom === 1 && c3.az === 0, `${tag} « Recentrer » remet la vue d'ensemble (×${c3.zoom}, azimut ${c3.az})`);
  void r1; void r2;

  await say('Zoome sur la base');
  const b = await shot('sf10-2-focus-base', 2600);
  note(/RDS primaire/.test(b.panel), `${tag} « zoome sur la base » : fiche du service « ${b.panel.trim().slice(0, 60)} »`);
  const sent = server.state.converse.at(-1)?.screen ?? '';
  note(/Architecture « Application 3-tiers multi-AZ » ; focus : aucun/.test(sent),
    `${tag} la ligne « Architecture » part dans le message (bloc ## Écran) : ${JSON.stringify(sent.split('\n')[1] ?? '')}`);

  const before = server.state.converse.length;
  await say('Éclate');
  const c = await shot('sf10-3-eclate', 2200);
  note(server.state.converse.length === before, `${tag} « éclate » sans IA (aucun appel au cerveau)`);
  note(c.viewer, `${tag} éclaté : la scène reste posée`);

  await say('Replie la zone B');
  const d = await shot('sf10-4-replie-zone-b', 2200);
  note(d.labels.some((l) => /⌬ 3 services/.test(l ?? '')), `${tag} zone B repliée en capsule (${d.labels.filter((l) => /⌬/.test(l ?? '')).join(' | ') || 'aucune capsule'})`);
  const screen = server.state.converse.at(-1)?.screen ?? '';
  note(/focus : RDS primaire/.test(screen) && /éclaté : oui/.test(screen),
    `${tag} l'état (focus, éclaté) suit dans le bloc ## Écran du message suivant`);

  await say('Vue de dessus');
  await shot('sf10-5-dessus', 2400);
  await say('En 2D');
  const f = await shot('sf10-6-en-2d', 1200);
  note(!f.viewer, `${tag} « en 2D » : la visionneuse SVG cadrée reprend (3D ${f.viewer})`);
  await sleep(1500);
  const u2 = (await measure(page)).universeFrames;
  await sleep(1200);
  const u3 = (await measure(page)).universeFrames;
  note(u3 > u2, `${tag} l'univers reprend après la scène (${u2} → ${u3} images)`);
  note(f.scrollW <= f.vw, `${tag} pas de défilement horizontal (${f.scrollW} ≤ ${f.vw})`);
  note(errors.length === 0, `${tag} erreurs de page : ${JSON.stringify(errors.slice(0, 3))}`);
  await browser.close();
}

const SIZES = { 1920: [1920, 1080], 390: [390, 844] };
const only = (process.env.BANC_ONLY ?? '1920,390').split(',');
for (const key of only) {
  const [width, height] = SIZES[key];
  await run({ width, height, tag: key === '390' ? 'tel' : 'pc' });
}
console.log(findings.join('\n'));
files.close();
process.exit(findings.some((f) => f.startsWith('BUG')) ? 1 : 0);
