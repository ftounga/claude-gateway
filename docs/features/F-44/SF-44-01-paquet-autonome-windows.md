# Mini-spec — F-44 / SF-44-01 — Construire le runner autonome Windows

## Identifiant

`F-44 / SF-44-01`

## Feature parente

`F-44` — Runner sans prérequis Java (Windows x64)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-44-01-paquet-autonome-windows`

---

## Objectif

> Produire, à chaque build de l'image backend, un paquet ZIP contenant le runner **et sa propre
> JVM** — sans exiger de machine Windows pour le construire.

---

## Déclencheur

Un client n'a pas pu lancer le runner : poste d'entreprise en **Java 8**. SF-38-22 a rendu l'échec
lisible ; il reste à le supprimer.

---

## Comportement attendu

### Cas nominal

Le build de l'image backend produit `claude-runner-windows-x64.zip`, dont le contenu déplié est :

```
claude-runner/
  claude-runner.cmd     ← ce que l'utilisateur lance
  claude-runner.jar     ← le runner, inchangé
  runtime/              ← JVM Windows réduite (jlink)
    bin/java.exe
```

`claude-runner.cmd` appelle **la JVM du paquet**, jamais celle du système :

```bat
"%~dp0runtime\bin\java.exe" -jar "%~dp0claude-runner.jar" %*
```

Tous les arguments sont transmis tels quels (`%*`) : `--gateway`, `--workspace`, `--code`.

### Le point qui rend la feature abordable

Le runtime Windows est construit **depuis Linux**, en pointant `jlink` sur les `jmods` d'un JDK
Windows téléchargé au build :

```
jlink --module-path <jdk-windows>/jmods --add-modules … --output runtime
```

**Aucune machine Windows, aucune matrice CI.** Vérifié le 2026-09-07 : `java.exe` produit, paquet
de **39 Mo**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Téléchargement du JDK Windows en échec | Le **build échoue** — mieux vaut pas d'image qu'une image servant un paquet absent ou tronqué |
| Somme de contrôle du JDK inattendue | Le build échoue : on n'empaquette pas une JVM dont on ne sait pas d'où elle vient |
| `jlink` en échec | Le build échoue |

---

## Critères d'acceptation

- [ ] Le build produit `claude-runner-windows-x64.zip` à un chemin connu de l'image.
- [ ] Le ZIP contient `claude-runner.cmd`, `claude-runner.jar` et `runtime/bin/java.exe`.
- [ ] Le `.cmd` invoque **la JVM du paquet**, avec des fins de ligne **CRLF** (un `.cmd` en LF est
      refusé par certains shells Windows).
- [ ] Le `.jar` du paquet est **le même** que celui servi aujourd'hui — aucune divergence de version.
- [ ] Le paquet pèse moins de 60 Mo.
- [ ] La construction ne requiert **aucune machine Windows**.
- [ ] L'intégrité du JDK téléchargé est vérifiée avant empaquetage.
- [ ] Aucun changement pour le `.jar` existant : il continue d'être construit et servi.

---

## Périmètre

### Hors scope

- Servir le paquet et le proposer à l'écran → **SF-44-02**.
- macOS, Linux : le `.jar` reste leur format.
- Les installeurs natifs `.msi` (droits administrateur).
- La signature de code Windows (certificat éditeur) — SmartScreen affichera un avertissement au
  premier lancement ; à traiter séparément si cela gêne un client.

---

## Technique

### Modules de la JVM réduite

Déterminés par `jdeps` sur le jar réel, plus deux ajouts que `jdeps` ne peut pas voir :

| Module | Pourquoi |
|---|---|
| `java.base` | socle |
| `java.net.http` | client HTTP du runner |
| `java.desktop`, `java.sql` | dépendances optionnelles de Jackson (`java.beans`) |
| `jdk.crypto.ec` | **courbes elliptiques** : sans lui, la poignée de main TLS échoue sur la plupart des serveurs modernes — `jdeps` ne le voit pas, il est chargé comme service |
| `jdk.unsupported` | `sun.misc.Unsafe`, utilisé par des bibliothèques tierces |

### Fichiers impactés

| Fichier | Changement |
|--------|-----------|
| `backend/Dockerfile` | Étape de construction du paquet dans le stage `runner-build`, copie dans l'image finale |
| `runner/package-windows.sh` | **Nouveau** — script de construction, exécutable hors Docker pour être testable |
| `runner/src/test/.../WindowsPackageTest` | **Nouveau** — vérifie la structure du paquet quand il existe |

### Migration Liquibase

- [x] **Non applicable.**

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |
| **Chaîne de livraison** | **Oui** | Le build de l'image backend s'allonge (téléchargement d'un JDK de ~200 Mo) et l'image grossit d'environ 39 Mo. Le `.jar` existant, `APP_RUNNER_JAR_PATH` et `GET /api/runner/download` sont **inchangés** — un runner déjà installé continue de fonctionner. |

---

## Plan de test

### Tests unitaires

- [ ] Le script produit une arborescence conforme (structure, présence des trois éléments).
- [ ] Le `.cmd` contient bien `runtime\bin\java.exe` et transmet les arguments.
- [ ] Le `.cmd` est en CRLF.

### Tests d'intégration

- [ ] Le test de structure s'exécute **sur le paquet réellement produit** quand il est présent, et
      est **ignoré** sinon — le paquet demande un téléchargement de 200 Mo, qu'on n'impose pas à
      chaque exécution de la suite.

### Isolation workspace

- [x] Non applicable.

---

## Notes et décisions

**D1 — Construire depuis Linux, pas sur un agent Windows.** `jlink` sait cibler une autre plateforme
si on lui donne les `jmods` correspondants. Une matrice CI Windows aurait doublé la complexité du
pipeline pour le même résultat.

**D2 — `java.desktop` est conservé bien qu'il pèse ~13 Mo.** Jackson y touche par `java.beans`, de
façon optionnelle et par réflexion : un manque ne se verrait qu'à l'exécution, **chez le client**.
On échange 13 Mo contre l'absence d'une classe d'incidents qu'on ne peut pas tester ici.

**D3 — Le build échoue plutôt que de livrer un paquet douteux.** Une image qui sert un ZIP tronqué
est pire qu'une image qui n'existe pas : le premier geste d'un nouveau client est de le télécharger.

**D4 — Le `.jar` ne bouge pas.** Il reste construit, servi, et documenté. F-44 **ajoute** un format,
elle n'en remplace aucun.
