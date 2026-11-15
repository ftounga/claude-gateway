// F-264 — banc « la galerie au centre » : la VRAIE page /jarvis (build de développement), API simulée
// (`../banc-jarvis-montre/mock.mjs`), Chrome sans tête SANS --hide-scrollbars. Un sujet ouvert, deux échanges longs
// (les transcriptions remplissent la colonne), puis Jarvis montre la galerie et « la deuxième ». Vérifie et capture :
// la galerie est au centre de l'écran, au-dessus des transcriptions, et ses cartes sont nettement visibles.
//
// Usage : `npx ng build --configuration development --output-path <dist>` puis, depuis un dossier où
// `npm i --no-save puppeteer-core@23.11.1` est fait (ou le front, qui l'a déjà) :
//   node <ce dossier>/scenario.mjs <dist>/browser <sortie>
// `BANC_ONLY=1366-3d-bas,1920-2d` : seulement ces passages. Code 1 si une vérification échoue.
import { createServer as http } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { extname, join } from 'node:path';
import puppeteer from 'puppeteer-core';
import { createServer, SCRIPTS, PIECES, DATA_PLATFORM } from '../banc-jarvis-montre/mock.mjs';

const [dist, out = './out'] = process.argv.slice(2);
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
await new Promise((r) => files.listen(4354, r));
await mkdir(out, { recursive: true });

// Les pièces du sujet : vidéos, photos, schéma, page — vignettes dessinées (SVG), aucun réseau.
const now = Date.now();
const KINDS = [
  ['VIDEO', 'PRODUCTION', 'Kubernetes — leçon 1', '#1A1430', '#E07B39'],
  ['VIDEO', 'PRODUCTION', 'Kubernetes — leçon 2', '#1A1430', '#E07B39'],
  ['IMAGE', 'ENTREE', 'capture-erreur-502.png', '#2A0F14', '#E5484D'],
  ['IMAGE', 'PREUVE', 'Santé du portail', '#0E2A1E', '#4CF2A0'],
  ['ARCHITECTURE', 'PRODUCTION', 'Architecture du cluster', '#0B1A2A', '#5FB4FF'],
  ['PAGE', 'PRODUCTION', 'Rapport de migration', '#1E1A12', '#F0954F'],
  ['VIDEO', 'PRODUCTION', 'Kubernetes — leçon 3', '#1A1430', '#E07B39'],
];
PIECES[DATA_PLATFORM] = KINDS.map(([kind, category, title], i) => ({ id: `p:${i + 1}`, category, kind, title,
  caption: `${title} — pièce ${i + 1}`, captionSource: 'TITLE', at: new Date(now - i * 3600_000).toISOString(),
  subjectId: DATA_PLATFORM, chantier: 3, chantierLink: 'MESSAGE', thumb: `/banc-thumb/${i}`, open: null,
  meta: kind === 'VIDEO' ? { durationSeconds: 120 } : {}, available: 'OK' }));
const thumb = (i) => {
  const [, , title, bg, fg] = KINDS[i];
  return `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="400"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">`
    + `<stop offset="0" stop-color="${bg}"/><stop offset="1" stop-color="#0B1020"/></linearGradient></defs>`
    + `<rect width="640" height="400" fill="url(#g)"/><rect x="24" y="24" width="592" height="352" fill="none" stroke="${fg}" stroke-width="3"/>`
    + `<text x="56" y="214" fill="${fg}" font-family="Inter, sans-serif" font-size="34" font-weight="600">${title}</text></svg>`;
};

const LONG = 'Le chantier avance : la migration du cluster est faite aux trois quarts, les journaux remontent dans Loki '
  + 'et la revue avec Killian est prévue demain matin. Il reste à valider les quotas et la sauvegarde. ';
SCRIPTS.length = 0;
SCRIPTS.push({ when: 'où en est', events: [['text', { delta: LONG.repeat(2) }]] });
SCRIPTS.push({ when: 'et ensuite', events: [['text', { delta: LONG.repeat(3) }]] });
SCRIPTS.push({ when: 'montre-moi les pièces', events: [['show', { action: 'galerie', pieces: [],
  scope: { subjectId: DATA_PLATFORM }, reason: 'demande' }], ['text', { delta: 'Voici les pièces du sujet. ' }]] });

async function measure(page) {
  return page.evaluate(() => {
    const gallery = document.querySelector('app-jarvis-piece-gallery');
    const cards = [...(gallery?.querySelectorAll('.pg__card') ?? [])].map((e) => {
      const r = e.getBoundingClientRect();
      const s = getComputedStyle(e);
      return { el: e, cx: r.left + r.width / 2, cy: r.top + r.height / 2, w: r.width, top: r.top, bottom: r.bottom,
        opacity: Number(s.opacity), shown: s.display !== 'none' && r.width > 4 };
    }).filter((c) => c.shown);
    const exchange = document.querySelector('.jv__exchange')?.getBoundingClientRect();
    // Ce qui est réellement au-dessus, au centre de chaque carte : la carte elle-même (ni rognée, ni recouverte) ?
    const onTop = cards.map((c) => {
      const el = document.elementFromPoint(Math.round(c.cx), Math.round(c.cy));
      return !!el && (c.el.contains(el) || !!el.closest?.('.pg__card'));
    });
    cards.forEach((c) => delete c.el);
    const panel = gallery?.querySelector('.pg__panel')?.getBoundingClientRect() ?? null;
    return { cards, onTop, exchangeTop: exchange?.top ?? null, panel: panel && { top: panel.top, bottom: panel.bottom,
      cx: panel.left + panel.width / 2, cy: panel.top + panel.height / 2 },
      vw: innerWidth, vh: innerHeight, scrollW: document.documentElement.scrollWidth,
      rendering: document.querySelector('app-jarvis-stage-3d')?.getAttribute('data-render') ?? null };
  });
}

async function run({ width, height, view, tag }) {
  const server = createServer();
  const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
    ignoreDefaultArgs: ['--hide-scrollbars'],
    args: ['--no-sandbox', `--window-size=${width},${height}`, '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
      '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'] });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message.slice(0, 200)));
  await page.setViewport({ width, height });
  await page.evaluateOnNewDocument((flat) => {
    localStorage.setItem('cg_token', 'banc');
    if (flat) localStorage.setItem('cg.jarvis.view', '2d');
    window.speechSynthesis && (window.speechSynthesis.speak = () => undefined);
  }, view === '2d');
  await page.setRequestInterception(true);
  page.on('request', (req) => {
    const p = new URL(req.url()).pathname;
    const m = p.match(/^\/api\/banc-thumb\/(\d+)$/);
    if (m) {
      req.respond({ status: 200, contentType: 'image/svg+xml', body: thumb(Number(m[1])) });
    } else if (p.startsWith('/api/')) {
      server.handle(req);
    } else {
      req.continue();
    }
  });
  await page.goto(`http://localhost:4354/jarvis?sujet=${DATA_PLATFORM}`, { waitUntil: 'load' });
  await sleep(7000);
  // Qualité : « bas » = ce que la séance du PO avait ; « normal » = le niveau haut lu par l'écran.
  await page.evaluate((v) => {
    const stage = document.querySelector('app-jarvis-stage-3d');
    const cmp = stage && window.ng.getComponent(stage);
    if (cmp && v !== '2d') cmp.level.set(v === '3d-bas' ? 'low' : 'high');
  }, view);
  const app = () => window.ng.getComponent(document.querySelector('app-jarvis'));
  for (const phrase of ['Où en est le sujet ?', 'Et ensuite ?']) {
    await page.evaluate((a, t) => (0, eval)(a)().converse(t), app.toString(), phrase);
    await sleep(2500);
  }
  await page.evaluate((a) => (0, eval)(a)().converse('Montre-moi les pièces du sujet.'), app.toString());
  await sleep(2600);
  await page.screenshot({ path: join(out, `${tag}-galerie.png`) });
  const g = await measure(page);
  const centerY = g.cards.length ? g.cards.reduce((s, c) => s + c.cy, 0) / g.cards.length : -1;
  note(g.cards.length >= 5, `${tag} galerie : ${g.cards.length} cartes affichées (rendu ${g.rendering})`);
  note(centerY > g.vh * 0.3 && centerY < g.vh * 0.7,
    `${tag} galerie au milieu de l'écran : centre des cartes à ${Math.round(centerY)} px sur ${g.vh}`);
  note(g.cards.every((c) => c.bottom <= g.vh && c.top >= 0), `${tag} toutes les cartes dans l'écran (aucune sous le pli)`);
  note(g.onTop.length > 0 && g.onTop.every(Boolean), `${tag} la galerie passe au-dessus de tout (transcriptions, cockpit) : ${JSON.stringify(g.onTop)}`);
  note(g.cards.every((c) => c.opacity >= 0.7), `${tag} cartes nettement visibles (opacités ${g.cards.map((c) => c.opacity.toFixed(2)).join(' ')})`);
  note(g.scrollW <= g.vw, `${tag} pas de défilement horizontal (${g.scrollW} ≤ ${g.vw})`);
  // « la deuxième » : même porte que la voix.
  await page.evaluate((a) => (0, eval)(a)().pieces.dispatch({ type: 'select', ref: 2 }, 'voice'), app.toString());
  await sleep(1600);
  await page.screenshot({ path: join(out, `${tag}-la-deuxieme.png`) });
  const h = await measure(page);
  note(!!h.panel && h.panel.cy > h.vh * 0.25 && h.panel.cy < h.vh * 0.65 && Math.abs(h.panel.cx - h.vw / 2) < 40,
    `${tag} « la deuxième » : la pièce au centre (${h.panel ? `${Math.round(h.panel.cx)},${Math.round(h.panel.cy)}` : 'aucun panneau'})`);
  note(h.cards.length >= 3 && h.cards.every((c) => c.opacity >= 0.45 && c.bottom <= h.vh),
    `${tag} « la deuxième » : les autres restent visibles dans l'écran (opacités ${h.cards.map((c) => c.opacity.toFixed(2)).join(' ')})`);
  note(errors.length === 0, `${tag} erreurs de page : ${JSON.stringify(errors.slice(0, 3))}`);
  await browser.close();
}

const RUNS = [];
for (const [width, height] of [[1366, 768], [1920, 1080]]) {
  for (const view of ['3d-bas', '3d-normal', '2d']) {
    RUNS.push({ width, height, view, tag: `${width}-${view}` });
  }
}
const only = process.env.BANC_ONLY?.split(',');
for (const r of RUNS.filter((x) => !only || only.includes(x.tag))) {
  await run(r);
}
console.log(findings.join('\n'));
files.close();
process.exit(findings.some((f) => f.startsWith('BUG')) ? 1 : 0);
