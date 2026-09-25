# Mini-spec — [F-158 / SF-158-10] Containment horizontal du terminal + test de largeur réel

## Identifiant

`F-158 / SF-158-10`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-10-terminal-containment-largeur`

## Priorité

**P0** — correctif : le terminal Atelier déborde / est tronqué à droite sur téléphone (~360-400 px).

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous 819 px, **fermer l'axe horizontal du terminal au niveau de `:host` / `.terminal-view`** (le fil ET
les rangées de contrôle sont des enfants de la vue) pour qu'aucune rangée trop large ne remonte jusqu'au
conteneur de scroll de page (`shell.component.scss .app-content{overflow:auto}`), et **poser le VRAI
garde-fou anti-régression** : un test Karma en DOM réel qui mesure `scrollWidth <= clientWidth`.

---

## Contexte / cause racine (déjà tracée)

- Chaîne : `.app-content{overflow:auto}` (shell) est le conteneur de scroll horizontal de PAGE.
  `atelier-terminal.component.scss` `:host` (l.2-5) et `.terminal-view` (l.7-12) n'ont **aucun**
  `overflow-x` / `max-width` / `min-width:0` → toute rangée trop large déborde jusqu'à `.app-content`.
- `atelier-terminal-mobile.component.scss` `.terminal-scrollback{overflow-x:hidden}` (SF-158-09) **clippe**
  le fil, mais les **rangées de contrôle** (target / mode / host-state / hint-runner), **sœurs** du fil
  sous `.terminal-view`, n'ont aucun containment → elles débordent encore.
- **Cause racine de fond** : le test existant (`terminal-contenu-responsive.spec.ts`) n'inspecte que la
  CSSOM (règle présente), jamais `scrollWidth <= clientWidth` réel → les fixes « passaient » sans corriger.

---

## Comportement attendu

### Cas nominal

- Desktop (≥ 820 px) : rendu du terminal **inchangé** (toutes les règles sous `@media (max-width: 819px)`).
- Téléphone (< 819 px) :
  - `:host` et `.terminal-view` portent `max-width: 100%` et `overflow-x: hidden` : le terminal **clôt
    l'axe horizontal** — aucune rangée ne remonte à `.app-content`, la **PAGE** ne défile jamais.
  - `.terminal-view > *` porte `min-width: 0` (backstop) : les enfants flex peuvent rétrécir sous leur
    taille intrinsèque au lieu de forcer un débordement.
  - `.terminal-scrollback{overflow-x:hidden}` (SF-158-09) reste, mais **comme filet** : les blocs qui
    DOIVENT défiler (diff, `pre`, `table` Markdown) gardent leur `overflow-x:auto` isolé (non-régression).
- **Uniformisation du point de rupture** : `atelier-terminal-questions.component.scss` coupait à **820 px**
  alors que tout le reste coupe à **819 px** → passe à **819 px** (charte : point de rupture unique 819 px).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Rangée de contrôle rigide plus large que 360 px | Ramenée dans le cadre (`overflow-x:hidden` de la vue) ; la page ne défile pas |
| Ligne mono / commande shell insécable très longue | Ne remonte jamais à `.app-content` (clip de `:host`/`.terminal-view`) |
| Bloc large qui doit défiler (diff / `pre` / `table`) | Défile **chez lui** (`overflow-x:auto` existant, non retiré) |
| Fenêtre exactement à 819 px | Le patron mobile s'applique (bornes cohérentes partout) |

---

## Critères d'acceptation

- [ ] Sous 819 px, `:host` et `.terminal-view` portent `max-width: 100%` **et** `overflow-x: hidden`.
- [ ] Sous 819 px, `.terminal-view > *` porte `min-width: 0`.
- [ ] `.terminal-scrollback{overflow-x:hidden}` conservé (filet) ; les blocs larges gardent leur
      `overflow-x:auto` isolé (non-régression).
- [ ] `atelier-terminal-questions.component.scss` coupe désormais à **819 px** (plus 820).
- [ ] **Test de largeur réel** (nouveau, DOM réel ChromeHeadless) : le terminal monté dans un hôte de
      **360 px** puis **400 px**, avec un contenu volontairement très large, vérifie
      `host.scrollWidth <= host.clientWidth`. Ce test **échoue avant** le fix de containment et **passe
      après** (garde-fou anti-régression réel).
- [ ] À ≥ 820 px, rendu identique à l'existant (aucune régression desktop).
- [ ] Jetons `--cg-*` uniquement, aucune couleur nouvelle ; budget de feuille respecté (nouveaux styles
      dans `atelier-terminal-mobile.component.scss`, pas dans la feuille principale au plafond 12 ko).
- [ ] `npm run build` vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) : inchangé.
- Les rangées de contrôle rendues **pleine largeur / utilisables** (target/mode/host-state) → **SF-158-11**.
- L'ergonomie fine (en-tête compact SF-158-12, composeur ancré SF-158-13, moment Teams SF-158-14).
- Toute logique métier, endpoint, migration, DTO, composant cluster. Pur frontend, display-only.
- Toute modification de `docs/PRODUCT_SPEC.md` (étape 6, séparée).

---

## Technique

### Endpoint(s)

Aucun. Pur frontend.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `AtelierTerminalComponent` — feuille dédiée **existante** `atelier-terminal-mobile.component.scss`
  (14ᵉ feuille, budget de build 12 ko de la feuille principale). On y **étend** le bloc
  `@media (max-width: 819px)`. **Aucune modification HTML.** **Aucune modification de la feuille
  principale.**
- `atelier-terminal-questions.component.scss` — `820px` → `819px` (uniformisation du point de rupture).

---

## Plan de test

### Tests unitaires (composant)

- [ ] `terminal-largeur-reelle.spec.ts` (**nouveau**) — **garde-fou en DOM réel** : monte
      `AtelierTerminalComponent` dans un hôte borné (360 px, puis 400 px), applique les règles mobiles
      (`@media (max-width: 819px)`) au layout réel, injecte un contenu volontairement très large
      (rangée de contrôle / ligne mono insécable), et **asserte `host.scrollWidth <= host.clientWidth`**.
      ROUGE avant le fix de containment, VERT après.
- [ ] `terminal-contenu-responsive.spec.ts` (existant) reste vert ; on y ajoute la vérification CSSOM du
      containment `:host` / `.terminal-view` / `min-width:0` (complément, pas substitut).

### Tests d'intégration

- Non applicable (pur affichage CSS ; le comportement de layout est vérifié par le test DOM réel).

### Isolation workspace

- [x] Non applicable — raison : aucun accès données, pur affichage CSS.

---

## Dépendances

### Subfeatures bloquantes

- Aucune. S'appuie sur SF-158-09 (feuille mobile + `.terminal-scrollback{overflow-x:hidden}`, livrée).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Navigation / routing** : non touchée. Aucun nouveau chemin, guard ni redirection ; même URL pour les
  deux tailles (display-only). Composants de navigation impactés : **aucun**.
- **Auth / tenant / plans / limites** : non touchés (pur affichage). Aucun accès données modifié.

---

## Notes et décisions

- **D1** : point de rupture unique **819 px** (DESIGN_SYSTEM `_breakpoints.scss` / `_forge-layout-shell.scss`).
  On aligne `atelier-terminal-questions.component.scss` (820 → 819).
- **D2 (test réel)** : la fenêtre Karma est 1440×900 (F-98) → les `@media (max-width:819px)` ne s'activent
  pas à ce viewport. Le test applique donc les règles mobiles au layout réel (extraction des règles
  `@media (max-width:819px)` déjà injectées par Angular dans `document.styleSheets`, ré-appliquées sans la
  garde media) puis mesure `scrollWidth <= clientWidth` — mesure de **layout réelle**, pas CSSOM. On ne
  touche pas la config Karma globale (les autres tests de mise en page mesurent à 1440×900).
- **Budget SCSS (F-117)** : règles dans la feuille dédiée mobile (bien sous le seuil `anyComponentStyle`
  4 ko), pas dans la feuille principale (plafond 12 ko). Aucune couleur nouvelle.
