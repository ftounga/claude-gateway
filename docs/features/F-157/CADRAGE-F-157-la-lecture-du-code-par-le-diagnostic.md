# Cadrage — F-157 — La lecture du code par le diagnostic

> Demande du PO, 2026-09-24, après la livraison de F-156 :
> *« Oui cadre la lecture du code source et livre. »*
>
> Et, plus tôt : *« Si ça se trouve, tu as besoin de lire le code pour savoir quelle optimisation
> n'a pas été faite. »*

## 1. Ce que F-156 a laissé ouvert, et pourquoi

F-156 tranche **active / dormante / indéterminée** à partir de deux sources : les **signaux** (outils
appelés, grandeurs d'usage) et l'**état en base** (une table vide prouve qu'une capacité n'a jamais
été alimentée). Cela a suffi pour les trois cas de la semaine.

Ce que ces deux sources **ne peuvent pas** dire :

| Question | Pourquoi les mesures ne suffisent pas |
|---|---|
| La capacité est-elle encore **branchée** ? | une modification du code peut l'avoir débranchée sans rien casser : elle est « présente » (les fichiers existent) et pourtant plus appelée |
| **Qu'est-ce qui l'empêche** de se déclencher ? | la condition d'activation est écrite dans le code ; aucune mesure ne la lit |
| Quelle optimisation **n'a pas été faite** ? | par définition, elle ne laisse **aucune trace** — c'est l'absence qu'il faut voir |

La troisième est celle du PO, et c'est la plus difficile : **on ne mesure pas ce qui n'existe pas**.

## 2. La condition qui commande tout

Lire le code exige d'**avoir le code**. Le backend tourne en conteneur, sans ses sources. Le seul
chemin honnête : **un terminal dont le projet est le dépôt de l'application**, désigné
explicitement par l'administrateur.

**Et il faut le vérifier.** Un projet désigné par erreur ferait lire un code sans rapport et conclure
n'importe quoi. Le dépôt est donc **reconnu** avant d'être lu : les chemins déclarés par la carte des
capacités doivent s'y trouver. Sinon : *« ce projet n'est pas le dépôt de l'application »*, et rien
n'est lu.

## 3. Deux lectures, et elles n'ont pas le même prix

### (a) Le **témoin de branchement** — déterministe, gratuit
Chaque capacité déclare, à côté de ses chemins, un ou plusieurs **témoins** : un fragment littéral
qui **doit** se trouver dans un fichier donné pour que la capacité soit branchée — l'appel qui la
déclenche, la garde qui l'ouvre. Le diagnostic lit le fichier et regarde.

- Témoin **présent** + aucun signal → **dormante** : branchée, jamais déclenchée. Un réglage, une
  donnée manquante, une condition non remplie.
- Témoin **absent** → **débranchée** : la modification du code l'a détachée. C'est un **défaut**, et
  c'est un verdict bien plus utile que « dormante ».

**Ce n'est pas de l'analyse de code** : c'est la vérification d'un fait déclaré. Aucun modèle, aucun
jeton. Et une garde de build vérifie que chaque témoin est **réellement présent** aujourd'hui —
sinon la carte mentirait, comme pour les chemins (F-156 / SF-156-01).

### (b) La **lecture raisonnée** — avec modèle, à la demande, et son prix annoncé
Pour une capacité que (a) n'explique pas, l'agent lit les fichiers déclarés et répond à **une seule
question bornée** : *« qu'est-ce qui empêche cette capacité de se déclencher, et quelle optimisation
n'a pas été faite ici ? »*

C'est **Provider-First dans sa forme littérale** : Claude sait lire du code, on **relaie** — on
n'écrit pas un analyseur statique.

**Trois garde-fous, non négociables** :
1. Le résultat est une **hypothèse citée**, marquée comme telle. **Jamais un verdict.** Une hypothèse
   qui se déguise en constat est pire qu'un silence.
2. **Le coût est annoncé avant et mesuré après.** Le diagnostic de F-156 ne coûtait rien ; celui-ci
   coûte, et le taire ferait exactement ce que le bilan reproche aux sessions.
3. **À la demande, capacité par capacité.** Pas de lecture en masse : on lit ce qu'on a décidé de
   comprendre.

## 4. Ce qu'on écarte, et pourquoi

- **L'auto-modification**, encore et toujours. L'application lit, comprend, propose. Le PO décide.
- **L'analyseur statique maison** : réimplémenter la lecture de code que le fournisseur fait déjà
  (`PROJECT.md` §3.3). Les témoins sont une vérification de fait, pas une analyse.
- **La lecture de tout le dépôt** : on lit **les fichiers déclarés par la carte**, rien d'autre. Un
  diagnostic qui lit 4 000 fichiers coûterait plus cher que ce qu'il ferait économiser — le défaut
  même qu'il traque.
- **Le déclenchement automatique** : la lecture raisonnée coûte ; elle se demande.

## 5. Découpage proposé

| SF | Objet |
|---|---|
| **SF-157-01** | Les **témoins de branchement** dans la carte, et leur garde de build |
| **SF-157-02** | **Lire le dépôt de l'application** depuis un terminal désigné, et le **reconnaître** |
| **SF-157-03** | Le verdict **débranchée**, distinct de **dormante** |
| **SF-157-04** | La **lecture raisonnée** : une hypothèse citée, jamais un verdict, coût annoncé |
| **SF-157-05** | L'**écran** : désigner le dépôt, lire, voir les témoins et les hypothèses |

**Ordre** : 01 → 02 → 03 → 04 → 05.

## 6. Garde-fous transverses
- **Isolation** `user_id` : le projet désigné passe par `requireOwned`, la lecture par le chemin
  existant (`ProjectFileRead`), qui lit **là où vit le projet**.
- **Administrateur seulement**, par la **définition unique** (`AdminService`).
- **Aucune migration** attendue : la carte est du code, le rapport n'est pas gardé.
- **Aucune écriture** : on lit, on ne modifie rien — ni le dépôt, ni la spec.
