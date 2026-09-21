**Le travail est jetable, le savoir est durable.** Un sujet produit des notes qui mourront avec lui.
Tout élément durable qu'il fait apparaître — une décision, une contrainte, un format, une limite
mesurée, un piège rencontré — doit être **promu**, et **la promotion dit où** : dans la carte du
**poste** si cela survivra au projet, dans la carte du **projet** sinon. Ce qui n'est pas promu est
perdu avec les notes.

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

### La promotion : ranger le durable, tout de suite

Quand un tour fait apparaître un **élément durable**, range-le **dans le même tour**. Ce qu'on note
« plus tard » ne se note jamais. Le geste est simple : **ajoute-le au bon fichier de carte**, selon
ce que c'est.

| Ce que le tour a fait apparaître | Où ça va |
|---|---|
| cluster, serveur, hébergement, stockage | `plateformes.md` |
| plage, DNS, domaine, flux, certificat, endpoint | `reseau.md` |
| VPN, bastion, forge, compte, droit, **piège** | `acces.md` |
| base, schéma, sauvegarde, restauration | `donnees.md` |
| supervision, alerte, astreinte, procédure | `exploitation.md` |
| contact, convention du client, annuaire des projets | `README.md` |
| une **décision propre à ce projet** | `PLAN-ACTION.md`, dans le projet |

Le critère tient en une phrase : **ce qui survivra au projet va dans la carte du poste ; ce qui
meurt avec lui reste dans le projet.**

**Tu n'as rien à déclarer sur la *plomberie* de ce rangement.** Pas de marqueur à poser en fin de
tour, pas de compte de promotion ni de dette à tenir dans ta réponse. Le suivi se fait **côté
serveur**, à partir des fichiers que tu écris réellement — un fait écrit dans une fiche est un fait
rangé, sans que tu aies à l'annoncer.

**Mais dis *où* tu as rangé.** La *destination* d'un fait durable — quel sujet, quel fichier — n'est
pas de la plomberie : elle concerne la personne, qui doit pouvoir corriger à chaud un mauvais
rangement. Quand tu ranges un fait durable, ajoute **une ligne factuelle** disant où : « rangé dans
`data-platform/PLAN-ACTION.md` ». Nomme le sujet et le fichier concrets, rien de plus — pas le
vocabulaire de coulisse (promotion, dette, marqueur).

**Et si la destination est ambiguë, demande — ne devine pas.** Plusieurs sujets plausibles, ou
racine (carte du poste) contre projet incertain : pose la question (« ça relève de `data-platform`
ou de `lzi` ? ») **avant** d'écrire, plutôt qu'un rangement muet au mauvais endroit. Ton rôle tient
en trois mots : **réponds** à la question, **écris** le durable dans la carte quand il apparaît, et
**dis où** tu l'as mis — la plomberie, elle, reste en silence.

`STATE.md` reste ton brouillon : notes-y librement où tu en es et ce que tu viens d'apprendre. Ce qui
doit survivre au sujet part dans la carte ; le reste meurt avec `STATE.md`, et c'est très bien.

### Où se rangent les dépôts, et où ils ne se rangent pas

Trois invariants de la racine du poste. Chacun porte son **action corrective**, et son identifiant
est celui que citent les contrôles qui les vérifient.

| Identifiant | La règle | Si c'est le cas, le geste |
|---|---|---|
| `clonage/depot-dans-repos` | Un dépôt client se clone dans **`repos/`** sous la racine du poste, jamais à côté des dossiers de projets. | Déplace-le : `mkdir -p repos && mv <dossier> repos/<dossier>`. Laissé parmi les sujets, il serait pris pour un projet et gouverné comme tel. |
| `clonage/projet-sans-git` | **Un projet n'est jamais un dépôt versionné** : un sujet est un dossier de travail. | Ce dossier porte un `.git/` : c'est un dépôt, pas un sujet. Déplace-le sous `repos/` et ouvre un dossier de travail distinct pour le sujet. |
| `clonage/note-hors-depot` | **Aucune note personnelle non versionnée** à la racine d'un dépôt client. | Déplace ce `.md` dans la carte du poste, puis supprime-le du dépôt. Il ne doit pas partir dans un dépôt qu'on ne possède pas. |

La troisième est celle qui protège le plus : elle évite de livrer ses propres notes dans un dépôt
qu'on ne possède pas.

### Le second regard

Quand un tour a **écrit**, un second regard compare la carte aux notes et liste ce qui est cité là
et absent d'ici. **Filet best-effort, pas une autorité** : vérifie chaque élément dans le fichier
cité ; s'il est durable et réellement absent, ajoute-le à la bonne carte ; sinon **ignore-le**. Ce
filet lit **les fichiers**, jamais une déclaration que tu poserais — c'est lui qui rattrape ce qu'un
tour aurait oublié de ranger. S'il ne rend rien de lisible, on te le dit — un filet qui se tait
quand il ne comprend pas ne protège de rien.

### Comment écrire un message d'erreur

**Un message d'erreur porte son action corrective**, parce qu'il est lu par un modèle qui doit
corriger, pas par un humain qui doit comprendre. « Le fichier ne respecte pas la convention » ne se
corrige pas. « Ajoute l'en-tête en tête de `src/Foo.java`, puis reprends » se corrige.

Applique cette règle partout où tu écris un refus : messages d'erreur du produit, retours de
validation, commentaires de revue.
