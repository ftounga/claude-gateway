#!/usr/bin/env sh
# F-232 / SF-232-04 — Construit l'APK de release SIGNÉ (v1 + v2) du compagnon Jarvis et le dépose,
# avec sa version, dans un dossier de sortie.
#
#   ./build-release.sh <keystore> <fichier-env-signature> <dossier-de-sortie>
#
# <fichier-env-signature> : lignes JARVIS_SIGNING_STORE_PASSWORD=…, JARVIS_SIGNING_KEY_ALIAS=…,
# JARVIS_SIGNING_KEY_PASSWORD=… (valeurs alphanumériques). Ni la clé ni ce fichier n'entrent dans le
# dépôt : ils viennent d'AWS Secrets Manager (claude-gateway/jarvis-companion-signing), par la
# construction Docker (secrets BuildKit) ou par une extraction locale dans un dossier temporaire.
#
# Sortie : jarvis-compagnon.apk + version.properties (versionCode, versionName), lus par la gateway
# (SF-232-02 / SF-232-04). La signature est vérifiée (apksigner) avant de rendre la main : une
# sortie sans v1 ET v2 valides fait échouer la construction.
set -eu

KEYSTORE=${1:?keystore}
SIGNING_ENV=${2:?fichier env de signature}
OUT=${3:?dossier de sortie}

HERE=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_HOME:?ANDROID_HOME doit pointer le SDK Android}"

set -a
. "$SIGNING_ENV"
set +a
JARVIS_SIGNING_STORE_FILE=$KEYSTORE
export JARVIS_SIGNING_STORE_FILE

cd "$HERE"
./gradlew --no-daemon -q :app:testDebugUnitTest :app:assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$APK" ]; then
    echo "ERREUR : $APK absent (APK non signé ? variables de signature manquantes)" >&2
    exit 1
fi

APKSIGNER=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort | tail -n 1)
# v2 : apksigner. v1 : jarsigner, le mécanisme même de la gateway (java.util.jar) — apksigner ne
# vérifie pas v1 quand minSdk >= 24, même si elle est présente.
"$APKSIGNER" verify --verbose "$APK" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" \
    || { echo "ERREUR : signature v2 absente" >&2; exit 1; }
jarsigner -verify "$APK" 2>&1 | grep -q "^jar verified\.$" \
    || { echo "ERREUR : signature v1 absente ou invalide — la gateway refuserait de servir l'APK" >&2; exit 1; }

mkdir -p "$OUT"
cp "$APK" "$OUT/jarvis-compagnon.apk"
# La version publiée vient de gradle.properties (jarvisVersionCode / jarvisVersionName) : une seule
# source, lue à la fois par Gradle et par la gateway.
{
    grep '^jarvisVersionCode=' gradle.properties | sed 's/^jarvisVersionCode=/versionCode=/'
    grep '^jarvisVersionName=' gradle.properties | sed 's/^jarvisVersionName=/versionName=/'
} > "$OUT/version.properties"
"$APKSIGNER" verify --print-certs "$APK" | grep 'SHA-256 digest' | head -n 1
cat "$OUT/version.properties"
