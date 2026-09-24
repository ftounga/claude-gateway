# Mini-spec — F-152 / SF-152-03 — Servir le `manifest.webmanifest` avec le bon type MIME

## Identifiant

`F-152 / SF-152-03`

## Feature parente

`F-152` — PWA installable (icône sur l'écran d'accueil)

## Statut

`ready`

## Date de création

2026-09-25

## Branche Git

`feat/SF-152-03-mime-manifest`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Servir `/manifest.webmanifest` avec le type MIME `application/manifest+json` (au lieu de
`application/octet-stream`) pour que la fabrication du WebAPK réussisse sur Android/Chrome et que
l'application installée apparaisse réellement dans le tiroir d'applications.

---

## Comportement attendu

### Cas nominal

- `GET /manifest.webmanifest` (servi par nginx dans l'image `frontend`) répond `200` avec
  `Content-Type: application/manifest+json`.
- Le **contenu** du manifest est inchangé (name/short_name « Claude Portal », icônes any + maskable
  192/512, `display: standalone`, `start_url: "/"`).
- Le manifest porte un cache **court** (`max-age=3600`, `public`) : une mise à jour du manifest est
  reprise sans devoir vider un cache immuable d'un an.
- Les autres assets restent servis comme avant : icônes PNG (`icons/**`), `favicon.ico`,
  `ngsw-worker.js`, `ngsw.json`, `index.html`, chunks hashés — **non impactés**.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Fichier `manifest.webmanifest` absent du build | `try_files $uri =404` → 404 (pas de repli SPA HTML sur cette URL) | 404 |
| Requête d'un autre `.json` (ex. `ngsw.json`) | Servi tel quel par la table MIME nginx en `application/json` (bloc exact `=` ne l'intercepte pas) | 200 |

---

## Critères d'acceptation

> Chaque critère est vérifiable. Pas d'ambiguïté.

- [ ] `frontend/nginx.conf` contient un `location = /manifest.webmanifest` qui force
      `default_type application/manifest+json` (via un bloc `types { }` vide, comme robots.txt/sitemap.xml).
- [ ] Ce bloc pose un cache court (`Cache-Control: public, max-age=3600`) et un `try_files $uri =404`.
- [ ] Le contenu de `frontend/public/manifest.webmanifest` n'est **pas** modifié.
- [ ] Le service des icônes (`icons/**`, PNG), `favicon.ico`, `ngsw-worker.js`/`ngsw.json`,
      `index.html`, robots.txt, sitemap.xml, page partagée `/p/` reste inchangé.
- [ ] Une **garde automatisée** échoue (code de sortie ≠ 0) si `nginx.conf` ne sert plus
      `.webmanifest` en `application/manifest+json` (esprit « vérifier le rendu servi »).
- [ ] `npm run build` (frontend) reste vert.

---

## Périmètre

### Hors scope (explicite)

- Le contenu du manifest et les icônes → SF-152-01 (livrée), non touchées.
- Le service worker / `ngsw` → SF-152-02 (livrée), non touché.
- Toute modification backend, runner, migration ou composant cluster.
- Le déploiement (hors périmètre de cette subfeature : livrée sans déploiement).

---

## Contraintes de validation

| Réglage | Valeur | Règle |
|---------|--------|-------|
| type MIME du manifest | `application/manifest+json` | imposé par la spec W3C ; requis par Chrome pour le WebAPK |
| cache du manifest | `public, max-age=3600` | court : le manifest doit rester rafraîchissable (pas d'`immutable`) |
| portée du bloc | `location = /manifest.webmanifest` | match exact : n'affecte aucun autre asset |

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun endpoint applicatif, aucune table, **aucune migration**. Configuration de service statique
nginx uniquement.

### Fichiers impactés

- `frontend/nginx.conf` — ajout d'un `location = /manifest.webmanifest` (type MIME + cache).
- `frontend/scripts/verify-nginx-mime.mjs` (nouveau) — garde : échoue si le manifest n'est pas
  servi en `application/manifest+json`.
- `frontend/package.json` — script `verify:nginx` appelant la garde.

### Composants Angular

Aucun. Pas d'écran, pas de composant, pas de route.

---

## Préoccupation transversale

| Préoccupation | Impactée ? | Composants |
|--------------|-----------|-----------|
| Auth / Principal | Non | le manifest est un asset statique public, hors chaîne d'auth |
| Contexte tenant | Non | aucun accès données, aucun `user_id` |
| Plans / limites | Non | aucun gate |
| Navigation / routing | Non | `location = /manifest.webmanifest` est un match exact d'asset ; ne touche ni le fallback SPA `location /`, ni les proxys `/api`, `/oauth2`, `/login/oauth2`, ni le bloc `^~ /p/` |

Vérification de non-régression : les autres `location` de `nginx.conf` (assets hashés, `.mjs`,
`index.html`/`main.js`, robots.txt, sitemap.xml, `/p/`, SPA, proxys backend) restent inchangés ;
le nouveau bloc en match exact ne peut intercepter que l'URL `/manifest.webmanifest`.

---

## Plan de test

### Tests

- [ ] Garde `verify-nginx-mime.mjs` : parse `frontend/nginx.conf`, échoue si aucun `location`
      ne sert `.webmanifest` en `application/manifest+json`, ou si le bloc pose un cache `immutable`
      sur le manifest. Passe (exit 0) sur la conf corrigée.
- [ ] `npm run build` (frontend) vert (aucun asset cassé, aucun changement de source Angular).

### Vérification manuelle du rendu servi (documentée, hors CI)

Après déploiement (hors périmètre de cette SF) :
```
curl -sI https://portal.ng-itconsulting.com/manifest.webmanifest | grep -i content-type
# attendu : content-type: application/manifest+json
```
Localement, l'image `frontend` bâtie sert le manifest via nginx avec ce type (bloc `location`
ci-dessus). En CI sans nginx en fonctionnement, la **garde statique** tient lieu de test automatisé,
sur le modèle de `verify-public-metadata.mjs` (F-29).

### Isolation workspace / user_id

- [x] Non applicable — configuration de service statique, aucun accès données.

---

## Dépendances

- **Bloquante amont** : SF-152-01 (manifest + icônes) et SF-152-02 (service worker) — livrées.
- Aucune question ouverte impactée.

---

## Notes et décisions

- **D-mime-webmanifest** : nginx (`mime.types` par défaut) ne mappe pas l'extension `.webmanifest`,
  d'où `application/octet-stream` en prod (confirmé par `curl -I`). Ce mauvais type fait échouer la
  fabrication du WebAPK (Android/Chrome, Samsung S23) : l'app « s'installe » sans apparaître dans le
  tiroir (raccourci fantôme). Correctif : un `location = /manifest.webmanifest` avec
  `types { } default_type application/manifest+json`, exactement le motif déjà utilisé pour
  `robots.txt` (text/plain) et `sitemap.xml` (application/xml).
- **D-cache-court** : le manifest reçoit `max-age=3600` (et non l'`immutable` d'un an des assets
  hashés) car son nom n'a pas de hash ; il doit rester rafraîchissable.
- **D-garde-statique** : la vérité étant « le rendu servi », la garde inspecte la conf nginx
  réellement embarquée dans l'image (le `nginx.conf` copié dans le Dockerfile) plutôt qu'un composant.
