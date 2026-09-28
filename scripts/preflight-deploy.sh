#!/usr/bin/env bash
#
# preflight-deploy.sh — F-84 / SF-84-09
#
# Repond a une seule question, avant de derouler docs/DEPLOYMENT.md : ce deploiement
# peut-il partir maintenant ?
#
# RAPPEL QUI CHANGE TOUT : il n'y a qu'UN environnement, et c'est la PRODUCTION. Le
# namespace s'appelle claude-gateway-staging et le profil Spring staging pour des raisons
# historiques, mais il sert portal.ng-itconsulting.com avec de vrais utilisateurs. Aucune
# vague de livraison ne deploie d'elle-meme : un seul deploiement, a la fin, par un humain.
#
# STRICTEMENT EN LECTURE SEULE : aucun apply, create, delete, push, commit, checkout ni
# stash. Ce script ne touche ni le depot, ni le cluster, ni AWS. Les inspections Git
# passent par --no-optional-locks, sans quoi Git reecrit l'index au passage (lecon
# SF-SP-01 : cette ecriture invisible suffit a fausser le controle suivant).
#
# Controles BLOQUANTS :
#   G1  le profil AWS est celui du deploiement, et l'identite repond
#   G2  le contexte kubectl pointe le bon cluster, et le namespace existe
#   G3  l'arbre est propre et HEAD est CONTENU dans origin/main
#   G4  le depot porte les reglages de drainage (SF-84-08) : sans eux, l'apply les RETIRE
#       du cluster et le deploiement se remet a tuer les tours d'agent en cours
#   G5  les TROIS images portent le tag du commit dans ECR (backend, frontend,
#       diagram-renderer) — l'oubli se paie par un ImagePullBackOff (vecu le 2026-09-22)
#
# Signaux INFORMATIFS, qui n'inversent jamais le verdict :
#   I1  ce qui tourne deja, et tout pod deja Terminating (un deploiement est peut-etre
#       en cours)
#   I2  depuis SF-84-08 un rollout dure plus longtemps : le pod draine ses tours
#   I3  la garde verifie la CONFIGURATION, pas l'instant. Si un tour long est en cours et
#       connu, prevenir le PO et le laisser choisir le moment.
#
# Usage :
#   scripts/preflight-deploy.sh                    # verdict lisible
#   scripts/preflight-deploy.sh --tag staging-abc  # forcer le tag d'image
#   scripts/preflight-deploy.sh --no-ecr           # avant le build des images (saute G5)
#   scripts/preflight-deploy.sh --no-git           # ne pas juger l'etat du depot (saute G3)
#   scripts/preflight-deploy.sh --profile X --cluster Y --namespace Z --base origin/main
#   scripts/preflight-deploy.sh --quiet            # n'imprimer que le verdict
#   scripts/preflight-deploy.sh --help
#
# Sorties :
#   0  GO           — tous les controles bloquants sont verts
#   1  NO-GO        — au moins un controle bloquant est rouge
#   2  usage        — option inconnue ou valeur invalide
#   4  INDETERMINE  — etat non evaluable (hors depot, outil absent, AWS ou cluster muet)
#
# Mini-spec : docs/features/F-84/SF-84-09-la-garde-avant-deploiement.md

set -euo pipefail

EXPECTED_PROFILE="legalcase-terraform"
EXPECTED_REGION="${AWS_REGION:-eu-west-3}"
EXPECTED_CLUSTER="legalcase-shared"
NAMESPACE="claude-gateway-staging"
BASE_REF="origin/main"
TAG=""
CHECK_ECR=1
CHECK_GIT=1
QUIET=0

ECR_REPOS=(claude-gateway-backend claude-gateway-frontend claude-gateway-diagram-renderer)

# Affiche l'en-tete de commentaire de ce fichier (tout ce qui suit le shebang jusqu'a la
# premiere ligne non commentee).
usage() {
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
}

usage_error() {
    echo "preflight-deploy: $1" >&2
    echo "Essayez : $0 --help" >&2
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --profile) [[ $# -ge 2 && -n "${2:-}" ]] || usage_error "--profile attend une valeur"
                   EXPECTED_PROFILE="$2"; shift 2 ;;
        --cluster) [[ $# -ge 2 && -n "${2:-}" ]] || usage_error "--cluster attend une valeur"
                   EXPECTED_CLUSTER="$2"; shift 2 ;;
        --namespace) [[ $# -ge 2 && -n "${2:-}" ]] || usage_error "--namespace attend une valeur"
                   NAMESPACE="$2"; shift 2 ;;
        --base)    [[ $# -ge 2 && -n "${2:-}" ]] || usage_error "--base attend une valeur"
                   BASE_REF="$2"; shift 2 ;;
        --tag)     [[ $# -ge 2 && -n "${2:-}" ]] || usage_error "--tag attend une valeur"
                   TAG="$2"; shift 2 ;;
        --no-ecr)  CHECK_ECR=0; shift ;;
        --no-git)  CHECK_GIT=0; shift ;;
        --quiet)   QUIET=1; shift ;;
        *)         usage_error "option inconnue : $1" ;;
    esac
done

BLOCKERS=()
NOTES=()

say() { [[ "$QUIET" -eq 1 ]] || echo "$1"; }
block() { BLOCKERS+=("$1"); }
note() { NOTES+=("$1"); }

indeterminate() {
    echo "INDETERMINE — $1" >&2
    exit 4
}

# ─── Le depot ────────────────────────────────────────────────────────────────────────────
REPO_ROOT="$(git --no-optional-locks rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || indeterminate "hors d'un depot Git : impossible de savoir quoi deployer"

# ─── Les outils ──────────────────────────────────────────────────────────────────────────
for tool in aws kubectl; do
    command -v "$tool" >/dev/null 2>&1 \
        || indeterminate "outil absent du PATH : $tool (voir docs/DEPLOYMENT.md, Pre-requis)"
done

say "==> Cible : namespace $NAMESPACE (= PRODUCTION) sur $EXPECTED_CLUSTER, profil $EXPECTED_PROFILE"

# ─── G1 — le profil AWS ──────────────────────────────────────────────────────────────────
if [[ -n "${AWS_PROFILE:-}" && "${AWS_PROFILE}" != "$EXPECTED_PROFILE" ]]; then
    block "G1 profil AWS : l'environnement impose AWS_PROFILE=${AWS_PROFILE}, le deploiement attend $EXPECTED_PROFILE"
fi
if ! CALLER="$(aws --profile "$EXPECTED_PROFILE" --region "$EXPECTED_REGION" \
        sts get-caller-identity --query Account --output text 2>/dev/null)"; then
    indeterminate "identite AWS non resolue sur le profil $EXPECTED_PROFILE (session expiree ?)"
fi
say "    G1 identite AWS : compte $CALLER"

# ─── G2 — le cluster et le namespace ─────────────────────────────────────────────────────
if ! CONTEXT="$(kubectl config current-context 2>/dev/null)"; then
    indeterminate "aucun contexte kubectl courant (aws eks update-kubeconfig ...)"
fi
if [[ "$CONTEXT" != *"$EXPECTED_CLUSTER"* ]]; then
    block "G2 cluster : le contexte kubectl courant est « $CONTEXT », attendu un contexte de $EXPECTED_CLUSTER"
elif ! kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
    block "G2 namespace : $NAMESPACE introuvable sur $CONTEXT"
else
    say "    G2 cluster : $CONTEXT, namespace $NAMESPACE present"
fi

# ─── G3 — ce qu'on s'apprete a mettre en production ──────────────────────────────────────
HEAD_SHORT="$(git --no-optional-locks -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || true)"
[[ -n "$HEAD_SHORT" ]] || indeterminate "HEAD introuvable : depot sans commit ?"
[[ -n "$TAG" ]] || TAG="staging-$HEAD_SHORT"

if [[ "$CHECK_GIT" -eq 1 ]]; then
    BLOCKERS_BEFORE_G3=${#BLOCKERS[@]}
    DIRTY="$(git --no-optional-locks -C "$REPO_ROOT" status --porcelain 2>/dev/null | head -n 20)"
    if [[ -n "$DIRTY" ]]; then
        block "G3 arbre sale : le tag d'image vaut le SHA de HEAD, mais l'arbre contient des modifications non commitees"
    fi
    if ! git --no-optional-locks -C "$REPO_ROOT" rev-parse --verify --quiet "$BASE_REF" >/dev/null 2>&1; then
        block "G3 base introuvable : $BASE_REF (git fetch origin ?)"
    elif ! git --no-optional-locks -C "$REPO_ROOT" merge-base --is-ancestor HEAD "$BASE_REF" 2>/dev/null; then
        block "G3 HEAD hors de $BASE_REF : on ne met pas en production un commit absent de la base"
    fi
    [[ ${#BLOCKERS[@]} -gt "$BLOCKERS_BEFORE_G3" ]] \
        || say "    G3 depot : arbre propre, HEAD ($HEAD_SHORT) contenu dans $BASE_REF"
else
    say "    G3 depot : saute (--no-git)"
fi

# ─── G4 — le depot porte-t-il encore le drainage ? (SF-84-08) ────────────────────────────
# Le coeur de cette garde. Un `kubectl apply -k` ecrase la configuration vivante par celle du
# depot : si le drainage n'est pas DANS les fichiers, le deploiement le RETIRE du cluster, et
# la production se remet a tuer les tours d'agent en cours. C'est la forme exacte du defaut de
# F-77, ou le correctif applique a la main n'existait dans aucun fichier.
DEPLOYMENT_YAML="$REPO_ROOT/k8s/base/backend/deployment.yaml"
APPLICATION_YML="$REPO_ROOT/backend/src/main/resources/application.yml"

drain_setting() {
    local file="$1" pattern="$2" label="$3"
    if [[ ! -f "$file" ]]; then
        block "G4 drainage : fichier introuvable — ${file#"$REPO_ROOT"/}"
        return
    fi
    grep -Eq "$pattern" "$file" \
        || block "G4 drainage : reglage manquant — $label (${file#"$REPO_ROOT"/})"
}

drain_setting "$DEPLOYMENT_YAML" '^[[:space:]]*terminationGracePeriodSeconds:[[:space:]]*[0-9]+' \
    "terminationGracePeriodSeconds"
drain_setting "$DEPLOYMENT_YAML" '^[[:space:]]*maxUnavailable:[[:space:]]*0[[:space:]]*$' \
    "maxUnavailable: 0"
drain_setting "$APPLICATION_YML" '^[[:space:]]*shutdown:[[:space:]]*graceful[[:space:]]*$' \
    "server.shutdown: graceful"
drain_setting "$APPLICATION_YML" '^[[:space:]]*turn-drain-seconds:' \
    "app.shutdown.turn-drain-seconds"

# ─── G5 — les trois images, un seul tag ──────────────────────────────────────────────────
if [[ "$CHECK_ECR" -eq 1 ]]; then
    for repo in "${ECR_REPOS[@]}"; do
        if aws --profile "$EXPECTED_PROFILE" --region "$EXPECTED_REGION" ecr describe-images \
                --repository-name "$repo" --image-ids "imageTag=$TAG" >/dev/null 2>&1; then
            say "    G5 image : $repo:$TAG presente"
        else
            block "G5 image absente : $repo:$TAG — les TROIS images portent le tag du commit"
        fi
    done
else
    say "    G5 images : saute (--no-ecr), tag attendu $TAG"
fi

# ─── I1 — ce qui tourne deja ─────────────────────────────────────────────────────────────
if PODS="$(kubectl -n "$NAMESPACE" get pods --no-headers 2>/dev/null)"; then
    RUNNING="$(printf '%s\n' "$PODS" | grep -c 'Running' || true)"
    TERMINATING="$(printf '%s\n' "$PODS" | grep -c 'Terminating' || true)"
    note "I1 pods : $RUNNING en Running, $TERMINATING en Terminating"
    if [[ "$TERMINATING" -gt 0 ]]; then
        note "I1 un pod draine deja : un deploiement est peut-etre en cours. Attendre sa fin."
    fi
fi
note "I2 depuis SF-84-08, un rollout backend peut prendre plusieurs minutes de plus : le pod attend la fin des tours d'agent avant de mourir (300 s au plus)."
note "I3 cette garde verifie la CONFIGURATION du drainage, pas l'instant. Un tour plus long que le drainage reste perdu : si un tour long est connu, prevenir le PO et le laisser choisir le moment."

# ─── Verdict ─────────────────────────────────────────────────────────────────────────────
if [[ "$QUIET" -eq 0 ]]; then
    echo
    for n in "${NOTES[@]}"; do echo "  $n"; done
    echo
fi

if [[ ${#BLOCKERS[@]} -gt 0 ]]; then
    echo "NO-GO — ${#BLOCKERS[@]} controle(s) bloquant(s)"
    for b in "${BLOCKERS[@]}"; do echo "  - $b"; done
    exit 1
fi

echo "GO — tag $TAG, namespace $NAMESPACE (PRODUCTION). Derouler docs/DEPLOYMENT.md."
exit 0
