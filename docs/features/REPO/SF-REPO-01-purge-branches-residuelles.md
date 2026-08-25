# Mini-spec — REPO / SF-REPO-01 — Purge des branches locales résiduelles `feat/SF-28-*`

> Feature : `REPO` — housekeeping du dépôt Git (aucun code applicatif, aucune migration, aucun écran)

---

## Identifiant

`REPO / SF-REPO-01`

## Feature parente

`REPO` — Hygiène du dépôt (housekeeping Git). Lignée : `worktree_doc_cleanup` (SF-CLEANUP-01,
2026-07-01) et `stale-worktrees-housekeeping` (SF-HK-01, 2026-07-10).

## Statut

`done`

## Date de création

2026-08-25

## Branche Git

`chore/repo-purge-branches-sf28`

---

## Objectif

Supprimer les branches locales résiduelles `feat/SF-28-*` dont le contenu est déjà
intégralement présent dans `origin/main` (squash-merge), sans jamais toucher à une branche
portant du travail non mergé ni à une branche utilisée par une session en cours.

---

## Contexte

La livraison de F-28 (Atelier / Claude Code Lite) s'est faite en squash-merge. Après merge, les
branches locales subsistent : `git branch --no-merged` les affiche comme « non mergées » alors
que leur patch est déjà dans `main`. Source de vérité = `git cherry origin/main <branche>` :
une ligne `-` signifie « patch équivalent déjà présent dans `origin/main` », une ligne `+`
signifie « travail non mergé » (interdiction de supprimer).

État constaté au 2026-08-25 (105 branches locales, dont 12 `feat/SF-28-*`) :

| Branche locale | SHA | `git cherry origin/main` | Verdict |
|---|---|---|---|
| `feat/SF-28-01-atelier-workspace` | `b7e5292` | `-` (0 `+`) | supprimable |
| `feat/SF-28-02-atelier-toolloop` | `3dbdacd` | `-` (0 `+`) | supprimable |
| `feat/SF-28-03-atelier-ecran` | `0c07bae` | `-` (0 `+`) | supprimable |
| `feat/SF-28-05-chat-atelier-streaming` | `a49ced6` | `-` (0 `+`) | supprimable |
| `feat/SF-28-07-frontend-gold-atelier` | `da92967` | `-` (0 `+`) | supprimable |
| `feat/SF-28-08-managed-agent-foundation` | `73fa59b` | `-` (0 `+`) | supprimable |
| `feat/SF-28-09-managed-agent-sessions` | `c8678fc` | `-` (0 `+`) | supprimable |
| `feat/SF-28-10-agent-exec-stream` | `0771e64` | vide (ancêtre direct) | supprimable |
| `feat/SF-28-11-frontend-mode-execution` | `3afc2fb` | `-` (0 `+`) | supprimable |
| `feat/SF-28-13-atelier-add-files` | `491099e` | `-` (0 `+`) | supprimable |
| `feat/SF-28-14-workspace-file-ops` | `3583798` | `-` (0 `+`) | supprimable |
| `feat/SF-28-15-file-explorer` | `9241ea4` | `-` (0 `+`) | supprimable |

Le mandat annonçait « ~20 branches `feat/SF-28-*` / `feat/SF-31-*` ». Vérification faite :
**aucune branche `feat/SF-31-*` n'existe** (ni locale ni distante) — F-31 a été livrée par des
branches `docs/F-31-*`, dont `docs/F-31-cloture` **actuellement utilisée par une session
concurrente** (worktree `wf_1755f628-7e5-2`). Le périmètre réel est donc de 12 branches.

---

## Comportement attendu

### Cas nominal

1. `git fetch origin` — raisonner sur `origin/main`, jamais sur un working tree.
2. Pour chaque branche candidate : `git cherry origin/main <branche>` → **0 ligne `+`** exigée.
3. Pour chaque branche candidate : vérifier qu'elle n'est **checked out dans aucun worktree**
   (`git worktree list --porcelain`).
4. Consigner le SHA de chaque branche dans cette mini-spec (réversibilité).
5. `git branch -D <branche>` pour les 12 branches validées.
6. Contrôle final : `git rev-parse origin/main` inchangé, branches protégées toujours présentes.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `git cherry` renvoie au moins une ligne `+` (travail non mergé) | **NE PAS supprimer** — conserver et signaler dans la PR |
| Branche checked out dans un worktree actif (ex. `docs/F-31-cloture`, `worktree-*`) | **NE PAS supprimer** — exclusion en amont, et refus de `git branch -D` par Git |
| Branche absente (déjà purgée par une autre session) | Ignorer sans erreur — idempotence |
| `git fetch` en échec (réseau) | **STOP** — la comparaison à `origin/main` ne serait pas fiable |

---

## Critères d'acceptation

- [x] Les 12 branches `feat/SF-28-*` listées ci-dessus sont absentes de `git branch --list 'feat/SF-28-*'`.
- [x] Chaque branche supprimée avait **0 commit `+`** face à `origin/main`, tracé dans cette mini-spec.
- [x] Le SHA de chaque branche supprimée est consigné → restauration par
      `git branch <nom> <sha>` (opération **réversible**). Aucun risque de GC : les 12 commits
      restent référencés par les branches distantes `origin/feat/SF-28-*`, volontairement
      conservées (voir « Hors scope »).
- [x] Aucune branche portant du travail non mergé n'a été supprimée.
- [x] Contrôle négatif : `chore/canvas-flaky-fix-and-library-rename` (commits `+`) et
      `docs/F-31-cloture` (worktree actif) sont **toujours présentes**.
- [x] `origin/main` inchangé : `git rev-parse origin/main` identique avant/après (`116bd51`).
- [x] Aucun fichier source, aucune migration Liquibase, aucun écran Angular touché
      → seul un document `docs/**` est commité.

## Plan de test minimal

- **Sécurité pré-suppression** (bloquant) : `git cherry origin/main <branche>` == 0 ligne `+`
  pour les 12 branches. Exécuté et tracé (tableau ci-dessus).
- **Isolation worktree** : `git worktree list --porcelain` — aucune des 12 branches n'y figure.
- **Non-régression `main`** : `git rev-parse origin/main` avant == après (`116bd51`).
- **Contrôle négatif** : branches à commits `+` et branches de worktrees actifs conservées.
- **Idempotence** : ré-exécution sans erreur (branche absente = no-op).
- **Isolation `user_id`** : sans objet — housekeeping Git pur, aucun accès aux données
  applicatives, aucun endpoint, aucune requête SQL.

---

## Tables / endpoints / composants impactés

Aucun. Pas de table, pas de migration Liquibase, pas d'endpoint, pas de composant Angular,
pas d'appel fournisseur (ni `AIProvider` ni Anthropic), pas de secret manipulé.

## Préoccupations transversales

| Préoccupation | Impacté ? | Justification |
|---|---|---|
| Auth / Principal | Non | Aucun code d'authentification touché |
| Contexte tenant / `user_id` | Non | Aucun accès aux données applicatives |
| Plans / limites | Non | Aucun gate ni quota touché |
| Navigation / routing | Non | Aucune route Angular touchée |

---

## Périmètre

### Hors scope (explicite, arbitrages tracés)

- **Branches distantes `origin/feat/SF-28-*`** : conservées. La suppression distante est moins
  réversible qu'une suppression locale et retire le contexte de diff des PR fermées.
  Alternative écartée : purge locale + distante (précédent SF-CLEANUP-01, appliqué à des
  branches `docs/` uniquement). Réversible : la purge distante reste faisable plus tard.
- **Autres familles de branches locales** (`feat/SF-01-*` → `feat/SF-27-*`, `fix/*`,
  `verify-main*`, `worktree-agent-*`, `worktree-wf_*`) : hors du périmètre annoncé pour cette
  subfeature, et plusieurs `worktree-*` appartiennent à des sessions vivantes. Non touchées.
- **Worktrees** : aucun `git worktree remove` ici (les worktrees présents sont actifs).
- **Aucune modification** de `docs/PROJECT.md`, du code backend/frontend ou de la configuration.
- **Aucun déploiement staging** (l'orchestrateur déploie une seule fois en fin de vague).
