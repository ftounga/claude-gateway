# Mini-spec — F-244 / SF-244-02 — Jarvis ne consomme plus le quota Gateway

## Identifiant
`F-244 / SF-244-02` · Feature parente `F-244` « Le budget propre de Jarvis » · Statut `in-review` · 2026-11-03 ·
Branche `feat/SF-244-02-jarvis-hors-quota-gateway` · Estimation 1 j · Dépend de SF-244-01

## Objectif
> Un tour d'origine Jarvis n'est ni bloqué par le quota Gateway, ni décompté de ce quota ; il reste écrit au journal,
> étiqueté, et les écrans de coût le montrent à part (« dont Jarvis »).

## Comportement attendu

### Cas nominal
- `AtelierChatService.runLoopOnLane` : la `VoiceMark` est lue **avant** le contrôle de quota (D4). Tour Jarvis ⇒ pas
  d'`assertWithinQuota`, pas de lecture du quota restant ; plafond par message = `AtelierTurnBudget.byok(maxTurnTokens)`
  (la borne de configuration seule, comme un tour BYOK). Le contrôle du budget Jarvis arrive en SF-244-03 (ici,
  « souple seulement » = rien ne bloque).
- `QuotaService.recordUsage` étiqueté `JARVIS` : **n'incrémente pas** `usage_counters` (ni alerte de quota F-42), écrit
  toujours la ligne `usage_turns` (origine `JARVIS`). Les tours non étiquetés sont inchangés.
- Cerveau rapide : la garde `assertWithinQuota` est **retirée** (amende SF-239-11, explicitement) ; son seul garde est
  le budget (plafond rapide existant, puis global en SF-244-03).
- Agrégats (D8) :
  - `GET /api/usage/by-client` (consommation par client, lue à côté du quota) : totaux = tours `GATEWAY` seulement ;
    nouvelle ligne `jarvis` { tokens d'entrée, de sortie, total, `costEur` } sur la même fenêtre.
  - `GET /api/admin/cost/summary` (dépense réelle, budgets F-133) : le total **reste vrai** (Jarvis compris) ; nouveau
    `jarvisSpentEur` (total) et `clients[].jarvisSpentEur` (« via Jarvis » par client).
  - Rapprochement facture (`costsBetween`) et coût par projet : inchangés (dépense réelle).
- `GET /api/usage` (compteur du quota) : Jarvis en est sorti par construction.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Quota Gateway épuisé + tour Jarvis | Le tour part, plafond = config | 200 (flux) |
| Quota Gateway épuisé + tour tapé | Refus avant tout appel (inchangé) | 429 / erreur quota |
| Lecture « dont Jarvis » en échec | Ligne vide, l'écran ne casse pas | 200 |
| Utilisateur B | Ne voit aucune dépense Jarvis de A | 200 |

## Critères d'acceptation
- [x] Après un tour Jarvis, `usage_counters` n'a pas bougé.
- [x] Un utilisateur dont le quota Gateway est épuisé peut encore parler à Jarvis (cerveau rapide) et déléguer.
- [x] Un tour tapé dans le même état reste bloqué par le quota (non-régression).
- [x] Le plafond par message d'un tour Jarvis est `maxTurnTokens`.
- [x] `/api/usage/by-client` et `/api/admin/cost/summary` montrent « dont Jarvis » ; Jarvis est exclu du quota.
- [x] Isolation A/B.

## Plan de test
- Unitaires : `AtelierChatServiceBudgetTest` (quota épuisé + Jarvis passe sans `assertWithinQuota` ni lecture du
  restant, étiquette `JARVIS_OPUS` ; quota épuisé + tapé refusé), `JarvisFastBrainServiceTest` (quota épuisé : le
  cerveau rapide répond, aucune interaction avec le quota).
- Intégration : `JarvisOutsideGatewayQuotaIntegrationTest` (compteur intact, by-client « dont Jarvis » en €, dépense
  par client ventilée, isolation A/B).
- Non-régression : suite complète (composants de la liste transversale : `QuotaService`, `AtelierTurnBudget`,
  `UsageTurnRepository`, `CostSummaryService`, `UsageByClientService`, `HostCostService`, `RoutineTurnRunner`).

## Impacts
- Endpoints : `GET /api/usage/by-client` (+`jarvis`), `GET /api/admin/cost/summary` (+`jarvisSpentEur`,
  +`clients[].jarvisSpentEur`). Ajouts de champs uniquement (rétro-compatibles).
- Pas de migration (index posé en SF-244-01).
- Composants : `AtelierChatService`, `QuotaService`, `JarvisFastBrainService`, `UsageTurnRepository`, `OriginSpend`
  (nouveau), `UsageByClient(Service)`, `UsageByClientResponse`, `HostCostService`, `CostSummary(Service)`.

## Préoccupations transversales
- **Plans / limites** — composants impactés et vérifiés :
  - `QuotaService.assertWithinQuota` : plus appelé pour un tour Jarvis ni par le cerveau rapide ; inchangé ailleurs
    (tours tapés, routines, Managed Agents, `/chat`).
  - `QuotaService.recordUsage` : branche `JARVIS` sans compteur ; `GATEWAY` inchangé.
  - `AtelierChatService.runLoopOnLane` (quota + marque), `recordCutTurn` (étiqueté, donc hors compteur aussi).
  - `AtelierTurnBudget` : `byok(maxTurnTokens)` pour Jarvis.
  - `JarvisFastBrainService.budgetState` : inchangé (plafond rapide souple/dur).
  - `JarvisVoiceService` / STT : plafond voix inchangé.
  - `UsageTurnRepository` : agrégat par client filtré `GATEWAY` ; coût par poste inchangé + ventilation Jarvis.
  - `CostBudgetService` (F-133) : lit `HostCostService.costs` ⇒ compte les délégations Jarvis (D8), inchangé.
  - `RoutineTurnRunner` : origine `GATEWAY`, inchangé (aucune `VoiceMark`).
- Auth / tenant / navigation : non touchés.

## Arbitrages
- Pas de contrôle « provisoire » du budget Jarvis dans cette SF : la cible (SF-244-03) arrive juste après ; un garde
  provisoire serait du code jetable. Le cadrage le voulait « souple seulement », c'est-à-dire non bloquant : identique.
- « dont Jarvis » sur `/api/usage/by-client` (consommation par client) et `/api/admin/cost/summary` ; `/api/usage`
  (compteur) n'en a pas besoin : Jarvis n'y entre plus. L'affichage est en SF-244-04.

## Hors périmètre
Le plafond Jarvis réglable (SF-244-03) ; l'affichage écran (SF-244-04).
