#!/usr/bin/env bash
#
# Crée la base de données claude-gateway sur l'instance RDS PARTAGÉE de legalcase.
# L'instance RDS est privée (joignable uniquement depuis les nœuds EKS) : on
# exécute donc psql depuis un Job Kubernetes à l'intérieur du cluster.
#
# Ce que fait le script (idempotent) :
#   1. Récupère les credentials MASTER RDS depuis Secrets Manager.
#   2. Génère un mot de passe pour le rôle applicatif `claudegateway`.
#   3. Lance un Job k8s (postgres:16) qui crée le rôle, la base `claudegatewaydb`
#      et l'extension pgvector.
#   4. Enregistre les credentials applicatifs dans un secret Secrets Manager
#      `claude-gateway/staging/rds/app-credentials` (réutilisable au déploiement).
#
# Prérequis : aws-cli authentifié (profil legalcase-terraform) + kubectl (contexte
# legalcase-shared). Namespace claude-gateway-staging déjà créé.
#
# Usage : ./scripts/create-rds-database.sh
set -euo pipefail

AWS_PROFILE="${AWS_PROFILE:-legalcase-terraform}"
AWS_REGION="${AWS_REGION:-eu-west-3}"
NAMESPACE="${NAMESPACE:-claude-gateway-staging}"
MASTER_SECRET_ID="${MASTER_SECRET_ID:-legalcase/staging/rds/credentials}"
APP_SECRET_ID="${APP_SECRET_ID:-claude-gateway/staging/rds/app-credentials}"
APP_DB="claudegatewaydb"
APP_USER="claudegateway"

export AWS_PROFILE AWS_REGION

echo "==> Lecture des credentials master RDS ($MASTER_SECRET_ID)"
MASTER_JSON=$(aws secretsmanager get-secret-value --secret-id "$MASTER_SECRET_ID" \
  --query SecretString --output text)
PGHOST=$(echo "$MASTER_JSON" | python3 -c "import sys,json;print(json.load(sys.stdin)['host'])")
PGPORT=$(echo "$MASTER_JSON" | python3 -c "import sys,json;print(json.load(sys.stdin).get('port',5432))")
PGUSER=$(echo "$MASTER_JSON" | python3 -c "import sys,json;print(json.load(sys.stdin)['username'])")
PGPASSWORD=$(echo "$MASTER_JSON" | python3 -c "import sys,json;print(json.load(sys.stdin)['password'])")

# Réutilise le mot de passe applicatif s'il existe déjà, sinon en génère un.
if APP_JSON=$(aws secretsmanager get-secret-value --secret-id "$APP_SECRET_ID" \
      --query SecretString --output text 2>/dev/null); then
  APP_PASS=$(echo "$APP_JSON" | python3 -c "import sys,json;print(json.load(sys.stdin)['password'])")
  echo "==> Mot de passe applicatif existant réutilisé"
else
  APP_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 28)
  echo "==> Nouveau mot de passe applicatif généré"
fi

echo "==> Création du Job k8s de bootstrap SQL (namespace $NAMESPACE)"
kubectl -n "$NAMESPACE" delete job cg-rds-bootstrap --ignore-not-found >/dev/null 2>&1 || true
kubectl -n "$NAMESPACE" create secret generic cg-rds-bootstrap \
  --from-literal=PGHOST="$PGHOST" \
  --from-literal=PGPORT="$PGPORT" \
  --from-literal=PGUSER="$PGUSER" \
  --from-literal=PGPASSWORD="$PGPASSWORD" \
  --from-literal=APP_USER="$APP_USER" \
  --from-literal=APP_PASS="$APP_PASS" \
  --from-literal=APP_DB="$APP_DB" \
  --dry-run=client -o yaml | kubectl apply -f -

cat <<'YAML' | sed "s|__NS__|$NAMESPACE|g" | kubectl apply -f -
apiVersion: batch/v1
kind: Job
metadata:
  name: cg-rds-bootstrap
  namespace: __NS__
spec:
  backoffLimit: 1
  ttlSecondsAfterFinished: 300
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: psql
          image: postgres:16-alpine
          envFrom:
            - secretRef:
                name: cg-rds-bootstrap
          command: ["/bin/sh", "-c"]
          args:
            - |
              set -e
              export PGPASSWORD="$PGPASSWORD"
              echo "Création du rôle et de la base…"
              psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 <<SQL
              DO \$\$
              BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$APP_USER') THEN
                  CREATE ROLE $APP_USER LOGIN PASSWORD '$APP_PASS';
                ELSE
                  ALTER ROLE $APP_USER WITH PASSWORD '$APP_PASS';
                END IF;
              END
              \$\$;
              SELECT 'CREATE DATABASE $APP_DB OWNER $APP_USER'
              WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$APP_DB')\gexec
              GRANT ALL PRIVILEGES ON DATABASE $APP_DB TO $APP_USER;
              SQL
              echo "Activation de pgvector dans $APP_DB…"
              psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$APP_DB" -v ON_ERROR_STOP=1 \
                -c "CREATE EXTENSION IF NOT EXISTS vector;" \
                -c "GRANT ALL ON SCHEMA public TO $APP_USER;"
              echo "OK"
YAML

echo "==> Attente de la fin du Job…"
kubectl -n "$NAMESPACE" wait --for=condition=complete job/cg-rds-bootstrap --timeout=180s \
  || { echo "ÉCHEC — logs :"; kubectl -n "$NAMESPACE" logs job/cg-rds-bootstrap; exit 1; }
kubectl -n "$NAMESPACE" logs job/cg-rds-bootstrap | tail -20

echo "==> Enregistrement des credentials applicatifs dans Secrets Manager ($APP_SECRET_ID)"
JDBC_URL="jdbc:postgresql://${PGHOST}:${PGPORT}/${APP_DB}"
APP_PAYLOAD=$(python3 - "$APP_USER" "$APP_PASS" "$PGHOST" "$PGPORT" "$APP_DB" "$JDBC_URL" <<'PY'
import json,sys
u,p,h,port,db,url=sys.argv[1:7]
print(json.dumps({"username":u,"password":p,"host":h,"port":int(port),"dbname":db,"jdbc_url":url}))
PY
)
if aws secretsmanager describe-secret --secret-id "$APP_SECRET_ID" >/dev/null 2>&1; then
  aws secretsmanager put-secret-value --secret-id "$APP_SECRET_ID" --secret-string "$APP_PAYLOAD" >/dev/null
else
  aws secretsmanager create-secret --name "$APP_SECRET_ID" \
    --description "claude-gateway staging app DB credentials" \
    --secret-string "$APP_PAYLOAD" >/dev/null
fi

echo "==> Nettoyage du secret de bootstrap"
kubectl -n "$NAMESPACE" delete secret cg-rds-bootstrap --ignore-not-found >/dev/null 2>&1 || true

echo ""
echo "✅ Base $APP_DB prête (rôle $APP_USER, pgvector activé)."
echo "   JDBC URL : $JDBC_URL"
echo "   Credentials applicatifs : Secrets Manager → $APP_SECRET_ID"
