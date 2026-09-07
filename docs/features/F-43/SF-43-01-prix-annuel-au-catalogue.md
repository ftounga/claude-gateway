# Mini-spec — F-43 / SF-43-01 — Le prix annuel au catalogue

## Identifiant

`F-43 / SF-43-01`

## Feature parente

`F-43` — Facturation annuelle

## Statut

`done`

## Date de création

2026-09-07

## Branche Git

`feat/SF-43-01-prix-annuel-catalogue`

---

## Objectif

Faire exister l'engagement annuel dans le modèle et la configuration — période `YEARLY`, price
Stripe annuel et prix d'affichage annuel par plan — et l'exposer au catalogue `GET /billing/plans`,
sans encore ouvrir aucune souscription annuelle.

---

## Comportement attendu

### Cas nominal

1. `BillingPeriod` porte une troisième valeur, `YEARLY`, aux côtés de `MONTHLY` et `DAILY`.
2. La configuration Stripe gagne deux mappings, du même style que `prices` et `display-prices` :
   - `yearly-prices` : code de plan → price ID Stripe **annuel**
     (`STRIPE_PRICE_SOLO_YEARLY`, `STRIPE_PRICE_PRO_YEARLY`, `STRIPE_PRICE_GOLD_YEARLY`, vides par défaut) ;
   - `yearly-display-prices` : code de plan → montant d'affichage annuel EUR
     (`STRIPE_DISPLAY_PRICE_SOLO_YEARLY:240`, `..._PRO_YEARLY:990`, `..._GOLD_YEARLY:1990`).
3. `display-prices` gagne l'entrée manquante `DAILY: ${STRIPE_DISPLAY_PRICE_DAILY:9}`.
4. `GET /billing/plans` enrichit chaque plan de deux champs :
   - `yearlyPriceEur` : le montant d'affichage annuel, ou `null` si l'annuel n'est pas proposé ;
   - `yearlyAvailable` : `true` **seulement** si un price ID annuel **et** un prix d'affichage annuel
     sont tous deux configurés pour ce plan.
5. Le quota renvoyé (`tokens`) reste l'allocation **mensuelle** du plan, à l'identique, quelle que
   soit la périodicité proposée.

### Règle de cohérence — pourquoi les deux conditions

Un price ID sans prix d'affichage donnerait un bouton « Payer à l'année » sans montant ; un prix
d'affichage sans price ID donnerait un montant sans paiement possible (503 au clic). `yearlyAvailable`
n'est vrai que quand les deux existent : l'écran ne peut pas proposer une offre à moitié configurée.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucun price annuel configuré pour un plan | `yearlyPriceEur = null`, `yearlyAvailable = false` — le plan reste listé en mensuel | 200 |
| Price annuel configuré, prix d'affichage annuel absent | `yearlyAvailable = false` (offre à moitié configurée, jamais proposée) | 200 |
| Prix d'affichage annuel configuré, price annuel absent | `yearlyAvailable = false` | 200 |
| Plan `DAILY` (pass journée, paiement unique) | Jamais annualisable : `yearlyAvailable = false` même si une valeur traînait en configuration | 200 |
| Appel non authentifié | Refus par la chaîne de sécurité existante (inchangée) | 401 |

---

## Critères d'acceptation

- [ ] `BillingPeriod.YEARLY` existe et `BillingPeriod.values()` a exactement trois valeurs.
- [ ] `application.yml` porte `yearly-prices` (SOLO/PRO/GOLD, vides par défaut) et
      `yearly-display-prices` (240/990/1990), en variables d'environnement, sans aucune constante en dur.
- [ ] `display-prices` porte `DAILY: ${STRIPE_DISPLAY_PRICE_DAILY:9}`.
- [ ] `BillingProperties.Stripe.yearlyPriceId(code)` et `yearlyDisplayPrice(code)` renvoient la valeur
      configurée, `null` sinon, et ne lèvent jamais sur un mapping absent.
- [ ] `BillingProperties.Stripe.isYearlyAvailable(Plan)` est vrai **si et seulement si** un price ID
      annuel et un prix d'affichage annuel sont tous deux renseignés, **et** que la période native du
      plan est `MONTHLY` (un pass journée est un paiement unique : il ne s'annualise pas).
- [ ] `GET /billing/plans` renvoie `yearlyPriceEur` et `yearlyAvailable` pour chaque plan listé.
- [ ] Le champ `tokens` de la réponse reste l'allocation **mensuelle** du plan, inchangée — test de
      non-régression explicite (le quota ne suit pas la périodicité).
- [ ] Aucun price ID Stripe (mensuel ou annuel) n'apparaît dans la réponse.
- [ ] Isolation : `GET /billing/plans` est un catalogue global sans données utilisateur ; l'endpoint
      reste authentifié et aucun `user_id` n'entre dans sa résolution — vérifié par test.

---

## Périmètre

### Hors scope (explicite)

- Souscrire à l'année (checkout, changement de plan) → SF-43-02.
- Persister la périodicité d'un abonnement → SF-43-02.
- Toute modification de l'écran de facturation → SF-43-03.
- Toute modification du quota, de `EntitlementService` ou de `QuotaService` : **rien** n'y est touché.
- La migration des abonnés mensuels et le prorata de changement de période (hors périmètre F-43).

---

## Valeurs initiales

Aucune entité créée ni modifiée. Uniquement de la configuration et une projection d'API.

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `yearly-prices.*` | vide | Vide ⇒ l'annuel n'est pas proposé pour ce plan (fail-closed, comme `prices`) |
| `yearly-display-prices.SOLO` | `240` | Dix mois payés sur douze à 24 €/mois |
| `yearly-display-prices.PRO` | `990` | Dix mois payés sur douze à 99 €/mois |
| `yearly-display-prices.GOLD` | `1990` | Dix mois payés sur douze à 199 €/mois |
| `display-prices.DAILY` | `9` | Anomalie corrigée : le pass journée n'avait aucun prix d'affichage |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `yearly-prices[code]` | Non | — | identifiant de price fournisseur opaque | — | aucune (jamais interprété) |
| `yearly-display-prices[code]` | Non | — | chaîne d'affichage (ex. `240`) — cosmétique, jamais un débit | — | aucune |
| `display-prices[DAILY]` | Non | — | chaîne d'affichage (ex. `9`) | — | aucune |
| clé de mapping | Oui si entrée présente | — | nom d'une valeur de `PlanCode` | Oui (map) | `name()` exact |

Notes :
- Les prix d'affichage sont **purement cosmétiques** : le débit réel est porté par le price Stripe.
  Une valeur d'affichage incohérente est un défaut de configuration, jamais un défaut de facturation.
- Une clé inconnue de `PlanCode` dans un mapping est simplement ignorée (aucun plan ne la lit).

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/billing/plans` | Oui | utilisateur authentifié (contrat **enrichi**, non cassé) |

### Tables impactées

Aucune.

| Table | Opération | Notes |
|-------|-----------|-------|
| — | — | Aucune écriture, aucune lecture nouvelle |

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucun changement de schéma.

### Composants Angular (si applicable)

Aucun. Le modèle TypeScript `BillingPeriod` reste inchangé en SF-43-01 : le champ ajouté à l'API est
simplement ignoré par l'écran existant (les champs inconnus n'affectent pas un typage structurel).
Sa consommation arrive en SF-43-03.

---

## Plan de test

### Tests unitaires

- [ ] `BillingPeriodTest` — `YEARLY` existe, trois valeurs au total, `valueOf("YEARLY")` résout.
- [ ] `BillingPropertiesYearlyTest` — `yearlyPriceId` / `yearlyDisplayPrice` renvoient la valeur configurée.
- [ ] `BillingPropertiesYearlyTest` — mappings `null` ⇒ maps vides, aucun `NullPointerException`.
- [ ] `BillingPropertiesYearlyTest` — `isYearlyAvailable` vrai quand price **et** affichage sont présents.
- [ ] `BillingPropertiesYearlyTest` — `isYearlyAvailable` faux si l'un des deux manque (les deux sens).
- [ ] `BillingPropertiesYearlyTest` — `isYearlyAvailable` faux sur le pass journée, même entièrement configuré.
- [ ] `BillingPropertiesYearlyTest` — `displayPrice(DAILY)` renvoie la valeur configurée (anomalie corrigée).
- [ ] `BillingPropertiesYearlyTest` — la résolution **mensuelle** est inchangée (non-régression).
- [ ] `PlanResponseTest` — projection : `yearlyPriceEur` et `yearlyAvailable` portés, `tokens` inchangé.

### Tests d'intégration

- [ ] `GET /api/billing/plans` → 200, chaque plan porte `yearlyPriceEur` et `yearlyAvailable`.
- [ ] `GET /api/billing/plans` → un plan sans price annuel a `yearlyAvailable = false`.
- [ ] `GET /api/billing/plans` → aucun price ID Stripe dans le corps de réponse.
- [ ] `GET /api/billing/plans` → `tokens` du plan reste l'allocation mensuelle configurée
      (**non-régression : le quota ne suit pas la périodicité**).
- [ ] `GET /api/billing/plans` → 401 sans jeton.

### Isolation utilisateur

- [x] Applicable — vérifiée par la nature de l'endpoint : le catalogue est **global** et sa
      construction ne lit **aucune** donnée d'utilisateur (aucun appel à `currentUser`, aucun accès
      repository). Test : deux utilisateurs distincts obtiennent exactement la même réponse, ce qui
      prouve qu'aucune donnée d'un tiers ne peut y fuir.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---------------|--------|---------------------|
| Auth / Principal | Non | Aucun changement : `GET /billing/plans` garde sa règle de sécurité |
| Contexte tenant | Non | Aucun `user_id` n'entre dans la résolution du catalogue |
| Plans / limites | **Oui** | Voir la liste ci-dessous |
| Navigation / routing | Non | Aucune route ajoutée ou modifiée |

### Plans / limites — composants impactés et vérification

L'ajout d'une valeur à `BillingPeriod` et de deux mappings de configuration touche la zone « plans ».
Composants qui lisent ces objets, et vérification faite sur chacun :

| Composant | Lit quoi | Impact | Vérification |
|-----------|----------|--------|--------------|
| `PlanCatalog` | `BillingPeriod` (période native de chaque plan) | Aucun : les cinq plans gardent leur période native (`MONTHLY`, sauf `DAILY`) | `PlanCatalogTest` inchangé, doit rester vert |
| `StripeBillingProvider.createCheckoutSession` | `plan.period()` pour choisir `PAYMENT` vs `SUBSCRIPTION` | Aucun : le test porte sur `== DAILY`, une nouvelle valeur ne bascule rien | `StripeBillingProviderTest` inchangé, doit rester vert |
| `CheckoutService` | `properties.stripe().priceId(...)` | Aucun : le mapping mensuel est intact | `CheckoutServiceTest` inchangé |
| `SubscriptionService.changePlan` | `properties.stripe().priceId(...)` | Aucun | `SubscriptionServiceTest` inchangé |
| `BillingController.plans()` | `priceId`, `displayPrice`, `tokensForPlan` | **Modifié** : deux champs ajoutés à la projection ; le filtre « price mensuel configuré » reste la condition d'être listé | `BillingApiIntegrationTest` étendu |
| `QuotaProperties.tokensForPlan` | `PlanCode` uniquement | **Aucun** — et c'est le point : l'allocation ne connaît pas la périodicité | Test de non-régression explicite |
| `EntitlementService.resolveMonthlyTokenQuota` | statut + `PlanCode` | **Aucun** — non touché par cette subfeature | `EntitlementServiceTest` inchangé |
| `QuotaService.currentPeriodStart` | horloge, mois calendaire UTC | **Aucun** | `QuotaServiceTest` inchangé |
| Frontend `Plan` / `BillingPeriod` | contrat `/billing/plans` | Contrat **enrichi**, jamais cassé : les champs existants gardent nom et type | Tests front existants doivent rester verts sans modification |

---

## Dépendances

### Subfeatures bloquantes

Aucune. SF-43-01 est le socle de F-43.

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` n'est touchée. `OQ-07` (pricing hors du code) est
      **respectée** : les montants annuels sont en variables d'environnement, jamais en constante.

---

## Notes et décisions

- **Pourquoi ne pas créer des `PlanCode` `SOLO_YEARLY`, `PRO_YEARLY`…** — ce serait la voie la plus
  courte et la pire : `QuotaProperties.plans` est indexé par `PlanCode`, `AtelierEntitlementService`
  teste `PlanCode == GOLD`, `EntitlementService` dérive le mode fournisseur du `PlanCode`. Doubler
  l'énumération obligerait à dupliquer **chacune** de ces tables de correspondance, et le jour où
  l'une serait oubliée, un abonné Gold annuel perdrait l'Atelier ou son quota. La périodicité est un
  **axe orthogonal** au plan : elle vit à côté, jamais dans le code de plan.
- **Le prix d'affichage annuel n'est pas calculé depuis le mensuel.** Multiplier 24 par 10 dans le
  code figerait la remise dans le binaire ; le PO doit pouvoir passer à onze mois payés, ou faire une
  promotion sur le seul plan Pro, sans redéploiement. Les 240 / 990 / 1990 sont donc des **défauts**,
  pas une formule.
- **`DAILY` ne s'annualise pas** : un pass journée est un paiement unique (`Mode.PAYMENT` chez le
  fournisseur), pas un abonnement. `isYearlyAvailable` l'exclut explicitement plutôt que de compter
  sur l'absence de configuration — une configuration accidentelle ne doit pas créer une offre absurde.
- **`STRIPE_DISPLAY_PRICE_DAILY:9`** — valeur commerciale prise par défaut, à revoir par le PO. Le
  pass journée alloue 500 k jetons, soit la moitié du Solo mensuel (1 M à 24 €) ; 9 € place la journée
  au-dessus du prorata mensuel, ce qui est la logique d'un pass ponctuel.
