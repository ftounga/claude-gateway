# SF-223-02 — Les contrastes du terminal sombre

**Feature :** F-223 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-223-02-contrastes-terminal`

## Objectif
Tout texte et tout bouton du terminal sombre atteint l'AA (4,5:1), mesuré sur les couleurs rendues.

## Comportement (cadrage F-223, bug 2)
| Endroit | Avant | Correctif |
|---|---|---|
| Titres d'étapes, « Plan v2 · 4 étapes » (couleur héritée) | 1,24 | `color: --cg-divider` sur `.journey-strip`, `.journey-card`, `.journey-closed` (9,12) |
| « Et maintenant ? » (`h3` global) | 1,16 | `.cap__title { color: inherit }` |
| Bouton grisé (encre Material 38 %) | 1,19 | jetons `--mdc-*-disabled-*` redéfinis dans `.terminal-view` |
| Texte secondaire de la bande | 2,66 | nouveau jeton `--cg-terminal-ink-2` `#B8C2CF` (6,46) |
| Risque externe, échec d'étape | 2,34 | `--cg-orange-2` (5,05) |
| « fait », phases passées, « validé » | 4,05 | nouveau jeton `--cg-terminal-success` `#4CC38A` (5,26) |
| Tableau des chantiers, attentes, bilan : boutons blanc sur blanc | ≈ 1,0 | classe globale `.cg-light-island` qui remet les encres Material des écrans clairs |

## Erreurs
Sans objet (styles). Risque : un panneau clair oublié hériterait encore des encres claires — les trois panneaux connus (`terminal-chantiers-board`, `terminal-actions-panel`, `session-bilan-panel`) portent l'îlot.

## Critères d'acceptation
- [x] Bande du parcours : titres, secondaire, « fait », externe, indice ≥ 4,5:1 sur `--cg-primary` (mesuré par spec, couleurs calculées).
- [x] Tableau des chantiers posé dans un ancêtre qui impose l'encre claire : boutons cerclés ≥ 4,5:1 sur blanc.
- [x] Nouveaux jetons documentés dans `DESIGN_SYSTEM.md` §21.

## Plan de test
- `terminal-contrastes-f223.spec.ts` (Karma, Chrome réel, styles globaux chargés) : contraste calculé sur `getComputedStyle`.
- Suite front complète (non-régression des specs de la bande, du tableau, des suites).
- Isolation : non applicable (aucun accès données).

## Impact
`styles.scss`, `atelier-terminal.component.scss`, `terminal-journey-strip`, `terminal-next-steps`, `terminal-chantiers-board`, `terminal-actions-panel`, `session-bilan-panel`, `DESIGN_SYSTEM.md`. Préoccupations transversales : aucune.

## Arbitrage (gate 🟠 réversible)
Deux couleurs ajoutées à la charte (`#B8C2CF`, `#4CC38A`), valeurs proposées par le cadrage, limitées au fond sombre du terminal. Alternative écartée : redéfinir `--cg-text-secondary` sur tout `.terminal-view` (aurait fui dans les îlots clairs : 1,8:1 sur blanc).

## Hors périmètre
Le blanc sur or des boutons pleins (2,63:1), point de charte signalé. Captures 1440×900, 1366×700, 390×844 : non produites par l'agent (pas de navigateur sur données réelles) ; la preuve est la mesure calculée en Chrome.
