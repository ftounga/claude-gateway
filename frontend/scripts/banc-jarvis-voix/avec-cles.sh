#!/usr/bin/env bash
#
# F-256 — exécute une commande avec la clé ElevenLabs (ELEVEN) lue À LA VOLÉE dans le secret k8s backend-secrets :
# jamais écrite dans un fichier, jamais affichée ; elle ne vit que dans l'environnement de la commande lancée.
#
# Usage : avec-cles.sh <commande> [arguments…]     (NS=claude-gateway-staging)
set -euo pipefail
NS="${NS:-claude-gateway-staging}"
ELEVEN="$(kubectl -n "$NS" get secret backend-secrets -o 'jsonpath={.data.APP_ELEVENLABS_API_KEY}' | base64 -d)"
export ELEVEN
exec "$@"
