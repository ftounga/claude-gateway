# Mini-spec — F-156 / SF-156-03 — Le verdict : active, dormante, indéterminée

## Identifiant
`F-156 / SF-156-03` — feature parente `F-156` — dépend de **SF-156-01** et **SF-156-02**

## Objectif
Trancher, pour chaque capacité, entre **« elle tourne »**, **« elle dort »** et **« je ne sais pas
encore »** — et, quand elle dort, dire **où regarder**.

## La question du cadrage
> *« 31 recherches de fichiers en 4 minutes : la capacité est-elle absente (il faut la créer) ou
> dormante (elle existe et ne se déclenche pas) ? »*

**Le second cas est le plus gros gisement** — il est arrivé trois fois cette semaine. Une capacité
dormante ne demande **aucun développement** : elle demande qu'on s'en aperçoive.

## Ce que « lire le code » veut dire ici, et ce que ça ne veut pas dire
**Ce qui est livré** : l'application lit **son propre état**. Pour les capacités dont le signal est
une **table** — l'index du dépôt, la mémoire de résolutions, la carte du poste —, une table **vide**
est la preuve directe que la capacité **n'a jamais été alimentée**. C'est exactement ce qui nous est
arrivé trois fois, et cela se constate sans lire une ligne de source.

**Ce qui n'est pas livré, et pourquoi** : la lecture du **code source** à l'exécution. Elle exige
que le terminal soit **le dépôt de l'application** — condition qui n'est pas toujours vraie — et
elle n'ajouterait rien aux trois cas rencontrés, que la carte et les tables tranchent déjà. La
déclarer livrée serait mentir sur ce que le diagnostic sait faire.

**Ce que la carte apporte à la place** : pour une capacité dormante, le verdict nomme **les fichiers
où elle vit** et **la condition qu'elle attend**. C'est l'endroit où aller regarder — ce que la
lecture automatique aurait donné, sans la promesse qu'on ne peut pas tenir.

## Comportement attendu
1. Chaque observation reçoit un **verdict** :
   - **ACTIVE** — le signal a été vu ;
   - **DORMANTE** — la capacité est déclarée (donc **présente** : la garde de SF-156-01 le
     garantit), et **aucun** signal sur la période ;
   - **INDÉTERMINÉE** — on n'a pas pu conclure, et on le **dit** plutôt que de deviner.
2. Un signal de **table** est **résolu** : la table est comptée pour le compte. Vide → la capacité
   n'a jamais été alimentée → **DORMANTE**. Non vide → **ACTIVE**.
3. Un verdict **DORMANTE** porte **l'endroit** (les chemins déclarés) et **ce qu'il faut vérifier**
   (la condition d'activation).
4. Il porte aussi **le gain**, quand l'enquête a su le calculer — sinon rien, jamais une estimation.
5. Une capacité **ACTIVE** n'est pas un constat : elle n'encombre pas le diagnostic. Elle reste
   consultable, mais les constats ne retiennent que **dormante** et **indéterminée**.

| Cas d'erreur | Comportement |
|---|---|
| Table inconnue du résolveur | **INDÉTERMINÉE**, et le dire — jamais « dormante » par défaut |
| Comptage impossible (base) | **INDÉTERMINÉE**, journalisé ; le diagnostic continue |
| Période vide | aucun verdict — il n'y a rien à trancher |

## Critères d'acceptation
- [ ] Signal vu → **ACTIVE**. Aucun signal, capacité déclarée → **DORMANTE**.
- [ ] Table **vide** → **DORMANTE** ; table **non vide** → **ACTIVE**.
- [ ] Table **inconnue** ou comptage en échec → **INDÉTERMINÉE**, jamais « dormante ».
- [ ] Un verdict DORMANTE nomme **les chemins** et **la condition**.
- [ ] Le gain n'apparaît que s'il a été **calculé** ; aucune estimation.
- [ ] Les constats retenus sont **dormante** et **indéterminée** ; les actives sont écartées **et
      comptées**.
- [ ] **ISOLATION** : chaque comptage de table filtre `user_id`.

## Hors scope
La **parité** (**SF-156-04**) · l'**écran** (**SF-156-05**) · la **lecture du code source** (voir
plus haut) · toute auto-modification.

## Technique
| Élément | Changement |
|---|---|
| `CapabilityVerdict` (enum) | `ACTIVE`, `DORMANTE`, `INDETERMINEE` |
| `CapabilityFinding` (record) | verdict, endroit, condition, gain |
| `TableSignalResolver` | compte les tables des signaux, par compte |
| `ProductDiagnosisService` | le passage de l'enquête aux constats |

**Aucune migration, aucun appel fournisseur.**

## Plan de test
- [ ] ACTIVE sur signal vu ; DORMANTE sur signal absent.
- [ ] Table vide → DORMANTE ; table pleine → ACTIVE.
- [ ] Table inconnue → INDÉTERMINÉE ; comptage qui lève → INDÉTERMINÉE, sans exception.
- [ ] Le constat DORMANTE porte chemins **et** condition.
- [ ] Gain repris **seulement** s'il existe.
- [ ] Les actives sont écartées des constats **et comptées**.
- [ ] **ISOLATION** : les comptages portent `user_id`.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint ici |
| **Contexte tenant** | **oui** | `TableSignalResolver` compte `repo_index_paths`, `resolution_memory` et `host_map_files` **par `user_id`** — via des méthodes de comptage ajoutées aux trois repositories, chacune portant le compte. Aucun comptage global. |
| Plans / limites | non | aucun appel fournisseur |
| Navigation / routing | non | aucune route |
