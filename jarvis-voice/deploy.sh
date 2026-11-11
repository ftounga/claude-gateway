#!/usr/bin/env bash
# F-257 / SF-257-11 — met en ligne le service vocal jarvis-voice dans la prod unique (namespace claude-gateway-staging).
#
# Usage : jarvis-voice/deploy.sh <worktree détaché sur le commit à livrer>  — JAMAIS le checkout partagé.
# Ne PAS lancer pendant un deploy2.sh (même cluster, même namespace). Ne touche ni au backend, ni au frontend, ni à
# k8s/** (partagé avec legalCase). Aucun secret : le service obtient le jeton d'écoute auprès de la passerelle.
# Le coupe-circuit côté passerelle (APP_JARVIS_VOICE_STREAM_ENABLED, configmap backend-config) est géré à part.
set -euo pipefail
export AWS_PROFILE="${AWS_PROFILE:-legalcase-terraform}"
cd "${1:?dossier du worktree à déployer}"
TAG="staging-$(git rev-parse --short HEAD)"
REG=504895205419.dkr.ecr.eu-west-3.amazonaws.com
REPO=claude-gateway-jarvis-voice
NS=claude-gateway-staging
HOST=portal.ng-itconsulting.com
echo "=== jarvis-voice : $TAG ==="

aws ecr describe-repositories --region eu-west-3 --repository-names "$REPO" >/dev/null 2>&1 \
  || aws ecr create-repository --region eu-west-3 --repository-name "$REPO" \
       --image-scanning-configuration scanOnPush=true --query 'repository.repositoryUri' --output text
aws ecr get-login-password --region eu-west-3 | docker login -u AWS --password-stdin "$REG" >/dev/null

docker build -q -t "$REG/$REPO:$TAG" jarvis-voice/
docker push -q "$REG/$REPO:$TAG"

# Les manifestes restent génériques : le registre et l'étiquette sont posés à la volée.
sed -e "s|REGISTRY_PLACEHOLDER|$REG|" -e "s|VOICE_IMAGE_TAG|$TAG|" jarvis-voice/k8s/deployment.yaml \
  | kubectl -n "$NS" apply -f -
kubectl -n "$NS" apply -f jarvis-voice/k8s/networkpolicy.yaml
kubectl -n "$NS" apply -f jarvis-voice/k8s/ingress.yaml
kubectl -n "$NS" rollout status deploy/jarvis-voice --timeout=300s

echo "=== vérifications ==="
kubectl -n "$NS" get pods -l app=jarvis-voice --no-headers \
  -o custom-columns=N:.metadata.name,S:.status.phase,R:.status.containerStatuses[0].ready
echo "health : $(kubectl -n "$NS" exec deploy/jarvis-voice -- python -c \
  'import urllib.request;print(urllib.request.urlopen("http://127.0.0.1:8080/health").read().decode())')"
echo "ws     : $(curl -s -o /dev/null -m 5 -w '%{http_code}' --http1.1 -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "https://$HOST/voice/ws" || true) (101 attendu)"
echo "site   : $(curl -s -o /dev/null -w '%{http_code}' "https://$HOST/") / api : $(curl -s -o /dev/null -w '%{http_code}' "https://$HOST/api/actuator/health")"
echo "TAG=$TAG"
