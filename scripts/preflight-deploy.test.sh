#!/usr/bin/env bash
#
# preflight-deploy.test.sh — F-84 / SF-84-09
#
# Test d'integration de scripts/preflight-deploy.sh.
#
# Monte un depot Git jetable (repertoire temporaire, jamais ce depot) et substitue aws et
# kubectl par des bouchons places en tete du PATH : aucun appel reseau, aucun cluster, aucun
# compte AWS. Chaque cas NO-GO est accompagne de son CONTROLE NEGATIF — le meme depot prive
# du defaut doit rendre GO. Un test qui ne peut pas echouer ne vaut rien (lecons SF-REPO-02
# et SF-WO-01).
#
# Les bouchons journalisent chaque appel : le dernier cas verifie qu'aucun VERBE D'ECRITURE
# (apply, create, delete, patch, scale, rollout...) n'a ete emis, et que le depot jetable est
# inchange. Une garde qui modifierait ce qu'elle inspecte ne serait pas une garde.
#
# Usage : scripts/preflight-deploy.test.sh
# Sortie : 0 si tous les cas passent, 1 sinon.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SCRIPT_DIR/preflight-deploy.sh"

if [[ ! -f "$SCRIPT" ]]; then
    echo "ERREUR: $SCRIPT introuvable" >&2
    exit 1
fi

SANDBOX="$(mktemp -d -t preflight-deploy-test.XXXXXX)"
cleanup() { rm -rf "$SANDBOX"; }
trap cleanup EXIT

# Isoler totalement la config Git de l'utilisateur : le bac a sable n'herite de rien.
export GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

BIN="$SANDBOX/bin"
CALLS="$SANDBOX/calls.log"
mkdir -p "$BIN"

failures=0
check() {
    if [[ "$1" == "ok" ]]; then
        echo "    ok   — $2"
    else
        echo "    FAIL — $2"
        failures=$((failures + 1))
    fi
}

expect() {  # expect <code attendu> <motif attendu dans la sortie> <libelle>
    local want_code="$1" want_text="$2" label="$3"
    if [[ "$CODE" != "$want_code" ]]; then
        check ko "$label (code $CODE, attendu $want_code) :: $OUT"
        return
    fi
    if [[ -n "$want_text" && "$OUT" != *"$want_text"* ]]; then
        check ko "$label (sortie sans « $want_text ») :: $OUT"
        return
    fi
    check ok "$label"
}

# ─── Les bouchons ────────────────────────────────────────────────────────────────────────
# Leur comportement est pilote par des fichiers-drapeaux du bac a sable, pour que chaque cas
# regle la situation qu'il veut sans reecrire de bouchon.

cat > "$BIN/aws" <<'STUB'
#!/usr/bin/env bash
echo "aws $*" >> "$CALLS"
if [[ -f "$SANDBOX/aws-identity-fails" ]]; then exit 255; fi
for arg in "$@"; do
    if [[ "$arg" == "sts" ]]; then echo "504895205419"; exit 0; fi
done
# ecr describe-images --repository-name <repo> --image-ids imageTag=<tag>
repo=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --repository-name) repo="$2"; shift 2 ;;
        *) shift ;;
    esac
done
if [[ -n "$repo" && -f "$SANDBOX/ecr-missing-$repo" ]]; then exit 254; fi
exit 0
STUB

cat > "$BIN/kubectl" <<'STUB'
#!/usr/bin/env bash
echo "kubectl $*" >> "$CALLS"
case "$1 ${2:-}" in
    "config current-context")
        cat "$SANDBOX/kube-context"; exit 0 ;;
esac
if [[ "$1" == "get" && "$2" == "namespace" ]]; then
    [[ -f "$SANDBOX/ns-missing" ]] && exit 1
    echo "$3   Active   1d"; exit 0
fi
if [[ "$1" == "-n" && "$3" == "get" && "$4" == "pods" ]]; then
    cat "$SANDBOX/pods" 2>/dev/null || true
    exit 0
fi
exit 0
STUB

chmod +x "$BIN/aws" "$BIN/kubectl"
export CALLS SANDBOX
export PATH="$BIN:$PATH"

# ─── Le depot jetable ────────────────────────────────────────────────────────────────────
REPO="$SANDBOX/repo"

reset_repo() {
    rm -rf "$REPO" "$SANDBOX/origin"
    mkdir -p "$REPO/k8s/base/backend" "$REPO/backend/src/main/resources"

    cat > "$REPO/k8s/base/backend/deployment.yaml" <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: claude-gateway-backend
spec:
  replicas: 2
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    spec:
      terminationGracePeriodSeconds: 660
YAML

    cat > "$REPO/backend/src/main/resources/application.yml" <<'YAML'
server:
  port: 8080
  shutdown: graceful
app:
  shutdown:
    turn-drain-seconds: ${APP_SHUTDOWN_TURN_DRAIN_SECONDS:300}
YAML

    # `git init -b main` n'existe pas avant Git 2.28 ; la branche est posee a la main pour que
    # le test tourne aussi sur les postes qui n'ont pas la derniere version.
    git -C "$REPO" init -q
    git -C "$REPO" symbolic-ref HEAD refs/heads/main
    git -C "$REPO" add -A
    git -C "$REPO" commit -q -m "base"

    # Un « origin » local : origin/main existe vraiment, sans reseau.
    git init -q --bare "$SANDBOX/origin"
    git -C "$REPO" remote add origin "$SANDBOX/origin"
    git -C "$REPO" push -q origin main
    git -C "$REPO" fetch -q origin

    # Etat par defaut des bouchons : tout va bien.
    rm -f "$SANDBOX/aws-identity-fails" "$SANDBOX/ns-missing"
    rm -f "$SANDBOX"/ecr-missing-*
    echo "arn:aws:eks:eu-west-3:504895205419:cluster/legalcase-shared" > "$SANDBOX/kube-context"
    printf 'claude-gateway-backend-1   1/1   Running   0   1d\nclaude-gateway-backend-2   1/1   Running   0   1d\n' > "$SANDBOX/pods"
    : > "$CALLS"
    unset AWS_PROFILE || true
}

run() {  # run [args...] — execute la garde DEPUIS le depot jetable
    set +e
    OUT="$(cd "$REPO" && bash "$SCRIPT" "$@" 2>&1)"
    CODE=$?
    set -e
}

echo "== preflight-deploy.sh =="

# ─── Cas 1 — tout vert ───────────────────────────────────────────────────────────────────
echo "  cas 1 — tout vert"
reset_repo
run
expect 0 "GO" "tout vert rend GO (sortie 0)"
expect 0 "staging-" "le verdict annonce le tag du commit"

# ─── Cas 2 — profil AWS impose par l'environnement ───────────────────────────────────────
echo "  cas 2 — profil AWS"
reset_repo
AWS_PROFILE=un-autre-compte run
expect 1 "G1 profil AWS" "un AWS_PROFILE etranger rend NO-GO"
reset_repo
AWS_PROFILE=legalcase-terraform run
expect 0 "GO" "controle negatif : le bon profil rend GO"

# ─── Cas 3 — mauvais cluster ─────────────────────────────────────────────────────────────
echo "  cas 3 — cluster"
reset_repo
echo "minikube" > "$SANDBOX/kube-context"
run
expect 1 "G2 cluster" "un contexte kubectl etranger rend NO-GO"
reset_repo
run
expect 0 "GO" "controle negatif : le bon contexte rend GO"

# ─── Cas 4 — namespace absent ────────────────────────────────────────────────────────────
echo "  cas 4 — namespace"
reset_repo
touch "$SANDBOX/ns-missing"
run
expect 1 "G2 namespace" "un namespace absent rend NO-GO"

# ─── Cas 5 — arbre sale ──────────────────────────────────────────────────────────────────
echo "  cas 5 — arbre sale"
reset_repo
echo "wip" > "$REPO/brouillon.txt"
run
expect 1 "G3 arbre sale" "un arbre sale rend NO-GO"
rm -f "$REPO/brouillon.txt"
run
expect 0 "GO" "controle negatif : l'arbre nettoye rend GO"

# ─── Cas 6 — HEAD hors de la base ────────────────────────────────────────────────────────
echo "  cas 6 — HEAD hors de origin/main"
reset_repo
git -C "$REPO" commit -q --allow-empty -m "commit non pousse"
run
expect 1 "G3 HEAD hors de" "un HEAD absent de origin/main rend NO-GO"
git -C "$REPO" push -q origin main
git -C "$REPO" fetch -q origin
run
expect 0 "GO" "controle negatif : le meme commit pousse rend GO"

# ─── Cas 7 — les quatre reglages de drainage, un par un ──────────────────────────────────
echo "  cas 7 — le drainage (SF-84-08)"
drain_case() {  # drain_case <fichier> <motif sed a supprimer> <libelle attendu>
    reset_repo
    sed -i "/$2/d" "$REPO/$1"
    git -C "$REPO" add -A && git -C "$REPO" commit -q -m "sans $3"
    git -C "$REPO" push -q origin main && git -C "$REPO" fetch -q origin
    run
    expect 1 "$3" "sans « $3 », la garde refuse : l'apply retirerait le drainage du cluster"
}
drain_case "k8s/base/backend/deployment.yaml" "terminationGracePeriodSeconds" "terminationGracePeriodSeconds"
drain_case "k8s/base/backend/deployment.yaml" "maxUnavailable" "maxUnavailable: 0"
drain_case "backend/src/main/resources/application.yml" "shutdown: graceful" "server.shutdown: graceful"
drain_case "backend/src/main/resources/application.yml" "turn-drain-seconds" "app.shutdown.turn-drain-seconds"
reset_repo
run
expect 0 "GO" "controle negatif : les quatre reglages presents rendent GO"

# ─── Cas 8 — les trois images ────────────────────────────────────────────────────────────
echo "  cas 8 — les trois images"
for repo in claude-gateway-backend claude-gateway-frontend claude-gateway-diagram-renderer; do
    reset_repo
    touch "$SANDBOX/ecr-missing-$repo"
    run
    expect 1 "G5 image absente : $repo" "une image manquante ($repo) rend NO-GO"
done
reset_repo
touch "$SANDBOX/ecr-missing-claude-gateway-diagram-renderer"
run --no-ecr
expect 0 "GO" "--no-ecr saute le controle des images (avant leur build)"

# ─── Cas 9 — outils absents ──────────────────────────────────────────────────────────────
# Un PATH reduit aux seuls outils dont la garde a besoin, moins celui qu'on retire. Masquer
# `aws` en le laissant ailleurs dans le PATH ne prouverait rien : la machine de test EN A un.
echo "  cas 9 — outils absents"
minimal_path() {  # minimal_path <repertoire> <outil a omettre>
    local dir="$1" omit="$2" tool real
    rm -rf "$dir"; mkdir -p "$dir"
    for tool in bash sh env git awk grep sed head cat find sort; do
        real="$(command -v "$tool" 2>/dev/null || true)"
        [[ -n "$real" ]] && ln -sf "$real" "$dir/$tool"
    done
    [[ "$omit" == "aws" ]] || ln -sf "$BIN/aws" "$dir/aws"
    [[ "$omit" == "kubectl" ]] || ln -sf "$BIN/kubectl" "$dir/kubectl"
}

reset_repo
minimal_path "$SANDBOX/noaws" aws
set +e
OUT="$(cd "$REPO" && PATH="$SANDBOX/noaws" bash "$SCRIPT" 2>&1)"; CODE=$?
set -e
expect 4 "outil absent du PATH : aws" "aws absent du PATH rend INDETERMINE (sortie 4)"

minimal_path "$SANDBOX/nokubectl" kubectl
set +e
OUT="$(cd "$REPO" && PATH="$SANDBOX/nokubectl" bash "$SCRIPT" 2>&1)"; CODE=$?
set -e
expect 4 "outil absent du PATH : kubectl" "kubectl absent du PATH rend INDETERMINE (sortie 4)"

# ─── Cas 10 — identite AWS en echec ──────────────────────────────────────────────────────
echo "  cas 10 — identite AWS"
reset_repo
touch "$SANDBOX/aws-identity-fails"
run
expect 4 "identite AWS" "une session AWS expiree rend INDETERMINE (sortie 4)"

# ─── Cas 11 — hors depot Git ─────────────────────────────────────────────────────────────
echo "  cas 11 — hors depot"
HORS="$SANDBOX/hors-depot"; mkdir -p "$HORS"
set +e
OUT="$(cd "$HORS" && GIT_CEILING_DIRECTORIES="$SANDBOX" bash "$SCRIPT" 2>&1)"; CODE=$?
set -e
expect 4 "hors d'un depot Git" "hors depot Git, la garde rend INDETERMINE"

# ─── Cas 12 — usage ──────────────────────────────────────────────────────────────────────
echo "  cas 12 — usage"
reset_repo
run --option-qui-nexiste-pas
expect 2 "option inconnue" "une option inconnue rend 2"
run --tag
expect 2 "--tag attend une valeur" "une option sans valeur rend 2"
run --help
expect 0 "preflight-deploy.sh" "--help affiche l'aide et rend 0"

# ─── Cas 13 — un pod deja en train de drainer ────────────────────────────────────────────
echo "  cas 13 — pod Terminating"
reset_repo
printf 'claude-gateway-backend-1   1/1   Running       0   1d\nclaude-gateway-backend-2   1/1   Terminating   0   1d\n' > "$SANDBOX/pods"
run
expect 0 "Terminating" "un pod qui draine est SIGNALE..."
expect 0 "GO" "...sans inverser le verdict (signal informatif)"

# ─── Cas 14 — la garde ne modifie rien ───────────────────────────────────────────────────
echo "  cas 14 — non-destructivite"
reset_repo
BEFORE_HEAD="$(git -C "$REPO" rev-parse HEAD)"
BEFORE_STATUS="$(git -C "$REPO" status --porcelain)"
BEFORE_STASH="$(git -C "$REPO" stash list)"
BEFORE_TREE="$(cd "$REPO" && find . -path ./.git -prune -o -type f -print | sort)"
run
AFTER_HEAD="$(git -C "$REPO" rev-parse HEAD)"
AFTER_STATUS="$(git -C "$REPO" status --porcelain)"
AFTER_STASH="$(git -C "$REPO" stash list)"
AFTER_TREE="$(cd "$REPO" && find . -path ./.git -prune -o -type f -print | sort)"
[[ "$BEFORE_HEAD" == "$AFTER_HEAD" ]] && check ok "HEAD inchange" || check ko "HEAD modifie"
[[ "$BEFORE_STATUS" == "$AFTER_STATUS" ]] && check ok "statut inchange" || check ko "statut modifie"
[[ "$BEFORE_STASH" == "$AFTER_STASH" ]] && check ok "pile de remise inchangee" || check ko "pile de remise modifiee"
[[ "$BEFORE_TREE" == "$AFTER_TREE" ]] && check ok "arborescence inchangee" || check ko "arborescence modifiee"

WRITE_VERBS='(apply|create|delete|patch|replace|scale|rollout|edit|annotate|label|set-context|put-|tag-resource)'
if grep -Eq "$WRITE_VERBS" "$CALLS"; then
    check ko "un verbe d'ecriture a ete emis vers aws/kubectl :: $(grep -E "$WRITE_VERBS" "$CALLS" | head -n 3)"
else
    check ok "aucun verbe d'ecriture emis vers aws/kubectl"
fi

echo
if [[ "$failures" -eq 0 ]]; then
    echo "TOUS LES CAS PASSENT"
    exit 0
fi
echo "$failures CAS EN ECHEC"
exit 1
