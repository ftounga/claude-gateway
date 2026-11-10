// F-253 — banc « Jarvis montre vraiment » : la VRAIE page /jarvis (build de développement), API simulée (`mock.mjs`),
// Chrome sans tête SANS --hide-scrollbars. Rejoue l'essai du PO du 07/11 (phrases réelles) et vérifie ce qui APPARAÎT.
//
// Usage : `npx ng build --configuration development --output-path <dist>` puis, depuis un dossier où
// `npm i --no-save puppeteer-core@23.11.1` est fait :
//   node <ce dossier>/scenario.mjs <dist>/browser <sortie>
// Sortie : une ligne OK/BUG par vérification, captures PNG, `requetes.txt` ; code 1 si un BUG.
import { createServer as http } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { extname, join } from 'node:path';
import puppeteer from 'puppeteer-core';
import { createServer, SCRIPTS, DATA_PLATFORM, AGENOR } from './mock.mjs';

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
await new Promise((r) => files.listen(4353, r));
await mkdir(out, { recursive: true });

/** Les ordres que le cerveau rapide émet (forme de `JarvisShowCommand`), par phrase de l'essai. */
const ESSAI = [
  { when: 'liste de mes clients', label: 'clients',
    events: (s) => s.clients ?? [['show', { action: 'remonter', pieces: [], reason: 'demande' }],
      ['text', { delta: 'Voici. ' }]] },
  { when: 'liste de mes sujets', label: 'sujets',
    events: (s) => s.sujets ?? [['text', { delta: 'Les chantiers en exécution y figurent. ' }]] },
  { when: 'vidéo chez le client cagip', label: 'videos-cagip',
    events: (s) => s.videos ?? [['show', { action: 'galerie', pieces: [], scope: { type: 'video' },
      reason: 'demande' }], ['text', { delta: 'Je vous affiche la galerie des vidéos de CAGIP. ' }]] },
];

async function measure(page) {
  return page.evaluate(() => {
    const visible = (el) => {
      if (!el) return false;
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return r.width > 4 && r.height > 4 && s.visibility !== 'hidden' && s.display !== 'none' && Number(s.opacity) > 0.05;
    };
    const app = document.querySelector('app-jarvis');
    const ng = window.ng;
    const cmp = app && ng ? ng.getComponent(app) : null;
    const stage = document.querySelector('app-jarvis-stage-3d');
    const stageCmp = stage && ng ? ng.getComponent(stage) : null;
    const gallery = document.querySelector('app-jarvis-piece-gallery');
    const galleryItems = gallery ? [...gallery.querySelectorAll('*')].filter((e) => /piece|carte|card/i.test(e.className?.toString?.() ?? '') && visible(e)).length : 0;
    const spoken = cmp?.spoken?.() ?? '';
    const labels = [...document.querySelectorAll('body *')].filter((e) => e.children.length === 0 && visible(e)
      && /CAGIP|Edenred|agenor|helios|data-platform/i.test(e.textContent ?? '')
      && !(spoken && spoken.includes(e.textContent.trim()))).map((e) => e.textContent.trim());
    const focus = cmp?.localShow?.() ?? cmp?.scenes?.focus?.() ?? null;
    return {
      rendering: stageCmp?.rendering?.() ?? null,
      canvas: [...document.querySelectorAll('canvas')].filter(visible).length,
      galleryVisible: visible(gallery?.firstElementChild ?? null),
      galleryItems,
      montrer: focus ? true : false,
      universeNames: [...new Set(labels)].slice(0, 12),
      spoken: cmp?.spoken?.() ?? null,
      notice: cmp?.notice?.() ?? null,
      scrollW: document.documentElement.scrollWidth, w: window.innerWidth,
    };
  });
}

/** SF-253-07 — le sujet ouvert atteint la scène et le cockpit ; une réponse rapide allume ce qu'elle cite ; étiquettes dans l'écran. */
async function verifierScene(page, tag, subject, width) {
  const app = () => window.ng.getComponent(document.querySelector('app-jarvis'));
  if (subject) {
    const seen = await page.evaluate((a) => {
      const c = (0, eval)(a)();
      const el = document.querySelector('app-jarvis-cockpit');
      return { bus: c.scenes.subjectId(), stage: c.stageSignals().subjectId,
        cockpit: el ? window.ng.getComponent(el).signals().subjectId : null };
    }, app.toString());
    note(seen.bus === subject && seen.stage === subject && seen.cockpit === subject,
      `${tag} sujet ouvert connu de la scène, de la 3D et du cockpit (Journée du sujet) : ${JSON.stringify(seen)}`);
  }
  await page.evaluate((a) => void (0, eval)(a)().converse('Où en est agenor ?'), app.toString());
  const lit = await page.waitForFunction((a) => {
    const keys = (0, eval)(a)().scenes.state().lit.map((l) => l.node.key);
    return keys.length ? keys : null;
  }, { timeout: 4000 }, app.toString()).then((h) => h.jsonValue()).catch(() => []);
  note(lit.includes(AGENOR), `${tag} réponse rapide citant « agenor » : nœud allumé = ${JSON.stringify(lit)}`);
  await sleep(600);
  await page.screenshot({ path: join(out, `${tag}-cite-agenor.png`) });
  if (width < 768) {
    const labels = await page.evaluate((w) => [...document.querySelectorAll('app-jarvis-constellation svg text')]
      .map((t) => ({ r: t.getBoundingClientRect(), name: t.textContent.trim() })).filter(({ r }) => r.width > 0)
      .map(({ r, name }) => ({ name, out: r.left < 0 || r.right > w })), width);
    const outside = labels.filter((l) => l.out).map((l) => l.name);
    note(labels.length > 0 && outside.length === 0,
      `${tag} étiquettes de la constellation dans l'écran (${labels.length} vues, hors écran : ${JSON.stringify(outside)})`);
  }
}

/** SF-253-08 — « Ce que je sais » en 2D, rendez-vous dit surligné, mesure du rendu partie à la fermeture (une fois). */
async function verifierPied(page, server, tag) {
  const opened = await page.evaluate(() => {
    const b = document.querySelector('.jv__wake .jv__state-open');
    b?.click();
    return !!b;
  });
  await sleep(500);
  const panel = await page.evaluate(() => !!document.querySelector('app-jarvis-state-panel'));
  note(opened && panel, `${tag} « Ce que je sais » en 2D : bouton=${opened}, volet ouvert=${panel}`);
  await page.evaluate(() => {
    const c = window.ng.getComponent(document.querySelector('app-jarvis'));
    c.stateOpen.set(false);
    c.agendaView.set({ mode: 'jour', date: new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Paris' }).format(new Date()) });
    c.state.set('parole');
    c.playingSentence.set('Ensuite, vous voyez Zahi.');
    window.ng.applyChanges(document.querySelector('app-jarvis'));
  });
  await sleep(800);
  const cited = await page.evaluate(() => ({ items: [...document.querySelectorAll('.jag__item--cited')].map((e) => e.textContent.trim().slice(0, 40)),
    blocks: document.querySelectorAll('.jag__block--cited').length }));
  await page.screenshot({ path: join(out, `${tag}-frise-surlignee.png`) });
  note(cited.items.length === 1 && cited.items[0].includes('Revue Zahi') && cited.blocks === 1,
    `${tag} frise : rendez-vous dit surligné ${JSON.stringify(cited)}`);
  await page.evaluate(() => {
    const c = window.ng.getComponent(document.querySelector('app-jarvis'));
    c.agendaView.set(null);
    window.dispatchEvent(new Event('pagehide'));
    window.dispatchEvent(new Event('pagehide'));
  });
  await sleep(800);
  const sent = server.state.usage.filter((u) => u.event === 'RENDER');
  note(sent.length === 1, `${tag} mesure du rendu envoyée à pagehide, une fois (${sent.length} envoi, mode=${sent[0]?.mode})`);
}

async function run({ width, height, tag, subject, view2d = false, overrides = {} }) {
  const server = createServer();
  SCRIPTS.length = 0;
  for (const e of ESSAI) {
    SCRIPTS.push({ when: e.when, events: e.events(overrides) });
  }
  SCRIPTS.push({ when: 'où en est agenor', events: [['text', { delta: 'Le chantier agenor avance bien, Monsieur. ' }]] });
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
    if (flat) localStorage.setItem('cg.jarvis.view', '2d'); // le choix « vue 2D » de l'appareil
    // La voix : rien n'est joué (aucune clé), la page continue.
    window.speechSynthesis && (window.speechSynthesis.speak = () => undefined);
  }, view2d);
  await page.setRequestInterception(true);
  page.on('request', (req) => {
    if (new URL(req.url()).pathname.startsWith('/api/')) {
      server.handle(req);
    } else {
      req.continue();
    }
  });
  await page.goto(`http://localhost:4353/jarvis${subject ? `?sujet=${subject}` : ''}`, { waitUntil: 'load' });
  await sleep(7000);
  const rest = await measure(page);
  await page.screenshot({ path: join(out, `${tag}-0-repos.png`) });
  note(rest.canvas > 0 || rest.rendering === '2d', `${tag} repos : rendu=${rest.rendering} canvas=${rest.canvas}`);
  note(rest.universeNames.length > 0, `${tag} repos : noms de l'univers visibles = ${JSON.stringify(rest.universeNames)}`);
  note(rest.scrollW <= rest.w, `${tag} repos : pas de défilement horizontal (${rest.scrollW} ≤ ${rest.w})`);
  for (const e of ESSAI) {
    const phrase = e.when === 'vidéo chez le client cagip'
      ? 'Vidéo chez le client CAGIP. Est-ce que tu peux me montrer ces vidéos-là ?'
      : e.when === 'liste de mes clients' ? 'Montre-moi la liste de mes clients.'
        : 'Montre-moi la liste de mes sujets en cours.';
    await page.evaluate((t) => window.ng.getComponent(document.querySelector('app-jarvis')).converse(t), phrase);
    await sleep(3500);
    const after = await measure(page);
    await page.screenshot({ path: join(out, `${tag}-${e.label}.png`) });
    const shown = after.galleryVisible || after.montrer;
    note(shown, `${tag} « ${phrase} » : quelque chose apparaît (galerie=${after.galleryVisible}, montrer=${after.montrer}, `
      + `éléments=${after.galleryItems}, noms=${JSON.stringify(after.universeNames)}) ; dit=${JSON.stringify(after.spoken)}`);
    await page.evaluate(() => {
      const c = window.ng.getComponent(document.querySelector('app-jarvis'));
      c.pieces?.reset?.();
      c.clearLocalShow?.();
      c.scenes?.dismissShow?.();
    });
  }
  await verifierScene(page, tag, subject, width);
  if (view2d) {
    await verifierPied(page, server, tag);
  }
  note(errors.length === 0, `${tag} erreurs de page : ${JSON.stringify(errors.slice(0, 3))}`);
  await writeFile(join(out, `${tag}-requetes.txt`), server.state.log.join('\n'));
  await browser.close();
}

// BANC_MODE=essai : le comportement prouvé en prod le 07/11. BANC_MODE=apres : les ordres que la passerelle émet
// désormais (F-253) — ce que l'écran en fait est vérifié de la même façon.
const mode = process.env.BANC_MODE ?? 'essai';
const univers = (view) => [['show', { action: 'univers', pieces: [], reason: 'demande', view }]];
const overrides = mode === 'apres' ? {
  clients: [...univers('clients'), ['text', { delta: 'Vos deux clients, CAGIP et Edenred. ' }]],
  sujets: [...univers('sujets'), ['text', { delta: 'Vos trois sujets, Monsieur. ' }]],
  videos: [['show', { action: 'galerie', pieces: [], reason: 'demande',
    scope: { client: 'c-cagip', clientName: 'CAGIP', type: 'video' } }],
  ['text', { delta: 'Les vidéos de CAGIP, Monsieur. ' }]],
} : {};
const RUNS = [
  { width: 1920, height: 1080, tag: 'pc' },
  { width: 1920, height: 1080, tag: 'pc-sujet', subject: DATA_PLATFORM },
  { width: 390, height: 844, tag: 'telephone' },
  { width: 390, height: 844, tag: 'telephone-2d', view2d: true },
];
// `BANC_ONLY=telephone-2d,pc` : seulement ces passages (poste chargé).
const only = process.env.BANC_ONLY?.split(',');
for (const r of RUNS.filter((x) => !only || only.includes(x.tag))) {
  await run({ ...r, overrides });
}
console.log(findings.join('\n'));
files.close();
process.exit(findings.some((f) => f.startsWith('BUG')) ? 1 : 0);
