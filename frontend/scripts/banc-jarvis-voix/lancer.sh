#!/usr/bin/env bash
#
# F-256 — LE BANC À VRAIE VOIX DE JARVIS, en une commande.
#
#   frontend/scripts/banc-jarvis-voix/lancer.sh [--scenarios a,b] [--build]
#
# 1. (--build) construit le jar du backend et la page en mode développement, via scripts/build-slot.sh ;
# 2. lance la passerelle LOCALE (passerelle.sh : H2 neuve, config non secrète de la prod, clés lues à la volée) ;
# 3. amorce le compte de test (amorce.sh) et complète le corpus (corpus.mjs, cache) ;
# 4. joue les scénarios (banc.mjs) — UNE session de transcription à la fois ;
# 5. arrête la passerelle. Rapport et résumé dans $BANC_OUT (défaut ~/.local/share/claude-gateway/banc-jarvis-voix/<date>).
#
# Prérequis : kubectl sur le cluster (lecture seule), Chrome (/usr/bin/google-chrome), Node 22, Java 21,
# puppeteer-core@23.11.1 installé dans $BANC_DEPS (défaut ~/.local/share/claude-gateway/banc-jarvis-voix-deps).
# Code de sortie : 0 si la porte est franchie, 1 sinon.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
WORK="${BANC_WORK:-${TMPDIR:-/tmp}/banc-jarvis-voix-$$}"
PORT="${BANC_PORT:-18256}"
OUT="${BANC_OUT:-$HOME/.local/share/claude-gateway/banc-jarvis-voix/$(date +%Y-%m-%dT%H-%M-%S)}"
JAR="${BANC_JAR:-$REPO/backend/target/claude-gateway-backend-0.0.1-SNAPSHOT.jar}"
DIST="${BANC_DIST:-$WORK/dist}"
NODE="${NODE:-node}"
BUILD=0
EXTRA=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --build) BUILD=1; shift ;;
        *) EXTRA+=("$1"); shift ;;
    esac
done
mkdir -p "$WORK" "$OUT"

if [[ "$BUILD" == 1 || ! -f "$JAR" ]]; then
    (cd "$REPO/backend" && "$REPO/scripts/build-slot.sh" ./mvnw -q -o package -DskipTests)
fi
if [[ "$BUILD" == 1 || ! -d "$DIST/browser" ]]; then
    (cd "$REPO/frontend" && "$REPO/scripts/build-slot.sh" npx ng build --configuration development --output-path "$DIST" >/dev/null)
fi
# Le jar est copié : une construction pendant le passage ne le change pas sous la passerelle.
cp "$JAR" "$WORK/passerelle.jar"

export BANC_PORT="$PORT" BANC_DB="$WORK/db"
: > "$OUT/passerelle.log"
"$HERE/passerelle.sh" "$WORK/passerelle.jar" > "$OUT/passerelle.log" 2>&1 &
GW=$!
trap 'kill $GW 2>/dev/null || true; rm -f "$WORK/jeton"' EXIT
for _ in $(seq 1 180); do
    grep -q "Started ClaudeGateway" "$OUT/passerelle.log" && break
    kill -0 $GW 2>/dev/null || { echo "passerelle arrêtée, voir $OUT/passerelle.log" >&2; exit 2; }
    sleep 1
done
"$HERE/amorce.sh" > "$WORK/jeton"
"$HERE/avec-cles.sh" "$NODE" "$HERE/corpus.mjs" >&2
BANC_COMMIT="${BANC_COMMIT:-$(git -C "$REPO" rev-parse --short HEAD)}" \
    "$NODE" "$HERE/banc.mjs" --dist "$DIST/browser" --token "$WORK/jeton" --gateway "http://localhost:$PORT" \
    --out "$OUT" "${EXTRA[@]}"
