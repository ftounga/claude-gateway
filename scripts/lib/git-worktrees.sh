#!/usr/bin/env bash
#
# git-worktrees.sh — REPO / SF-SP-01
#
# Fonctions partagees par les outils de housekeeping Git du depot.
# Ce fichier est une BIBLIOTHEQUE : il se source, il ne s'execute pas.
#
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/git-worktrees.sh"
#
# Pourquoi une bibliotheque plutot qu'une copie : la detection d'activite recente est
# subtile et a deja ete corrigee une fois (SF-REPO-02, correctif du 2026-08-26 — la seule
# mtime de la racine du worktree ne bouge pas quand un agent edite un fichier imbrique).
# Dupliquer cette fonction, c'est garantir que le prochain correctif n'en corrigera qu'une.
#
# Mini-spec : docs/features/SESSIONS-PARALLELES/SF-SP-01-preflight-sessions-en-vol.md

# --- Repertoire de metadonnees Git propre a un worktree ---------------------------------
# Pour le checkout principal, c'est le git-dir commun lui-meme ; pour un worktree lie,
# c'est <git-dir commun>/worktrees/<nom du repertoire>.
gw_meta_dir() {
    local wt="$1" main_root="$2" git_common_dir="$3"
    if [[ "$wt" == "$main_root" ]]; then
        printf '%s\n' "$git_common_dir"
    else
        printf '%s\n' "$git_common_dir/worktrees/$(basename "$wt")"
    fi
}

# --- Detection d'activite recente -------------------------------------------------------
# Un agent vivant peut avoir un arbre propre entre deux commits : ce test repere l'activite
# recente independamment de l'etat Git.
#
# La mtime du repertoire racine du worktree ne suffit pas : elle ne change que lorsqu'une
# entree est creee ou supprimee *a la racine*. Un agent editant backend/src/... pendant des
# heures la laisserait intacte. On teste donc trois signaux, du moins cher au plus cher :
#   1. la mtime de la racine du worktree ;
#   2. les metadonnees Git du worktree (index, HEAD, logs/HEAD) — rafraichies par a peu pres
#      toute commande Git executee dedans, y compris `git status` ;
#   3. un parcours recursif du contenu, elague des repertoires de build (node_modules,
#      target, dist, .angular, .git) et arrete des le premier fichier recent (-quit).
#
# Usage : gw_is_recently_active <worktree> <minutes> <main_root> <git_common_dir>
# Retour : 0 si une activite a ete detectee, 1 sinon.
gw_is_recently_active() {
    local wt="$1" minutes="$2" main_root="$3" git_common_dir="$4"
    local threshold="-${minutes} minutes"

    # 1. Racine du worktree.
    if [[ -n "$(find "$wt" -maxdepth 0 -newermt "$threshold" -print 2>/dev/null)" ]]; then
        return 0
    fi

    # 2. Metadonnees Git propres a ce worktree.
    local wt_git_dir meta
    wt_git_dir="$(gw_meta_dir "$wt" "$main_root" "$git_common_dir")"
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
