# SF-HK-01 — Nettoyage des worktrees résiduels et purge des branches superseded

> Feature : `stale-worktrees-housekeeping`
> Type : housekeeping dépôt Git (pas de code applicatif, pas de migration, pas d'écran)
> Date : 2026-07-10

## Objectif (une phrase)

Supprimer les worktrees Git résiduels du workflow `wf_f56dd998-79d` et purger les
branches dont le contenu est déjà intégralement présent dans `main`, sans jamais
toucher à une branche portant du travail non mergé.

## Contexte

3 worktrees résiduels subsistent sous `.claude/worktrees/` :

| Worktree | HEAD | Nature | Contenu dans `main` ? |
|----------|------|--------|-----------------------|
| `wf_f56dd998-79d-2` | `43bba5c` (detached) | docs(F-21) déjà mergé (#78) | Oui — ancêtre direct de `main` |
| `wf_f56dd998-79d-3` | `bb991e4` `feat/SF-22-01-panneau-canvas-artifacts` | SF-22-01 canvas artifacts | Oui — `git cherry` = `-` (patch équivalent dans `main`) |
| `wf_f56dd998-79d-4` | `df26239` `docs/F-23-complete-2026-07-03` | docs(F-23) | Oui — `git cherry` = `-` |

Branches docs post-merge superseded (squash mergé, `git cherry main <branch>` sans `+`) :
`docs/F-05/06/07/08/13/15/16/21/23-complete-*`.

## Comportement nominal

1. Vérifier que chaque worktree ciblé est propre (`git status --porcelain` vide). ✅ vérifié
2. Vérifier que chaque commit de worktree est représenté dans `main`
   (ancêtre direct ou `git cherry` sans `+`). ✅ vérifié
3. Vérifier que chaque branche à purger n'a **aucun** commit `+` face à `main`. ✅ vérifié
4. `git worktree remove` des 3 worktrees, puis `git worktree prune`.
5. `git branch -D` des branches locales superseded (docs nommées + branches
   associées aux worktrees fermés + branches de suivi `worktree-wf_f56dd998-79d-*`).
6. `git push origin --delete` des branches distantes docs superseded confirmées
   mergées dans `origin/main`.

## Cas d'erreur

- Worktree sale (modifications non commitées) → **NE PAS** supprimer, signaler. (n/a : tous propres)
- Branche avec commit `+` (travail non mergé) → **NE PAS** supprimer.
  Exemple protégé : `chore/canvas-flaky-fix-and-library-rename` (2 commits `+`) → conservée.
- Worktree verrouillé → ne pas forcer. (n/a : aucun verrou)

## Critères d'acceptation vérifiables

- [ ] `git worktree list` ne référence plus aucun `wf_f56dd998-79d-*`.
- [ ] Les branches locales `docs/F-05/06/07/08/13/15/16/21/23-complete-*` sont supprimées.
- [ ] `feat/SF-22-01-panneau-canvas-artifacts` et `worktree-wf_f56dd998-79d-{2,3,4}` supprimées.
- [ ] Aucune branche portant un commit non mergé (`+`) n'a été supprimée.
- [ ] `main` (local et distant) inchangé : `git rev-parse main` identique avant/après.
- [ ] Le build n'est pas concerné (aucun fichier source touché).

## Plan de test minimal

- **Sécurité pré-suppression** : `git cherry main <branch>` == 0 ligne `+` pour chaque
  branche purgée ; `git merge-base --is-ancestor` ou `git cherry` pour chaque worktree HEAD.
- **Non-régression `main`** : `git rev-parse main` avant == après.
- **Isolation** : housekeeping Git pur, aucun accès données applicatives → pas de
  filtre `user_id` concerné.
- **Contrôle négatif** : `chore/canvas-flaky-fix-and-library-rename` toujours présente.

## Tables / endpoints / composants impactés

Aucun. Pas de code applicatif, pas de migration Liquibase, pas d'écran Angular,
pas d'endpoint. Opération sur les métadonnées Git locales et branches distantes.

## Hors périmètre

- Suppression des worktrees/branches d'autres workflows (`wf_c4d3e234-dda-*`,
  `worktree-agent-*`, `verify-main*`) — non ciblés par cette housekeeping.
- Suppression de `docs/F-03-complete` et `chore/F-01-docs-post-merge` (mergées mais
  hors liste nommée) — conservées par prudence de périmètre.
- Toute branche `feat/SF-*` de fonctionnalité non explicitement résiduelle.
- Aucune modification de `docs/PROJECT.md`, `PRODUCT_SPEC.md` ou du code.
