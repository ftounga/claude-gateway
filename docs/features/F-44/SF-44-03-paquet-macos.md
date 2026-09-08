# Mini-spec — F-44 / SF-44-03 — Le paquet autonome macOS, servi et proposé

## Identifiant

`F-44 / SF-44-03`

## Feature parente

`F-44` — Runner sans prérequis Java (périmètre amendé le 2026-09-08 : macOS y entre)

## Statut

`ready`

## Date de création

2026-09-08

## Branche Git

`feat/SF-44-03-paquet-macos` (backend) puis `feat/SF-44-03-ecran-macos` (écran)

---

## Objectif

> Donner au poste **macOS verrouillé** ce que SF-44-01/02 ont donné au poste Windows : un paquet
> qui embarque sa propre JVM, construit depuis Linux, servi par la gateway et proposé à l'écran —
> pour les **deux architectures** Apple Silicon et Intel.

---

## Pourquoi maintenant

Le périmètre initial de F-44 excluait macOS sur une hypothèse écrite : « un développeur macOS ou
Linux a déjà un JDK ». Une intervention chez le **même client**, le 2026-09-08, sur un poste macOS
d'entreprise, l'a démentie : ni droits administrateur, ni Homebrew, ni JDK. L'hypothèse n'était pas
fausse pour un développeur — elle était fausse pour un **poste géré par une DSI**, et c'est ce
poste-là que F-44 vise.

Les deux outils de construction sont déjà sur `main` (commit `8358abb`, versionnés comme *outils*) :
`runner/package-macos.sh` construit le paquet, et il a été vérifié à la main (bin/java Mach-O arm64
resp. x86_64, 38,6 et 39,7 Mo, `jdk.crypto.ec` présent). **Aucun des deux n'est servi par la
gateway** : c'est exactement ce que cette subfeature ajoute.

---

## Comportement attendu

### Cas nominal

| Route | Sert | Nom du fichier |
|---|---|---|
| `GET /runner/download` | le `.jar` — **inchangé** | `claude-runner.jar` |
| `GET /runner/download/windows` | le paquet Windows — **inchangé** | `claude-runner-windows-x64.zip` |
| `GET /runner/download/macos-aarch64` | le paquet macOS Apple Silicon | `claude-runner-macos-aarch64.tar.gz` |
| `GET /runner/download/macos-x64` | le paquet macOS Intel | `claude-runner-macos-x64.tar.gz` |

Les quatre sont **publiques** : ce sont des clients, pas des données utilisateur — ils ne contiennent
ni jeton ni secret, l'appairage vient après.

`GET /runner/download/formats` gagne deux booléens : `macosAarch64Package`, `macosX64Package`.
Les champs existants (`jar`, `windowsPackage`) sont **inchangés** — un frontend plus ancien continue
de lire ce qu'il lisait.

L'image du backend construit les deux paquets macOS dans le stage `runner-build` existant, par le
même `jlink` croisé depuis Linux : **aucune machine Apple, aucune matrice d'intégration continue.**

### À l'écran (dialogue d'appairage)

Le choix de format devient **quatre options**, dans l'ordre, celles absentes de la gateway étant
masquées :

1. **Windows, sans rien installer** — ~39 Mo, contient Java.
2. **Mac Apple Silicon (M1 → M4)** — ~39 Mo, contient Java.
3. **Mac Intel** — ~40 Mo, contient Java.
4. **Fichier `.jar`** — 2,5 Mo, nécessite Java 21 déjà installé.

Le format **présélectionné** suit le système qui consulte la page : Windows → le paquet Windows,
Mac → Apple Silicon, tout le reste (Linux compris) → le `.jar`. Un format indisponible fait
retomber la sélection sur le premier format réellement servi.

La commande affichée suit le format : `./claude-runner.command --gateway …` sur macOS,
`claude-runner.cmd …` sur Windows, `java -jar claude-runner.jar …` sur le jar. **Jamais `java` devant
un paquet** : ce serait rappeler la JVM du système, celle-là même qui manque.

### Cas d'erreur

| Situation | Comportement | Code HTTP |
|-----------|--------------|-----------|
| Paquet macOS non empaqueté dans l'image (déploiement antérieur) | 404 `runner_package_unavailable`, message nommant la plateforme et l'architecture | 404 |
| Chemin non configuré | 404, même réponse | 404 |
| Fichier absent ou illisible | 404 — jamais une erreur serveur | 404 |
| `GET /download/formats` illisible depuis l'écran | l'écran retombe sur le `.jar` seul, sans erreur affichée | — |
| Architecture Mac choisie à tort par l'utilisateur | hors du ressort de la gateway : le lanceur échoue côté poste (`Bad CPU type`). L'écran donne le moyen de vérifier (« À propos de ce Mac ») | — |

---

## Critères d'acceptation

- [ ] `GET /runner/download` et `GET /runner/download/windows` sont **inchangés** (non-régression).
- [ ] `GET /runner/download/macos-aarch64` sert l'archive sous le nom `claude-runner-macos-aarch64.tar.gz`.
- [ ] `GET /runner/download/macos-x64` sert l'archive sous le nom `claude-runner-macos-x64.tar.gz`.
- [ ] Un paquet macOS absent donne un **404 `runner_package_unavailable`** dont le **message** nomme
      la plateforme et l'architecture — et n'emporte pas les autres formats.
- [ ] Les deux nouvelles routes sont **publiques** et passent par la chaîne `/runner/**`.
- [ ] `GET /runner/download/formats` expose `macosAarch64Package` et `macosX64Package` sans modifier
      les champs existants.
- [ ] Le `Dockerfile` construit les deux paquets macOS et les dépose dans l'image ; le build
      **échoue** plutôt que de livrer une archive tronquée (garde déjà présente dans le script).
- [ ] La configuration k8s pointe les deux nouveaux chemins.
- [ ] L'écran propose les formats macOS quand ils sont servis, les **masque** sinon.
- [ ] Le format présélectionné suit le système qui consulte la page.
- [ ] La commande affichée pour un paquet macOS est `./claude-runner.command …` et ne contient
      **jamais** `java -jar`.
- [ ] **Design system** : couleurs et polices de `DESIGN_SYSTEM.md`, aucun `window.confirm`.

---

## Périmètre

### Hors scope (explicite)

- **Linux** : reste dehors. Un poste Linux verrouillé au point de n'avoir aucun JDK n'a pas été
  rencontré, et le `.jar` de 2,5 Mo y reste le bon format.
- **La signature / notarisation Apple** : sans certificat développeur, Gatekeeper avertit. Le
  lanceur lève la quarantaine (`xattr -dr com.apple.quarantine`), ce qui suffit à démarrer ; une
  notarisation véritable est un sujet de compte Apple Developer, pas de code.
- **Les installeurs natifs `.pkg`** : ils demandent les droits administrateur qu'on cherche à éviter.
- **`scripts/setup-runner-macos.sh`** : c'est la matière de **F-45** (mise en service guidée sur
  poste d'entreprise), pas de F-44. Il reste un outil d'intervention, non servi par la gateway.
- **Toute modification du protocole d'appairage** : les quatre formats parlent le même.

---

## Technique

### Classes et fichiers impactés

| Fichier | Changement |
|--------|-----------|
| `runner/RunnerDownloadController` | Deux routes macOS, deux chemins configurables, deux champs de plus dans `RunnerDownloadFormats` |
| `runner/RunnerSecurityConfig` | Les deux routes déclarées `permitAll` sur la chaîne `/runner/**` |
| `backend/src/main/resources/application.yml` | `app.runner.macos-aarch64-package-path`, `app.runner.macos-x64-package-path` |
| `k8s/base/backend/configmap.yaml` | `APP_RUNNER_MACOS_AARCH64_PACKAGE_PATH`, `APP_RUNNER_MACOS_X64_PACKAGE_PATH` |
| `backend/Dockerfile` | Construction des deux paquets macOS dans le stage `runner-build`, copie dans l'image |
| `frontend/core/models/atelier.models.ts` | `RunnerDownloadFormats` : deux booléens |
| `frontend/core/services/atelier.service.ts` | Deux téléchargements |
| `runner-pairing-dialog.component.{ts,html}` | Quatre formats, présélection par système, commande adaptée |

### Migration Liquibase

- [x] **Non applicable** — aucune donnée, aucun schéma.

### Contraintes de validation

| Champ | Contrainte | Source |
|---|---|---|
| Architecture | `aarch64` \| `x64` — fermé, refusé sinon | `package-macos.sh` (déjà tranché) |
| Nom d'archive | `claude-runner-macos-{arch}.tar.gz` | `package-macos.sh` |
| Taille minimale du JDK téléchargé | 100 Mo, sinon échec du build | `package-macos.sh` (D3 de SF-44-01) |

Aucune contrainte structurante n'est laissée indéterminée ; aucune entrée d'`OPEN_QUESTIONS.md`
n'est ouverte par cette subfeature.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| **Auth / Principal** | **Oui** | Deux routes **publiques** de plus. Composants à vérifier : `RunnerSecurityConfig` (chaîne `/runner/**`, ordonnée avant la principale), et le fait qu'aucune des deux n'apparaisse dans les chemins authentifiés de `SecurityConfig`. Test d'intégration sans authentification pour chacune. |
| Contexte tenant | Non | Le paquet est identique pour tous : aucune donnée utilisateur, aucun `user_id` en jeu |
| Plans / limites | Non | Le téléchargement n'est pas conditionné à un plan (l'appairage l'est) |
| **Navigation / routing** | **Oui** | Le dialogue d'appairage gagne deux options. Chemins à revérifier : création de projet local → appairage → connexion ; réouverture de l'appairage depuis un projet en attente ; gateway sans paquet (retour au jar seul). |

---

## Plan de test

### Tests unitaires / intégration backend

- [ ] Chaque route macOS sert son archive avec le bon `Content-Disposition`.
- [ ] Chaque route macOS rend un 404 `runner_package_unavailable` quand le chemin est vide, absent
      ou illisible — et le message nomme l'architecture.
- [ ] Un paquet macOS absent n'empêche ni le jar ni le paquet Windows d'être servis.
- [ ] Les deux routes répondent **sans authentification**.
- [ ] `GET /download/formats` rend les quatre booléens, chacun suivant la présence réelle du fichier.
- [ ] Non-régression : `/download` et `/download/windows` inchangés.

### Tests frontend

- [ ] Les formats macOS sont proposés quand la gateway les sert, masqués sinon.
- [ ] La présélection suit le système (Windows / Mac / autre).
- [ ] La commande d'un paquet macOS est `./claude-runner.command …` et ne contient pas `java -jar`.
- [ ] Le téléchargement macOS appelle la bonne route et enregistre le bon nom de fichier.
- [ ] Non-régression : le comportement Windows et jar est inchangé.

### Isolation workspace

- [x] **Non applicable** — aucune donnée utilisateur n'est lue ni écrite.

---

## Notes et décisions

**D1 — Une route par architecture, pas un paramètre.** `/download/macos?arch=x64` rendrait le cache
et les liens directs ambigus, et obligerait à arbitrer une valeur inconnue. C'est la même décision
qu'en SF-44-02 (D1), appliquée à l'axe qui s'ajoute.

**D2 — Un seul code d'erreur pour les paquets, un message par plateforme.** SF-44-02 a distingué
`runner_jar_unavailable` de `runner_package_unavailable` parce que ce sont **deux incidents
d'exploitation différents** : le jar manque / le paquet n'a pas été empaqueté. Entre paquets, c'est
le **même** incident ; ce qui change est *lequel*, et le message le dit. Multiplier les codes
obligerait l'écran à en connaître quatre pour une seule conduite : masquer le format.

**D3 — `.tar.gz` et non `.zip`.** Le ZIP perd le bit exécutable de `runtime/bin/java` et du lanceur,
et les liens symboliques du JDK. Un paquet décompressé et non exécutable serait pire qu'absent : il
échoue **chez le client**, après le téléchargement. (Décidé et vérifié dans `package-macos.sh`.)

**D4 — Apple Silicon présélectionné sur un Mac ; l'Intel reste visible.** Le navigateur ne sait pas
distinguer les deux de façon fiable — Safari comme Chrome annoncent `MacIntel` sur un M3. Écarté :
`navigator.userAgentData.getHighEntropyValues()`, asynchrone et absent de Safari et Firefox, donc un
chemin de repli à écrire pour une bonne moitié des Macs, sans gain. On présélectionne l'architecture
majoritaire depuis 2020, on rend l'autre visible d'un clic, et on donne le moyen de vérifier
(« À propos de ce Mac »). Réversible : c'est une valeur par défaut.

**D5 — Les deux architectures, pas seulement Apple Silicon.** Construire les deux coûte ~78 Mo dans
l'image et deux `jlink` au build. Ne construire qu'`aarch64` économiserait la moitié — et rejouerait
exactement l'échec qui a produit la feature le jour où le poste verrouillé est un Mac Intel. Les
postes d'entreprise sont précisément ceux qu'on ne renouvelle pas.

**D6 — La commande affichée n'appelle jamais `java`.** Comme sur Windows : préfixer par `java`
rappellerait la JVM du système. Sur macOS le lanceur est `./claude-runner.command`, exécutable parce
que l'archive est un `tar.gz`.

**D7 — Backend puis frontend, dans cet ordre, sans worktrees parallèles.** Le contrat d'API est
figé par cette mini-spec, mais la surface est trop petite pour que le parallélisme rapporte plus que
son coût de coordination. La règle « backend avant frontend » est respectée : l'écran ne peut pas
proposer un format que la gateway ne sert pas encore.

---

## Ce que cette subfeature ne fait pas, et qui reste vrai

La construction croisée est vérifiée au build ; **rien ne prouve en intégration continue qu'un Mac
verrouillé exécute le paquet** — même angle mort que pour Windows, relevé le 2026-09-08 et porté par
le scénario **S16** du smoke manuel de F-38. Cette subfeature ajoute le **S17** correspondant pour
macOS. Sa planification reste **OQ-13**.
