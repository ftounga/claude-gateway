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
import { createServer, SCRIPTS, DATA_PLATFORM } from './mock.mjs';

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

async function run({ width, height, tag, subject, overrides = {} }) {
  const server = createServer();
  SCRIPTS.length = 0;
  for (const e of ESSAI) {
    SCRIPTS.push({ when: e.when, events: e.events(overrides) });
  }
  const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
    ignoreDefaultArgs: ['--hide-scrollbars'],
    args: ['--no-sandbox', `--window-size=${width},${height}`, '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
      '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'] });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message.slice(0, 200)));
  await page.setViewport({ width, height });
  await page.evaluateOnNewDocument(() => {
    localStorage.setItem('cg_token', 'banc');
    // La voix : rien n'est joué (aucune clé), la page continue.
    window.speechSynthesis && (window.speechSynthesis.speak = () => undefined);
  });
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
      c.scenes?.clearShow?.();
    });
  }
  note(errors.length === 0, `${tag} erreurs de page : ${JSON.stringify(errors.slice(0, 3))}`);
  await writeFile(join(out, `${tag}-requetes.txt`), server.state.log.join('\n'));
  await browser.close();
}

const mode = process.env.BANC_MODE ?? 'essai';
await run({ width: 1920, height: 1080, tag: 'pc' });
await run({ width: 1920, height: 1080, tag: 'pc-sujet', subject: DATA_PLATFORM });
await run({ width: 390, height: 844, tag: 'telephone' });
if (mode === 'essai') {
  // Rien de plus : le comportement prouvé en prod.
}
console.log(findings.join('\n'));
files.close();
process.exit(findings.some((f) => f.startsWith('BUG')) ? 1 : 0);
