#!/usr/bin/env bash
#
# build-slot.test.sh — test de scripts/build-slot.sh : code de retour transmis, et jamais plus de
# CG_BUILD_SLOTS commandes en même temps.
#
# Usage : scripts/build-slot.test.sh
# Sortie : code 0 si tous les cas passent, 1 sinon.

set -euo pipefail
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build-slot.sh"
SANDBOX="$(mktemp -d -t build-slot-test.XXXXXX)"
trap 'rm -rf "$SANDBOX"' EXIT
export CG_BUILD_SLOT_DIR="$SANDBOX/slots" CG_BUILD_SLOTS=2

"$SCRIPT" true || { echo "ÉCHEC : code 0 attendu"; exit 1; }
set +e; "$SCRIPT" sh -c 'exit 7'; rc=$?; set -e
[[ $rc -eq 7 ]] || { echo "ÉCHEC : code 7 attendu, reçu $rc"; exit 1; }

# 5 commandes lancées ensemble : le maximum observé en parallèle doit rester ≤ 2.
probe() {
    local n
    n=$(( $(ls "$SANDBOX/running" 2>/dev/null | wc -l) + 1 ))
    touch "$SANDBOX/running/$BASHPID"; echo "$n" >> "$SANDBOX/max"
    sleep 0.4
    rm -f "$SANDBOX/running/$BASHPID"
}
export -f probe; export SANDBOX
mkdir -p "$SANDBOX/running"
for _ in 1 2 3 4 5; do "$SCRIPT" bash -c probe & done
wait
max=$(sort -n "$SANDBOX/max" | tail -1)
[[ $max -le 2 ]] || { echo "ÉCHEC : $max commandes simultanées (max 2)"; exit 1; }
echo "OK build-slot (max simultané observé : $max)"
