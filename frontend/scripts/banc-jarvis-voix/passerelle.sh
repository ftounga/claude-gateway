#!/usr/bin/env bash
#
# F-256 — banc à vraie voix de Jarvis : la PASSERELLE LOCALE avec les VRAIS fournisseurs.
#
# Lance le jar du backend (profil dev, H2 en mémoire, port $BANC_PORT) avec :
#   - la configuration NON SECRÈTE de la prod (configmap backend-config : modèle du cerveau rapide, voix, flux…),
#     filtrée sur Jarvis / voix / transcription, pour que le banc mesure ce que la prod fait ;
#   - les clés des fournisseurs lues À LA VOLÉE dans le secret k8s backend-secrets : jamais écrites dans un
#     fichier, jamais affichées, jamais journalisées (elles ne vivent que dans l'environnement du processus java).
#
# Usage : passerelle.sh <jar>   (BANC_PORT=18256 par défaut ; NS=claude-gateway-staging)
# Lecture seule côté prod : `kubectl get` uniquement.
set -euo pipefail

JAR="${1:?usage : passerelle.sh <jar du backend>}"
NS="${NS:-claude-gateway-staging}"
PORT="${BANC_PORT:-18256}"

secret() {
    kubectl -n "$NS" get secret backend-secrets -o "jsonpath={.data.$1}" | base64 -d
}

# Configuration non secrète de la prod, seulement ce qui règle Jarvis, la voix et l'écoute.
while IFS='=' read -r key value; do
    [[ -n "$key" ]] && export "$key=$value"
done < <(kubectl -n "$NS" get configmap backend-config -o json \
    | python3 -I -c 'import json,sys
d=json.load(sys.stdin)["data"]
for k,v in sorted(d.items()):
    if any(p in k for p in ("JARVIS","ELEVENLABS","SPEECH","STT_MODEL","UTTERANCE")) and "\n" not in v:
        print(f"{k}={v}")')

ANTHROPIC_API_KEY="$(secret ANTHROPIC_API_KEY)"
APP_ELEVENLABS_API_KEY="$(secret APP_ELEVENLABS_API_KEY)"
APP_STT_API_KEY="$(secret APP_STT_API_KEY)"
export ANTHROPIC_API_KEY APP_ELEVENLABS_API_KEY APP_STT_API_KEY
export APP_STT_BASE_URL="${APP_STT_BASE_URL:-https://api.openai.com/v1}"
# Secret JWT LOCAL, tiré à chaque lancement (jamais celui de la prod).
APP_JWT_SECRET="$(head -c 48 /dev/urandom | base64 -w0)"
export APP_JWT_SECRET
# Rien ne sort vers d'autres services : ni courrier, ni agenda, ni relève périodique.
export APP_JARVIS_MAIL_ENABLED=false APP_JARVIS_CALENDAR_ENABLED=false APP_JARVIS_STATE_RELEVE_ENABLED=false

# Base H2 EN FICHIER (serveur automatique) : l'amorce du compte de test (amorce.mjs) s'y connecte pendant que la
# passerelle tourne. Effacée à chaque lancement : chaque passage part du même état.
DB="${BANC_DB:-${TMPDIR:-/tmp}/banc-jarvis-voix-db}"
rm -f "$DB".mv.db "$DB".trace.db

exec java -Xmx1g -jar "$JAR" --spring.profiles.active=dev --server.port="$PORT" \
    --spring.datasource.url="jdbc:h2:file:$DB;AUTO_SERVER=TRUE" \
    --logging.level.fr.claudegateway=INFO
