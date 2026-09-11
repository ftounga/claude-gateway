# Mini-spec — F-67 / SF-67-01 — Le montant des recharges, en configuration

## Identifiant

`F-67 / SF-67-01`

## Feature parente

`F-67` — Le prix avant le clic

## Statut

`ready`

## Date de création

2026-09-11

## Branche Git

`feat/SF-67-01-montant-recharges-config`

---

## Objectif

Exposer le montant d'affichage de chaque pack de recharge, servi par la **configuration** — au même
patron que `display-prices` des plans — pour qu'un écran puisse afficher un prix sans jamais
l'inventer.

---

## Comportement attendu

### Cas nominal

1. `app.billing.stripe.topup-display-prices` associe un **code de pack** à un **montant affiché**
   (chaîne, EUR), alimenté par environnement : `STRIPE_DISPLAY_PRICE_TOPUP_DAY` (défaut `4,99`) et
   `STRIPE_DISPLAY_PRICE_TOPUP_STANDARD` (**défaut vide**).
2. `GET /api/billing/topups` renvoie chaque pack avec un champ **`priceEur`** : le montant configuré,
   ou **`null`** si la clé est absente ou vide.
3. `GET /api/usage/alert` (F-42) renvoie le pack recommandé avec le **même champ** `priceEur`, résolu
   par la même configuration : la recharge en un clic ne peut pas afficher un autre prix que l'écran
   de facturation.
4. Le **price ID Stripe reste interne** : il n'est exposé par aucune des deux réponses, exactement
   comme pour les plans.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Aucun montant configuré pour le pack (clé absente) | `priceEur` = `null`. Le pack reste listé et vendable | 200 |
| Montant configuré à la chaîne vide ou blanche | `priceEur` = `null` — jamais `""`, qu'un écran afficherait comme « €» | 200 |
| Bloc `topup-display-prices` entièrement absent de la configuration | Carte vide, `priceEur` = `null` pour tous les packs, aucun démarrage en échec | 200 |
| Montant configuré pour un code inconnu du catalogue | Ignoré en silence — la configuration ne crée pas de pack | 200 |
| Appel sans JWT | Inchangé : 401 | 401 |

---

## Critères d'acceptation

- [ ] `BillingProperties.Stripe` porte `topupDisplayPrices` (carte code → montant) et
      `topupDisplayPrice(String packCode)` qui renvoie `null` pour une valeur absente, vide ou blanche.
- [ ] Une configuration sans bloc `topup-display-prices` donne une carte **vide**, pas un `null`
      (mêmes garde-fous que `displayPrices` / `yearlyDisplayPrices`).
- [ ] `GET /api/billing/topups` renvoie `priceEur` pour chaque pack : `"4,99"` pour `DAY` avec les
      défauts livrés, `null` pour `STANDARD`.
- [ ] `GET /api/usage/alert` renvoie `topUp.priceEur` résolu par la même configuration lorsqu'une
      alerte est levée, et `topUp` reste `null` quand aucune alerte ne l'est.
- [ ] **Aucun montant n'est écrit en dur dans le code Java** : le défaut `4,99` vit dans
      `application.yml`, et le pack `STANDARD` n'a **aucun** défaut.
- [ ] Aucun price ID Stripe n'apparaît dans une réponse d'API.
- [ ] Isolation `user_id` inchangée : les deux endpoints restent authentifiés et ne lisent que la
      ligne de l'utilisateur courant (aucune requête de données n'est ajoutée).
- [ ] Aucun appel à Stripe n'est ajouté ; aucun price ID n'est créé ni modifié.

---

## Périmètre

### Hors scope (explicite)

- **Décider le montant du pack `STANDARD`** — `À CONFIRMER PAR LE PO` (`TARIFS.md` §7, OQ-16).
- Créer ou modifier un price / produit Stripe.
- Filtrer le catalogue sur la présence d'un price ID (voir cadrage — risque de retirer une recharge
  réellement vendue, la configuration de production n'étant pas lisible depuis le dépôt).
- Tout affichage : c'est SF-67-02.
- Le prix des plans, de l'option Atelier, du supplément par poste : déjà exposés.

---

## Valeurs initiales

| Clé | Valeur livrée | Règle |
|---|---|---|
| `app.billing.stripe.topup-display-prices.DAY` | `4,99` | Valeur relevée en note de livraison SF-09-04 et reprise par `TARIFS.md` §2, **à reconfirmer au tableau de bord Stripe** |
| `app.billing.stripe.topup-display-prices.STANDARD` | **vide** | Aucune source dans le dépôt — un montant inventé serait pire que son absence |

---

## Contraintes de validation

| Champ | Obligatoire | Format | Normalisation |
|---|---|---|---|
| `topup-display-prices.<CODE>` | Non | Chaîne libre (le montant est **affiché tel quel**, jamais calculé ni comparé) | Valeur vide ou blanche ⇒ `null` |
| `TopUpPackResponse.priceEur` | Non | Chaîne ou `null` | — |

Notes :
- Le montant est une **chaîne**, comme `display-prices` des plans : il est cosmétique, jamais un
  calcul. Le débit réel appartient au price ID Stripe (OQ-07).
- Le séparateur décimal est celui qu'on veut voir à l'écran (`4,99`), puisque la valeur n'est pas
  formatée par l'application.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---|---|---|---|
| GET | `/api/billing/topups` | Oui (JWT) | **Ajout** du champ `priceEur` à chaque pack |
| GET | `/api/usage/alert` | Oui (JWT) | **Ajout** du champ `priceEur` à `topUp` |

Ajout de champ uniquement : aucun champ retiré ni renommé, contrat rétro-compatible.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable — aucun changement de schéma (configuration et DTO uniquement).

### Fichiers backend

- `billing/BillingProperties.java` — carte `topupDisplayPrices` + accesseur.
- `billing/dto/TopUpPackResponse.java` — champ `priceEur`, fabrique `of(pack, priceEur)`.
- `billing/dto/TopUpPacksResponse.java` — projection avec le montant.
- `billing/BillingController.java` — résolution du montant à la lecture du catalogue.
- `quota/dto/QuotaAlertResponse.java`, `quota/UsageController.java` — même montant sur le chemin de
  l'alerte.
- `src/main/resources/application.yml`, `src/test/resources/application-test.yml`.

---

## Plan de test

### Tests unitaires

- [ ] `BillingProperties` — `topupDisplayPrice` renvoie le montant configuré.
- [ ] `BillingProperties` — `null` si le code est absent, si la valeur est vide, si la valeur est
      blanche, et si la carte est `null` (configuration absente).
- [ ] `TopUpPackResponse.of` — reporte code, libellé, jetons et montant ; `null` reste `null`.
- [ ] `QuotaAlertResponse.from` — `topUp` reste `null` sans pack ; porte `priceEur` avec pack.

### Tests d'intégration

- [ ] `GET /api/billing/topups` → 200, `DAY.priceEur = "4,99"` (défaut de test), `STANDARD.priceEur`
      absent/`null`.
- [ ] `GET /api/billing/topups` → aucun champ de réponse ne contient de price ID Stripe.
- [ ] `GET /api/billing/topups` sans JWT → 401 (non-régression).
- [ ] `GET /api/usage/alert` → 200 avec `topUp.priceEur` quand l'alerte est levée.

### Isolation utilisateur

- [x] Applicable et **inchangée** : les deux endpoints lisent le `user_id` du JWT ; SF-67-01 n'ajoute
      aucune lecture de données. Le catalogue de packs et sa configuration sont **globaux, non
      nominatifs** — ils ne portent aucune donnée d'utilisateur. Non-régression couverte par le test
      401 ci-dessus et par les tests d'alerte existants.

---

## Dépendances

### Subfeatures bloquantes

Aucune. (F-21 / SF-21-02 — catalogue de packs — `done` ; F-42 — alerte — `done`.)

### Questions ouvertes impactées

- [ ] **OQ-16 point 1** — prix du pack `STANDARD` : **non tranchée**, et SF-67-01 ne la tranche pas.
      Elle livre la clé qui l'accueillera, vide.
- [ ] **OQ-16 point 2** — prix du pack `DAY` (4,99 € à reconfirmer) : non tranchée ; la valeur entre
      en configuration **avec** sa mention « à reconfirmer » dans `TARIFS.md`.
- [ ] **OQ-07** — prix externalisés, jamais en dur : respectée, c'est le patron suivi.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | Non | Aucun changement d'authentification ; les deux endpoints restent `CurrentUser`. |
| Contexte tenant | Non | Aucun accès de données ajouté. |
| **Plans / limites** | **Oui — affichage seulement** | Composants lisant la configuration de facturation : `BillingController.plans()` (inchangé), `CheckoutService`, `TopUpService` (résolution du **price ID**, inchangée), `AtelierOptionService` (inchangé), `SeatQuotaService` (inchangé), `QuotaAlertService` (choix du pack, inchangé). Le nouvel accesseur est **additif** : aucun gate, aucun quota, aucun montant débité n'en dépend. |
| Navigation / routing | Non | Aucune route. |

---

## Notes et décisions

1. **Montant en chaîne, pas en nombre.** Identique à `display-prices` des plans : le montant est
   affiché, jamais calculé. Le transformer en `BigDecimal` laisserait croire que l'application
   connaît le prix débité — elle ne le connaît pas (OQ-07).
2. **Vide ⇒ `null`, jamais `""`.** Un montant vide traversant l'API se serait affiché « €» à côté
   d'un bouton d'achat.
3. **Le même montant sur les deux chemins.** L'alerte des 80 % propose `STANDARD` en un clic : lui
   servir une autre source de prix que l'écran de facturation aurait recréé, en plus petit, le
   défaut que F-67 corrige.
