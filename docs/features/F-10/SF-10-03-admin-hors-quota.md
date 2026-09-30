# Mini-spec — [F-10 / SF-10-03] L'administrateur n'est jamais bridé par le quota de tokens

## Identifiant

`F-10 / SF-10-03`

## Feature parente

`F-10` — Quotas & entitlements

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-10-03-admin-hors-quota`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Un utilisateur de rôle **ADMIN** n'est **jamais** bloqué ni raboté par le quota mensuel de tokens (pré-vol, borne du tour hébergé, alerte, jauge), **tout en restant mesuré** (`usage_counters` et `usage_turns` continuent d'être alimentés).

---

## Contexte / décision

Décision PO du **2026-09-30** : exempter le rôle ADMIN du bridage de quota de tokens, en continuant à mesurer sa consommation (analyses de coût). Cas mesuré en prod : le PO (rôle ADMIN, plan GOLD = 12 M tokens/mois) a épuisé son quota ; en hébergé la borne d'un message = `min(app.atelier.max-turn-tokens, quota restant)`, donc à quota ≈ 0 chaque message ne fait plus qu'une itération puis coupe. Cohérent avec la règle « l'admin a tout » portée par `AdministratorEntitlement` (F-107).

---

## Comportement attendu

### Cas nominal

- **ADMIN, mode hébergé** : `QuotaService.assertWithinQuota(userId)` ne lève jamais `QuotaExceededException` quel que soit le cumul consommé ; la borne du tour hébergé vaut le plafond configuré `app.atelier.max-turn-tokens` (le quota restant ne la rabote plus), exactement comme en BYOK.
- **Mesure conservée** : `QuotaService.recordUsage(...)` (toutes surcharges) continue d'incrémenter `usage_counters` et d'écrire la ligne `usage_turns` pour l'ADMIN — aucun chemin de comptage n'est court-circuité.
- Point unique : `EntitlementService.resolveEffectiveMonthlyTokenQuota(subscription)` renvoie une allocation **effectivement illimitée** (`UNLIMITED_TOKEN_QUOTA`) quand le titulaire de l'abonnement est ADMIN. Ce point alimente à lui seul le pré-vol, la jauge (`currentUsage`), la borne hébergée (via `currentUsage().remainingTokens()`) et l'alerte (`QuotaAlertService`).
- Le rôle est lu via `AdministratorEntitlement.isAdministrator(userId)` (lecture en base `users.role`, source unique « l'admin a tout »), à partir du `subscription.getUserId()` — jamais un paramètre client.

### Cas d'erreur / non-régression

| Situation | Comportement attendu |
|-----------|---------------------|
| Utilisateur **non-ADMIN** | Comportement inchangé : quota du plan (+ postes F-65 + bonus F-21), blocage à la limite (402), alerte au seuil. |
| **BYOK** (ADMIN ou non) | Inchangé : `assertWithinQuota` sort tôt sur `isCustomerKeyBilled` et exige toujours une clé active (`requireActiveApiKey`). La plateforme n'alloue aucun jeton, la limite reste chez le fournisseur. |
| Abonnement **expiré/résilié** d'un non-ADMIN | Inchangé : fail-closed, quota 0 → blocage. |
| Catalogue `GET /billing/plans` | Inchangé : `resolveMonthlyTokenQuota` (plan seul) n'est **pas** touché — le catalogue annonce le plan indépendamment du lecteur. |
| Évaluation d'alerte de quota pour un ADMIN | Jamais levée (ratio ≈ 0 sur quota illimité) ; l'alerte n'échoue jamais l'appel de toute façon. |

---

## Critères d'acceptation

- [ ] ADMIN → `resolveEffectiveMonthlyTokenQuota` renvoie `UNLIMITED_TOKEN_QUOTA` (valeur sûre, sans débordement quand on y ajoute bonus/report).
- [ ] ADMIN hébergé → `assertWithinQuota` ne lève pas, même quand `used >= quota du plan`.
- [ ] ADMIN hébergé → borne du tour = `min(maxTurnTokens, remaining)` avec `remaining` illimité ⇒ vaut `maxTurnTokens` (non rabotée).
- [ ] ADMIN → `recordUsage` incrémente toujours `usage_counters` et écrit `usage_turns` (mesure conservée).
- [ ] Non-ADMIN → tous les comportements de quota inchangés (pré-vol, jauge, alerte, blocage à la limite).
- [ ] BYOK → inchangé (sortie précoce + exigence de clé).
- [ ] Isolation `user_id` : le rôle est résolu depuis `subscription.getUserId()` (contexte de sécurité), jamais un paramètre entrant.

---

## Plan de test minimal

### Unitaires — `EntitlementService`
- ADMIN (abonnement ACTIVE) → `resolveEffectiveMonthlyTokenQuota` = `UNLIMITED_TOKEN_QUOTA` ; `resolveMonthlyTokenQuota` (plan seul) inchangé.
- ADMIN sans supplément de poste : `SeatQuotaService.grantedTokens` **n'est pas** appelé (court-circuit).
- Non-ADMIN → valeur inchangée (plan + postes) ; garanties existantes (essai, BYOK, résilié) préservées.

### Unitaires — `QuotaService` (entitlement mocké renvoyant `UNLIMITED_TOKEN_QUOTA`)
- `assertWithinQuota` ne lève pas même avec un compteur au-delà du quota du plan.
- `currentUsage().remainingTokens()` reste très grand (borne hébergée = `maxTurnTokens`, non rabotée).
- `recordUsage` sauve toujours le compteur et appelle `usageLedgerService.recordTurn` (mesure conservée).

### Non-régression
- Suite `QuotaServiceTest`, `EntitlementServiceTest`, `EntitlementServiceSeatTest`, `EntitlementServiceYearlyTest`, `QuotaAlertServiceTest` vertes.

### Isolation
- Le rôle est lu via `subscription.getUserId()` ; aucun paramètre client n'entre dans la décision.

---

## Préoccupation transversale — « Plans / limites » (COCHÉE)

Analyse d'impact : liste de **tous** les appels aux services de limites/quota et vérification de chaque gate.

| Gate / appel | Fichier | Effet ADMIN | Vérification |
|--------------|---------|-------------|--------------|
| `assertWithinQuota(userId)` | `QuotaService` (pré-vol hébergé, appelé par `AtelierChatService`, `ChatService`, `AskService`…) | `quota` illimité ⇒ `used >= quota` faux ⇒ ne lève jamais. BYOK inchangé (sortie précoce). | Test unit + inspection appelants |
| Borne du tour hébergé `AtelierTurnBudget.hosted(maxTurnTokens, remaining)` | `AtelierChatService` (~l.1795-1797) | `remaining` illimité ⇒ `min(maxTurnTokens, remaining)` = `maxTurnTokens`. **Aucune** modif de ce fichier (transparent via `currentUsage`). | Inspection + test QuotaService |
| `currentUsage(userId)` | `QuotaService` (jauge `GET /usage` + source de la borne) | `quota`/`remaining` illimités. Mesure `used`/`processed` intacte. | Test unit |
| `resolveEffectiveMonthlyTokenQuota(subscription)` | `EntitlementService` | **Point unique modifié** : ADMIN ⇒ `UNLIMITED_TOKEN_QUOTA`. | Test unit |
| `resolveMonthlyTokenQuota(subscription)` (plan seul, catalogue) | `EntitlementService` | **Non modifié** : catalogue inchangé. | Inspection |
| `QuotaAlertService.evaluateAfterUsage` / `currentAlert` | `QuotaAlertService` | Quota illimité ⇒ ratio ≈ 0 ⇒ jamais d'alerte pour ADMIN ; ne bloque jamais. | Inspection |
| `recordUsage(...)` (toutes surcharges) | `QuotaService` | **Inchangé** : mesure conservée pour ADMIN. | Test unit |
| `GET /usage` (`UsageController`) | `UsageController` → `currentUsage` | ADMIN voit un quota illimité, consommation mesurée affichée. | Inspection |
| `assertWithinSandboxLimit` (temps de bac à sable F-28) | `QuotaService` | **Hors périmètre** : c'est une autre limite (secondes de sandbox), pas le quota de tokens. Non modifié. | Hors scope explicite |

---

## Tables / endpoints / composants impactés

- **Tables** : aucune nouvelle table, aucune migration. Lecture de `users.role` (existant) via `AdministratorEntitlement`.
- **Endpoints** : aucun nouvel endpoint. `GET /usage` et le pré-vol des chemins servis (Atelier/chat/ask) changent de comportement pour l'ADMIN uniquement.
- **Composants backend** : `EntitlementService` (dépendance ajoutée `AdministratorEntitlement`, constante `UNLIMITED_TOKEN_QUOTA`, court-circuit ADMIN) ; javadoc de `AdministratorEntitlement` mise à jour (le quota de tokens est désormais exempté pour l'ADMIN). Aucun changement `AtelierChatService`/`QuotaService`/`QuotaAlertService`.
- **Frontend** : aucun (la jauge dégrade gracieusement avec un grand quota).

---

## Hors périmètre (explicite)

- Ne touche pas les plans grand public ni la tarification ; ne monétise rien (au contraire, augmente la marge en évitant un blocage de l'ADMIN).
- Ne modifie pas la limite de temps de bac à sable (`assertWithinSandboxLimit`, F-28).
- Ne modifie pas le mode BYOK ni le comptage/relevé (F-16/F-61/F-133).
- Aucune UI dédiée (badge « illimité » éventuel = évolution ultérieure).
