# Mini-spec — F-152 / SF-152-02 — Service worker (coquille + prérequis Web Push)

## Identifiant

`F-152 / SF-152-02`

## Feature parente

`F-152` — PWA installable (icône sur l'écran d'accueil)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-152-02-service-worker`

---

## Objectif

Poser le **service worker** de l'application (`@angular/service-worker`), enregistré **uniquement
en production**, qui met en cache la **coquille** (jamais l'API) — installabilité offline et
**prérequis technique du Web Push** (F-153 y branchera `push`/`notificationclick`).

---

## Comportement attendu

### Cas nominal

- **Build de production** : le builder génère `ngsw-worker.js` et `ngsw.json` (piloté par
  `serviceWorker: "ngsw-config.json"` dans la config `production` d'`angular.json`).
- **Runtime prod** : `provideServiceWorker('ngsw-worker.js', { enabled: !isDevMode(), registrationStrategy: 'registerWhenStable:30000' })` enregistre le SW une fois l'app stable.
- **Cache** : `ngsw-config.json` met en cache la **coquille** (`index.html`, `*.css`, `*.js`,
  `favicon.ico`, `manifest.webmanifest`, icônes, polices locales). **Aucun `dataGroup`** →
  l'API et le SSE **ne sont jamais mis en cache** (le tour vit côté serveur, F-84 : rien à
  répliquer hors ligne).
- **Dev** : aucun SW généré (config `development` sans `serviceWorker`) **et** non enregistré
  (`enabled: !isDevMode()` → false en dev) — pas de perturbation du rechargement à chaud.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| SW indisponible / navigateur sans support | `provideServiceWorker` n'enregistre rien ; l'app fonctionne normalement (progressif) |
| Nouvelle version déployée | Le SW récupère la nouvelle coquille au prochain chargement stable (updateMode `prefetch`) ; l'API n'étant pas cachée, aucun risque de données périmées |

---

## Critères d'acceptation

- [ ] `@angular/service-worker` ajouté aux dépendances (version alignée sur Angular 19.2).
- [ ] `ngsw-config.json` présent : `assetGroups` coquille (`app` prefetch + `assets` lazy), **aucun `dataGroups`** (API/SSE non cachés).
- [ ] `angular.json` : `serviceWorker: "ngsw-config.json"` **uniquement** dans la config `production` (pas en `development`).
- [ ] `provideServiceWorker` dans `app.config.ts` avec `enabled: !isDevMode()` et une stratégie « registerWhenStable ».
- [ ] `ng build` (prod) génère `ngsw-worker.js` **et** `ngsw.json` dans `dist/frontend/browser/`.
- [ ] `ngsw.json` ne contient **aucune** URL d'API (`/api/`, `/atelier/…`) — vérifié.
- [ ] Aucun spec ne casse (app.config n'est pas utilisé en test de composant).

---

## Périmètre

### Hors scope (explicite)

- Le manifest + icônes → SF-152-01 (livrée).
- Les handlers `push` / `notificationclick` et l'UI d'activation → **F-153 / SF-153-03** (branchés
  **sur** ce service worker).
- Tout vrai mode hors ligne du pilotage (impossible/inutile, le tour vit côté serveur F-84).

---

## Contraintes de validation

| Réglage | Valeur | Règle |
|---------|--------|-------|
| enregistrement SW | prod uniquement | `enabled: !isDevMode()` + `serviceWorker` seulement en config `production` |
| cache API/SSE | **jamais** | aucun `dataGroups` dans `ngsw-config.json` |

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun. Migration : **non applicable**.

### Fichiers impactés

- `frontend/package.json` (+ `package-lock.json`) — ajout `@angular/service-worker`.
- `frontend/ngsw-config.json` (nouveau) — cache de la coquille.
- `frontend/angular.json` — `serviceWorker` dans la config `production`.
- `frontend/src/app/app.config.ts` — `provideServiceWorker` conditionnel prod.

### Composants Angular

Aucun composant ; configuration d'application uniquement.

---

## Préoccupation transversale

Aucune : pas d'auth, tenant, plan, ni route/guard. Le SW n'intercepte que des assets statiques.

---

## Plan de test

### Tests

- [ ] `ng build` (production) **vert** ; `dist/frontend/browser/ngsw-worker.js` et `ngsw.json` présents.
- [ ] `ngsw.json` ne cache **aucune** URL d'API (`grep -E '/api/|/atelier/'` → vide).
- [ ] Config `development` d'`angular.json` **sans** `serviceWorker` (prod-only).
- [ ] Non-régression : `app.component.spec.ts` (et suite existante) vert ; aucun spec n'importe `app.config`.

> **Pourquoi pas de test unitaire du SW** : le service worker n'est ni généré ni enregistré en
> environnement de test (`isDevMode()` vrai, config `development`). Son bon fonctionnement se vérifie
> par la présence des artefacts du **build de production** et l'absence d'API dans `ngsw.json`.

### Isolation workspace / user_id

- [x] Non applicable — configuration de build/runtime, aucun accès données.

---

## Dépendances

- **Bloquante amont** : SF-152-01 (manifest) — livrée. F-153 (b/c) **dépendra** de cette SF.

---

## Notes et décisions

- **D-prod-only** : SW généré et enregistré en production seulement (D2 du cadrage) — deux verrous :
  `serviceWorker` absent de la config `development`, et `enabled: !isDevMode()`.
- **D-no-api-cache** : `ngsw-config.json` sans `dataGroups` (D4 du cadrage) — la coquille est
  cachée, jamais l'API/SSE ; pas de faux « mode hors ligne » du pilotage.
- **D-manuel-plutôt-que-ng-add** : configuration manuelle (le manifest/icônes ont été posés en
  SF-152-01) pour éviter que `ng add @angular/pwa` ne réécrive/duplique `index.html`, le manifest
  et les icônes existants.
