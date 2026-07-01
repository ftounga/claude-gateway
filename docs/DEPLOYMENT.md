# Déploiement — claude-gateway (staging)

Déploiement dans le cluster EKS partagé **`legalcase-shared`** (AWS `504895205419`, eu-west-3),
workspace dédié (namespace **`claude-gateway-staging`**), exposé sur **`portal.ng-itconsulting.com`**.
RDS PostgreSQL **partagé avec legalcase**, base dédiée `claudegatewaydb`.

## Pré-requis
- `aws-cli` authentifié sur le profil **`legalcase-terraform`** (`aws sts get-caller-identity` OK).
- `kubectl` contexte `legalcase-shared` (`aws eks update-kubeconfig --region eu-west-3 --name legalcase-shared`).
- `docker`, `terraform` ≥ 1.3.
- Prérequis cluster déjà en place (partagés avec legalcase) : nginx-ingress, cert-manager (`letsencrypt-prod`).

## Secrets/valeurs à fournir
- `ANTHROPIC_API_KEY` (mode Hosted). Stripe/OAuth/mail : optionnels pour la 1ʳᵉ validation.

---

## Étape 1 — Infra AWS (Terraform)

```bash
# ECR (repos claude-gateway-backend/frontend) — dépôt legalcase-infra
cd ~/dev/legalcase-infra/cluster
terraform init && terraform apply        # crée module.ecr_claude_gateway

# S3 + IRSA (bucket uploads + rôle Textract/S3) — env staging
cd ~/dev/legalcase-infra/environments/staging
terraform init && terraform apply
terraform output claude_gateway_irsa_role_arn   # → IRSA_ROLE_ARN
terraform output claude_gateway_s3_bucket       # → nom du bucket
```

## Étape 2 — Namespace + base RDS

```bash
cd ~/dev/claude-gateway
kubectl apply -f k8s/overlays/staging/namespace.yaml   # crée claude-gateway-staging
./scripts/create-rds-database.sh                        # crée claudegatewaydb + rôle + pgvector
# → credentials applicatifs dans Secrets Manager: claude-gateway/staging/rds/app-credentials
```

## Étape 3 — Secret backend K8s

```bash
NS=claude-gateway-staging
APP=$(aws secretsmanager get-secret-value --profile legalcase-terraform \
      --secret-id claude-gateway/staging/rds/app-credentials --query SecretString --output text)
DB_URL=$(echo "$APP" | python3 -c "import sys,json;print(json.load(sys.stdin)['jdbc_url'])")
DB_USER=$(echo "$APP" | python3 -c "import sys,json;print(json.load(sys.stdin)['username'])")
DB_PASS=$(echo "$APP" | python3 -c "import sys,json;print(json.load(sys.stdin)['password'])")
S3_BUCKET=$(cd ~/dev/legalcase-infra/environments/staging && terraform output -raw claude_gateway_s3_bucket)

kubectl -n $NS create secret generic backend-secrets \
  --from-literal=ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  --from-literal=SPRING_DATASOURCE_URL="$DB_URL" \
  --from-literal=SPRING_DATASOURCE_USERNAME="$DB_USER" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$DB_PASS" \
  --from-literal=S3_BUCKET="$S3_BUCKET" \
  --dry-run=client -o yaml | kubectl apply -f -
```

## Étape 4 — Build + push images (ECR)

```bash
ACCOUNT=504895205419; REGION=eu-west-3
REG=$ACCOUNT.dkr.ecr.$REGION.amazonaws.com
aws ecr get-login-password --profile legalcase-terraform --region $REGION \
  | docker login --username AWS --password-stdin $REG
TAG=staging-$(git rev-parse --short HEAD)

docker build -t $REG/claude-gateway-backend:$TAG -t $REG/claude-gateway-backend:staging-latest ./backend
docker push $REG/claude-gateway-backend:$TAG && docker push $REG/claude-gateway-backend:staging-latest

docker build --build-arg BUILD_CONFIGURATION=production \
  -t $REG/claude-gateway-frontend:$TAG -t $REG/claude-gateway-frontend:staging-latest ./frontend
docker push $REG/claude-gateway-frontend:$TAG && docker push $REG/claude-gateway-frontend:staging-latest
```

## Étape 5 — Déploiement Kustomize

```bash
NS=claude-gateway-staging; REG=504895205419.dkr.ecr.eu-west-3.amazonaws.com
TAG=staging-$(git rev-parse --short HEAD)
IRSA=$(cd ~/dev/legalcase-infra/environments/staging && terraform output -raw claude_gateway_irsa_role_arn)

sed -i "s|REGISTRY_PLACEHOLDER|$REG|g;  s|BACKEND_IMAGE_TAG|$TAG|g" k8s/base/backend/deployment.yaml
sed -i "s|REGISTRY_PLACEHOLDER|$REG|g;  s|FRONTEND_IMAGE_TAG|$TAG|g" k8s/base/frontend/deployment.yaml
HASH=$(kubectl -n $NS get secret backend-secrets -o yaml | sha256sum | cut -c1-16)
sed -i "s|SECRETS_HASH_PLACEHOLDER|$HASH|g" k8s/base/backend/deployment.yaml
sed -i "s|IRSA_ROLE_ARN_PLACEHOLDER|$IRSA|g" \
  k8s/base/backend/deployment.yaml k8s/overlays/staging/service-account-patch.yaml

kubectl apply -k k8s/overlays/staging/
kubectl -n $NS rollout status deployment/claude-gateway-backend --timeout=10m
kubectl -n $NS rollout status deployment/claude-gateway-frontend --timeout=10m
```
> Ne pas committer les manifests après ces `sed` (placeholders remplacés). Utiliser `git checkout k8s/` ensuite.

## Étape 6 — DNS

```bash
# Hostname du NLB nginx-ingress (partagé) :
kubectl get svc -A | grep -i loadbalancer
```
Créer chez le registrar un **CNAME** `portal.ng-itconsulting.com` → `<hostname NLB>`.

## Étape 7 — TLS + vérification

```bash
kubectl -n claude-gateway-staging get certificate     # claude-gateway-tls → Ready=True (après DNS)
kubectl -n claude-gateway-staging get pods,ingress
curl -sf https://portal.ng-itconsulting.com/api/actuator/health
```
La page d'accueil doit afficher le statut backend en vert.

---

## CI/CD (après validation)
Une fois validé, les pushes sur `main` déploient via `.github/workflows/{backend,frontend}.yml`.
Configurer les **GitHub Secrets** : `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `EKS_CLUSTER_NAME`
(=`legalcase-shared`), `IRSA_BACKEND_ROLE_ARN`, `ANTHROPIC_API_KEY`, `DB_USERNAME`, `DB_PASSWORD`,
`DB_URL`, `S3_BUCKET` (+ Stripe/OAuth/mail selon besoin).
```bash
gh secret set EKS_CLUSTER_NAME -b legalcase-shared -R ftounga/claude-gateway
# … etc.
```
