#!/usr/bin/env bash
# Garde-fou gouvernance claude-gateway (PreToolUse / Bash).
# Bloque uniquement les 2 dangers clairs, pour ne jamais entraver la vague autonome :
#   1. push --force sur origin (perte d'historique)
#   2. gh pr merge SANS --squash (le repo merge en squash — convention)
# Tout le reste passe. Doit rester ultra-rapide (s'exécute sur chaque Bash).
set -euo pipefail

# Récupère la commande depuis le JSON stdin du hook.
CMD="$(python3 -c 'import sys,json; print(json.load(sys.stdin).get("tool_input",{}).get("command",""))' 2>/dev/null || true)"
[ -z "$CMD" ] && exit 0

# 1) push --force
if printf '%s' "$CMD" | grep -Eq 'git +push([^|;&]*)(--force|-f\b|--force-with-lease)'; then
  echo "BLOQUÉ : 'git push --force' interdit (perte d'historique). Utilise une branche + PR." >&2
  exit 2
fi

# 2) gh pr merge sans --squash
if printf '%s' "$CMD" | grep -Eq 'gh +pr +merge'; then
  if ! printf '%s' "$CMD" | grep -Eq -- '--squash'; then
    echo "BLOQUÉ : 'gh pr merge' doit utiliser --squash (convention repo)." >&2
    exit 2
  fi
fi

exit 0
