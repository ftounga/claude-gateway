# SF-224-03 — Les appels annexes comptés, « 7 derniers jours » en dollars (backend)

**Feature :** F-224 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-224-03-annexes-et-7-jours`

## Objectif
Le coût calculé compte aussi les appels annexes, et l'écran peut montrer les 7 derniers jours en dollars (la devise de la facture), l'euro et son taux en second.

## Comportement
1. **Appels annexes** (cadrage, périmètre 2) : l'aide (`HelpChatService`), la lecture raisonnée du diagnostic (`ProductDiagnosticService.explain`) et l'extraction de l'index de la carte (`HostMapSectionExtractor`, clé plateforme seulement — pas en BYOK) inscrivent leur consommation dans `usage_turns` avec `kind = 'ANNEXE'` (colonne posée par la migration 181), au tarif de leur modèle (`UsageLedgerService.recordAnnex`). **Hors quota** : ce sont des appels de la plateforme, pas des tours demandés ; le compteur de période de l'utilisateur ne bouge pas. Best-effort : jamais au point de casser l'appel. Sans projet ni poste : ils tombent dans « hors client ».
2. **Résumé du coût** (`GET /api/admin/cost/summary`) : nouvelle période `7d` (de J-6 00:00 UTC à demain 00:00 UTC), à côté de `week` (semaine ISO, repart le lundi) et `month` ; le résumé porte `spentUsd` (total et par client) et `usdToEur` (le taux appliqué). Les montants en euros et les budgets (en euros) sont inchangés.

## Erreurs
| Situation | Comportement | HTTP |
|---|---|---|
| Période inconnue | « attendu week, 7d ou month » | 400 |
| Inscription annexe en échec | journalisée (type seulement), l'appel rend sa réponse | — |
| Non administrateur | refus | 403 |

## Critères d'acceptation
- [x] Un appel annexe est inscrit `ANNEXE`, au tarif de son modèle (Haiku 4.5 : 0,00205 $ pour 1 000/200/500).
- [x] Rien d'inscrit sans consommation ou sans utilisateur.
- [x] `period=7d` : bornes J-6 → aujourd'hui, `spentUsd`, `usdToEur` = 0,92.

## Plan de test
`UsageLedgerServiceTest.anAnnexCallIsLabelledAndPricedAtItsModel`, `CostBudgetApiIntegrationTest.theSummaryRouteIsAdminOnlyAndRefusesAnUnknownPeriod` (7d) ; suites aide, carte, diagnostic, résumé du coût. Isolation : inscription sous le `userId` de l'appel ; le résumé reste administrateur.

## Impact
`UsageTurn` (+`kind`), `UsageTurnWriter`, `UsageLedgerService`, `CostWindow`, `CostSummary(Service)`, `TurnCostView` ; `HelpChatService`, `HostMapSectionExtractor`, `ProductDiagnosticService` (mutateurs `setLedger`, aucun constructeur changé). Préoccupations transversales : plans/limites — **aucun** appel aux limites ajouté (annexes hors quota, décision tracée ci-dessous).

## Arbitrage (gate 🟠 réversible)
Annexes comptées dans le **coût** mais pas dans le **quota** de l'utilisateur : le cadrage demande « comptés, étiquetés annexe » pour que l'écran corresponde à la facture ; les imputer au quota changerait ce que chaque client peut consommer, sans demande. Retour arrière : supprimer les appels `recordAnnex`.

## Hors périmètre
L'écran (SF-224-04).
