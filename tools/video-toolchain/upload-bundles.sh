#!/usr/bin/env bash
# F-215 / SF-215-06 — Signe le manifeste de la chaîne vidéo et publie paquets + manifeste dans le bucket du portail.
#
# Usage : tools/video-toolchain/upload-bundles.sh [--dry-run] [--force]
#   --dry-run  vérifie et signe, affiche les copies S3 sans rien envoyer
#   --force    remplace une version déjà publiée (sinon : refus si le manifeste existe déjà)
#
# Variables : BUCKET (défaut claude-gateway-staging-documents-504895205419), AWS_REGION (défaut eu-west-3),
#             SIGNING_SECRET_ID (défaut claude-gateway/runner-update-signing-key — la clé F-111 du runner).
#
# Prérequis : build-bundles.sh lancé pour les QUATRE plateformes ; aws cli authentifié ; java ≥ 21.
# Ce script n'est lancé par aucun test ni aucune CI : c'est un geste d'exploitation, au déploiement.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=versions.env
source "$HERE/versions.env"

BUCKET="${BUCKET:-claude-gateway-staging-documents-504895205419}"
AWS_REGION="${AWS_REGION:-eu-west-3}"
SIGNING_SECRET_ID="${SIGNING_SECRET_ID:-claude-gateway/runner-update-signing-key}"
OUT="${VIDEO_TOOLCHAIN_OUT:-$HERE/out}/$TOOLCHAIN_VERSION"
PREFIX="toolchain/video/$TOOLCHAIN_VERSION"
PUBLIC_KEY="$REPO/runner/src/main/resources/update-signing-public-key.pem"
DRY_RUN=0
FORCE=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --force) FORCE=1 ;;
    *) echo "Option inconnue : $arg" >&2; exit 2 ;;
  esac
done

MANIFEST="$OUT/manifest.json"
[ -f "$MANIFEST" ] || { echo "ERREUR : $MANIFEST absent — lancez d'abord build-bundles.sh." >&2; exit 2; }

# 1. Les quatre paquets sont là et correspondent au manifeste (empreinte et taille).
for plat in darwin-arm64 darwin-x64 linux-x64 win-x64; do
  file="$OUT/$plat.tar.gz"
  [ -f "$file" ] || { echo "ERREUR : $file absent — build-bundles.sh $plat." >&2; exit 2; }
  expected="$(node -p "(require('$MANIFEST').bundles['$plat'] || {}).sha256 || ''")"
  actual="$(sha256sum "$file" | awk '{ print $1 }')"
  [ "$expected" = "$actual" ] || { echo "ERREUR : $plat.tar.gz ne correspond pas au manifeste (relancez build-bundles.sh)." >&2; exit 3; }
  echo "  ✓ $plat.tar.gz  $actual"
done
[ "$(node -p "require('$MANIFEST').version")" = "$TOOLCHAIN_VERSION" ] || { echo "ERREUR : version du manifeste ≠ $TOOLCHAIN_VERSION" >&2; exit 3; }

# 2. Signature du manifeste avec la clé F-111 (jamais écrite ailleurs qu'un dossier temporaire 0700).
KEYDIR="$(mktemp -d)"
chmod 700 "$KEYDIR"
trap 'rm -rf "$KEYDIR"' EXIT
aws secretsmanager get-secret-value --region "$AWS_REGION" --secret-id "$SIGNING_SECRET_ID" \
  --query SecretString --output text > "$KEYDIR/key.pem"
chmod 600 "$KEYDIR/key.pem"
java "$HERE/SignToolchainManifest.java" "$KEYDIR/key.pem" "$PUBLIC_KEY" "$MANIFEST"
rm -f "$KEYDIR/key.pem"

# 3. Publication : paquets d'abord, signature, manifeste en dernier (un runner ne voit le manifeste
#    qu'une fois tout le reste en place).
if [ "$FORCE" = 0 ] && aws s3api head-object --region "$AWS_REGION" --bucket "$BUCKET" --key "$PREFIX/manifest.json" >/dev/null 2>&1; then
  echo "ERREUR : s3://$BUCKET/$PREFIX/manifest.json existe déjà. --force pour remplacer cette version." >&2
  exit 4
fi
S3="aws s3 cp --region $AWS_REGION --only-show-errors"
[ "$DRY_RUN" = 1 ] && S3="$S3 --dryrun"
for plat in darwin-arm64 darwin-x64 linux-x64 win-x64; do
  $S3 --content-type application/gzip "$OUT/$plat.tar.gz" "s3://$BUCKET/$PREFIX/$plat.tar.gz"
done
$S3 --content-type text/plain "$MANIFEST.sig" "s3://$BUCKET/$PREFIX/manifest.json.sig"
$S3 --content-type application/json "$MANIFEST" "s3://$BUCKET/$PREFIX/manifest.json"
if [ "$DRY_RUN" = 1 ]; then
  echo "Simulation terminée : rien n'a été envoyé."
else
  echo "Chaîne vidéo $TOOLCHAIN_VERSION publiée : s3://$BUCKET/$PREFIX/"
  aws s3 ls --region "$AWS_REGION" "s3://$BUCKET/$PREFIX/"
fi
