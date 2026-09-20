# Mini-spec — F-133 / SF-133-07 — L'écran du coût réel

## Identifiant
`F-133 / SF-133-07`

## Feature parente
`F-133` — Le coût réel Anthropic : par message, par client, par semaine

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-133-07-l-ecran-du-po`

---

## Objectif

Donner à l'administrateur **un seul endroit** où lire ce qu'il dépense : cette semaine, ce mois, par
client, avec les budgets et les alertes.

---

## Comportement attendu

### Cas nominal

1. L'administrateur ouvre `/admin`. Une section **Coût réel** s'ajoute au-dessus de la
   consommation (F-61) : « combien ça me coûte » précède « qui consomme ».
2. Un sélecteur **Cette semaine / Ce mois**.
3. Un total en euros, et sous lui la liste des clients : dépense, budget, part consommée.
4. Les **alertes** en cours sont affichées en tête, chacune disant son client et son niveau.
5. Chaque ligne permet de **fixer ou retirer** le budget du client ; un champ à part fixe le
   budget par défaut.

### Ce que l'écran ne fait pas

- Il ne remplace pas la section **Consommation** (F-61), qui répond à « qui consomme, en volumes ».
  Les deux coexistent, et la nouvelle le dit dans son intitulé : l'une compte des tokens, l'autre
  des euros.
- Il n'exporte pas, ne facture pas, ne bloque pas.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Appelant non administrateur | la section n'est pas atteignable : `/admin` est déjà gardé |
| API en échec | message d'erreur **dans la section**, le reste de la page reste utilisable |
| Aucun budget | la part consommée n'est pas affichée — jamais une part inventée |
| Aucune dépense | « Aucune dépense sur la période », pas un tableau vide sans explication |
| Budget refusé (négatif) | message clair sous le champ, valeur non appliquée |

---

## Critères d'acceptation

- [ ] Une section « Coût réel » apparaît dans `/admin`, **au-dessus** de la section Consommation.
- [ ] Le sélecteur bascule entre **cette semaine** et **ce mois**, et recharge les données.
- [ ] Le total et chaque ligne affichent un montant **en euros**, deux décimales, virgule française.
- [ ] Un client **sans budget** affiche sa dépense **sans** part consommée.
- [ ] Un client **avec budget** affiche sa part, et une barre de progression.
- [ ] Les alertes en cours sont listées avec leur niveau (**approche** / **dépassement**).
- [ ] Fixer un budget le rend effectif **sans recharger la page** ; le retirer fait retomber sur le défaut.
- [ ] Un budget négatif est refusé avec un message, et la valeur précédente reste.
- [ ] Une erreur d'API affiche un message **sans casser** le reste de l'écran d'administration.
- [ ] **Design system** : aucune couleur, police ou espacement hors `DESIGN_SYSTEM.md` ; aucun `window.alert/confirm/prompt` ; notifications par `MatSnackBar`.

---

## Périmètre

### Hors scope (explicite)
- L'export, la facturation, le blocage.
- La réconciliation avec la facture Anthropic (SF-133-05, optionnelle).
- Le détail par projet : le grain est le **client**.
- Les graphiques d'évolution : un tableau et des barres suffisent à piloter.

---

## Technique

### Endpoints

| Méthode | URL | Rôle | Rôle fonctionnel |
|---|---|---|---|
| GET | `/api/admin/cost/summary?period=week\|month` | ADMIN | dépense par client **et** budget **et** part, en une lecture |

Les routes de budget (SF-133-04) et d'alertes (SF-133-06) existent déjà.

**Pourquoi une route de synthèse** plutôt que trois appels combinés côté écran : la part consommée
est une **règle métier** (budget propre, sinon défaut, sinon aucun). La calculer dans le navigateur
en ferait une seconde définition, qui divergerait un jour de celle des alertes.

### Tables impactées
Aucune.

### Migration Liquibase
- [ ] Non applicable

### Classes touchées

| Classe | Changement |
|---|---|
| **`CostSummary`** *(nouveau)* | total, période, lignes par client avec budget et part |
| **`CostSummaryService`** *(nouveau)* | assemble dépense + budget ; **réutilise** la résolution de SF-133-04 |
| `CostBudgetController` | la route de synthèse |

### Composants Angular

- **`AdminCostComponent`** *(nouveau)* — la section, sous `admin/cost/`
- `AdminComponent` — l'insère au-dessus de `app-admin-usage`
- **`AdminCostService`** *(nouveau)* — les quatre appels

---

## Plan de test

### Tests unitaires (backend)
- [ ] La synthèse joint dépense et budget ; part calculée seulement quand il y a un budget.
- [ ] Un client sans dépense mais **avec** budget apparaît quand même (à 0 %).
- [ ] La période « mois » agrège bien le mois en cours.

### Tests d'intégration (backend)
- [ ] `GET /summary` en administrateur : 200 et forme attendue ; **403** sinon.
- [ ] Deux comptes : chacun ne voit que le sien.
- [ ] Période invalide ⇒ 400.

### Tests frontend
- [ ] La section affiche total, clients, montants en euros.
- [ ] Un client sans budget n'affiche **pas** de part.
- [ ] Le sélecteur recharge avec la bonne période.
- [ ] Fixer un budget appelle l'API et rafraîchit.
- [ ] Une erreur d'API affiche un message et ne casse rien.

### Isolation utilisateur
- [x] Applicable — testée côté API.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | route de synthèse derrière `assertAdmin()` ; la page `/admin` a déjà son garde de route — vérifier que la section n'apparaît pas ailleurs |
| **Contexte tenant** | **oui** | `CostSummaryService` lit par `HostCostService` et `CostBudgetService`, filtrés `user_id` |
| **Navigation / routing** | **oui** | aucune route nouvelle : la section s'ajoute **dans** `/admin`. Vérifier que le chargement différé de la page d'administration n'est pas cassé |
| Plans / limites | non | aucun décompte touché |

---

## Estimation

**1,5 jour.** Une route de synthèse, un composant, son service, leurs tests.
