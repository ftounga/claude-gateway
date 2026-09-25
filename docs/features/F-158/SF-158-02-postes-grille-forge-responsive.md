# Mini-spec — [F-158 / SF-158-02] Postes (grille Forge) responsive

## Identifiant

`F-158 / SF-158-02`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-02-postes-grille-forge-responsive`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous ~819 px, la grille de tuiles de projets de l'écran Postes (Forge) passe en une seule
colonne pleine largeur, sans qu'aucune tuile ne dépasse l'écran et sans jamais de scroll
horizontal ; le desktop (≥ 820 px) reste strictement inchangé.

---

## Comportement attendu

### Cas nominal

- Desktop (≥ 820 px) : la grille garde `repeat(auto-fill, minmax(232px, 1fr))` — aucun changement.
- Téléphone (< 819 px) : la grille bascule en `minmax(0, 1fr)` (une colonne). Les tuiles
  (`app-forge-project-tile`, `display:block`) et la tuile fantôme prennent toute la largeur
  disponible ; aucun débordement horizontal de la page.
- Les cibles tactiles du tri des projets (`.poste__sort-option`) atteignent ≥ 44 px de haut sous
  819 px pour rester atteignables au pouce.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Poste sans projet | Le message « Aucun projet… » reste lisible pleine largeur, aucun scroll-x |
| Beaucoup de projets (grille dense) | Empilement vertical propre en une colonne, la page défile verticalement uniquement |
| Contenu de tuile large (nom long) | La tuile est contrainte par `minmax(0, 1fr)` ; le texte est géré par les tuiles (ellipsis existant), pas de débordement de la grille |

---

## Critères d'acceptation

- [ ] Sous 819 px, `.poste__grid` s'affiche en une seule colonne (tuiles empilées pleine largeur).
- [ ] Aucune tuile n'est plus large que le viewport à ~400 px ; aucun scroll horizontal de la page.
- [ ] Les options de tri (`.poste__sort-option`) ont une hauteur ≥ 44 px sous 819 px.
- [ ] À ≥ 820 px, le rendu de la grille est identique à l'existant (aucune régression desktop).
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; jetons `--cg-*` uniquement (ici, aucune couleur ajoutée).
- [ ] Le build frontend passe (`npm run build`) — vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) : inchangé.
- Les autres écrans de F-158 (chat, forge-rail, gouvernance, onboarding, rapports, admin/usage,
  space-pitch, terminal Atelier) — chacun est une SF distincte.
- Le composant `forge-rail` (SF-158-03) et le shell maître–détail (déjà responsive, F-151).
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

- `PostesComponent` (`postes/postes.component.{scss,html}`) — ajout d'un bloc `@media (max-width: 819px)`
  dans la feuille du composant ; HTML inchangé (la structure `.poste__grid` porte déjà les tuiles
  pleine largeur). Aucune nouvelle feuille SCSS nécessaire (règles minimes, budget F-117 respecté).

---

## Plan de test

### Tests unitaires (composant)

- [ ] `postes.component.spec.ts` — la grille des projets rend une tuile par projet (test existant conservé,
      non-régression du markup `.poste__grid`).
- [ ] Ajout : la classe `.poste__grid` est présente et porte les tuiles (garde-fou markup pour le patron responsive).

### Tests d'intégration

- Non applicable (pur affichage CSS ; la responsivité est vérifiée par le build + revue visuelle du `@media`).

### Isolation workspace

- [x] Non applicable — raison : aucun accès données, pur affichage CSS.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF indépendante et démo-able isolément, cf. CADRAGE F-158 §4).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 (CADRAGE)** : réutilisation du point de rupture 819 px et de la forme « colonnes fixes → 1 colonne »
  du DESIGN_SYSTEM (`_forge-layout-shell.scss:148`). Aucune nouvelle route.
- **D4** : jamais de scroll-x → `minmax(0, 1fr)` (et non `1fr` seul) pour empêcher un item de grille de
  déborder à cause de son contenu (`min-width:auto` par défaut).
- **Cibles ≥ 44 px** : ajout `min-height: 44px` sur `.poste__sort-option` sous 819 px.
- **Budget SCSS (F-117 / D5)** : `postes.component.scss` est **déjà** au-dessus du budget de 4 kB sur
  `origin/main` (comme 8 autres composants du dépôt) — WARNING non bloquant, build vert. Les règles
  mobile ajoutées sont minimes (~750 octets) et ne **causent pas** le dépassement ; une feuille dédiée
  ne ramènerait pas la feuille principale sous le budget (dépassement pré-existant et indépendant de
  F-158). Décision : règles conservées dans la feuille du composant, dépassement pré-existant tracé
  ici comme item non bloquant (revue).
