# Mini-spec — F-09 / SF-09-02 — Checkout Stripe & webhook

## Identifiant

`F-09 / SF-09-02`

## Feature parente

`F-09` — Abonnements & billing Stripe

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-09-02-stripe-checkout-webhook`

---

## Objectif

> Permettre à l'utilisateur de souscrire un plan via Stripe Checkout (`POST /billing/checkout`) et
> refléter le cycle de vie du paiement dans `subscriptions` via le webhook signé `POST /webhook/stripe`.

---

## Comportement attendu

### Cas nominal

1. `POST /billing/checkout` `{ "planCode": "PRO" }` (authentifié) → la Gateway crée une **session Stripe
   Checkout** (mode `subscription` pour un plan MENSUEL, `payment` pour un pass DAILY), y attache
   `client_reference_id = userId` et `metadata.userId/planCode`, et renvoie l'URL de redirection.
2. L'utilisateur paie sur Stripe ; Stripe appelle `POST /webhook/stripe` avec un en-tête `Stripe-Signature`.
3. La Gateway **vérifie la signature** (secret de webhook), traduit l'événement et met à jour
   l'abonnement de l'utilisateur : `checkout.session.completed` → `status=ACTIVE`, `planCode`,
   `stripe_customer_id`, `stripe_subscription_id`, `current_period_end`.
4. `customer.subscription.updated` → maj `status`/`current_period_end` ; `customer.subscription.deleted`
   → `status=CANCELED`. Traitement **idempotent** (rejouer un événement ne casse rien).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `planCode` absent / inconnu du catalogue | Rejet, aucune session créée | 400 |
| Stripe non configuré (clé secrète absente) ou price ID manquant | Fournisseur billing dormant | 503 |
| Requête `checkout` non authentifiée | Rejet | 401 |
| Webhook sans signature valide | Rejet, aucune mutation | 400 |
| Webhook d'un type non géré | Ignoré silencieusement | 200 |
| Webhook référant un user/abonnement introuvable | Ignoré (log warn), pas d'erreur | 200 |

---

## Critères d'acceptation

- [ ] `POST /billing/checkout` avec un `planCode` valide renvoie 200 + `checkoutUrl` non vide (via provider stubé en test).
- [ ] `POST /billing/checkout` avec `planCode` inconnu → 400.
- [ ] `POST /billing/checkout` non authentifié → 401.
- [ ] `POST /billing/checkout` quand le fournisseur n'est pas configuré → 503.
- [ ] `POST /webhook/stripe` est **public** (pas de JWT) et vérifie la signature ; signature invalide → 400.
- [ ] Un événement `checkout.session.completed` passe l'abonnement du bon user à `ACTIVE` avec plan + ids Stripe.
- [ ] Un événement `customer.subscription.deleted` passe l'abonnement à `CANCELED`.
- [ ] Le webhook est idempotent (rejouer le même événement ne crée pas de doublon, n'altère pas incorrectement).
- [ ] Isolation : la mutation ne touche que l'abonnement du `userId`/`stripe_*_id` porté par l'événement.
- [ ] Aucune clé secrète Stripe ni signature n'apparaît dans les logs ; le code métier dépend d'une interface `BillingProvider`, jamais de Stripe en direct.

---

## Périmètre

### Hors scope (explicite)

- Enforcement des quotas/tokens (F-10).
- Portail de gestion Stripe (billing portal), factures téléchargeables → ultérieur.
- Écran de facturation Angular → SF-09-03.
- Proraction fine upgrade/downgrade : déléguée à Stripe (on reflète juste le statut reçu).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs |
|-------|-------------|------------------|
| planCode (body checkout) | Oui | ∈ catalogue (`SOLO,PRO,DAILY`) |
| Stripe-Signature (header webhook) | Oui | signature Stripe valide vs `app.billing.stripe.webhook-secret` |

---

## Technique

### Endpoint(s)

| Méthode | URL (`/api` + ci-dessous) | Auth | Notes |
|---------|-----|------|-------|
| POST | `/billing/checkout` | Oui (JWT) | body `{planCode}` → `{checkoutUrl}` |
| POST | `/webhook/stripe` | **Non** (signature Stripe) | corps brut + `Stripe-Signature` |

### Contrat API (figé — importé par SF-09-03)

**`POST /api/billing/checkout`** `{ "planCode": "PRO" }` → `200 { "checkoutUrl": "https://checkout.stripe.com/..." }`
`400` planCode inconnu · `401` non authentifié · `503` fournisseur non configuré.

**`POST /api/webhook/stripe`** (appelé par Stripe) → `200` si traité/ignoré · `400` signature invalide.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `subscriptions` | SELECT/UPDATE | peuplement des colonnes Stripe + statut (aucune nouvelle migration) |

### Migration Liquibase

- [ ] Non applicable — colonnes Stripe déjà créées en `008` (SF-09-01).

### Dépendances techniques

- `com.stripe:stripe-java` (SDK officiel) ajouté au `pom.xml`.
- Config `app.billing.stripe.*` : `secret-key`, `webhook-secret`, `prices.{SOLO,PRO,DAILY}`, `success-url`, `cancel-url` — **tous via l'environnement**, jamais commités.
- SecurityConfig : `/webhook/**` en `permitAll` (auth par signature, pas JWT).

### Composants Angular

- Aucun (backend uniquement).

---

## Plan de test

### Tests unitaires

- [ ] `CheckoutService` — planCode inconnu → `UnknownPlanException` ; provider non configuré → `BillingProviderUnavailableException` ; nominal → délègue au provider avec le price ID résolu.
- [ ] `WebhookService` — `CHECKOUT_COMPLETED` → abonnement ACTIVE + ids ; `SUBSCRIPTION_DELETED` → CANCELED ; user introuvable → no-op ; idempotence (2 appels).
- [ ] `StripeBillingProvider` — `isConfigured()` = false si clé vide ; signature invalide → `WebhookVerificationException`.

### Tests d'intégration (`BillingCheckoutApiIntegrationTest`, provider stubé @Primary)

- [ ] `POST /billing/checkout` (PRO) → 200 + checkoutUrl.
- [ ] `POST /billing/checkout` (planCode inconnu) → 400.
- [ ] `POST /billing/checkout` sans JWT → 401.
- [ ] `POST /webhook/stripe` (event CHECKOUT_COMPLETED stubé) → 200 et abonnement passé ACTIVE.
- [ ] `POST /webhook/stripe` (signature invalide, provider réel) → 400.

### Isolation utilisateur

- [x] Applicable — le webhook ne mute que l'abonnement du user porté par l'événement (résolu par `userId`/`stripe_subscription_id`), jamais un autre.

---

## Dépendances

### Subfeatures bloquantes

- `SF-09-01` — **done** (table `subscriptions`, `SubscriptionService`, catalogue).

### Questions ouvertes impactées

- [ ] `OQ-07` (price IDs, TVA) — **contournée** : price IDs et URLs via configuration d'environnement ;
  Stripe Tax non activé en V1 (option de config ultérieure). Tracé comme arbitrage.

---

## Préoccupations transversales

- **Auth / Principal** : ajout d'un endpoint **public** `/webhook/**` (permitAll). Composants impactés :
  `SecurityConfig` (une règle ajoutée). **Aucun endpoint existant modifié** : la règle `anyRequest().authenticated()`
  reste ; le webhook est authentifié par **signature Stripe** (pas JWT). Test de non-régression : les endpoints
  billing existants (`/billing/plans`, `/billing/subscription`) restent 401 sans JWT (couvert par SF-09-01).
- **Plans / limites** : mutation du statut d'abonnement. Aucun service de quota n'existe encore (F-10) → aucun gate à réviser.

---

## Notes et décisions

- **Arbitrage — `BillingProvider` (interface) + `StripeBillingProvider`** : le code métier ne dépend jamais de Stripe
  en direct (parallèle de `AIProvider`), ce qui rend `CheckoutService`/`WebhookService` testables sans réseau et
  prépare un futur fournisseur de paiement. Réversible.
- **Arbitrage — mode `payment` pour DAILY, `subscription` pour mensuel** : un pass journée est un paiement unique
  (pas d'abonnement récurrent Stripe) ; `current_period_end = now + 1 j`. Réversible (config/logique).
- **Arbitrage — clé/secret Stripe via env, fournisseur dormant si absent (503)** : même patron que la clé Anthropic.
