# Mini-spec — F-159 / SF-159-06 — Écran Administration responsive

## Identifiant

`F-159 / SF-159-06`

## Feature parente

`F-159` — Responsive de finition (suite de F-158). Après les fondations (SF-159-01 :
`.page`, `.table-scroll`, mixin `phone` à 819 px, `box-sizing` global, garde-fou dialogs),
cette SF rattache l'**écran Administration** (`/admin`, `frontend/src/app/admin/*`) au
responsive téléphone en **réutilisant le Lot 0** — jamais un breakpoint local.

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-159-06-admin-responsive`

---

## Objectif

> En une phrase : rendre l'écran Administration (`frontend/src/app/admin/*`) confortable au
> téléphone (~400 px) sans scroll horizontal — la **table des comptes** défile dans son propre
> conteneur (`.table-scroll` du Lot 0) au lieu d'élargir la page, les **dialogs**
> `access-code`/`package-editor` cessent de forcer une `min-width` plus large que l'écran (règle
> Lot 0 : `min-width: min(Xpx, 100%)`), et les **gestes** (boutons d'action, pagination,
> bascules de période) atteignent des cibles ≥ 44 px sous 819 px — sans régresser le desktop
> (≥ 820 px).

---

## Comportement attendu

### Cas nominal

1. **Table des comptes** (`admin.component.html`, `mat-table` à 6 colonnes : e-mail, rôle, plan,
   statut, tokens, inscrit le) : enveloppée dans `<div class="table-scroll">` (Lot 0). Sous
   819 px, si la table dépasse la largeur disponible, **elle seule** défile horizontalement ; la
   page ne défile jamais.
2. **Gouttière de la page** : `.admin-page` (aujourd'hui `padding: 24px`) passe à
   `var(--cg-space-3)` (16 px) sous 819 px pour reprendre la largeur volée, en gardant une
   gouttière ≥ 16 px à toute largeur.
3. **Pagination** : les contrôles du `mat-paginator` (boutons page précédente/suivante, sélecteur
   de taille de page) atteignent une cible tactile `min-height: 44px` sous 819 px.
4. **Dialogs `access-code` et `package-editor`** : leur contenu interne
   (`.code-form`, `.editor`) ne fixe plus une `min-width` rigide (460 px / 480 px) qui, malgré le
   garde-fou global du Lot 0 (panneau ≤ 96vw), déclenchait un scroll-x *interne* sous ~480 px. Il
   passe à `min-width: min(460px, 100%)` / `min(480px, 100%)` (patron SF-159-03) : desktop
   inchangé, contenu qui rétrécit au téléphone. La grille de fichier du `package-editor`
   (`.editor__file`, 3 colonnes) passe à une colonne sous 819 px via le mixin `bp.phone` (au lieu
   du `@media (max-width: 640px)` local supprimé).
5. **Sections en liste** (Gouvernance, Codes d'accès, Coût réel) : déjà en `flex-wrap` (aucun
   débordement horizontal) ; sous 819 px on reprend la gouttière (`padding: var(--cg-space-3)`),
   on borne les blocs à `min-width` rigide (`codes-admin__fresh-main`, `cost-admin__field`) à
   `min-width: 0`, et on porte les boutons/bascules d'action à `min-height: 44px`.
6. **Section Consommation** (`admin-usage`) : **déjà responsive** (F-158 / SF-158-07, table sous
   `overflow-x`, tuiles empilées, cibles 44 px). **Aucune retouche.**

### Cas d'erreur

> Feature purement CSS/SCSS + un ajout de balise conteneur (aucun endpoint, aucune donnée). Les
> « cas d'erreur » sont des risques de régression visuelle traités explicitement.

| Situation | Comportement attendu |
|-----------|---------------------|
| Table à 6 colonnes plus large que l'écran | `.table-scroll` isole le défilement horizontal ; la page ne défile pas. |
| Dialog ouvert sous 480 px | `min-width: min(Xpx, 100%)` → le contenu rétrécit, pas de scroll-x interne. |
| Écran large (desktop ≥ 820 px) | Les `min(Xpx, 100%)` valent `Xpx` (place disponible) ; les cibles 44 px et la reprise de padding sont bornées sous `phone` → desktop inchangé. |

---

## Critères d'acceptation

- [ ] La table des comptes de `admin.component.html` est enveloppée dans `<div class="table-scroll">`.
- [ ] `.admin-page` reprend sa gouttière à `var(--cg-space-3)` (16 px) sous 819 px.
- [ ] Sous 819 px, les contrôles du `mat-paginator` de l'écran Admin ont `min-height: 44px`.
- [ ] `access-code-dialog` : `.code-form` porte `min-width: min(460px, 100%)`, le `@media (max-width: 640px)` local est supprimé.
- [ ] `package-editor-dialog` : `.editor` porte `min-width: min(480px, 100%)`, la grille `.editor__file` passe à une colonne sous 819 px via `bp.phone`, le `@media (max-width: 640px)` local est supprimé.
- [ ] Gouvernance / Codes d'accès / Coût réel : sous 819 px, gouttière `var(--cg-space-3)`, blocs `min-width` rigides neutralisés, boutons/bascules d'action à `min-height: 44px`.
- [ ] Chaque feuille modifiée réutilise le point de rupture partagé (`@use '../../styles/breakpoints' as bp;` — profondeur adaptée), aucun `@media (max-width: 819px)` recopié, aucun breakpoint local nouveau.
- [ ] Charte respectée : uniquement des jetons `--cg-*` et unités relatives, aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] `npm run build` vert (budgets F-117 : chaque feuille de composant reste sous le budget).
- [ ] Pas de régression desktop (≥ 820 px) : `min()` inerte à la place disponible, cibles 44 px et reprise de padding bornées sous `phone`.
- [ ] Un test vérifie l'enveloppe `.table-scroll` de la table des comptes.

---

## Périmètre

### Hors scope (explicite)

- Les autres écrans de F-159 (chaque SF traite le sien).
- La section `admin-usage` : déjà responsive (F-158), aucune retouche.
- Le desktop (≥ 820 px) : inchangé.
- Tout backend, endpoint, migration, logique TS. **Pur frontend, un seul écran.**
- La mise à jour de `PRODUCT_SPEC.md` (étape 6 groupée au niveau de la vague F-159).
- Le garde-fou global des dialogs (Lot 0) : seulement **consommé**, jamais modifié.

---

## Préoccupations transversales

| Préoccupation | Déclenchée ? | Analyse d'impact |
|--------------|--------------|------------------|
| Auth / Principal | Non | Aucune touche à l'auth. |
| Contexte tenant | Non | Aucun accès données. |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucune route ni guard modifié. |
| CSS global | Non | Modifications **bornées aux composants** de `frontend/src/app/admin/*` (styles scopés Angular) + une balise conteneur dans `admin.component.html` + un test. Aucun style global touché ; le Lot 0 est seulement **consommé**. |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration.

### Composants / fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `frontend/src/app/admin/admin.component.html` | MODIF | Enveloppe `.table-scroll` autour de la `mat-table` des comptes. |
| `frontend/src/app/admin/admin.component.scss` | MODIF | `@use` breakpoints ; gouttière `space-3` + cibles 44 px du paginator sous `phone`. |
| `frontend/src/app/admin/admin.component.spec.ts` | MODIF | Test de l'enveloppe `.table-scroll`. |
| `frontend/src/app/admin/access-codes/access-code-dialog/access-code-dialog.component.scss` | MODIF | `min-width: min(460px, 100%)` ; suppression du `@media 640px` local. |
| `frontend/src/app/admin/governance-packages/package-editor-dialog/package-editor-dialog.component.scss` | MODIF | `@use` breakpoints ; `min-width: min(480px, 100%)` ; grille fichier une colonne sous `phone` ; suppression du `@media 640px` local. |
| `frontend/src/app/admin/access-codes/access-codes.component.scss` | MODIF | `@use` breakpoints ; sous `phone` : gouttière `space-3`, `fresh-main` min-width 0, cibles 44 px. |
| `frontend/src/app/admin/governance-packages/governance-packages.component.scss` | MODIF | `@use` breakpoints ; sous `phone` : gouttière `space-3`, cibles 44 px des actions. |
| `frontend/src/app/admin/cost/admin-cost.component.scss` | MODIF | `@use` breakpoints ; sous `phone` : gouttière `space-3`, `field` min-width 0, cibles 44 px. |

---

## Plan de test

### Tests unitaires (rendu du composant, styles scopés chargés par Karma)

- [ ] La `mat-table` des comptes est bien enfant d'un `.table-scroll` (structure DOM).
- [ ] Non-régression : la liste des comptes se charge et s'affiche toujours (test existant conservé).

### Tests d'intégration

- [ ] `npm run build` (production) vert, budgets respectés.

### Isolation workspace

- [ ] Non applicable — feature purement CSS/structure, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-159-01` (fondations : `.table-scroll`, mixin `phone`, garde-fou dialogs) — mergée.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Réutilisation stricte du Lot 0** : `.table-scroll` global pour la table, `min-width: min(...)`
  (patron SF-159-03) pour les dialogs, mixin `bp.phone` pour toute borne de breakpoint. Aucun
  `@media (max-width: 819px)` recopié, aucun `@media (max-width: 640px)` conservé.
- **`admin-usage` intouchée** : déjà responsive (F-158) ; la modifier serait un risque de
  régression sans gain.
- **Table Material** : `.table-scroll` (`overflow-x: auto; max-width: 100%`) enveloppe la
  `mat-table` sans changer son rendu desktop.
