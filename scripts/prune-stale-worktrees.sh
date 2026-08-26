#!/usr/bin/env bash
#
# prune-stale-worktrees.sh — REPO / SF-REPO-02
#
# Purge les worktrees Git residuels des vagues de livraison precedentes, situes sous
# .claude/worktrees/, puis supprime les branches locales devenues orphelines et lance
# un `git worktree prune` final.
#
# A executer AVANT de lancer une vague de livraison autonome, depuis le checkout principal.
#
# Securite : l'operation est destructive (git worktree remove efface le repertoire de
# travail). Quatre garde-fous cumulatifs protegent chaque candidat ; un seul en echec
# suffit a l'ecarter. Le mode par defaut est un DRY-RUN : rien n'est supprime sans --apply.
#
# Usage :
#   scripts/prune-stale-worktrees.sh                 # dry-run (defaut) : affiche le plan
#   scripts/prune-stale-worktrees.sh --apply         # execute reellement
#   scripts/prune-stale-worktrees.sh --age-minutes 0 # desactive le garde-fou d'inactivite
#   scripts/prune-stale-worktrees.sh --no-fetch      # ne pas contacter origin
#
# Mini-spec : docs/features/REPO/SF-REPO-02-purge-worktrees-residuels.md

set -euo pipefail

APPLY=0
DO_FETCH=1
AGE_MINUTES=60
BASE_REF="origin/main"
# Seuls les worktrees sous ce prefixe (relatif a la racine du depot) sont candidats.
WORKTREE_PREFIX=".claude/worktrees/"

# Affiche l'en-tete de commentaire de ce fichier (tout ce qui suit le shebang jusqu'a
# la premiere ligne non commentee).
usage() {
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply)        APPLY=1; shift ;;
        --no-fetch)     DO_FETCH=0; shift ;;
        --age-minutes)  AGE_MINUTES="${2:?--age-minutes requiert une valeur}"; shift 2 ;;
        --base)         BASE_REF="${2:?--base requiert une valeur}"; shift 2 ;;
        -h|--help)      usage; exit 0 ;;
        *)              echo "ERREUR: option inconnue '$1'" >&2; usage >&2; exit 2 ;;
    esac
done

if ! [[ "$AGE_MINUTES" =~ ^[0-9]+$ ]]; then
    echo "ERREUR: --age-minutes attend un entier >= 0 (recu: '$AGE_MINUTES')" >&2
    exit 2
fi

# --- Garde-fou 0 : le script doit tourner dans le checkout principal ------------------
# Dans un worktree lie, --git-dir differe de --git-common-dir. Retirer un autre worktree
# depuis un worktree lie est fragile et interdit par les agents isoles.
git_dir="$(git rev-parse --absolute-git-dir)"
git_common_dir="$(cd "$(git rev-parse --git-common-dir)" && pwd)"
if [[ "$git_dir" != "$git_common_dir" ]]; then
    echo "ERREUR: ce script doit etre lance depuis le checkout principal, pas depuis un worktree lie." >&2
    echo "        checkout principal = $(dirname "$git_common_dir")" >&2
    exit 3
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

# --- Etape 1 : fetch, pour raisonner sur origin/main et jamais sur un working tree -----
if [[ "$DO_FETCH" -eq 1 ]]; then
    if ! git fetch --quiet origin; then
        echo "ERREUR: 'git fetch origin' a echoue — la comparaison a $BASE_REF ne serait pas fiable. STOP." >&2
        exit 4
    fi
fi

if ! base_sha="$(git rev-parse --verify --quiet "$BASE_REF^{commit}")"; then
    echo "ERREUR: reference de base introuvable: $BASE_REF" >&2
    exit 4
fi

mode_label="DRY-RUN (aucune modification — utiliser --apply pour executer)"
[[ "$APPLY" -eq 1 ]] && mode_label="APPLY (destructif)"

echo "== prune-stale-worktrees =="
echo "  depot            : $repo_root"
echo "  base             : $BASE_REF ($base_sha)"
echo "  prefixe candidat : $WORKTREE_PREFIX"
echo "  inactivite min.  : ${AGE_MINUTES} min"
echo "  mode             : $mode_label"
echo

# --- Detection d'activite recente -------------------------------------------------------
# Un agent vivant peut avoir un arbre propre entre deux commits : ce garde-fou repere
# l'activite recente independamment de l'etat Git.
#
# La mtime du repertoire racine du worktree ne suffit pas : elle ne change que lorsqu'une
# entree est creee ou supprimee *a la racine*. Un agent editant backend/src/... pendant des
# heures la laisserait intacte. On teste donc trois signaux, du moins cher au plus cher :
#   1. la mtime de la racine du worktree ;
#   2. les metadonnees Git du worktree (index, HEAD, logs/HEAD) — rafraichies par a peu pres
#      toute commande Git executee dedans, y compris `git status` ;
#   3. un parcours recursif du contenu, elague des repertoires de build (node_modules,
#      target, dist, .angular, .git) et arrete des le premier fichier recent (-quit).
is_recently_active() {
    local wt="$1" minutes="$2"
    local threshold="-${minutes} minutes"

    # 1. Racine du worktree.
    if [[ -n "$(find "$wt" -maxdepth 0 -newermt "$threshold" -print 2>/dev/null)" ]]; then
        return 0
    fi

    # 2. Metadonnees Git propres a ce worktree.
    local wt_git_dir="$git_common_dir/worktrees/$(basename "$wt")"
    local meta
    for meta in index HEAD logs/HEAD ORIG_HEAD; do
        if [[ -e "$wt_git_dir/$meta" ]] &&
           [[ -n "$(find "$wt_git_dir/$meta" -maxdepth 0 -newermt "$threshold" -print 2>/dev/null)" ]]; then
            return 0
        fi
    done

    # 3. Contenu du worktree, hors repertoires de build, arret au premier fichier recent.
    local hit
    hit="$(find "$wt" \
             \( -name node_modules -o -name target -o -name dist \
                -o -name .angular -o -name .git \) -prune -o \
             -type f -newermt "$threshold" -print -quit 2>/dev/null)"
    [[ -n "$hit" ]]
}

# --- Etape 2 : enumerer les worktrees --------------------------------------------------
# `git worktree list --porcelain` produit des blocs separes par une ligne vide :
#   worktree <chemin> / HEAD <sha> / branch <ref> | detached / locked [raison]
removed=0
skipped=0
declare -a branches_to_check=()

current_wt=""
current_head=""
current_branch=""
current_locked=0

flush_worktree() {
    [[ -z "$current_wt" ]] && return 0

    local wt="$current_wt" head="$current_head" branch="$current_branch" locked="$current_locked"
    current_wt=""; current_head=""; current_branch=""; current_locked=0

    # Le checkout principal n'est jamais candidat.
    [[ "$wt" == "$repo_root" ]] && return 0

    # Ne cibler que les worktrees sous .claude/worktrees/.
    case "$wt" in
        "$repo_root/$WORKTREE_PREFIX"*) ;;
        *) return 0 ;;
    esac

    local label="${wt#"$repo_root"/}"
    local short_head="${head:0:7}"
    local branch_label="${branch:-(detached)}"

    skip() {
        echo "  SKIP    $label [$short_head $branch_label] — $1"
        skipped=$((skipped + 1))
    }

    # Garde-fou 1 : worktree verrouille -> ne jamais forcer.
    # `git worktree list --porcelain` n'emet la ligne `locked` que sur les Git recents :
    # verifier aussi le fichier de verrou dans le git-dir commun (compatibilite Git < 2.36).
    if [[ -f "$git_common_dir/worktrees/$(basename "$wt")/locked" ]]; then
        locked=1
    fi
    if [[ "$locked" -eq 1 ]]; then
        skip "locked (verrouille explicitement)"
        return 0
    fi

    # Idempotence : repertoire deja disparu -> laisser `git worktree prune` faire le menage.
    if [[ ! -d "$wt" ]]; then
        echo "  PRUNE   $label — repertoire absent, sera nettoye par 'git worktree prune'"
        return 0
    fi

    # Garde-fou 2 : activite recente -> probablement une session vivante.
    if [[ "$AGE_MINUTES" -gt 0 ]] && is_recently_active "$wt" "$AGE_MINUTES"; then
        skip "recently-active (activite il y a moins de ${AGE_MINUTES} min — session probablement vivante)"
        return 0
    fi

    # Garde-fou 3 : worktree sale -> ne jamais supprimer du travail non commite.
    local status_out
    if ! status_out="$(git -C "$wt" status --porcelain 2>/dev/null)"; then
        skip "unreadable (statut Git illisible)"
        return 0
    fi
    if [[ -n "$status_out" ]]; then
        skip "dirty ($(printf '%s\n' "$status_out" | wc -l) fichier(s) non commite(s))"
        return 0
    fi

    # Garde-fou 4 : HEAD deja present dans la base -> aucun travail non merge perdu.
    if ! git merge-base --is-ancestor "$head" "$base_sha" 2>/dev/null; then
        skip "unmerged (HEAD $short_head n'est pas un ancetre de $BASE_REF)"
        return 0
    fi

    echo "  REMOVE  $label [$short_head $branch_label]"
    if [[ "$APPLY" -eq 1 ]]; then
        git worktree remove "$wt"
    fi
    removed=$((removed + 1))
    [[ -n "$branch" ]] && branches_to_check+=("$branch")
    return 0
}

echo "-- Worktrees --"
while IFS= read -r line; do
    case "$line" in
        "worktree "*) flush_worktree; current_wt="${line#worktree }" ;;
        "HEAD "*)     current_head="${line#HEAD }" ;;
        "branch "*)   current_branch="$(git rev-parse --abbrev-ref "${line#branch }" 2>/dev/null || true)" ;;
        "locked"*)    current_locked=1 ;;
        "detached")   current_branch="" ;;
    esac
done < <(git worktree list --porcelain)
flush_worktree

[[ "$removed" -eq 0 && "$skipped" -eq 0 ]] && echo "  (aucun worktree candidat)"

# --- Etape 3 : purger les branches locales devenues orphelines -------------------------
echo
echo "-- Branches liberees --"
deleted_branches=0
if [[ "${#branches_to_check[@]}" -eq 0 ]]; then
    echo "  (aucune)"
fi
for branch in "${branches_to_check[@]:-}"; do
    [[ -z "$branch" ]] && continue

    # Protection en dur des branches structurantes.
    case "$branch" in
        main|master|HEAD) echo "  KEEP    $branch — branche protegee"; continue ;;
    esac

    # Idempotence : branche deja supprimee.
    if ! branch_sha="$(git rev-parse --verify --quiet "refs/heads/$branch")"; then
        continue
    fi

    # Encore checked out dans un worktree ? Ne pas toucher (Git refuserait de toute facon).
    # En dry-run le worktree d'origine n'a pas ete retire : la verification serait toujours
    # positive et le rapport annoncerait a tort un KEEP. On ne la fait donc qu'en mode --apply.
    if [[ "$APPLY" -eq 1 ]] && git worktree list --porcelain | grep -qx "branch refs/heads/$branch"; then
        echo "  KEEP    $branch — encore checked out dans un worktree"
        continue
    fi

    # Un seul commit '+' face a la base = travail non merge -> interdiction de supprimer.
    ahead="$(git cherry "$BASE_REF" "$branch" | grep -c '^+' || true)"
    if [[ "$ahead" -ne 0 ]]; then
        echo "  KEEP    $branch [${branch_sha:0:7}] — $ahead commit(s) non merge(s)"
        continue
    fi

    echo "  DELETE  $branch [${branch_sha:0:7}]  (restauration: git branch $branch $branch_sha)"
    if [[ "$APPLY" -eq 1 ]]; then
        git branch -D "$branch" >/dev/null
    fi
    deleted_branches=$((deleted_branches + 1))
done

# --- Etape 4 : prune final des enregistrements orphelins --------------------------------
echo
echo "-- Prune des enregistrements orphelins --"
if [[ "$APPLY" -eq 1 ]]; then
    git worktree prune --verbose || true
else
    git worktree prune --dry-run --verbose || true
fi

# --- Rapport ----------------------------------------------------------------------------
echo
echo "== Resume =="
echo "  worktrees retires  : $removed"
echo "  worktrees conserves: $skipped"
echo "  branches supprimees: $deleted_branches"
echo "  $BASE_REF          : $(git rev-parse "$BASE_REF")  (inchange)"
if [[ "$APPLY" -eq 0 ]]; then
    echo
    echo "  DRY-RUN : rien n'a ete modifie. Relancer avec --apply pour executer."
fi
