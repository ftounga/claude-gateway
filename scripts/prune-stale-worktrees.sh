#!/usr/bin/env bash
#
# prune-stale-worktrees.sh — REPO / SF-REPO-02
#
# Purge les worktrees Git residuels des vagues de livraison precedentes, situes sous
# .claude/worktrees/, puis supprime les branches locales devenues orphelines et lance
# un `git worktree prune` final.
#
# Une branche locale est ORPHELINE quand elle n'est plus checked out dans aucun worktree
# conserve et que `git cherry origin/main <branche>` ne produit aucun commit '+' : tout son
# contenu est deja dans la base (squash-merge compris), sa suppression ne perd rien.
#
# A executer AVANT de lancer une vague de livraison autonome, depuis le checkout principal.
#
# Securite : l'operation est destructive (git worktree remove efface le repertoire de
# travail). Quatre garde-fous cumulatifs protegent chaque candidat ; un seul en echec
# suffit a l'ecarter. Le mode par defaut est un DRY-RUN : rien n'est supprime sans --apply.
#
# Un CINQUIEME garde-fou, global celui-la (SF-SP-02), s'ajoute en mode --apply : la purge
# consulte scripts/check-parallel-sessions.sh et refuse de detruire quoi que ce soit tant
# qu'une session parallele travaille dans le depot. Les quatre premiers garde-fous sont
# locaux a chaque candidat : aucun ne repond a la question « une vague est-elle en cours ? ».
#
# Usage :
#   scripts/prune-stale-worktrees.sh                 # dry-run (defaut) : affiche le plan
#   scripts/prune-stale-worktrees.sh --apply         # execute reellement
#   scripts/prune-stale-worktrees.sh --age-minutes 0 # desactive le garde-fou d'inactivite
#   scripts/prune-stale-worktrees.sh --no-fetch      # ne pas contacter origin
#   scripts/prune-stale-worktrees.sh --no-sweep-branches # n'examiner que les branches
#                                                    # liberees par un retrait de worktree
#   scripts/prune-stale-worktrees.sh --apply --force-busy # passer outre le garde-fou 5
#
# Sorties : 0 succes | 2 usage | 3 lance depuis un worktree lie | 4 base non evaluable
#           5 session parallele en vol (garde-fou 5)
#
# Mini-spec : docs/features/REPO/SF-REPO-02-purge-worktrees-residuels.md
#             docs/features/worktrees-orphelins/SF-WO-01-balayage-branches-orphelines.md
#             docs/features/SESSIONS-PARALLELES/SF-SP-02-purge-refusee-si-session-en-vol.md

set -euo pipefail

APPLY=0
DO_FETCH=1
AGE_MINUTES=60
BASE_REF="origin/main"
# Seuls les worktrees sous ce prefixe (relatif a la racine du depot) sont candidats.
WORKTREE_PREFIX=".claude/worktrees/"
# Balayage global des branches locales (SF-WO-01). A 0, on retombe sur le comportement
# de SF-REPO-02 : seules les branches liberees par un retrait de worktree sont examinees.
SWEEP_BRANCHES=1
# Passe outre le garde-fou 5 (session parallele en vol). Volontairement absent du dry-run :
# le dry-run ne detruit rien, il n'a rien a forcer.
FORCE_BUSY=0
# Branches jamais evaluees, jamais supprimees, quelle que soit leur position.
PROTECTED_BRANCHES=(main master HEAD)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/git-worktrees.sh
source "$SCRIPT_DIR/lib/git-worktrees.sh"

# Affiche l'en-tete de commentaire de ce fichier (tout ce qui suit le shebang jusqu'a
# la premiere ligne non commentee).
usage() {
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply)              APPLY=1; shift ;;
        --no-fetch)           DO_FETCH=0; shift ;;
        --no-sweep-branches)  SWEEP_BRANCHES=0; shift ;;
        --force-busy)         FORCE_BUSY=1; shift ;;
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
if [[ "$SWEEP_BRANCHES" -eq 1 ]]; then
    echo "  branches         : balayage global (toutes les branches locales)"
else
    echo "  branches         : liberees par un retrait uniquement (--no-sweep-branches)"
fi
echo "  mode             : $mode_label"
echo

# --- Garde-fou 5 : ne rien detruire pendant qu'une session parallele travaille ----------
# Les quatre garde-fous suivants sont LOCAUX a chaque candidat. Aucun ne repond a la question
# globale « une vague est-elle en cours ? » — et c'est celle-la qui compte avant d'effacer un
# repertoire de travail : un agent vivant entre deux SF a un arbre propre, un HEAD merge et
# peut n'avoir rien ecrit depuis 61 minutes. Les quatre garde-fous passent alors au vert.
#
# Le controle n'est evalue qu'en --apply : le dry-run ne detruit rien, et doit rester
# consultable meme pendant une vague — c'est le seul moyen de voir ce que la purge ferait.
# Il est appele avec --no-gh (une PR ouverte des jours ne doit pas geler le housekeeping ;
# une branche a PR ouverte porte de toute facon des commits non merges, donc `git cherry` la
# protege deja) et avec LE MEME seuil d'inactivite, sans quoi un worktree pourrait etre
# ecarte par un outil et detruit par l'autre.
if [[ "$APPLY" -eq 1 ]]; then
    preflight="$SCRIPT_DIR/check-parallel-sessions.sh"
    if [[ ! -f "$preflight" ]]; then
        # Defense en profondeur ajoutee par-dessus 4 garde-fous et un dry-run par defaut :
        # rendre la purge inutilisable parce qu'un fichier manque couterait plus qu'il ne protege.
        echo "AVERTISSEMENT: $preflight introuvable — garde-fou 'session en vol' non evalue." >&2
    else
        set +e
        preflight_out="$(bash "$preflight" --quiet --no-fetch --no-gh --age-minutes "$AGE_MINUTES" 2>&1)"
        preflight_code=$?
        set -e
        case "$preflight_code" in
            0)
                echo "-- Controle de vol : CLEAR --"
                echo
                ;;
            1)
                if [[ "$FORCE_BUSY" -eq 1 ]]; then
                    echo "-- Controle de vol : BUSY, FORCE par --force-busy --"
                    echo "  $preflight_out"
                    echo
                else
                    echo "ERREUR: une session parallele travaille dans ce depot. RIEN n'a ete detruit." >&2
                    echo "  $preflight_out" >&2
                    echo "  Detail  : bash $preflight" >&2
                    echo "  Forcer  : --force-busy" >&2
                    exit 5
                fi
                ;;
            *)
                echo "AVERTISSEMENT: controle de vol indisponible (sortie $preflight_code) — garde-fou 5 non evalue." >&2
                ;;
        esac
    fi
fi

# --- Detection d'activite recente -------------------------------------------------------
# Fournie par scripts/lib/git-worktrees.sh (gw_is_recently_active), partagee avec
# check-parallel-sessions.sh depuis SF-SP-02. Elle vivait ici en copie ; cette copie a deja
# du etre corrigee une fois (SF-REPO-02, 2026-08-26 : la seule mtime de la racine ne bouge
# pas quand un agent edite un fichier imbrique). En garder deux exemplaires garantissait que
# le prochain correctif n'en corrige qu'un.

# --- Etape 2 : enumerer les worktrees --------------------------------------------------
# `git worktree list --porcelain` produit des blocs separes par une ligne vide :
#   worktree <chemin> / HEAD <sha> / branch <ref> | detached / locked [raison]
removed=0
skipped=0
# Branches liberees par un worktree planifie au retrait -> candidates a la suppression.
declare -a freed_branches=()
# Branches encore checked out dans un worktree CONSERVE (checkout principal compris)
# -> intouchables : Git refuserait la suppression, et l'agent qui y travaille la perdrait.
declare -a held_branches=()

current_wt=""
current_head=""
current_branch=""
current_locked=0

# Marque une branche comme detenue par un worktree conserve : le balayage de l'etape 3
# ne devra jamais y toucher.
hold_branch() {
    [[ -n "$1" ]] && held_branches+=("$1")
    return 0
}

flush_worktree() {
    [[ -z "$current_wt" ]] && return 0

    local wt="$current_wt" head="$current_head" branch="$current_branch" locked="$current_locked"
    current_wt=""; current_head=""; current_branch=""; current_locked=0

    # Le checkout principal n'est jamais candidat — et sa branche courante est detenue.
    if [[ "$wt" == "$repo_root" ]]; then
        hold_branch "$branch"
        return 0
    fi

    # Ne cibler que les worktrees sous .claude/worktrees/. Les autres sont hors perimetre,
    # donc conserves : leurs branches le sont aussi.
    case "$wt" in
        "$repo_root/$WORKTREE_PREFIX"*) ;;
        *) hold_branch "$branch"; return 0 ;;
    esac

    local label="${wt#"$repo_root"/}"
    local short_head="${head:0:7}"
    local branch_label="${branch:-(detached)}"

    # Ecarter le worktree conserve sa branche : elle reste checked out.
    skip() {
        echo "  SKIP    $label [$short_head $branch_label] — $1"
        skipped=$((skipped + 1))
        hold_branch "$branch"
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
        [[ -n "$branch" ]] && freed_branches+=("$branch")
        return 0
    fi

    # Garde-fou 2 : activite recente -> probablement une session vivante.
    if [[ "$AGE_MINUTES" -gt 0 ]] && gw_is_recently_active "$wt" "$AGE_MINUTES" "$repo_root" "$git_common_dir"; then
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
    [[ -n "$branch" ]] && freed_branches+=("$branch")
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

# --- Etape 3 : prune des enregistrements orphelins --------------------------------------
# AVANT la purge des branches, pas apres : tant qu'un enregistrement de worktree survit,
# Git refuse de supprimer la branche qu'il declare checked out — meme si le repertoire a
# disparu depuis longtemps. Purger d'abord, c'est liberer les branches que l'etape 4 doit
# pouvoir examiner ; purger apres laisserait `git branch -D` echouer sur ces branches-la.
echo
echo "-- Prune des enregistrements orphelins --"
if [[ "$APPLY" -eq 1 ]]; then
    git worktree prune --verbose || true
else
    git worktree prune --dry-run --verbose || true
fi

# --- Etape 4 : purger les branches locales orphelines -----------------------------------
# Perimetre du balayage (SF-WO-01) :
#   - par defaut : TOUTES les branches locales. Les branches deja squash-mergees dont plus
#     aucun worktree ne depend sont invisibles pour un balayage derive des seuls retraits ;
#     ce sont pourtant elles qui saturent l'espace de nommage `worktree-wf_*` et font
#     repartir un agent parallele d'une branche perimee.
#   - avec --no-sweep-branches : seulement les branches liberees par un retrait (SF-REPO-02),
#     y compris celles d'un enregistrement perime que l'etape 3 vient de purger.
#
# Trois filtres, du moins cher au plus cher : nom protege, detenue par un worktree conserve,
# puis `git cherry` (le seul qui lance un calcul de patch-id).
#
# La detention est calculee a partir du plan ci-dessus, pas d'un `git worktree list` relu :
# en dry-run le worktree candidat n'a pas encore ete retire, et le relire annoncerait a tort
# un KEEP pour une branche que le mode --apply supprimerait.
echo
echo "-- Branches orphelines --"
deleted_branches=0

is_held() {
    local candidate="$1" held
    for held in "${held_branches[@]:-}"; do
        [[ "$candidate" == "$held" ]] && return 0
    done
    return 1
}

declare -a branches_to_check=()
if [[ "$SWEEP_BRANCHES" -eq 1 ]]; then
    while IFS= read -r branch; do
        [[ -n "$branch" ]] && branches_to_check+=("$branch")
    done < <(git for-each-ref --format='%(refname:short)' refs/heads/)
else
    branches_to_check=("${freed_branches[@]:-}")
fi

examined=0
for branch in "${branches_to_check[@]:-}"; do
    [[ -z "$branch" ]] && continue

    # Protection en dur des branches structurantes.
    is_protected=0
    for protected in "${PROTECTED_BRANCHES[@]}"; do
        [[ "$branch" == "$protected" ]] && is_protected=1
    done
    if [[ "$is_protected" -eq 1 ]]; then
        echo "  KEEP    $branch — branche protegee"
        continue
    fi

    # Idempotence : branche deja supprimee (ou nom invalide).
    if ! branch_sha="$(git rev-parse --verify --quiet "refs/heads/$branch")"; then
        continue
    fi

    # Encore checked out dans un worktree conserve -> intouchable.
    if is_held "$branch"; then
        # En balayage global, le silence vaut mieux qu'une ligne par branche detenue :
        # on ne trace que les branches reellement examinees.
        [[ "$SWEEP_BRANCHES" -eq 0 ]] && echo "  KEEP    $branch — encore checked out dans un worktree"
        continue
    fi

    examined=$((examined + 1))

    # Un seul commit '+' face a la base = travail non merge -> interdiction de supprimer.
    ahead="$(git cherry "$BASE_REF" "$branch" | grep -c '^+' || true)"
    if [[ "$ahead" -ne 0 ]]; then
        echo "  KEEP    $branch [${branch_sha:0:7}] — $ahead commit(s) non merge(s)"
        continue
    fi

    # Un refus de Git (branche encore reclamee par un worktree) ne doit pas tuer le run :
    # il est signale, la branche est conservee, le balayage continue.
    if [[ "$APPLY" -eq 1 ]] && ! git branch -D "$branch" >/dev/null 2>&1; then
        echo "  ECHEC   $branch — suppression refusee par Git, branche conservee"
        continue
    fi
    echo "  DELETE  $branch [${branch_sha:0:7}]  (restauration: git branch $branch $branch_sha)"
    deleted_branches=$((deleted_branches + 1))
done

[[ "$examined" -eq 0 ]] && echo "  (aucune branche candidate)"

# --- Rapport ----------------------------------------------------------------------------
echo
echo "== Resume =="
echo "  worktrees retires  : $removed"
echo "  worktrees conserves: $skipped"
echo "  branches examinees : $examined"
echo "  branches supprimees: $deleted_branches"
echo "  $BASE_REF          : $(git rev-parse "$BASE_REF")  (inchange)"
if [[ "$APPLY" -eq 0 ]]; then
    echo
    echo "  DRY-RUN : rien n'a ete modifie. Relancer avec --apply pour executer."
fi
