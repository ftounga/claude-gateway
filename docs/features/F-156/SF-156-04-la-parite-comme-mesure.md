# Mini-spec — F-156 / SF-156-04 — La parité comme mesure

## Identifiant
`F-156 / SF-156-04` — feature parente `F-156` — dépend de **SF-156-01** → **03**

## Objectif
Faire de la parité une **mesure** plutôt qu'une opinion : pour chaque capacité de référence,
*présente dans le produit ?* et *déclenchée dans les sessions ?* — **le diagnostic naît de l'écart
entre ces deux colonnes.**

## La demande
> PO (mémoire projet) : *« Je veux que l'Atelier raisonne exactement comme Claude Code. »*
> Cadrage F-156 : *« Une liste de capacités de référence — déléguer à des sous-agents, explorer en
> parallèle, travailler en lecture seule, compacter, réutiliser le cache, indexer, planifier — et
> pour chacune : présente ? déclenchée ? »*

Jusqu'ici la parité se discutait de mémoire. Deux colonnes la rendent **constatable**.

## Trois états, pas deux — et le troisième est le plus important
| État | Ce que ça veut dire | Ce qu'il faut faire |
|---|---|---|
| **Présente et déclenchée** | la parité est tenue | rien |
| **Présente, jamais déclenchée** | **dormante** (SF-156-03) | un branchement à réparer, **aucun développement** |
| **Absente** | le produit ne sait pas le faire | **créer** — c'est une feature |

**Et une absence peut être délibérée.** Les *hooks* sont écrits « hors périmètre » dans F-39 : les
proposer à chaque rapport rendrait le diagnostic insupportable. Une référence écartée porte donc
**sa raison**, et n'apparaît **jamais** comme un manque.

## Comportement attendu
1. Une **liste de référence** déclare ce qu'un harnais de cet ordre sait faire, et pour chacune :
   soit **la capacité du produit** qui la porte, soit **la raison** pour laquelle elle est écartée.
2. La parité rend, par référence : **présente ?**, **déclenchée ?**, et l'écart en une phrase.
3. Une référence **écartée volontairement** est rendue comme telle — jamais comme un manque.
4. Une référence **sans correspondance et sans raison** est un **manque réel** : c'est le seul cas
   qui appelle une feature.
5. La parité se calcule **à partir du diagnostic** (SF-156-03) : elle ne relit rien, elle croise.

| Cas d'erreur | Comportement |
|---|---|
| Référence pointant une capacité inexistante | **échec du build** (test) — la liste mentirait |
| Référence à la fois portée et écartée | **échec du build** (test) — on ne peut pas être les deux |
| Diagnostic vide | parité rendue avec « non observé », jamais « absente » |

## Critères d'acceptation
- [ ] Chaque référence porte un identifiant, un nom, **ce qu'elle apporte**, et soit une capacité,
      soit une raison d'écart.
- [ ] Toute capacité pointée **existe dans la carte** — vérifié par un test.
- [ ] Aucune référence n'est **à la fois** portée et écartée — vérifié par un test.
- [ ] La parité distingue **présente/déclenchée**, **présente/dormante**, **absente**, **écartée**.
- [ ] Sans observation, l'état est **« non observé »**, jamais « absente ».
- [ ] Le **compte** des manques réels est rendu — c'est le chiffre qui intéresse le PO.

## Hors scope
L'**écran** (**SF-156-05**) · toute comparaison avec un produit tiers autre que par cette liste ·
toute auto-modification.

## Technique
| Élément | Changement |
|---|---|
| `ReferenceCapability` (record) | la référence : ce qu'elle apporte, sa capacité ou sa raison d'écart |
| `ParityReference` | la liste déclarée |
| `ParityRow` (record) | référence, présente, déclenchée, état, note |
| `ParityService` | le croisement diagnostic × références |
| `ParityReferenceTest` | la **garde** : capacités réelles, pas de double statut |

**Aucune migration, aucune route, aucun appel fournisseur.**

## Plan de test
- [ ] Référence portée + verdict ACTIVE → **présente et déclenchée**.
- [ ] Référence portée + verdict DORMANTE → **présente, jamais déclenchée**.
- [ ] Référence sans capacité et sans raison → **absente**, comptée comme manque.
- [ ] Référence **écartée** → rendue avec sa raison, **jamais** comptée comme manque.
- [ ] Diagnostic vide → **non observé**.
- [ ] **Garde** : capacité pointée inexistante → échec ; double statut → échec.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint ici |
| Contexte tenant | non | **aucune lecture** : la parité croise une liste déclarée avec un diagnostic déjà produit sous isolation (SF-156-03) |
| Plans / limites | non | aucun appel fournisseur |
| Navigation / routing | non | aucune route |
