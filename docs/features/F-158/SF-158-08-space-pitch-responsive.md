# Mini-spec — [F-158 / SF-158-08] space-pitch responsive

## Identifiant

`F-158 / SF-158-08`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-08-space-pitch-responsive`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous ~819 px, le bloc de présentation d'espace non souscrit (`space-pitch`) passe en une
seule colonne pleine largeur, avec une gouttière latérale ≥ 16 px, des cibles tactiles
≥ 44 px et aucun scroll horizontal ; le desktop (≥ 820 px) reste inchangé.

---

## Comportement attendu

### Cas nominal

- Desktop (≥ 820 px) : le bloc garde `max-width: 760px`, son padding de 32 px, la grille de
  points en `repeat(auto-fit, minmax(200px, 1fr))` et la ligne d'essai en `space-between` —
  **aucun changement**.
- Téléphone (< 819 px) :
  - `.space-pitch` réduit son padding de 32 px à 16 px (gouttière latérale ≥ 16 px, plus de
    largeur utile) ; la `margin` verticale est conservée.
  - `.space-pitch__points` bascule en `minmax(0, 1fr)` (une colonne) : chaque argument s'empile
    pleine largeur, aucun plus large que l'écran.
  - `.space-pitch__trial` s'empile en colonne (`align-items: stretch`) : le libellé d'essai
    au-dessus, le groupe d'actions en dessous pleine largeur.
  - `.space-pitch__actions` passe en colonne ; chaque lien-bouton (`J'ai un code d'essai`,
    `Voir les formules`) occupe toute la largeur et fait ≥ 44 px de haut (cible tactile).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Titre / lede long | Le texte revient à la ligne dans le `minmax(0, 1fr)`, aucun débordement horizontal |
| Libellé de point long | La carte de point est contrainte par la colonne unique ; le texte enveloppe, pas de scroll-x |
| Libellé d'action long | Le lien-bouton pleine largeur enveloppe son texte, aucun débordement |

---

## Critères d'acceptation

- [ ] Sous 819 px, `.space-pitch__points` s'affiche en une seule colonne (arguments empilés pleine largeur).
- [ ] Sous 819 px, `.space-pitch__trial` s'empile verticalement et `.space-pitch__actions` passe en colonne ; chaque lien-bouton fait ≥ 44 px de haut et occupe toute la largeur.
- [ ] Sous 819 px, la gouttière latérale du bloc est ≥ 16 px.
- [ ] Aucun élément n'est plus large que le viewport à ~400 px ; aucun scroll horizontal de la page.
- [ ] À ≥ 820 px, le rendu est identique à l'existant (aucune régression desktop).
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; jetons `--cg-*` uniquement (aucune couleur ajoutée).
- [ ] Le build frontend passe (`npm run build`) — vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) : inchangé.
- Les autres écrans de F-158 (chat, postes, forge-rail, gouvernance, onboarding, rapports,
  admin/usage, terminal Atelier) — chacun est une SF distincte.
- Toute logique métier, tout endpoint, toute migration, tout DTO, tout changement de contenu du
  pitch. Pur frontend, display-only.
- Toute modification de `docs/PRODUCT_SPEC.md` (étape 6 groupée séparément).

---

## Technique

### Endpoint(s)

Aucun. Pur frontend.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- `SpacePitchComponent` (`shared/space-pitch/space-pitch.component.{scss,html}`) — ajout d'un bloc
  `@media (max-width: 819px)` dans la feuille du composant. Le HTML reçoit tout au plus l'ajout de
  classes déjà présentes (`.space-pitch__code`, `.space-pitch__plans` existent). Aucune nouvelle
  feuille SCSS nécessaire (règles minimes, budget F-117 respecté).

---

## Plan de test

### Tests unitaires (composant)

- [ ] `space-pitch.component.spec.ts` — tests existants conservés (rendu Vigie / Forge, points,
      liens code / formules, absence de montant).
- [ ] Ajout : garde-fou markup responsive — la grille `.space-pitch__points` est présente et
      porte les points, et le conteneur d'actions `.space-pitch__actions` existe.

### Tests d'intégration

- Non applicable (pur affichage CSS ; la responsivité est vérifiée par le build + revue du `@media`).

### Isolation workspace

- [x] Non applicable — raison : aucun accès données, pur affichage CSS.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF indépendante et démo-able isolément, cf. CADRAGE F-158 §4).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Navigation / routing** : non touché. Les liens `routerLink` vers `/billing` (code d'essai et
  formules) sont conservés à l'identique ; aucune route ni guard modifié — pur affichage CSS.
- **Auth / tenant / plans** : non touchés (pur affichage).

---

## Notes et décisions

- **D1 (CADRAGE)** : réutilisation du point de rupture 819 px et de la forme « colonnes fixes →
  1 colonne » du DESIGN_SYSTEM (`_forge-layout-shell.scss:148`). Aucune nouvelle route.
- **D4** : jamais de scroll-x → `minmax(0, 1fr)` (et non `1fr` seul) pour empêcher un item de
  grille de déborder à cause de son contenu (`min-width:auto` par défaut).
- **Cibles ≥ 44 px** : les liens-boutons (`mat-flat-button` / `mat-stroked-button`) reçoivent
  `min-height: 44px` et `width: 100%` sous 819 px.
- **Gouttière ≥ 16 px** : padding du bloc ramené de 32 px à 16 px sous 819 px (précédent SF-158-05).
- **Budget SCSS (F-117 / D5)** : les règles mobile ajoutées sont minimes ; `space-pitch.component.scss`
  reste sous le budget de build. Décision : règles conservées dans la feuille du composant, pas de
  feuille dédiée nécessaire.
