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
| **Le filet** | On **regarde** ce qui vient d'être produit et on alerte | Le juge de fin de tour |

Le premier niveau seul ne tient pas : un modèle oublie, contourne, ou n'a simplement pas lu la ligne
qui comptait. C'est pour cela que les deux autres existent.

## Ce que ce paquet refuse

| Contrôle | Quand | Ce qu'il refuse |
|---|---|---|
| `commit-sans-trace-llm` | Avant chaque commande | Un `git commit` dont le message porte une trace d'assistant : co-signature, mention « Generated with… », lien de session, émoji robot |
| `juge-fin-de-tour` | En fin de tour | Une réponse sans marqueur de fin de tour, ou qui déclare du durable non encore promu |
| `promotion-dette-bloquante` | En fin de tour | Une clôture alors qu'une case reste non cochée dans la carte du projet |

Tous les refus sont **bornés** : après quelques passages, la main revient au modèle. Aucun contrôle
ne peut prendre un message en otage.

## Les fichiers déposés

| Fichier | Rôle |
|---|---|
| `STATE.md` | L'état du sujet en cours — **jetable** |
| `PLAN-ACTION.md` | La carte du projet — **durable** |
| `.claude/skills/explique.md` | Expliquer un morceau du projet à partir de ce qui est écrit |
| `.claude/skills/plan-dashboard.md` | Faire le point sur la carte, promouvoir, compter la dette |

Rien n'est jamais écrasé : un fichier déjà présent est laissé tel quel, et l'écran l'annonce avant
l'activation.

## Comment écrire un refus

**Un message d'erreur porte son action corrective**, parce qu'il est lu par un modèle qui doit
corriger, pas par un humain qui doit comprendre. « Le fichier ne respecte pas la convention » ne se
corrige pas ; « ajoute l'en-tête en tête de `src/Foo.java`, puis reprends » se corrige. C'est la règle
d'écriture de tout ce paquet, et elle vaut pour les refus que tu écris toi-même.
