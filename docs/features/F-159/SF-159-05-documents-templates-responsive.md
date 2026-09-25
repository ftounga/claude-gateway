# Mini-spec — F-159 / SF-159-05 — Écrans Documents + Modèles responsive

## Identifiant

`F-159 / SF-159-05`

## Feature parente

`F-159` — Responsive de finition (suite de F-158). Le Lot 0 (SF-159-01, mergé `#894`) a posé
les fondations transverses : `box-sizing` global, point de rupture unique **819 px** via
`_breakpoints.scss` (`$bp-phone` + `@mixin phone`), utilitaires `.page` / `.table-scroll`,
garde-fou dialogs. Cette SF **adopte** ces fondations sur les deux écrans « bibliothèque » :
**Documents** (`/documents`) et **Modèles de prompts** (`/templates`).

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-159-05-documents-templates-responsive`

---

## Objectif

> En une phrase : rendre les écrans **Documents** et **Modèles** confortables au téléphone
> (~400 px) — tableaux Material enveloppés dans `.table-scroll` (seul le tableau défile, jamais
> la page), cartes et en-têtes resserrés, boutons d'action tactiles (≥ 44 px) — sans jamais de
> défilement horizontal de page et sans régresser le desktop (≥ 820 px).

---

## Comportement attendu

### Cas nominal

1. **Tableaux enveloppés** : chaque `<table mat-table>` (liste des documents, liste des modèles)
   est placé dans un conteneur `.table-scroll` (utilitaire du Lot 0). À 400 px, un tableau plus
   large que l'écran défile **chez lui** (`overflow-x: auto`), la **page** ne défile jamais
   horizontalement. Le paginateur reste hors du conteneur de défilement.
2. **Cartes resserrées sous 819 px** : les cartes (`__submit`, `__list`, `__detail`, `__form`)
   passent d'un padding de 24 px à `--cg-space-3` (16 px) pour laisser plus de contenu utile ;
   l'espacement vertical de la page passe aussi à `--cg-space-3`.
3. **En-tête Modèles empilé** : sous 819 px, l'en-tête (`__header`) passe en colonne et le
   bouton « Nouveau modèle » passe **pleine largeur** et **tactile** (≥ 44 px).
4. **Lien Documents pleine largeur** : sous 819 px, le lien « Poser une question… »
   (`__ask-link`) passe **pleine largeur** et **tactile** (≥ 44 px).
5. **Actions de ligne tactiles** : sous 819 px, les boutons d'action des tableaux (Voir /
   Supprimer ; Copier / Modifier / Supprimer) atteignent **≥ 44 px** de haut.
6. **Pas de défilement horizontal de page** : à 400 px, aucune barre horizontale au niveau page.

### Cas d'erreur

> Feature purement CSS/SCSS + gabarit (aucun endpoint, aucune donnée, aucune logique TS). Les
> « cas d'erreur » sont des risques de régression visuelle traités explicitement.

| Situation | Comportement attendu |
|-----------|---------------------|
| Desktop ≥ 820 px | Layout inchangé : cartes à 24 px, en-tête en ligne, actions à leur taille par défaut. Tout le responsive est borné sous `@include bp.phone`. |
| Budget de style par feuille (F-117) | Feuilles actuelles très en dessous du seuil (documents ≈ 1,5 ko, templates ≈ 0,95 ko ; warning à 4 ko). Le bloc responsive (~0,6 ko/feuille) est ajouté **dans la feuille existante** — pas de dépassement, donc pas de feuille dédiée. |
| Point de rupture / conteneur | **Réutilise** le Lot 0 : `@use '../../styles/breakpoints' as bp;` + `@include bp.phone` ; enveloppe `.table-scroll` globale. Aucun breakpoint ni conteneur local réinventé. |

---

## Critères d'acceptation

- [ ] Chaque tableau Material des deux écrans est enveloppé dans `.table-scroll`.
- [ ] Sous 819 px, `.documents` / `.templates` et leurs cartes portent un padding `--cg-space-3`.
- [ ] Sous 819 px, `.templates__header` est en colonne et son bouton fait ≥ 44 px, pleine largeur.
- [ ] Sous 819 px, `.documents__ask-link` fait ≥ 44 px et est pleine largeur.
- [ ] Sous 819 px, les boutons d'action des tableaux font ≥ 44 px de haut.
- [ ] Charte respectée : uniquement jetons `--cg-*` et unités relatives, aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] Le responsive s'appuie sur le mixin `bp.phone` du Lot 0 (aucun `@media` recopié à la main).
- [ ] `npm run build` vert (budgets F-117 respectés).
- [ ] Pas de régression desktop (≥ 820 px) : tout est borné sous `bp.phone`.
- [ ] Un test CSSOM par écran vérifie les règles responsive fondatrices.

---

## Périmètre

### Hors scope (explicite)

- Les autres écrans de F-159 (chacun sa SF).
- Le desktop (≥ 820 px) : inchangé.
- La coquille (F-151), les écrans de contenu (F-158), la PWA (F-152), les notifications (F-153).
- Tout backend, endpoint, migration, logique TypeScript. **Pur frontend / CSS + gabarit.**
- La mise à jour de `PRODUCT_SPEC.md` (étape 6 groupée au niveau de la vague F-159).

---

## Préoccupations transversales

| Préoccupation | Déclenchée ? | Analyse d'impact |
|--------------|--------------|------------------|
| Auth / Principal | Non | Aucune touche à l'auth. |
| Contexte tenant | Non | Aucun accès données ; aucun `user_id`. |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucune route ni guard modifié. |
| CSS (feuille de composant) | Oui, borné | Blocs responsive ajoutés **uniquement** dans `documents.component.scss` et `templates.component.scss` (styleUrl encapsulé Angular). L'utilitaire `.table-scroll` est global (Lot 0) et déjà partagé. Composants impactés : **uniquement** `frontend/src/app/documents/*` et `frontend/src/app/templates/*`. Tout est sous `bp.phone`. |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration.

### Composants / fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `frontend/src/app/documents/documents.component.html` | MODIF | Enveloppe le `<table mat-table>` dans `.table-scroll`. |
| `frontend/src/app/documents/documents.component.scss` | MODIF | `@use` breakpoints + bloc `@include bp.phone`. |
| `frontend/src/app/documents/documents-responsive.spec.ts` | CRÉATION | Test CSSOM des règles responsive. |
| `frontend/src/app/templates/templates.component.html` | MODIF | Enveloppe le `<table mat-table>` dans `.table-scroll`. |
| `frontend/src/app/templates/templates.component.scss` | MODIF | `@use` breakpoints + bloc `@include bp.phone`. |
| `frontend/src/app/templates/templates-responsive.spec.ts` | CRÉATION | Test CSSOM des règles responsive. |

---

## Plan de test

### Tests unitaires (CSSOM — styles du composant injectés par Angular)

**Documents :**
- [ ] Il existe une règle `@media (max-width: 819px)` qui met `.documents` en padding `--cg-space-3`.
- [ ] Sous 819 px, `.documents__ask-link` porte `width: 100%` et `min-height: 44px`.
- [ ] Sous 819 px, les boutons de `.documents__table` portent `min-height: 44px`.
- [ ] Non-régression : aucune couleur littérale hors jeton dans le bloc responsive.

**Modèles :**
- [ ] Il existe une règle `@media (max-width: 819px)` qui met `.templates__header` en `flex-direction: column`.
- [ ] Sous 819 px, le bouton d'en-tête porte `width: 100%` et `min-height: 44px`.
- [ ] Sous 819 px, les boutons de `.templates__table` portent `min-height: 44px`.
- [ ] Non-régression : aucune couleur littérale hors jeton dans le bloc responsive.

### Tests d'intégration

- [ ] `npm run build` (production) vert, budgets respectés.

### Isolation workspace

- [ ] Non applicable — feature purement CSS, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-159-01` (fondations) — **mergée** (`#894`). Fournit `bp.phone` et `.table-scroll`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Bloc responsive inline (pas de feuille dédiée)** : contrairement à SF-159-02 (feuilles proches
  du budget), les deux feuilles ici sont très en dessous du seuil F-117 (warning à 4 ko). Ajouter
  ~0,6 ko dans chaque feuille existante reste sous le budget et garde la cascade lisible ; la règle
  « feuille dédiée » ne se déclenche qu'en cas de dépassement.
- **`.table-scroll` global** : l'enveloppe est l'utilitaire du Lot 0 (styles globaux non
  encapsulés), pas une classe de composant — cohérent avec la consigne « envelopper les tables
  Material dans `.table-scroll` ».
- **Tactile ≥ 44 px** : les boutons Material `mat-button` font ~36 px par défaut ; on relève leur
  `min-height` sous 819 px pour que Voir/Copier/Modifier/Supprimer restent atteignables au pouce.
