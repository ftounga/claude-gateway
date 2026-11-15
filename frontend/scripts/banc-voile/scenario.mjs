// Banc du voile discret (F-259 / SF-259-01) sur /atelier : pose par double Échap, pose par inactivité (délai
// court forcé), retrait au clic, titre de l'onglet avant/pendant/après, rien d'interrompu derrière.
// Usage : servir le front (`npx ng serve --port 4319`), puis depuis un dossier où `npm i --no-save puppeteer-core@23.11.1`
// est fait (avec ../banc-volets/mock.mjs à côté) : `node scenario.mjs [base=http://localhost:4319] [sortie=./out]`.
// Chrome SANS --hide-scrollbars, API simulée (aucune donnée réelle). Une ligne OK/BUG par vérification ; code 1 si BUG.
import puppeteer from 'puppeteer-core';
import { mkdirSync } from 'node:fs';
import { createServer } from './mock.mjs';

const BASE = process.argv[2] ?? 'http://localhost:4319';
const OUT = process.argv[3] ?? './out';
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const findings = [];
const note = (ok, what) => { findings.push(`${ok ? 'OK  ' : 'BUG '} ${what}`); };

const server = createServer();
const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
  ignoreDefaultArgs: ['--hide-scrollbars'], args: ['--no-sandbox', '--window-size=1366,768'] });
const page = await browser.newPage();
await page.setViewport({ width: 1366, height: 768 });
page.setDefaultNavigationTimeout(120000);
// Délai court forcé (étape 5 seulement, `__shortIdle`) : le délai d'1 min du réglage devient 3 s.
await page.evaluateOnNewDocument(() => {
  const original = window.setTimeout;
  window.setTimeout = function (fn, ms, ...rest) { return original.call(this, fn, ms === 60000 && window.__shortIdle ? 3000 : ms, ...rest); };
});
await page.setRequestInterception(true);
const failed = [];
page.on('requestfailed', (req) => failed.push(new URL(req.url()).pathname));
page.on('request', (req) => {
  if (new URL(req.url()).pathname.startsWith('/api/')) { server.handle(req); } else { req.continue(); }
});
page.on('pageerror', (e) => console.log('PAGEERROR', e.message.slice(0, 300)));

await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
await page.evaluate(() => {
  localStorage.setItem('cg_token', 'x.eyJleHAiOjk5OTk5OTk5OTl9.y');
  localStorage.setItem('cg.veil', JSON.stringify({ enabled: true, delayMin: 1 }));
});
await page.goto(`${BASE}/atelier/${server.WS}`, { waitUntil: 'domcontentloaded' });
await page.waitForSelector('app-atelier-terminal', { timeout: 90000 });
await sleep(2500);

const tab = () => page.evaluate(() => ({ title: document.title,
  icon: document.querySelector('link[rel="icon"]')?.getAttribute('href') ?? null }));
const veiled = () => page.evaluate(() => !!document.querySelector('[data-testid="discreet-veil"]'));
const activity = async () => { await page.mouse.move(400 + Math.random() * 50, 300); };

// Un tour en cours : la requête de flux reste ouverte. Un marqueur prouve l'absence de rechargement.
await page.evaluate(() => {
  const cmp = window.ng.getComponent(document.querySelector('app-atelier'));
  cmp.draft.set('Demande du chantier 10'); cmp.send();
  window.__benchMarker = 'vivant';
});
await activity();
await sleep(1000);
const before = await tab();
note(!(await veiled()), `avant : pas de voile ; titre « ${before.title} »`);
await page.screenshot({ path: `${OUT}/1-avant.png` });

// Saisie en cours dans le terminal : elle doit survivre au voile, et rien ne doit s'y taper sous le voile.
await page.evaluate(() => {
  const field = [...document.querySelectorAll('app-atelier-terminal .terminal-field')].find((f) => f.offsetParent !== null);
  field.focus();
  window.__benchField = field;
});
await page.keyboard.type('brouillon');

// 1. Double Échap.
await page.keyboard.press('Escape');
await sleep(150);
await page.keyboard.press('Escape');
await sleep(400);
const during = await tab();
note(await veiled(), 'double Échap (150 ms) : voile posé');
note(during.title === 'Documentation', `pendant : titre « ${during.title} »`);
note(during.icon?.startsWith('data:image/svg+xml') && during.icon !== before.icon, 'pendant : icône neutre');
await page.screenshot({ path: `${OUT}/2-voile.png` });
const text = await page.evaluate(() => document.querySelector('[data-testid="discreet-veil"]').innerText);
note(!/claude|ng portal|atelier|edenred|cagip|verrouill/i.test(text), 'aucun nom de produit, de client ni « verrouillé » sur le voile');

// 2. Souris et clavier n'enlèvent pas.
await page.mouse.move(700, 400); await page.mouse.move(200, 600);
await page.keyboard.type('xyz'); await page.keyboard.press('Enter'); await page.keyboard.press('Escape');
await sleep(300);
note(await veiled(), 'souris qui bouge + touches (dont Entrée, Échap) : voile toujours là');

// 3. Clic : enlève, ne traverse pas.
const target = await page.evaluate(() => {
  const veil = document.querySelector('[data-testid="discreet-veil"]');
  veil.style.pointerEvents = 'none';
  const under = document.elementFromPoint(683, 400);
  veil.style.pointerEvents = '';
  window.__underClicks = 0;
  under.addEventListener('click', () => { window.__underClicks += 1; });
  under.addEventListener('mousedown', () => { window.__underClicks += 1; });
  return under.tagName + '.' + under.className;
});
await page.mouse.click(683, 400);
await sleep(400);
const after = await tab();
const state = await page.evaluate(() => ({ underClicks: window.__underClicks, marker: window.__benchMarker,
  draft: window.__benchField?.value, focused: document.activeElement === window.__benchField }));
note(!(await veiled()), 'clic : voile enlevé');
note(state.underClicks === 0, `le clic n'a pas traversé (élément dessous : ${target}, ${state.underClicks} événement)`);
note(after.title === before.title, `après : titre rétabli « ${after.title} »`);
note(after.icon === before.icon, 'après : icône rétablie');
note(state.draft === 'brouillon', `saisie conservée, rien tapé dessous : « ${state.draft} »`);
note(state.focused, 'le champ de saisie garde le focus après le retrait');
note(state.marker === 'vivant', 'aucun rechargement de la page');
note(!failed.some((p) => p.includes('/chat/stream')), 'le flux du tour en cours n\'a pas été coupé');
await page.screenshot({ path: `${OUT}/3-apres.png` });

// 4. Deux Échap espacés de plus de 500 ms.
await page.keyboard.press('Escape'); await sleep(700); await page.keyboard.press('Escape'); await sleep(300);
note(!(await veiled()), 'deux Échap à 700 ms : pas de voile');

// 5. Inactivité (délai forcé à 3 s) : une activité relance le compte, puis le voile se pose.
await page.evaluate(() => { window.__shortIdle = true; });
await activity(); await sleep(2000); await activity(); await sleep(2000);
note(!(await veiled()), 'activité avant l\'échéance : compte relancé, pas de voile');
await sleep(1800);
note(await veiled(), 'inactivité : voile posé');
note((await tab()).title === 'Documentation', 'inactivité : titre « Documentation »');
await page.screenshot({ path: `${OUT}/4-voile-inactivite.png` });
await page.mouse.click(683, 400); await sleep(300);
note(!(await veiled()) && (await tab()).title === before.title, 'clic : voile enlevé, titre rétabli');

// 6. Page publique : aucun voile.
await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' }); await sleep(2500);
await page.keyboard.press('Escape'); await sleep(100); await page.keyboard.press('Escape'); await sleep(4000);
note(!(await veiled()), '/login : ni raccourci ni inactivité');

// 7. Réglages : la section est là.
await page.goto(`${BASE}/settings`, { waitUntil: 'domcontentloaded' });
await page.waitForSelector('app-discreet-veil-settings', { timeout: 60000 }); await sleep(1500);
const settings = await page.evaluate(() => document.querySelector('app-discreet-veil-settings').innerText);
note(/Voile discret/.test(settings) && /1 min/.test(settings), 'réglages : section « Voile discret », délai 1 min lu');
const card = await page.$('app-discreet-veil-settings');
await card.screenshot({ path: `${OUT}/5-reglage.png` });

await browser.close();
for (const f of findings) { console.log(f); }
process.exit(findings.some((f) => f.startsWith('BUG')) ? 1 : 0);
