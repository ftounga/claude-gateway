# Mini-spec — F-133 / SF-133-04 — Le budget par client et par semaine

## Identifiant
`F-133 / SF-133-04`

## Feature parente
`F-133` — Le coût réel Anthropic : par message, par client, par semaine

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-133-04-budget-par-client`

---

## Objectif

Donner à l'administrateur un **budget hebdomadaire**, global et par client, à opposer à la dépense
réelle — pour piloter, jamais pour bloquer.

---

## Comportement attendu

### Cas nominal

1. L'administrateur fixe un budget hebdomadaire **par défaut** (tous clients) et, s'il le souhaite,
   un budget **propre à un client**.
2. Le budget d'un client est celui qui lui est propre, à défaut celui par défaut, à défaut aucun.
3. Pour une semaine donnée, on lit : la **dépense réelle** (SF-133-03), le **budget**, la **part
   consommée**.
4. Un budget modifié vaut **pour la semaine en cours et les suivantes** ; les semaines passées
   gardent le budget qui leur était opposé.

### Ce que le budget ne fait pas

**Il n'arrête rien.** Aucun tour n'est refusé, aucun quota n'est modifié. Le refus de service reste
l'affaire du quota commercial (F-10 / F-36), qui a déjà ses plafonds, ses exceptions et sa
facturation. Mélanger les deux ferait d'un outil de pilotage interne un mécanisme de refus.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|---|---|---|
| Appelant non administrateur | accès refusé | 403 |
| Montant négatif | refus explicite | 400 |
| Montant nul | **accepté** : « ce client ne doit rien coûter » est une consigne légitime | 200 |
| Client inconnu ou d'un autre compte | refus | 404 |
| Aucun budget défini | la dépense s'affiche **sans** part consommée, jamais avec une part inventée | 200 |

---

## Critères d'acceptation

- [ ] `PUT /api/admin/cost/budget` fixe le budget hebdomadaire **par défaut**, en euros.
- [ ] `PUT /api/admin/cost/budget/{hostId}` fixe le budget d'un **client**.
- [ ] `DELETE /api/admin/cost/budget/{hostId}` supprime le budget propre : le client retombe sur le défaut.
- [ ] `GET /api/admin/cost/budget` rend le défaut et la liste des budgets propres.
- [ ] Les quatre routes répondent **403** à un appelant qui n'est pas administrateur.
- [ ] Un budget propre l'emporte sur le défaut ; sans budget propre, le défaut s'applique ; sans défaut, il n'y a pas de budget.
- [ ] Un montant négatif est refusé ; **zéro est accepté**.
- [ ] Un budget d'un autre compte est invisible et impossible à modifier (isolation `user_id`).
- [ ] **Aucun tour n'est refusé** du fait d'un budget, même très dépassé : test de non-régression explicite.

---

## Périmètre

### Hors scope (explicite)
- Les **alertes** (SF-133-06) : ici on pose le budget, on ne prévient pas encore.
- L'**écran** (SF-133-07) : les routes existent, l'interface viendra.
- Tout blocage, toute modification du quota.
- Les budgets mensuels : la semaine est le grain demandé ; le mois se lit déjà par cumul.

---

## Valeurs initiales

| Champ | Valeur à la création | Règle |
|---|---|---|
| `amount_eur` | fourni | `NUMERIC(10,2)`, ≥ 0 |
| `host_id` | `null` pour le défaut | un budget par (utilisateur, client) |
| `updated_at` | horloge applicative | jamais fourni par le client |

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| `amountEur` | Oui | ≥ 0, ≤ 1 000 000 | arrondi à deux décimales |
| `hostId` | Non | poste de l'utilisateur | — |

---

## Technique

### Endpoints

| Méthode | URL | Rôle |
|---|---|---|
| GET | `/api/admin/cost/budget` | ADMIN |
| PUT | `/api/admin/cost/budget` | ADMIN |
| PUT | `/api/admin/cost/budget/{hostId}` | ADMIN |
| DELETE | `/api/admin/cost/budget/{hostId}` | ADMIN |

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `cost_budgets` | `CREATE` | `(user_id, host_id)` unique, `host_id` nullable pour le défaut |

### Migration Liquibase
- [x] Oui — `120-cost-budgets.xml`

### Classes touchées

| Classe | Changement |
|---|---|
| **`CostBudget`** *(nouveau)* | entité |
| **`CostBudgetRepository`** *(nouveau)* | lectures filtrées `user_id` |
| **`CostBudgetService`** *(nouveau)* | résolution défaut/propre, validation, garde admin |
| **`CostBudgetController`** *(nouveau)* | les quatre routes |
| DTO | requête et réponse **distincts** |

### Composants Angular
Aucun (SF-133-07).

---

## Plan de test

### Tests unitaires
- [ ] Résolution : budget propre > défaut > aucun.
- [ ] Montant négatif refusé, zéro accepté, arrondi à deux décimales.
- [ ] Un budget supprimé fait retomber le client sur le défaut.

### Tests d'intégration
- [ ] Les quatre routes en administrateur : 200 et effet attendu.
- [ ] Les quatre routes en utilisateur ordinaire : **403**.
- [ ] Un budget posé par un compte est **invisible** de l'autre.
- [ ] **Non-régression** : un client très au-delà de son budget peut toujours lancer un tour.

### Isolation utilisateur
- [x] Applicable — testée sur lecture **et** écriture.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | 4 routes neuves, toutes derrière `AdminService.assertAdmin()` — jamais une garde réécrite. Vérifier qu'aucune n'oublie la garde (test par route) |
| **Contexte tenant** | **oui** | `CostBudgetRepository`, `CostBudgetService` — toute lecture et toute écriture filtrent `user_id` |
| **Plans / limites** | **oui** | `QuotaService`, `EntitlementService`, `AtelierCostProperties` — **aucun n'est touché**, et le test de non-régression le prouve : un budget dépassé ne refuse rien |
| Navigation / routing | non | aucun écran à ce stade |

---

## Estimation

**1 jour.** Une table, une entité, un service, quatre routes, leurs tests.
