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
# Comme le depot reel (.gitignore, ligne `.claude/worktrees/`) : sans cela le checkout
# principal est perpetuellement sale, et le garde-fou 5 (SF-SP-02) refuserait toute purge
# sur du bruit de test plutot que sur une vraie session parallele.
printf '.claude/worktrees/\n' > .gitignore
echo one > f.txt
mkdir -p deep
echo live > deep/live.txt
git add .gitignore f.txt deep/live.txt
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

# --- Cas 8 : balayage global des branches orphelines detachees ---------------------------
# SF-WO-01 : une branche squash-mergee dont plus aucun worktree ne depend est invisible pour
# un balayage derive des seuls retraits de worktree. C'est pourtant le cas majoritaire.
echo "[8] balayage global : branche orpheline sans worktree"
git branch orphan-merged HEAD
# Contrôle negatif : meme forme (aucun worktree), mais un commit propre -> intouchable.
git branch orphan-ahead HEAD
git checkout --quiet orphan-ahead
echo ahead > ahead.txt
git add ahead.txt
git commit --quiet -m "travail non merge, hors worktree"
git checkout --quiet main
out="$(bash "$SCRIPT" --no-fetch --age-minutes 0)"
grep -q 'DELETE  orphan-merged' <<<"$out" \
    && check ok "orphan-merged planifiee au DELETE" \
    || check ko "orphan-merged non planifiee (balayage global inoperant)"
grep -q 'KEEP    orphan-ahead .*commit(s) non merge' <<<"$out" \
    && check ok "orphan-ahead conservee (travail non merge)" \
    || check ko "orphan-ahead non protegee"
grep -q 'restauration: git branch orphan-merged' <<<"$out" \
    && check ok "commande de restauration affichee" || check ko "restauration non affichee"

# --- Cas 9 : branche d'un worktree CONSERVE ---------------------------------------------
# wt-dirty a 0 commit '+' mais son worktree survit (garde-fou dirty) : la supprimer
# arracherait la branche sous les pieds de la session qui y travaille.
echo "[9] branche d'un worktree conserve"
grep -q 'DELETE  wt-dirty' <<<"$out" \
    && check ko "wt-dirty planifiee au DELETE alors que son worktree est conserve" \
    || check ok "wt-dirty non planifiee (detenue par un worktree conserve)"
grep -q 'KEEP    main' <<<"$out" \
    && check ok "main explicitement protegee" || check ko "main non protegee"

# --- Cas 10 : --no-sweep-branches restaure le comportement SF-REPO-02 --------------------
echo "[10] --no-sweep-branches"
out_nosweep="$(bash "$SCRIPT" --no-fetch --age-minutes 0 --no-sweep-branches)"
grep -q 'DELETE  orphan-merged' <<<"$out_nosweep" \
    && check ko "orphan-merged planifiee malgre --no-sweep-branches" \
    || check ok "orphan-merged ignoree avec --no-sweep-branches"

# --- Cas 11 : --apply du balayage global -------------------------------------------------
echo "[11] --apply du balayage global"
bash "$SCRIPT" --no-fetch --age-minutes 0 --apply >/dev/null
git rev-parse --verify --quiet refs/heads/orphan-merged >/dev/null \
    && check ko "orphan-merged aurait du etre supprimee" || check ok "orphan-merged supprimee"
git rev-parse --verify --quiet refs/heads/orphan-ahead >/dev/null \
    && check ok "orphan-ahead conservee" || check ko "orphan-ahead supprimee a tort"
git rev-parse --verify --quiet refs/heads/wt-dirty >/dev/null \
    && check ok "wt-dirty conservee (worktree vivant)" || check ko "wt-dirty supprimee a tort"
git rev-parse --verify --quiet refs/heads/main >/dev/null \
    && check ok "main intacte apres balayage global" || check ko "main perdue"
[[ -f .claude/worktrees/wf_test-3/scratch.txt ]] \
    && check ok "aucun travail non commite perdu" || check ko "travail non commite perdu"

# --- Cas 12 : enregistrement de worktree perime (repertoire efface a la main) ------------
# Tant que l'enregistrement survit, Git refuse de supprimer la branche qu'il declare checked
# out : le prune doit donc passer AVANT la purge des branches, sinon `git branch -D` echoue.
echo "[12] enregistrement perime : prune avant purge de branche"
git worktree add --quiet -b wt-stale .claude/worktrees/wf_test-5 HEAD
rm -rf .claude/worktrees/wf_test-5
if bash "$SCRIPT" --no-fetch --age-minutes 0 --apply >/dev/null 2>&1; then
    check ok "run termine sans erreur malgre l'enregistrement perime"
else
    check ko "le run a echoue sur l'enregistrement perime"
fi
git rev-parse --verify --quiet refs/heads/wt-stale >/dev/null \
    && check ko "wt-stale aurait du etre supprimee apres le prune" \
    || check ok "wt-stale supprimee (prune execute avant la purge)"

# --- Garde-fou 5 (SF-SP-02) : la purge refuse de detruire pendant qu'une session vole ----
# Recule dans le temps un worktree entier — contenu, racine et metadonnees Git. Sans les
# metadonnees, la moindre commande Git executee dedans le ferait repasser pour « actif ».
backdate_wt() {
    local wt="$1" stamp meta
    stamp="$(date -d '3 hours ago' '+%Y%m%d%H%M')"
    find "$wt" -exec touch -t "$stamp" {} + 2>/dev/null || true
    touch -t "$stamp" "$wt"
    for meta in index HEAD logs/HEAD ORIG_HEAD; do
        [[ -e ".git/worktrees/$(basename "$wt")/$meta" ]] &&
            touch -t "$stamp" ".git/worktrees/$(basename "$wt")/$meta"
    done
    return 0
}
backdate_all() {
    local d
    for d in .claude/worktrees/*/; do
        [[ -d "$d" ]] && backdate_wt "${d%/}"
    done
    return 0
}

echo "[13] garde-fou 5 : session parallele en vol"
# Controle negatif d'abord : depot au repos, la purge doit fonctionner normalement.
git worktree add --quiet -b wt-victim-a .claude/worktrees/wf_test-6 HEAD
backdate_all
if bash "$SCRIPT" --no-fetch --apply >/dev/null 2>&1; then
    check ok "controle negatif : purge executee quand aucune session ne vole"
else
    check ko "la purge a echoue alors qu'aucune session ne vole"
fi
[[ ! -d .claude/worktrees/wf_test-6 ]] \
    && check ok "controle negatif : le worktree residuel a bien ete retire" \
    || check ko "wf_test-6 aurait du etre retire"

# Puis une session vivante : victime supprimable + worktree actif (fichier imbrique).
git worktree add --quiet -b wt-victim-b .claude/worktrees/wf_test-8 HEAD
git worktree add --quiet -b wt-live .claude/worktrees/wf_test-7 HEAD
backdate_all
touch .claude/worktrees/wf_test-7/deep/live.txt
set +e
bash "$SCRIPT" --no-fetch --apply >/dev/null 2>&1
code=$?
set -e
[[ "$code" -eq 5 ]] && check ok "sortie 5 (session en vol)" || check ko "sortie $code au lieu de 5"
[[ -d .claude/worktrees/wf_test-8 ]] \
    && check ok "RIEN n'a ete detruit : la victime est intacte" \
    || check ko "un worktree a ete detruit malgre le refus"

# Echappatoire : --force-busy fait aboutir la meme purge.
set +e
bash "$SCRIPT" --no-fetch --apply --force-busy >/dev/null 2>&1
code=$?
set -e
[[ "$code" -eq 0 ]] && check ok "--force-busy fait aboutir la purge" || check ko "--force-busy => sortie $code"
[[ ! -d .claude/worktrees/wf_test-8 ]] \
    && check ok "la victime est retiree sous --force-busy" || check ko "wf_test-8 non retire"
[[ -d .claude/worktrees/wf_test-7 ]] \
    && check ok "le worktree actif reste protege par le garde-fou 2" \
    || check ko "le worktree actif a ete detruit"

# --- Cas 14 : collision de branche = refus egalement, mais le dry-run reste consultable --
echo "[14] garde-fou 5 : collision de branche"
git worktree add --quiet -b wt-shared .claude/worktrees/wf_test-9 HEAD
git worktree add --quiet --force .claude/worktrees/wf_test-10 wt-shared
backdate_all
set +e
bash "$SCRIPT" --no-fetch --apply >/dev/null 2>&1
code=$?
set -e
[[ "$code" -eq 5 ]] && check ok "sortie 5 (branche partagee par 2 worktrees)" \
    || check ko "sortie $code au lieu de 5"
set +e
bash "$SCRIPT" --no-fetch >/dev/null 2>&1
code=$?
set -e
[[ "$code" -eq 0 ]] \
    && check ok "le dry-run reste consultable pendant une vague" \
    || check ko "le dry-run a ete bloque (sortie $code)"
git worktree remove --force .claude/worktrees/wf_test-10
git worktree remove --force .claude/worktrees/wf_test-9

# --- Cas 15 : controle de vol indisponible => avertissement, pas blocage -----------------
# Defense en profondeur : l'absence du controle ne doit pas rendre la purge inutilisable.
echo "[15] controle de vol introuvable"
mkdir -p "$SANDBOX/nopreflight/lib"
cp "$SCRIPT" "$SANDBOX/nopreflight/prune-stale-worktrees.sh"
cp "$SCRIPT_DIR/lib/git-worktrees.sh" "$SANDBOX/nopreflight/lib/git-worktrees.sh"
git worktree add --quiet -b wt-victim-c .claude/worktrees/wf_test-11 HEAD
backdate_all
touch .claude/worktrees/wf_test-7/deep/live.txt
set +e
err="$(bash "$SANDBOX/nopreflight/prune-stale-worktrees.sh" --no-fetch --apply 2>&1 >/dev/null)"
code=$?
set -e
[[ "$code" -eq 0 ]] && check ok "purge executee malgre l'absence du controle" \
    || check ko "sortie $code : l'absence du controle a bloque la purge"
grep -q 'AVERTISSEMENT' <<<"$err" \
    && check ok "avertissement emis sur stderr" || check ko "aucun avertissement emis"
[[ ! -d .claude/worktrees/wf_test-11 ]] \
    && check ok "le worktree residuel a bien ete retire" || check ko "wf_test-11 non retire"

# --- Cas 16 a 18 (SF-BO-01) : --branches-only -------------------------------------------
# Le garde-fou 5 refuse le run ENTIER pendant qu'une vague tourne. Comme le depot n'est
# jamais au repos, la purge de branches — qui ne detruit aucun repertoire de travail —
# n'a jamais pu tourner. --branches-only separe les deux operations.

# Recule la mtime de tous les reflogs de branches : sans cela, toute branche creee par le
# test serait « recently-touched » et conservee. C'est le filtre du cas 17.
backdate_reflogs() {
    local stamp
    stamp="$(date -d '3 hours ago' '+%Y%m%d%H%M')"
    find .git/logs/refs/heads -type f -exec touch -t "$stamp" {} + 2>/dev/null || true
    return 0
}

echo "[16] --branches-only : purger les branches pendant qu'une vague tourne"
# Une victime supprimable en mode complet, un enregistrement perime, une orpheline detachee.
git worktree add --quiet -b wt-victim-d .claude/worktrees/wf_test-12 HEAD
git worktree add --quiet -b wt-stale2 .claude/worktrees/wf_test-13 HEAD
rm -rf .claude/worktrees/wf_test-13
git branch bo-orphan HEAD
backdate_all
backdate_reflogs
# Une session vivante : fichier imbrique touche a l'instant.
touch .claude/worktrees/wf_test-7/deep/live.txt

# Contrôle negatif : le mode complet refuse toujours (le garde-fou 5 reste arme).
set +e
bash "$SCRIPT" --no-fetch --apply >/dev/null 2>&1
code=$?
set -e
[[ "$code" -eq 5 ]] \
    && check ok "contrôle negatif : le mode complet refuse toujours (sortie 5)" \
    || check ko "sortie $code au lieu de 5 : le garde-fou 5 n'est plus arme"

# Le mode --branches-only, lui, aboutit.
set +e
out="$(bash "$SCRIPT" --no-fetch --branches-only --apply 2>&1)"
code=$?
set -e
[[ "$code" -eq 0 ]] \
    && check ok "--branches-only aboutit malgre la session en vol" \
    || check ko "sortie $code : --branches-only a ete bloque"
git rev-parse --verify --quiet refs/heads/bo-orphan >/dev/null \
    && check ko "bo-orphan aurait du etre supprimee" || check ok "bo-orphan supprimee"
git rev-parse --verify --quiet refs/heads/orphan-ahead >/dev/null \
    && check ok "orphan-ahead conservee (travail non merge)" || check ko "orphan-ahead supprimee a tort"
git rev-parse --verify --quiet refs/heads/main >/dev/null \
    && check ok "main intacte" || check ko "main perdue"
[[ -d .claude/worktrees/wf_test-12 ]] \
    && check ok "AUCUN worktree retire : la victime du mode complet est intacte" \
    || check ko "wf_test-12 a ete retire alors que --branches-only l'interdit"
git rev-parse --verify --quiet refs/heads/wt-victim-d >/dev/null \
    && check ok "la branche d'un worktree vivant est protegee" \
    || check ko "wt-victim-d supprimee alors que son worktree existe"
[[ -d .claude/worktrees/wf_test-7 ]] \
    && check ok "le worktree actif est intact" || check ko "le worktree actif a ete detruit"
git worktree list --porcelain | grep -q 'wf_test-13' \
    && check ok "l'enregistrement perime n'est pas prunne" \
    || check ko "--branches-only a prunne un enregistrement de worktree"
git rev-parse --verify --quiet refs/heads/wt-stale2 >/dev/null \
    && check ok "la branche d'un enregistrement perime est conservee" \
    || check ko "wt-stale2 supprimee alors que son enregistrement survit"

# --- Cas 17 : garde-fou 'recently-touched' ----------------------------------------------
# Une branche creee par une session vivante APRES la photo des worktrees serait supprimee
# sous ses pieds : elle n'a aucun commit '+' et n'est detenue par personne.
echo "[17] garde-fou 'recently-touched' sur les branches"
backdate_reflogs
git branch bo-recent HEAD   # reflog ecrit a l'instant
out="$(bash "$SCRIPT" --no-fetch --branches-only)"
grep -q 'KEEP    bo-recent .*recently-touched' <<<"$out" \
    && check ok "branche a la reference fraiche conservee" \
    || check ko "bo-recent aurait du etre conservee (recently-touched)"
out="$(bash "$SCRIPT" --no-fetch --branches-only --age-minutes 0)"
grep -q 'DELETE  bo-recent' <<<"$out" \
    && check ok "contrôle negatif : --age-minutes 0 la rend candidate" \
    || check ko "bo-recent non candidate malgre --age-minutes 0"

# --- Cas 18 : combinaison impossible => erreur d'usage -----------------------------------
echo "[18] --branches-only --no-sweep-branches : erreur d'usage"
before="$(snapshot)"
set +e
bash "$SCRIPT" --no-fetch --branches-only --no-sweep-branches --apply >/dev/null 2>&1
code=$?
set -e
[[ "$code" -eq 2 ]] && check ok "sortie 2 (usage)" || check ko "sortie $code au lieu de 2"
[[ "$before" == "$(snapshot)" ]] \
    && check ok "etat Git inchange" || check ko "l'erreur d'usage a modifie l'etat"

echo
if [[ "$failures" -eq 0 ]]; then
    echo "TOUS LES CAS PASSES"
    exit 0
fi
echo "$failures cas en echec"
exit 1
