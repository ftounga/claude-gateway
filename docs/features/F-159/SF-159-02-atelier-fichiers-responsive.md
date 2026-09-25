# Mini-spec — F-159 / SF-159-02 — Écran Atelier / Fichiers responsive

## Identifiant

`F-159 / SF-159-02`

## Feature parente

`F-159` — Responsive de finition (suite de F-158). SF-159-01 (Lot 0) a posé les
fondations transverses (box-sizing global, point de rupture unique 819 px via
`_breakpoints.scss` : `$bp-phone` + `@mixin phone`, `.page`, `.table-scroll`, garde-fou
dialogs). Cette SF **adopte** ces fondations sur l'**explorateur de fichiers de l'Atelier**
(`/atelier/<id>/files`), seul écran du chemin critique encore cassé au téléphone.

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-159-02-atelier-fichiers-responsive`

---

## Objectif

> En une phrase : rendre l'écran **Atelier / Fichiers** confortable au téléphone (~400 px)
> en empilant l'arbre (`tree-pane`) et l'aperçu sous **819 px**, en passant la recherche en
> pleine largeur et en rendant les gestes de l'arbre tactiles (≥ 44 px, actions visibles sans
> survol) — sans jamais de défilement horizontal de page et sans régresser le desktop (≥ 820 px).

---

## Comportement attendu

### Cas nominal

1. **Corps empilé sous 819 px** : `.body` passe de deux colonnes (`tree-pane` 320 px fixe +
   `viewer`) à **une colonne** (`flex-direction: column`). L'arbre passe en **pleine largeur**
   (la largeur fixe de 320 px est neutralisée), borné en hauteur (`max-height`) avec son propre
   défilement vertical ; l'aperçu/édition prend le reste sous l'arbre.
2. **Barre du haut adaptée** : `.topbar` passe à la ligne (`flex-wrap`), le `topbar-spacer` est
   neutralisé et le **champ de recherche passe en pleine largeur** (`.search-field { width: 100% }`),
   sur sa propre ligne. Les deux boutons de retour passent à la ligne et restent tactiles (≥ 44 px).
3. **Arbre tactile** : sous 819 px, les lignes de l'arbre (`.row`) passent à **≥ 44 px** de haut ;
   les actions de ligne (renommer / télécharger / supprimer), aujourd'hui **révélées au survol**
   (`.row:hover .row-actions`) — geste **absent au doigt** — deviennent **toujours visibles** et
   leurs boutons icône atteignent ≥ 44 px.
4. **Aperçu tactile** : sous 819 px, les boutons d'action de l'aperçu (`.viewer-actions`) restent
   ≥ 44 px ; l'éditeur (`textarea`) conserve son défilement interne (jamais la page).
5. **Pas de défilement horizontal de page** : à 400 px, aucune barre horizontale au niveau page ;
   le contenu large (éditeur) défile chez lui.

### Cas d'erreur

> Feature purement CSS/SCSS (aucun endpoint, aucune donnée). Les « cas d'erreur » sont des
> risques de régression visuelle traités explicitement.

| Situation | Comportement attendu |
|-----------|---------------------|
| Desktop ≥ 820 px | Layout inchangé : deux colonnes, `tree-pane` 320 px, actions au survol. Tout le responsive est borné sous `@include bp.phone`. |
| Budget de style par feuille (F-117) | Les deux feuilles existantes sont proches du budget (`anyComponentStyle` : 12 kB erreur). Le responsive va dans une **3ᵉ feuille dédiée** `atelier-files-mobile.component.scss` (précédent : `atelier-terminal-mobile.component.scss`). |
| Point de rupture / conteneur | **Réutilise** le Lot 0 : `@use '../../../styles/breakpoints' as bp;` + `@include bp.phone`. Aucun breakpoint local réinventé. |

---

## Critères d'acceptation

- [ ] Sous 819 px, `.body` est en `flex-direction: column`.
- [ ] Sous 819 px, `.tree-pane` est en pleine largeur (largeur fixe 320 px neutralisée) et borné en hauteur.
- [ ] Sous 819 px, `.search-field` est en pleine largeur (`width: 100%`).
- [ ] Sous 819 px, `.row` (ligne d'arbre) fait ≥ 44 px de haut.
- [ ] Sous 819 px, `.row-actions` est visible sans survol (`display: flex`).
- [ ] Charte respectée : uniquement jetons `--cg-*` et unités relatives, aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] Le responsive s'appuie sur le mixin `bp.phone` du Lot 0 (aucun `@media` recopié à la main).
- [ ] `npm run build` vert (budgets F-117 respectés).
- [ ] Pas de régression desktop (≥ 820 px) : tout est borné sous `bp.phone`.
- [ ] Un test CSSOM vérifie les règles responsive fondatrices de l'écran.

---

## Périmètre

### Hors scope (explicite)

- Les autres écrans de F-159 (chacun sa SF).
- Le desktop (≥ 820 px) : inchangé.
- La coquille (F-151), les écrans de contenu (F-158), la PWA (F-152), les notifications (F-153).
- Tout backend, endpoint, migration, logique TypeScript. **Pur frontend / CSS.**
- La mise à jour de `PRODUCT_SPEC.md` (étape 6 groupée au niveau de la vague F-159).

---

## Préoccupations transversales

| Préoccupation | Déclenchée ? | Analyse d'impact |
|--------------|--------------|------------------|
| Auth / Principal | Non | Aucune touche à l'auth. |
| Contexte tenant | Non | Aucun accès données ; aucun `user_id`. |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucune route ni guard modifié. |
| CSS (feuille de composant) | Oui, borné | Nouvelle 3ᵉ feuille `atelier-files-mobile.component.scss` **rattachée au seul composant `AtelierFilesComponent`** (styleUrls encapsulé Angular). Aucun autre composant impacté. Tout est sous `bp.phone`. Composants impactés : **uniquement** `frontend/src/app/atelier/files/*`. |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration.

### Composants / fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `frontend/src/app/atelier/files/atelier-files-mobile.component.scss` | CRÉATION | Bloc responsive < 819 px via `@use ../../../styles/breakpoints` + `@include bp.phone`. |
| `frontend/src/app/atelier/files/atelier-files.component.ts` | MODIF | Ajout de la 3ᵉ feuille en fin de `styleUrls` (cascade). |
| `frontend/src/app/atelier/files/atelier-files-responsive.spec.ts` | CRÉATION | Test CSSOM des règles responsive de l'écran. |

---

## Plan de test

### Tests unitaires (CSSOM — styles du composant injectés par Angular)

- [ ] Il existe une règle `@media (max-width: 819px)` qui met `.body` en `flex-direction: column`.
- [ ] Sous 819 px, `.tree-pane` porte `width: 100%` (largeur fixe neutralisée).
- [ ] Sous 819 px, `.search-field` porte `width: 100%`.
- [ ] Sous 819 px, `.row` porte `min-height: 44px`.
- [ ] Sous 819 px, `.row-actions` porte `display: flex`.
- [ ] Non-régression : aucune couleur littérale hors jeton dans le bloc responsive.

### Tests d'intégration

- [ ] `npm run build` (production) vert, budgets respectés.

### Isolation workspace

- [ ] Non applicable — feature purement CSS, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-159-01` (fondations) — **mergée** (`#894`). Fournit `bp.phone`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **3ᵉ feuille dédiée** : les deux feuilles existantes (`atelier-files.component.scss` ~4,6 ko,
  `atelier-files-viewer.component.scss` ~2,7 ko) portent l'ossature et l'aperçu ; le responsive
  va dans une feuille à part, comme `atelier-terminal-mobile.component.scss` (F-151), pour tenir
  le budget `anyComponentStyle` (F-117) et garder la cascade lisible.
- **Actions de ligne au doigt** : le survol n'existe pas au tactile ; on rend `.row-actions`
  toujours visible sous 819 px pour que renommer/télécharger/supprimer restent atteignables.
- **`tree-pane` borné en hauteur** : l'arbre garde son défilement propre et laisse une hauteur
  utile à l'aperçu empilé dessous, dans la hauteur `calc(100vh - 64px)` de l'hôte.
