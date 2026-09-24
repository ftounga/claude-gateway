# Mini-spec — F-152 / SF-152-01 — Manifest + icônes (installable)

## Identifiant

`F-152 / SF-152-01`

## Feature parente

`F-152` — PWA installable (icône sur l'écran d'accueil)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-152-01-manifest-icones`

---

## Objectif

Rendre l'application **ajoutable à l'écran d'accueil avec une icône** : ajouter un
`manifest.webmanifest` (nom, icônes **maskables** 192/512, `theme_color`, `display: standalone`,
`start_url`), un `apple-touch-icon`, et les `<link>` correspondants dans `index.html`, avec des
icônes de marque (navy/orange de la charte).

---

## Comportement attendu

### Cas nominal

- Le document **servi** (`index.html`) porte `<link rel="manifest" href="manifest.webmanifest">`
  et `<link rel="apple-touch-icon" href="icons/apple-touch-icon.png">`.
- Le `manifest.webmanifest` déclare : `name`, `short_name`, `start_url: "/"`,
  `display: "standalone"`, `theme_color`, `background_color`, et des `icons` 192 et 512 px
  (`purpose: "any"` et `purpose: "maskable"`).
- **Android/Chrome** : le critère d'installabilité est rempli → « Ajouter à l'écran d'accueil »
  proposé ; au lancement, ouverture **plein écran** (`standalone`) avec l'icône de marque.
- **iOS ≥ 16.4** : via Partager → « Sur l'écran d'accueil », l'`apple-touch-icon` est utilisé.
- Les métadonnées SEO/OG existantes (F-29) et le repli `noscript` **ne régressent pas**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Manifest introuvable (mauvais chemin) | Le site reste fonctionnel (le manifest est un progressif) ; le critère PWA n'est pas rempli — évité en vérifiant le HTML **servi** (build) |
| Icône manquante | Idem : dégrade proprement (l'installabilité échoue mais l'app marche) — évité en vérifiant la présence des fichiers dans le build |

---

## Critères d'acceptation

- [ ] `manifest.webmanifest` présent, valide (JSON), avec `display: standalone`, `start_url: "/"`, `name`, `short_name`, `theme_color`, `background_color`, `icons` 192 + 512 (`any` et `maskable`).
- [ ] Les icônes 192/512 (maskables, marge de sécurité) + `apple-touch-icon` (180) sont dans les assets et servies à la racine.
- [ ] `index.html` porte `<link rel="manifest">` et `<link rel="apple-touch-icon">`.
- [ ] Le **HTML servi** (`dist/frontend/index.html` après `ng build`, vérifié) contient ces `<link>` et les métadonnées SEO/OG + `noscript` restent présents (non-régression F-29).
- [ ] Le `manifest.webmanifest` et les icônes sont présents dans `dist/frontend/`.
- [ ] Couleurs strictement DESIGN_SYSTEM (navy `#0B1020` / orange `#E07B39`).

---

## Périmètre

### Hors scope (explicite)

- Le **service worker** et l'enregistrement (prod-only) → SF-152-02.
- Toute notification (in-tab, Web Push) → F-153.
- Un mode hors ligne du pilotage (impossible/inutile, le tour vit côté serveur F-84).

---

## Contraintes de validation

| Champ manifest | Valeur | Règle |
|----------------|--------|-------|
| `display` | `standalone` | plein écran sans barre navigateur |
| `start_url` | `/` | ouverture à la racine |
| `theme_color` | `#0B1020` (navy charte) | jeton DESIGN_SYSTEM |
| `background_color` | `#0B1020` (navy charte) | splash de démarrage |
| `icons` | 192 + 512, `any` + `maskable` | marge de sécurité maskable (≈ 80 % zone sûre) |

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun. Migration : **non applicable**.

### Fichiers impactés

- `frontend/public/manifest.webmanifest` (nouveau) — servi à la racine (les assets du projet sont
  globés depuis `public/` par `angular.json`).
- `frontend/public/icons/icon-192.png`, `icon-512.png`, `icon-192-maskable.png`,
  `icon-512-maskable.png`, `apple-touch-icon.png` (nouveaux, générés de la marque).
- `frontend/src/index.html` — `<link rel="manifest">` + `<link rel="apple-touch-icon">`.

> **Décision (écart documenté au cadrage D1)** : plutôt que `ng add @angular/pwa` (qui écrit le
> manifest dans `src/` et l'ajoute à `angular.json`), on place le manifest et les icônes dans
> `public/` — **déjà** la racine d'assets du projet (`favicon.ico`, `claude-portal-logo.png` y
> vivent). C'est le changement le plus **petit et cohérent** avec l'existant : aucun bloc `assets`
> nouveau dans `angular.json`. Le service worker (SF-152-02) est traité séparément.

---

## Préoccupation transversale

Aucune : pas d'auth, tenant, plan, ni route/guard. Le manifest ne change pas la navigation.

---

## Plan de test

### Tests

- [ ] `manifest.webmanifest` est un JSON valide contenant les clés requises (vérifié par lecture + `node -e JSON.parse` sur le fichier servi).
- [ ] `ng build` **vert** ; `dist/frontend/index.html` contient `rel="manifest"` et `rel="apple-touch-icon"` (vérifié par `grep` sur le HTML servi, conformément à D5 du cadrage : vérifier le **HTML servi**, pas seulement le composant).
- [ ] `dist/frontend/manifest.webmanifest` et les icônes existent après build.
- [ ] Non-régression F-29 : `dist/frontend/index.html` contient toujours `og:title`, `canonical` et le repli `app-fallback`/`noscript`.

### Isolation workspace / user_id

- [x] Non applicable — fichiers statiques, aucun accès données.

---

## Dépendances

Aucune subfeature bloquante. F-152 est indépendante de F-151. Aucune question ouverte impactée.

---

## Notes et décisions

- **Icônes maskables** : composées sur fond navy `#0B1020` avec le logo centré dans la zone sûre
  (≈ 66 % de la largeur) pour respecter la marge de sécurité maskable (Android applique un masque
  variable). Icônes `any` : logo pleine trame. `apple-touch-icon` 180 px (iOS applique ses propres
  coins arrondis).
- **DRAPEAU (hérité du cadrage)** : décision **iOS-managé** (MDM peut brider l'ajout à l'écran
  d'accueil, requis au Web Push iOS) à trancher **avant F-153**. F-152 reste livrable sans ce
  verdict (installabilité + prérequis SW utiles quel que soit le verdict).
