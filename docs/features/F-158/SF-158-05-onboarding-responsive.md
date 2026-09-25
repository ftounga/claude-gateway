# Mini-spec — [F-158 / SF-158-05] Onboarding responsive

## Identifiant

`F-158 / SF-158-05`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-05-onboarding-responsive`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous ~819 px, l'écran d'onboarding (premier lancement) passe en une seule colonne et
reste confortable au doigt (cartes de mode empilées, boutons d'action pleine largeur,
cibles tactiles ≥ 44 px, aucun scroll horizontal) ; le desktop (≥ 820 px) reste inchangé.

---

## Comportement attendu

### Cas nominal

- Desktop (≥ 820 px) : la grille de modes garde `repeat(auto-fit, minmax(240px, 1fr))` et
  les actions restent en ligne — aucun changement.
- Téléphone (< 819 px) :
  - Le conteneur `.onboarding` réduit son padding latéral (24 px → 16 px) pour garder une
    gouttière ≥ 16 px sans resserrer le contenu.
  - `.onboarding__modes` bascule en `minmax(0, 1fr)` (une colonne) : les deux cartes Hosted /
    BYOK s'empilent pleine largeur, aucune plus large que l'écran.
  - `.onboarding__actions` s'empile en colonne, chaque bouton prenant toute la largeur et une
    hauteur ≥ 44 px (cible tactile au pouce), l'action principale d'abord visuellement.
  - Le fil du parcours (stepper) prend toute la largeur disponible ; aucun débordement horizontal.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Profil non chargé (erreur `me`) | Le libellé de repli reste lisible pleine largeur, aucun scroll-x |
| Libellé de mode long | La carte est contrainte par `minmax(0, 1fr)` ; le texte revient à la ligne, pas de débordement |
| Hint e-mail non vérifié affiché | La pastille d'astuce revient à la ligne proprement, aucun scroll-x |

---

## Critères d'acceptation

- [ ] Sous 819 px, `.onboarding__modes` s'affiche en une seule colonne (cartes empilées pleine largeur).
- [ ] Sous 819 px, `.onboarding__actions` s'empile verticalement ; chaque bouton fait ≥ 44 px de haut et occupe toute la largeur.
- [ ] Aucun élément n'est plus large que le viewport à ~400 px ; aucun scroll horizontal de la page.
- [ ] À ≥ 820 px, le rendu est identique à l'existant (aucune régression desktop).
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; jetons `--cg-*` uniquement (aucune couleur ajoutée).
- [ ] Le build frontend passe (`npm run build`) — vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) : inchangé.
- Les autres écrans de F-158 (chat, postes, forge-rail, gouvernance, rapports, admin/usage,
  space-pitch, terminal Atelier) — chacun est une SF distincte.
- Toute logique métier, tout endpoint, toute migration, tout DTO, tout changement de parcours
  d'onboarding. Pur frontend, display-only.
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

- `OnboardingComponent` (`onboarding/onboarding.component.{scss,html}`) — ajout d'un bloc
  `@media (max-width: 819px)` dans la feuille du composant. Le HTML reste inchangé sur le fond ;
  seule la classe portant les actions est déjà en place (`.onboarding__actions`). Aucune nouvelle
  feuille SCSS nécessaire (règles minimes, budget F-117 respecté).

---

## Plan de test

### Tests unitaires (composant)

- [ ] `onboarding.component.spec.ts` — tests existants conservés (chargement e-mail, parcours
      HOSTED/BYOK, skip, non-régression navigation).
- [ ] Ajout : garde-fou markup — la grille `.onboarding__modes` porte bien les deux cartes de mode
      (patron responsive) et le conteneur d'actions `.onboarding__actions` est présent.

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

- **Navigation / routing** : non touché. L'onboarding conserve exactement les mêmes routes de
  sortie (`/chat`, `/billing`) et le même parcours (stepper non linéaire, boutons Continuer /
  Retour / Passer / Terminer). Aucune route ni guard modifié — pur affichage CSS.
- **Auth / tenant / plans** : non touchés (pur affichage).

---

## Notes et décisions

- **D1 (CADRAGE)** : réutilisation du point de rupture 819 px et de la forme « colonnes fixes →
  1 colonne » du DESIGN_SYSTEM (`_forge-layout-shell.scss:148`). Aucune nouvelle route.
- **D4** : jamais de scroll-x → `minmax(0, 1fr)` (et non `1fr` seul) pour empêcher un item de
  grille de déborder à cause de son contenu (`min-width:auto` par défaut).
- **Cibles ≥ 44 px** : les boutons d'action (`mat-flat-button` / `mat-stroked-button` /
  `mat-button`) reçoivent `min-height: 44px` et `width: 100%` sous 819 px.
- **Gouttière ≥ 16 px** : padding latéral du conteneur ramené à 16 px sous 819 px (au lieu de
  24 px) pour laisser plus de largeur utile au contenu tout en gardant une marge de sécurité.
- **Budget SCSS (F-117 / D5)** : les règles mobile ajoutées sont minimes ; `onboarding.component.scss`
  reste sous le budget de build. Décision : règles conservées dans la feuille du composant, pas de
  feuille dédiée nécessaire.
