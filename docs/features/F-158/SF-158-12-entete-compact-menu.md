# Mini-spec — F-158 / SF-158-12 En-tête compact + menu ⋯ (terminal mobile)

## Identifiant

`F-158 / SF-158-12`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-12-entete-compact-menu`

---

## Objectif

Sur téléphone (< 820 px), remplacer la barre d'en-tête surchargée du terminal Atelier par un
en-tête compact (fil d'Ariane tronqué + pastille moteur + Interrompre pendant un run) et regrouper
toutes les actions secondaires dans un `mat-menu` déclenché par un bouton ⋯ — sans rien retirer et
sans toucher le rendu desktop (≥ 820 px).

---

## Comportement attendu

### Cas nominal

- **≥ 820 px (desktop)** : la barre est **strictement inchangée** — bandeau d'actions complet en
  clair (Instructions, À faire, Valider les commandes, Fichiers, Publier, Réinitialiser, Nouveau
  départ, Quitter), aucun bouton ⋯.
- **< 820 px (mobile)** : la barre ne montre en clair que le **fil d'Ariane (tronqué)**, la
  **pastille moteur**, et — pendant un run — le spinner, le chrono et **Interrompre**. Les chips
  purement informatives (dossier, budget hebdo, coût projet, signe de vie, lien Teams) sont masquées
  (elles restent visibles en desktop). Toutes les **actions** secondaires sont déplacées dans un
  `mat-menu` ouvert par un bouton ⋯ (`more_vert`). Chaque entrée du menu émet exactement le même
  `@Output` que le bouton correspondant du bandeau desktop. Aucune action ne disparaît.

Le basculement mobile/desktop est piloté par un signal `isNarrow` alimenté par
`matchMedia('(max-width: 819px)')` (mis à jour sur changement), avec repli `false` sans `window`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `window.matchMedia` indisponible (SSR / test dégradé) | `isNarrow` vaut `false` → rendu desktop, aucune erreur |
| Aucun tour en cours | Ni spinner ni Interrompre en clair (desktop comme mobile), inchangé |
| Le viewport passe de desktop à mobile (rotation / redimensionnement) | Le listener `matchMedia` met `isNarrow` à jour, l'en-tête bascule |

---

## Critères d'acceptation

- [ ] À ≥ 820 px, le bandeau d'actions complet est présent (bouton « Fichiers », « Nouveau départ »…)
      et **aucun** bouton ⋯ (`.terminal-overflow`) n'est rendu.
- [ ] À < 820 px, un bouton ⋯ est rendu et ouvre un `mat-menu` contenant toutes les actions
      secondaires ; le fil d'Ariane et la pastille moteur restent en clair.
- [ ] Pendant un run, « Interrompre » reste en clair sur mobile (hors du menu).
- [ ] Aucune action présente sur desktop n'est absente sur mobile (déplacée, jamais supprimée).
- [ ] Le rendu desktop (≥ 820 px) est identique à l'existant (aucune règle nouvelle hors
      `@media (max-width: 819px)` / hors signal `isNarrow`).
- [ ] `npm run build` vert ; feuille principale du terminal non modifiée (reste à 11.97 kB) ; styles
      nouveaux dans la feuille mobile dédiée (< 12 kB).
- [ ] Jetons `--cg-*` uniquement, aucune couleur nouvelle, cibles ⋯ ≥ 44 px, gouttières ≥ 16 px.

---

## Périmètre

### Hors scope (explicite)

- Le composeur ancré en bas et les décisions empilées (SF-158-13).
- La peau du fil / des messages et le moment Teams en colonne (SF-158-14).
- Toute modification desktop.
- Tout backend, endpoint, migration, composant cluster.

---

## Technique

### Composants Angular

- `AtelierTerminalComponent` (`atelier-terminal.component.ts/.html`) — ajout de
  `MatMenuModule`, d'un signal `isNarrow` (matchMedia) et restructuration mobile de
  `.terminal-bar-actions` (bandeau desktop conditionné `@if (!isNarrow())`, menu ⋯ `@if (isNarrow())`).
- `atelier-terminal-mobile.component.scss` — masquage des chips informatives et cadrage du fil
  tronqué sous 819 px ; bouton ⋯ ≥ 44 px.

### Tables impactées / Migration

Aucune. Pur frontend display-only.

---

## Préoccupations transversales

- **Navigation / routing** : aucune route touchée ; le fil d'Ariane conserve ses liens. Composants
  impactés : `AtelierTerminalComponent` uniquement (barre). Aucun guard modifié.
- **Auth / tenant / plans** : non touchés.

---

## Plan de test

### Tests unitaires / composant (Karma ChromeHeadless)

- [ ] Non-régression desktop (`terminal-largeur-reelle.spec.ts`) : à largeur desktop (isNarrow=false),
      le bandeau d'actions complet est présent et le bouton ⋯ absent.
- [ ] Compact mobile (`terminal-largeur-reelle.spec.ts`) : à 360/400 px avec `isNarrow=true` +
      patron mobile appliqué, l'en-tête ne fait pas déborder la page (`scrollWidth <= clientWidth`)
      et le bouton ⋯ est présent.

### Isolation workspace

- [ ] Non applicable — composant de présentation, aucun accès données.

---

## Notes et décisions

- **Mécanisme de garantie desktop** : tout le mobile est soit sous `@media (max-width: 819px)`
  (feuille mobile dédiée), soit conditionné au signal `isNarrow()` (faux en desktop / Karma 1440 px).
- **Fidélité maquette** : la maquette (`.bar`) ne montre que fil + moteur + ⋯ ; les chips budget /
  coût / dossier / live / Teams sont donc masquées sur mobile (présentes en desktop). Un éventuel
  « retour » de la maquette est déjà porté par le fil d'Ariane cliquable (pas de bouton dédié).
