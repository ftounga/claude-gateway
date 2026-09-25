# Mini-spec — F-159 / SF-159-04 — Écran Réglages (settings) responsive

## Identifiant

`F-159 / SF-159-04`

## Feature parente

`F-159` — Responsive de finition (suite de F-158). Après les fondations (SF-159-01 :
`.page`, `.table-scroll`, mixin `phone` à 819 px, `box-sizing` global, garde-fou dialogs),
cette SF rattache l'**écran Réglages du compte** (`/settings`) au responsive téléphone en
**réutilisant le Lot 0** — jamais un breakpoint local.

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-159-04-settings-responsive`

---

## Objectif

> En une phrase : rendre l'écran Réglages (`frontend/src/app/settings/*`) confortable au
> téléphone (~400 px) sans scroll horizontal — les lignes libellé/valeur passent à la ligne
> (`.settings__row` en `flex-wrap`), une valeur longue (e-mail, jeton masqué) s'enroule au lieu
> de déborder, et les groupes de boutons passent à la ligne avec des cibles tactiles ≥ 44 px —
> sans régresser le desktop (≥ 820 px).

---

## Comportement attendu

### Cas nominal

1. **Lignes libellé/valeur** : `.settings__row` (aujourd'hui `display:flex` sans `flex-wrap`,
   libellé `flex:0 0 160px` rigide + valeur) passe en `flex-wrap: wrap`. Sur desktop la ligne
   reste sur une ligne (la place ne manque pas) ; sous 819 px, quand un e-mail long ne tient
   plus à côté d'un libellé de 160 px, la valeur **passe à la ligne** au lieu de pousser la
   largeur de la page.
2. **Valeur longue enroulée** : `.settings__value` reçoit `min-width: 0` et
   `overflow-wrap: anywhere` — un e-mail (`prenom.nom-tres-long@sous-domaine.example.com`) ou
   un jeton masqué mono s'enroule dans sa colonne au lieu de déclencher un défilement
   horizontal de page.
3. **Groupes de boutons** : `.settings__actions` (aujourd'hui `display:flex; gap` sans wrap,
   jusqu'à 5 boutons/liens : profil, documents, modèles, rapports, MCP) passe en
   `flex-wrap: wrap`. Sous 819 px, chaque `button`/`a` de la barre d'actions a une cible
   tactile `min-height: 44px`.
4. **Gouttière latérale** : la gouttière existante `.settings { padding: var(--cg-space-4) }`
   (16 px) satisfait déjà « ≥ 16 px à toute largeur » ; inchangée.
5. **Dialogs de l'écran** (supprimer le compte, retirer la clé API, retirer le jeton Git) :
   déjà couverts par le **garde-fou global des dialogs sous 819 px** du Lot 0 (panneau borné à
   ≤ 96vw, `min-width` interne neutralisée). Aucune retouche nécessaire.

### Cas d'erreur

> Feature purement CSS/SCSS (aucun endpoint, aucune donnée). Les « cas d'erreur » sont des
> risques de régression visuelle traités explicitement.

| Situation | Comportement attendu |
|-----------|---------------------|
| E-mail / jeton très long | `overflow-wrap: anywhere` + `min-width: 0` sur la valeur → enroulé, pas de scroll-x. |
| Écran large (desktop ≥ 820 px) | `flex-wrap: wrap` reste inerte tant que la place ne manque pas ; les cibles 44 px sont sous le mixin `phone` → desktop inchangé. |
| Beaucoup de boutons d'action | `flex-wrap: wrap` → ils passent à la ligne, jamais de débordement. |

---

## Critères d'acceptation

- [ ] `.settings__row` porte `flex-wrap: wrap`.
- [ ] `.settings__value` porte `min-width: 0` et `overflow-wrap: anywhere` (valeur longue enroulée).
- [ ] `.settings__actions` porte `flex-wrap: wrap`.
- [ ] Sous 819 px, les boutons/liens de `.settings__actions` ont `min-height: 44px` (via mixin `bp.phone` du Lot 0).
- [ ] Le composant réutilise le point de rupture partagé (`@use '../../styles/breakpoints' as bp;`), aucun `@media (max-width: 819px)` recopié, aucun breakpoint local.
- [ ] Charte respectée : uniquement des jetons `--cg-*` et unités relatives, aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] `npm run build` vert (budgets F-117 : la feuille de composant reste sous le budget ; sinon feuille dédiée).
- [ ] Pas de régression desktop (≥ 820 px) : `flex-wrap` inerte à la place disponible, cibles 44 px bornées sous `phone`.
- [ ] Un test vérifie `flex-wrap: wrap` sur `.settings__row` et l'enroulement de `.settings__value`.

---

## Périmètre

### Hors scope (explicite)

- Les autres écrans de F-159 (chaque SF traite le sien).
- Le desktop (≥ 820 px) : inchangé.
- Tout backend, endpoint, migration, logique TS. **Pur frontend, un seul écran.**
- La mise à jour de `PRODUCT_SPEC.md` (étape 6 groupée au niveau de la vague F-159).
- Les dialogs de l'écran : déjà couverts par le garde-fou global du Lot 0 (aucune retouche).

---

## Préoccupations transversales

| Préoccupation | Déclenchée ? | Analyse d'impact |
|--------------|--------------|------------------|
| Auth / Principal | Non | Aucune touche à l'auth. |
| Contexte tenant | Non | Aucun accès données. |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucune route ni guard modifié. |
| CSS global | Non | Modifications **bornées au composant** `settings.component.scss` (styles scopés Angular) + un test. Aucun style global touché ; le Lot 0 est seulement **consommé**. |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration.

### Composants / fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `frontend/src/app/settings/settings.component.scss` | MODIF | `@use` breakpoints ; `.settings__row` wrap ; `.settings__value` enroulement ; `.settings__actions` wrap + cibles 44 px sous `phone`. |
| `frontend/src/app/settings/settings.component.spec.ts` | MODIF | Test des règles responsive (flex-wrap, enroulement de la valeur). |

---

## Plan de test

### Tests unitaires (rendu du composant, styles scopés chargés par Karma)

- [ ] `.settings__row` a `flex-wrap: wrap` (getComputedStyle).
- [ ] `.settings__value` a `overflow-wrap` enroulant (`anywhere`/`break-word`) et `min-width: 0`.
- [ ] Non-régression : aucune règle n'introduit de couleur littérale hors jeton.

### Tests d'intégration

- [ ] `npm run build` (production) vert, budgets respectés.

### Isolation workspace

- [ ] Non applicable — feature purement CSS, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-159-01` (fondations : mixin `phone`, garde-fou dialogs) — mergée.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Réutilisation stricte du Lot 0** : chemin `@use '../../styles/breakpoints' as bp;` (deux
  niveaux : `settings/` → `app/` → `src/`), même patron que SF-159-03.
- **`flex-wrap` sans mixin** : posé sans borne de breakpoint car inerte tant que la place ne
  manque pas (desktop) et bénéfique dès que la largeur se réduit ; seules les **cibles 44 px**
  sont bornées sous `phone` pour ne pas alourdir le desktop.
- **Dialogs non retouchés** : couverts par le garde-fou global du Lot 0 (SF-159-01).
