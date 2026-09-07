#!/bin/sh
# Construit le paquet autonome macOS du runner (F-44, extension de SF-44-01 à macOS).
#
# Même principe que package-windows.sh : `jlink` sait cibler une AUTRE plateforme si on lui donne
# les `jmods` correspondants. Le runtime macOS se construit donc depuis Linux, sans Mac ni matrice
# d'intégration continue.
#
# Deux différences avec Windows, toutes deux structurelles :
#   - deux architectures (aarch64 pour les Mac Apple Silicon, x64 pour les Intel) ;
#   - une archive .tar.gz et non .zip : le JDK macOS contient des liens symboliques, et le ZIP
#     perdrait le bit exécutable de bin/java — le paquet serait inutilisable après décompression.
#
# Usage : ./package-macos.sh <jar> <repertoire-de-sortie> [aarch64|x64]
set -eu

JAR="${1:?usage: package-macos.sh <jar> <sortie> [aarch64|x64]}"
OUT="${2:?usage: package-macos.sh <jar> <sortie> [aarch64|x64]}"
ARCH="${3:-aarch64}"

case "$ARCH" in
    aarch64|x64) ;;
    *) echo "ERREUR : architecture inconnue '$ARCH' (attendu aarch64 ou x64)." >&2; exit 1 ;;
esac

JDK_VERSION=21
JDK_URL="https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/mac/${ARCH}/jdk/hotspot/normal/eclipse"
ARCHIVE="claude-runner-macos-${ARCH}.tar.gz"

# Identiques à Windows : les quatre premiers viennent de `jdeps`, les deux derniers sont invisibles
# pour lui (jdk.crypto.ec est chargé comme service — sans lui la poignée de main TLS échoue).
MODULES="java.base,java.desktop,java.net.http,java.sql,jdk.crypto.ec,jdk.unsupported"

WORK="${OUT}/.work-macos-${ARCH}"
rm -rf "$WORK" "${OUT}/${ARCHIVE}"
mkdir -p "$WORK"

echo "→ Téléchargement du JDK macOS ${ARCH} (Temurin ${JDK_VERSION})"
curl -fsSL -o "${WORK}/jdk-mac.tar.gz" "$JDK_URL"

# Le build échoue plutôt que de livrer un paquet douteux (D3 de SF-44-01).
SIZE=$(wc -c < "${WORK}/jdk-mac.tar.gz")
if [ "$SIZE" -lt 100000000 ]; then
    echo "ERREUR : JDK macOS tronqué (${SIZE} octets). Paquet non construit." >&2
    exit 1
fi

echo "→ Extraction"
mkdir -p "${WORK}/jdk"
tar -xzf "${WORK}/jdk-mac.tar.gz" -C "${WORK}/jdk"

# Sur macOS l'arborescence est <jdk>/Contents/Home/jmods, un niveau plus bas que sur Windows.
JMODS=$(find "${WORK}/jdk" -maxdepth 4 -type d -name jmods | head -1)
if [ -z "$JMODS" ]; then
    echo "ERREUR : aucun répertoire jmods dans le JDK téléchargé." >&2
    exit 1
fi

echo "→ jlink (Linux → macOS ${ARCH}) : $MODULES"
jlink --module-path "$JMODS" --add-modules "$MODULES" \
      --output "${WORK}/pkg/claude-runner/runtime" \
      --no-header-files --no-man-pages --compress=zip-6

if [ ! -f "${WORK}/pkg/claude-runner/runtime/bin/java" ]; then
    echo "ERREUR : jlink n'a pas produit bin/java — le runtime n'est pas celui de macOS." >&2
    exit 1
fi

# Un java.exe ici signifierait qu'on a empaqueté le runtime de la machine de build.
if [ -f "${WORK}/pkg/claude-runner/runtime/bin/java.exe" ]; then
    echo "ERREUR : runtime Windows détecté dans un paquet macOS." >&2
    exit 1
fi

cp "$JAR" "${WORK}/pkg/claude-runner/claude-runner.jar"

# Lanceur en LF, exécutable. `.command` s'ouvre au double-clic dans le Finder ; il marche aussi
# depuis un terminal. La levée de quarantaine est faite ICI, au premier lancement : macOS marque
# tout ce qui vient du réseau, et sans cela le premier `java` est refusé par Gatekeeper.
cat > "${WORK}/pkg/claude-runner/claude-runner.command" <<'LAUNCHER'
#!/bin/sh
# Runner autonome (F-44) : la JVM du paquet, jamais celle du système.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)

# macOS met en quarantaine ce qui vient du réseau ; sans cela, le premier lancement est refusé.
if command -v xattr >/dev/null 2>&1; then
    xattr -dr com.apple.quarantine "$HERE" 2>/dev/null || true
fi

exec "$HERE/runtime/bin/java" -jar "$HERE/claude-runner.jar" "$@"
LAUNCHER
chmod +x "${WORK}/pkg/claude-runner/claude-runner.command"

echo "→ Archive"
(cd "${WORK}/pkg" && tar -czf "../../${ARCHIVE}" claude-runner)
rm -rf "$WORK"

echo "✓ $(ls -la "${OUT}/${ARCHIVE}" | awk '{printf "%.1f Mo", $5/1048576}') → ${OUT}/${ARCHIVE}"
