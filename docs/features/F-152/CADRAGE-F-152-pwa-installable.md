# F-152 — PWA installable (icône sur l'écran d'accueil)

> Cadrage du 2026-09-24, à la demande du PO (« je veux une icône comme toute application que j'ouvrirai
> sur mon téléphone »).
> Source : audit `docs/audits/AUDIT-2026-09-24-version-mobile-remote-control.md` (§4 reco Option 2, §5
> palier 3 — prérequis).
>
> **Ce document cadre ; il ne livre aucun code.** Palier 2 des 3 paliers de la version mobile
> (F-151 responsive → **F-152 PWA installable** → F-153 notifications).

## 0. Objectif

Faire de l'application web une **PWA installable** : le PO ajoute le site à l'écran d'accueil et obtient
une **icône** comme n'importe quelle app, qui s'ouvre en plein écran (sans barre de navigateur). C'est la
réponse directe à « je veux une icône que j'ouvrirai sur mon téléphone ». F-152 pose aussi le **service
worker**, **prérequis technique du Web Push** (F-153). **Frontend + `angular.json`/`index.html`.**

## 1. Réponse directe au PO : comment l'app arrive sur le téléphone

- **Pas de store, pas d'installateur.** On ouvre `portal.ng-itconsulting.com` dans le navigateur du
  téléphone, puis **« Ajouter à l'écran d'accueil »** (Android : proposé automatiquement / menu Chrome ;
  iOS ≥ 16.4 : menu Partager → « Sur l'écran d'accueil »). Une **icône** apparaît ; au lancement, l'app
  s'ouvre en **plein écran** (`display: standalone`), comme une app native.
- **Réserve à lever avant F-153 (pas F-152)** : sur **iPhone managé** (parc bancaire, CAGIP…), l'ajout à
  l'écran d'accueil peut être **bridé par le MDM**, et le Web Push iOS **exige** cette installation. F-152
  reste utile quel que soit le verdict (installabilité + service worker) ; la décision iOS-managé se
  tranche **avant le palier 3**.

## 2. Ce qui existe déjà (vérifié)

- `frontend/src/index.html` : `<meta name="viewport">`, `<base href="/">`, `<title>`, métadonnées SEO/OG
  (F-29) présentes ; **aucun `<link rel="manifest">`, aucun `apple-touch-icon`**.
- `frontend/angular.json` : blocs `assets` présents ; **pas de `manifest.webmanifest`, pas de
  `serviceWorker: true`, pas de `ngsw-config.json`.**
- Aucun service worker dans le projet (grep : zéro `@angular/service-worker`).

## 3. Décisions de conception

- **D1 — `ng add @angular/pwa` comme base.** Génère `manifest.webmanifest`, `ngsw-config.json`, les
  icônes et l'enregistrement `ServiceWorkerModule`/`provideServiceWorker`. On **revoit** ensuite chaque
  fichier généré (icônes de marque, nom, theme-color) plutôt que d'accepter les valeurs par défaut.
- **D2 — Enregistrement conditionnel prod uniquement.** Le service worker n'est **enregistré qu'en
  production** (`enabled: !isDevMode()` / `registrationStrategy` au chargement stable) — jamais en `dev`
  pour ne pas perturber le rechargement à chaud ni servir du cache périmé aux développeurs.
- **D3 — Charte pour les icônes.** Icônes **maskables** 192 px et 512 px + `theme-color` dérivés du
  DESIGN_SYSTEM (navy/orange de la charte, cf. roadmap UX) ; nom court/nom long cohérents avec « Claude
  Portal ». Aucune couleur hors charte.
- **D4 — Cache prudent (coquille), pas d'offline métier.** `ngsw-config.json` met en cache la **coquille
  applicative** (assets statiques) pour l'installabilité ; **aucune** mise en cache des réponses API/SSE
  (le tour vit côté serveur, F-84 : rien à répliquer offline). Pas de faux « mode hors ligne » du
  pilotage.
- **D5 — Ne pas casser le rendu servi.** Vérifier le **HTML SERVI** (curl), pas seulement le composant :
  le `<link rel="manifest">` et l'`apple-touch-icon` doivent apparaître dans le document livré, et les
  métadonnées SEO/OG existantes (F-29) et le repli noscript ne doivent pas régresser.
- **D6 — Gateway-First / Provider Independence** intactes (aucune capacité IA, aucun appel modèle) ;
  **aucun composant cluster**, aucune migration, aucun endpoint.

## 4. Découpage en subfeatures (≈ 2)

| SF | Titre | Contenu | Impact |
|----|-------|---------|--------|
| **SF-152-01** | Manifest + icônes (installable) | `manifest.webmanifest` (name/short_name, icônes **maskables** 192/512, `theme-color`, `background_color`, `display: standalone`, `start_url`) ; `<link rel="manifest">` + `apple-touch-icon` dans `index.html` ; icônes de marque (charte) dans les assets ; déclaration `assets` dans `angular.json`. Rend l'app **ajoutable à l'écran d'accueil avec icône**. | `frontend/src/index.html`, `frontend/angular.json`, `frontend/src/manifest.webmanifest`, assets icônes |
| **SF-152-02** | Service worker (coquille + prérequis Web Push) | `@angular/service-worker` + `ngsw-config.json` (cache de la coquille, pas d'API) ; enregistrement **conditionnel prod** ; build `angular.json` (`serviceWorker: true`). Pose le SW **sur lequel F-153 branchera `push`/`notificationclick`**. | `frontend/angular.json`, `ngsw-config.json`, bootstrap app (`provideServiceWorker`) |

**Ordre** : SF-152-01 → SF-152-02. La 01 livre déjà l'icône (valeur visible immédiate) ; la 02 pose le
prérequis technique de F-153.

## 5. Dépendances

- **F-152 est indépendante de F-151** (peut être livrée en parallèle), mais l'audit recommande l'ordre
  paliers 1→2→3 (confort d'abord).
- **F-153 dépend de SF-152-02** (le service worker héberge le handler `push`/`notificationclick`).

## 6. Garde-fous

Gateway-First, Provider Independence, DESIGN_SYSTEM strict (icônes/theme-color de marque), isolation
`user_id` (rien touché), **aucun composant cluster**, aucune migration, aucun endpoint, aucun changement
runner.

## 7. Hors périmètre

- Le confort tactile des écrans → **F-151**.
- Toute notification (in-tab, Web Push, VAPID, abonnements) → **F-153**.
- Un vrai mode hors ligne du pilotage (impossible/inutile : le tour vit côté serveur, F-84).

## 8. Drapeaux

- **Décision iOS-managé à trancher AVANT F-153** : sur iPhone géré par MDM, l'ajout à l'écran d'accueil
  (requis pour le Web Push iOS) peut être bridé — à vérifier sur le parc cible (CAGIP…). F-152 reste
  livrable sans ce verdict.
