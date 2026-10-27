# Mini-spec — [F-218 / SF-218-03] L'écran sans confirmation d'étape externe

## Identifiant
SF-218-03

## Feature parente
F-218 — Le mode offensif

## Statut
Done (PR #1308)

## Date de création
2026-10-27

## Branche Git
`feat/SF-218-03-ecran-sans-confirmation`

---

## Objectif
Que [Faire cette étape] (tableau des chantiers, F-208) et [Lancer] (suites, F-209) envoient la demande au premier clic, même quand une étape EXTERNE est en jeu, sans dialogue « Confirmer ? ».

## Comportement attendu

### Cas nominal
- Tableau des chantiers : un clic sur [Faire cette étape] d'une étape EXTERNE émet aussitôt « Chantier N — fais l'étape K : … », comme pour toute étape.
- Suites : une étape EXTERNE cochée dans les prérequis part au premier [Lancer].
- Le badge « externe » reste affiché (information, pas une porte).

### Cas d'erreur
- Étape non débloquée / plan à valider : le bouton reste désactivé avec sa raison (inchangé, `doBlockedReason`).
- Prérequis EXTERNE non coché : il n'est pas inclus dans la demande (inchangé).

## Critères d'acceptation
1. Aucun `role="alertdialog"` de confirmation d'étape externe dans le tableau ni dans les suites.
2. [Faire cette étape] sur EXTERNE émet la demande au premier clic.
3. [Lancer] avec EXTERNE coché émet la demande au premier clic.
4. `npm run build` et `ng test` verts.

## Périmètre
### Hors scope (explicite)
- Le pré-cochage : une étape EXTERNE reste **non pré-cochée** dans les prérequis d'une suite (c'est le périmètre de la demande, pas une confirmation) — arbitrage tracé.
- La validation du plan d'un clic (F-176) : gardée.
- L'invite d'autorisation du terminal (SF-218-02 côté serveur).

## Valeurs initiales
Sans objet.

## Contraintes de validation
Sans objet.

## Technique
### Endpoint(s)
Aucun.
### Tables impactées
Aucune.
### Migration Liquibase
Aucune.
### Composants Angular (si applicable)
- `TerminalChantiersBoardComponent` : retrait de `confirming`, `externalWarning`, `confirmDo`, du bloc `.board__confirm` et de son style.
- `TerminalNextStepsComponent` : retrait de `confirmingLaunch`, `externalWarning`, `confirmLaunch`, du bloc `.pre__confirm` et de son style.
Aucune couleur ni police ajoutée (design system inchangé).

## Plan de test
### Tests unitaires
- `terminal-chantiers-board.component.spec.ts` : l'étape EXTERNE part au premier clic, aucun dialogue.
- `terminal-suites-colonnes.spec.ts` : EXTERNE cochée part au premier [Lancer], aucun dialogue.
### Tests d'intégration
- Suite frontend complète.
### Isolation workspace
Sans objet (aucun appel nouveau ; la demande passe par le fil existant).

## Dépendances
### Subfeatures bloquantes
SF-218-01 (doctrine : le plan validé vaut feu vert).
### Questions ouvertes impactées
Aucune.

## Notes et décisions
- Décision PO 2026-10-27 : l'irréversible n'est jamais confirmé. Cliquer sur l'étape EST la décision.
