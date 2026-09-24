# Mini-spec — F-151 / SF-151-02 — Shell Atelier responsive (280px 1fr → 1 colonne)

## Identifiant

`F-151 / SF-151-02`

## Feature parente

`F-151` — Atelier mobile (responsive du chemin critique)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-151-02-shell-atelier-responsive`

---

## Objectif

Sous 819 px, faire passer la vue « liste de projets » de l'Atelier de la grille fixe
`280px 1fr` (`atelier.component.scss:9`) à **une seule colonne** pleine largeur, sur le modèle
`/forge`, sans régression au-dessus de 820 px.

---

## Comportement attendu

### Cas nominal

- **≥ 820 px** : rien ne change. La vue « liste » garde sa grille `280px 1fr` — sidebar projets
  à gauche, zone principale (fil d'Ariane, barre de mode, invite « Sélectionnez un projet ») à
  droite. Rendu **byte-identique**.
- **< 819 px** : la grille devient **une colonne** (`minmax(0, 1fr)`). La sidebar projets prend
  toute la largeur (son filet droit devient un filet bas) ; la zone principale s'empile dessous.
  La liste reste pleinement utilisable au doigt ; jamais de défilement horizontal ; la page
  s'écoule verticalement dans `.app-content` (pas de piège de défilement imbriqué).
- **Un projet ouvert** : inchangé à toutes les tailles. La vue terminal (`app-atelier-terminal`)
  **remplace entièrement** la grille (elle n'est pas rendue à l'intérieur de `.atelier-layout`) ;
  la navigation « liste ↔ terminal » se fait déjà par sélection d'un projet, **même URL**
  (`/atelier`), exactement comme `/forge`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun projet (liste vide) | Le message « Aucun projet… » de la sidebar reste lisible pleine largeur, sans scroll-x |
| Redimensionnement < 819 px → ≥ 820 px | La grille `280px 1fr` reprend (le CSS `@media` prime), aucun état à réinitialiser (pur CSS) |

---

## Critères d'acceptation

- [ ] À ≥ 820 px, `.atelier-layout` conserve `grid-template-columns: 280px 1fr` (non-régression desktop).
- [ ] À < 819 px, `.atelier-layout` passe à une colonne ; la sidebar est pleine largeur avec un filet bas (au lieu du filet droit).
- [ ] La vue terminal (projet ouvert) est inchangée à toutes les tailles.
- [ ] Aucun défilement horizontal à 400 px ; le contenu s'écoule verticalement.
- [ ] Aucune nouvelle route, aucun guard, aucune redirection ; la sélection d'un projet ouvre le terminal sur la même URL qu'avant.

---

## Périmètre

### Hors scope (explicite)

- Le shell global (`shell.component`) → SF-151-01 (livrée).
- La **barre d'outils du terminal** et les boutons de décision au pouce → SF-151-03.
- Le pilotage (F-84), l'installabilité (F-152), les notifications (F-153).

---

## Contraintes de validation

Aucune donnée saisie. Point de rupture imposé : **819 px** (`max-width: 819px`), aligné sur le
patron `/forge` et `DESIGN_SYSTEM.md §16`.

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun. Migration : **non applicable**.

### Composants Angular

- `AtelierComponent` — **CSS uniquement** (`atelier.component.scss`) : bloc
  `@media (max-width: 819px)` sur `.atelier-layout`, `.atelier-sidebar` (et `:host`/hauteur pour
  laisser la page s'écouler). Aucune modification de `.ts`/`.html`, aucune logique.

---

## Préoccupation transversale — Navigation / routing

Composants qui résolvent la navigation de l'Atelier : `AtelierComponent` (sélection de projet →
vue terminal, **même URL** `/atelier`). Impact :

- Aucune route ajoutée/modifiée ; aucun guard ; aucune redirection. La bascule liste↔terminal
  existe déjà (signal `activeWorkspaceId()`), inchangée.
- Non-régression : `atelier.component.spec.ts` reste vert (aucun `.ts`/`.html` touché).

---

## Plan de test

### Tests

- [ ] Non-régression : `atelier.component.spec.ts` reste **entièrement vert** (aucune logique touchée).
- [ ] `ng build` production **vert** (SCSS valide, budget de feuille respecté).
- [ ] Vérification structurelle : la vue « liste » monte `.atelier-layout` avec `.atelier-sidebar`
      et `.atelier-main` (déjà couverte par les tests existants).

> **Pourquoi pas de test unitaire de media-query** : le comportement responsive est **purement
> CSS** (aucune logique) et dépend de la largeur réelle du viewport, non pilotable de façon fiable
> dans Karma/ChromeHeadless en test de composant. La validité est garantie par `ng build` + la
> non-régression structurelle ; le rendu au point de rupture 819 px suit le patron `/forge` déjà
> éprouvé.

### Isolation workspace / user_id

- [x] Non applicable — aucun accès données (pur affichage CSS).

---

## Dépendances

Aucune subfeature bloquante (indépendante de SF-151-01). Aucune question ouverte impactée.

---

## Notes et décisions

- **D-nodrawer** : pas de « drawer » à état. La sidebar (liste de projets) et le terminal ne sont
  **jamais** affichés en même temps — quand un projet est ouvert, `app-atelier-terminal` remplace
  toute la grille. Il n'y a donc rien à faire glisser par-dessus autre chose : « une colonne » est
  la forme responsive correcte et honnête (un drawer ajouterait de l'état pour aucun bénéfice),
  fidèle au patron `/forge` « liste plein écran ↔ détail plein écran, même URL ».
- Charte : jetons `--cg-*` uniquement ; jamais de scroll-x.
