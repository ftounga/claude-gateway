# Mini-spec — [F-107 / SF-107-03] Gold Vigie, Gold complet et option Vigie

---

## Identifiant

`F-107 / SF-107-03`

## Feature parente

`F-107` — L'offre par espace : la plateforme se paie, en BYOK aussi
(cadrage validé : `CADRAGE-F-107-l-offre-par-espace.md`, §3, §5, §9)

## Statut

`done` — livrée le 2026-09-13 (PR #534)

## Date de création

2026-09-13

## Branche Git

`feat/SF-107-03-gold-vigie-option-vigie`

---

## Objectif

Le catalogue vend les deux lignes d'offre de la grille décidée : **Gold Forge** (l'actuel Gold,
199 €), **Gold Vigie** (229 €), **Gold complet** (249 €) et l'**option Vigie** (69 €) sur Solo, Pro,
BYOK et Gold Forge — montants en configuration, **price IDs vides par défaut**.

---

## Contexte

SF-107-02 a posé `SpaceEntitlementService` (FORGE, VIGIE). Le cadrage §9 fixe les montants. Le droit
Vigie est aujourd'hui l'« option Teams » (`teams_option_status`), qui n'a **aucun parcours d'achat** :
ni price, ni checkout, ni webhook. L'option Vigie remplace les options Teams et Radar (§3) : elle
reprend cette colonne d'état et gagne le parcours d'achat de l'option Forge (F-40).

---

## Comportement attendu

### Cas nominal

1. **Plans** (`PlanCode`, `PlanCatalog`) :
   - `GOLD` reste le code de l'actuel Gold (**aucun abonnement ne change**), libellé **« Gold Forge »** ;
   - `GOLD_VIGIE` — « Gold Vigie », Hosted, mensuel ;
   - `GOLD_COMPLETE` — « Gold complet », Hosted, mensuel.
2. **Configuration** (`application.yml`, défauts décidés §9) :
   - `display-prices` : `GOLD_VIGIE: 229`, `GOLD_COMPLETE: 249` ;
   - `yearly-display-prices` : `GOLD_VIGIE: 2290` (Gold complet : aucun annuel décidé → aucune entrée) ;
   - `prices` / `yearly-prices` : `GOLD_VIGIE`, `GOLD_COMPLETE` **vides** → plans non listés, non
     souscriptibles, tant que le PO n'a pas créé les prices ;
   - `app.quota.plans` : `GOLD_VIGIE` et `GOLD_COMPLETE` = **12 000 000** (un seul quota, celui de Gold) ;
   - `vigie-option-price-id` **vide**, `vigie-option-display-price` **69**.
3. **Droits** (`SpaceEntitlementService`) :

   | Espace | Inclus dans | Option portée par |
   |---|---|---|
   | `FORGE` | `GOLD`, `GOLD_COMPLETE` | `SOLO`, `PRO`, `BYOK`, `GOLD_VIGIE` |
   | `VIGIE` | `GOLD_VIGIE`, `GOLD_COMPLETE` | `SOLO`, `PRO`, `BYOK`, `GOLD` |

   L'option Forge sur Gold Vigie prend le montant et le price Solo/Pro (40 €) ; la carte d'option dit
   qu'à ce prix Gold complet revient moins cher.
4. **Option Vigie — parcours d'achat** (miroir exact de l'option Forge, F-40) :
   - `GET /api/billing/vigie-option` → `priceEur`, `entitled`, `includedInPlan`, `status`, `cancelAt`,
     `available`, `includedForAdministrator`, `goldCarrier` ;
   - `POST /api/billing/vigie-option/checkout` → session d'un **abonnement distinct**, métadonnée
     `kind=vigie_option` ;
   - `POST /api/billing/vigie-option/cancel` → résiliation en fin de période ;
   - webhook : `VIGIE_OPTION_COMPLETED / UPDATED / DELETED` écrivent `teams_option_status` (état de
     l'option Vigie), `vigie_option_stripe_subscription_id`, `vigie_option_cancel_at` — **jamais** le
     plan ; seconde ligne de défense par l'identifiant d'abonnement d'option.
5. **Écran d'abonnement** : cartes Gold Forge (« Forge incluse »), Gold Vigie (« Vigie incluse — Teams,
   Radar, réunions »), Gold complet (« Forge et Vigie incluses ») quand elles sont listées ; section
   **Option Vigie** (montant, inclus, actif, administrateur, résiliation, « bientôt disponible » sans
   price) sous l'option Forge.
6. **`docs/TARIFS.md`** §7 bis : lignes Gold Vigie, Gold complet, option Vigie marquées « configurées
   (price à créer) ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Checkout option Vigie, plan incluant la Vigie (Gold Vigie, Gold complet) | `vigie_option_included` | 409 |
| Checkout option Vigie sans plan porteur en cours (essai, résilié) | `no_active_subscription` | 409 |
| Checkout option Vigie déjà en cours | `vigie_option_already_active` | 409 |
| Checkout option Vigie, price vide ou Stripe non configuré | `billing_unavailable` | 503 |
| Résiliation sans option en cours | `vigie_option_not_active` | 409 |
| Checkout / change-plan `GOLD_VIGIE` sans price | refus existant (plan non souscriptible) | 404 / 503 |
| Non authentifié | 401 | 401 |

---

## Critères d'acceptation

- [ ] CA1 — `GOLD` : Forge vrai, Vigie faux sans option ; Gold + option Vigie → Vigie vrai (non-régression).
- [ ] CA2 — `GOLD_VIGIE` actif : Vigie vrai (inclus), Forge faux sans option, Forge vrai avec option.
- [ ] CA3 — `GOLD_COMPLETE` actif : Forge et Vigie vrais (inclus) ; résilié : les deux faux.
- [ ] CA4 — Solo / Pro / BYOK / essai / administrateur : réponses d'avant inchangées.
- [ ] CA5 — `GET /billing/plans` ne liste ni `GOLD_VIGIE` ni `GOLD_COMPLETE` sans price ; avec price,
      229 / 249 € et 12 M jetons ; `GOLD` libellé « Gold Forge ».
- [ ] CA6 — `GET /billing/vigie-option` d'un Solo : `priceEur=69`, `available=false` (price vide) ;
      d'un Gold Vigie : `includedInPlan=true` ; d'un admin : `includedForAdministrator=true`.
- [ ] CA7 — checkout option Vigie avec price configuré → session `kind=vigie_option` sur ce price ;
      webhook de complétion → `teams_option_status=ACTIVE`, plan et `status` inchangés.
- [ ] CA8 — suppression de l'abonnement d'option Vigie (sans métadonnée) → option `CANCELED`, plan intact.
- [ ] CA9 — écran : cartes et section Option Vigie ; aucune couleur hors charte.
- [ ] CA10 — isolation : toute lecture passe par `getOrCreateForUser(userId)` du contexte de sécurité.

---

## Périmètre

### Hors scope (explicite)

- Création de prices Stripe (PO) ; tout changement de montant existant.
- Réserve de synchro liée au droit et essai Vigie par code (SF-107-04).
- Supplément par client par espace (SF-107-05).
- Migration d'un abonnement `GOLD` existant vers `GOLD_COMPLETE` (aucun abonnement ne change).
- Montant annuel de Gold complet (non décidé).
- Renommage des colonnes `atelier_option_*` / `teams_option_status`.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `display-prices.GOLD_VIGIE` | `229` | §9 |
| `display-prices.GOLD_COMPLETE` | `249` | §9 |
| `yearly-display-prices.GOLD_VIGIE` | `2290` | §9 |
| `prices.GOLD_VIGIE`, `prices.GOLD_COMPLETE` | vide | aucun price créé par un agent |
| `quota.plans.GOLD_VIGIE`, `GOLD_COMPLETE` | `12000000` | §3 : un seul quota, celui de Gold |
| `vigie-option-display-price` | `69` | §9 ; blanc → 69 |
| `vigie-option-price-id` | vide | aucun price créé par un agent |

## Contraintes de validation

Aucun champ saisi. Montant blanc → défaut ; price blanc → offre ou option non souscriptible.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/billing/vigie-option` | Oui | USER |
| POST | `/api/billing/vigie-option/checkout` | Oui | USER |
| POST | `/api/billing/vigie-option/cancel` | Oui | USER |
| GET | `/api/billing/plans` | Oui | USER — deux plans de plus quand ils ont un price |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `subscriptions` | ALTER + SELECT/UPDATE | `vigie_option_stripe_subscription_id` (unique), `vigie_option_cancel_at` |

### Migration Liquibase

- [x] `093-subscriptions-vigie-option.xml` — deux colonnes nullable + index unique ; rollback : drop.
      Aucun type propre à un moteur (PostgreSQL et H2).

### Composants impactés

- Backend : `PlanCode`, `PlanCatalog`, `SpaceEntitlementService`, `BillingProperties.Stripe`,
  `Subscription`, `SubscriptionRepository`, `VigieOptionService` (nouveau), exceptions d'option Vigie,
  `VigieOptionResponse`, `BillingController`, `BillingProvider` (+ `VigieOptionCheckoutCommand`),
  `StripeBillingProvider`, `BillingEventType`, `WebhookService`, `GlobalExceptionHandler`,
  `application.yml`.
- Frontend : `billing.models.ts`, `billing.service.ts`, `billing.component.{ts,html}`.
- Docs : `TARIFS.md` §7 bis, `ARCHITECTURE_CANONIQUE.md` (colonnes `subscriptions`).

### Préoccupations transversales

- [x] **Plans / limites** — nouveaux plans et nouveau porteur d'option. Composants qui lisent le plan
  ou le droit : `SpaceEntitlementService` (source unique) → `AtelierAccessService`,
  `TeamsAccessService` (Vigie, Radar, outils `teams_*`), `RunnerTeamsMomentController`,
  `AtelierOptionService`, `VigieOptionService` ; `EntitlementService` (quota par plan : 12 M par
  configuration, `isCustomerKeyBilled` : Hosted), `QuotaProperties.tokensForPlan`, `CheckoutService`
  / `SubscriptionService.changePlan` (plan listé seulement avec price), `BillingController.plans`,
  `AccessCodeService` (droit offert = `GOLD`, inchangé), `WebhookService`. Non-régression : suites
  d'entitlement, d'option Forge, de webhook et de facturation.
- [ ] Auth / Principal, tenant, navigation — non.

---

## Plan de test

### Tests unitaires

- [ ] `SpaceEntitlementServiceTest` — Gold Vigie, Gold complet (actif, résilié), Gold + option Vigie,
      Gold Vigie + option Forge ; porteurs par espace.
- [ ] `VigieOptionServiceTest` — vue (Solo 69, indisponible sans price, Gold Vigie inclus, admin, Gold
      porteur), checkout (inclus 409, sans plan 409, déjà active 409, price vide 503, nominal : price
      transmis), cancel (409 sans option, nominal).
- [ ] `WebhookServiceTest` — complétion / mise à jour / suppression d'option Vigie ; seconde ligne de
      défense ; plan jamais touché.
- [ ] `PlanCatalogTest` / `BillingProperties` — libellés, défaut 69.

### Tests d'intégration

- [ ] `VigieOptionBillingApiIntegrationTest` — 401 sans jeton ; Solo : 69 / indisponible ; Gold Vigie :
      inclus ; checkout sans price 503 ; cancel sans option 409.
- [ ] `BillingPlansApiIntegrationTest` (ou existant) — plans Gold Vigie / complet absents sans price.

### Frontend

- [ ] `billing.component.spec` — carte Gold Vigie « Vigie incluse » ; section Option Vigie à 69 € et
      « Bientôt disponible » sans price ; incluse sur Gold Vigie.

### Isolation

- [x] Applicable — lectures par `getOrCreateForUser(userId)` du `CurrentUser` ; webhook résolu par
      identifiant d'abonnement d'option, `userId` des métadonnées, puis client fournisseur.

---

## Dépendances

### Subfeatures bloquantes

- SF-107-02 (droits d'espace).

### Questions ouvertes impactées

- OQ-16 point 4 (concordance affichés ↔ Stripe) : s'étend aux nouveaux prices, sans changement de régime.

---

## Notes et décisions

- **`GOLD_COMPLETE`** plutôt que `GOLD_FULL` : reprend le nom décidé (« Gold complet »).
- **État de l'option Vigie dans `teams_option_status`** : l'option Vigie remplace l'option Teams
  (cadrage §3) ; renommer la colonne n'apporte rien et coûterait une migration de données.
- **Option Forge sur Gold Vigie au montant Solo/Pro** : aucune grille ne fixe un montant propre ;
  l'écran signale que Gold complet est moins cher plutôt que d'inventer un prix.
