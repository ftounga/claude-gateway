# Mini-spec — F-232 / SF-232-04 — Construire, signer et empaqueter le compagnon

## Identifiant

`F-232 / SF-232-04`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (cadrage `CADRAGE-F-232-compagnon-android.md`, D2)

## Statut

`in-review`

## Date de création

2026-11-01

## Branche Git

`feat/SF-232-04-construire-signer-compagnon`

---

## Objectif

> L'APK du compagnon est réellement **compilé, testé et signé (v1 + v2)** par une construction
> reproductible, et l'image backend l'embarque avec sa version, pour que SF-232-02 le serve en prod.

Subfeature ajoutée au découpage le 2026-11-01 : SF-232-01/02 ont été livrées sans qu'aucun APK n'ait
jamais été construit (aucun SDK Android sur la machine de livraison). Sans elle, rien n'est installable.

---

## Comportement attendu

### Cas nominal

1. **Wrapper Gradle versionné** (`android/jarvis-companion/gradlew`, Gradle 8.7, somme SHA-256 de la
   distribution épinglée) : `./gradlew :app:testDebugUnitTest :app:assembleRelease` passe.
2. **Version unique** : `jarvisVersionCode` / `jarvisVersionName` dans `android/jarvis-companion/gradle.properties`,
   lus par Gradle **et** par la gateway.
3. **`build-release.sh <keystore> <env-signature> <sortie>`** : tests, `assembleRelease` signé, vérification
   v2 (`apksigner`) **et** v1 (`jarsigner`, le mécanisme même de la gateway), puis dépose
   `jarvis-compagnon.apk` + `version.properties` (`versionCode`, `versionName`).
4. **Clé** : keystore PKCS12 RSA 4096 créé **hors du dépôt**, rangé dans AWS Secrets Manager
   `claude-gateway/jarvis-companion-signing` (JSON : `keystore_base64`, `store_password`, `key_alias`,
   `key_password`, `store_type`). Jamais dans le dépôt ni dans une couche d'image.
5. **Image backend** : étage Docker `jarvis-build` (JDK 17, cmdline-tools Android épinglés par SHA-256,
   platform 34, build-tools 34) qui appelle `build-release.sh` avec deux **secrets BuildKit**
   (`jarvis_keystore`, `jarvis_signing_env`) montés le temps d'une instruction. L'étage final copie
   `/app/jarvis/` et pose `APP_JARVIS_COMPANION_APK_PATH=/app/jarvis/jarvis-compagnon.apk`.
6. **Version lue par la gateway** : si `APP_JARVIS_COMPANION_VERSION_CODE` n'est pas posée (0), la gateway
   lit `version.properties` à côté de l'APK. Une variable posée prime.
7. **Empreinte** du certificat posée dans le ConfigMap `backend-config` (non secrète) :
   `APP_JARVIS_COMPANION_SIGNING_CERT_SHA256`.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Construction Docker sans les secrets de signature | Avertissement, **aucun APK** dans l'image ; la construction réussit (le compagnon est optionnel, à l'inverse du runner) ; la gateway ne sert rien | — |
| APK construit sans v1 ou sans v2 valide | `build-release.sh` échoue ⇒ la construction Docker échoue | — |
| `version.properties` absent, illisible ou `versionCode` < 1, sans variable | Rien n'est servi (`available: false`), comme avant | 200 / 404 |
| Variable `VERSION_CODE` posée et fichier présent | La variable prime | — |

---

## Critères d'acceptation

- [x] `./gradlew :app:testDebugUnitTest :app:assembleRelease` passe sur la machine de livraison (SDK 34).
- [x] L'APK de release est signé v1 + v2 par la clé de Secrets Manager ; empreinte publiée dans le README.
- [x] Aucun keystore, mot de passe ni fichier de signature dans le dépôt (`.gitignore` + revue du diff).
- [x] La gateway sert un APK dont la version vient de `version.properties` quand la variable est absente.
- [x] La variable `APP_JARVIS_COMPANION_VERSION_CODE` prime sur le fichier.
- [x] L'étage Docker `jarvis-build` produit l'APK signé avec les secrets, et rien (sans échec) sans eux.
- [x] README : construire, signer, publier, empreinte, où est la clé, comment l'image le reçoit.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| `version.properties` → `versionCode` | entier ≥ 1, sinon rien n'est servi |
| `version.properties` → `versionName` | texte ≤ 40 caractères ; absent ⇒ `versionCode` affiché |
| `jarvisVersionCode` | strictement croissant à chaque APK publié (Android refuse une mise à jour plus ancienne) |

---

## Plan de test minimal

- **Unitaires** (`JarvisCompanionApkTest`) : version lue depuis `version.properties` ; variable prioritaire ;
  fichier absent ou `versionCode` invalide ⇒ rien servi.
- **Intégration** : tests existants SF-232-02 inchangés et verts ; vérification manuelle de l'APK réel
  construit (`JarvisCompanionApk.current()` sur l'APK signé par la vraie clé).
- **Android** : `./gradlew :app:testDebugUnitTest` (cœur pur) + `assembleRelease` ; `apksigner` / `jarsigner`.
- **Docker** : `docker build --target jarvis-build` avec et sans secrets.
- **Isolation utilisateur** : sans objet (binaire public, aucune donnée utilisateur).

---

## Impacts

- **Tables / migrations** : aucune. **Endpoints** : aucun nouveau.
- **Backend** : `JarvisCompanionApk` (lecture de `version.properties`).
- **Build** : `backend/Dockerfile` (étage `jarvis-build`, copie, `ENV`), `android/jarvis-companion/`
  (wrapper, `gradle.properties`, `build-release.sh`, README).
- **Ops** : secret `claude-gateway/jarvis-companion-signing` ; ConfigMap `backend-config` (empreinte) ;
  `deploy2.sh` doit passer les deux secrets BuildKit (changement proposé, non appliqué ici).
- **Préoccupations transversales** : aucune (ni auth, ni tenant, ni plans, ni navigation).

---

## Hors périmètre

- Déployer l'image (fait par le PO).
- Modifier `deploy2.sh` (hors dépôt) : le changement exact est donné dans la PR et le README.
- Annonces, appairage, jeton (SF-232-03).
