#!/usr/bin/env bash
#
# check-parallel-sessions.sh — SESSIONS-PARALLELES / SF-SP-01
#
# Repond a une seule question, avant de demarrer une vague de livraison ou de lancer
# `prune-stale-worktrees.sh --apply` : une autre session Claude travaille-t-elle en ce
# moment dans ce depot ?
#
# Ce controle etait jusqu'ici refait a la main, en prose, avant chaque vague. Il decide
# pourtant de deux choses couteuses : ecrire dans le checkout partage, et effacer des
# repertoires de travail. Il est donc outille, verifiable et rejouable a l'identique.
#
# STRICTEMENT EN LECTURE SEULE : ce script ne retire, ne supprime, ne commite et ne pousse
# rien. Il peut donc tourner depuis n'importe ou dans le depot, checkout principal comme
# worktree lie — contrairement a prune-stale-worktrees.sh, dont le refus (sortie 3) protege
# d'une operation destructive.
#
# « Lecture seule » va jusqu'a l'index : les `git status` d'inspection passent par
# --no-optional-locks, sans quoi Git rafraichit et REECRIT l'index de chaque worktree visite.
# Cette ecriture invisible suffirait a faire passer tous les worktrees pour « actifs » aux
# yeux du garde-fou d'age de prune-stale-worktrees.sh, qui tourne juste apres : le controle
# neutraliserait silencieusement la purge qu'il est cense proteger (defaut trouve par le
# cas 13 du test de la purge, SF-SP-02).
#
# Signaux qui rendent le verdict BUSY (« session en vol ») :
#   W1  worktree lie avec une activite de moins de N minutes
#   W2  checkout principal sale (fichiers non commites)
#   W3  checkout principal en avance sur la base (commits non pousses)
#   W4  au moins une pull request ouverte
#   W5  une meme branche checked out dans deux worktrees ou plus
#
# Signaux informatifs, qui n'inversent jamais le verdict :
#   I1  worktree dirty / unmerged / locked SANS activite recente -> RESIDU (voir le prune)
#   I2  checkout principal en retard sur la base -> il manque une mise a jour locale
#   I3  worktree dont le repertoire a disparu -> enregistrement perime
#
# Usage :
#   scripts/check-parallel-sessions.sh                  # verdict lisible
#   scripts/check-parallel-sessions.sh --age-minutes 15 # seuil d'inactivite (0 desactive W1)
#   scripts/check-parallel-sessions.sh --include-self   # ne pas s'exclure soi-meme
#   scripts/check-parallel-sessions.sh --no-fetch       # ne pas contacter origin
#   scripts/check-parallel-sessions.sh --no-gh          # ignorer le signal des PR ouvertes
#   scripts/check-parallel-sessions.sh --require-gh     # gh indisponible => BUSY
#   scripts/check-parallel-sessions.sh --quiet          # n'imprimer que le verdict
#
# Sorties :
#   0  CLEAR — aucune session en vol, aucune collision : la vague peut demarrer
#   1  BUSY  — au moins un signal en vol ou une collision
#   2  usage (option inconnue, valeur invalide)
#   4  etat non evaluable (hors depot Git, fetch en echec, base introuvable)
#
# Mini-spec : docs/features/SESSIONS-PARALLELES/SF-SP-01-preflight-sessions-en-vol.md

set -euo pipefail

AGE_MINUTES=60
BASE_REF="origin/main"
DO_FETCH=1
USE_GH=1
REQUIRE_GH=0
INCLUDE_SELF=0
QUIET=0
WORKTREE_PREFIX=".claude/worktrees/"

# Affiche l'en-tete de commentaire de ce fichier (tout ce qui suit le shebang jusqu'a
# la premiere ligne non commentee).
usage() {
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-fetch)     DO_FETCH=0; shift ;;
        --no-gh)        USE_GH=0; shift ;;
        --require-gh)   REQUIRE_GH=1; shift ;;
        --include-self) INCLUDE_SELF=1; shift ;;
        --quiet)        QUIET=1; shift ;;
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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/git-worktrees.sh
source "$SCRIPT_DIR/lib/git-worktrees.sh"

# --- Contexte Git -----------------------------------------------------------------------
if ! git rev-parse --git-dir >/dev/null 2>&1; then
    echo "ERREUR: hors d'un depot Git." >&2
    exit 4
fi
git_common_dir="$(cd "$(git rev-parse --git-common-dir)" && pwd)"
self_root="$(git rev-parse --show-toplevel)"

if [[ "$DO_FETCH" -eq 1 ]]; then
    if ! git fetch --quiet origin 2>/dev/null; then
        echo "ERREUR: 'git fetch origin' a echoue — l'etat de $BASE_REF ne serait pas fiable. STOP." >&2
        exit 4
    fi
fi

if ! base_sha="$(git rev-parse --verify --quiet "$BASE_REF^{commit}")"; then
    echo "ERREUR: reference de base introuvable: $BASE_REF" >&2
    exit 4
fi

# --- Enumeration des worktrees ----------------------------------------------------------
# `git worktree list --porcelain` liste TOUJOURS le checkout principal en premier ; c'est
# ainsi qu'on l'identifie, sans deduire un chemin depuis le git-dir commun.
declare -a wt_paths=() wt_heads=() wt_branches=() wt_locked=()
cur_path="" cur_head="" cur_branch="" cur_locked=0

flush_entry() {
    [[ -z "$cur_path" ]] && return 0
    wt_paths+=("$cur_path")
    wt_heads+=("$cur_head")
    wt_branches+=("$cur_branch")
    wt_locked+=("$cur_locked")
    cur_path=""; cur_head=""; cur_branch=""; cur_locked=0
    return 0
}

while IFS= read -r line; do
    case "$line" in
        "worktree "*) flush_entry; cur_path="${line#worktree }" ;;
        "HEAD "*)     cur_head="${line#HEAD }" ;;
        "branch "*)   cur_branch="$(git rev-parse --abbrev-ref "${line#branch }" 2>/dev/null || true)" ;;
        "locked"*)    cur_locked=1 ;;
        "detached")   cur_branch="" ;;
    esac
done < <(git worktree list --porcelain)
flush_entry

main_root="${wt_paths[0]}"
main_head="${wt_heads[0]}"
main_branch="${wt_branches[0]}"

self_label="$self_root"
[[ "$self_root" != "$main_root" ]] && self_label="${self_root#"$main_root"/}"

say() { [[ "$QUIET" -eq 1 ]] || echo "$@"; }

# `${array[*]}` avec IFS ne colle qu'un SEUL caractere entre les elements : pour un separateur
# de deux caracteres (", "), il faut joindre a la main.
join_by() {
    local sep="$1"; shift
    local out="" item
    for item in "$@"; do
        [[ -n "$out" ]] && out+="$sep"
        out+="$item"
    done
    printf '%s' "$out"
}

say "== check-parallel-sessions =="
say "  depot            : $main_root"
say "  base             : $BASE_REF ($(printf '%.7s' "$base_sha"))"
say "  worktrees        : ${#wt_paths[@]} (1 principal + $(( ${#wt_paths[@]} - 1 )) lie(s))"
age_note=""
[[ "$AGE_MINUTES" -eq 0 ]] && age_note="  (signal W1 desactive)"
say "  inactivite min.  : ${AGE_MINUTES} min$age_note"
if [[ "$INCLUDE_SELF" -eq 1 ]]; then
    say "  soi              : $self_label  (INCLUS — --include-self)"
else
    say "  soi              : $self_label  (exclu du signal d'activite)"
fi
say

inflight=0
residus=0
collisions=0
unreadable=0
open_prs=0
gh_status="ok"

# --- Checkout principal : W2 (sale), W3 (en avance), I2 (en retard) ----------------------
say "-- Checkout principal --"
main_notes=()
main_busy=0

if main_status="$(git --no-optional-locks -C "$main_root" status --porcelain 2>/dev/null)"; then
    if [[ -n "$main_status" ]]; then
        main_notes+=("W2 sale ($(printf '%s\n' "$main_status" | wc -l) fichier(s) non commite(s))")
        main_busy=1
    fi
else
    main_notes+=("statut illisible — compte comme occupe par prudence")
    main_busy=1
fi

ahead="$(git rev-list --count "$base_sha..$main_head" 2>/dev/null || echo 0)"
behind="$(git rev-list --count "$main_head..$base_sha" 2>/dev/null || echo 0)"
if [[ "$ahead" -gt 0 ]]; then
    main_notes+=("W3 en avance de $ahead commit(s) non pousse(s) sur $BASE_REF")
    main_busy=1
fi
[[ "$behind" -gt 0 ]] && main_notes+=("I2 en retard de $behind commit(s) sur $BASE_REF (informatif)")

main_details=""
if [[ ${#main_notes[@]} -gt 0 ]]; then
    main_details=" — $(join_by '; ' "${main_notes[@]}")"
fi
if [[ "$main_busy" -eq 1 ]]; then
    say "  EN VOL  $main_root [$(printf '%.7s' "$main_head") ${main_branch:-(detached)}]$main_details"
    inflight=$((inflight + 1))
else
    say "  OK      $main_root [$(printf '%.7s' "$main_head") ${main_branch:-(detached)}]$main_details"
fi

# --- Worktrees lies : W1 (actif), I1 (residu), I3 (repertoire absent) --------------------
say
say "-- Worktrees lies --"
linked=0
for i in "${!wt_paths[@]}"; do
    [[ "$i" -eq 0 ]] && continue
    wt="${wt_paths[$i]}"
    head="${wt_heads[$i]}"
    branch="${wt_branches[$i]}"
    locked="${wt_locked[$i]}"
    linked=$((linked + 1))

    label="${wt#"$main_root"/}"
    tag="[$(printf '%.7s' "$head") ${branch:-(detached)}]"

    # Enregistrement perime : le repertoire a disparu. Jamais une session en vol.
    if [[ ! -d "$wt" ]]; then
        say "  INFO    $label $tag — I3 repertoire absent (enregistrement perime, voir 'git worktree prune')"
        continue
    fi

    # Soi-meme : le script tourne dedans, donc y cree de l'activite. L'inclure garantirait
    # un faux positif a tous les coups.
    if [[ "$wt" == "$self_root" && "$INCLUDE_SELF" -eq 0 ]]; then
        say "  SOI     $label $tag — exclu (--include-self pour l'inclure)"
        continue
    fi

    # W1 : activite recente. C'est le SEUL signal qui prouve qu'une session est vivante.
    if [[ "$AGE_MINUTES" -gt 0 ]] && gw_is_recently_active "$wt" "$AGE_MINUTES" "$main_root" "$git_common_dir"; then
        say "  EN VOL  $label $tag — W1 activite il y a moins de ${AGE_MINUTES} min"
        inflight=$((inflight + 1))
        continue
    fi

    # Sans activite recente, tout le reste est du DECHET DE VAGUE, pas une session : le
    # dire BUSY rendrait le verdict perpetuellement rouge (les worktrees survivent aux
    # vagues eteintes). On le signale pour le prune, sans bloquer.
    reasons=()
    if [[ -f "$git_common_dir/worktrees/$(basename "$wt")/locked" || "$locked" -eq 1 ]]; then
        reasons+=("locked")
    fi
    if status_out="$(git --no-optional-locks -C "$wt" status --porcelain 2>/dev/null)"; then
        [[ -n "$status_out" ]] && reasons+=("dirty ($(printf '%s\n' "$status_out" | wc -l) fichier(s))")
    else
        say "  ILLISIBLE $label $tag — statut Git illisible, compte comme occupe par prudence"
        unreadable=$((unreadable + 1))
        continue
    fi
    git merge-base --is-ancestor "$head" "$base_sha" 2>/dev/null || reasons+=("unmerged")

    if [[ ${#reasons[@]} -gt 0 ]]; then
        say "  RESIDU  $label $tag — I1 $(join_by ', ' "${reasons[@]}") (inactif : voir scripts/prune-stale-worktrees.sh)"
        residus=$((residus + 1))
    else
        say "  OK      $label $tag"
    fi
done
[[ "$linked" -eq 0 ]] && say "  (aucun worktree lie)"

# --- W5 : une meme branche checked out dans plusieurs worktrees --------------------------
# C'est la collision que cette feature nomme : deux sessions qui ecrivent la meme ref.
say
say "-- Branches partagees --"
shared_found=0
declare -a seen=()
for i in "${!wt_branches[@]}"; do
    branch="${wt_branches[$i]}"
    [[ -z "$branch" ]] && continue

    already=0
    for s in "${seen[@]:-}"; do [[ "$s" == "$branch" ]] && already=1; done
    [[ "$already" -eq 1 ]] && continue
    seen+=("$branch")

    holders=()
    for j in "${!wt_branches[@]}"; do
        if [[ "${wt_branches[$j]}" == "$branch" ]]; then
            p="${wt_paths[$j]}"
            holders+=("${p#"$main_root"/}")
        fi
    done
    if [[ ${#holders[@]} -ge 2 ]]; then
        say "  COLLISION $branch — ${#holders[@]} worktrees : $(join_by ', ' "${holders[@]}")"
        collisions=$((collisions + 1))
        shared_found=1
    fi
done
[[ "$shared_found" -eq 0 ]] && say "  (aucune branche partagee entre deux worktrees)"

# --- W4 : pull requests ouvertes ---------------------------------------------------------
say
say "-- Pull requests ouvertes --"
if [[ "$USE_GH" -eq 0 ]]; then
    gh_status="ignore"
    say "  (signal ignore — --no-gh)"
elif ! command -v gh >/dev/null 2>&1; then
    gh_status="indisponible"
    say "  INDISPONIBLE — binaire 'gh' absent du PATH"
elif ! gh_out="$(gh pr list --state open --limit 50 2>/dev/null)"; then
    gh_status="indisponible"
    say "  INDISPONIBLE — 'gh pr list' a echoue (hors ligne ou non authentifie)"
else
    if [[ -n "$gh_out" ]]; then
        open_prs="$(printf '%s\n' "$gh_out" | wc -l)"
        while IFS= read -r pr; do
            [[ -n "$pr" ]] && say "  PR OUVERTE  $pr"
        done <<<"$gh_out"
    else
        say "  (aucune)"
    fi
fi

# --- Verdict ------------------------------------------------------------------------------
busy=0
[[ "$inflight"   -gt 0 ]] && busy=1
[[ "$collisions" -gt 0 ]] && busy=1
[[ "$unreadable" -gt 0 ]] && busy=1
[[ "$open_prs"   -gt 0 ]] && busy=1

gh_note=""
if [[ "$gh_status" == "indisponible" ]]; then
    if [[ "$REQUIRE_GH" -eq 1 ]]; then
        busy=1
        gh_note=" (signal PR INDISPONIBLE et --require-gh : on refuse de conclure)"
    else
        gh_note=" (signal PR indisponible — verdict rendu sur les seuls signaux locaux)"
    fi
fi

say
say "== Verdict =="
say "  sessions en vol  : $inflight"
say "  collisions       : $collisions"
say "  PR ouvertes      : $open_prs"
say "  residus (inactifs, non bloquants) : $residus"
[[ "$unreadable" -gt 0 ]] && say "  etats illisibles : $unreadable"

if [[ "$busy" -eq 1 ]]; then
    echo "VERDICT: BUSY — au moins une session est en vol ou en collision. NE PAS demarrer de vague, NE PAS lancer le prune --apply.$gh_note"
    exit 1
fi
echo "VERDICT: CLEAR — aucune session en vol, aucune collision.$gh_note"
exit 0
