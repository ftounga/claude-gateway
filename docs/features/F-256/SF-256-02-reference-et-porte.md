# Mini-spec — F-256 / SF-256-02 Référence et porte de mise en ligne

## Identifiant
`F-256 / SF-256-02`

## Feature parente
`F-256` — Banc à vraie voix de Jarvis (cadrage `CADRAGE-F-256-banc-a-vraie-voix.md`)

## Statut
`in-review` · **Estimation :** 0,5 j · **Branche :** `feat/SF-256-02-reference-et-porte` · **Dépend de :** SF-256-01

## Objectif
Consigner la référence chiffrée de la version actuelle de Jarvis au banc à vraie voix, et faire du banc au vert une
condition de toute mise en ligne de Jarvis.

## Comportement attendu

### Cas nominal
1. Le cadrage §7 porte les chiffres du passage de référence sur `origin/main` (commit, date) : taux entendu, WER,
   médiane/p90 du premier son, replis par type et raisons de fermeture, répétitions, « Voici » sans image, ordres
   d'écran, captures vides, coût en € du passage — et ce qu'ils confirment ou infirment de la régression du 09/11.
2. `project-governance/checklists/release-checklist.md`, section Jarvis : « aucun déploiement de Jarvis sans banc à vraie
   voix au vert » (commande, résumé collé dans la PR, objectifs).
3. `project-governance/playbooks/testing-strategy.md` : règle V9 (quand le jouer, une session à la fois).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Porte non franchie par la version actuelle (référence) | consignée telle quelle ; la règle s'applique aux prochaines mises en ligne de Jarvis (correctifs d'abord) |
| PR qui ne touche pas Jarvis | règle sans objet (« Si la PR touche Jarvis ») |

## Critères d'acceptation
- [ ] Les chiffres de référence et leur passage (commit, date, coût) sont dans le cadrage.
- [ ] La règle est dans `release-checklist.md` (section Jarvis) et `testing-strategy.md` (V9).
- [ ] La conclusion sur la régression de la transcription en direct est écrite, preuve à l'appui (raison de fermeture).

## Plan de test
- Documentation seule : relecture ; suites complètes vertes (aucun code applicatif).
- Isolation utilisateur : sans objet.

## Impact
`docs/features/F-256/CADRAGE-F-256-banc-a-vraie-voix.md`, `project-governance/checklists/release-checklist.md`,
`project-governance/playbooks/testing-strategy.md`, `docs/PRODUCT_SPEC.md`. Préoccupations transversales : aucune.

## Hors périmètre
Corriger les écarts mesurés (enquête et F-257) ; jouer le banc en CI.
