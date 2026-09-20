# Mini-spec — F-133 / SF-133-14 — Le budget par défaut, expliqué

## Identifiant
`F-133 / SF-133-14`

## Feature parente
`F-133` — Le coût réel Anthropic

## Statut
`draft` — demandé par le PO le 2026-09-20

## Branche Git
`feat/SF-133-12-alerte-dans-la-forge` *(livrée avec SF-133-12 et SF-133-13 : même écran, même PR)*

---

## Objectif

Que le bloc « Budget hebdomadaire par défaut » dise **ce qu'il fait**, sans qu'il faille lire le
code pour le savoir.

---

## Le défaut

Le PO : *« Le budget hebdomadaire par défaut. On est d'accord, c'est celui pour tous les clients,
mélangé sur toute la semaine ? Je ne suis pas sûr de comprendre ce bloc. »*

**Non, et c'est précisément le problème** : `CostBudgetService.budgetOf` applique le défaut
**à chaque client** qui n'a pas de budget propre. Ce n'est pas une enveloppe commune : avec cinq
clients et un défaut à 50 €, le plafond total de la semaine est de **250 €**, pas de 50 €.

Le libellé laissait croire l'inverse — et un libellé ambigu sur un plafond de dépense est un défaut
sérieux, pas une question de style.

---

## Comportement attendu

### Cas nominal
1. Le champ s'intitule **« Budget hebdomadaire par client (€) »**.
2. Une phrase sous le champ dit la règle : *« S'applique à chaque client qui n'a pas de budget
   propre. Ce n'est pas une enveloppe commune. »*
3. Le **total qui en résulte** est écrit : *« 5 clients × 50 € = 250 € budgétés cette semaine »*.
4. Ce total est celui que l'écran affiche déjà en haut (`budgetEur`) : **aucun second calcul**.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Aucun budget posé | la phrase de règle reste ; aucun total n'est écrit — il n'y a rien à totaliser |
| Période « mois » | le budget reste **hebdomadaire** ; la phrase le dit, elle ne parle jamais du mois |

---

## Critères d'acceptation

- [ ] Le libellé du champ ne dit plus « par défaut » seul : il dit **« par client »**.
- [ ] La règle est écrite à l'écran, en une phrase, sous le champ.
- [ ] Le total budgété est écrit, et il est **lu** du résumé — pas recalculé côté navigateur.
- [ ] Sans budget, aucun total n'est écrit.
- [ ] Design system : aucune couleur ni police hors charte.

---

## Périmètre

### Hors scope
- Changer la **règle** : le défaut reste par client. Seul le texte change.
- Un budget global tous clients confondus — ce serait une autre décision, à trancher séparément.

---

## Technique

| Fichier | Changement |
|---|---|
| `admin-cost.component.html` | libellé, phrase de règle, total résultant |
| `admin-cost.component.ts` | `budgetExplanation()` — la phrase, construite du résumé |
| `admin-cost.component.scss` | une classe de note discrète |

Aucun changement d'API, aucune table.

---

## Plan de test

### Frontend
- [ ] La phrase de règle est présente.
- [ ] Le total est écrit quand un budget existe, absent sinon.
- [ ] Le total affiché est bien celui du résumé.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | — |
| **Plans / limites** | **oui** *(texte seul)* | La **règle** d'application du budget n'est pas touchée : `CostBudgetService.budgetOf` et `CostAlertService` restent inchangés. Seul le texte de l'écran change. Aucun gate, aucun quota modifié. |
| Navigation / routing | non | — |
