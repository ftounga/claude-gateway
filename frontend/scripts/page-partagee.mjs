/**
 * Génère `partage.html` — la coquille servie aux **clients** qui ouvrent un lien de page
 * (F-110 / SF-110-06).
 *
 * Pourquoi un second document. La page partagée retire déjà le logo et pose son propre titre, mais
 * elle le fait **en JavaScript**, donc après chargement. Or Teams, Outlook et Slack composent leur
 * aperçu de lien à partir des métadonnées du HTML **sans exécuter le JavaScript** : le client voyait
 * « Claude Portal — passerelle professionnelle vers Claude » et le logo, dans le fil de discussion,
 * avant même d'avoir cliqué.
 *
 * Ce document est **dérivé d'`index.html` au moment du build**, et non maintenu à part : l'index
 * référence les bundles par des noms qui changent à chaque compilation, et une copie figée cesserait
 * de fonctionner au build suivant — en silence.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const dist = process.argv[2] ?? 'dist/frontend/browser';
const source = join(dist, 'index.html');
const target = join(dist, 'partage.html');

let html = readFileSync(source, 'utf8');

// Le titre de l'onglet et l'aperçu : rien qui désigne l'outil.
html = html.replace(/<title>[\s\S]*?<\/title>/, '<title>Page partagée</title>');

// Les métadonnées d'aperçu partent entièrement : un aperçu neutre vaut mieux qu'un aperçu réécrit,
// qui laisserait une description à maintenir en double.
html = html.replace(/\s*<meta\s+(?:property|name)="(?:og:[a-z_]+|twitter:[a-z]+|description)"[^>]*>/g, '');

// L'icône d'onglet est le logo de marque : on la retire plutôt que d'en inventer une.
html = html.replace(/\s*<link\s+rel="icon"[^>]*>/g, '');

// Les contenus de repli — celui que lisent les crawlers avant qu'Angular ne démarre, ET celui du
// <noscript> — décrivent l'application en toutes lettres. Il y en a DEUX, et le second se laisse
// oublier : c'est la garde en fin de script qui l'a signalé. Sur cette route, les deux disent
// seulement ce qu'est le lien.
const neutralFallback =
  '<div class="app-fallback"><h1>Page partagée</h1>' +
  '<p>Ce lien ouvre un document partagé avec vous. Il est privé, révocable et à durée limitée.</p>' +
  '<p class="app-fallback__editor">Si rien ne s\'affiche, activez JavaScript puis rechargez.</p>' +
  '</div>';
html = html.replace(/<div class="app-fallback">[\s\S]*?<\/div>/g, neutralFallback);

const leftovers = ['claude-portal', 'Claude Portal', 'claude-gateway'].filter((word) =>
  html.includes(word),
);
if (leftovers.length > 0) {
  // Échouer le build plutôt que livrer une coquille qui nomme encore l'outil.
  console.error(`partage.html porte encore : ${leftovers.join(', ')}`);
  process.exit(1);
}

writeFileSync(target, html);
console.log(`partage.html généré depuis ${source}`);
