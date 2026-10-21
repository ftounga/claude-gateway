# Mini-spec — [F-207 / SF-207-06] Le terminal ne tremble plus

## Identifiant
`F-207 / SF-207-06` — Feature parente `F-207` (Regagner l'espace de travail) — Statut `done` — Créée 2026-10-21
Branche : `feat/SF-207-06-terminal-qui-tremble` — correctif de SF-207-03 (mergée).

## Objectif
Le terminal ne tremble plus pendant le travail : plus rien ne déborde en largeur, donc le conteneur principal ne gagne plus de barres de défilement que le recalage en bas ferait sauter.

## Cause (mesurée)
- Le bouton de réglage ⚙ de la barre d'état (SF-207-03) mesure 26 px, mais sa **zone tactile** Material reste de 48 px, centrée. Avec 8 px de marge à droite, elle dépasse de 3 px le bord de l'écran.
- Ce dépassement rend `main.app-content` (`overflow: auto`) défilable. Avec des barres de défilement classiques (Windows, ~15 px), il gagne une barre horizontale **et** une barre verticale (mesure à 1366 × 768 : 1366 × 768 → 1351 × 753).
- À chaque nouvelle ligne du tour, `scrollToBottom()` (`scrollIntoView`) fait défiler **tous** les ancêtres défilables, dont ce conteneur. La vue entière saute, et les lignes se recalent. Au repos, il y a moins de mises à jour, donc moins de tremblement.
- Avant F-207 (commit `23054685^`), aucun élément ne dépassait, sur le même banc de mesure.
- Un navigateur sans tête cache ses barres de défilement. Il faut le lancer sans `--hide-scrollbars` pour voir le défaut.

## Comportement nominal
- La barre d'état garde 16 px de marge à droite. La zone tactile du bouton ⚙ tient dans l'écran, et le bouton reste à 26 px.
- Garde-fou : la racine du terminal (`.terminal-view`) coupe tout débordement **horizontal** (`overflow-x: clip`). `clip` ne crée pas de conteneur défilable, donc `scrollIntoView` ne peut pas le faire défiler. La hauteur n'est pas touchée : le défilement vertical sur téléphone (la fenêtre) reste inchangé.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Un futur élément du terminal dépasse en largeur | coupé par la racine du terminal ; `main` ne devient pas défilable |
| Tiroirs, rail des questions, menus | inchangés : tiroirs internes au terminal, menus dans l'overlay CDK |

## Critères d'acceptation
- [ ] CA1 À 1366 × 768, barres de défilement visibles, tour en cours : `main.app-content` garde `scrollWidth ≤ clientWidth` et ne change pas de taille.
- [ ] CA2 La zone tactile du bouton ⚙ ne dépasse pas le bord droit du terminal.
- [ ] CA3 `.terminal-view` porte `overflow-x: clip` (calculé).
- [ ] CA4 Non-régression : suite frontend verte, `ng build` vert, rendu visuel inchangé (capture).

## Plan de test
- Spec `terminal-barre-etat.spec.ts` : la zone tactile du menu ne dépasse pas la barre (CA2) ; `.terminal-view` en `overflow-x: clip` (CA3).
- Banc headless avec barres de défilement réelles, avant/après correctif (CA1).
- Isolation utilisateur : sans objet.

## Impacts
- Composants : `atelier-terminal-dock.component.scss`, `atelier-terminal.component.scss`, spec.
- Aucun endpoint, aucune donnée, aucune route. Design system : espacement `--cg-space-3` de l'échelle.

## Hors périmètre
Le glissement sous-pixel du recalage en bas, mesuré identique avant F-207 et imperceptible.
