# Mini-spec — [F-158 / SF-158-07] Admin / usage responsive

## Identifiant

`F-158 / SF-158-07`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-07-admin-usage-responsive`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous ~819 px, l'écran d'administration « Consommation » (F-61) passe ses tuiles de totaux
en une seule colonne, le tableau d'usage défile dans son conteneur isolé (jamais la page),
et le sélecteur de période reste tactile (≥ 44 px) ; le desktop (≥ 820 px) reste inchangé.

---

## Comportement attendu

### Cas nominal

- Desktop (≥ 820 px) : la grille `.usage-admin__totals` garde
  `repeat(auto-fit, minmax(180px, 1fr))`, le tableau et le sélecteur de période restent
  en ligne — aucun changement.
- Téléphone (< 819 px) :
  - Le conteneur `.usage-admin` réduit son padding latéral (24 px → 16 px) pour garder une
    gouttière ≥ 16 px tout en libérant de la largeur utile.
  - `.usage-admin__totals` bascule en `minmax(0, 1fr)` (une colonne) : les trois tuiles
    (Tokens d'entrée / Tokens de sortie / Coût estimé) s'empilent pleine largeur, aucune plus
    large que l'écran.
  - Le tableau d'usage `.usage-admin__table` reste dans `.usage-admin__table-wrap`
    (`overflow-x: auto`, déjà en place) : il défile horizontalement **dans son cadre**, la
    page ne défile jamais horizontalement (D4).
  - Le sélecteur de période (`mat-button-toggle-group`) revient à la ligne sous le titre
    (l'en-tête est déjà `flex-wrap: wrap`) et chaque bascule fait ≥ 44 px de haut (cible
    tactile au pouce).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune consommation (état vide) | Le libellé de repli reste lisible pleine largeur, aucun scroll-x |
| Erreur de chargement (`getUsage` échoue) | La zone de données reste vide, l'en-tête et le sélecteur restent utilisables pleine largeur, aucun scroll-x |
| E-mail de compte très long dans le tableau | Le tableau défile dans son conteneur `overflow-x:auto` ; la page ne déborde pas |

---

## Critères d'acceptation

- [ ] Sous 819 px, `.usage-admin__totals` s'affiche en une seule colonne (tuiles empilées pleine largeur).
- [ ] Sous 819 px, le tableau d'usage défile dans son conteneur `.usage-admin__table-wrap` ; la page ne défile jamais horizontalement à ~400 px.
- [ ] Sous 819 px, chaque bascule du sélecteur de période fait ≥ 44 px de haut (cible tactile).
- [ ] Aucun élément n'est plus large que le viewport à ~400 px ; aucun scroll horizontal de la page.
- [ ] À ≥ 820 px, le rendu est identique à l'existant (aucune régression desktop).
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; jetons `--cg-*` uniquement (aucune couleur ajoutée).
- [ ] Le build frontend passe (`npm run build`) — vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) : inchangé.
- Les autres écrans de F-158 (chat, postes, forge-rail, gouvernance, onboarding, rapports,
  space-pitch, terminal Atelier) — chacun est une SF distincte.
- Toute logique métier, tout endpoint, toute migration, tout DTO, tout changement du service
  d'usage ou du calcul de part/évolution. Pur frontend, display-only.
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

- `AdminUsageComponent` (`admin/usage/admin-usage.component.{scss,html}`) — ajout d'un bloc
  `@media (max-width: 819px)` en fin de feuille du composant. Le HTML reste inchangé (la
  structure `.usage-admin__totals` / `.usage-admin__table-wrap` est déjà en place ; le
  conteneur défilant `overflow-x:auto` existe déjà). Aucune nouvelle feuille SCSS nécessaire
  (règles minimes, budget F-117 respecté).

---

## Plan de test

### Tests unitaires (composant)

- [ ] `admin-usage.component.spec.ts` — tests existants conservés (totaux, liste des comptes,
      absence de nom projet/poste, changement de période, état vide, erreur, division par zéro).
- [ ] Ajout : garde-fou markup — la grille des totaux `.usage-admin__totals` et le conteneur
      défilant `.usage-admin__table-wrap` (patron responsive, jamais de scroll-x de page) sont
      présents quand des données existent.

### Tests d'intégration

- Non applicable (pur affichage CSS ; la responsivité est vérifiée par le build + revue du `@media`).

### Isolation workspace

- [x] Non applicable — raison : aucun accès données, pur affichage CSS (l'écran consomme un
  endpoint admin déjà protégé, non touché ici).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF indépendante et démo-able isolément, cf. CADRAGE F-158 §4).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Navigation / routing** : non touché. L'écran conserve exactement sa route admin ; aucune
  route ni guard modifié — pur affichage CSS. Aucun repli d'affichage introduisant un état de
  navigation (contrairement à SF-158-01/chat).
- **Auth / tenant / plans** : non touchés (pur affichage ; l'endpoint admin reste inchangé).

---

## Notes et décisions

- **D1 (CADRAGE)** : réutilisation du point de rupture 819 px et de la forme « colonnes fixes →
  1 colonne » du DESIGN_SYSTEM (`_forge-layout-shell.scss:148`). Aucune nouvelle route.
- **D4** : jamais de scroll-x → `.usage-admin__totals` en `minmax(0, 1fr)` (et non `1fr` seul)
  pour empêcher un item de grille de déborder à cause de son contenu (`min-width:auto` par
  défaut) ; le tableau large reste isolé dans `.usage-admin__table-wrap` (`overflow-x:auto`
  déjà en place).
- **Cibles ≥ 44 px** : les bascules du sélecteur de période (`mat-button-toggle`) reçoivent
  `min-height: 44px` sous 819 px.
- **Gouttière ≥ 16 px** : padding du conteneur ramené à `var(--cg-space-3)` (16 px) sous 819 px
  au lieu de `var(--cg-space-4)` (24 px).
- **Budget SCSS (F-117 / D5)** : les règles mobile ajoutées sont minimes ;
  `admin-usage.component.scss` reste sous le budget de build. Décision : règles conservées dans
  la feuille du composant, pas de feuille dédiée nécessaire.
