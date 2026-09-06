# Mini-spec — [F-40 / SF-02] Souscrire et résilier l'option Atelier (Stripe)

---

## Identifiant

`F-40 / SF-02`

## Feature parente

`F-40` — Option Atelier (droit découplé du plan)

## Statut

`done` — livrée le 2026-09-07

## Date de création

2026-09-07

## Branche Git

`feat/SF-40-02-souscrire-resilier-option`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre l'option Atelier **souscriptible et résiliable** par l'API, via un abonnement Stripe
**distinct** du plan (prix en configuration, défaut `APP_BILLING_ATELIER_OPTION_PRICE=40` €/mois),
et faire vivre son statut par le webhook **sans jamais toucher au plan**.

---

## Contexte

SF-40-01 a livré la règle : le droit d'Atelier se lit sur `subscriptions.atelier_option_status`.
Cette colonne n'est aujourd'hui **écrite par personne** — l'option existe mais ne s'achète pas.

Deux pièges structurent la subfeature :

1. **L'option est un second abonnement chez le fournisseur.** Son identifiant vit dans une colonne
   distincte (`atelier_option_stripe_subscription_id`, migration 054). Le `WebhookService` actuel
   résout l'abonnement visé par un événement en essayant, dans l'ordre, l'identifiant d'abonnement,
   **puis le client**, puis le `userId`. Un événement portant sur l'option tomberait donc sur le
   repli « par client » et **écraserait le statut du plan** : résilier l'option annulerait
   l'abonnement. C'est le défaut le plus grave possible ici, et il est traité en premier.
2. **Résilier ne doit pas voler des jours payés.** La résiliation est programmée **en fin de
   période** (`cancel_at_period_end`), pas immédiate. Le droit reste donc ouvert jusqu'au terme, et
   c'est le `customer.subscription.deleted` de ce terme qui le referme.

---

## Comportement attendu

### Cas nominal — souscription

1. `GET /billing/atelier-option` décrit l'état : prix d'affichage, droit effectif, droit **inclus au
   plan** (Gold), statut de l'option, date de résiliation programmée, et disponibilité du paiement.
2. `POST /billing/atelier-option/checkout` : le service vérifie que l'utilisateur a un plan porteur
   (`SOLO`/`PRO`) **actif**, que l'Atelier n'est pas déjà inclus à son offre, et que l'option n'est
   pas déjà en cours. Il résout le price ID de configuration et délègue au `BillingProvider`.
3. La session Checkout est créée en mode **abonnement**, réutilise le client Stripe existant s'il est
   connu, et porte les métadonnées `kind=atelier_option` + `userId` — **sur la session et sur
   l'abonnement créé**, pour que les événements ultérieurs restent identifiables.
4. Le client est redirigé vers Stripe ; au paiement, `checkout.session.completed` porte
   `kind=atelier_option` : le webhook écrit `atelier_option_status = ACTIVE` et
   `atelier_option_stripe_subscription_id`, **sans toucher** au plan, au statut du plan, ni à
   `stripe_subscription_id`.
5. Au tour suivant, `AtelierEntitlementService` ouvre le droit — sans qu'un jeton ait changé.

### Cas nominal — résiliation

1. `POST /billing/atelier-option/cancel` : le service exige une option en cours, puis demande au
   fournisseur une résiliation **en fin de période**.
2. La date de fin est enregistrée dans `atelier_option_cancel_at` ; le statut **reste** `ACTIVE` :
   l'utilisateur a payé le mois, il garde l'Atelier jusqu'au terme.
3. Au terme, `customer.subscription.deleted` sur l'identifiant de l'option écrit
   `atelier_option_status = CANCELED` et efface la date programmée. Le plan n'est pas touché.
4. Une souscription relancée avant le terme lève simplement la résiliation programmée côté
   fournisseur ; côté gateway, un nouveau checkout est refusé tant que l'option est en cours (409),
   et l'écran (SF-40-03) proposera « reprendre » plutôt que « souscrire ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP / `error` |
|-----------|---------------------|---------------------|
| Souscription alors que le plan est `GOLD` actif | Refus : l'Atelier est déjà inclus | 409 `atelier_option_included` |
| Souscription sans plan porteur actif (essai, `CANCELED`, `DAILY`) | Refus explicite : souscrire un plan Solo/Pro d'abord | 409 `no_active_subscription` |
| Souscription alors que l'option est déjà `ACTIVE`/`PAST_DUE` | Refus | 409 `atelier_option_already_active` |
| Aucun price ID d'option configuré | Fournisseur indisponible | 503 `billing_unavailable` |
| Fournisseur non configuré (clé absente) | Fournisseur indisponible | 503 `billing_unavailable` |
| Échec d'appel au fournisseur | Erreur de paiement neutre (aucun détail brut, aucune clé journalisée) | 502 `billing_error` |
| Résiliation sans option en cours | Refus | 409 `atelier_option_not_active` |
| Webhook d'abonnement portant sur l'option | Applique **l'option seule** ; plan, statut du plan et `stripe_subscription_id` inchangés | 200 |
| Webhook rejoué | Idempotent (écriture du même état) | 200 |
| Requête non authentifiée | Refus | 401 |

---

## Critères d'acceptation

- [x] `GET /billing/atelier-option` renvoie le prix d'affichage issu de la **configuration** (aucune
      valeur commerciale en dur), le droit effectif, `includedInPlan` pour un Gold, le statut de
      l'option et la date de résiliation programmée.
- [x] `POST /billing/atelier-option/checkout` d'un Solo actif renvoie une URL de paiement ; la
      commande passée au fournisseur porte le price ID de configuration et `kind=atelier_option`.
- [x] Les cinq refus de souscription (Gold, sans plan porteur, déjà active, price absent, fournisseur
      absent) renvoient chacun leur code et leur `error`, sans appel au fournisseur quand il est
      inutile.
- [x] `checkout.session.completed` d'option écrit `atelier_option_status = ACTIVE` et l'identifiant
      d'abonnement de l'option, **et laisse `plan_code`, `status` et `stripe_subscription_id`
      strictement inchangés** (test dédié).
- [x] `customer.subscription.updated` **et** `customer.subscription.deleted` portant l'identifiant de
      l'option n'écrivent que l'option — **jamais** le plan, y compris quand l'événement ne porte que
      le client Stripe et non l'identifiant d'abonnement (test du repli « par client »).
- [x] Symétriquement, un événement portant sur le **plan** ne touche pas l'option (test dédié).
- [x] `POST /billing/atelier-option/cancel` programme la résiliation en fin de période : la date est
      enregistrée, le statut reste `ACTIVE`, **le droit reste ouvert**.
- [x] Le prix d'affichage et le price ID sont **deux variables d'environnement** avec un défaut dans
      `application.yml` (défaut 40 €), sur le style des blocs existants.
- [x] Aucun quota n'est lu ni modifié par les trois endpoints (`QuotaService` non sollicité).
- [x] Isolation : les trois endpoints prennent le `userId` du **contexte de sécurité** ; jamais du corps.

---

## Périmètre

### Hors scope (explicite)

- L'**écran** de facturation → SF-40-03.
- La modification des **quotas** (l'option n'ajoute pas un jeton).
- Le **prorata** de résiliation : c'est le fournisseur qui l'applique, la gateway n'en calcule rien.
- La **reprise** d'une résiliation programmée (« annuler l'annulation ») : elle se fait côté
  fournisseur ; la gateway ne l'expose pas en V1.
- Le partage de l'option entre utilisateurs (→ F-17, V3).
- La facturation de l'option **à l'usage** : c'est un forfait mensuel, point.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `atelier_option_status` | `NULL` | Aucune option tant qu'aucun paiement n'est finalisé. Passe à `ACTIVE` sur `checkout.session.completed`. |
| `atelier_option_stripe_subscription_id` | `NULL` | Peuplé par le même événement, depuis `session.subscription`. |
| `atelier_option_cancel_at` | `NULL` | Peuplé à la demande de résiliation ; effacé à la fermeture effective. |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `app.billing.stripe.atelier-option-price-id` | Non | — | Price ID fournisseur ; **vide ⇒ option non souscriptible (503)** | — | — |
| `app.billing.stripe.atelier-option-display-price` | Non | — | Montant d'affichage EUR, chaîne (ex. `"40"`) ; **cosmétique** — le débit réel est porté par le price | — | — |
| `atelier_option_cancel_at` | Non | — | Horodatage avec fuseau | Non | — |

Notes :
- Le montant d'affichage est **une chaîne**, comme `display-prices` : il est rendu tel quel et n'entre
  dans aucun calcul. Aucune arithmétique commerciale ne vit dans la gateway.
- Aucun champ n'est accepté du client sur ces trois endpoints (pas de corps de requête) : il n'y a
  donc rien à valider côté entrée, et rien à usurper.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/billing/atelier-option` | Oui | USER |
| POST | `/api/billing/atelier-option/checkout` | Oui | USER |
| POST | `/api/billing/atelier-option/cancel` | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `subscriptions` | `ALTER TABLE` (1 colonne) + `SELECT` / `UPDATE` | Seules les trois colonnes d'option sont écrites |

### Migration Liquibase

- [x] Oui — `055-subscriptions-atelier-option-cancel-at.xml` (1 `addColumn` nullable, `rollback` par `dropColumn`)
- [ ] Non applicable

### Composants Angular (si applicable)

Aucun.

### Classes touchées

| Classe | Nature |
|--------|--------|
| `billing.AtelierOptionService` | **Nouveau** — souscription, résiliation, description |
| `billing.dto.AtelierOptionResponse` | **Nouveau** |
| `billing.AtelierOptionNotApplicableException` / `AtelierOptionAlreadyActiveException` / `AtelierOptionNotActiveException` | **Nouveaux** |
| `billing.provider.AtelierOptionCheckoutCommand` | **Nouveau** |
| `billing.provider.BillingProvider` (+ `StripeBillingProvider`) | 2 méthodes : checkout d'option, résiliation programmée |
| `billing.provider.BillingEventType` | 1 valeur : `ATELIER_OPTION_COMPLETED` |
| `billing.WebhookService` | Routage des événements d'option **avant** la résolution générique |
| `billing.SubscriptionRepository` | `findByAtelierOptionStripeSubscriptionId` |
| `billing.BillingProperties.Stripe` | 2 composants explicites |
| `billing.BillingController` | 3 endpoints |
| `shared.error.GlobalExceptionHandler` | 3 mappings d'erreur |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | Aucun changement d'authentification. Les trois endpoints vivent sous `/billing`, déjà authentifié, et lisent `CurrentUser` comme les six endpoints voisins. |
| **Contexte tenant** | **Oui** | Trois nouveaux endpoints qui écrivent en base. Le `userId` vient **exclusivement** de `CurrentUser` (JWT) ; aucun des trois n'accepte de corps de requête, il n'y a donc aucun identifiant client à usurper. Composants revus : `BillingController` (les trois méthodes), `AtelierOptionService` (toutes les opérations prennent `userId` en premier paramètre et passent par `SubscriptionService.getOrCreateForUser`, filtré `user_id` unique), `WebhookService` (n'est **pas** authentifié par JWT : il résout l'utilisateur par les identifiants **fournisseur**, et la nouvelle résolution par `atelier_option_stripe_subscription_id` est **plus étroite** que les existantes — un identifiant, un abonnement, par index unique). Test d'isolation à deux utilisateurs sur les trois endpoints. |
| **Plans / limites** | **Oui** | Nouveau gate commercial. Composants revus : <br>• `AtelierEntitlementService` — **non modifié** : la règle de SF-40-01 est déjà la bonne, cette SF ne fait que l'alimenter. Un test le fige (le droit s'ouvre après le webhook, sans changement de code de la règle).<br>• `EntitlementService` / `QuotaService` / `QuotaProperties` — **non sollicités** : un test vérifie que le quota d'un Solo est identique avant et après souscription de l'option.<br>• `SubscriptionService.changePlan` — un changement de plan **ne touche pas** l'option (test) ; un Solo optionnaire qui passe Pro garde son option, un Solo qui passe Gold la garde aussi (elle devient redondante, l'écran le dira en SF-40-03).<br>• `WebhookService.applyCheckoutCompleted` / `applySubscriptionUpdate` / `applySubscriptionDeleted` — les trois sont revus : chacun route désormais l'option avant sa résolution générique. |
| Navigation / routing | Non | Aucune route frontend (SF-40-03). Les trois URL sont sous `/billing/**`, déjà couvert par la configuration de sécurité existante — aucun `SecurityFilterChain` modifié. |

---

## Plan de test

### Tests unitaires

`AtelierOptionServiceTest` (nouveau) :

- [x] Solo actif ⇒ checkout créé, commande portant le price ID de configuration et le client Stripe existant
- [x] Pro actif ⇒ checkout créé
- [x] Gold actif ⇒ `atelier_option_included`, **aucun appel fournisseur**
- [x] Essai / plan `CANCELED` / `DAILY` ⇒ `no_active_subscription`, aucun appel fournisseur
- [x] Option déjà `ACTIVE` ou `PAST_DUE` ⇒ `atelier_option_already_active`, aucun appel fournisseur
- [x] Price ID absent ⇒ `billing_unavailable`, aucun appel fournisseur
- [x] Résiliation sans option ⇒ `atelier_option_not_active`, aucun appel fournisseur
- [x] Résiliation avec option ⇒ appel de résiliation programmée, date enregistrée, statut **toujours** `ACTIVE`
- [x] Description : prix issu de la configuration, `includedInPlan` vrai en Gold, faux en Solo

`WebhookServiceTest` (existant, étendu) :

- [x] `ATELIER_OPTION_COMPLETED` ⇒ option `ACTIVE` + identifiant enregistré ; `plan_code`, `status`
      et `stripe_subscription_id` **strictement inchangés**
- [x] `SUBSCRIPTION_DELETED` sur l'identifiant de l'option ⇒ option `CANCELED`, **plan intact**
- [x] `SUBSCRIPTION_UPDATED` sur l'identifiant de l'option ⇒ statut de l'option seul
- [x] `SUBSCRIPTION_UPDATED` ne portant **que le client Stripe** d'un utilisateur optionnaire ⇒
      applique au **plan** (comportement d'avant), pas à l'option
- [x] Événement portant sur le **plan** ⇒ option intacte
- [x] Les tests existants passent sans modification de leurs attentes

`StripeBillingProviderTest` (existant, étendu) :

- [x] Checkout d'option sans clé ⇒ `BillingProviderUnavailableException`
- [x] Checkout d'option sans price ID ⇒ `BillingProviderUnavailableException`
- [x] Résiliation programmée sans clé / sans identifiant ⇒ `BillingProviderUnavailableException`
- [x] `checkout.session.completed` portant `kind=atelier_option` ⇒ `ATELIER_OPTION_COMPLETED`

### Tests d'intégration

`AtelierOptionBillingApiIntegrationTest` (nouveau) :

- [x] `GET /api/billing/atelier-option` ⇒ 200, prix `"40"` (défaut de configuration), `entitled` faux
- [x] Gold ⇒ `includedInPlan` vrai et `entitled` vrai
- [x] `POST .../checkout` fournisseur dormant ⇒ 503 `billing_unavailable`
- [x] `POST .../checkout` en Gold ⇒ 409 `atelier_option_included`
- [x] `POST .../checkout` en essai ⇒ 409 `no_active_subscription`
- [x] `POST .../cancel` sans option ⇒ 409 `atelier_option_not_active`
- [x] Les trois endpoints sans jeton ⇒ 401
- [x] Le quota renvoyé par `GET /api/usage` est identique avant et après pose de l'option
- [x] **Bout en bout du droit** : option posée ⇒ `GET /api/workspaces` passe de 403 à 200 sans
      qu'aucun code de la règle n'ait changé

### Isolation utilisateur

- [x] Applicable — test : Alice optionnaire et Bob non-optionnaire interrogent les trois endpoints
      dans la même exécution ; chacun ne voit que son propre état, et la résiliation d'Alice ne
      change rien chez Bob. Le webhook, non authentifié, est vérifié séparément : un événement portant
      l'identifiant d'option d'Alice ne modifie que la ligne d'Alice.

---

## Dépendances

### Subfeatures bloquantes

- `SF-40-01` — statut : **done** (PR #264)

### Questions ouvertes impactées

- [x] Aucune question de `docs/OPEN_QUESTIONS.md` n'est tranchée ni contournée.

---

## Notes et décisions

- **D6 — L'option est un abonnement Stripe distinct, pas une seconde ligne du même abonnement.**
  Ajouter un item au `Subscription` existant aurait mélangé deux engagements dans un objet que
  `changeSubscriptionPlan` remplace déjà par index (`getItems().getData().get(0)`) : un changement de
  plan aurait alors écrasé l'option, ou l'inverse. Deux abonnements, deux cycles de vie, deux
  identifiants — et un changement de plan qui ne peut pas emporter l'option par accident.
- **D7 — La résiliation est programmée en fin de période, jamais immédiate.** L'utilisateur a payé le
  mois ; le lui reprendre à la seconde du clic serait un vol de jours. Conséquence assumée : il faut
  une colonne pour dire « résiliation programmée », faute de quoi l'écran ne pourrait rien montrer
  après le clic. **Arbitrage commercial pris par défaut, à revoir par le product owner.**
- **D8 — Le routage du webhook passe par l'identifiant, pas par la métadonnée.** Les métadonnées
  sont posées (et servent au `checkout.session.completed`, dont la session ne porte pas encore
  d'identifiant connu de nous), mais les événements d'abonnement sont routés par
  `atelier_option_stripe_subscription_id` : un index unique est une preuve, une métadonnée est une
  convention. Le repli « par client » du `WebhookService` reste réservé au plan.
- **D9 — Prix d'affichage et price ID sont deux composants explicites du bloc `stripe`**, et non deux
  entrées de plus dans les maps `prices`/`display-prices` clés par `PlanCode`. L'option n'est pas un
  plan ; l'inscrire dans une map de plans aurait été une convention à retenir, exactement le genre
  d'implicite que `CODING_RULES` §2 demande d'éviter.
