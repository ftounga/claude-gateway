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
| Cœur pur (`core/`) testé hors SDK | `./test-core.sh` — vert |
| Compilation Gradle, APK | **jamais faite** : aucun SDK Android sur la machine de livraison ni en CI |
| Signature | **jamais faite** : aucun keystore |
| Essai sur téléphone | **jamais fait** |

## Construire

Prérequis : JDK 17+, SDK Android (platform 34, build-tools 34), Gradle 8.7+.

```bash
cd android/jarvis-companion
gradle wrapper --gradle-version 8.7     # une fois, pour générer ./gradlew (non versionné à ce jour)
./gradlew :app:testDebugUnitTest        # mêmes tests que ./test-core.sh
./gradlew :app:assembleDebug            # APK de debug, signé par la clé de debug locale
```

APK publiable (signé) :

```bash
export JARVIS_SIGNING_STORE_FILE=/chemin/hors/depot/jarvis.jks
export JARVIS_SIGNING_STORE_PASSWORD=…  JARVIS_SIGNING_KEY_ALIAS=jarvis  JARVIS_SIGNING_KEY_PASSWORD=…
./gradlew :app:assembleRelease -PjarvisVersionCode=2 -PjarvisVersionName=0.2.0
```

Le keystore **ne doit jamais entrer dans le dépôt** (`*.jks`, `*.keystore` ignorés). Perdre la clé
interdit toute mise à jour : Android refuse un APK signé par une autre clé (il faudrait désinstaller).

## Servir l'APK depuis la gateway (SF-232-02)

La gateway sert l'APK par `GET /api/runner/download/android`, à côté des paquets du runner (F-44),
**seulement si sa signature v1 est faite par le certificat attendu** (l'APK est signé v1 et v2 ; la
gateway vérifie v1 avec le seul JDK). L'écran Jarvis propose l'installation sur Android, puis la mise
à jour quand le compagnon qui l'ouvre (`?compagnon=<versionCode>`) est plus ancien.

Variables du backend (aucun secret : l'empreinte d'un certificat est publique) :

| Variable | Valeur |
|---|---|
| `APP_JARVIS_COMPANION_APK_PATH` | chemin de l'APK dans l'image, ex. `/app/jarvis-compagnon.apk` |
| `APP_JARVIS_COMPANION_VERSION_CODE` | le `jarvisVersionCode` de la construction |
| `APP_JARVIS_COMPANION_VERSION_NAME` | le `jarvisVersionName` |
| `APP_JARVIS_COMPANION_SIGNING_CERT_SHA256` | `keytool -list -v -keystore jarvis.jks -alias jarvis` → ligne « SHA256 » |

Tant que ces variables ne sont pas posées et l'APK absent de l'image, la gateway ne sert rien et
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
