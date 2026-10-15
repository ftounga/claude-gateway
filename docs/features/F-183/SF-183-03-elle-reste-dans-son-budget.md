# Mini-spec — F-183 / SF-183-03 Elle reste dans son budget

## Identifiant
`F-183 / SF-183-03` — Feature parente : F-183 « Les routines du sujet » (décision **D3**).
Statut : `ready` — 2026-10-16 — Branche `feat/SF-183-03-elle-reste-dans-son-budget`.

## Objectif
Borner la dépense d'une routine sans humain : un **budget en dollars par exécution** (coupe nette, tour marqué INCOMPLET) et un **budget mensuel par routine**, tous deux visibles dans `/cout` (F-165).

## Comportement attendu

### Cas nominal
1. **Coupe en dollars dans la boucle** : `AtelierChatService.capSpend(userId, workspaceId, capUsd)` arme, pour le tour de ce sujet, un plafond en dollars (`AtelierSpendCap`) ; avant chaque itération, la boucle chiffre la dépense du tour (`QuotaService.costOf`, tarif du modèle servi, cache compris) et **renonce avant l'appel** qui franchirait le plafond, l'itération à venir étant majorée par la plus chère déjà observée (même règle D-L8-2 que le plafond de tokens ; la **première itération est toujours permise**, D-L8-3). Le tour s'arrête net, réponse « INCOMPLET — la routine a atteint son budget de N $ par exécution ; le travail déjà fait est conservé. », `budgetReached = true` ⇒ exécution `INCOMPLETE`. `releaseSpendCap` désarme. **Le modèle, l'effort de raisonnement, les outils et la consigne ne changent pas** : le budget coupe, il ne dégrade jamais (justesse avant coût).
2. **Tours de suite** (précisions pendant la routine) : chaque tour de suite est armé avec le **reste** du budget ; reste ≤ 0 ⇒ pas de tour de suite (précisions rendues « abandonnées »).
3. **Budget mensuel** : colonne `subject_routines.monthly_budget_usd` (migration 156, défaut **100,00 $**, bornes 1 … 2 000 $). Dépense du mois = somme `routine_runs.cost_usd` depuis le 1er du mois **dans le fuseau de la routine**. Au créneau : dépense ≥ budget ⇒ ligne `SKIPPED_BUDGET` (le créneau est consommé, rien ne part) ; sinon plafond de l'exécution = `min(maxRunUsd, budget mensuel − dépense du mois)`, écrit dans `routine_runs.cap_usd`.
4. **Visible** : `RoutineView` gagne `monthlyBudgetUsd`, `monthSpentUsd` ; le panneau `/cout` ajoute une section « Routines de ce sujet » : par routine, budget par exécution, dépensé ce mois / budget mensuel (barre), coût du dernier passage. Le formulaire `/routines` expose le budget mensuel.
5. Le run manuel (SF-02) suit les mêmes règles (409 `routine_budget_exhausted` si le mois est épuisé).

### Contrat API (figé, additif)
```json
// RoutineRequest (+) "monthlyBudgetUsd": 100.00   (facultatif, défaut 100)
// RoutineView (+)   "monthlyBudgetUsd": 100.00, "monthSpentUsd": 3.42
```

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Budget mensuel hors 1 … 2 000 $ ou < budget par exécution | `routine_invalid` | 400 |
| Mois épuisé au créneau | `SKIPPED_BUDGET`, aucun tour | — |
| `run` manuel, mois épuisé | `routine_budget_exhausted` | 409 |
| Plafond atteint en cours de tour | coupe nette avant l'appel, `INCOMPLETE`, travail conservé | — |
| Calcul de coût en échec | le plafond de tokens existant reste en place (aucune coupe en dollars inventée) | — |

## Contraintes de validation
`monthlyBudgetUsd` ∈ [1 ; 2 000], 2 décimales, ≥ `maxRunUsd`. Première itération toujours permise. Le plafond en dollars ne s'applique qu'aux tours armés (les tours au clavier sont inchangés).

## Critères d'acceptation
- [ ] Un tour armé à 0,50 $ dont l'itération la plus chère vaut 0,30 $ s'arrête avant l'appel qui ferait passer la dépense au-delà de 0,50 $ ; réponse INCOMPLET ; `budgetReached = true`.
- [ ] Un tour non armé est strictement inchangé (même nombre d'appels, même réponse).
- [ ] Modèle et effort de raisonnement identiques avec ou sans plafond.
- [ ] Mois épuisé ⇒ `SKIPPED_BUDGET`, aucun tour ; reste 1,20 $ avec `maxRunUsd` 5 $ ⇒ `cap_usd = 1,20`.
- [ ] `/cout` montre budget par exécution, dépensé du mois / budget mensuel et dernier coût de chaque routine du sujet.
- [ ] Isolation : la dépense du mois ne lit que les exécutions de la routine du propriétaire.

## Plan de test
- Unitaires `AtelierSpendCapTest` (seuils, 1re itération, montant nul).
- `AtelierChatServiceSpendCapTest` : boucle à fournisseur bouchonné — coupe armée, non armée inchangée, modèle/effort identiques.
- `RoutinePlannerTest` (+) : mois épuisé ⇒ SKIPPED_BUDGET ; plafond = min(...).
- `RoutineTurnRunnerTest` (+) : armement / désarmement, tours de suite avec le reste.
- `RoutineServiceTest` (+) : validations du budget mensuel ; dépense du mois par fuseau.
- Intégration `RoutineApiIntegrationTest` (+) : `monthSpentUsd` ; `run` mois épuisé ⇒ 409 ; isolation.
- Front : section Routines de `/cout` (rendu, barre bornée à 100 %), champ budget mensuel du formulaire.

## Impacts
- Tables : `subject_routines.monthly_budget_usd` — migration `156-routine-monthly-budget.xml`.
- Endpoints : champs additifs ; nouveau code 409 `routine_budget_exhausted`.
- Composants : `AtelierSpendCap` (nouveau), `AtelierChatService` (vérification avant itération, armement par clé de tour), `RoutineTurnRunner`, `RoutinePlanner`, `RoutineService` ; front : `/cout`, formulaire `/routines`.
- **Préoccupations transversales** : **Plans/limites ☑** — composants : `AtelierTurnBudget` (plafond de tokens, inchangé et toujours appliqué en plus), `QuotaService.assertWithinQuota`/`recordUsage` (inchangés : la routine est décomptée du quota), `QuotaWindowService` (inchangé), `CostBudget`/`CostBudgetService` (budget hebdomadaire du poste F-133, inchangé, toujours affiché par `/budget`), `UsageLedgerService` (inchangé) ; **Tenant ☑** — dépense du mois lue par `routine_id` d'une routine relue sous `user_id` + `workspace_id`.
- Provider Independence : chiffrage par `ProviderCostCalculator` (grille de tarifs configurée), aucun appel fournisseur.

## Hors périmètre
Budget global toutes routines confondues ; plafond en dollars pour les tours au clavier ; changement de modèle ou d'effort pour économiser (interdit par la règle « justesse avant coût »).
