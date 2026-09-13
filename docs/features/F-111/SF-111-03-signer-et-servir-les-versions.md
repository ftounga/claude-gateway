# Mini-spec — [F-111 / SF-111-03] Signer et servir les versions

## Identifiant

`F-111 / SF-111-03`

## Feature parente

`F-111` — Le runner se met à jour d'un clic (cadrage : `CADRAGE-F-111-le-runner-se-met-a-jour.md`)

## Statut

`done` — PR #568 mergée le 2026-09-13

## Date de création

2026-09-13

## Branche Git

`feat/SF-111-03-signer-et-servir`

---

## Objectif

L'image backend **signe** le runner à la construction (Ed25519, clé privée fournie par un secret de build
Docker) et publie `runner.jar`, son empreinte, sa signature et un manifeste ; la gateway les **sert** sous
`GET /runner/update/**` ; le runner sait **télécharger par son propre client HTTP, vérifier empreinte et
signature avec la clé publique embarquée, puis seulement installer** une version dans `versions/`.

---

## Comportement attendu

### Cas nominal

1. **Construction** (`backend/Dockerfile`, étage `runner-build`, juste après `mvnw package`) :
   `RUN --mount=type=secret,id=runner_signing_key` exécute `runner/build-tools/SignRunnerUpdate.java`
   (programme à fichier unique, hors du runner livré) :
   - lit `runner-build.properties` **dans le jar** (identifiant, contrat, Java minimal) ;
   - écrit `target/update/runner.jar` (copie à l'octet de `claude-runner.jar`), `runner.jar.sha256`
     (hexadécimal), `runner.jar.sig` (signature Ed25519 du jar, Base64) et `runner-manifest.json`
     (`id`, `version`, `commit`, `builtAt`, `contract`, `minJava`, `sha256`, `size`, `signed`, `signedAt`,
     `notes` lues dans `runner/release-notes.txt`, 10 lignes au plus) ;
   - **vérifie la signature avec `update-signing-public-key.pem`** : une clé privée qui ne correspond pas
     à la clé embarquée arrête la construction.
   - `ARG REQUIRE_RUNNER_SIGNATURE=true` (défaut) : **secret absent ou vide → la construction échoue**
     avec un message qui donne la commande. `false` (constructions locales explicites) : jar, empreinte et
     manifeste `signed: false`, **sans signature**, avec un avertissement.
   - L'étage final copie `target/update/` vers `/app/runner-update/`.
2. **Service** (`RunnerUpdateArtifacts`, lu une fois au démarrage) : dossier `app.runner.update-dir`, par
   défaut `<dossier de app.runner.jar-path>/runner-update`. Le manifeste n'est retenu que si l'empreinte
   du jar **recalculée** égale celle du manifeste. Routes **publiques** (même accès que
   `/runner/download`, chaîne `/runner/**`) :

   | Route | Réponse |
   |---|---|
   | `GET /runner/update/manifest` | le manifeste (JSON) |
   | `GET /runner/update/{version}` | le jar, si `{version}` = `id` du manifeste **et** la version est signée |
   | `GET /runner/update/{version}/sha256` | l'empreinte (texte) |
   | `GET /runner/update/{version}/signature` | la signature Base64 (texte) |

3. **Vérification côté runner** (`update.UpdateVerifier`) : empreinte SHA-256 du fichier téléchargé =
   empreinte servie (et = empreinte attendue par la commande quand elle est fournie) ; **signature Ed25519
   valide avec la clé publique embarquée** (`KeyFactory`/`Signature` « Ed25519 ») ; identifiant lu dans
   `runner-build.properties` **du jar téléchargé** = identifiant demandé (un jar ancien correctement signé
   ne peut pas se faire passer pour une version récente).
4. **Téléchargement et installation** (`update.UpdateInstaller`) : les trois
   ressources sont demandées **par le client HTTP du runner** (proxy, `px`, magasin de confiance d'entreprise
   F-80), le jar en mémoire (64 Mo au plus) ; **aucune écriture dans `versions/` avant la vérification** ;
   puis `LauncherHome.install`.
5. **Conseil** : `RunnerUpdateView.notes` reprend les notes du manifeste de la version servie ;
   `updatable` (nouveau champ) dit si une version **signée** est servie — le bouton de SF-111-04 en dépend.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Construction avec `REQUIRE_RUNNER_SIGNATURE=true` sans secret | échec de `docker build`, message nommant `--secret id=runner_signing_key` | — |
| Clé privée ≠ clé publique embarquée | échec de `docker build` | — |
| Jar sans `runner-build.properties` filtré | échec du programme de signature | — |
| Aucun artefact, manifeste illisible, empreinte du jar ≠ manifeste | routes `404 runner_update_unavailable` | 404 |
| `{version}` ≠ version servie, ou version non signée | `404 runner_update_unavailable` | 404 |
| Empreinte différente au téléchargement | refus `sha256_mismatch`, rien d'écrit | — |
| Signature absente / invalide (proxy qui modifie, gateway compromise) | refus `signature_invalid`, rien d'écrit | — |
| Jar signé d'une autre version | refus `version_mismatch`, rien d'écrit | — |
| Jar plus gros que 64 Mo, réponse HTTP ≠ 200 | refus `download_failed`, rien d'écrit | — |

---

## Critères d'acceptation

- [ ] CA1 — `docker build` (défaut) sans secret échoue avec un message clair ; avec `--build-arg REQUIRE_RUNNER_SIGNATURE=false`, l'image se construit et contient `/app/runner-update/{runner.jar,runner.jar.sha256,runner-manifest.json}`.
- [ ] CA2 — Le programme de signature produit une signature que `UpdateVerifier` accepte avec la clé publique correspondante, et **échoue** si la clé privée ne correspond pas à la clé publique fournie (test réel du programme).
- [ ] CA3 — Les 4 routes servent manifeste, jar, empreinte, signature pour la version signée ; 404 sinon ; accessibles sans authentification.
- [ ] CA4 — `UpdateVerifier` refuse empreinte fausse, signature fausse ou absente, jar d'une autre version ; accepte le bon (paires de clés de test générées à la volée).
- [ ] CA5 — `UpdateInstaller` télécharge par le client HTTP fourni, n'écrit **rien** dans `versions/` sur refus, installe sur succès.
- [ ] CA6 — La clé publique de production embarquée est lisible (Ed25519) et inchangée.
- [ ] CA7 — `runnerUpdate.notes` et `runnerUpdate.updatable` rendus dans la vue d'ensemble.

---

## Périmètre

### Hors scope (explicite)

- La commande `update`, le bouton, l'attente du calme (SF-111-04) ; santé et retour arrière (SF-111-05).
- **Le script de déploiement et toute ressource AWS** : la ligne `docker build --secret id=runner_signing_key,src=<fichier>` est documentée, pas appliquée.
- La rotation de clé (une seule clé publique embarquée).

---

## Valeurs initiales

Aucune entité créée.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `{version}` (route) | Oui | 64 | identifiant de version F-111 ; comparé à l'identifiant servi | — | — |
| jar téléchargé | Oui | 64 Mo | jar contenant `runner-build.properties` | — | — |
| signature | Oui | — | Base64 d'une signature Ed25519 (64 octets) | — | trim |
| empreinte | Oui | 64 | hexadécimal SHA-256 | — | trim, casse ignorée |
| notes | Non | 10 lignes × 200 car. | texte | — | trim, lignes `#` ignorées |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/runner/update/manifest` | Non (public, comme `/runner/download`) | — |
| GET | `/api/runner/update/{version}` | Non | — |
| GET | `/api/runner/update/{version}/sha256` | Non | — |
| GET | `/api/runner/update/{version}/signature` | Non | — |

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants

- Construction : `runner/build-tools/SignRunnerUpdate.java`, `runner/release-notes.txt`, `backend/Dockerfile`.
- Backend : `RunnerUpdateArtifacts`, `RunnerUpdateController`, `RunnerSecurityConfig`, `RunnerUpdateAdvisor`, `RunnerUpdateView`, `application.yml` (`app.runner.update-dir`).
- Runner : `update.UpdateVerifier`, `update.UpdateInstaller` (téléchargement compris), `update.UpdateRejectedException`.
- Frontend : modèle `RunnerUpdateView` (`notes`, `updatable`) ; notes affichées en infobulle de la pastille.

### Préoccupations transversales

- **Auth / Principal : oui** — 4 routes publiques ajoutées à la chaîne `/runner/**`. Composants impactés : `RunnerSecurityConfig` (liste explicite, pas de joker `/runner/update/**`), `RunnerDownloadController` (inchangé), routes runner authentifiées par jeton (`/runner/poll|send|disconnect|teams/**|radar/**`) inchangées ; test de non-régression : une route voisine non listée reste refusée.
- Tenant : non (artefact public, sans donnée utilisateur). Plans : non. Navigation : non.

---

## Plan de test

### Tests unitaires

- [ ] runner `UpdateVerifierTest` — accepte ; refuse empreinte, signature, signature d'une autre clé, version, jar illisible ; clé embarquée lisible.
- [ ] runner `UpdateInstallerTest` — serveur HTTP local : succès → installé ; refus → `versions/` vide ; 404 et taille excessive refusés.
- [ ] runner `SignRunnerUpdateToolTest` — le **vrai** programme `java build-tools/SignRunnerUpdate.java` : sign (clés générées), clé discordante → échec, unsigned → pas de `.sig`.
- [ ] backend `RunnerUpdateArtifactsTest` — manifeste retenu, empreinte discordante écartée, version non signée non servie.
- [ ] backend `RunnerUpdateAdvisorTest` — `notes`, `updatable`.

### Tests d'intégration

- [ ] backend `RunnerUpdateApiIntegrationTest` — 4 routes sans authentification (200/404) ; une route voisine non listée refusée.
- [ ] Docker : construction `REQUIRE_RUNNER_SIGNATURE=false` complète ; construction par défaut sans secret → échec.

### Isolation workspace

- [x] Non applicable — artefact public, aucune donnée d'utilisateur.

---

## Dépendances

### Subfeatures bloquantes

- SF-111-01 (identifiant, `runner-build.properties`) — done ; SF-111-02 (`LauncherHome`) — done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Signature du jar entier**, identifiant **relu dans le jar signé** : pas de signature séparée du
  manifeste à gérer, et aucune rétrogradation possible par un manifeste mensonger.
- **D2 — Programme Java à fichier unique** plutôt qu'`openssl` : même JDK que la construction, pas de
  dépendance à la version d'OpenSSL de l'image, et vérification par la clé embarquée au même endroit.
- **D3 — Routes explicites** dans la chaîne de sécurité, comme `/runner/download/*` : un joker ouvrirait
  d'avance toute future route sous `/runner/update`.
- **D4 — Pas de signature, pas de jar servi** : une image construite sans signature publie le manifeste
  (information) mais aucune version installable.
