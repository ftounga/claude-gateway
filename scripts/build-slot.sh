#!/usr/bin/env bash
#
# build-slot.sh — gouvernance « vitesse de livraison » (2026-11-07)
#
# Exécute une commande lourde (suite Maven, ng test, build) dans l'un des CG_BUILD_SLOTS créneaux
# du poste (2 par défaut). Au-delà, la commande ATTEND son tour au lieu de se partager le processeur :
# mesuré le 2026-11-05, la suite backend passe de 18 à 31 min quand plusieurs agents la jouent
# en même temps.
#
# Usage : scripts/build-slot.sh <commande> [arguments…]
#   ex. scripts/build-slot.sh ./mvnw -q test
# Sortie : le code de retour de la commande.

set -uo pipefail

if [[ $# -eq 0 ]]; then
    echo "Usage : $0 <commande> [arguments…]" >&2
    exit 2
fi

SLOTS="${CG_BUILD_SLOTS:-2}"
DIR="${CG_BUILD_SLOT_DIR:-${TMPDIR:-/tmp}/cg-build-slots}"
mkdir -p "$DIR"

run_in_slot() {
    local fd="$1"
    "${@:2}"
    local rc=$?
    flock -u "$fd"
    return $rc
}

# Un créneau libre tout de suite ?
for i in $(seq 1 "$SLOTS"); do
    exec {fd}>"$DIR/slot$i"
    if flock -n "$fd"; then
        run_in_slot "$fd" "$@"
        exit $?
    fi
    exec {fd}>&-
done

# Sinon, attendre (sans boucle active) le créneau attribué à ce processus.
i=$(( $$ % SLOTS + 1 ))
echo "build-slot : ${SLOTS} créneau(x) occupé(s), attente du créneau $i…" >&2
exec {fd}>"$DIR/slot$i"
flock "$fd"
run_in_slot "$fd" "$@"
exit $?
