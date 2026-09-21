/**
 * La coquille des pages partagées ne nomme jamais l'outil (F-110 / SF-110-06).
 *
 * Ce test tourne sur le fichier RÉELLEMENT produit par le build : c'est la seule façon de voir ce
 * qu'un client reçoit. Le correctif Angular (titre, logo) n'agit qu'après exécution du JavaScript,
 * or Teams, Outlook et Slack composent leur aperçu de lien sans l'exécuter.
 */
import { readFileSync, existsSync } from 'node:fs';
import assert from 'node:assert/strict';

const path = process.argv[2] ?? 'dist/frontend/browser/partage.html';
assert.ok(existsSync(path), `${path} doit être généré par le build`);
const html = readFileSync(path, 'utf8');

for (const forbidden of ['claude-portal', 'Claude Portal', 'claude-gateway', 'Anthropic']) {
  assert.ok(!html.includes(forbidden), `la coquille ne doit pas contenir « ${forbidden} »`);
}
assert.ok(html.includes('<title>Page partagée</title>'), 'le titre doit être neutre');
assert.ok(!/og:|twitter:/.test(html), "aucune métadonnée d'aperçu ne doit subsister");
assert.ok(!/rel="icon"/.test(html), "aucune icône de marque ne doit subsister");
// Et ce qui doit rester : la page doit toujours démarrer.
assert.ok(html.includes('<app-root>'), "la coquille doit toujours démarrer l'application");
assert.ok(/<script[^>]+src="main/.test(html), 'la coquille doit charger le bundle');

console.log('partage.html : neutre, et fonctionnel');
