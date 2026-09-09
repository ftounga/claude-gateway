# Appairer une machine

L'appairage est le geste qui relie **une fois** votre machine à votre compte. Il s'ouvre depuis
l'Atelier, bouton **Connecter une machine**, et se déroule en quatre étapes : vérifier l'accès
réseau, générer un code, récupérer le runner, lancer la commande.

## Le code d'appairage

Le code est généré depuis l'écran. Il est à **usage unique** et **valable 5 minutes**. S'il expire,
il suffit d'en générer un autre : rien n'est perdu.

## La commande de premier lancement

Avec le fichier `.jar` :

```
java -jar claude-runner.jar \
  --gateway https://portal.ng-itconsulting.com/api \
  --workspace /chemin/vers/le/projet \
  --code AB2C3D4E \
  --label "poste-dev"
```

Avec un paquet autonome Windows, remplacez `java -jar claude-runner.jar` par
`claude-runner.cmd` ; sur Mac, par `./claude-runner.command`. Les options sont les mêmes.

L'option `--gateway` inclut bien le suffixe `/api`. `--label` est un libellé libre qui vous aidera à
reconnaître la machine dans la liste des jetons.

### Un chemin Windows avec des espaces

Entourez-le de guillemets — sans quoi le shell l'avale et le runner reçoit un chemin tronqué :

```
--workspace "C:\Users\Prenom Nom\dev\mon-projet"
```

C'est le piège classique sous Git Bash. La commande affichée par l'écran met déjà les guillemets.

## Le jeton, et les lancements suivants

Le code est échangé **une seule fois** contre un **jeton**, écrit sur votre machine dans
`<projet>/.claude-runner/token.json` (avec une copie de repli dans `~/.claude-runner/`), en
permissions restreintes. Aux lancements suivants, `--code` devient inutile tant que le jeton est
valide.

Mieux : après un appairage réussi, **plus aucun argument n'est nécessaire**.

```
cd /chemin/vers/le/projet
java -jar claude-runner.jar
```

Le runner a mémorisé l'adresse de la passerelle et la racine du projet dans un fichier
`session.json` voisin, qui ne contient **aucun secret**. Il cherche cette mémoire depuis le
répertoire courant, puis dans les dossiers parents, puis dans `~/.claude-runner`. L'ordre de
priorité est : **argument de la ligne de commande**, puis **variable d'environnement**, puis
**mémoire de reprise**.

Le runner ne redemande jamais un code en silence. Quand il ne peut pas reprendre, il dit lequel des
quatre cas s'applique : rien de mémorisé, jeton expiré (avec la date), jeton absent, ou racine
mémorisée disparue.

## Révoquer une machine

Depuis l'Atelier, la liste des jetons permet de révoquer un accès. La liaison est coupée
immédiatement. Un jeton refusé par la passerelle — révoqué ou expiré — est **effacé** sur la
machine : le lancement suivant redemandera un code.

## Un seul runner par projet, pour l'instant

Aujourd'hui, chaque projet a son code d'appairage et son runner. Si vous travaillez sur plusieurs
projets, vous appairez chacun d'eux.
