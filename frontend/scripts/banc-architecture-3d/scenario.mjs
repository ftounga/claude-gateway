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
  if (process.env.BANC_ZOOM === '1') {
    await zoomScenario(page, shot, tag);
    note(errors.length === 0, `${tag} erreurs de page : ${JSON.stringify(errors.slice(0, 3))}`);
    await browser.close();
    return;
  }
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
  const sy = box.y + box.h * 0.42;
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

/**
 * SF-238-15 — zoom borné et cadrage plein (`BANC_ZOOM=1`) : au repos, après un pincement fort, après dix crans de
 * molette au même endroit, après « Recentrer ». Vérifie qu'un service reste visible dans le cadre et mesure
 * l'emprise du schéma (étiquettes et icônes posées) dans le cadre de la visionneuse.
 */
async function zoomScenario(page, shot, tag) {
  const frame = () => page.evaluate(() => {
    const v = document.querySelector('app-jarvis-piece-architecture .pa');
    const c = v?.getBoundingClientRect();
    if (!c) {
      // La 3D a renoncé (repli SVG) : plus aucun service dans la visionneuse.
      return { cx: innerWidth / 2, cy: innerHeight / 2, w: innerWidth, h: innerHeight, services: [], fillW: 0, fillH: 0,
        zoom: NaN, web: null, rds: null, gone: true,
        fallback: (window.__counts ?? []).filter((k) => k.startsWith('a_2d')).join(',') };
    }
    const inside = (r) => r.right > c.left && r.left < c.right && r.bottom > c.top && r.top < c.bottom;
    const shown = (e) => getComputedStyle(e).visibility !== 'hidden' && Number(e.style.opacity || 0) > 0.05;
    const services = [...v.querySelectorAll('.jv3d-label--arch-service')].filter(shown)
      .map((e) => ({ t: e.textContent, r: e.getBoundingClientRect() })).filter((s) => inside(s.r));
    const all = [...v.querySelectorAll('.jv3d-label')].filter(shown).map((e) => e.getBoundingClientRect()).filter(inside);
    let x0 = Infinity; let x1 = -Infinity; let y0 = Infinity; let y1 = -Infinity;
    for (const r of all) {
      x0 = Math.min(x0, r.left); x1 = Math.max(x1, r.right); y0 = Math.min(y0, r.top); y1 = Math.max(y1, r.bottom);
    }
    const nav = window.ng.getComponent(document.querySelector('app-jarvis-piece-architecture')).nav();
    const pick = (name) => {
      const e = services.find((s) => s.t === name);
      return e ? { x: (e.r.left + e.r.right) / 2, y: e.r.top - 12 } : null;
    };
    const fps = v.querySelector('app-architecture-stage')?.getAttribute('data-fps') ?? '';
    return { fps, cx: c.left + c.width / 2, cy: c.top + c.height / 2, w: c.width, h: c.height,
      services: services.map((s) => s.t), fillW: all.length ? (x1 - x0) / c.width : 0,
      fillH: all.length ? (y1 - y0) / c.height : 0, zoom: nav.zoom, web: pick('Web A') ?? pick('ALB'),
      rds: pick('RDS primaire') ?? pick('Web B') };
  });
  const report = (name, f) => `${tag} ${name} : ${f.services.length} service(s) dans le cadre, `
    + `${f.fps ? `${f.fps} images/s, ` : ''}${f.fallback ? `repli ${f.fallback}, ` : ''}emprise des libellés `
    + `${Math.round(f.fillW * 100)} % × ${Math.round(f.fillH * 100)} %, zoom ×${Number(f.zoom).toFixed(2)}`
    + (f.gone ? ' — la 3D a renoncé (repli SVG)' : '');
  await shot('z1-repos', 4200);
  if (process.env.BANC_GOVERNOR === '1') {
    await page.evaluate(() => { window.__keepGovernor = true; });
  }
  // Le motif d'un éventuel repli 2D (compteurs de la mesure, sans contenu) et la cadence de la scène.
  await page.evaluate(() => {
    const store = window.ng.getComponent(document.querySelector('app-jarvis-piece-architecture')).store;
    const count = store.count.bind(store);
    window.__counts = [];
    store.count = (key, n) => { window.__counts.push(key); count(key, n); };
    // Rendu LOGICIEL (swiftshader, 2 à 17 images/s) : le gouverneur de qualité renoncerait à la 3D au premier gros
    // plan, ce qu'un vrai GPU ne fait pas. Le banc des gestes l'écarte (BANC_GOVERNOR=1 pour le garder).
    if (!window.__keepGovernor) {
      const stage = window.ng.getComponent(document.querySelector('app-architecture-stage'));
      if (stage?.engine) {
        stage.engine.governor = null;
      }
    }
  });
  const f1 = await frame();
  note(f1.services.length >= 6, report('repos', f1));
  // Pincement fort : deux doigts écartés de 80 à 560 px (×7) autour d'un service.
  const cdp = await page.createCDPSession();
  const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y], id) => ({ x, y, id })) });
  const at = f1.web ?? { x: f1.cx, y: f1.cy };
  const half = Math.min(240, f1.w / 2 - 8);
  await touch('touchStart', [[at.x - 40, at.y], [at.x + 40, at.y]]);
  for (let i = 1; i <= 20; i++) {
    const d = 40 + ((half - 40) * i) / 20;
    await touch('touchMove', [[at.x - d, at.y], [at.x + d, at.y]]);
  }
  await touch('touchEnd', []);
  await shot('z2-pince-fort', 1400);
  const f2 = await frame();
  note(f2.services.length >= 1 && f2.zoom > 1, report('pincement fort', f2));
  // Molette : dix crans vers l'avant au-dessus d'un service (après « Recentrer »).
  await page.evaluate(() => [...document.querySelectorAll('.pa__btn')].find((b) => b.textContent.trim() === 'Recentrer')?.click());
  await new Promise((r) => setTimeout(r, 1800));
  const f2b = await frame();
  const hover = f2b.rds ?? { x: f2b.cx, y: f2b.cy };
  await page.mouse.move(hover.x, hover.y);
  for (let i = 0; i < 10; i++) {
    await page.mouse.wheel({ deltaY: -100 });
    await new Promise((r) => setTimeout(r, 60));
  }
  await shot('z3-molette-x10', 1400);
  const f3 = await frame();
  note(f3.services.length >= 1 && f3.zoom > 1, report('molette ×10', f3));
  await page.evaluate(() => [...document.querySelectorAll('.pa__btn')].find((b) => b.textContent.trim() === 'Recentrer')?.click());
  await shot('z4-recentre', 2200);
  const f4 = await frame();
  note(f4.zoom === 1 && f4.services.length >= f1.services.length, report('après Recentrer', f4));
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
