/**
 * F-152 SF-152-03 — Garde : le manifest PWA doit être servi avec le bon type MIME.
 *
 * La vérité, c'est le RENDU SERVI. En prod, `GET /manifest.webmanifest` tombait dans
 * `application/octet-stream` (nginx ne mappe pas l'extension `.webmanifest`), ce qui fait échouer
 * la fabrication du WebAPK sur Android/Chrome — l'app « s'installe » sans apparaître dans le tiroir.
 *
 * Karma teste des composants Angular, pas le service statique. Comme `verify-public-metadata.mjs`
 * (F-29) le fait pour robots.txt/sitemap.xml, ce script tient lieu de test automatisé : il inspecte
 * le `nginx.conf` RÉELLEMENT embarqué dans l'image `frontend` (copié par le Dockerfile) et échoue
 * avec un code de sortie non nul si le manifest n'est plus servi en `application/manifest+json`.
 *
 * Usage : node scripts/verify-nginx-mime.mjs [chemin-nginx.conf]
 */
import { readFileSync, existsSync } from 'node:fs';

const confPath = process.argv[2] ?? 'nginx.conf';

const failures = [];
const check = (ok, message) => { if (!ok) failures.push(message); };

check(existsSync(confPath), `${confPath} : fichier introuvable`);

if (existsSync(confPath)) {
  const conf = readFileSync(confPath, 'utf8');

  // Isoler le bloc `location = /manifest.webmanifest { ... }`. On s'arrête à l'accolade fermante
  // en début de ligne (celle du `location`), et non au `}` interne de `types { }`.
  const block = conf.match(/location\s*=\s*\/manifest\.webmanifest\s*\{([\s\S]*?)\n\s*\}/);
  check(!!block, 'nginx.conf : aucun `location = /manifest.webmanifest` (le manifest tomberait en application/octet-stream)');

  if (block) {
    const body = block[1];
    check(
      /default_type\s+application\/manifest\+json\s*;/.test(body),
      'nginx.conf : le manifest n\'est pas servi en `application/manifest+json` (default_type manquant ou incorrect)',
    );
    // Sans `types { }` vide, la table MIME héritée du serveur reprend le dessus sur default_type.
    check(
      /types\s*\{\s*\}/.test(body),
      'nginx.conf : bloc `types { }` vide manquant — default_type ne s\'appliquerait pas',
    );
    // Le manifest n'a pas de hash dans son nom : il doit rester rafraîchissable.
    check(
      !/immutable/.test(body),
      'nginx.conf : le manifest ne doit PAS être en cache `immutable` (nom sans hash, doit rester rafraîchissable)',
    );
  }
}

if (failures.length > 0) {
  console.error(`\n✖ Type MIME du manifest — ${failures.length} échec(s) sur ${confPath} :\n`);
  failures.forEach((f) => console.error(`  - ${f}`));
  process.exit(1);
}
console.log(`✔ Manifest PWA servi en application/manifest+json (vérifié sur ${confPath}).`);
