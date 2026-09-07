# Mini-spec — F-38 / SF-38-22 — Le runner dit qu'il lui faut Java 21

## Identifiant

`F-38 / SF-38-22`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-38-22-runner-version-java`

---

## Objectif

> Qu'un runner lancé sur une JVM trop ancienne dise **ce qu'il faut faire**, au lieu de laisser la
> machine virtuelle cracher une trace d'exception.

---

## Déclencheur

Premier lancement chez un client, le 2026-09-07 :

```
Error: A JNI error has occurred, please check your installation and try again
Exception in thread "main" java.lang.UnsupportedClassVersionError:
  fr/claudegateway/runner/RunnerMain has been compiled by a more recent version of the Java
  Runtime (class file version 65.0), this version of the Java Runtime only recognizes class
  file versions up to 52.0
```

Trente lignes de trace pour dire une chose simple : le poste a **Java 8**, le runner demande
**Java 21**. Aucun des deux nombres affichés (65.0, 52.0) n'est une version de Java que
l'utilisateur reconnaîtra.

L'écran d'appairage annonce « Java 21 **suffit** » — formulation qui se lit comme *« Java 21 est
suffisant »*, donc comme un plafond, alors que c'est un **plancher**.

---

## Comportement attendu

### Cas nominal

Sur une JVM 21 ou plus récente, **rien ne change** : le runner démarre exactement comme aujourd'hui,
mêmes arguments, même sortie.

### JVM trop ancienne

Le runner s'arrête avec un message qui tient en cinq lignes, sur `stderr`, et un code de sortie
non nul :

```
Ce runner demande Java 21 ou plus récent. Cette machine exécute Java 8.

  1. Vérifiez la version installée :  java -version
  2. Installez un JDK 21 : https://adoptium.net/temurin/releases/?version=21
     Aucun droit administrateur n'est nécessaire : une archive décompressée suffit.
  3. Relancez en désignant ce JDK :
     "/chemin/vers/jdk-21/bin/java" -jar claude-runner.jar --gateway … --workspace … --code …
```

**La version détectée est nommée** (« Java 8 »), pas un numéro de format de classe.

### Cas d'erreur

| Situation | Comportement |
|-----------|--------------|
| Version de la JVM illisible ou inattendue | Le runner **tente de démarrer** — un doute sur la version ne doit jamais empêcher un runner sain de fonctionner |
| JVM ≥ 21 | Aucun message, aucun coût : la vérification est un test d'entier |

---

## Critères d'acceptation

- [ ] Sur JVM 21+, le comportement est **identique** à aujourd'hui (arguments, sortie, code retour).
- [ ] Sur JVM < 21, le message ci-dessus s'affiche et le processus sort avec un code non nul.
- [ ] Le message **nomme la version trouvée** (« Java 8 »), jamais un numéro de format de classe.
- [ ] `UnsupportedClassVersionError` n'apparaît plus : la classe d'entrée est chargeable par une
      JVM 8.
- [ ] Une version illisible ne bloque pas le démarrage.
- [ ] L'écran d'appairage annonce « Java 21 ou plus récent » et non « Java 21 suffit ».

---

## Périmètre

### Hors scope

- Abaisser la cible de compilation du runner : Java 21 reste le socle du projet (`TECH_STACK`).
- Empaqueter une JVM avec le runner (installeur, image native) — c'est un autre chantier.
- La détection côté gateway : le problème se produit **avant** toute connexion.

---

## Technique

### Le point dur

Une classe compilée en Java 21 ne peut pas se charger sur une JVM 8 — donc **la vérification ne peut
pas vivre dans le code applicatif** : la JVM échoue avant d'exécuter la moindre ligne.

Solution : une classe d'entrée `RunnerLauncher` **compilée pour Java 8**, qui ne référence aucune
API récente, vérifie la version, puis appelle `RunnerMain` **par réflexion** — la réflexion évite que
le chargement de `RunnerMain` ne soit déclenché par la seule vérification.

Le manifeste du fat-jar pointe désormais sur `RunnerLauncher`.

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `runner/RunnerLauncher` | **Nouvelle** — compilée en `release 8`, point d'entrée du jar |
| `runner/pom.xml` | `default-compile` exclut le lanceur ; une exécution dédiée le compile en 8 ; `mainClass` du shade plugin |
| `runner-pairing-dialog.component.html` (écran) | « Java 21 ou plus récent » |

### Migration Liquibase

- [x] **Non applicable.**

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucune route ; seul un libellé du dialogue d'appairage change |
| **Compatibilité de livraison** | **Oui** | Le jar est servi par `GET /api/runner/download` depuis l'image backend (`APP_RUNNER_JAR_PATH`). Un runner **déjà installé** chez un client n'est pas mis à jour : la correction ne profite qu'aux téléchargements suivants. Aucun protocole ne change, donc un runner ancien continue de fonctionner. |

---

## Plan de test

### Tests unitaires

- [ ] La comparaison de version accepte 21, 22, 25.
- [ ] Elle refuse 8, 11, 17.
- [ ] Une chaîne illisible (`null`, `""`, `"inconnu"`) est traitée comme **acceptable**.
- [ ] Le message nomme la version trouvée.

### Tests d'intégration

- [ ] Le jar construit a bien `RunnerLauncher` comme `Main-Class`.
- [ ] `RunnerLauncher.class` est en **version de classe 52** (chargeable par une JVM 8), alors que
      `RunnerMain.class` reste en 65.

### Isolation workspace

- [x] Non applicable.

---

## Notes et décisions

**D1 — Vérifier dans un lanceur, pas dans le code applicatif.** C'est la seule place possible : sur
une JVM 8, aucune classe compilée en 21 ne se charge. Tout le reste du runner garde la cible 21.

**D2 — Le doute profite au démarrage.** Si la version est illisible, on démarre. Une JVM exotique
mais valide ne doit pas être bloquée par notre lecture d'une propriété système ; et si elle est
réellement trop ancienne, la JVM produira son erreur d'origine — on n'aura rien perdu.

**D3 — Nommer la version, pas le format de classe.** « class file version 52.0 » n'est
interprétable que par quelqu'un qui connaît déjà la réponse. Le message dit « Java 8 ».
