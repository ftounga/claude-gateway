# Mini-spec — [F-41 / SF-41-01] Le plan BYOK au catalogue, et le zéro qui n'est pas un impayé

## Identifiant

`F-41 / SF-41-01`

## Feature parente

`F-41` — Plan BYOK (plateforme seule)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-41-01-plan-byok-catalogue`

---

## Objectif

Ajouter au catalogue un plan **BYOK** — accès plateforme et Atelier compris, **allocation de jetons
nulle**, appels servis par la clé du client — et faire en sorte que ce zéro de jetons **n'ouvre pas
la même porte** que le zéro d'un abonnement expiré.

---

## Comportement attendu

### Cas nominal

1. `GET /api/billing/plans` expose une entrée `BYOK` (`providerMode: "BYOK"`, `period: "MONTHLY"`,
   `tokens: 0`, `priceEur: "29"`), **si et seulement si** un price Stripe lui est configuré — même
   règle de filtrage que les autres plans (un plan sans price n'est pas souscriptible).
2. `POST /api/billing/checkout` avec `planCode: "BYOK"` crée la session de paiement, exactement
   comme pour les autres plans (aucun chemin de paiement spécifique).
3. Une fois l'abonnement `ACTIVE` sur le plan `BYOK` :
   - `EntitlementService.resolveMonthlyTokenQuota` rend **0** (le plan n'alloue aucun jeton) ;
   - `EntitlementService.isCustomerKeyBilled` rend **vrai** : les jetons sont sur le compte du
     client ;
   - `QuotaService.assertWithinQuota` **ne bloque pas** — il n'y a aucun quota plateforme à
     épuiser ;
   - `AtelierEntitlementService.isEntitled` rend **vrai** : l'Atelier est compris dans l'offre, au
     même titre que Gold (le droit reste un droit, pas un jeton).
4. Un abonnement **expiré / annulé / essai échu** continue de résoudre 0 jeton **et** de bloquer :
   `isCustomerKeyBilled` est faux, `assertWithinQuota` lève `QuotaExceededException` (402).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucun price Stripe configuré pour `BYOK` | Le plan n'apparaît pas dans `GET /billing/plans` ; `POST /billing/checkout` le refuse comme tout plan sans price | 400 `unknown_plan` |
| Abonnement BYOK `CANCELED` (ou essai échu) | Zéro jeton **et** accès bloqué : `isCustomerKeyBilled` faux | 402 `quota_exceeded` |
| Abonnement BYOK `PAST_DUE` (sursis de paiement) | Traité comme en cours, exactement comme les autres plans : pas de blocage | — |
| Plan BYOK actif mais aucune clé enregistrée | **Hors périmètre de cette subfeature** : traité en SF-41-02 (`409 byok_key_required`) | — |
| Utilisateur d'un autre compte | Aucun accès croisé : toute résolution part du `userId` du contexte de sécurité | 401/403 |

---

## Critères d'acceptation

- [ ] `PlanCode` déclare `BYOK` et `PlanCatalog` expose l'entrée `new Plan(BYOK, "BYOK", ProviderMode.BYOK, MONTHLY)`.
- [ ] `application.yml` porte `STRIPE_PRICE_BYOK` (vide par défaut), `APP_BILLING_BYOK_PRICE` (défaut **29**) dans le bloc `display-prices`, et `APP_QUOTA_BYOK_TOKENS` (défaut **0**) dans `app.quota.plans`. Aucune valeur commerciale en dur dans le code.
- [ ] `EntitlementService.resolveMonthlyTokenQuota` rend 0 pour un abonnement BYOK actif (allocation nulle assumée).
- [ ] `EntitlementService.isCustomerKeyBilled` rend vrai pour BYOK `ACTIVE`/`PAST_DUE`, faux pour BYOK `CANCELED`/`INCOMPLETE`/essai, faux pour tout plan Hosted.
- [ ] `QuotaService.assertWithinQuota` **ne lève pas** pour un BYOK actif, quelle que soit la consommation déjà enregistrée.
- [ ] `QuotaService.assertWithinQuota` **lève toujours** `QuotaExceededException` pour un abonnement expiré/annulé (zéro non-BYOK) — non-régression du fail-closed.
- [ ] `AtelierEntitlementService.isEntitled` et `isIncludedInPlan` rendent vrai pour un BYOK actif ; `AtelierAccessService.hasAccess()` s'ouvre en conséquence.
- [ ] **Non-régression Gold, Solo, Pro, Daily, essai, expiré** : les quotas et droits d'Atelier de ces états sont identiques avant/après.
- [ ] Isolation : toute résolution part du `userId` du contexte de sécurité ; aucun test ne fait fuir l'abonnement d'un autre utilisateur.

---

## Périmètre

### Hors scope (explicite)

- Le refus quand le plan est BYOK et qu'aucune clé n'est enregistrée (→ SF-41-02).
- Toute modification de l'écran de facturation (→ SF-41-03).
- La facturation à l'usage réel de la clé du client, et tout partage de clé entre comptes.
- Le changement de plan vers/depuis BYOK par `POST /billing/subscription/change` : il fonctionne
  déjà de façon générique (price configuré ⇒ changement possible), aucun code spécifique n'est écrit.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `plan_code` | inchangé (`null` à l'essai) | Aucune migration : `plan_code` est un `varchar(32)` sans contrainte d'énumération ; `BYOK` s'y écrit comme `GOLD`. |
| quota `BYOK` | `0` | Valeur de configuration (`APP_QUOTA_BYOK_TOKENS`), réversible par environnement. |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `planCode` (checkout / change) | Oui | 32 | `SOLO`, `PRO`, `DAILY`, `GOLD`, **`BYOK`** | Non | `trim().toUpperCase()` (existant) |
| `STRIPE_PRICE_BYOK` | Non | — | price ID Stripe ; vide ⇒ plan non listé et non souscriptible | Non | — |
| `APP_BILLING_BYOK_PRICE` | Non | — | montant EUR d'affichage, défaut `29` | Non | — |
| `APP_QUOTA_BYOK_TOKENS` | Non | — | entier ≥ 0, défaut `0` | Non | — |

Notes :
- Le montant d'affichage est **cosmétique** : le débit réel est porté par le price Stripe.
- Un quota BYOK strictement positif resterait techniquement possible (configuration) mais
  contredirait la feature : le défaut `0` est la valeur de référence.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/billing/plans` | Oui | USER — enrichi d'une entrée `BYOK` |
| POST | `/api/billing/checkout` | Oui | USER — accepte `planCode: "BYOK"` (aucun code spécifique) |
| GET | `/api/usage` | Oui | USER — quota 0 pour un BYOK actif (affichage traité en SF-41-03) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `subscriptions` | SELECT / UPDATE | `plan_code = 'BYOK'` — **aucune migration** (`varchar(32)` libre) |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — `plan_code` est un `varchar(32)` sans contrainte d'énumération (migration 008).

### Composants Angular (si applicable)

Aucun dans cette subfeature (→ SF-41-03).

---

## Plan de test

### Tests unitaires

- [ ] `PlanCatalogTest` — le catalogue contient `BYOK` en `ProviderMode.BYOK` / `MONTHLY`.
- [ ] `EntitlementServiceTest` — BYOK `ACTIVE` ⇒ quota 0 **et** `isCustomerKeyBilled` vrai.
- [ ] `EntitlementServiceTest` — BYOK `PAST_DUE` ⇒ `isCustomerKeyBilled` vrai (sursis, même règle que les autres plans).
- [ ] `EntitlementServiceTest` — BYOK `CANCELED` ⇒ quota 0 **et** `isCustomerKeyBilled` **faux** (le cœur de la feature).
- [ ] `EntitlementServiceTest` — `SOLO` / `GOLD` / essai / expiré ⇒ `isCustomerKeyBilled` faux, quotas inchangés (non-régression).
- [ ] `QuotaServiceTest` — BYOK actif avec consommation déjà enregistrée ⇒ `assertWithinQuota` ne lève pas.
- [ ] `QuotaServiceTest` — abonnement expiré (quota 0, non BYOK) ⇒ `assertWithinQuota` lève `QuotaExceededException` (non-régression du fail-closed).
- [ ] `AtelierEntitlementServiceTest` — BYOK actif ⇒ `isEntitled` et `isIncludedInPlan` vrais ; BYOK annulé ⇒ faux.
- [ ] `AtelierAccessServiceTest` — utilisateur `USER` sur BYOK actif ⇒ `hasAccess()` vrai, `requireAccess()` ne lève pas.

### Tests d'intégration

- [ ] `GET /api/billing/plans` → l'entrée `BYOK` est présente quand un price est configuré, absente sinon.
- [ ] `POST /api/billing/checkout` avec `planCode: "BYOK"` → 200 et URL de paiement (fournisseur bouchonné).
- [ ] `POST /api/chat` sur un abonnement BYOK actif → **pas** de 402 `quota_exceeded` (le plan n'est pas un impayé).
- [ ] `POST /api/chat` sur un abonnement annulé → 402 `quota_exceeded` (non-régression).
- [ ] `GET /api/atelier/workspaces` sur un abonnement BYOK actif → 200 (droit d'Atelier ouvert).

### Isolation utilisateur

- [x] Applicable — test : deux utilisateurs, l'un sur BYOK actif, l'autre sur un abonnement annulé ;
  le second reste bloqué et ne bénéficie jamais du droit du premier. Toutes les résolutions partent
  du `userId` du contexte de sécurité, jamais d'un paramètre client.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|--------------|--------|---------------------|
| **Auth / Principal** | Non | Aucun changement du Principal ni du mode d'authentification. |
| **Contexte tenant** | Non | Aucun nouveau moyen de résoudre le tenant ; le `userId` reste celui du `SecurityContext`. |
| **Plans / limites** | **Oui** | Nouveau plan + nouveau gate. Composants qui lisent un plan ou une limite, **tous revus** : `PlanCatalog`, `PlanCode`, `SubscriptionService.changePlan` (générique, aucun cas particulier), `BillingController.plans()` (filtre par price), `CheckoutService` (générique), `WebhookService` (résolution du plan par price ID — vérifiée), `QuotaProperties.tokensForPlan`, `EntitlementService`, `QuotaService.assertWithinQuota` / `currentUsage` / `recordUsage`, `AtelierEntitlementService`, `AtelierAccessService`, `AtelierOptionService` (l'option devient « incluse » sur BYOK), `AtelierChatService` (budget de tour hosted/BYOK), `UsageReportService`. |
| **Navigation / routing** | Non | Aucune route ajoutée ou modifiée. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-40-01` (droit d'Atelier découplé du plan) — statut : **done** (sur `main`).
- `SF-03-*` (BYOK : clé chiffrée, mode) — statut : **done**.

### Questions ouvertes impactées

- [ ] `OQ-07` (pricing externalisé) — respectée : prix et price ID en configuration, jamais en dur.
- [ ] `OQ-08` (overage monétisé) — **non impactée** : le plan BYOK n'a pas de dépassement, il n'a pas de quota.

---

## Notes et décisions

- **Le zéro n'est pas une donnée suffisante.** Le quota seul ne dit pas *pourquoi* il vaut zéro.
  Plutôt que d'ajouter une valeur sentinelle (`-1` = illimité), qui se propagerait dans tous les
  calculs de pourcentage et de reste, on ajoute un **prédicat explicite** :
  `isCustomerKeyBilled(subscription)`. Le zéro reste zéro — il est *vrai* — mais la décision de
  bloquer cesse de s'appuyer dessus seule.
- **Le gate reste `QuotaService`.** C'est déjà le point de pré-vol appelé par les trois chemins
  servis. Y poser la dérogation BYOK évite d'ajouter un quatrième garde-fou que quelqu'un oubliera
  d'appeler.
- **La consommation reste enregistrée** (`recordUsage`) : elle sert l'observabilité et le rapport
  d'usage (F-16). Elle n'est simplement plus opposable, faute de quota. L'affichage « 1 200 000 / 0 »
  qui en résulte est une vraie verrue, corrigée à l'écran en SF-41-03.
- **Arbitrage commercial pris par défaut, à revoir par le PO** : le plan BYOK ouvre l'Atelier
  *inclus* (comme Gold), et non via l'option F-40 à 40 €. Un client qui apporte sa clé paie déjà ses
  jetons ; lui vendre en plus le droit d'Atelier reviendrait à facturer deux fois la même chose.
