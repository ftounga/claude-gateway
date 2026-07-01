# Mini-spec — [F-10 / SF-01] Quotas & entitlements — cœur backend

## Identifiant

`F-10 / SF-01`

## Feature parente

`F-10` — Quotas & entitlements (compteurs de consommation tokens)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-10-01-quotas-backend`

---

## Objectif

> Compter la consommation de tokens par utilisateur et par période, vérifier le quota du plan
> **avant** chaque appel au fournisseur, et bloquer proprement quand le quota est atteint, tout en
> exposant la consommation courante via `GET /usage`.

---

## Comportement attendu

### Cas nominal

1. À chaque tour de chat (`POST /chat`), **avant** l'appel au fournisseur IA, le service vérifie que
   l'utilisateur n'a pas déjà atteint le quota de tokens de sa période courante (mois calendaire UTC).
2. Si le quota n'est pas atteint, l'appel au fournisseur est effectué normalement.
3. Après réponse du fournisseur, la consommation (`inputTokens + outputTokens` rapportés par
   `ChatCompletionResult`) est ajoutée au compteur de la période courante de l'utilisateur.
4. `GET /usage` renvoie pour l'utilisateur courant : tokens consommés, quota du plan, tokens
   restants, bornes de la période.

### Résolution de l'entitlement (quota mensuel de tokens)

Le quota est dérivé de l'abonnement (F-09, `SubscriptionService.getOrCreateForUser`) :

| État de l'abonnement | Quota mensuel appliqué |
|----------------------|------------------------|
| `ACTIVE` avec `planCode` | quota configuré du plan (`app.quota.plans.<CODE>`) |
| `PAST_DUE` avec `planCode` | quota configuré du plan (accès en sursis, cf. `SubscriptionStatus`) |
| `TRIALING` et essai non expiré (`trialEndsAt` nul ou futur) | quota d'essai (`app.quota.trial-tokens`) |
| autre (`CANCELED`, `INCOMPLETE`, essai expiré) | `0` → bloqué |

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Quota de la période déjà atteint (usage ≥ quota) | Appel fournisseur non effectué, aucun message persisté, erreur métier `quota_exceeded` | 402 |
| Quota résolu à 0 (essai expiré / abonnement inactif) | Idem (bloqué) | 402 |
| `GET /usage` sans JWT | Refus | 401 |
| `POST /chat` sans JWT | Refus (inchangé) | 401 |

---

## Critères d'acceptation

- [ ] La consommation de tokens (`input + output`) est enregistrée par utilisateur et par mois calendaire (UTC) après chaque appel fournisseur réussi.
- [ ] Avant l'appel fournisseur, si `usage ≥ quota` pour la période courante, l'appel est refusé (402 `quota_exceeded`) et **aucun** message utilisateur n'est persisté.
- [ ] Le quota appliqué est dérivé de l'abonnement selon le tableau d'entitlements ci-dessus.
- [ ] `GET /usage` renvoie `usedTokens`, `quotaTokens`, `remainingTokens`, `periodStart`, `periodEnd` pour l'utilisateur courant uniquement.
- [ ] Isolation `user_id` : un utilisateur ne voit et n'incrémente jamais le compteur d'un autre ; le compteur est filtré sur `user_id` (jamais un paramètre client).
- [ ] Un utilisateur d'essai valide peut chatter (quota d'essai > 0) ; les tests d'intégration chat existants restent verts.
- [ ] Aucune clé ni secret n'apparaît dans les logs ; le provider reste appelé via l'interface `AIProvider`.

---

## Périmètre

### Hors scope (explicite)

- **Overage monétisé** (facturation au token au-delà du quota) — OQ-08 reste ouverte pour la
  variante monétisée. V1 = **blocage à la limite** (option explicitement listée par OQ-08), décision
  réversible et non monétaire. La facturation d'overage relève d'une évolution ultérieure.
- Quota spécifique par jour pour le pass `DAILY` (traité comme une allocation mensuelle en V1 —
  simplification tracée). 
- Notification proactive de quota (mail/push) — hors périmètre (module Notifications, ultérieur).
- Écran/affichage frontend — traité en `SF-10-02` (frontend).

---

## Valeurs initiales

| Champ (`usage_counters`) | Valeur initiale | Règle |
|-------|----------------|-------|
| `id` | UUID | généré applicatif (`@UuidGenerator`) |
| `user_id` | utilisateur du contexte de sécurité | jamais un paramètre client |
| `period_start` | 1er jour du mois courant (UTC) | clé de période, unique avec `user_id` |
| `input_tokens` / `output_tokens` | 0 | incrémentés après chaque appel |
| `created_at` / `updated_at` | auto (base / Hibernate) | — |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Unicité | Normalisation |
|-------|-------------|------------------|---------|---------------|
| `usage_counters.user_id` | Oui | uuid | unique avec `period_start` | — |
| `usage_counters.period_start` | Oui | date (1er du mois UTC) | unique avec `user_id` | tronqué au mois |
| `input_tokens` / `output_tokens` | Oui | entier ≥ 0 (`bigint`) | — | jamais négatif |

Quotas (config `app.quota`, réversibles, overridables par env) — valeurs par défaut V1 :
`trial-tokens=200000`, `SOLO=1000000`, `PRO=5000000`, `DAILY=500000`.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/usage` | Oui | USER |

Contrat `GET /api/usage` (réponse 200) — **figé** (importé par SF-10-02 frontend) :

```json
{
  "usedTokens": 4200,
  "quotaTokens": 200000,
  "remainingTokens": 195800,
  "periodStart": "2026-07-01",
  "periodEnd": "2026-08-01"
}
```

`POST /chat` inchangé côté contrat ; nouveau code d'erreur possible `402 { "error": "quota_exceeded", "message": "..." }`.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `usage_counters` (nouvelle) | INSERT / SELECT / UPDATE | compteur par (`user_id`, `period_start`) |
| `subscriptions` | SELECT | résolution de l'entitlement (F-09) |

### Migration Liquibase

- [x] Oui — `009-usage-counters.xml` (changesets `postgresql` + `h2`, id PK uuid, unique `user_id`+`period_start`)

### Composants Angular

- Aucun (frontend en SF-10-02).

---

## Plan de test

### Tests unitaires

- [ ] `EntitlementService` — quota résolu pour ACTIVE/plan, PAST_DUE/plan, TRIALING valide, essai expiré (0), CANCELED (0).
- [ ] `QuotaService` — `assertWithinQuota` passe sous quota, lève `QuotaExceededException` à/au-delà du quota et si quota=0.
- [ ] `QuotaService` — `recordUsage` crée le compteur puis l'incrémente (upsert sur la même période).
- [ ] `ChatService` — appelle `assertWithinQuota` avant le fournisseur et `recordUsage` après ; quota atteint ⇒ fournisseur jamais appelé, aucun message persisté.

### Tests d'intégration

- [ ] `GET /api/usage` → 200 avec quota d'essai pour un nouvel utilisateur (usage 0).
- [ ] `GET /api/usage` → 401 sans JWT.
- [ ] `POST /api/chat` répété jusqu'au dépassement (quota d'essai abaissé en test) → 402 `quota_exceeded`, compteur non incrémenté au-delà.
- [ ] Isolation : le compteur d'Alice n'est pas affecté par les appels de Bob ; `GET /usage` d'Alice ≠ celui de Bob.

### Isolation utilisateur

- [x] Applicable — tests d'isolation `user_id` sur `GET /usage` et sur l'incrément du compteur.

---

## Dépendances

### Subfeatures bloquantes

- `F-09` (subscriptions / entitlements) — **done** (mergée).
- `F-02` (chat proxy) — **done** (point d'insertion de la vérification/enregistrement).

### Questions ouvertes impactées

- [x] `OQ-08` — Facturation de l'overage : **V1 = blocage à la limite** (non monétisé). La variante
  monétisée reste ouverte. Décision réversible tracée dans `OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Période = mois calendaire UTC** : simple, prévisible, reset mensuel automatique sans dépendre
  du cycle Stripe (réversible ; on pourra aligner sur `current_period_end` plus tard).
- **Pré-check sans réservation** : le coût en tokens d'un appel n'est pas connu à l'avance ; on
  bloque quand le cumul de la période a atteint le quota. Un dernier appel peut dépasser légèrement
  le quota, le suivant est bloqué. Comportement de gateway simple et acceptable.
- **402 Payment Required** choisi pour `quota_exceeded` (sémantique « quota du plan épuisé, upgrade »).
