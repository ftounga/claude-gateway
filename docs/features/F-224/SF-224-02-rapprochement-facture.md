# SF-224-02 — Le rapprochement avec la facture Anthropic (backend)

**Feature :** F-224 · **Statut :** done (2026-10-31) · **Estimation :** 1 j · **Branche :** `feat/SF-224-02-rapprochement-facture` · **Migration :** 181 (pré-assignée)

## Objectif
Montrer à l'administrateur, jour par jour, « facturé par Anthropic » à côté de « calculé par la gateway », avec l'écart et la consommation par clé API.

## Comportement
1. **Lecture de la facture** derrière l'interface neutre `ProviderBillingReport` (Provider Independence) ; implémentation `AnthropicAdminBillingReport` en HTTP direct (rapports absents des SDK) : `cost_report` (montant par jour, cents → dollars), `usage_report/messages` groupé par `api_key_id` et `model` (chiffré à la grille), `api_keys` (noms). En-têtes `x-api-key` (clé Admin) et `anthropic-version`. Pagination suivie (20 pages au plus).
2. **Clé** `APP_ANTHROPIC_ADMIN_KEY` (`app.cost.anthropic-admin.key`) : absente → `configured=false`, aucun appel, et l'écran reçoit `setupHint` (comment la créer). Jamais journalisée ni renvoyée ; une panne ne remonte que son type.
3. **Tâche quotidienne** (06:20 UTC, réglable) : relit les 31 derniers jours et range dans `provider_cost_reports` (migration 181) — une ligne par (jour, `COST`) et par (jour, `KEY`, clé) ; relire remplace (idempotent, plusieurs pods sans doublon).
4. **Routes administrateur** : `GET /api/admin/cost/reconciliation?days=30` (1 à 31) → `{configured, setupHint, lastFetchedAt, billedTotalUsd, calculatedTotalUsd, days:[{day, billedUsd, calculatedUsd, gapUsd, keys:[{apiKeyId, label, amountUsd, inputTokens, outputTokens}]}]}`, du plus récent au plus ancien ; `POST …/refresh` relit puis rend la même vue.
5. **Calculé** = somme des `usage_turns.provider_cost_usd` du jour UTC — agrégat de **plateforme**, lu par l'administrateur seul ; aucune donnée d'un compte n'en sort.
6. Migration 181 ajoute aussi `usage_turns.kind` (`TOUR` par défaut, `ANNEXE`), utilisé par SF-224-03.
7. `docs/ops/cout-facture-anthropic.md` : créer la clé Admin, lire l'écart, séparer une clé par client (décision PO, pas de code).

## Erreurs
| Situation | Comportement | HTTP |
|---|---|---|
| Non administrateur | refus | 403 |
| Clé absente | `configured=false`, `setupHint`, jours sans « facturé » | 200 |
| Fournisseur injoignable (tâche) | journalisé par son type ; les lignes précédentes restent | — |
| Fournisseur injoignable (bouton) | erreur 5xx générique, sans la clé | 500 |

## Critères d'acceptation
- [x] Sans clé : inactif, aucun appel, indication de création.
- [x] Cents convertis en dollars, pages suivies, clé dans l'en-tête, jamais dans un message d'erreur.
- [x] Facturé / calculé / écart du jour ; clés triées par montant, nommées.
- [x] Relire deux fois ne double pas les lignes.
- [x] 403 pour un non-administrateur sur les deux routes.

## Plan de test
`AnthropicAdminBillingReportTest` (serveur simulé, 4 tests), `ProviderCostReconciliationApiIntegrationTest` (2 tests, `ProviderBillingReport` simulé). Migration jouée sur H2 et PostgreSQL (Testcontainers). Isolation : donnée de plateforme, garde administrateur testée.

## Impact
Nouveau paquet `quota/billing` ; `UsageTurnRepository.costsBetween` ; `application.yml` (`app.cost.anthropic-admin`) ; migration 181 ; `ARCHITECTURE_CANONIQUE.md`. Secret : `APP_ANTHROPIC_ADMIN_KEY` dans `backend-secrets` (injecté par `envFrom`, aucun manifeste à changer). Préoccupations transversales : aucune (route admin, pas de tenant).

## Arbitrage (gate 🟠 réversible)
Le détail par clé est une **estimation** (tokens de l'`usage_report` × grille) car le `cost_report` ne se groupe pas par clé ; le total facturé, lui, est exact. Retour arrière : retirer la clé (rapprochement inactif) ou la migration 181.

## Hors périmètre
L'écran (SF-224-03 et SF-224-04) ; une clé par client dans la gateway (décision PO).
