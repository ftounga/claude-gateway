// Banc des volets (F-246) : rejoue les parcours du PO du 03/11 sur le terminal en split.
// Usage : servir le front (`npx ng serve --port 4311`), puis depuis un dossier où `npm i --no-save puppeteer-core@23.11.1`
// est fait : `node scenario.mjs [base=http://localhost:4311] [sortie=./out]`. Chrome SANS --hide-scrollbars, API simulée
// (`mock.mjs`, aucune donnée réelle). Sortie : une ligne OK/BUG par vérification ; code 1 si un BUG.
import puppeteer from 'puppeteer-core';
import { mkdirSync } from 'node:fs';
import { createServer } from './mock.mjs';

const BASE = process.argv[2] ?? 'http://localhost:4311';
const OUT = process.argv[3] ?? './out';
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const findings = [];
const note = (ok, what) => { findings.push(`${ok ? 'OK  ' : 'BUG '} ${what}`); };

async function run(width, height, tag) {
  const server = createServer();
  const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
    ignoreDefaultArgs: ['--hide-scrollbars'], args: ['--no-sandbox', `--window-size=${width},${height}`] });
  const page = await browser.newPage();
  await page.setViewport({ width, height });
  await page.setRequestInterception(true);
  page.on('request', (req) => {
    if (new URL(req.url()).pathname.startsWith('/api/')) {
      server.handle(req);
    } else {
      req.continue();
    }
  });
  page.on('pageerror', (e) => console.log('PAGEERROR', e.message.slice(0, 300)));
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => localStorage.setItem('cg_token', 'x.eyJleHAiOjk5OTk5OTk5OTl9.y'));
  const open = async () => {
    await page.goto(`${BASE}/atelier/${server.WS}`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('app-atelier-terminal', { timeout: 60000 });
    await sleep(2500);
  };
  // Ce que l'écran montre : le chantier de chaque volet (lu dans l'écran) et les fils rendus.
  const screen = () => page.evaluate(() => {
    const host = document.querySelector('app-atelier');
    const cmp = window.ng?.getComponent(host);
    const panes = [...document.querySelectorAll('.lane-split__pane')].filter((p) => !p.hidden);
    const text = (el) => (el?.innerText ?? '').replace(/\s+/g, ' ');
    const map = document.querySelector('app-terminal-pane-map');
    return {
      left: cmp?.chatLane?.() ?? null,
      right: cmp?.splitPane?.()?.chantier ?? null,
      heads: [...document.querySelectorAll('.terminal-pane-head__title')].map((h) => text(h)),
      paneTexts: [0, 1].map((k) => { const p = [...document.querySelectorAll(".lane-split__pane")].find((x) => !x.hidden && x.classList.contains("lane-split__pane--right") === (k === 1)); return p ? text(p).slice(0, 4000) : ""; }),
      map: map ? text(map) : null,
      overflowX: document.documentElement.scrollWidth > window.innerWidth,
      reopen: [...document.querySelectorAll('button')].filter((b) => /Chantier 11|Ch\.11/.test(b.innerText)
        || /11/.test(b.getAttribute('aria-label') ?? '')).length,
    };
  });
  const shot = (name) => page.screenshot({ path: `${OUT}/${tag}-${name}.png` });
  const cmpCall = (fn) => page.evaluate(fn);
  // Les fils ne se mélangent jamais : à chaque étape, un volet ne montre que les demandes de son chantier,
  // et la barre du bas du volet nomme son chantier.
  const leaks = (st) => {
    const own = [st.left, st.right];
    const bad = st.paneTexts.map((t, i) => [...t.matchAll(/(?:Question|Demande) du chantier (\d+)/g)]
      .map((m) => Number(m[1])).filter((n) => n !== own[i])).flat();
    const chips = st.paneTexts.map((t) => (t.match(/Chantier (\d+) · Exécution/) ?? [])[1])
      .map((n, i) => (n === undefined || Number(n) === own[i] ? null : `volet ${i}: barre=${n} fil=${own[i]}`))
      .filter(Boolean);
    if (bad.length || chips.length) {
      note(false, `[${tag}] fils mélangés : ${[...bad.map((n) => 'demande de ' + n), ...chips].join(' ; ')}`);
    }
  };

  await open();
  let s = await screen(); leaks(s);
  await shot('1-arrivee');
  note(s.left === 10 && s.right === 11, `[${tag}] arrivée : gauche=${s.left} droite=${s.right} (attendu 10 | 11)`);

  // Lancer les deux : une demande dans chaque volet.
  await cmpCall(() => {
    const cmp = window.ng.getComponent(document.querySelector('app-atelier'));
    cmp.draft.set('Go chantier 10'); cmp.send();
    const pane = window.ng.getComponent(document.querySelector('app-terminal-lane-pane'));
    pane.draft.set('Go chantier 11'); pane.send();
  });
  await sleep(800);
  const sent = server.state.sent.map((x) => `${x.message}→${x.chantier}`).join(', ');
  note(server.state.sent.some((x) => x.message === 'Go chantier 10' && x.chantier === 10)
    && server.state.sent.some((x) => x.message === 'Go chantier 11' && x.chantier === 11),
    `[${tag}] lancer les deux : envois ${sent}`);

  // Ouvrir un 3e chantier (9) : « Mettre au focus » au tableau du volet de gauche — le geste du PO.
  await cmpCall(() => {
    const terms = [...document.querySelectorAll('app-atelier-terminal')];
    window.ng.getComponent(terms[0]).onSwitchChantier(9);
  });
  await sleep(2500);
  s = await screen(); leaks(s);
  await shot('2-ouvre-9');
  note(s.right === 11, `[${tag}] ouvrir 9 : le chantier 11 garde son volet de droite (droite=${s.right})`);
  note(s.left === 9 || s.right === 9, `[${tag}] ouvrir 9 : 9 est affiché (gauche=${s.left} droite=${s.right})`);
  note(s.left !== 10 || true, `[${tag}] ouvrir 9 : 10 ${s.left === 10 ? 'reste à gauche' : s.right === 10 ? 'est passé à DROITE' : 'a DISPARU de l\'écran'}`);
  const mixed = s.paneTexts.some((t, i) => (i === 0 ? s.left : s.right) !== 9 && /Réponse du chantier 9/.test(t));
  note(!mixed, `[${tag}] ouvrir 9 : aucun volet ne montre le fil de 9 sans être le volet de 9`);
  note(s.map !== null, `[${tag}] repère « qui est où » visible : ${s.map ?? 'aucun'}`);

  // Fermer le volet de droite, puis rouvrir ce chantier.
  const closedChantier = s.right;
  await page.evaluate(() => document.querySelector('.lane-split__pane--right .terminal-pane-head__close')?.click());
  await sleep(1200);
  s = await screen(); leaks(s);
  note(s.right === null, `[${tag}] fermer le volet de droite (chantier ${closedChantier}) : droite=${s.right}`);
  // Rouvrir : un bouton du repère « à droite » pour ce chantier ?
  const reopened = await page.evaluate((n) => {
    const btn = [...document.querySelectorAll('app-terminal-pane-map button')]
      .find((b) => b.getAttribute('data-chantier') === String(n) && b.getAttribute('data-side') === 'right');
    if (!btn) { return false; }
    btn.click(); return true;
  }, closedChantier);
  await sleep(1500);
  s = await screen(); leaks(s);
  await shot('3-rouvre');
  note(reopened && s.right === closedChantier,
    `[${tag}] rouvrir le chantier ${closedChantier} : ${reopened ? `droite=${s.right}` : 'AUCUN moyen à l\'écran'}`);

  // Recharger la page : la disposition revient telle quelle.
  const before = { left: s.left, right: s.right };
  server.state.live[11] = false; // le tour de 11 a fini entre-temps
  await open();
  s = await screen(); leaks(s);
  await shot('4-recharge');
  note(s.left === before.left && s.right === before.right,
    `[${tag}] recharger : avant ${before.left} | ${before.right}, après ${s.left} | ${s.right}`);

  // Un chantier s'ouvre côté serveur sans geste ici (autre appareil, agent) : rien ne bouge.
  server.state.open.push(12); server.state.titles[12] = 'Ouvert ailleurs'; server.state.focus = 12;
  server.state.history[12] = []; server.state.live[12] = false; server.state.startedAt[12] = Date.now();
  await cmpCall(() => window.ng.getComponent(document.querySelector('app-atelier-terminal')).loadJourney('ws-1'));
  await sleep(2000);
  s = await screen(); leaks(s);
  note(s.left === before.left && s.right === before.right,
    `[${tag}] chantier ouvert ailleurs (focus serveur → 12) : volets inchangés (${s.left} | ${s.right})`);

  // « Ouvrir le chantier » depuis le volet de gauche : il arrive à DROITE, sa reprise part dans SON fil.
  server.state.sent = [];
  await cmpCall(() => window.ng.getComponent(document.querySelector('app-atelier-terminal'))
    .onJourneyGesture('accept-guided'));
  await sleep(2500);
  s = await screen(); leaks(s);
  await shot('5-nouveau-chantier');
  const sentNew = server.state.sent.map((x) => `« ${x.message} »→${x.chantier}`).join(', ');
  note(s.left === before.left && s.right === 13,
    `[${tag}] ouvrir un chantier depuis la gauche : gauche=${s.left} droite=${s.right} (attendu ${before.left} | 13)`);
  note(server.state.sent.length > 0 && server.state.sent.every((x) => x.chantier === 13),
    `[${tag}] la reprise du nouveau chantier part dans son fil : ${sentNew || 'rien envoyé'}`);

  // Basculer de volet pendant un tour : le chantier de gauche tourne, on l'échange avec la droite par la carte.
  server.state.live[before.left] = true;
  const leftBefore = s.left; const rightBefore = s.right;
  await page.evaluate((n) => [...document.querySelectorAll('app-terminal-pane-map button')]
    .find((b) => b.getAttribute('data-chantier') === String(n) && b.getAttribute('data-side') === 'right')?.click(), leftBefore);
  await sleep(2500);
  s = await screen(); leaks(s);
  await shot('6-echange-pendant-tour');
  note(s.left === rightBefore && s.right === leftBefore,
    `[${tag}] échanger pendant un tour : ${leftBefore} | ${rightBefore} → ${s.left} | ${s.right}`);
  const attachRight = server.state.log.filter((l) => l.includes('/chat/attach') && l.includes(`chantier=${leftBefore}`)).length;
  note(attachRight > 0, `[${tag}] le tour de ${leftBefore} est suivi dans son nouveau volet (rebranchements : ${attachRight})`);

  // Sur téléphone : passer d'un onglet à l'autre ne change ni les chantiers ni les fils.
  if (width < 820) {
    await page.evaluate(() => document.querySelectorAll('.lane-tab')[1]?.click());
    await sleep(600);
    await page.evaluate(() => document.querySelectorAll('.lane-tab')[0]?.click());
    await sleep(600);
    const t = await screen();
    note(t.left === s.left && t.right === s.right, `[${tag}] onglets : volets inchangés (${t.left} | ${t.right})`);
  }

  // Les fils ne se mélangent jamais : chaque volet ne montre que les demandes de son chantier.
  s = await screen(); leaks(s);
  const own = [s.left, s.right];
  const finalLeaks = s.paneTexts.map((t, i) => [...t.matchAll(/(?:Question|Demande) du chantier (\d+)/g)]
    .map((m) => Number(m[1])).filter((n) => n !== own[i])).flat();
  note(finalLeaks.length === 0, `[${tag}] aucun fil d'un autre chantier dans un volet${finalLeaks.length ? ' : ' + finalLeaks.join(',') : ''}`);

  note(!s.overflowX, `[${tag}] aucun défilement horizontal de la page`);
  await shot('5-fin');
  await browser.close();
}

await run(1366, 768, 'pc');
await run(360, 740, 'tel');
console.log(findings.join('\n'));
process.exit(findings.some((f) => f.startsWith('BUG')) ? 1 : 0);
