# Mini-spec — [F-21 / SF-21-02] Rachat de tokens via Stripe (checkout one-shot + webhook)

## Identifiant — Parente — Statut

`F-21 / SF-21-02` — `F-21` Rachat de tokens (top-up) — `ready`

## Date — Branche

2026-07-03 — `feat/SF-21-02-topup-stripe`

---

## Objectif

Permettre à un utilisateur d'acheter ponctuellement un **pack de tokens** (top-up) via une session de
paiement Stripe **one-shot** (mode PAYMENT) ; à réception du **webhook signé** de paiement finalisé,
créditer les tokens sur son quota courant via `QuotaService.creditBonusTokens(userId, tokens)`, de façon
**idempotente** (aucun double-crédit en cas de rejeu du webhook).

---

## Comportement attendu

### Cas nominal

1. `GET /billing/topups` → catalogue des packs disponibles (code, libellé, nombre de tokens).
2. `POST /billing/topup/checkout {packCode}` → le backend résout le pack (catalogue serveur, montant de
   tokens **autoritatif côté serveur**), résout le **price ID** du pack depuis la config (env), et
   délègue au `BillingProvider` la création d'une session **PAYMENT** (one-shot). Réponse : `{checkoutUrl}`.
   Métadonnées portées par la session : `userId`, `kind=topup`, `topupCode`.
3. Après paiement, Stripe envoie `checkout.session.completed`. Le `StripeBillingProvider` vérifie la
   **signature**, détecte `kind=topup` et produit un `BillingEvent(TOPUP_COMPLETED, userId, …, eventId,
   topupCode)`. `WebhookService` résout le pack via le catalogue, crédite `pack.tokens()` sur la période
   courante de l'utilisateur (`creditBonusTokens`) et **marque l'événement comme traité** (idempotence).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `packCode` absent / vide | Rejet validation | 400 (`validation_error`) |
| `packCode` inconnu du catalogue | Rejet | 400 (`validation_error`) |
| Fournisseur non configuré (clé absente) ou price ID absent | Indisponible | 503 (`billing_unavailable`) |
| Échec d'appel Stripe (création session) | Erreur passerelle | 502 (`billing_error`) |
| Checkout sans JWT | Non authentifié | 401 |
| Webhook sans/mauvaise signature | Rejet | 400 (`invalid_signature`) |
| Webhook `TOPUP_COMPLETED` **rejoué** (même `eventId`) | Ignoré, **pas** de re-crédit | 200 |
| Webhook top-up sans `userId` ou pack inconnu | Ignoré silencieusement | 200 |

---

## Critères d'acceptation

- [ ] `GET /billing/topups` renvoie le catalogue des packs (code, label, tokens), protégé par JWT.
- [ ] `POST /billing/topup/checkout` (pack valide) renvoie une `checkoutUrl` ; le provider reçoit le mode
      PAYMENT et les métadonnées `kind=topup` + `topupCode`.
- [ ] Un pack inconnu ou absent → 400 ; sans JWT → 401 ; provider dormant → 503.
- [ ] Un webhook `TOPUP_COMPLETED` signé crédite exactement `pack.tokens()` sur la période de l'utilisateur.
- [ ] Un **rejeu** du même webhook (`eventId` identique) **ne recrédite pas** (idempotence).
- [ ] Le crédit cible le `userId` porté par l'événement — **isolation** : un autre utilisateur n'est pas affecté.
- [ ] Aucune clé/secret Stripe n'est journalisé ; le code métier ne dépend que de l'interface `BillingProvider`.

---

## Périmètre

### Hors scope (explicite)

- **Frontend** (bouton « Racheter des tokens » sur `/billing`) : SF-21-03.
- Multi-packs riches / remises / historique d'achats : hors V1 (un pack `STANDARD` suffit à l'UX SF-21-03).
- Remboursements / expiration des tokens rachetés (les bonus vivent sur la période courante, F-21 / SF-21-01).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `processed_billing_events.event_id` | id d'événement fournisseur | clé primaire, gate d'idempotence |
| `processed_billing_events.processed_at` | `now()` | horodatage du traitement |
| `usage_counters.bonus_tokens` | +`pack.tokens()` | crédité par `creditBonusTokens` (SF-21-01) |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `packCode` (requête) | Oui | code d'un pack du catalogue (`STANDARD`) | trim, comparaison insensible à la casse |
| montant de tokens crédité | — | **autoritatif serveur** (catalogue), jamais fourni par le client | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/billing/topups` | Oui (JWT) | USER |
| POST | `/api/billing/topup/checkout` | Oui (JWT) | USER |
| POST | `/api/webhook/stripe` | Non (signature) | — (public, existant, étendu) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `processed_billing_events` | INSERT / SELECT | **nouvelle** — ledger d'idempotence par `event_id` fournisseur |
| `usage_counters` | UPDATE | crédit `bonus_tokens` (via `QuotaService`, SF-21-01) |

### Migration Liquibase

- [x] Oui — `033-processed-billing-events.xml` (create table, réversible par dropTable). Numéro pré-assigné 033.

### Composants / classes

- **Nouveaux** : `TopUpPack`, `TopUpCatalog`, `TopUpService`, `provider/TopUpCheckoutCommand`,
  `ProcessedBillingEvent`, `ProcessedBillingEventRepository`, DTOs `TopUpCheckoutRequest`,
  `TopUpPackResponse`, `TopUpPacksResponse`, migration `033`.
- **Modifiés** : `BillingProvider` (+`createTopUpCheckoutSession`), `StripeBillingProvider` (impl + détection
  `kind=topup`, `eventId`), `BillingEvent` (+`eventId`, +`topupCode`), `BillingEventType` (+`TOPUP_COMPLETED`),
  `WebhookService` (+ crédit idempotent), `BillingController` (+2 endpoints), `BillingProperties.Stripe`
  (+`topupPrices`), `application.yml` (+`topup-prices`).

---

## Préoccupation transversale — Plans / limites

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `QuotaService.creditBonusTokens` | Appelé depuis le webhook top-up | Test : crédit exact, isolation `userId` |
| `WebhookService` | Nouveau type d'événement `TOPUP_COMPLETED` géré, idempotent | Tests unitaires + intégration (rejeu) |
| `BillingProvider` (interface) | Nouvelle méthode → stub de test à mettre à jour | Compilation + tests d'intégration |
| Endpoints existants (`/billing/checkout`, `/webhook/stripe` abonnement) | **Inchangés** (chemin abonnement préservé) | Suite billing existante verte (non-régression) |

---

## Plan de test

### Tests unitaires

- [ ] `TopUpCatalogTest` — catalogue non vide ; `find` insensible à la casse ; code inconnu → vide.
- [ ] `TopUpServiceTest` — pack valide → commande construite (mode/price délégués au provider) ; pack inconnu → `UnknownPlanException`.
- [ ] `StripeBillingProviderTest` — `createTopUpCheckoutSession` : non configuré → 503 ; price ID absent → 503.
- [ ] `WebhookServiceTest` — `TOPUP_COMPLETED` crédite via `QuotaService` ; **rejeu même `eventId` → un seul crédit** ; sans `userId` / pack inconnu / `eventId` absent → ignoré (aucun crédit).

### Tests d'intégration

- [ ] `GET /api/billing/topups` → 200 + packs (avec JWT) ; 401 sans JWT.
- [ ] `POST /api/billing/topup/checkout {STANDARD}` → 200 + `checkoutUrl` ; pack inconnu → 400 ; sans JWT → 401 ; provider dormant → 503.
- [ ] Webhook top-up (stub `TOPUP_COMPLETED`) → `usage_counters.bonus_tokens` d'Alice crédité ; second envoi même `eventId` → toujours un seul crédit.

### Isolation utilisateur

- [x] Applicable — le crédit cible le `userId` de l'événement ; test : le bonus d'Alice n'affecte pas Bob.

---

## Dépendances

### Subfeatures bloquantes

- `SF-21-01` — statut : done (fournit `creditBonusTokens` + colonne `bonus_tokens`).
- `SF-09-02` — statut : done (fournit `BillingProvider`/`StripeBillingProvider`/webhook).

### Questions ouvertes impactées

- Aucune nouvelle. Pricing/price IDs restent externalisés (OQ-07, déjà tranchée).

---

## Notes et décisions

- **Montant autoritatif serveur** : le nombre de tokens crédité vient du **catalogue serveur** (via
  `topupCode`), jamais du client ni du montant Stripe — défense en profondeur.
- **Idempotence** : table `processed_billing_events` (PK = `event_id` fournisseur). Pré-check `existsById`
  pour le rejeu séquentiel (cas courant) + gate d'insertion (contrainte PK) pour le rejeu concurrent ; le
  crédit et le marqueur sont dans la **même transaction** (rollback conjoint si échec).
- **Provider Independence** : nouvelle méthode sur l'interface `BillingProvider` ; le `WebhookService` ne
  connaît jamais Stripe. `BillingEvent` étendu de deux champs nullables (`eventId`, `topupCode`).
- **Sécurité** : secrets et price IDs par environnement uniquement ; aucune clé journalisée (patron F-09).
</content>
