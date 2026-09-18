# Gouvernance du projet — le savoir durable

> Déposé par le paquet de gouvernance **Le savoir durable**. Ce fichier explique ce que le paquet
> attend et pourquoi. Il ne s'exécute pas : ce sont les contrôles qui refusent, pas lui.

## Le principe

**Le travail est jetable, le savoir est durable.** Un sujet produit des notes qui mourront avec lui.
Tout élément durable qu'il fait apparaître doit être **promu** vers la carte du projet
(`PLAN-ACTION.md`), sinon il est perdu avec les notes.

## Les trois niveaux

Une gouvernance qui tient repose sur trois niveaux, et pas un seul :

| Niveau | Ce que c'est | Ce qui le porte ici |
|---|---|---|
| **La convention** | On le **demande** au modèle | Les règles du paquet, ajoutées à la consigne de chaque tour |
| **Le verrou** | On le **refuse**, mécaniquement | Les contrôles, branchés sur les points de contrôle de la boucle |
| **Le filet** | On **regarde les fichiers** produits et on alerte | Le second regard, qui compare la carte aux notes |

Le premier niveau seul ne tient pas : un modèle oublie, contourne, ou n'a simplement pas lu la ligne
qui comptait. C'est pour cela que les deux autres existent.

**Le modèle n'a rien à comptabiliser.** Il répond à la question et écrit le durable dans la carte ;
il ne pose aucun marqueur de fin de tour et ne tient aucun compte de promotion ou de dette dans sa
réponse. Le **suivi se fait côté serveur**, à partir des fichiers réellement écrits — c'est là que
vivent le verrou et le filet.

## Ce que ce paquet refuse

| Contrôle | Quand | Ce qu'il refuse |
|---|---|---|
| `commit-sans-trace-llm` | Avant chaque commande | Un `git commit` dont le message porte une trace d'assistant : co-signature, mention « Generated with… », lien de session, émoji robot |
| `integrite-du-poste` | En fin de tour, **si le tour a écrit** | Une carte absente, un projet sans `STATE.md`, une clôture avec une case ouverte, un projet qui est un dépôt git, une note perso non versionnée chez un client. Il **signale** aussi, sans bloquer : une carte sans section, un index surchargé, une référence morte, une dette en cours |
| `juge-independant` | En fin de tour, **si le tour a écrit** | Rien : il **signale**. Un second regard compare la carte du poste aux notes du projet et liste ce qui est cité là et absent d'ici — une **liste à vérifier**, pas un verdict |

Tous les refus sont **bornés** : après quelques passages, la main revient au modèle. Aucun contrôle
ne peut prendre un message en otage.

**Le suivi de la promotion et de la dette est un effet de bord serveur.** Le modèle ne pose aucun
marqueur de fin de tour et ne déclare rien : ce qu'il **écrit** dans une fiche de carte est constaté
côté serveur, et la dette (les cases `- [ ]` des fichiers du projet) est **comptée et signalée** sans
jamais renvoyer le modèle au travail ni s'inviter dans sa réponse.

`integrite-du-poste` porte **deux niveaux distincts, et ils ne se mélangent pas** : les **erreurs**
refusent la fin du tour, les **avertissements** informent et ne bloquent jamais. Chaque message porte
son **action corrective**, parce qu'il est lu par un modèle qui doit corriger. Les avertissements se
lisent aussi sur l'écran du poste, à côté de la carte.

`juge-independant` lit **les fichiers**, jamais une déclaration du modèle : il attrape le cas où un
tour a oublié de promouvoir — un modèle qui oublie de ranger oublierait aussi de le déclarer, alors
on regarde ce qui est réellement écrit. Il ne tourne que si le tour a écrit.

## Les fichiers déposés

| Fichier | Rôle |
|---|---|
| `STATE.md` | L'état du sujet en cours — **jetable**. Sa section `## Statut` vaut `en cours` ou `clos` |
| `PLAN-ACTION.md` | La carte du projet — **durable** |
| `.claude/skills/explique.md` | Expliquer un morceau du projet à partir de ce qui est écrit |
| `.claude/skills/plan-dashboard.md` | Faire le point sur la carte quand on le demande : ce qu'elle dit, ce qui lui manque |

Rien n'est jamais écrasé : un fichier déjà présent est laissé tel quel, et l'écran l'annonce avant
l'activation.

## Le statut du sujet, et la clôture

`STATE.md` porte une section `## Statut` avec **deux valeurs, et deux seulement** : `en cours` tant
que le sujet vit, `clos` quand il est terminé. Elle sépare deux situations qui n'appellent pas la
même réponse :

| Statut | Une case `- [ ]` reste ouverte | Ce qui se passe |
|---|---|---|
| `en cours` | oui | **Avertissement** — c'est un état normal : on le dit, on continue |
| `clos` | oui | **Refus** — remonte-les dans la carte du poste, puis clos |

Un `STATE.md` sans section `Statut` est lu **`en cours`** : on ne clôt jamais un sujet par
distraction.

## Comment écrire un refus

**Un message d'erreur porte son action corrective**, parce qu'il est lu par un modèle qui doit
corriger, pas par un humain qui doit comprendre. « Le fichier ne respecte pas la convention » ne se
corrige pas ; « ajoute l'en-tête en tête de `src/Foo.java`, puis reprends » se corrige. C'est la règle
d'écriture de tout ce paquet, et elle vaut pour les refus que tu écris toi-même.
