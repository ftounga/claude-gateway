#!/usr/bin/env bash
#
# F-256 — LE BANC À VRAIE VOIX DE JARVIS, en une commande.
#
#   frontend/scripts/banc-jarvis-voix/lancer.sh [--scenarios a,b] [--build] [--boucle legacy|stream]
#                                               [--voix elevenlabs|openai]
#
# F-257 / SF-257-06 :
#   --boucle stream  la page est réglée sur la conversation en flux ; le service jarvis-voice tourne en local
#                    (Python 3.12 + dépendances dans $BANC_DEPS/venv-voice, créé au premier passage avec uv) derrière
#                    la passerelle locale (coupe-circuit APP_JARVIS_VOICE_STREAM_ENABLED ouvert pour le banc seulement).
#   --voix           la voix de Jarvis : `elevenlabs` (DÉFAUT, comme en prod : la voix choisie par le PO à
#                    l'audition, $BANC_VOIX_ID) ou `openai` (la voix par défaut de la configuration, référence F-256 §7).
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
LOOP=legacy
VOICE=elevenlabs
EXTRA=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --build) BUILD=1; shift ;;
        --boucle) LOOP="$2"; shift 2 ;;
        --voix) VOICE="$2"; shift 2 ;;
        *) EXTRA+=("$1"); shift ;;
    esac
done
# La voix de la prod : celle que le PO a choisie à l'audition (identifiant de voix ElevenLabs, pas un secret).
if [[ "$VOICE" == elevenlabs ]]; then
    export APP_JARVIS_VOICE_PROVIDER=elevenlabs APP_JARVIS_VOICE="${BANC_VOIX_ID:-JBFqnCBsd6RMkjVDRZzb}"
fi
VPORT="${BANC_VOICE_PORT:-$((PORT + 1))}"
DEPS="${BANC_DEPS:-$HOME/.local/share/claude-gateway/banc-jarvis-voix-deps}"
if [[ "$LOOP" == stream ]]; then
    export APP_JARVIS_VOICE_STREAM_ENABLED=true
    VENV="$DEPS/venv-voice"
    if [[ ! -x "$VENV/bin/python" ]]; then
        uv venv -q -p 3.12 "$VENV"
        VIRTUAL_ENV="$VENV" uv pip install -q -r "$REPO/jarvis-voice/requirements.txt"
    fi
    EXTRA+=(--boucle stream --voix-service "http://localhost:$VPORT")
fi
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
VS=""
trap 'kill $GW 2>/dev/null || true; [[ -n "$VS" ]] && kill $VS 2>/dev/null; rm -f "$WORK/jeton"' EXIT
for _ in $(seq 1 180); do
    grep -q "Started ClaudeGateway" "$OUT/passerelle.log" && break
    kill -0 $GW 2>/dev/null || { echo "passerelle arrêtée, voir $OUT/passerelle.log" >&2; exit 2; }
    sleep 1
done
if [[ "$LOOP" == stream ]]; then
    : > "$OUT/jarvis-voice.log"
    (cd "$REPO/jarvis-voice" && JARVIS_VOICE_GATEWAY_URL="http://localhost:$PORT/api" exec "$VENV/bin/python" -m uvicorn \
        app.main:app --host 127.0.0.1 --port "$VPORT" --no-access-log) > "$OUT/jarvis-voice.log" 2>&1 &
    VS=$!
    for _ in $(seq 1 60); do
        curl -fs "http://localhost:$VPORT/health" >/dev/null 2>&1 && break
        kill -0 $VS 2>/dev/null || { echo "jarvis-voice arrêté, voir $OUT/jarvis-voice.log" >&2; exit 2; }
        sleep 1
    done
fi
"$HERE/amorce.sh" > "$WORK/jeton"
"$HERE/avec-cles.sh" "$NODE" "$HERE/corpus.mjs" >&2
BANC_COMMIT="${BANC_COMMIT:-$(git -C "$REPO" rev-parse --short HEAD)}" \
    "$NODE" "$HERE/banc.mjs" --dist "$DIST/browser" --token "$WORK/jeton" --gateway "http://localhost:$PORT" \
    --out "$OUT" "${EXTRA[@]}"
