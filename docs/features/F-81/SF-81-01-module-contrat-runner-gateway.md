# Mini-spec — F-81 / SF-81-01 — Le module qui fait se rencontrer les deux côtés

## Identifiant

`F-81 / SF-81-01`

## Feature parente

`F-81` — Le contrat runner ↔ gateway ne peut plus dériver en silence

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-81-01-module-contrat`

---

## Objectif

Créer un troisième module Maven `contract-tests`, dépendant du backend **et** du runner, dont les
tests sérialisent l'objet réel d'un côté et le font lire par le code réel de l'autre — de sorte
qu'un champ renommé d'un seul côté fasse **échouer la construction**, à la minute où il est renommé.

---

## Comportement attendu

### Cas nominal

Le module ne contient **aucun code de production** : uniquement `src/test/java`. Chaque test joue un
aller-retour réel entre les deux modules, dans **une même exécution de JVM** :

| Ce qui traverse | Émetteur (objet + mapper réels) | Récepteur (code réel) |
|---|---|---|
| Réponse d'appairage | backend `PairResponse` + `ObjectMapper` de l'auto-configuration Spring Boot | runner `PairingClient.pair(...)` → `StoredToken` |
| Requête d'appairage | runner `PairingClient` (corps réellement posté) | backend `PairRequest` + mapper du backend |
| Trame `ready` | runner `ToolDispatcher.readyFrame(...)` | backend `RunnerCallDispatcher.onFrame(..., "ready", ...)` |
| Trame `tool_call` | backend `RunnerCallDispatcher.call(...)` | runner `FrameRouter.route(...)` → `ToolDispatcher` |
| Trames `tool_stream` / `tool_result` | runner `ToolDispatcher` | backend `RunnerCallDispatcher` → `RunnerCallResult` |

Pour l'appairage, un **vrai serveur HTTP local** (`com.sun.net.httpserver`, JDK) sert la réponse
sérialisée par le backend et capture le corps posté par le runner : le `PairingClient` de production
est exercé tel quel, avec son propre `ObjectMapper`, sans qu'aucun champ soit recopié à la main.

Pour les trames, le canal d'émission du backend (`RunnerOutbound`) et le transport du runner
(`FrameTransport`) sont branchés **l'un sur l'autre** : ce que le backend écrit est exactement ce que
le runner lit, et réciproquement.

### Cas d'erreur — ce que le module doit détecter, et ce qu'il doit tolérer

| Situation | Comportement attendu |
|---|---|
| Un champ est **renommé d'un seul côté** (le défaut du 2026-09-10) | Au moins un test **échoue** |
| Un champ est **ajouté d'un seul côté** (gateway plus récente qu'un runner déployé) | Aucun test n'échoue — c'est la tolérance recherchée |
| Le backend n'est pas installé en jar de bibliothèque | Le module ne compile pas ; le script de lancement l'installe correctement |
| Le module est ajouté au dépôt | L'image de production **ne le construit pas** |

---

## Critères d'acceptation

- [ ] Un module Maven `contract-tests/` existe, dépend de `claude-gateway-backend` et de
      `claude-runner`, et ne contient **que** des tests (`src/test/java`, aucun `src/main`).
- [ ] Le test d'appairage fait lire au `StoredToken` du runner un JSON produit par la sérialisation
      d'un `PairResponse` du backend — **jamais une chaîne recopiée** — et vérifie que `token`,
      `hostId` et `expiresAt` arrivent tous les trois.
- [ ] Le test d'appairage fait lire au `PairRequest` du backend le corps **réellement posté** par le
      `PairingClient` du runner, et vérifie que `code`, `label`, `rootName`, `os` et `elevated`
      arrivent.
- [ ] La trame `ready` émise par le runner est aiguillée par le dispatcher réel du backend : les
      capacités déclarées et l'interpréteur déclaré sont bien reçus.
- [ ] Une trame `tool_call` émise par le backend est exécutée par l'aiguilleur réel du runner, et le
      `tool_result` qu'il renvoie redevient un `RunnerCallResult` porteur du contenu, du code de
      sortie et de la sortie diffusée.
- [ ] **Vérification dans les deux sens (bloquante)** : le renommage réel d'un champ d'un seul côté
      est joué, le test est vu **rouge**, le champ est remis, le test est vu **vert**. La trace est
      portée dans la PR. Un test de contrat jamais vu rouge ne prouve rien.
- [ ] Un champ **ajouté** d'un seul côté à la réponse d'appairage ne fait échouer aucun test.
- [ ] `backend/Dockerfile` est **inchangé** et ne construit pas le module.
- [ ] `cd backend && ./mvnw verify` et `cd runner && ./mvnw test` restent verts et inchangés.

---

## Périmètre

### Hors scope (explicite)

- **Changer le contenu du contrat** : F-81 le gèle et le surveille, elle ne le redessine pas.
- Versionner l'API du runner (`/v1/`, négociation de version).
- L'exigence `@JsonIgnoreProperties` sur tous les DTO d'échange → SF-81-02.
- Le journal de version de runner → SF-81-03.
- La mise à jour automatique du runner.
- Le câblage du module dans `.github/workflows/` (hors de portée de cette livraison — voir
  « Notes et décisions »).

---

## Technique

### Endpoints

Aucun. Le module n'expose rien et ne modifie aucun code de production.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Structure ajoutée

```
contract-tests/
  pom.xml                     parent spring-boot-starter-parent ; deps backend + runner + starter-test
  mvnw, mvnw.cmd, .mvn/       wrapper, copié à l'identique de runner/
  src/test/java/fr/claudegateway/contract/
      ContractMappers.java        le mapper RÉEL de chaque côté (émetteur / récepteur)
      PairingContractTest.java    réponse et requête d'appairage, dans les deux sens
      ReadyFrameContractTest.java trame d'ouverture
      ToolFramesContractTest.java tool_call → tool_stream → tool_result, boucle complète
scripts/contract-tests.sh     installe runner + backend, puis joue le module
```

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `PairingContractTest` — la réponse d'appairage du backend est lue par le runner : trois champs.
- [ ] `PairingContractTest` — la requête d'appairage du runner est lue par le backend : cinq champs.
- [ ] `PairingContractTest` — un champ inconnu ajouté à la réponse ne casse pas la lecture.
- [ ] `ReadyFrameContractTest` — capacités déclarées reçues (`bash` absent ⇒ outil refusé).
- [ ] `ReadyFrameContractTest` — interpréteur déclaré reçu par le backend.
- [ ] `ToolFramesContractTest` — `tool_call` : outil, entrée, projet et délai arrivent au runner.
- [ ] `ToolFramesContractTest` — `tool_result` : contenu, `exitCode`, `bytes` et flux reviennent.
- [ ] `ToolFramesContractTest` — une erreur d'outil revient avec son code du contrat §4.

### Tests d'intégration

Le module **est** le test d'intégration : il fait tourner le code de production des deux modules
ensemble. Aucun endpoint HTTP applicatif n'est monté (sauf le serveur local d'appairage, qui joue le
rôle de la gateway au niveau transport).

### Isolation utilisateur

- [x] Non applicable — aucune donnée, aucun accès base, aucun endpoint. Le module ne lit et n'écrit
      rien : il compare des formes de messages. L'isolation `user_id` reste vérifiée là où elle vit,
      dans les tests du backend.

---

## Dépendances

### Subfeatures bloquantes

- Aucune.

### Questions ouvertes impactées

- [x] `OQ-18` — **tranchée par le PO le 2026-09-12** : le troisième module. Écartés : la dépendance
      `test` du backend vers le runner, et les instantanés JSON versionnés.

---

## Notes et décisions

**D1 — Le module ne gonfle pas l'image de production, et c'est structurel.**
`backend/Dockerfile` ne copie **jamais** la racine du dépôt : il copie `runner/.mvn`, `runner/mvnw`,
`runner/pom.xml`, `runner/src/`, puis `backend/.mvn`, `backend/mvnw`, `backend/pom.xml`,
`backend/src/`. Un répertoire `contract-tests/` posé à la racine lui est donc **invisible**, sans
qu'aucune ligne du Dockerfile ne change. La condition à tenir est de **ne pas créer de POM réacteur
racine** : c'est lui qui ferait entrer le module dans la réaction en chaîne. Le module reste donc
autonome, comme le runner l'est déjà, et `cd backend && ./mvnw verify` est inchangé.

**D2 — Le backend est installé en jar de bibliothèque, pas en jar exécutable.**
`spring-boot-maven-plugin:repackage` remplace l'artefact principal par un fat-jar dont les classes
vivent sous `BOOT-INF/classes` : dépendre de cet artefact ne compile pas. Deux voies :
ajouter `<classifier>exec</classifier>` au plugin (documenté, mais il faudrait alors changer la ligne
`COPY --from=build /app/target/*.jar app.jar` du Dockerfile, qui verrait deux jars) ; ou **installer
le backend avec `-Dspring-boot.repackage.skip=true`** avant de jouer le module. **Retenu : la
seconde.** Elle ne touche ni le POM du backend, ni le Dockerfile, ni l'image — donc aucun risque sur
le chemin de production —, et le script `scripts/contract-tests.sh` la rend impossible à oublier.
Réversible : passer au classifier ne demanderait que deux lignes.

**D3 — Le câblage CI est signalé, pas fait.**
`.github/workflows/backend.yml` ne joue aujourd'hui que `cd backend && ./mvnw verify -q`, et son
déclenchement automatique est désactivé (`workflow_dispatch` seul). Le module doit donc y être ajouté
par une étape d'une ligne (`./scripts/contract-tests.sh`). Cette livraison n'a pas le droit de
modifier `.github/` : le manque est **tracé comme risque résiduel** dans la PR, et le script existe
pour que l'ajout soit trivial.

**D4 — « L'objet réel, jamais une chaîne recopiée ».**
C'est le point qui distingue ce module des quatre tests de SF-48-04. Une seule exception, assumée :
le test de **tolérance** ajoute un champ inconnu à un JSON par ailleurs produit par le backend — on
ne peut pas simuler une gateway plus récente sans écrire le champ qu'elle ajouterait. Le champ est
ajouté **sur l'arbre sérialisé du vrai objet**, jamais sur une chaîne écrite à la main.
