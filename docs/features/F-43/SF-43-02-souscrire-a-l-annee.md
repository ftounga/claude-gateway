# Mini-spec — F-43 / SF-43-02 — Souscrire à l'année

## Identifiant

`F-43 / SF-43-02`

## Feature parente

`F-43` — Facturation annuelle

## Statut

`done`

## Date de création

2026-09-07

## Branche Git

`feat/SF-43-02-souscrire-a-l-annee`

---

## Objectif

Rendre l'engagement annuel réellement souscriptible : le paiement et le changement de plan portent
une périodicité, l'abonnement la mémorise, et **le quota reste mensuel** — prouvé par un test.

---

## Comportement attendu

### Cas nominal — souscription

1. `POST /billing/checkout` accepte un champ **optionnel** `period` (`MONTHLY` | `YEARLY`).
   Absent, vide ou `MONTHLY` ⇒ comportement actuel, à l'octet près.
2. `period = YEARLY` : le service résout le **price annuel** du plan
   (`stripe.yearlyPriceId`) au lieu du mensuel, après avoir vérifié que l'offre annuelle est
   réellement disponible (`stripe.isYearlyAvailable`, SF-43-01).
3. La session de paiement porte la métadonnée `billingPeriod`, exactement comme elle porte déjà
   `planCode`. Le webhook la relit et la persiste sur l'abonnement.
4. `checkout.session.completed` pose `subscriptions.billing_period` **en même temps** que
   `plan_code` et `status = ACTIVE`. Un événement qui ne porte pas de périodicité (tous les
   événements antérieurs à F-43, et tous ceux d'option Atelier ou de top-up) **n'en change aucune**.

### Cas nominal — changement de plan

5. `POST /billing/subscription/change` accepte le même champ optionnel `period`. Passer de mensuel à
   annuel — ou changer de plan **et** de périodicité en un geste — remplace le price de l'abonnement
   fournisseur par le price annuel cible ; la proratisation reste celle du fournisseur, aucune règle
   maison n'est ajoutée.
6. Le reflet local est optimiste (`plan_code` **et** `billing_period`), comme aujourd'hui pour
   `plan_code` seul ; le webhook confirme.

### Cas nominal — lecture

7. `GET /billing/subscription` expose `billingPeriod` (`MONTHLY` | `YEARLY` | `DAILY` | `null`).
   `null` signifie « aucun engagement enregistré » : les abonnements antérieurs à F-43 et les essais.

### LA règle : le quota reste mensuel

8. Un abonnement dont `billing_period = YEARLY` reçoit **exactement** le même quota qu'un abonnement
   mensuel du même plan : `EntitlementService.resolveMonthlyTokenQuota` ne lit que le statut et le
   `PlanCode`, jamais la périodicité, et `QuotaService.currentPeriodStart` reste le premier jour du
   mois calendaire UTC. **Aucune ligne** de `fr.claudegateway.quota` n'est modifiée par cette
   subfeature, et deux tests le figent.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `period` absent, vide ou blanc | Traité comme `MONTHLY` (compatibilité stricte du contrat existant) | 200 |
| `period` inconnu (ex. `WEEKLY`, `annuel`) | `validation_error` — refus explicite, aucun repli silencieux sur mensuel | 400 |
| `period = DAILY` demandé par le client | `validation_error` — la périodicité journalière est la **nature** du pass, pas un choix d'achat | 400 |
| `period = YEARLY` sur un plan sans offre annuelle configurée | `yearly_not_available` — jamais un repli silencieux vers le price mensuel (le client paierait autre chose que ce qu'il a demandé) | 409 |
| `period = YEARLY` sur le plan `DAILY` | `yearly_not_available` (un paiement unique ne s'annualise pas) | 409 |
| Plan inconnu | `validation_error` (inchangé) | 400 |
| Changement de plan sans abonnement actif | `no_active_subscription` (inchangé) | 409 |
| Fournisseur de paiement non configuré | `billing_unavailable` (inchangé) | 503 |
| Non authentifié | Refus par la chaîne de sécurité (inchangée) | 401 |

---

## Critères d'acceptation

- [ ] `POST /billing/checkout` sans `period` produit **exactement** la même session qu'avant F-43
      (price mensuel, même métadonnée `planCode`) — non-régression figée par test.
- [ ] `POST /billing/checkout` avec `period = YEARLY` utilise le **price annuel** du plan.
- [ ] `POST /billing/checkout` avec `period = YEARLY` sur un plan sans offre annuelle répond **409
      `yearly_not_available`** et n'appelle **pas** le fournisseur.
- [ ] `POST /billing/checkout` avec un `period` inconnu répond 400 et n'appelle pas le fournisseur.
- [ ] `POST /billing/subscription/change` avec `period = YEARLY` envoie le price annuel au
      fournisseur et reflète `billing_period = YEARLY` localement.
- [ ] `checkout.session.completed` portant `billingPeriod=YEARLY` persiste `billing_period = YEARLY`.
- [ ] Un événement **sans** périodicité laisse `billing_period` **inchangé** (ni écrasé, ni remis à
      `null`) — test explicite sur un abonnement déjà annuel.
- [ ] `GET /billing/subscription` expose `billingPeriod`, et **aucun** identifiant Stripe.
- [ ] **Le quota reste mensuel** : un abonnement `YEARLY` résout la même allocation qu'un
      abonnement `MONTHLY` du même plan, et la période de consommation reste le mois calendaire.
- [ ] Aucun fichier de `fr.claudegateway.quota` n'est modifié par cette subfeature.
- [ ] Isolation : `billing_period` n'est jamais lu ni écrit sans passer par l'abonnement de
      l'utilisateur du contexte de sécurité — un utilisateur ne peut pas modifier la périodicité
      d'un autre (test à deux utilisateurs).

---

## Périmètre

### Hors scope (explicite)

- L'écran de facturation → SF-43-03.
- La **migration automatique** des abonnés mensuels vers l'annuel (hors F-43).
- Le **prorata** de changement de période en cours d'année : la proratisation existante du
  fournisseur (`CREATE_PRORATIONS`) est conservée telle quelle, aucune règle maison.
- Toute forme de quota annuel.
- L'option Atelier (F-40) reste **mensuelle** : elle n'est pas annualisée ici.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `subscriptions.billing_period` | `NULL` | Nullable et sans défaut : aucun abonnement existant n'est réécrit, et `NULL` se lit « aucun engagement enregistré » (essai, ou abonnement antérieur à F-43) |

Comportements à la création :
- Un essai provisionné (`provisionTrial`) laisse `billing_period` à `NULL` : il n'y a pas
  d'engagement tant qu'il n'y a pas de paiement.
- `billing_period` n'est peuplé que par un paiement confirmé (webhook) ou un changement de plan
  explicite de l'utilisateur.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `CheckoutRequest.planCode` | Oui | — | valeur de `PlanCode` (inchangé) | Non | `trim()` + majuscules |
| `CheckoutRequest.period` | **Non** | 16 | `MONTHLY` ou `YEARLY` uniquement | Non | `trim()` + majuscules ; vide ⇒ `MONTHLY` |
| `ChangePlanRequest.period` | **Non** | 16 | `MONTHLY` ou `YEARLY` uniquement | Non | idem |
| `subscriptions.billing_period` | Non | 16 | `MONTHLY`, `DAILY`, `YEARLY`, ou `NULL` | Non | `EnumType.STRING` |

Notes :
- `DAILY` est refusé **en entrée** bien qu'il soit une valeur légale en base : c'est la nature du
  pass journée, imposée par le catalogue, pas une périodicité que l'acheteur choisit. Accepter la
  valeur laisserait croire qu'on peut acheter un plan mensuel « à la journée ».
- Le champ est optionnel **par conception** : le contrat existant du frontend ne l'envoie pas, et
  doit continuer de fonctionner sans modification.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/billing/checkout` | Oui | utilisateur authentifié (champ **ajouté**, optionnel) |
| POST | `/api/billing/subscription/change` | Oui | utilisateur authentifié (champ **ajouté**, optionnel) |
| GET | `/api/billing/subscription` | Oui | utilisateur authentifié (réponse **enrichie**) |
| POST | `/api/billing/webhook` | Signature fournisseur | — (métadonnée **ajoutée**) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `subscriptions` | `ALTER` (ajout de colonne) + `UPDATE` | `billing_period varchar(16)` nullable |

### Migration Liquibase

- [x] Oui — `062-subscriptions-billing-period.xml` (nullable, sans défaut, rollback = `dropColumn`)
- [ ] Non applicable

### Composants Angular (si applicable)

Aucun. Le modèle TypeScript reste inchangé : le champ ajouté à la réponse est ignoré par l'écran
existant, et le champ de requête est optionnel. Sa consommation arrive en SF-43-03.

---

## Plan de test

### Tests unitaires

- [ ] `CheckoutServiceTest` — sans `period` : price **mensuel** transmis au fournisseur (non-régression).
- [ ] `CheckoutServiceTest` — `period = YEARLY` : price **annuel** transmis.
- [ ] `CheckoutServiceTest` — `period` inconnu ⇒ `UnknownBillingPeriodException`, fournisseur jamais appelé.
- [ ] `CheckoutServiceTest` — `period = DAILY` ⇒ refus, fournisseur jamais appelé.
- [ ] `CheckoutServiceTest` — `YEARLY` sur plan sans offre annuelle ⇒ `YearlyBillingUnavailableException`,
      fournisseur jamais appelé (**pas** de repli sur le price mensuel).
- [ ] `SubscriptionServiceTest` — `changePlan` avec `YEARLY` : price annuel envoyé, `billing_period`
      reflété localement.
- [ ] `SubscriptionServiceTest` — `changePlan` sans `period` : price mensuel, `billing_period` posé à
      `MONTHLY` (l'utilisateur a explicitement choisi une offre mensuelle).
- [ ] `BillingPeriodSelectionTest` — la règle d'achat au complet : normalisation, refus d'une
      périodicité inconnue, refus de `DAILY` en entrée, price mensuel/annuel, refus de l'annuel sans
      offre, pass journée non annualisable.
- [ ] `CheckoutCommandTest` — la commande porte la périodicité **achetée**, et retombe sur celle du
      plan quand aucune n'est donnée (compatibilité F-09). *Le mode de paiement et la métadonnée
      construits par `StripeBillingProvider` ne sont pas testables sans appeler le SDK Stripe ; le
      contrat qui les détermine — `CheckoutCommand.period()` — l'est, et c'est lui qui est figé.*
- [ ] `WebhookServiceTest` — `CHECKOUT_COMPLETED` avec `YEARLY` persiste `billing_period = YEARLY`.
- [ ] `WebhookServiceTest` — événement **sans** périodicité : `billing_period` inchangé.
- [ ] `EntitlementServiceYearlyTest` — **le quota reste mensuel** : même allocation pour `YEARLY` et
      `MONTHLY` à plan et statut identiques ; un abonnement annuel n'obtient pas douze fois le quota.

### Tests d'intégration

- [ ] `POST /api/billing/checkout` `{planCode, period:"YEARLY"}` → 200 (plan avec offre annuelle).
- [ ] `POST /api/billing/checkout` `{planCode:"PRO", period:"YEARLY"}` → 409 `yearly_not_available`.
- [ ] `POST /api/billing/checkout` `{planCode, period:"WEEKLY"}` → 400.
- [ ] `POST /api/billing/checkout` `{planCode}` (sans period) → 200 (non-régression).
- [ ] `POST /api/billing/subscription/change` `{planCode, period:"YEARLY"}` → 200, `billingPeriod` renvoyé.
- [ ] `GET /api/billing/subscription` → expose `billingPeriod`, aucun identifiant Stripe.
- [ ] `GET /api/usage` d'un abonné annuel → quota **mensuel** du plan, période = mois calendaire.

### Isolation utilisateur

- [x] Applicable — test : Alice souscrit à l'année, Bob reste mensuel ; l'abonnement de Bob garde
      `billing_period = null` et son quota est inchangé. Chaque écriture passe par
      `subscriptionRepository.findByUserId(userId du contexte)` — aucun identifiant de ressource
      n'est accepté du client.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---------------|--------|---------------------|
| Auth / Principal | Non | Aucun changement : les trois endpoints gardent leur règle de sécurité et lisent le `userId` du JWT |
| Contexte tenant | **Oui** | Voir la liste ci-dessous |
| Plans / limites | **Oui** | Voir la liste ci-dessous |
| Navigation / routing | Non | Aucune route ajoutée |

### Contexte tenant — composants impactés

| Composant | Résolution du tenant | Impact | Vérification |
|-----------|---------------------|--------|--------------|
| `BillingController.checkout` | `currentUser.principal().id()` | **Inchangé** — `period` est une donnée d'achat, jamais un identifiant de ressource | Test d'intégration à deux utilisateurs |
| `BillingController.changePlan` | `currentUser.requireId()` | **Inchangé** | idem |
| `BillingController.subscription` | `currentUser.requireId()` | **Inchangé** | idem |
| `SubscriptionService.changePlan` | `getOrCreateForUser(userId)` | **Inchangé** — l'écriture de `billing_period` passe par le même abonnement filtré | Test d'isolation |
| `WebhookService.resolve` | identifiants fournisseur puis `userId` | **Inchangé** — aucune nouvelle clé de résolution ; `billing_period` est écrit sur l'abonnement déjà résolu | `WebhookServiceTest` |

### Plans / limites — composants impactés

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `CheckoutService` | **Modifié** — résolution du price selon la périodicité | Tests unitaires + intégration |
| `SubscriptionService.changePlan` | **Modifié** — idem, plus le reflet local | Tests unitaires |
| `StripeBillingProvider.createCheckoutSession` | **Modifié** — mode et métadonnée ; `DAILY` reste `PAYMENT`, `MONTHLY` et `YEARLY` restent `SUBSCRIPTION` | `StripeBillingProviderTest` |
| `WebhookService` | **Modifié** — persiste la périodicité quand l'événement la porte | `WebhookServiceTest` |
| `QuotaProperties.tokensForPlan` | **Aucun** — ne connaît que le `PlanCode` | Test de non-régression |
| `EntitlementService.resolveMonthlyTokenQuota` | **Aucun** — ne lit pas `billing_period` | `EntitlementServiceYearlyTest` |
| `QuotaService.currentPeriodStart` | **Aucun** — mois calendaire UTC | Test d'intégration `/usage` |
| `QuotaAlertService` (F-42) | **Aucun** — le seuil porte sur le quota effectif de la période mensuelle | Tests F-42 inchangés, doivent rester verts |
| `AtelierEntitlementService` / `AtelierAccessService` | **Aucun** — le droit d'Atelier se lit sur le plan et l'option, jamais sur la périodicité | Tests inchangés, doivent rester verts |
| `TopUpService` | **Aucun** — un rachat reste un paiement unique | Tests inchangés |

---

## Dépendances

### Subfeatures bloquantes

- `SF-43-01` — statut : **done** (fournit `BillingPeriod.YEARLY`, `yearlyPriceId`, `isYearlyAvailable`).

### Questions ouvertes impactées

- [ ] Aucune. `OQ-07` (pricing hors du code) reste respectée.

---

## Notes et décisions

- **Un constructeur secondaire plutôt qu'une refonte de `BillingEvent`.** Ajouter un composant à ce
  record obligerait à réécrire ses 19 sites de construction, dont une majorité de tests qui n'ont
  rien à voir avec la périodicité — beaucoup de bruit, et autant de surface de conflit avec les
  sessions parallèles. Un constructeur secondaire délègue au canonique avec `billingPeriod = null`,
  et ce `null` **porte exactement la bonne sémantique** : un événement qui ne dit rien de la
  périodicité ne doit en changer aucune.
- **Pas de repli silencieux vers le mensuel** quand l'annuel est demandé mais indisponible. Le repli
  serait la voie confortable, et il ferait payer au client autre chose que ce qu'il a demandé.
  409 `yearly_not_available`, explicitement.
- **`DAILY` refusé en entrée.** La périodicité journalière est imposée par le catalogue (le pass
  journée *est* un paiement unique) ; l'accepter comme choix d'achat laisserait croire qu'on peut
  acheter un plan mensuel à la journée.
- **`billing_period` nullable, jamais rétro-rempli.** Écrire `MONTHLY` sur tous les abonnements
  existants serait une affirmation qu'on ne peut pas prouver : on ne sait pas ce qui a été souscrit
  avant que la colonne existe. `NULL` dit « aucun engagement enregistré », ce qui est exact.
- **L'option Atelier reste mensuelle.** L'annualiser demanderait un second price annuel et une règle
  de synchronisation des termes entre deux abonnements fournisseur — un sujet à part entière, hors
  périmètre de F-43.
