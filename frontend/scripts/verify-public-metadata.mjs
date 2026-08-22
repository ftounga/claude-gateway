/**
 * F-29 SF-29-02 — Vérificateur des signaux d'indexation publics.
 *
 * `index.html`, `robots.txt` et `sitemap.xml` sont hors de portée de Karma, qui teste
 * des composants et non le document hôte. Ce script tient lieu de test automatisé :
 * il échoue avec un code de sortie non nul dès qu'un signal attendu disparaît.
 *
 * Usage : node scripts/verify-public-metadata.mjs [racine]
 *   - sans argument : vérifie les sources (src/index.html + public/)
 *   - avec `dist/frontend/browser` : vérifie le build produit
 */
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const DIST = process.argv[2];
const html = readFileSync(DIST ? join(DIST, 'index.html') : 'src/index.html', 'utf8');
const robotsPath = DIST ? join(DIST, 'robots.txt') : 'public/robots.txt';
const sitemapPath = DIST ? join(DIST, 'sitemap.xml') : 'public/sitemap.xml';

// Routes authentifiées relevées dans app.routes.ts — toute nouvelle route privée
// doit être ajoutée ici ET dans robots.txt, sinon la vérification échoue.
const PRIVATE_ROUTES = [
  '/chat', '/atelier', '/documents', '/ask', '/templates',
  '/billing', '/reports', '/settings', '/profile', '/admin', '/onboarding', '/auth/',
];
const PUBLIC_URLS = ['/', '/login', '/register'];
// Lexique qui fait classer le domaine en « anonymizer / proxy avoidance ».
const FORBIDDEN = /proxy|unrestricted|no limits|bypass|unblock|anonymous/i;

const failures = [];
const check = (ok, message) => { if (!ok) failures.push(message); };

// ---- index.html ----
check(/<title>Claude Portal —/.test(html), 'index.html : <title> « Claude Portal » absent');

const description = html.match(/<meta name="description" content="([^"]+)"/);
check(!!description, 'index.html : meta description absente');
if (description) {
  check(
    description[1].length <= 160,
    `index.html : meta description de ${description[1].length} caractères (max 160)`,
  );
}

for (const tag of ['og:type', 'og:title', 'og:description', 'og:url', 'og:image', 'og:locale']) {
  check(html.includes(`property="${tag}"`), `index.html : balise Open Graph ${tag} absente`);
}
check(/<link rel="canonical" href="https:\/\/portal\.ng-itconsulting\.com\//.test(html),
  'index.html : lien canonique absent ou incorrect');

// Le contenu de repli doit être À L'INTÉRIEUR de <app-root> : Angular vide l'élément
// hôte au démarrage. Placé après, il resterait affiché en doublon sous l'application.
const appRoot = html.match(/<app-root>([\s\S]*?)<\/app-root>/);
check(!!appRoot, 'index.html : <app-root> introuvable');
if (appRoot) {
  check(/<h1[\s>]/.test(appRoot[1]), 'index.html : aucun <h1> dans le contenu de repli de <app-root>');
  check(appRoot[1].replace(/<[^>]+>/g, ' ').trim().length >= 300,
    'index.html : contenu de repli trop court pour un moteur de classification (< 300 caractères)');
}
check(/<noscript>/.test(html), 'index.html : bloc <noscript> absent');

const visible = html.replace(/<!--[\s\S]*?-->/g, '');
check(!FORBIDDEN.test(visible), 'index.html : terme du lexique de contournement présent');

// ---- robots.txt ----
check(existsSync(robotsPath), `${robotsPath} : fichier absent`);
if (existsSync(robotsPath)) {
  const robots = readFileSync(robotsPath, 'utf8');
  check(/^User-agent: \*/m.test(robots), 'robots.txt : directive User-agent absente');
  check(/^Allow: \/$/m.test(robots), 'robots.txt : « Allow: / » absent (le site doit rester indexable)');
  check(/^Sitemap: https:\/\/portal\.ng-itconsulting\.com\/sitemap\.xml$/m.test(robots),
    'robots.txt : directive Sitemap absente ou incorrecte');
  for (const route of PRIVATE_ROUTES) {
    check(robots.includes(`Disallow: ${route}`), `robots.txt : route privée ${route} non exclue`);
  }
}

// ---- sitemap.xml ----
check(existsSync(sitemapPath), `${sitemapPath} : fichier absent`);
if (existsSync(sitemapPath)) {
  const sitemap = readFileSync(sitemapPath, 'utf8');
  check(sitemap.startsWith('<?xml'), 'sitemap.xml : déclaration XML absente');
  check(sitemap.includes('http://www.sitemaps.org/schemas/sitemap/0.9'),
    'sitemap.xml : espace de noms sitemap absent');

  const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
  check(locs.length > 0, 'sitemap.xml : aucune URL listée');
  for (const loc of locs) {
    const path = loc.replace('https://portal.ng-itconsulting.com', '') || '/';
    check(PUBLIC_URLS.includes(path), `sitemap.xml : URL non publique listée (${loc})`);
  }
  for (const url of PUBLIC_URLS) {
    check(locs.some((l) => l.endsWith(url)), `sitemap.xml : URL publique ${url} manquante`);
  }
  for (const [, date] of sitemap.matchAll(/<lastmod>([^<]+)<\/lastmod>/g)) {
    check(/^\d{4}-\d{2}-\d{2}$/.test(date), `sitemap.xml : lastmod au mauvais format (${date})`);
  }
}

// ---- verdict ----
const scope = DIST ? `build (${DIST})` : 'sources';
if (failures.length > 0) {
  console.error(`\n✖ Signaux d'indexation — ${failures.length} échec(s) sur les ${scope} :\n`);
  failures.forEach((f) => console.error(`  - ${f}`));
  process.exit(1);
}
console.log(`✔ Signaux d'indexation vérifiés sur les ${scope} (index.html, robots.txt, sitemap.xml).`);
