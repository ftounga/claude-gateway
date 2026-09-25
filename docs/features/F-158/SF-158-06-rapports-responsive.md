# Mini-spec — [F-158 / SF-158-06] Rapports responsive

## Identifiant

`F-158 / SF-158-06`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-06-rapports-responsive`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous ~819 px, l'écran Rapports d'usage passe ses grilles de tuiles (résumé) en une seule colonne
pleine largeur et isole ses tableaux larges dans un conteneur `overflow-x:auto`, de sorte que la
**page** ne défile jamais horizontalement et que tous les gestes atteignent ≥ 44 px au pouce ; le
desktop (≥ 820 px) reste strictement inchangé.

---

## Comportement attendu

### Cas nominal

- Desktop (≥ 820 px) : la grille de tuiles `.reports__summary`
  (`repeat(auto-fit, minmax(200px, 1fr))`) et le tableau mensuel `mat-table` sont **inchangés**.
- Téléphone (< 819 px) :
  - `.reports__summary` bascule en `minmax(0, 1fr)` (une colonne) — les tuiles s'empilent pleine
    largeur, aucun débordement.
  - Le **tableau mensuel** (`.reports__table`, un `mat-table` à 5 colonnes aux cellules
    `white-space` par défaut) est placé dans un conteneur `.reports__table-wrap` en
    `overflow-x:auto` : le tableau défile **dans son cadre**, la page jamais.
  - Les **tableaux « Par client »** (`.reports__projects`) conservent leur conteneur
    `.reports__projects-wrap` (`overflow-x:auto`) déjà en place — aucun changement de structure.
  - Le sélecteur de fenêtre `.reports__window` (`mat-button-toggle-group` « 3 mois / 6 mois … »)
    reste tactile (≥ 44 px) et le `.reports__section-head` passe déjà à la ligne (`flex-wrap`).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Rapport vide (aucune période) | La carte `.reports__empty` reste lisible pleine largeur, aucun scroll-x |
| Chargement du rapport en échec | Le spinner puis l'état résultant restent pleine largeur, aucun scroll-x |
| Consommation par client vide (403 / rien attribué) | La carte `.reports__empty` (section client) reste lisible pleine largeur |
| Période très longue / grand nombre de tokens | Contenu contraint : tableaux dans leur conteneur défilant isolé ; la page ne déborde pas |

---

## Critères d'acceptation

- [ ] Sous 819 px, `.reports__summary` s'affiche en une seule colonne (tuiles empilées pleine largeur).
- [ ] Sous 819 px, le tableau mensuel est dans un conteneur `overflow-x:auto` : la **page** ne défile
      jamais horizontalement à ~400 px, même avec des colonnes larges.
- [ ] Les tableaux « Par client » restent dans leur conteneur défilant isolé (non-régression).
- [ ] Les gestes de l'écran (bascules de période, pagination) ont une hauteur ≥ 44 px sous 819 px.
- [ ] À ≥ 820 px, le rendu est identique à l'existant (aucune régression desktop).
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; jetons `--cg-*` uniquement (aucune couleur ajoutée).
- [ ] Le build frontend passe (`npm run build`) — vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) : inchangé.
- Les autres écrans de F-158 (chat, postes, forge-rail, gouvernance, onboarding, admin/usage,
  space-pitch, terminal Atelier) — chacun est une SF distincte.
- Toute logique métier, tout endpoint, toute migration, tout DTO. Pur frontend, display-only.
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

- `ReportsComponent` (`reports/reports.component.{scss,html}`) :
  - HTML : envelopper le `mat-table` mensuel (+ son `mat-paginator`) dans un conteneur
    `.reports__table-wrap` (`overflow-x:auto`). Reste du markup inchangé (les tableaux client ont
    déjà `.reports__projects-wrap`).
  - SCSS : ajout d'un bloc `@media (max-width: 819px)` (grille résumé → 1 colonne, cibles ≥ 44 px)
    et de la règle `.reports__table-wrap { overflow-x: auto; }`. Aucune nouvelle feuille SCSS
    (règles minimes ; voir note budget F-117).

---

## Plan de test

### Tests unitaires (composant)

- [ ] `reports.component.spec.ts` — tests existants conservés (chargement du rapport, table remplie,
      consommation par client) : non-régression du markup.
- [ ] Ajout : le tableau mensuel est enveloppé dans un conteneur `.reports__table-wrap`
      (garde-fou markup pour l'isolation du scroll horizontal).

### Tests d'intégration

- Non applicable (pur affichage CSS ; la responsivité est vérifiée par le build + revue du `@media`).

### Isolation workspace

- [x] Non applicable — raison : aucun accès données, pur affichage CSS. Les tests existants du
      composant (services mockés, isolation portée par le backend) restent verts, non touchés.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF indépendante et démo-able isolément, cf. CADRAGE F-158 §4).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Navigation / routing** : non touchée. Aucun nouveau chemin, aucun guard, aucune redirection ;
  la même URL `/rapports` sert les deux tailles (display-only).
- **Auth / tenant / plans** : non touchés (pur affichage). Aucun accès données modifié.

---

## Notes et décisions

- **D1 (CADRAGE)** : réutilisation du point de rupture 819 px et de la forme « colonnes fixes →
  1 colonne » du DESIGN_SYSTEM (`_forge-layout-shell.scss:148`, `DESIGN_SYSTEM.md:686`). Aucune
  nouvelle route.
- **D4 (CADRAGE)** : jamais de scroll-x → grille en `minmax(0, 1fr)` (et non `1fr` seul) et
  **tableaux** larges (`mat-table` mensuel, tables client) dans un conteneur `overflow-x:auto`
  isolé, jamais la page.
- **Cibles ≥ 44 px** : `min-height: 44px` sous 819 px sur les bascules `mat-button-toggle` et les
  boutons de pagination.
- **Budget SCSS (F-117 / D5)** : les règles mobile ajoutées sont minimes (~0,6 kB). Décision :
  règles conservées dans la feuille du composant (cohérent avec SF-158-04). Vérifier au build que le
  WARNING éventuel de budget est non bloquant (build vert).
