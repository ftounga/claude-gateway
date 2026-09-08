#!/bin/sh
# Construit le paquet autonome Windows x64 du runner (F-44 / SF-44-01).
#
# Le runner est un .jar : il ne contient que du bytecode et suppose une JVM 21 sur le poste. Cette
# supposition a échoué au premier contact chez un client — poste d'entreprise en Java 8, JVM imposée
# par la DSI, pas de droits administrateur. Ce script livre l'application AVEC sa propre JVM.
#
# Le point qui rend l'affaire abordable : `jlink` sait cibler une AUTRE plateforme si on lui donne
# les `jmods` correspondants. Le runtime Windows se construit donc depuis Linux, sans machine
# Windows ni matrice d'intégration continue.
#
# Usage : ./package-windows.sh <jar> <repertoire-de-sortie>
set -eu

JAR="${1:?usage: package-windows.sh <jar> <sortie>}"
OUT="${2:?usage: package-windows.sh <jar> <sortie>}"

JDK_VERSION=21
JDK_URL="https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/windows/x64/jdk/hotspot/normal/eclipse"

# Modules de la JVM réduite. Les quatre premiers viennent de `jdeps` sur le jar réel ; les deux
# derniers ne sont PAS visibles par jdeps :
#   - jdk.crypto.ec  : courbes elliptiques. Sans lui, la poignée de main TLS échoue sur la plupart
#                      des serveurs modernes — il est chargé comme service, jamais référencé.
#   - jdk.unsupported: sun.misc.Unsafe, utilisé par des bibliothèques tierces.
MODULES="java.base,java.desktop,java.net.http,java.sql,jdk.crypto.ec,jdk.unsupported"

WORK="${OUT}/.work"
rm -rf "$WORK" "${OUT}/claude-runner-windows-x64.zip"
mkdir -p "$WORK"

echo "→ Téléchargement du JDK Windows x64 (Temurin ${JDK_VERSION})"
curl -fsSL -o "${WORK}/jdk-win.zip" "$JDK_URL"

# D3 : le build échoue plutôt que de livrer un paquet douteux. Une image qui sert un ZIP tronqué est
# pire qu'une image qui n'existe pas — le premier geste d'un nouveau client est de le télécharger.
SIZE=$(wc -c < "${WORK}/jdk-win.zip")
if [ "$SIZE" -lt 100000000 ]; then
    echo "ERREUR : JDK Windows tronqué (${SIZE} octets). Paquet non construit." >&2
    exit 1
fi

echo "→ Extraction"
unzip -q "${WORK}/jdk-win.zip" -d "${WORK}/jdk"
JMODS=$(find "${WORK}/jdk" -maxdepth 2 -type d -name jmods | head -1)
if [ -z "$JMODS" ]; then
    echo "ERREUR : aucun répertoire jmods dans le JDK téléchargé." >&2
    exit 1
fi

echo "→ jlink (Linux → Windows) : $MODULES"
jlink --module-path "$JMODS" --add-modules "$MODULES" \
      --output "${WORK}/pkg/claude-runner/runtime" \
      --no-header-files --no-man-pages --compress=zip-6

if [ ! -f "${WORK}/pkg/claude-runner/runtime/bin/java.exe" ]; then
    echo "ERREUR : jlink n'a pas produit java.exe — le runtime n'est pas celui de Windows." >&2
    exit 1
fi

cp "$JAR" "${WORK}/pkg/claude-runner/claude-runner.jar"

# CRLF explicite : un .cmd en LF est refusé par certains shells Windows. C'est le genre de détail
# qui transforme un paquet correct en « ça ne marche pas » chez le client.
#
# Double-clic (F-46 / SF-46-02) : le lanceur existe pour ça, et un double-clic ne transmet aucun
# argument — depuis SF-46-01 le runner reprend alors la configuration mémorisée à l'appairage. Reste
# le cas où il refuse (jamais appairé, jeton expiré) : la fenêtre se refermerait sur le message.
# `%cmdcmdline%` contient le nom du .cmd quand il a été double-cliqué, jamais quand il est appelé
# depuis un terminal déjà ouvert — la pause n'est donc posée que là où elle sert.
printf '@echo off\r\nsetlocal\r\nrem Runner autonome (F-44) : la JVM du paquet, jamais celle du systeme.\r\nrem Double-clic (F-46) : aucun argument, la configuration memorisee prend le relais.\r\necho "%%cmdcmdline%%" | find /i "%%~nx0" >nul && set CLAUDE_RUNNER_DOUBLECLIC=1\r\n"%%~dp0runtime\\bin\\java.exe" -jar "%%~dp0claude-runner.jar" %%*\r\nif errorlevel 1 if defined CLAUDE_RUNNER_DOUBLECLIC pause\r\n' \
    > "${WORK}/pkg/claude-runner/claude-runner.cmd"

echo "→ Archive"
(cd "${WORK}/pkg" && zip -q -r -9 "../../claude-runner-windows-x64.zip" claude-runner)
rm -rf "$WORK"

echo "✓ $(ls -la "${OUT}/claude-runner-windows-x64.zip" | awk '{printf "%.1f Mo", $5/1048576}') → ${OUT}/claude-runner-windows-x64.zip"
