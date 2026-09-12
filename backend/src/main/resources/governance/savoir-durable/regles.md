**Le travail est jetable, le savoir est durable.** Un sujet produit des notes qui mourront avec lui.
Tout élément durable qu'il fait apparaître — une décision, une contrainte, un format, une limite
mesurée, un piège rencontré — doit être **promu** vers la carte du projet, sinon il est perdu avec les
notes.

### Où vit quoi

| Fichier | Où | Ce qu'il porte | Durée de vie |
|---|---|---|---|
| `STATE.md` | dans le projet | L'état du sujet en cours : où j'en suis, ce que je viens d'apprendre, la prochaine étape | Jetable — il meurt avec le sujet |
| `PLAN-ACTION.md` | dans le projet | **La carte du projet** : les décisions prises, les contraintes, ce qui reste à faire | Durable — il survit à tous les sujets |
| `README.md`, `acces.md`, `reseau.md`, `plateformes.md`, `donnees.md`, `exploitation.md` | **à la racine du poste** | **La carte du poste** : ce que la machine sait du client, tous projets confondus | Durable — elle survit à tous les projets |

Écris dans `STATE.md` sans hésiter : c'est un brouillon. N'écris dans `PLAN-ACTION.md` que ce qui
servira encore dans six mois.

### La carte du poste

**La carte, ce sont les fichiers `.md` posés à la racine du poste**, à côté des dossiers de projets.
Les projets sont des **dossiers**, la carte des **fichiers** : aucune confusion possible, et aucune
convention de chemin n'est imposée — la racine est celle de la machine, quel que soit son nom.

C'est l'endroit où la connaissance s'accumule : **à chaque projet qu'on ajoute, la connaissance de
l'infrastructure augmente.** Un projet meurt avec son sujet ; ce qu'il a fait apparaître de durable
reste sur la carte et sert au projet suivant.

**Règle d'écriture, sans exception : n'y mettre que des faits**, chacun avec sa **date** (sous la
forme `constaté le AAAA-MM-JJ`) et sa **source** (qui l'a dit, ou ce qui l'a montré). Trois
interdits qui vont avec :

1. **Pas de duplication** — quand la source de vérité existe ailleurs (wiki, dépôt, ticket), on
   **pointe** vers elle. Une copie devient fausse sans prévenir.
2. **Pas de secret** — jamais un mot de passe, une clé ou un jeton. On note **où** le secret vit et
   **qui** l'accorde.
3. **Pas de supposition** — ce qui n'a pas été vérifié se note comme non vérifié, ou ne se note pas.

Le **terminal du poste** s'ouvre à la racine : c'est de là qu'on lit et qu'on écrit la carte.

### La règle des livrables

**Rien de ce qui sort du projet ne doit suggérer qu'un modèle l'a produit.** Cela vaut pour les
messages de commit, les descriptions de demandes de fusion, la documentation, les commentaires de
code, les journaux et les messages d'erreur.

Concrètement, et sans exception : pas de co-signature au nom d'un assistant, pas de mention
« Generated with… », pas de lien de session, pas d'émoji robot. Le livrable appartient au projet et
porte le nom de la personne qui l'a demandé.

Un contrôle **refuse mécaniquement** un `git commit` portant l'un de ces marqueurs. Le reste — le
ton, les tournures, les listes à trois éléments partout — n'est pas vérifiable par une machine : il
est de ta responsabilité.

### La promotion, et sa dette

Quand un tour fait apparaître un élément durable, **ajoute-le à `PLAN-ACTION.md`** dans le même tour.
Ce qu'on note « plus tard » ne se note jamais.

Ce qui reste à faire s'écrit dans la carte sous forme de cases à cocher Markdown non cochées. Tant
qu'il en reste une, **le sujet n'est pas clos** : traite-la, ou retire la ligne devenue sans objet.
Un contrôle refuse de clore un tour tant que tu déclares une dette non nulle.

### Le marqueur de fin de tour

Termine **chaque** réponse finale par cette ligne, exactement sous cette forme :

```
<!-- fin-de-tour: promotion=aucune; dette=0 -->
```

- `promotion` — ce que ce tour a fait apparaître de durable et qui **ne figure pas encore** dans
  `PLAN-ACTION.md`, séparé par des virgules. Écris `aucune` s'il n'y a rien. Sois honnête : c'est toi
  qui juges, et c'est le seul endroit où ce jugement est demandé.
- `dette` — le nombre de cases non cochées restant dans `PLAN-ACTION.md`.

C'est un commentaire HTML : il ne s'affiche pas dans la réponse. Sans lui, la fin du tour est refusée
— non pas pour te punir, mais parce qu'un contrôle qui se tait quand il ne comprend pas ne protège de
rien.

### Comment écrire un message d'erreur

**Un message d'erreur porte son action corrective**, parce qu'il est lu par un modèle qui doit
corriger, pas par un humain qui doit comprendre. « Le fichier ne respecte pas la convention » ne se
corrige pas. « Ajoute l'en-tête en tête de `src/Foo.java`, puis reprends » se corrige.

Applique cette règle partout où tu écris un refus : messages d'erreur du produit, retours de
validation, commentaires de revue.
