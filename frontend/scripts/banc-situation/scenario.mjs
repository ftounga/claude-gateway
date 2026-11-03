// Banc du point de situation en pop-up (F-225 / SF-225-04).
// Usage : servir le front (`npx ng serve --port 4312`), puis depuis un dossier où `npm i --no-save puppeteer-core@23.11.1`
// est fait (copier ce fichier à côté) : `node scenario.mjs [base=http://localhost:4312] [sortie=./out]`.
// Chrome SANS --hide-scrollbars, API simulée (données fictives). Vérifie : ouverture d'elle-même après une absence,
// pas à la navigation suivante, Échap / clic dehors / croix, focus piégé, onglets, aucun débordement horizontal.
// Sortie : une ligne OK/BUG par vérification et des captures ; code 1 si un BUG.
import puppeteer from 'puppeteer-core';
import { mkdirSync } from 'node:fs';

const BASE = process.argv[2] ?? 'http://localhost:4312';
const OUT = process.argv[3] ?? './out';
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const findings = [];
const note = (ok, what) => { findings.push(`${ok ? 'OK  ' : 'BUG '} ${what}`); };

const WS = 'ws-sit';
const now = Date.now();
const iso = (msAgo) => new Date(now - msAgo).toISOString();
const H = 3_600_000;

const SITUATION = {
  generatedAt: iso(0), lastActivityAt: iso(4 * H), idleHours: 4, mode: 'GUIDE', lastUserMessageAt: iso(4 * H), turnLive: false,
  chantiers: [
    { number: 11, title: 'LDIC-338 et LDIC-339 : endpoint S3 et utilisateur de transfert en prod', phaseLabel: 'Exécution',
      focus: true, done: 8, total: 9, nextRank: 8, nextStep: 'Envoyer le message à Rémi', planAwaitingValidation: false,
      diagnosisPending: false, who: 'Vous', whoDetail: 'un message à envoyer', stale: false },
    { number: 9, title: 'Runner GitLab dédié', phaseLabel: 'Exécution', focus: false, done: 5, total: 12, nextRank: 6,
      nextStep: 'Associer le rôle IAM au compte de service', planAwaitingValidation: false, diagnosisPending: false,
      who: 'Autrui', whoDetail: 'Wassim (ticket CPE)', stale: false },
    { number: 10, title: 'LDIC-323 recensement des ressources dev', phaseLabel: 'Plan', focus: false, done: 0, total: 6,
      nextRank: 1, nextStep: 'Lister les rôles IAM', planAwaitingValidation: true, diagnosisPending: false, who: null,
      whoDetail: null, stale: false },
  ],
  attentes: [
    { id: 'a1', description: 'Envoyer à Wassim la demande d’association Pod Identity', status: 'DEMANDE', qui: 'AUTRUI',
      to: 'Wassim (ticket CPE)', since: iso(4 * 24 * H), chantierNumber: 9 },
    { id: 'a2', description: 'Demander la levée du verrou Atlantis sur la MR !123', status: 'A_FAIRE', qui: 'MOI', to: null,
      since: iso(15 * H), chantierNumber: 9 },
  ],
  envois: [
    { messageId: 'm2', blockIndex: 0, kind: 'MESSAGE', subject: 'Validation des deux tickets', recipient: 'Rémi',
      proposedAt: iso(4 * H), sentAt: null, attenteKey: null, attenteStatus: null,
      excerpt: 'Bonjour Rémi, les deux tickets portent sur la prod…', chantierNumber: 11 },
    { messageId: 'm1', blockIndex: 0, kind: 'MAIL', subject: 'Relance pipeline dev', recipient: 'KPMG',
      proposedAt: iso(5 * H), sentAt: iso(4.5 * H), attenteKey: null, attenteStatus: null, excerpt: 'Bonjour…', chantierNumber: 9 },
  ],
  exchange: { request: 'Depuis quand tu dis UAT ? Je comprenais l’environnement UAT.', requestAt: iso(4 * H),
    reply: '<<essentiel>>Tu as raison, j’ai employé « UAT » pour deux choses différentes. Les deux tickets portent sur la **prod** ; « en UAT » voulait dire le statut Jira, en attente de la validation de Rémi.<</essentiel>>\n\nDétail…',
    replyAt: iso(4 * H) },
  pieces: [{ id: 'p1', path: 'notes/c11-releve.txt', sizeBytes: 4200, createdAt: iso(5 * H), messageId: 'm1' }],
  suites: [{ title: 'Envoyer le message à Rémi', request: 'Envoie le message à Rémi' }],
};

function handle(req) {
  const url = new URL(req.url());
  const p = url.pathname;
  const json = (status, body) => req.respond({ status, contentType: 'application/json', body: JSON.stringify(body) });
  if (p === '/api/me') {
    return json(200, { id: 'u1', email: 'po@x.fr', emailVerified: true, provider: 'LOCAL', role: 'ADMIN' });
  }
  if (p === '/api/workspaces' && req.method() === 'GET') {
    return json(200, [{ id: WS, name: 'data-platform', createdAt: iso(0), source: 'EMPTY', gitRepo: null,
      executionTarget: 'RUNNER', hostName: 'poste-po', hostId: 'h1' }]);
  }
  if (p === `/api/workspaces/${WS}`) {
    return json(200, { id: WS, name: 'data-platform', fileCount: 0, files: [], createdAt: iso(0), source: 'EMPTY',
      gitRepo: null, executionTarget: 'RUNNER' });
  }
  if (p === `/api/workspaces/${WS}/situation`) {
    return json(200, SITUATION);
  }
  if (p === `/api/workspaces/${WS}/chat` && req.method() === 'GET') {
    return json(200, [
      { id: 'u1', role: 'USER', content: SITUATION.exchange.request, createdAt: iso(4 * H) },
      { id: 'm2', role: 'ASSISTANT', content: SITUATION.exchange.reply, createdAt: iso(4 * H) },
    ]);
  }
  if (p === `/api/workspaces/${WS}/chat/turn`) {
    return json(200, { live: false, turnId: null, cursor: 0, startedAt: null, pending: null });
  }
  if (p.includes('/stream') || p.includes('/attach')) {
    return; // pend
  }
  return json(404, {});
}

async function run(width, height, tag) {
  const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
    ignoreDefaultArgs: ['--hide-scrollbars'], args: ['--no-sandbox', `--window-size=${width},${height}`] });
  const page = await browser.newPage();
  await page.setViewport({ width, height });
  await page.setRequestInterception(true);
  page.on('request', (req) => (new URL(req.url()).pathname.startsWith('/api/') ? handle(req) : req.continue()));
  page.on('pageerror', (e) => console.log('PAGEERROR', e.message.slice(0, 300)));
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => {
    localStorage.setItem('cg_token', 'x.eyJleHAiOjk5OTk5OTk5OTl9.y');
    localStorage.removeItem('cg.situation.visits');
  });
  const open = async () => {
    await page.goto(`${BASE}/atelier/${WS}`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('app-atelier-terminal', { timeout: 60000 });
    await sleep(2500);
  };
  const dialog = () => page.$('mat-dialog-container app-terminal-situation-dialog');
  const overflow = () => page.evaluate(() => {
    const surface = document.querySelector('.cg-situation-dialog .mat-mdc-dialog-surface');
    const r = surface?.getBoundingClientRect();
    return { page: document.documentElement.scrollWidth > window.innerWidth,
      dialog: r ? (r.left < 0 || r.right > window.innerWidth) : null,
      inner: surface ? surface.scrollWidth > surface.clientWidth + 1 : null };
  });

  // 1. Arrivée après 4 h d'absence : la pop-up s'ouvre d'elle-même, sur « L'essentiel ».
  await open();
  note(!!(await dialog()), `[${tag}] arrivée après 4 h : la pop-up s'ouvre d'elle-même`);
  const essential = await page.evaluate(() => [...document.querySelectorAll('.sitd__line')].map((l) => l.innerText.replace(/\s+/g, ' ').trim()));
  note(essential.length >= 3 && essential.length <= 5, `[${tag}] l'essentiel compte ${essential.length} lignes (3 à 5)`);
  console.log(`[${tag}] essentiel :\n  ${essential.join('\n  ')}`);
  const aria = await page.evaluate(() => {
    const c = document.querySelector('mat-dialog-container');
    return { role: c?.getAttribute('role'), modal: c?.getAttribute('aria-modal'), labelled: c?.getAttribute('aria-labelledby') };
  });
  note(aria.role === 'dialog' && aria.modal === 'true' && aria.labelled === 'sitd-title',
    `[${tag}] role=${aria.role} aria-modal=${aria.modal} aria-labelledby=${aria.labelled}`);
  let o = await overflow();
  note(!o.page && !o.dialog && !o.inner, `[${tag}] aucun débordement (page=${o.page} pop-up=${o.dialog} intérieur=${o.inner})`);
  await page.screenshot({ path: `${OUT}/${tag}-1-essentiel.png` });

  // 2. Focus piégé : 12 Tab ne sortent pas de la pop-up.
  let escaped = false;
  for (let i = 0; i < 12; i++) {
    await page.keyboard.press('Tab');
    escaped ||= await page.evaluate(() => !document.activeElement?.closest('mat-dialog-container'));
  }
  note(!escaped, `[${tag}] focus piégé (Tab x12 reste dans la pop-up)`);

  // 3. Onglet « Détail » : la carte complète.
  await page.click('#sitd-tab-detail');
  await sleep(400);
  const detail = await page.evaluate(() => document.querySelector('#sitd-panel-detail')?.innerText ?? '');
  note(/Marquer envoyé/.test(detail) && /Ce qui attend/.test(detail) && /Pièces fournies/.test(detail),
    `[${tag}] « Détail » garde la carte complète (mails, attentes, pièces)`);
  o = await overflow();
  note(!o.page && !o.dialog, `[${tag}] Détail sans débordement horizontal`);
  await page.screenshot({ path: `${OUT}/${tag}-2-detail.png` });

  // 4. Échap ferme ; le bouton de la barre la rouvre ; clic dehors ferme ; la croix ferme.
  await page.keyboard.press('Escape');
  await sleep(500);
  note(!(await dialog()), `[${tag}] Échap ferme`);
  // Les encarts d'accueil du banc (guide « premiers pas », avertissement poste) couvrent la barre : on les range.
  await page.evaluate(() => [...document.querySelectorAll('button')]
    .filter((b) => /Masquer le guide|^Compris$/.test(b.innerText.trim())).forEach((b) => b.click()));
  await sleep(400);
  await page.click('.terminal-situation-open');
  await sleep(600);
  note(!!(await dialog()), `[${tag}] le bouton de la barre la rouvre`);
  await page.mouse.click(4, height - 4);
  await sleep(500);
  note(!(await dialog()), `[${tag}] un clic dehors ferme`);
  await page.click('.terminal-situation-open');
  await sleep(600);
  const cross = await page.$('.sitd__close');
  note(!!cross, `[${tag}] le bouton de la barre la rouvre encore`);
  await cross?.click();
  await sleep(500);
  note(!!cross && !(await dialog()), `[${tag}] la croix ferme`);
  await page.screenshot({ path: `${OUT}/${tag}-3-barre.png` });

  // 5. Navigation suivante, sous le seuil (visite notée) : rien ne s'ouvre seul.
  await open();
  note(!(await dialog()), `[${tag}] navigation suivante sous le seuil : rien ne s'ouvre seul`);
  await browser.close();
}

try {
  await run(1366, 768, '1366');
  await run(360, 740, '360');
} catch (e) {
  note(false, `banc interrompu : ${e.message}`);
}
console.log(findings.join('\n'));
process.exit(findings.some((f) => f.startsWith('BUG')) ? 1 : 0);
