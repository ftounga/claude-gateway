# Mini-spec — F-09 / SF-09-04 — Retirer le plan « Pass journée » du catalogue

## Identifiant

`F-09 / SF-09-04`

## Feature parente

`F-09` — Abonnements & billing

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-09-04-retrait-plan-daily`

---

## Objectif

> Retirer `DAILY` du catalogue des plans — sans casser la lecture d'un abonnement qui le porterait
> déjà.

---

## Déclencheur

Relevé du 2026-09-07 sur le compte Stripe **live** : le plan `DAILY` (500 k jetons, période
`DAILY`) n'a **jamais eu de price ID**. Il figure au catalogue depuis l'origine mais il est
**invendable** — `GET /billing/plans` le filtre déjà, parce qu'un plan sans price n'est pas listé.

Ce qui a masqué le problème est un homonyme : le produit Stripe « Claude Proxy — Pass Journée »
(4,99 €, paiement unique) est le **pack de recharge** `STRIPE_PRICE_TOPUP_DAY`, pas le plan. Deux
choses différentes, un seul nom.

Décision du product owner, 2026-09-07 : **retirer le plan**. Le pack de recharge, lui, reste — c'est
lui qui rend le service.

---

## Comportement attendu

### Cas nominal

1. `GET /billing/plans` ne propose plus `DAILY`. **Aucun changement visible** : il en était déjà
   absent, faute de price ID. Ce qui change est que l'absence devient une **décision** au lieu d'un
   effet de bord de configuration.
2. `POST /billing/change-plan` avec `DAILY` répond **404 `plan_unknown`** au lieu de créer un
   abonnement. C'est le seul changement de contrat.

### Un abonnement qui porte déjà `DAILY`

Aucun ne devrait exister — le plan n'a jamais été souscriptible faute de price. Le code doit
néanmoins le supporter, parce que `subscriptions.plan_code` est un `varchar(32)` **sans contrainte
d'énumération** et qu'un enregistrement de test ou d'API peut exister :

| Chemin | Comportement exigé |
|---|---|
| Lecture de l'abonnement (`GET /billing/subscription`) | Fonctionne, plan affiché tel quel |
| Allocation de jetons | **Inchangée** : `APP_QUOTA_DAILY_TOKENS` est conservé |
| Consommation, quota, alerte | Inchangés |
| Changement **vers** un autre plan | Fonctionne — on sort de `DAILY`, on n'y entre plus |
| Webhook tardif d'un pass journée | Traité comme aujourd'hui |

**La valeur `PlanCode.DAILY` est donc CONSERVÉE.** La retirer ferait échouer la désérialisation de
l'abonnement et transformerait un retrait commercial en incident.

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| `change-plan` vers `DAILY` | Refus, aucun appel au fournisseur | 404 `plan_unknown` |
| `STRIPE_PRICE_DAILY` encore renseigné en configuration | Sans effet : le plan n'est plus au catalogue | — |

---

## Critères d'acceptation

- [ ] `DAILY` n'est plus dans `PlanCatalog.plans()`.
- [ ] `PlanCode.DAILY` **existe toujours** — un abonnement qui le porte se lit sans erreur.
- [ ] `GET /billing/plans` ne le renvoie pas (déjà vrai, désormais figé par un test).
- [ ] `POST /billing/change-plan` vers `DAILY` répond 404, sans appel au fournisseur.
- [ ] L'allocation d'un abonnement `DAILY` existant est inchangée (500 k).
- [ ] Les quatre autres plans sont intacts : Solo, Pro, Gold, BYOK — codes, prix, quotas.
- [ ] Le **pack de recharge** `DAY` (200 k) est intact : c'est un `TopUpPack`, pas un plan.
- [ ] L'écran de facturation n'affiche aucune mention résiduelle de « Pass journée » comme plan.

---

## Périmètre

### Hors scope

- Le pack de recharge `DAY` — il reste, et son libellé n'est pas retouché ici.
- Toute migration de données : aucun abonnement n'est réécrit.
- Le renommage du produit Stripe « Claude Proxy — Pass Journée » (geste d'exploitation, pas de code).

---

## Technique

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `billing/PlanCatalog` | Retrait de l'entrée `DAILY` |
| `billing/PlanCode` | **Inchangé** — la valeur est conservée, avec le commentaire qui dit pourquoi |
| `application.yml` | Retrait de `prices.DAILY` et `display-prices.DAILY` ; **conservation** de `quota.plans.DAILY` |
| `billing.component.ts` (écran) | Retrait des libellés de plan « Pass journée » devenus morts |

### Migration Liquibase

- [x] **Non applicable** — aucune donnée n'est touchée.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| **Plans / limites** | **Oui** | Tous les appelants de `planCatalog.plans()` : `BillingController.plans` (liste), `SubscriptionService.changePlan` (cible), `CheckoutService` (souscription), `EntitlementService.isByokPlan` (mode fournisseur), `AtelierEntitlementService` (plans porteurs de l'option — `DAILY` en était **déjà** exclu par F-40). Chacun vérifié : aucun ne doit changer de comportement pour Solo, Pro, Gold ou BYOK. |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `PlanCatalog` ne contient plus `DAILY` et contient toujours les quatre autres.
- [ ] `PlanCode.DAILY` reste résoluble depuis une chaîne (compatibilité de lecture).
- [ ] `EntitlementService` rend 500 k pour un abonnement `DAILY` actif.
- [ ] `isByokPlan(DAILY)` reste `false` sans lever.

### Tests d'intégration

- [ ] `GET /billing/plans` ne contient pas `DAILY` et contient Solo, Pro, Gold.
- [ ] `POST /billing/change-plan` vers `DAILY` → 404, et le fournisseur n'est jamais appelé.
- [ ] Un abonné `DAILY` existant lit son abonnement et son usage sans erreur.

### Tests frontend

- [ ] La liste des plans s'affiche sans `DAILY` (non-régression de l'écran de facturation).

### Isolation workspace

- [x] Non applicable — aucun accès à une ressource par identifiant.

---

## Notes et décisions

**D1 — Retirer du catalogue, pas de l'énumération.** `subscriptions.plan_code` est un `varchar(32)`
sans contrainte : rien n'empêche une ligne `DAILY` d'exister. Supprimer la constante transformerait
un retrait commercial en `IllegalArgumentException` à la lecture — un incident pour l'utilisateur
concerné, alors que l'objectif est de ne plus **vendre** l'offre.

**D2 — Le quota `DAILY` est conservé en configuration.** Il ne coûte rien, et il est la seule chose
qui rende correcte l'allocation d'un abonnement historique. Le retirer ferait tomber cet abonné à
zéro jeton sans qu'aucune décision ne l'ait voulu.

**D3 — Le pack de recharge n'est pas touché.** L'homonymie est ce qui a masqué le problème pendant
des mois, mais le pack, lui, fonctionne et se vend. Le renommer est un geste d'exploitation dans
Stripe, à part.
