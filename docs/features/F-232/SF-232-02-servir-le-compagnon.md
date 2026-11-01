# Mini-spec — F-232 / SF-232-02 — Servir le compagnon : téléchargement, signature, mise à jour

## Identifiant

`F-232 / SF-232-02`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (cadrage `CADRAGE-F-232-compagnon-android.md`, D1c, D2)

## Statut

`in-review`

## Date de création

2026-11-01

## Branche Git

`feat/SF-232-02-servir-compagnon`

---

## Objectif

> La gateway sert l'APK du compagnon **seulement s'il porte la bonne signature**, par le même chemin
> que les paquets du runner (F-44), et l'écran Jarvis le propose : installation sur Android, mise à
> jour quand le compagnon qui l'a ouvert est plus ancien.

---

## Comportement attendu

### Cas nominal

| Route (publique, chaîne `/runner/**`) | Rend |
|---|---|
| `GET /runner/download/android` | l'APK `jarvis-compagnon.apk`, type `application/vnd.android.package-archive` |
| `GET /runner/download/android/version` | `{ available, versionCode, versionName, sha256, signingCertSha256, sizeBytes }` |

**Signature vérifiée par la gateway (D2)** : l'APK n'est servi que si sa signature v1 (JAR) est
valide **et** faite par le certificat dont l'empreinte SHA-256 est configurée
(`app.jarvis.companion.signing-cert-sha256`). Le contrôle porte sur chaque entrée hors `META-INF/` ; le
résultat est mis en cache par (taille, date de modification) du fichier. L'APK est construit avec les
signatures v1 **et** v2 (`build.gradle`) : v2 pour Android, v1 pour que la gateway la vérifie avec le
seul JDK.

**Configuration** (aucun secret ; l'empreinte d'un certificat est publique) :
`APP_JARVIS_COMPANION_APK_PATH`, `APP_JARVIS_COMPANION_VERSION_CODE`,
`APP_JARVIS_COMPANION_VERSION_NAME`, `APP_JARVIS_COMPANION_SIGNING_CERT_SHA256`.

**Écran Jarvis** :
- Sur Android (agent utilisateur), si le compagnon est disponible et que l'écran n'a pas été ouvert par
  lui : un lien discret « Jarvis téléphone verrouillé : installer le compagnon Android (v…) ».
- Ouvert par le compagnon (`?source=compagnon&compagnon=<versionCode>`) : si une version plus récente
  est servie, « Mise à jour du compagnon disponible (v…) » avec le lien ; Android confirme
  l'installation (D1c). Le compagnon passe sa version dans l'URL : il n'a **toujours pas besoin de la
  permission `INTERNET`**.
- Ouvert par le compagnon : Jarvis **écoute aussitôt** (le PO vient de dire « Jarvis ») et le réveil est
  compté dans la mesure D9 (`WAKE`), comme le mot d'éveil de l'écran.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Chemin non configuré / fichier absent ou illisible | `available: false` ; téléchargement → `jarvis_companion_unavailable` | 200 / 404 |
| Empreinte attendue non configurée | Rien n'est servi (`available: false`, 404) — jamais d'APK non vérifié | 200 / 404 |
| APK non signé, signé v2 seulement, ou par un autre certificat | Rien n'est servi, avertissement journalisé (sans contenu) | 200 / 404 |
| APK altéré (entrée modifiée après signature) | Rien n'est servi | 200 / 404 |
| `/version` indisponible côté écran | Le lien n'apparaît pas ; Jarvis fonctionne comme avant | — |

---

## Critères d'acceptation

- [x] Les deux routes sont publiques, déclarées une par une dans `RunnerSecurityConfig` (pas de joker).
- [x] Un APK signé par le certificat attendu est servi avec le bon nom et le bon type ; `/version` rend version, SHA-256 du fichier, empreinte et taille.
- [x] Un APK signé par un autre certificat, non signé ou altéré n'est **jamais** servi.
- [x] Sans empreinte configurée, rien n'est servi.
- [x] Les routes du runner (`/runner/download`, `/windows`, `/macos-*`, `/formats`) sont inchangées.
- [x] L'écran Jarvis propose l'installation sur Android et la mise à jour quand `compagnon` < `versionCode` ; rien sinon.
- [x] Ouvert par le compagnon, Jarvis écoute et compte un `WAKE`.
- [x] Le compagnon ajoute `compagnon=<versionCode>` à l'URL qu'il ouvre ; toujours aucune permission `INTERNET`.
- [x] Design system : jetons `--cg-*`, Angular Material, pas de `window.confirm`.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| `signing-cert-sha256` | 64 caractères hexadécimaux ; deux-points et casse ignorés (format `keytool` accepté) |
| `compagnon` (URL) | entier ≥ 1, sinon ignoré |

---

## Plan de test minimal

- **Unitaires** (`JarvisCompanionApkTest`) : APK signé par le bon certificat ⇒ vérifié ; autre certificat ⇒ refusé ; non signé ⇒ refusé ; altéré ⇒ refusé ; empreinte absente ⇒ refusé ; format `AA:BB:…` accepté ; cache relu après modification du fichier. Les APK de test sont des archives signées par `keytool` + `jarsigner` du JDK courant.
- **Intégration** (`JarvisCompanionDownloadApiIntegrationTest`) : `/version` et `/android` publics (sans JWT), 200 + octets + en-têtes quand vérifié ; 404 `jarvis_companion_unavailable` + `available:false` sans configuration.
- **Frontend** (`jarvis.component.spec.ts`) : lien d'installation sur Android ; mise à jour quand la version du compagnon est plus ancienne ; rien quand elle est à jour ; écoute et `WAKE` à l'ouverture par le compagnon.
- **Isolation utilisateur** : sans objet — binaire public sans donnée utilisateur, comme les paquets runner F-44.

---

## Impacts

- **Tables** : aucune. **Migration** : aucune.
- **Endpoints** : `GET /runner/download/android`, `GET /runner/download/android/version` (nouveaux).
- **Frontend** : `JarvisComponent` (lien + mise à jour + écoute à l'arrivée), `JarvisService.companion()`, modèle `JarvisCompanionVersion`.
- **Android** : `JarvisLink.jarvisUrl(gateway, versionCode)`, `build.gradle` (signatures v1 + v2).
- **Préoccupations transversales** :
  - **Auth** : deux routes publiques ajoutées à la chaîne `/runner/**` ; composants vérifiés : `RunnerSecurityConfig` (déclarations unitaires, pas de joker), chaîne principale inchangée ; test d'intégration sans JWT.
  - **Navigation** : `/jarvis` lit désormais `source` et `compagnon` en plus de `sujet` et `alerte` ; les liens existants (alertes de veille `/jarvis?alerte=…`, manifeste `start_url /jarvis`) ne portent pas `source` ⇒ comportement inchangé (vérifié par les tests existants).

---

## Hors périmètre

- Construire et signer l'APK (aucun SDK ni keystore ici — voir `android/jarvis-companion/README.md`).
- Empaqueter l'APK dans l'image backend (à faire quand un APK signé existera : `COPY` + variables du configmap).
- Annonces téléphone verrouillé et jeton dédié (SF-232-03).
