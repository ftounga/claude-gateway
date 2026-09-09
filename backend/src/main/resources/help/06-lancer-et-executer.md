# Lancer le runner et exécuter des commandes

## Les options de lancement

| Option | Variable d'environnement | Défaut | Rôle |
|---|---|---|---|
| `--gateway` | `CLAUDE_RUNNER_GATEWAY` | mémoire de reprise | Adresse de la passerelle, suffixe `/api` compris |
| `--workspace` | `CLAUDE_RUNNER_WORKSPACE` | mémoire de reprise | Racine du projet ; le runner refuse tout accès au-dessus |
| `--code` | `CLAUDE_RUNNER_CODE` | — | Code d'appairage, requis au premier lancement seulement |
| `--label` | `CLAUDE_RUNNER_LABEL` | aucun | Libellé de la machine affiché dans l'application |
| `--heartbeat-interval` | `CLAUDE_RUNNER_HEARTBEAT_INTERVAL` | `30` secondes | Période du battement de cœur |
| `--no-bash` | `CLAUDE_RUNNER_NO_BASH` | absent | Restreint la machine aux **outils fichiers** : plus aucune commande |
| `--transport` | `CLAUDE_RUNNER_TRANSPORT` | `auto` | `auto`, `websocket` ou `polling` |

L'argument de la ligne de commande l'emporte toujours sur la variable d'environnement.

`Ctrl-C` ferme la connexion et arrête le processus proprement (code de sortie `0`).

## L'exécution de commandes

Elle est **autorisée par défaut**, et **chaque commande demande votre autorisation à l'écran** avant
de partir. Cette porte de confirmation n'est pas désactivable en mode runner.

`--no-bash` fait l'inverse : la machine n'annonce plus la capacité, et la passerelle refuse l'appel
**avant même de l'émettre**, en expliquant comment le réactiver. C'est un verrou posé par la
machine, pas par l'application.

Ce qui encadre une commande :

- elle est passée à `/bin/sh -c` (`cmd.exe /c` sous Windows) et tourne **avec vos droits** ;
- elle démarre dans la racine du projet ; un sous-dossier peut être demandé, mais il passe par la
  même garde de confinement que les fichiers (ni `..`, ni chemin absolu, ni dossier exclu) ;
- la sortie standard et la sortie d'erreur sont diffusées **au fil de l'eau**, ligne à ligne ;
- l'entrée standard est fermée : une commande qui attend une saisie reçoit une fin de fichier au
  lieu de rester bloquée ;
- une seule commande à la fois ; ligne de commande limitée à 8 192 caractères ; 256 Kio de sortie
  diffusée par appel, au-delà la sortie est tronquée ; délai de 120 secondes par défaut, ramené au
  temps restant du tour ;
- un code de sortie non nul est rendu tel quel : la commande a tourné, son échec est une
  information, pas une panne du produit ;
- le bouton **Interrompre** de l'Atelier tue le processus et arrête le tour.

## Avec quels droits le runner agit

Avec **les droits du compte qui l'a lancé**, ni plus ni moins. Il ne bride aucun privilège et ne
prétend pas le faire.

- Lancé par vous, une commande `sudo` qui demande un mot de passe **échoue** — il n'y a pas de
  terminal pour le saisir. C'est un effet de bord, pas une protection : n'y comptez pas.
- Si `sudo` est configuré sans mot de passe, ou si le runner tourne en `root`, la commande passe.

**Recommandation : lancez le runner avec votre compte habituel, jamais en `root`.** S'il est lancé
en `root`, il vous le dit au démarrage, et l'écran vous le rappelle au moment d'autoriser une
commande.

Ce qui protège réellement : la porte de confirmation, le journal d'audit (chaque appel est tracé,
y compris les refus), les exclusions de fichiers, et la révocation des jetons qui coupe la liaison
immédiatement.

À noter : la racine du projet confine les **outils fichiers**, pas ce qu'une commande touche
ensuite. Un shell reste un shell.

## Le transport

Par défaut (`auto`), le runner ouvre une connexion WebSocket sécurisée, et se replie sur une
interrogation périodique si le WebSocket échoue de façon répétée — ce qui arrive derrière certains
proxys d'entreprise. `--transport polling` force ce repli, `--transport websocket` l'interdit.
