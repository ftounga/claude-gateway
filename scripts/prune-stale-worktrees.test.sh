#!/usr/bin/env bash
#
# prune-stale-worktrees.test.sh — REPO / SF-REPO-02
#
# Test d'integration de scripts/prune-stale-worktrees.sh.
# Cree un depot Git jetable (dans un repertoire temporaire, jamais dans ce depot) avec
# trois worktrees couvrant les cas nominal / dirty / unmerged, puis verifie les
# garde-fous, la non-destructivite du dry-run, l'idempotence et le refus depuis un
# worktree lie.
#
# Usage : scripts/prune-stale-worktrees.test.sh
# Sortie : code 0 si tous les cas passent, 1 sinon.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SCRIPT_DIR/prune-stale-worktrees.sh"

if [[ ! -x "$SCRIPT" ]]; then
    echo "ERREUR: $SCRIPT introuvable ou non executable" >&2
    exit 1
fi

SANDBOX="$(mktemp -d -t prune-stale-worktrees-test.XXXXXX)"
cleanup() { rm -rf "$SANDBOX"; }
trap cleanup EXIT

# Isoler totalement la config Git de l'utilisateur : le sandbox ne doit rien heriter.
export GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

failures=0
check() {
    if [[ "$1" == "ok" ]]; then
        echo "    ok   — $2"
    else
        echo "    FAIL — $2"
        failures=$((failures + 1))
    fi
}

# --- Montage du depot jetable -----------------------------------------------------------
git init --quiet --bare "$SANDBOX/origin.git"
git init --quiet "$SANDBOX/repo"
git -C "$SANDBOX/repo" symbolic-ref HEAD refs/heads/main
cd "$SANDBOX/repo"
git remote add origin "$SANDBOX/origin.git"
echo one > f.txt
mkdir -p deep
echo live > deep/live.txt
git add f.txt deep/live.txt
git commit --quiet -m "c1"
git push --quiet -u origin main 2>/dev/null
mkdir -p .claude/worktrees

# A : propre, HEAD ancetre de origin/main            -> doit etre RETIRE
git worktree add --quiet -b wt-clean .claude/worktrees/wf_test-1 HEAD
# B : porte un commit non merge                      -> doit etre CONSERVE (unmerged)
git worktree add --quiet -b wt-unmerged .claude/worktrees/wf_test-2 HEAD
echo extra > .claude/worktrees/wf_test-2/extra.txt
git -C .claude/worktrees/wf_test-2 add extra.txt
git -C .claude/worktrees/wf_test-2 commit --quiet -m "travail non merge"
# C : historique a jour mais fichier non commite     -> doit etre CONSERVE (dirty)
git worktree add --quiet -b wt-dirty .claude/worktrees/wf_test-3 HEAD
echo scratch > .claude/worktrees/wf_test-3/scratch.txt

snapshot() { { git worktree list; echo '--'; git branch --list; } | sort; }

# --- Cas 1 : garde-fou d'inactivite (defaut 60 min) --------------------------------------
echo "[1] garde-fou 'recently-active' (defaut 60 min)"
out="$(bash "$SCRIPT" --no-fetch)"
[[ "$(grep -c 'recently-active' <<<"$out")" -eq 3 ]] \
    && check ok "les 3 worktrees recents sont ecartes" \
    || check ko "les 3 worktrees recents auraient du etre ecartes"
grep -q 'worktrees retires  : 0' <<<"$out" \
    && check ok "aucun retrait planifie" || check ko "un retrait a ete planifie a tort"

# --- Cas 2 : dry-run non destructif ------------------------------------------------------
echo "[2] dry-run non destructif (--age-minutes 0)"
before="$(snapshot)"
out="$(bash "$SCRIPT" --no-fetch --age-minutes 0)"
after="$(snapshot)"
[[ "$before" == "$after" ]] \
    && check ok "etat Git identique avant/apres" || check ko "le dry-run a modifie l'etat"
grep -q 'REMOVE  .claude/worktrees/wf_test-1' <<<"$out" \
    && check ok "wf_test-1 (propre + merge) planifie au retrait" || check ko "wf_test-1 non planifie"
grep -q 'wf_test-2.*unmerged' <<<"$out" \
    && check ok "wf_test-2 ecarte pour travail non merge" || check ko "wf_test-2 non ecarte"
grep -q 'wf_test-3.*dirty' <<<"$out" \
    && check ok "wf_test-3 ecarte pour modifications non commitees" || check ko "wf_test-3 non ecarte"
grep -q 'DELETE  wt-clean' <<<"$out" \
    && check ok "le dry-run predit la suppression de branche" || check ko "suppression de branche non predite"

# --- Cas 3 : --apply ---------------------------------------------------------------------
echo "[3] --apply"
bash "$SCRIPT" --no-fetch --age-minutes 0 --apply >/dev/null
[[ ! -d .claude/worktrees/wf_test-1 ]] \
    && check ok "wf_test-1 retire" || check ko "wf_test-1 aurait du etre retire"
[[ -d .claude/worktrees/wf_test-2 ]] \
    && check ok "wf_test-2 (unmerged) conserve" || check ko "wf_test-2 supprime a tort"
[[ -d .claude/worktrees/wf_test-3 ]] \
    && check ok "wf_test-3 (dirty) conserve" || check ko "wf_test-3 supprime a tort"
[[ -f .claude/worktrees/wf_test-3/scratch.txt ]] \
    && check ok "aucun travail non commite perdu" || check ko "travail non commite perdu"
git rev-parse --verify --quiet refs/heads/wt-clean >/dev/null \
    && check ko "branche wt-clean aurait du etre supprimee" || check ok "branche wt-clean supprimee"
git rev-parse --verify --quiet refs/heads/wt-unmerged >/dev/null \
    && check ok "branche wt-unmerged conservee" || check ko "branche wt-unmerged supprimee a tort"
git rev-parse --verify --quiet refs/heads/main >/dev/null \
    && check ok "branche main intacte" || check ko "branche main perdue"

# --- Cas 4 : idempotence -----------------------------------------------------------------
echo "[4] idempotence"
if bash "$SCRIPT" --no-fetch --age-minutes 0 --apply >/dev/null 2>&1; then
    check ok "re-execution sans erreur"
else
    check ko "la re-execution a echoue"
fi

# --- Cas 5 : refus depuis un worktree lie ------------------------------------------------
echo "[5] refus depuis un worktree lie"
set +e
( cd .claude/worktrees/wf_test-2 && bash "$SCRIPT" --no-fetch --age-minutes 0 --apply ) >/dev/null 2>&1
code=$?
set -e
[[ "$code" -eq 3 ]] \
    && check ok "sortie 3 (refus)" || check ko "sortie $code au lieu de 3"

# --- Cas 6 : fetch en echec = STOP -------------------------------------------------------
echo "[6] echec de fetch => STOP"
git remote set-url origin "$SANDBOX/inexistant.git"
set +e
bash "$SCRIPT" --age-minutes 0 --apply >/dev/null 2>&1
code=$?
set -e
[[ "$code" -eq 4 ]] \
    && check ok "sortie 4 (fetch impossible)" || check ko "sortie $code au lieu de 4"

# --- Cas 7 : activite recente EN PROFONDEUR, racine ancienne -----------------------------
# Regression : tester la seule mtime de la racine du worktree ne suffit pas. Elle ne change
# que si une entree est creee/supprimee a la racine — un agent editant un fichier imbrique
# la laisse intacte, et le worktree serait juge inactif alors qu'une session y travaille.
echo "[7] activite recente sur un fichier imbrique, racine backdatee"
git remote set-url origin "$SANDBOX/origin.git"
git worktree add --quiet -b wt-deep .claude/worktrees/wf_test-4 HEAD
# `touch` sur un fichier suivi ne change que la mtime : le worktree reste propre et merge,
# donc seul le garde-fou d'age peut l'ecarter.
touch .claude/worktrees/wf_test-4/deep/live.txt
old="$(date -d '3 hours ago' '+%Y%m%d%H%M')"
touch -t "$old" .claude/worktrees/wf_test-4
for meta in index HEAD logs/HEAD; do
    [[ -e ".git/worktrees/wf_test-4/$meta" ]] && touch -t "$old" ".git/worktrees/wf_test-4/$meta"
done
out="$(bash "$SCRIPT" --no-fetch)"
grep -q 'wf_test-4.*recently-active' <<<"$out" \
    && check ok "worktree ecarte grace au fichier imbrique recent" \
    || check ko "worktree juge inactif alors qu'un fichier imbrique est recent (regression mtime racine)"
grep -q 'REMOVE  .claude/worktrees/wf_test-4' <<<"$out" \
    && check ko "wf_test-4 planifie au retrait a tort" \
    || check ok "aucun retrait planifie pour wf_test-4"
# Contrôle positif : sans garde-fou d'age, il redevient candidat (propre + merge).
out="$(bash "$SCRIPT" --no-fetch --age-minutes 0)"
grep -q 'REMOVE  .claude/worktrees/wf_test-4' <<<"$out" \
    && check ok "candidat a nouveau avec --age-minutes 0 (propre + merge)" \
    || check ko "wf_test-4 aurait du redevenir candidat avec --age-minutes 0"

echo
if [[ "$failures" -eq 0 ]]; then
    echo "TOUS LES CAS PASSES"
    exit 0
fi
echo "$failures cas en echec"
exit 1
