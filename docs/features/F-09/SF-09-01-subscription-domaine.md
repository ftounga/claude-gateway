# Mini-spec — F-09 / SF-09-01 — Domaine abonnement, catalogue de plans & trial

## Identifiant

`F-09 / SF-09-01`

## Feature parente

`F-09` — Abonnements & billing Stripe

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-09-01-subscription-domaine`

---

## Objectif

> Poser le modèle de données d'abonnement (table `subscriptions`, isolée `user_id`), exposer le
> catalogue de plans (Hosted/BYOK × Solo/Pro/Daily) et provisionner un essai gratuit de 14 jours,
> via `GET /billing/plans` et `GET /billing/subscription` — **sans** encore intégrer Stripe (SF-09-02).

---

## Comportement attendu

### Cas nominal

1. `GET /billing/plans` (authentifié) → renvoie le **catalogue statique** des plans proposés
   (code, libellé, mode fournisseur `HOSTED`/`BYOK`, période `MONTHLY`/`DAILY`). Le prix affiché et le
   `priceId` Stripe restent **hors réponse** en SF-09-01 (l'affichage prix/checkout arrive en SF-09-02).
2. `GET /billing/subscription` (authentifié) → renvoie l'abonnement de l'utilisateur courant.
   Si l'utilisateur n'en a **pas encore**, un abonnement d'essai est **provisionné à la volée** :
   `status=TRIALING`, `planCode=null`, `trialEndsAt = now + 14 jours`. Idempotent (un seul par user).
3. La résolution de l'utilisateur passe **exclusivement** par `CurrentUser.requireId()` (JWT) ; toute
   lecture/écriture filtre sur `user_id`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Requête non authentifiée (JWT absent/invalide) | Rejet, aucune donnée | 401 |
| Aucun abonnement existant | Provisionnement trial idempotent, renvoi 200 | 200 |
| Concurrence : 2 requêtes créent le trial en parallèle | Contrainte `unique(user_id)` → 1 seule ligne, l'autre relit | 200 |

---

## Critères d'acceptation

- [ ] `GET /billing/plans` renvoie 200 avec la liste des plans du catalogue (≥ les 3 tiers Solo/Pro/Daily).
- [ ] `GET /billing/subscription` sans abonnement existant crée un trial `TRIALING`, `trialEndsAt = now+14j`, et le renvoie.
- [ ] Un second `GET /billing/subscription` renvoie **le même** abonnement (idempotence, pas de doublon).
- [ ] Les deux endpoints renvoient 401 sans JWT valide.
- [ ] Isolation : l'abonnement renvoyé appartient toujours au `user_id` du JWT ; un user ne voit jamais celui d'un autre.
- [ ] La table `subscriptions` porte une contrainte d'unicité sur `user_id` et un index sur `user_id`.
- [ ] Aucune donnée sensible (clé, secret Stripe) dans le code, la config commitée ou les logs.

---

## Périmètre

### Hors scope (explicite)

- Intégration Stripe : création de session Checkout, webhook, `stripe_customer_id`/`subscription_id` **peuplés** → **SF-09-02**.
- Enforcement des quotas/entitlements (compteurs de tokens, gate avant appel) → **F-10**.
- Écran de facturation Angular → **SF-09-03**.
- Upgrade/downgrade/annulation → gérés via Stripe en SF-09-02.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| status | `TRIALING` | à la création du trial |
| plan_code | `null` | aucun plan payant tant que le checkout n'a pas abouti |
| trial_ends_at | `now + 14j` | durée d'essai fixe (config `app.billing.trial-days`, défaut 14) |
| stripe_customer_id | `null` | peuplé en SF-09-02 |
| stripe_subscription_id | `null` | peuplé en SF-09-02 |
| current_period_end | `null` | peuplé en SF-09-02 |

Comportements à la création :
- `user_id` = utilisateur du contexte de sécurité (jamais un paramètre client).
- `created_at`/`updated_at` gérés par Hibernate (`@CreationTimestamp`/`@UpdateTimestamp`).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| user_id | Oui | — | UUID | **Oui** (un abonnement par user) | — |
| status | Oui | 16 | `TRIALING,ACTIVE,PAST_DUE,CANCELED,INCOMPLETE` | Non | — |
| plan_code | Non | 32 | code du catalogue (enum `PlanCode`) | Non | — |

---

## Technique

### Endpoint(s)

| Méthode | URL (publique = `/api` + ci-dessous) | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/billing/plans` | Oui | USER |
| GET | `/billing/subscription` | Oui | USER |

### Contrat API (figé — importé par SF-09-03)

**`GET /api/billing/plans`** → `200`
```json
{ "plans": [
  { "code": "SOLO",  "label": "Solo",  "providerMode": "HOSTED", "period": "MONTHLY" },
  { "code": "PRO",   "label": "Pro",   "providerMode": "HOSTED", "period": "MONTHLY" },
  { "code": "DAILY", "label": "Pass journée", "providerMode": "HOSTED", "period": "DAILY" }
] }
```

**`GET /api/billing/subscription`** → `200`
```json
{
  "status": "TRIALING",
  "planCode": null,
  "trialEndsAt": "2026-07-15T10:00:00Z",
  "currentPeriodEnd": null
}
```
`401` si non authentifié. `stripeCustomerId`/`stripeSubscriptionId` **ne sont jamais** exposés.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `subscriptions` | CREATE (migration 008), INSERT/SELECT | 1 ligne par user, isolation `user_id` |

### Migration Liquibase

- [x] Oui — `008-subscriptions.xml` (changesets `postgresql` + `h2`). Inclut d'emblée les colonnes Stripe **nullable** (peuplées en SF-09-02) pour éviter une seconde migration.

### Composants Angular

- Aucun (backend uniquement).

---

## Plan de test

### Tests unitaires (`SubscriptionServiceTest`)

- [ ] `getOrCreate` sans abonnement → crée un `TRIALING`, `trialEndsAt ≈ now+14j`, `planCode=null`.
- [ ] `getOrCreate` avec abonnement existant → renvoie l'existant, aucune création.
- [ ] `PlanCatalog` expose les plans attendus (Solo/Pro/Daily) avec mode/période corrects.

### Tests d'intégration (`BillingApiIntegrationTest`)

- [ ] `GET /billing/plans` → 200, liste non vide.
- [ ] `GET /billing/subscription` (1re fois) → 200, `status=TRIALING`, `trialEndsAt` non nul.
- [ ] `GET /billing/subscription` (2e fois) → 200, même identité, 1 seule ligne en base.
- [ ] `GET /billing/subscription` sans JWT → 401.
- [ ] La réponse JSON ne contient jamais `stripeCustomerId`/`stripeSubscriptionId`.

### Isolation utilisateur

- [x] Applicable — test : Alice et Bob provisionnent chacun leur trial ; Alice ne voit que le sien (2 lignes distinctes, `user_id` respectés).

---

## Dépendances

### Subfeatures bloquantes

- `F-01` (auth/JWT/`CurrentUser`) — **done**.

### Questions ouvertes impactées

- [ ] `OQ-07` (réglages Stripe, price IDs, TVA) — **contournée** : SF-09-01 n'expose ni prix ni price ID ; le catalogue est un enum statique. Le mapping price ID (config env) est traité en SF-09-02. Trace d'arbitrage dans la PR.

---

## Notes et décisions

- **Arbitrage — provisionnement trial à la volée (lazy)** plutôt qu'à l'inscription : évite de modifier le flux
  d'auth F-01 (préoccupation transversale « Auth/Principal ») et donc tout risque de régression sur `register`/OAuth.
  Réversible (on peut basculer vers un provisioning eager plus tard). Idempotence garantie par `unique(user_id)`.
- **Arbitrage — colonnes Stripe nullable dès la migration 008** : le modèle d'abonnement inclut naturellement les
  identifiants Stripe ; les inclure nullable dès 008 évite une migration supplémentaire en SF-09-02. Réversible.
- **Préoccupation transversale « Plans / limites »** : nouveau domaine d'abonnement. Composants impactés listés :
  aucun service de quota n'existe encore (F-10 non démarrée) → aucun gate à réviser. `subscriptions` est la première
  brique ; F-10 la consommera. Aucune route existante modifiée.
