# Mini-spec — F-133 / SF-133-06 — Les deux alertes

## Identifiant
`F-133 / SF-133-06`

## Feature parente
`F-133` — Le coût réel Anthropic : par message, par client, par semaine

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-133-06-les-deux-alertes`

---

## Objectif

Prévenir l'administrateur **quand on approche** du budget de la semaine, puis **quand on l'a
dépassé** — par client et au total.

---

## Pourquoi ne pas réutiliser l'alerte de quota (F-42)

`QuotaAlertService` existe, mais il répond à une autre question : il prévient **l'utilisateur** que
son quota **commercial mensuel** s'épuise, et lui propose une **recharge Stripe**. Ici, il s'agit de
prévenir **l'administrateur** d'une dépense **hebdomadaire** qu'il a lui-même budgétée, et il n'y a
rien à racheter.

Les greffer l'un sur l'autre mêlerait deux destinataires, deux périodes et deux intentions dans un
seul objet. Le service d'alerte de coût est donc distinct — et il ne touche pas à F-42, dont les
tests restent verts sans modification.

---

## Comportement attendu

### Cas nominal

1. Pour la **semaine en cours**, on lit la dépense réelle (SF-133-03) et le budget (SF-133-04).
2. Pour chaque client budgété **et** pour le total, on calcule la part consommée.
3. Deux seuils : **approche** (par défaut 80 %) et **dépassement** (100 %).
   Le **total** se compare à la **somme des budgets applicables**, et non au budget par défaut :
   celui-ci vaut **par client** (SF-133-04), en faire aussi un plafond global le ferait dire deux
   choses à la fois.
4. L'administrateur reçoit la liste des alertes en cours, chacune disant : le client, la dépense, le
   budget, la part, et lequel des deux seuils est franchi.

### Ce que l'alerte n'est pas

- Elle **ne bloque rien** — cf. SF-133-04.
- Elle **n'envoie ni courriel ni notification** : elle se lit quand on regarde. Un envoi
  automatique, c'est une autre décision (destinataire, fréquence, désabonnement) et une autre
  subfeature.
- Elle ne « se marque pas comme vue » : elle est **calculée à la lecture**, donc toujours juste. Une
  alerte écartée puis rouverte demanderait un état à maintenir, et un état de plus se désynchronise.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|---|---|---|
| Appelant non administrateur | accès refusé | 403 |
| Aucun budget défini | **aucune alerte** — on ne peut pas dépasser un budget qui n'existe pas | 200 |
| Budget à zéro et dépense nulle | aucune alerte : rien n'a été dépensé | 200 |
| Budget à zéro et dépense positive | alerte de **dépassement** | 200 |
| Seuil configuré absurde | retombe sur 80 % | — |

---

## Critères d'acceptation

- [ ] `GET /api/admin/cost/alerts` rend les alertes de la **semaine en cours**, et **403** pour un non-administrateur.
- [ ] Une dépense à **85 %** d'un budget de 100 € déclenche une alerte **d'approche**, pas de dépassement.
- [ ] Une dépense à **120 %** déclenche une alerte **de dépassement**, et **une seule** — jamais les deux à la fois.
- [ ] Une dépense à **50 %** ne déclenche **rien**.
- [ ] Un client **sans budget** ne produit aucune alerte, même très dépensier.
- [ ] Budget **zéro** + dépense positive ⇒ dépassement ; budget zéro + dépense nulle ⇒ rien.
- [ ] Le **total** se compare à la **somme des budgets applicables** — celui de chaque client qui en a un, propre ou par défaut. Le budget par défaut est un budget **par client** (SF-133-04) : en faire aussi un plafond global le ferait dire deux choses à la fois.
- [ ] Les alertes d'un compte sont **invisibles** de l'autre.
- [ ] F-42 (alerte de quota) est **inchangé** : ses tests passent sans modification.

---

## Périmètre

### Hors scope (explicite)
- L'**écran** (SF-133-07) : ici, seule l'API.
- Tout envoi de message (courriel, notification, webhook).
- Toute mémoire d'alerte « vue » ou « écartée ».
- Les alertes mensuelles.

---

## Technique

### Endpoints

| Méthode | URL | Rôle |
|---|---|---|
| GET | `/api/admin/cost/alerts` | ADMIN |

### Tables impactées
Aucune. Tout est calculé à la lecture.

### Migration Liquibase
- [ ] Non applicable

### Classes touchées

| Classe | Changement |
|---|---|
| **`CostAlert`** *(nouveau)* | une alerte : portée, client, dépense, budget, part, seuil franchi |
| **`CostAlertService`** *(nouveau)* | le calcul, pour la semaine en cours |
| **`CostAlertProperties`** *(nouveau)* | seuil d'approche (défaut 0,8) |
| `CostBudgetController` | la route de lecture |

### Composants Angular
Aucun.

---

## Plan de test

### Tests unitaires
- [ ] 50 % ⇒ rien ; 85 % ⇒ approche ; 120 % ⇒ dépassement ; jamais les deux.
- [ ] Budget zéro + dépense positive ⇒ dépassement ; budget zéro + dépense nulle ⇒ rien.
- [ ] Client sans budget ⇒ aucune alerte.
- [ ] Seuil absurde (0, négatif, > 1) ⇒ 80 %.

### Tests d'intégration
- [ ] La route rend les alertes attendues pour l'administrateur, **403** sinon.
- [ ] Deux comptes : chacun ne voit que les siennes.
- [ ] **Non-régression F-42** : les tests d'alerte de quota passent sans modification.

### Isolation utilisateur
- [x] Applicable — testée.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | route neuve derrière `AdminService.assertAdmin()` |
| **Contexte tenant** | **oui** | `CostAlertService` lit par `HostCostService` et `CostBudgetService`, tous deux filtrés `user_id` |
| **Plans / limites** | **oui** | `QuotaAlertService`, `QuotaService`, `EntitlementService` — **aucun touché** ; l'alerte de coût est un service distinct, et les tests F-42 le prouvent |
| Navigation / routing | non | aucun écran |

---

## Estimation

**0,5 jour.** Un calcul, une route, leurs tests.
