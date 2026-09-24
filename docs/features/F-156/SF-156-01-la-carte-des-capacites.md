# Mini-spec — F-156 / SF-156-01 — La carte des capacités

## Identifiant
`F-156 / SF-156-01` — feature parente `F-156`

## Objectif
Savoir **ce que le produit sait faire**, **où c'est dans le code**, et **à quelle condition ça
s'active** — pour pouvoir dire « **dormante** » plutôt que « absente ».

## La question qui commande toute la feature
> PO : *« Est-ce que l'application peut s'auto-améliorer, s'auto-diagnostiquer ? »*

La trace dit : *31 recherches de fichiers sur le poste, 4 minutes cumulées*. Deux lectures possibles :

| Lecture | Ce que ça veut dire | Ce qu'il faut faire |
|---|---|---|
| **Capacité absente** | l'application ne sait pas servir un index depuis la base | **créer** la capacité — c'est une feature |
| **Capacité dormante** | elle sait le faire, mais elle **ne s'est pas déclenchée** | **rien à développer** : un branchement à réparer |

Sans carte, on ne peut pas trancher — et on développerait ce qui existe déjà. **C'est arrivé trois
fois cette semaine** : index livré jamais amorcé, skill resté périmé sur le poste, carte absente du
contexte. Capacité **payée**, capacité **inutilisée**.

## La décision de conception : une carte déclarée dans le code
La carte n'est **pas une table**. C'est un **registre déclaré**, versionné avec le produit : une
capacité qui apparaît, disparaît ou déménage le fait dans le même commit que le code. Une table
aurait dérivé du code sans que rien ne le signale — c'est exactement le défaut qu'on traque.

**Et une garde la tient honnête** : un test vérifie qu'à chaque capacité correspond **au moins un
fichier qui existe vraiment**. Une carte qui ment est pire qu'une carte absente : elle fait conclure
« dormante » sur une capacité supprimée.

## Comportement attendu
1. Chaque capacité déclare : un **identifiant stable**, un **nom lisible**, ce qu'elle **évite**
   (le gaspillage qu'elle supprime), **où** elle vit (chemins réels), **à quelle condition** elle
   s'active, et **le signal** qui prouve qu'elle s'est déclenchée.
2. Le **signal** est ce que SF-156-03 ira chercher : un **outil** appelé, ou une **marque** dans les
   mesures existantes. Le déclarer ici évite de le redécouvrir capacité par capacité plus tard.
3. La carte est **lisible d'un seul endroit** et **ordonnée** de façon stable.
4. La carte est **ouverte** : une capacité s'ajoute sans toucher aux autres, et sa seule
   déclaration suffit à la faire entrer dans le diagnostic.

| Cas d'erreur | Comportement |
|---|---|
| Deux capacités du même identifiant | **échec du build** (test) — un doublon fausserait tout comptage |
| Chemin déclaré inexistant | **échec du build** (test) — la carte doit rester vraie |
| Capacité sans signal | **échec du build** (test) — on ne pourrait jamais dire si elle se déclenche |

## Critères d'acceptation
- [ ] La carte déclare au moins les capacités de référence du cadrage : **sous-agents**,
      **exploration parallèle**, **lecture seule**, **cache de prompt**, **index du dépôt**,
      **compaction**, **plan**, **mémoire de résolutions**, **carte du poste**.
- [ ] Chaque capacité porte identifiant, nom, ce qu'elle évite, chemins, condition, signal.
- [ ] Les identifiants sont **uniques**.
- [ ] **Chaque chemin déclaré existe** dans le dépôt — vérifié par un test.
- [ ] Chaque capacité a **au moins un signal**.
- [ ] L'ordre de la carte est **stable** d'un appel à l'autre.

## Hors scope
Les **motifs** sur plusieurs sessions (**SF-156-02**) · le **verdict** absente/dormante
(**SF-156-03**) · la **parité** (**SF-156-04**) · l'**écran** (**SF-156-05**) · toute lecture du
code à l'exécution · toute auto-modification.

## Technique
| Élément | Changement |
|---|---|
| `ProductCapability` (record) | une capacité : identité, ce qu'elle évite, chemins, condition, signaux |
| `CapabilityMap` | la carte déclarée, et son accès |
| `CapabilityMapTest` | la **garde** : unicité, chemins réels, signaux présents |

**Aucune migration, aucun appel fournisseur, aucune route.** C'est un registre.

## Plan de test
- [ ] Les capacités de référence sont présentes.
- [ ] Identifiants uniques.
- [ ] **Chaque chemin déclaré existe sur le disque** — la garde anti-mensonge.
- [ ] Chaque capacité a un signal, un nom non vide, une condition non vide.
- [ ] L'ordre est stable.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint, aucune identité — c'est une constante du produit |
| Contexte tenant | non | **aucune donnée utilisateur** n'est lue : la carte décrit le produit, pas un compte |
| Plans / limites | non | aucun appel fournisseur |
| Navigation / routing | non | aucune route |
