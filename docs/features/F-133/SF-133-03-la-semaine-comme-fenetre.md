# Mini-spec — F-133 / SF-133-03 — La semaine comme fenêtre

## Identifiant
`F-133 / SF-133-03`

## Feature parente
`F-133` — Le coût réel Anthropic : par message, par client, par semaine

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-133-03-la-semaine-comme-fenetre`

---

## Objectif

Savoir lire la dépense réelle **par semaine** et **par client**, grain que rien ne sait produire
aujourd'hui — tout est mensuel.

---

## Pourquoi une classe dédiée plutôt que d'étendre `UsageWindow`

La mini-spec de cadrage disait « `UsageWindow` apprend le grain semaine ». **Je dévie, et voici
pourquoi.** `UsageWindow` explique dans sa propre javadoc que le mois est son grain *parce que les
compteurs de période (F-10) ont le mois pour grain* — offrir un intervalle plus fin « laisserait
croire à une précision qui n'existe pas ». C'est vrai pour les compteurs, et c'est faux pour le
journal par tour, qui est horodaté à la seconde.

Étendre `UsageWindow` mêlerait deux contraintes opposées dans un même objet et ferait porter à F-16
et F-61 le risque d'une régression pour un besoin qui ne les concerne pas. `CostWindow` est donc une
classe **distincte**, qui s'appuie sur ce que le journal sait vraiment faire.

---

## Comportement attendu

### Cas nominal

1. On demande la dépense d'une **semaine** (ou d'un mois) pour l'utilisateur courant.
2. La fenêtre est normalisée : semaine **ISO**, du **lundi 00:00 UTC** au lundi suivant exclu.
3. Une requête unique agrège, par poste, le **coût réel** (`provider_cost_usd`) et les volumes.
4. Le résultat donne le total et la liste des clients, « hors client » en dernier.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Fenêtre inversée (`from` après `to`) | refus explicite, comme pour `UsageWindow` |
| Fenêtre au-delà du plafond (53 semaines) | refus explicite |
| Tours sans coût (antérieurs à F-133) | comptés en **volume**, ignorés en **coût** — jamais remplacés par une estimation |
| Aucun tour sur la période | total à zéro, liste vide, jamais d'erreur |

---

## Critères d'acceptation

- [ ] `CostWindow.week(date)` rend le **lundi** de la semaine ISO de `date`, à 00:00 UTC, et le lundi suivant comme borne exclue — y compris pour un dimanche.
- [ ] `CostWindow.month(date)` rend le premier du mois et le premier du mois suivant.
- [ ] Une semaine à cheval sur deux mois agrège bien les tours des deux mois.
- [ ] La dépense d'un poste sur une semaine est la **somme des `provider_cost_usd`** de ses tours, et non une estimation par tokens.
- [ ] Les tours **sans coût** (avant F-133) comptent dans les volumes et pour **zéro** en coût.
- [ ] La somme des clients **égale** le total de la fenêtre, seau « hors client » compris.
- [ ] Un utilisateur ne voit jamais les tours d'un autre : filtre `user_id` sur la requête, testé avec deux comptes et deux postes homonymes.
- [ ] Une fenêtre inversée ou trop longue est refusée avec un message explicite.

---

## Périmètre

### Hors scope (explicite)
- Aucun endpoint, aucun écran : SF-133-03 est une **fondation** pour les budgets (04), les alertes (06) et l'écran (07).
- F-16 et F-61 ne sont pas touchés : ils gardent `UsageWindow` et leur estimation mensuelle.
- Aucune conversion en euros : la fondation travaille en dollars.

---

## Technique

### Endpoints
Aucun.

### Tables impactées
Aucune. Nouvelle **lecture** sur `usage_turns`, couverte par l'index `(user_id, occurred_at)` existant.

### Migration Liquibase
- [ ] Non applicable

### Classes touchées

| Classe | Changement |
|---|---|
| **`CostWindow`** *(nouveau)* | semaine ISO ou mois, bornes UTC, validation |
| **`HostCostAggregate`** *(nouveau)* | projection : poste, coût, tokens |
| **`HostCostService`** *(nouveau)* | la dépense par poste sur une fenêtre |
| `UsageTurnRepository` | une requête d'agrégation du **coût** par poste |

### Composants Angular
Aucun.

---

## Plan de test

### Tests unitaires
- [ ] `CostWindow` — un mercredi, un lundi, un dimanche rendent tous le **même** lundi.
- [ ] `CostWindow` — semaine à cheval sur deux mois ; semaine à cheval sur deux années.
- [ ] `CostWindow` — fenêtre inversée et fenêtre trop longue refusées.
- [ ] `CostWindow` — le mois se comporte comme avant.

### Tests d'intégration
- [ ] La dépense d'une semaine somme les coûts réels des tours de cette semaine, et **exclut** ceux de la semaine voisine.
- [ ] Un tour sans coût compte en volume et pour zéro en coût.
- [ ] La somme des clients égale le total.
- [ ] **Isolation** : deux comptes, deux postes homonymes — chacun ne voit que le sien.

### Isolation utilisateur
- [x] Applicable — testée explicitement.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Contexte tenant** | **oui** | `UsageTurnRepository` (nouvelle requête, filtre `user_id` obligatoire), `HostCostService` — le `userId` vient du contexte de sécurité, jamais d'un paramètre |
| Auth / Principal | non | aucun endpoint à ce stade |
| Plans / limites | non | aucun décompte touché |
| Navigation / routing | non | aucun écran |

---

## Estimation

**0,5 jour.** Une classe de fenêtre, une requête, un service de lecture.
