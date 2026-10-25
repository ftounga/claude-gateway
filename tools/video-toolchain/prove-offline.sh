#!/usr/bin/env bash
# F-215 / SF-215-06 — Preuve réelle, hors ligne : la chaîne vidéo fabriquée par build-bundles.sh, servie par un
# portail local (serve-local.py), récupérée par le VRAI montage du runner, puis video_render_check et un rendu réel.
#
# Usage : tools/video-toolchain/prove-offline.sh [dossier out/<version>]   (Linux x64, paquet linux-x64 fabriqué)
#
# Conditions de l'essai :
#   - espace réseau isolé (`unshare -rn`) : seule la boucle locale existe, internet est injoignable ;
#   - HTTPS_PROXY / HTTP_PROXY vers un port mort, NO_PROXY=localhost,127.0.0.1 ;
#   - PATH sans Node 22 (JDK + /usr/bin + /bin seulement) ;
#   - manifeste signé par une clé d'ESSAI générée ici (la clé F-111 ne sort pas de Secrets Manager).
# Prérequis : java ≥ 21, python3, openssl, curl, un Chrome ou Edge installé, dépendances Maven du runner en cache.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=versions.env
source "$HERE/versions.env"
SRC="${1:-$HERE/out/$TOOLCHAIN_VERSION}"
PORT="${PROVE_PORT:-18765}"

if [ -z "${PROVE_OFFLINE_INSIDE:-}" ]; then
  [ -f "$SRC/manifest.json" ] && [ -f "$SRC/linux-x64.tar.gz" ] || {
    echo "ERREUR : $SRC/manifest.json et linux-x64.tar.gz requis (build-bundles.sh linux-x64)." >&2; exit 2; }
  WORK="$(mktemp -d)"
  mkdir -p "$WORK/portal/$TOOLCHAIN_VERSION" "$WORK/root"
  ln -s "$SRC/linux-x64.tar.gz" "$WORK/portal/$TOOLCHAIN_VERSION/linux-x64.tar.gz"
  cp "$SRC/manifest.json" "$WORK/portal/$TOOLCHAIN_VERSION/manifest.json"
  openssl genpkey -algorithm ed25519 -out "$WORK/test-key.pem" 2>/dev/null
  openssl pkey -in "$WORK/test-key.pem" -pubout -out "$WORK/test-pub.pem"
  java "$HERE/SignToolchainManifest.java" "$WORK/test-key.pem" "$WORK/test-pub.pem" \
    "$WORK/portal/$TOOLCHAIN_VERSION/manifest.json"
  echo "== Essai dans un espace réseau isolé (boucle locale seule) — dossier de travail $WORK"
  exec env PROVE_OFFLINE_INSIDE="$WORK" PROVE_REAL_HOME="$HOME" unshare -rn bash "$0" "$SRC"
fi

WORK="$PROVE_OFFLINE_INSIDE"
ip link set lo up
echo "== Internet est-il joignable ?"
curl -sS -m 5 -o /dev/null https://nodejs.org/dist/ 2>&1 | sed 's/^/   nodejs.org : /' || true
curl -sS -m 5 -o /dev/null https://registry.npmjs.org/ 2>&1 | sed 's/^/   registry.npmjs.org : /' || true

python3 "$HERE/serve-local.py" "$WORK/portal/$TOOLCHAIN_VERSION" "$PORT" jeton-essai > "$WORK/portal.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break
  sleep 0.1
done

export HTTPS_PROXY="http://127.0.0.1:9" HTTP_PROXY="http://127.0.0.1:9"
export https_proxy="$HTTPS_PROXY" http_proxy="$HTTP_PROXY"
export NO_PROXY="localhost,127.0.0.1" no_proxy="localhost,127.0.0.1"
export PATH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}/bin:/usr/bin:/bin"
# Les caches Maven (wrapper et dépôt) restent ceux de l'utilisateur : rien n'est téléchargé (-o).
export HOME="$PROVE_REAL_HOME" MAVEN_USER_HOME="$PROVE_REAL_HOME/.m2"
export VIDEO_TOOLCHAIN_LIVE_PORTAL="http://127.0.0.1:$PORT/api"
export VIDEO_TOOLCHAIN_LIVE_ROOT="$WORK/root"
export VIDEO_TOOLCHAIN_LIVE_PUBKEY="$WORK/test-pub.pem"
export VIDEO_TOOLCHAIN_LIVE_TOKEN="jeton-essai"
echo "== PATH=$PATH"
echo "== node du PATH : $(command -v node || echo aucun) $(node --version 2>/dev/null || true)"

set +e
"$REPO/runner/mvnw" -o -f "$REPO/runner/pom.xml" -Dmaven.repo.local="$PROVE_REAL_HOME/.m2/repository" -Duser.home="$PROVE_REAL_HOME" \
  test -Dtest=VideoToolchainLiveTest -Dsurefire.failIfNoSpecifiedTests=false -DtrimStackTrace=false
RC=$?
set -e
echo "== Requêtes reçues par le portail local :"
cat "$WORK/portal.log"
echo "== MP4 :"
ls -la "$WORK/root/demo/renders/" 2>&1 || true
exit $RC
