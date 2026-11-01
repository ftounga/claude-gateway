# Jarvis — le compagnon Android (F-232)

Un petit APK, installé hors store, qui écoute « Jarvis » **sur le téléphone**, même verrouillé, et
ouvre alors l'écran Jarvis de l'application (`/jarvis`, F-231).

- **Rien ne sort** : la reconnaissance du mot se fait sur l'appareil (même moteur que SF-231-05,
  porté en Java : empreinte MFCC, alignement DTW, trois gabarits appris). Le manifeste ne demande
  même pas la permission `INTERNET`.
- **Aucun jeton** : l'écran Jarvis s'ouvre dans le navigateur (ou la PWA installée), avec la session
  habituelle.
- **Interrupteur** dans la notification (Pause / Reprendre / Quitter) ; pause automatique sous 15 %
  de batterie hors charge, reprise à 20 % ou en charge.

## État

| Étape | État |
|---|---|
| Sources (Java 17, minSdk 26, targetSdk 34) | versionnées |
| Cœur pur (`core/`) testé | `./gradlew :app:testDebugUnitTest` (ou `./test-core.sh` sans SDK) — vert |
| Compilation Gradle, APK | **faite** le 2026-11-01 (SF-232-04) : wrapper Gradle 8.7 versionné, SDK 34 |
| Signature | **faite** : v1 + v2, clé de release dans AWS Secrets Manager (voir plus bas) |
| Empaquetage dans l'image backend | étage Docker `jarvis-build` (SF-232-04) |
| Essai sur téléphone | à faire par le PO (aucun appareil branché sur la machine de livraison) |

## Construire

Prérequis : JDK 17+, SDK Android (platform 34, build-tools 34). Gradle est fourni par le wrapper
(`./gradlew`, version 8.7, somme SHA-256 de la distribution épinglée).

```bash
cd android/jarvis-companion
export ANDROID_HOME=$HOME/Android/Sdk     # ou un local.properties « sdk.dir=… » (non versionné)
./gradlew :app:testDebugUnitTest          # mêmes tests que ./test-core.sh
./gradlew :app:assembleDebug              # APK de debug, signé par la clé de debug locale
```

### Version

Une seule source : `jarvisVersionCode` / `jarvisVersionName` dans `gradle.properties`. **Incrémenter
`jarvisVersionCode` à chaque APK publié** : Android refuse d'installer une version plus ancienne, et la
gateway ne propose la mise à jour que si son `versionCode` dépasse celui du compagnon installé.

### APK publiable (signé v1 + v2)

```bash
./build-release.sh <keystore> <fichier-env-signature> <dossier-de-sortie>
```

Le fichier d'environnement contient `JARVIS_SIGNING_STORE_PASSWORD=…`, `JARVIS_SIGNING_KEY_ALIAS=…`,
`JARVIS_SIGNING_KEY_PASSWORD=…`. Le script lance les tests, construit, **vérifie v2 (`apksigner`) et v1
(`jarsigner`, le mécanisme même de la gateway)**, puis dépose `jarvis-compagnon.apk` et
`version.properties` (lu par la gateway).

### La clé de release

| | |
|---|---|
| Où | AWS Secrets Manager, région `eu-west-3`, secret **`claude-gateway/jarvis-companion-signing`** (profil `legalcase-terraform`) |
| Contenu (JSON) | `keystore_base64` (PKCS12), `store_password`, `key_alias` (`jarvis`), `key_password`, `store_type` |
| Certificat | `CN=Jarvis compagnon, O=NG IT Consulting, C=FR`, RSA 4096, valide jusqu'en 2054 |
| Empreinte SHA-256 | `48:DA:5A:D8:97:99:0C:88:3F:BE:45:78:E5:3E:33:40:E9:B0:70:BA:9B:01:A1:B3:AB:96:C0:1A:BF:0F:40:A6` |

Le keystore **n'entre jamais dans le dépôt** (`*.jks`, `*.keystore`, `*.p12` ignorés) ni dans une couche
d'image. **Perdre la clé interdit toute mise à jour** : Android refuse un APK signé par une autre clé (il
faudrait désinstaller). Ne jamais supprimer le secret.

Extraction locale (dossier temporaire, effacé ensuite) :

```bash
export AWS_PROFILE=legalcase-terraform
K=$(mktemp -d); chmod 700 "$K"
aws secretsmanager get-secret-value --region eu-west-3 --secret-id claude-gateway/jarvis-companion-signing \
  --query SecretString --output text > "$K/s.json"
python3 -I -c 'import base64,json,sys; s=json.load(open(sys.argv[1])); d=sys.argv[2]
open(d+"/jarvis.p12","wb").write(base64.b64decode(s["keystore_base64"]))
open(d+"/signing.env","w").write("JARVIS_SIGNING_STORE_PASSWORD=%s\nJARVIS_SIGNING_KEY_ALIAS=%s\nJARVIS_SIGNING_KEY_PASSWORD=%s\n" % (s["store_password"], s["key_alias"], s["key_password"]))' "$K/s.json" "$K"
./build-release.sh "$K/jarvis.p12" "$K/signing.env" ./dist; rm -rf "$K"
```

### Dans l'image backend

L'étage `jarvis-build` de `backend/Dockerfile` construit et signe l'APK, puis l'étage final le copie dans
`/app/jarvis/` (`APP_JARVIS_COMPANION_APK_PATH=/app/jarvis/jarvis-compagnon.apk` posé par l'image). La clé
arrive par deux **secrets BuildKit**, montés le temps d'une instruction :

```bash
docker build --build-arg JARVIS_SIGNING=on \
  --secret id=jarvis_keystore,src=<keystore PKCS12> \
  --secret id=jarvis_signing_env,src=<fichier env de signature> \
  -f backend/Dockerfile .
```

Sans ces secrets (et sans `JARVIS_SIGNING=on`), l'image se construit **sans compagnon** (avertissement) ;
avec `JARVIS_SIGNING=on` et sans secrets, la construction **échoue**. Le script de déploiement extrait le
secret dans un dossier temporaire, comme il le fait pour la clé du runner.

## Servir l'APK depuis la gateway (SF-232-02)

La gateway sert l'APK par `GET /api/runner/download/android`, à côté des paquets du runner (F-44),
**seulement si sa signature v1 est faite par le certificat attendu** (l'APK est signé v1 et v2 ; la
gateway vérifie v1 avec le seul JDK). L'écran Jarvis propose l'installation sur Android, puis la mise
à jour quand le compagnon qui l'ouvre (`?compagnon=<versionCode>`) est plus ancien.

Variables du backend (aucun secret : l'empreinte d'un certificat est publique) :

| Variable | Valeur |
|---|---|
| `APP_JARVIS_COMPANION_APK_PATH` | posé par l'image : `/app/jarvis/jarvis-compagnon.apk` |
| `APP_JARVIS_COMPANION_VERSION_CODE` | facultatif : absent, la gateway lit `version.properties` à côté de l'APK (SF-232-04) ; posé, il prime |
| `APP_JARVIS_COMPANION_VERSION_NAME` | facultatif, idem |
| `APP_JARVIS_COMPANION_SIGNING_CERT_SHA256` | l'empreinte ci-dessus — posée dans le ConfigMap `backend-config` de prod |

Tant que l'empreinte n'est pas posée ou que l'APK est absent de l'image, la gateway ne sert rien et
l'écran n'affiche aucun lien.

## Essai sur téléphone (à faire une fois construit)

1. Installer l'APK (« sources inconnues » autorisées pour Chrome ou le gestionnaire de fichiers).
2. Ouvrir « Jarvis » : autoriser micro + notifications, l'exemption de batterie, l'ouverture plein
   écran (Android 14+).
3. Apprendre « Jarvis » (trois fois), démarrer l'écoute : la notification « Jarvis écoute » apparaît.
4. Verrouiller, dire « Jarvis » : l'écran s'allume, le déverrouillage est demandé, puis `/jarvis`
   s'ouvre.
5. Éteindre l'écran : l'écoute reprend. Toucher « Pause » : la notification passe à « En pause ».
6. Redémarrer le téléphone : la notification « Réactiver Jarvis » apparaît (Android 14+ interdit de
   relancer un micro au démarrage sans geste).
