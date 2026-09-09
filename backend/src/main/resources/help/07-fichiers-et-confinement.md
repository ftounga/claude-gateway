# Fichiers, confinement et exclusions

## Ce que l'assistant peut faire sur votre machine

Quatre outils, et rien d'autre :

| Outil | Ce qu'il fait |
|---|---|
| `list_files` | Liste les fichiers de la racine du projet, en chemins relatifs |
| `read_file` | Lit un fichier texte |
| `write_file` | Écrit un fichier, en créant les dossiers parents manquants |
| `search_files` | Cherche un motif et rend des lignes `chemin:ligne: texte` |

## Le confinement

**La vérification qui fait foi est celle du runner, sur votre machine.** Tout chemin est résolu de
façon canonique — liens symboliques compris — et doit rester sous la racine déclarée au lancement.
Un `..`, un chemin absolu, une lettre de lecteur Windows ou un lien qui sort de la racine sont
refusés, et **rien n'est lu ni écrit**.

Les messages d'erreur renvoyés à l'application ne citent que des chemins **relatifs** : le chemin
absolu de votre machine ne sort jamais du poste.

Bornes appliquées localement : lecture refusée au-delà de 8 Mio, contenu tronqué au-delà de
512 Kio, écriture refusée au-delà de 512 Kio, 20 000 fichiers au plus pour le listing, fichiers
binaires et fichiers de plus d'1 Mio ignorés par la recherche. Chaque appel a son propre délai
(30 secondes par défaut) et peut être interrompu depuis la session. La console du runner affiche
chaque appel exécuté et sa durée.

## Les exclusions

Le filtre s'applique **sur votre machine, avant toute lecture, écriture ou listing** : ce qui est
exclu ne quitte jamais le poste. Les quatre outils le traversent — deviner le chemin d'un fichier
exclu ne le rend pas lisible.

Deux jeux de règles :

1. **Vos règles** — un fichier `.runnerignore` à la racine du projet. S'il est absent, le runner se
   replie sur le `.gitignore` de la racine. La syntaxe est celle de `.gitignore` : `#` pour un
   commentaire, `!` pour une négation, `/` final pour un dossier seulement, `/` initial ou interne
   pour un motif ancré à la racine, sinon le nom de base à n'importe quelle profondeur, avec les
   jokers `*`, `?` et `**`. La dernière règle qui correspond l'emporte. Les classes de caractères
   comme `[a-z]` ne sont pas interprétées : elles sont comparées littéralement.
2. **La liste par défaut, non désactivable** — `.env`, `*.pem`, `id_rsa*`, `.aws/`, `.kube/config`,
   `.ssh/`. Elle est évaluée **en dernier** et gagne toujours : un `!.env` dans votre
   `.runnerignore` ne la réactive pas.

Un dossier exclu est élagué du balayage : son contenu n'est ni listé, ni ouvert, ni lu.

Les règles sont chargées **au démarrage**. Modifier `.runnerignore` demande donc de relancer le
runner, qui affiche alors la source des règles et leur nombre.

Cette liste par défaut est volontairement courte et littérale. Elle ne couvre **pas** `.env.local`,
`id_ed25519`, `*.key`, `.npmrc`… : ajoutez-les à votre `.runnerignore`. Et pointez la racine sur le
dossier du projet, jamais sur votre dossier personnel.
