# Télécharger le runner : quel format choisir

Le runner se télécharge depuis la Forge : bouton **Connecter une machine**, étape *Récupérer le
runner*. Quatre formats existent ; l'écran n'affiche que ceux que la passerelle sert réellement.

| Format | Taille | Prérequis | Ce qu'on lance |
|---|---|---|---|
| **Windows, sans rien installer** (`claude-runner-windows-x64.zip`) | ~39 Mo | aucun — Java est dans l'archive | `claude-runner.cmd` |
| **Mac Apple Silicon** (`claude-runner-macos-aarch64.tar.gz`) | ~39 Mo | aucun — Java est dans l'archive | `./claude-runner.command` |
| **Mac Intel** (`claude-runner-macos-x64.tar.gz`) | ~40 Mo | aucun — Java est dans l'archive | `./claude-runner.command` |
| **Fichier `.jar`** (`claude-runner.jar`) | 2,5 Mo | **Java 21 ou plus récent** déjà installé | `java -jar claude-runner.jar` |

Adresses de téléchargement, si vous préférez la ligne de commande :

```
https://portal.ng-itconsulting.com/api/runner/download
https://portal.ng-itconsulting.com/api/runner/download/windows
https://portal.ng-itconsulting.com/api/runner/download/macos-aarch64
https://portal.ng-itconsulting.com/api/runner/download/macos-x64
```

Ces adresses sont **publiques** : ce sont des programmes clients, ils ne contiennent ni jeton ni
secret. L'appairage vient après.

## Comment choisir

- **Poste d'entreprise verrouillé, pas de droits administrateur, Java ancien ou absent** → prenez le
  paquet autonome de votre système. Il embarque sa propre machine virtuelle Java : rien à installer,
  rien à demander à la DSI.
- **Java 21 déjà installé** → prenez le `.jar`. Télécharger 39 Mo pour en utiliser 2,5 n'a pas de
  sens.

## Apple Silicon ou Intel ?

Menu **Pomme** › **À propos de ce Mac**. « Puce Apple » = Apple Silicon (M1 à M4), « Processeur
Intel » = Intel. Les archives macOS sont en `.tar.gz` et non en `.zip` : un `.zip` perdrait le
caractère exécutable du lanceur.

## Un format n'apparaît pas dans l'écran

C'est que cette passerelle ne le sert pas. L'écran préfère masquer un format plutôt que proposer un
lien qui échouerait. Le `.jar` reste toujours disponible.

## Aucun droit administrateur, aucun service en arrière-plan

Le runner n'est pas un installeur. On décompresse une archive, on lance un programme dans un
terminal, il reste ouvert tant qu'on travaille, et `Ctrl-C` l'arrête proprement.
