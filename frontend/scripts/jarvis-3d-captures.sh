#!/usr/bin/env bash
# F-236 / SF-236-01 — captures de référence de Jarvis en 3D (cadrage §15).
#
# Prérequis : le front de DÉVELOPPEMENT servi (`npm start`, port 4200) — la page du banc `/jarvis-banc` n'existe
# qu'en développement. Chrome headless en rendu logiciel (swiftshader), téléphone 390×844 et PC 1920×1080.
# Critère automatique : chaque capture a ≥ 2 % de pixels hors fond, et aucune erreur dans la console.
#
# Usage : scripts/jarvis-3d-captures.sh [sortie=../docs/features/F-236/captures/socle] [base=http://localhost:4200]
#         JARVIS_BANC_SCENES="coeur" JARVIS_BANC_TIMES="0 1200 2600" pour choisir scènes et instants.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/../../docs/features/F-236/captures/socle}"
BASE="${2:-http://localhost:4200}"
SCENES="${JARVIS_BANC_SCENES:-coeur}"
TIMES="${JARVIS_BANC_TIMES:-0 1200 2600}"
CHROME="${CHROME:-$(command -v google-chrome || command -v chromium || command -v chromium-browser || true)}"
MIN_COVERAGE="0.02"
# SF-236-14 — au-delà de cette part de gris neutre, la capture montre le message d'erreur du serveur de dev.
MAX_OVERLAY="0.08"

if [[ -z "$CHROME" ]]; then
  echo "jarvis-3d-captures : Chrome introuvable (variable CHROME)." >&2
  exit 2
fi
if ! curl -sf -o /dev/null "$BASE/"; then
  echo "jarvis-3d-captures : $BASE ne répond pas — lancer « npm start » d'abord." >&2
  exit 2
fi
mkdir -p "$OUT"

failed=0
for scene in $SCENES; do
  for t in $TIMES; do
    for size in "390,844:telephone:moyen" "1920,1080:pc:haut"; do
      IFS=: read -r window device level <<<"$size"
      png="$OUT/${scene}-t${t}-${device}.png"
      # SF-236-04 — le rendu logiciel est parfois en retard sur le temps virtuel (première image pas encore posée) :
      # une capture vide est refaite, au plus trois fois, avant d'être déclarée en échec.
      for attempt in 1 2 3; do
        log="$(mktemp)"
        profile="$(mktemp -d)"
        rm -f "$png"
        "$CHROME" --headless=old --disable-gpu-sandbox --use-angle=swiftshader --enable-unsafe-swiftshader \
          --user-data-dir="$profile" --no-first-run --hide-scrollbars --window-size="$window" \
          --virtual-time-budget=6000 --enable-logging=stderr --v=0 \
          --screenshot="$png" "$BASE/jarvis-banc?banc=${scene}&t=${t}&niveau=${level}" >"$log" 2>&1 || true
        rm -rf "$profile"
        if [[ -s "$png" ]] && awk -v s="$(node "$HERE/png-coverage.mjs" "$png")" -v m="$MIN_COVERAGE" \
            'BEGIN { exit !(s >= m) }' && awk -v o="$(node "$HERE/png-coverage.mjs" "$png" --overlay)" \
            -v m="$MAX_OVERLAY" 'BEGIN { exit !(o <= m) }'; then
          break
        fi
        [[ $attempt -lt 3 ]] && rm -f "$log"
      done
      if [[ ! -s "$png" ]]; then
        echo "ÉCHEC $png : aucune capture produite." >&2
        failed=1
        rm -f "$log"
        continue
      fi
      share="$(node "$HERE/png-coverage.mjs" "$png")"
      errors="$(grep -E 'CONSOLE.*(Uncaught|ERROR|Error)|:ERROR:CONSOLE' "$log" || true)"
      overlay="$(node "$HERE/png-coverage.mjs" "$png" --overlay)"
      rm -f "$log"
      if awk -v s="$share" -v m="$MIN_COVERAGE" 'BEGIN { exit !(s < m) }'; then
        echo "ÉCHEC $png : capture vide ($share de pixels hors fond < $MIN_COVERAGE)." >&2
        failed=1
      elif awk -v o="$overlay" -v m="$MAX_OVERLAY" 'BEGIN { exit !(o > m) }'; then
        echo "ÉCHEC $png : message d'erreur du serveur de développement à l'écran (recompilation ratée)." >&2
        failed=1
      elif [[ -n "$errors" ]]; then
        echo "ÉCHEC $png : erreur console :" >&2
        echo "$errors" >&2
        failed=1
      else
        echo "OK    $png ($share hors fond)"
      fi
    done
  done
done
exit "$failed"
