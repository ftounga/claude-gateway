#!/usr/bin/env bash
#
# check-parallel-sessions.test.sh — SESSIONS-PARALLELES / SF-SP-01
#
# Test d'integration de scripts/check-parallel-sessions.sh.
# Monte un depot Git jetable (repertoire temporaire, jamais ce depot) et verifie, signal par
# signal, le verdict CLEAR / BUSY, ses codes de sortie et sa non-destructivite.
#
# Chaque cas BUSY est accompagne de son CONTROLE NEGATIF : le meme depot prive du signal doit
# rendre CLEAR. Un test qui ne peut pas echouer ne vaut rien (lecons SF-REPO-02 et SF-WO-01).
#
# Usage : scripts/check-parallel-sessions.test.sh
# Sortie : 0 si tous les cas passent, 1 sinon.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SCRIPT_DIR/check-parallel-sessions.sh"

if [[ ! -f "$SCRIPT" ]]; then
    echo "ERREUR: $SCRIPT introuvable" >&2
    exit 1
fi

SANDBOX="$(mktemp -d -t check-parallel-sessions-test.XXXXXX)"
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

# Execute le script en capturant sortie ET code, sans tuer le test sous set -e.
run() {
    set +e
    OUT="$(bash "$SCRIPT" "$@" 2>&1)"
    CODE=$?
    set -e
}

# Recule dans le temps un worktree entier : contenu, racine et metadonnees Git. Sans les
# metadonnees, la moindre commande Git executee dedans (y compris par le script lui-meme)
# le ferait repasser pour « actif ».
backdate() {
    local wt="$1" stamp
    stamp="$(date -d '3 hours ago' '+%Y%m%d%H%M')"
    find "$wt" -exec touch -t "$stamp" {} + 2>/dev/null || true
    touch -t "$stamp" "$wt"
    local meta
    for meta in index HEAD logs/HEAD ORIG_HEAD; do
        [[ -e "$SANDBOX/repo/.git/worktrees/$(basename "$wt")/$meta" ]] &&
            touch -t "$stamp" "$SANDBOX/repo/.git/worktrees/$(basename "$wt")/$meta"
    done
    return 0
}

backdate_all() {
    local d
    for d in "$SANDBOX"/repo/.claude/worktrees/*/; do
        [[ -d "$d" ]] && backdate "${d%/}"
    done
    return 0
}

# --- Montage du depot jetable -----------------------------------------------------------
git init --quiet --bare "$SANDBOX/origin.git"
git init --quiet "$SANDBOX/repo"
git -C "$SANDBOX/repo" symbolic-ref HEAD refs/heads/main
cd "$SANDBOX/repo"
git remote add origin "$SANDBOX/origin.git"
# Les worktrees vivent sous .claude/ : les ignorer, sinon leur creation salit le checkout
# principal et le signal W2 se declencherait sur du bruit de test.
printf '.claude/\n' > .gitignore
echo one > f.txt
mkdir -p deep
echo live > deep/live.txt
git add .gitignore f.txt deep/live.txt
git commit --quiet -m "c1"
git push --quiet -u origin main 2>/dev/null
mkdir -p .claude/worktrees

# --- Cas 1 : depot au repos --------------------------------------------------------------
echo "[1] depot au repos : aucun worktree lie, checkout propre et synchrone"
run --no-fetch --no-gh
[[ "$CODE" -eq 0 ]] && check ok "sortie 0" || check ko "sortie $CODE au lieu de 0"
grep -q 'VERDICT: CLEAR' <<<"$OUT" && check ok "verdict CLEAR" || check ko "verdict non CLEAR"
grep -q '(aucun worktree lie)' <<<"$OUT" \
    && check ok "aucun worktree lie signale" || check ko "worktrees lies mal comptes"

# --- Cas 2 : W1, worktree lie actif ------------------------------------------------------
# Regression heritee de SF-REPO-02 : l'activite doit etre vue sur un fichier IMBRIQUE, la
# racine du worktree restant ancienne. `touch` sur un fichier suivi ne modifie que la mtime,
# donc le worktree reste propre et merge : seul le signal d'activite peut le declarer en vol.
echo "[2] W1 : worktree lie avec une activite recente sur un fichier imbrique"
git worktree add --quiet -b wt-a .claude/worktrees/wf_a HEAD
backdate .claude/worktrees/wf_a
touch .claude/worktrees/wf_a/deep/live.txt
run --no-fetch --no-gh
[[ "$CODE" -eq 1 ]] && check ok "sortie 1" || check ko "sortie $CODE au lieu de 1"
grep -q 'VERDICT: BUSY' <<<"$OUT" && check ok "verdict BUSY" || check ko "verdict non BUSY"
grep -q 'EN VOL  .claude/worktrees/wf_a.*W1 activite' <<<"$OUT" \
    && check ok "wf_a declare en vol (W1)" || check ko "wf_a non detecte comme actif"

# --- Cas 3 : controle negatif de W1 ------------------------------------------------------
echo "[3] --age-minutes 0 : le signal d'activite est desactive"
run --no-fetch --no-gh --age-minutes 0
[[ "$CODE" -eq 0 ]] && check ok "sortie 0" || check ko "sortie $CODE au lieu de 0"
grep -q 'EN VOL' <<<"$OUT" \
    && check ko "un worktree reste declare en vol malgre --age-minutes 0" \
    || check ok "plus aucune session en vol"

# --- Cas 4 : auto-exclusion --------------------------------------------------------------
# Le script cree lui-meme de l'activite dans le worktree d'ou il tourne : sans exclusion il
# se declarerait en vol a tous les coups.
echo "[4] auto-exclusion du worktree courant"
touch .claude/worktrees/wf_a/deep/live.txt
set +e
out_self="$(cd .claude/worktrees/wf_a && bash "$SCRIPT" --no-fetch --no-gh 2>&1)"
code_self=$?
set -e
[[ "$code_self" -eq 0 ]] && check ok "CLEAR depuis le worktree lui-meme" \
    || check ko "sortie $code_self depuis le worktree lui-meme"
grep -q 'SOI     .claude/worktrees/wf_a' <<<"$out_self" \
    && check ok "worktree courant marque SOI" || check ko "worktree courant non marque SOI"
set +e
out_self2="$(cd .claude/worktrees/wf_a && bash "$SCRIPT" --no-fetch --no-gh --include-self 2>&1)"
code_self2=$?
set -e
[[ "$code_self2" -eq 1 ]] && check ok "--include-self le compte a nouveau (BUSY)" \
    || check ko "--include-self sans effet (sortie $code_self2)"

# --- Cas 13 (ici, meme montage) : pas de refus depuis un worktree lie --------------------
echo "[13] execution autorisee depuis un worktree lie (pas de sortie 3)"
[[ "$code_self" -ne 3 && "$code_self2" -ne 3 ]] \
    && check ok "aucune sortie 3" || check ko "le script refuse de tourner depuis un worktree lie"

# --- Cas 5 : W2, checkout principal sale -------------------------------------------------
echo "[5] W2 : fichier non commite dans le checkout principal"
echo scratch > scratch.txt
run --no-fetch --no-gh --age-minutes 0
[[ "$CODE" -eq 1 ]] && check ok "sortie 1" || check ko "sortie $CODE au lieu de 1"
grep -q 'EN VOL.*W2 sale' <<<"$OUT" \
    && check ok "checkout principal declare en vol (W2)" || check ko "W2 non detecte"
rm -f scratch.txt
run --no-fetch --no-gh --age-minutes 0
grep -q 'W2 sale' <<<"$OUT" \
    && check ko "W2 persiste apres nettoyage" || check ok "controle negatif : W2 disparait"

# --- Cas 6 : W3 (en avance) puis I2 (en retard) ------------------------------------------
echo "[6] W3 : commit non pousse ; I2 : retard informatif"
echo two > f2.txt
git add f2.txt
git commit --quiet -m "c2"
run --no-fetch --no-gh --age-minutes 0
[[ "$CODE" -eq 1 ]] && check ok "sortie 1" || check ko "sortie $CODE au lieu de 1"
grep -q 'W3 en avance de 1 commit' <<<"$OUT" \
    && check ok "commit non pousse detecte (W3)" || check ko "W3 non detecte"
git push --quiet origin main 2>/dev/null
git reset --hard --quiet HEAD~1
run --no-fetch --no-gh --age-minutes 0
[[ "$CODE" -eq 0 ]] && check ok "un retard ne bloque pas (sortie 0)" \
    || check ko "un retard a rendu le verdict BUSY (sortie $CODE)"
grep -q 'I2 en retard de 1 commit' <<<"$OUT" \
    && check ok "retard signale en INFO (I2)" || check ko "I2 non signale"

# --- Cas 7 : W5, branche partagee entre deux worktrees -----------------------------------
echo "[7] W5 : une meme branche checked out dans deux worktrees"
git worktree add --quiet --force .claude/worktrees/wf_b wt-a
run --no-fetch --no-gh --age-minutes 0
[[ "$CODE" -eq 1 ]] && check ok "sortie 1" || check ko "sortie $CODE au lieu de 1"
grep -q 'COLLISION wt-a — 2 worktrees' <<<"$OUT" \
    && check ok "collision de branche detectee" || check ko "collision non detectee"
grep -q 'COLLISION wt-a.*wf_a.*wf_b' <<<"$OUT" \
    && check ok "les deux worktrees sont nommes" || check ko "les porteurs ne sont pas nommes"
git worktree remove --force .claude/worktrees/wf_b
run --no-fetch --no-gh --age-minutes 0
grep -q 'COLLISION' <<<"$OUT" \
    && check ko "collision persistante apres retrait" \
    || check ok "controle negatif : plus de collision"

# --- Cas 8 : I1, worktree sale mais INACTIF = residu, pas session ------------------------
# Sans cette distinction, le verdict serait rouge en permanence : les worktrees survivent aux
# vagues eteintes, souvent sales ou non merges.
echo "[8] I1 : worktree sale et inactif => RESIDU, verdict CLEAR"
git worktree add --quiet -b wt-dirty .claude/worktrees/wf_c HEAD
echo scratch > .claude/worktrees/wf_c/scratch.txt
backdate_all
run --no-fetch --no-gh
[[ "$CODE" -eq 0 ]] && check ok "sortie 0 (un residu ne bloque pas)" \
    || check ko "sortie $CODE : un residu a rendu le verdict BUSY"
grep -q 'RESIDU  .claude/worktrees/wf_c.*dirty' <<<"$OUT" \
    && check ok "wf_c classe RESIDU" || check ko "wf_c non classe RESIDU"
grep -q 'prune-stale-worktrees.sh' <<<"$OUT" \
    && check ok "renvoi vers l'outil de purge" || check ko "aucun renvoi vers la purge"

# --- Cas 9 : I1, worktree non merge mais inactif -----------------------------------------
echo "[9] I1 : worktree non merge et inactif => RESIDU, verdict CLEAR"
git worktree add --quiet -b wt-unmerged .claude/worktrees/wf_d HEAD
echo extra > .claude/worktrees/wf_d/extra.txt
git -C .claude/worktrees/wf_d add extra.txt
git -C .claude/worktrees/wf_d commit --quiet -m "travail non merge"
backdate_all
run --no-fetch --no-gh
[[ "$CODE" -eq 0 ]] && check ok "sortie 0" || check ko "sortie $CODE au lieu de 0"
grep -q 'RESIDU  .claude/worktrees/wf_d.*unmerged' <<<"$OUT" \
    && check ok "wf_d classe RESIDU (unmerged)" || check ko "wf_d non classe RESIDU"

# --- Cas 10 : W4, pull request ouverte ---------------------------------------------------
# `gh` est simule par un stub en tete de PATH : le test ne contacte jamais le reseau.
echo "[10] W4 : pull request ouverte (gh simule)"
mkdir -p "$SANDBOX/bin"
cat > "$SANDBOX/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "256	un titre	une-branche	OPEN"
STUB
chmod +x "$SANDBOX/bin/gh"
set +e
OUT="$(PATH="$SANDBOX/bin:$PATH" bash "$SCRIPT" --no-fetch --age-minutes 0 2>&1)"
CODE=$?
set -e
[[ "$CODE" -eq 1 ]] && check ok "sortie 1" || check ko "sortie $CODE au lieu de 1"
grep -q 'PR OUVERTE  256' <<<"$OUT" \
    && check ok "PR ouverte listee" || check ko "PR ouverte non listee"
cat > "$SANDBOX/bin/gh" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$SANDBOX/bin/gh"
set +e
OUT="$(PATH="$SANDBOX/bin:$PATH" bash "$SCRIPT" --no-fetch --age-minutes 0 2>&1)"
CODE=$?
set -e
[[ "$CODE" -eq 0 ]] && check ok "controle negatif : aucune PR => CLEAR" \
    || check ko "sortie $CODE alors qu'aucune PR n'est ouverte"

# --- Cas 11 : gh indisponible ------------------------------------------------------------
# Meme traitement qu'un binaire absent : le signal est marque INDISPONIBLE, le verdict est
# rendu sur les seuls signaux locaux — sauf --require-gh, qui refuse de conclure.
echo "[11] gh indisponible : verdict local, sauf --require-gh"
cat > "$SANDBOX/bin/gh" <<'STUB'
#!/usr/bin/env bash
exit 1
STUB
chmod +x "$SANDBOX/bin/gh"
set +e
OUT="$(PATH="$SANDBOX/bin:$PATH" bash "$SCRIPT" --no-fetch --age-minutes 0 2>&1)"
CODE=$?
set -e
[[ "$CODE" -eq 0 ]] && check ok "verdict rendu malgre gh indisponible" \
    || check ko "sortie $CODE : gh indisponible a bloque le verdict"
grep -q 'INDISPONIBLE' <<<"$OUT" \
    && check ok "signal PR marque INDISPONIBLE" || check ko "indisponibilite non signalee"
set +e
OUT="$(PATH="$SANDBOX/bin:$PATH" bash "$SCRIPT" --no-fetch --age-minutes 0 --require-gh 2>&1)"
CODE=$?
set -e
[[ "$CODE" -eq 1 ]] && check ok "--require-gh rend le verdict BUSY" \
    || check ko "--require-gh sans effet (sortie $CODE)"

# --- Cas 12 : non-destructivite ----------------------------------------------------------
echo "[12] non-destructivite : l'etat Git est strictement inchange"
snapshot() { { git worktree list; echo '--'; git branch --list; echo '--'; git status --porcelain; } | sort; }
before="$(snapshot)"
run --no-fetch --no-gh --age-minutes 0
after="$(snapshot)"
[[ "$before" == "$after" ]] \
    && check ok "worktrees, branches et statut identiques avant/apres" \
    || check ko "le script a modifie l'etat Git"
[[ -f .claude/worktrees/wf_c/scratch.txt ]] \
    && check ok "aucun fichier non commite touche" || check ko "un fichier non commite a disparu"

# --- Cas 14 : codes de sortie d'erreur ---------------------------------------------------
echo "[14] codes de sortie d'erreur"
run --option-qui-nexiste-pas
[[ "$CODE" -eq 2 ]] && check ok "option inconnue => 2" || check ko "option inconnue => $CODE au lieu de 2"
run --no-fetch --no-gh --age-minutes -1
[[ "$CODE" -eq 2 ]] && check ok "--age-minutes invalide => 2" || check ko "--age-minutes invalide => $CODE"
run --no-fetch --no-gh --base origin/branche-inexistante
[[ "$CODE" -eq 4 ]] && check ok "base introuvable => 4" || check ko "base introuvable => $CODE au lieu de 4"
git remote set-url origin "$SANDBOX/inexistant.git"
run --no-gh
[[ "$CODE" -eq 4 ]] && check ok "fetch impossible => 4" || check ko "fetch impossible => $CODE au lieu de 4"
git remote set-url origin "$SANDBOX/origin.git"

echo
if [[ "$failures" -eq 0 ]]; then
    echo "TOUS LES CAS PASSES"
    exit 0
fi
echo "$failures cas en echec"
exit 1
