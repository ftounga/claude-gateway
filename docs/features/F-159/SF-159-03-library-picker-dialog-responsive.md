# Mini-spec — F-159 / SF-159-03 — Dialog library-picker responsive

## Identifiant

`F-159 / SF-159-03`

## Feature parente

`F-159` — Responsive de finition. Après les fondations transverses (SF-159-01,
Lot 0 : `box-sizing`, point de rupture unique 819 px, `.page`, `.table-scroll`,
garde-fou global des dialogs), cette SF rattache l'écran **dialog library-picker**
(`chat/library-picker`) au responsive téléphone en réutilisant le Lot 0.

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-159-03-library-picker-responsive`

---

## Objectif

> En une phrase : rendre le dialogue « Importer depuis ma bibliothèque »
> confortable au téléphone (~400 px) — jamais de défilement horizontal, contenu
> qui rétrécit dans le panneau borné à 96vw du Lot 0, cibles tactiles ≥ 44 px —
> sans régresser le desktop (≥ 820 px).

---

## Comportement attendu

### Cas nominal

1. **Le contenu rétrécit avec le panneau.** `.picker-content` cesse d'imposer une
   largeur plancher rigide de 380 px : `min-width: 380px` → `min-width: min(380px, 100%)`.
   Sur desktop la surface a la place → 380 px (inchangé) ; sur un téléphone où le
   Lot 0 borne le panneau overlay à ≤ 96vw et neutralise la `min-width` de la surface,
   le contenu descend jusqu'à la largeur disponible au lieu de forcer un débordement.
2. **Les noms longs s'ellipsent au lieu de forcer la largeur.** La borne rigide
   `.picker-item-name { max-width: 340px }` devient fluide (`max-width: 100%` +
   `min-width: 0` sur la chaîne flex du corps) : le nom occupe l'espace disponible et
   s'ellipse (`text-overflow: ellipsis` déjà présent), sans jamais élargir la ligne.
3. **Cibles tactiles ≥ 44 px sous 819 px.** Sous le point de rupture téléphone
   (mixin `bp.phone` du Lot 0), les boutons d'action (`Annuler`, `Importer`) portent
   `min-height: 44px` ; l'item de liste (zone de coche pleine largeur) garantit une
   hauteur de frappe ≥ 44 px.
4. **Aucune régression desktop.** Tout ce qui est propre au téléphone est borné sous
   `@include bp.phone`. Le reste (`min(380px, 100%)`, `max-width: 100%`, `min-width: 0`)
   est neutre à ≥ 820 px (la surface a la place, le résultat est identique à avant).

### Cas d'erreur

> Feature purement CSS/SCSS d'un seul composant (aucun endpoint, aucune donnée, aucun
> changement de logique TS/HTML). Les « cas d'erreur » sont des risques de régression
> visuelle traités explicitement.

| Situation | Comportement attendu |
|-----------|---------------------|
| Nom de fichier très long sur téléphone | Ellipsé dans la largeur disponible, pas de scroll-x (tooltip conservé au survol/appui long). |
| Beaucoup de documents (liste longue) sur téléphone | Défilement **vertical** du `mat-dialog-content`, jamais horizontal. |
| États vide / chargement / erreur | Centrés, `padding` inchangé, tiennent dans 96vw. |
| Desktop ≥ 820 px | Rendu identique à avant (380–520 px), aucune bordure/hauteur modifiée. |

---

## Critères d'acceptation

- [ ] `.picker-content` porte `min-width: min(380px, 100%)` (plus de `380px` rigide).
- [ ] `.picker-item-name` ne porte plus de `max-width` en pixels rigide ; il s'ellipse dans l'espace disponible (`max-width: 100%`, chaîne flex `min-width: 0`).
- [ ] Sous 819 px (mixin `bp.phone` du Lot 0), les boutons d'action et la zone de coche d'un item font ≥ 44 px de haut.
- [ ] Le breakpoint et le garde-fou dialog proviennent du Lot 0 : `@use '../../styles/breakpoints'` + neutralisation `min-width` de la surface **déjà** globale — aucun `@media (max-width: 819px)` recopié en dur, aucun breakpoint local inventé.
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; seulement jetons `--cg-*` et unités relatives. Aucune valeur littérale de couleur ajoutée.
- [ ] Pas de défilement horizontal du dialog ni de la page à ~400 px.
- [ ] Pas de régression desktop (≥ 820 px) : rendu inchangé.
- [ ] `npm run build` vert (budgets F-117 respectés ; feuille de composant sous plafond).
- [ ] Tests unitaires du composant toujours verts (comportement TS inchangé).

---

## Périmètre

### Hors scope (explicite)

- Toute modification de la logique TS ou du HTML du composant (comportement, sélection,
  filtrage des statuts, formatage) — **inchangés**.
- Les fondations globales (SF-159-01, Lot 0) — **réutilisées**, non modifiées.
- Les autres écrans/dialogs de F-159 (chacun sa SF, parallélisme).
- Le desktop (≥ 820 px) : inchangé.
- Backend, endpoint, migration, données. **Pur frontend, un seul composant.**
- La mise à jour de `PRODUCT_SPEC.md` (étape 6 groupée au niveau de la vague F-159).

---

## Préoccupations transversales

| Préoccupation | Déclenchée ? | Analyse d'impact |
|--------------|--------------|------------------|
| Auth / Principal | Non | Aucune touche à l'auth. |
| Contexte tenant | Non | Aucun accès données ; isolation `user_id` garantie côté backend, inchangée. |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucune route ni guard. |
| CSS global | Non | Changements **bornés à la feuille du composant** `library-picker-dialog.component.scss`. Le seul style global consommé (mixin `phone`, garde-fou dialog) provient du Lot 0 déjà mergé, non modifié. Aucun impact silencieux hors de cet écran. |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration.

### Composants / fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `frontend/src/app/chat/library-picker/library-picker-dialog.component.scss` | MODIF | `@use` breakpoints Lot 0 ; `min-width: min(380px,100%)` ; nom fluide + `min-width: 0` ; cibles ≥ 44 px sous `bp.phone`. |

Aucun changement TS/HTML/spec de logique (les tests existants restent verts tels quels).

---

## Plan de test

### Tests unitaires

- [ ] Les 6 specs existantes du composant restent vertes (aucune logique TS/HTML modifiée) :
  filtrage des statuts exploitables, état d'erreur, bascule + confirm, annulation, formatage de taille.

### Tests d'intégration / build

- [ ] `npm run build` (production) vert, budgets F-117 respectés.

### Vérification responsive (manuelle, tracée)

- [ ] À ~400 px : dialog ≤ 96vw, pas de scroll-x, nom long ellipsé, boutons ≥ 44 px.
- [ ] À ≥ 820 px : rendu identique à avant.

### Isolation workspace

- [ ] Non applicable — feature purement CSS d'un composant d'affichage, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-159-01` (Lot 0, fondations responsive globales) — **mergée** (commit `29a6e7b5`). Réutilisée.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Réutilisation stricte du Lot 0** : le point de rupture vient de
  `@use '../../styles/breakpoints' as bp;` (mixin `bp.phone`), le bornage du panneau à
  96vw et la neutralisation de la `min-width` de la surface Material sont **déjà** des
  règles globales de SF-159-01. Cette SF n'invente ni breakpoint ni conteneur local.
- **`min(380px, 100%)`** : conforme à la consigne de vague (remplacer `min-width: 380px`).
  Neutre sur desktop, laisse rétrécir sur téléphone.
- **Cibles ≥ 44 px** bornées sous `bp.phone` pour ne pas alourdir le desktop.
