# Mini-spec — F-159 / SF-159-01 — Fondations responsive globales

## Identifiant

`F-159 / SF-159-01`

## Feature parente

`F-159` — Responsive de finition (suite de F-158). Après F-151 (coquille) et F-158
(écrans de contenu), F-159 pose les **fondations transverses** qui manquaient : un
point de rupture unique partagé, un modèle de gouttière de page réutilisable, et des
garde-fous globaux (box-sizing, débordement des tables, largeur des dialogs) pour que
l'app soit confortable au téléphone (~400 px) sans régresser le desktop.

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-159-01-fondations-responsive`

---

## Objectif

> En une phrase : poser les fondations SCSS responsive partagées de l'application —
> `box-sizing` global, point de rupture unique **819 px** (`_breakpoints.scss` : `$bp-phone`
> + `@mixin phone`), classe de page `.page`, utilitaire `.table-scroll`, et un garde-fou
> global des dialogs sous 819 px — sans régresser le desktop (≥ 820 px).

---

## Comportement attendu

### Cas nominal

1. **`box-sizing` global** : `*, *::before, *::after { box-sizing: border-box }` est
   déclaré une seule fois dans `src/styles.scss`. Padding et bordure sont désormais
   inclus dans les largeurs déclarées partout, ce qui supprime les débordements
   horizontaux d'un contenu qui dépasse sa colonne.
2. **Point de rupture unique** : un partial `src/styles/_breakpoints.scss` expose
   `$bp-phone: 819px` et `@mixin phone { @media (max-width: 819px) { @content } }`,
   aligné sur la valeur déjà employée par `_forge-layout-shell.scss:148` et
   `DESIGN_SYSTEM.md` (§ « Téléphone (< 820 px) »). Les composants futurs et les
   refontes s'appuieront dessus au lieu de recopier `@media (max-width: 819px)`.
3. **Classe `.page` partagée** : une gouttière de page réutilisable —
   `max-width` raisonnable, `margin-inline: auto`, `padding-inline: clamp(16px, 4vw, 24px)`
   (gouttière latérale ≥ 16 px à toute largeur, montant à 24 px sur grand écran),
   `padding-block` en jetons `--cg-*`. Disponible pour les écrans, aucun écran existant
   n'y est rattaché dans cette SF (aucun élément ne porte `class="page"` aujourd'hui).
4. **`.table-scroll`** : `overflow-x: auto` (+ `max-width: 100%`) — enveloppe standard
   pour un tableau large, seul autorisé à défiler horizontalement, jamais la page.
5. **Garde-fou dialogs sous 819 px** : sous le point de rupture, le panneau de dialog
   Material est borné à `≤ 96vw` et la surface interne peut rétrécir (`min-width` interne
   neutralisé sur la surface, contenu `max-width: 100%`), pour qu'un dialog ne déclenche
   jamais de défilement horizontal de page sur un téléphone.

### Cas d'erreur

> Feature purement CSS/SCSS (aucun endpoint, aucune donnée). Les « cas d'erreur »
> sont des risques de régression visuelle traités explicitement.

| Situation | Comportement attendu |
|-----------|---------------------|
| Un composant fixait une `width` + `padding` en s'appuyant implicitement sur `content-box` | `box-sizing: border-box` peut réduire sa boîte de contenu. Risque tracé et **spot-check** des écrans déjà responsive (chat, postes/Forge, gouvernance, onboarding, rapports, admin/usage, space-pitch, terminal Atelier) après build. |
| Un composant portait déjà un `.page` local (collision de nom) | Vérifié : **aucun** élément ne porte `class="page"` (seuls `admin-page`, `mcp-page`, `files-page` existent — jetons de classe distincts, non matchés par `.page`). Pas de collision. |
| Un dialog interne impose une `min-width` supérieure à l'écran | Le garde-fou global borne le panneau à `≤ 96vw` sous 819 px et neutralise la `min-width` de la surface → pas de scroll-x. |
| La lib `mermaid` / pages publiées (iframe F-109) | Isolées (document séparé) — non affectées par les styles globaux. |

---

## Critères d'acceptation

- [ ] `src/styles.scss` déclare `*, *::before, *::after { box-sizing: border-box }` (une seule fois).
- [ ] `src/styles/_breakpoints.scss` existe, exporte `$bp-phone: 819px` et `@mixin phone`.
- [ ] `src/styles.scss` consomme le mixin (`@use` en tête) pour le garde-fou dialogs.
- [ ] `.page` existe avec `max-width`, `margin-inline: auto`, `padding-inline: clamp(16px, 4vw, 24px)`, `padding-block`.
- [ ] `.table-scroll` existe avec `overflow-x: auto`.
- [ ] Sous 819 px, le panneau de dialog Material est borné à `≤ 96vw`.
- [ ] Charte respectée : uniquement des jetons `--cg-*` et unités relatives, aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] `npm run build` vert (budgets F-117 respectés : styles global, pas de feuille de composant en dépassement).
- [ ] Pas de régression desktop (≥ 820 px) : tout le nouveau responsive est borné sous le mixin `phone` ou est une hygiène neutre (box-sizing, `.page`/`.table-scroll` non rattachés à un écran existant).
- [ ] Spot-check des écrans déjà responsive : aucun écran majeur cassé par `box-sizing`.
- [ ] Un test (`document.styleSheets`) vérifie la présence des règles fondatrices.

---

## Périmètre

### Hors scope (explicite)

- Le rattachement des écrans existants à `.page` (refonte écran par écran — SF suivantes de F-159).
- Le desktop (≥ 820 px) : inchangé.
- La coquille (F-151), les écrans de contenu (F-158), la PWA (F-152), les notifications (F-153).
- Tout backend, endpoint, migration, composant cluster. **Pur frontend.**
- La mise à jour de `PRODUCT_SPEC.md` (étape 6 groupée au niveau de la vague F-159).

---

## Préoccupations transversales

| Préoccupation | Déclenchée ? | Analyse d'impact |
|--------------|--------------|------------------|
| Auth / Principal | Non | Aucune touche à l'auth. |
| Contexte tenant | Non | Aucun accès données. |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucune route ni guard modifié. |
| **CSS global (box-sizing)** | **Oui** | `box-sizing: border-box` s'applique à **tous** les composants. Impact borné et vérifié : (a) build vert ; (b) spot-check des 8 écrans déjà rendus responsive par F-158 + terminal Atelier ; (c) `.page`/`.table-scroll` sont des utilitaires **non rattachés** à un écran existant, donc sans effet tant qu'un écran ne les adopte pas. Composants qui posent déjà `box-sizing: border-box` (onboarding, radar-subject, forge-rail, forge-project-tile, mosaique) : inchangés. |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration.

### Composants / fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `frontend/src/styles.scss` | MODIF | `@use` breakpoints ; box-sizing global ; `.page` ; `.table-scroll` ; garde-fou dialogs sous 819 px. |
| `frontend/src/styles/_breakpoints.scss` | CRÉATION | `$bp-phone: 819px` + `@mixin phone`. |
| `frontend/src/app/styles/fondations-responsive.spec.ts` | CRÉATION | Test CSSOM des règles fondatrices (styles globaux chargés par Karma). |

---

## Plan de test

### Tests unitaires (CSSOM — styles globaux chargés par Karma)

- [ ] `box-sizing: border-box` s'applique à un élément arbitraire du document (règle globale présente).
- [ ] `.page` porte `margin-inline: auto` et un `padding-inline` en `clamp(...)`.
- [ ] `.table-scroll` porte `overflow-x: auto`.
- [ ] Il existe une règle `@media (max-width: 819px)` qui borne le panneau de dialog à `≤ 96vw`.
- [ ] Non-régression : aucune règle fondatrice n'introduit de couleur littérale hors jeton.

### Tests d'intégration

- [ ] `npm run build` (production) vert, budgets respectés.

### Isolation workspace

- [ ] Non applicable — feature purement CSS, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

- Aucune. Première SF de F-159 (les fondations).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Valeur 819 px** : reprise telle quelle de l'existant (`_forge-layout-shell.scss:148`,
  `DESIGN_SYSTEM.md`) pour un point de rupture unique et cohérent ; « téléphone » = `< 820 px`.
- **Emplacement du partial** : `src/styles/_breakpoints.scss` (nouveau dossier de partials
  globaux), référencé depuis `styles.scss` par `@use './styles/breakpoints' as bp;`.
- **`.page` volontairement non rattachée** dans cette SF : fournir la fondation sans risquer
  une régression de layout sur un écran existant ; l'adoption se fera écran par écran.
- **Garde-fou dialogs** : ciblé sur `.cdk-overlay-pane` / `.mat-mdc-dialog-panel` (overlay
  Material au niveau document, donc règle globale légitime), `!important` pour l'emporter sur
  les largeurs posées par les composants, borné sous le mixin `phone` (desktop intact).
