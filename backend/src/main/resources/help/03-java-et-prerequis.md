# Java : de quelle version ai-je besoin ?

Le fichier `.jar` du runner demande **Java 21 ou plus récent**. C'est un **plancher**, pas un
plafond : Java 22, 23 ou plus récent conviennent aussi.

Vérifier la version installée :

```
java -version
```

## « A JNI error has occurred » / `UnsupportedClassVersionError`

C'est le symptôme d'une machine virtuelle Java **trop ancienne** — typiquement Java 8 sur un poste
d'entreprise. Le message d'origine parle de « class file version 65.0 » et « 52.0 » : ce sont des
numéros internes, 65 correspond à Java 21 et 52 à Java 8.

Le runner détecte lui-même ce cas et affiche un message court plutôt qu'une trace d'exception.

Deux remèdes, au choix :

1. **Prendre le paquet autonome** de votre système (Windows, Mac Apple Silicon, Mac Intel). Il
   contient sa propre machine virtuelle Java : la version installée sur le poste n'a plus
   d'importance. C'est la solution quand la JVM est imposée par la DSI.
2. **Installer un JDK 21**, par exemple depuis `https://adoptium.net/temurin/releases/?version=21`.
   Aucun droit administrateur n'est nécessaire : une archive décompressée suffit, il reste à pointer
   dessus au lancement.

## Plusieurs Java installés

Si `java -version` affiche une version ancienne alors qu'un JDK 21 est présent sur le poste, appelez
le binaire par son chemin complet :

```
/chemin/vers/jdk-21/bin/java -jar claude-runner.jar
```

Sous Windows, entourez le chemin de guillemets s'il contient des espaces.

## Et le reste ?

Il n'y a pas d'autre prérequis : ni base de données, ni serveur web, ni port à ouvrir en entrée. Le
runner ouvre lui-même une connexion **sortante** en HTTPS/443 vers la passerelle. Ce qui bloque en
pratique, ce n'est presque jamais Java : c'est la sortie réseau — voir la page sur le proxy.
