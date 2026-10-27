# Mini-spec — [F-220 / SF-220-04] La pastille « qui débloque » dans le sujet

## Identifiant
SF-220-04

## Feature parente
F-220 — Le tableau de bord du client

## Statut
Done

## Date de création
2026-10-27

## Branche Git
`feat/SF-220-04-pastille-sujet`

---

## Objectif
Montrer la même pastille de couleur « qui débloque » dans la vue d'un sujet — onglets, résumé et liste des étapes du tableau des chantiers (F-208) — et pas seulement sur le tableau du client.

## Comportement attendu

### Cas nominal
- **Contrat importé de SF-220-01** : `GET /api/workspaces/{ws}/journey` porte `chantiers[].unblocker` et `chantiers[].plan.steps[].unblocker` (`{ who, label, detail, step, waitingOn, since }`, `null` pour une étape faite).
- Nouveau composant partagé `app-unblocker-pill` : icône + libellé + couleur (`--cg-success` / `--cg-orange` / `--cg-text-secondary`), phrase complète en infobulle et en `aria-label` ; taille `md` (libellé complet) ou `sm` (libellé court : « l'outil avance », « votre décision », « on attend »).
- Tableau des chantiers d'un sujet (F-208) :
  - **onglet** de chaque chantier : pastille `sm` ;
  - **résumé** du chantier ouvert : pastille `md` + la phrase `detail` ;
  - **étape** non faite : pastille `sm` dans sa ligne de méta ; rien pour une étape faite ou vérifiée.
- Le marqueur « à vous » et l'indicateur « autonome » existants restent (l'indicateur d'activité s'ajoute à la couleur).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Gateway antérieure (pas de champ `unblocker`) | aucune pastille, rien d'autre ne change | 200 |
| `unblocker` `null` (étape faite) | aucune pastille sur l'étape | 200 |

## Critères d'acceptation
1. Un chantier `OUTIL` montre sur son onglet une pastille « l'outil avance » avec l'icône `bolt`.
2. Le résumé du chantier ouvert montre le libellé complet et la phrase `detail`.
3. Une étape `AUTRUI` montre « on attend », sa phrase en infobulle / `aria-label` ; une étape vérifiée n'a pas de pastille.
4. Sans champ `unblocker`, le tableau s'affiche comme avant.
5. Aucune couleur hors charte ; la couleur n'est jamais seule (libellé toujours écrit).

## Périmètre
### Hors scope (explicite)
- La bande du parcours dans l'en-tête du terminal (inchangée) ; le tableau du client (SF-220-03) ; reporter et rappel (SF-220-05).

## Valeurs initiales
Aucune.

## Contraintes de validation
Aucune (affichage).

## Technique
### Endpoint(s) consommés
- `GET /api/workspaces/{ws}/journey` (enrichi par SF-220-01).

### Migration Liquibase
- [x] Non applicable

### Composants Angular
- `shared/unblocker-pill/unblocker-pill.component.ts` (nouveau).
- `atelier/terminal/terminal-chantiers-board.component.ts` (onglets, résumé, étapes).
- `core/models/journey.models.ts` (`unblocker?` sur `OpenChantier` et `JourneyPlanStep`), `client-board.models.ts` (`UNBLOCKER_SHORT`).

## Préoccupations transversales
- Aucune (affichage d'un champ déjà servi ; ni auth, ni tenant, ni limites, ni navigation).

## Plan de test
### Tests unitaires
- `unblocker-pill.component.spec.ts` : libellé court/complet, icône, `aria-label`, rien sans marque.
- `terminal-chantiers-unblocker.spec.ts` : onglet, résumé, étapes ; absence sans champ.
### Tests d'intégration
- Backend couvert par SF-220-01 (`ChantierUnblockerIntegrationTest`).
### Isolation utilisateur
- [x] Non applicable — affichage ; isolation au serveur.

## Dépendances
- SF-220-01 — Done (PR #1315).
