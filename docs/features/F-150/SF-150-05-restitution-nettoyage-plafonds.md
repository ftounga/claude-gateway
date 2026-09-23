# Mini-spec — F-150 / SF-150-05 — Restitution des changements + nettoyage + plafonds

## Identifiant

`F-150 / SF-150-05`

## Feature parente

`F-150` — Sous-agent `task` capable d'agir, isolé par git-worktree sur le poste

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-150-05-restitution`

---

## Objectif

Faire **remonter dans la synthèse** la référence de restitution d'une sous-tâche `task` (branche + diff résumé), en **committant** le worktree sur sa branche — **jamais de merge aveugle** dans la copie de travail réelle —, garantir le **nettoyage** (démontage + reap) et arrêter les **plafonds/réglages**.

---

## Comportement attendu

### Cas nominal

1. À la fin d'une sous-tâche `task`, la gateway demande au runner `worktree_finalize` : `git add -A` + `git commit` sur la **branche du worktree** (`atelier/task/<taskId>`) s'il y a des changements, puis `git diff --stat` du commit.
2. La **synthèse** remontée à la boucle principale porte alors : la **branche** + le **diff résumé** + la consigne « reprends-la explicitement (merge/cherry-pick) ». Sans changement : « rien à reprendre ».
3. Le worktree est ensuite **démonté** (nettoyage garanti, D9) ; la **branche reste** dans le dépôt → la reprise est un **acte explicite ultérieur**, jamais un merge aveugle.
4. **Reap** des orphelins : au démarrage du runner (SF-150-01) et via l'opération `worktree_reap`.
5. **Plafonds/réglages** : le plafond de délégations par message (`app.atelier.max-delegations`) borne aussi le nombre de `task` par message (knob existant réutilisé).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Runner ancien (`worktree_finalize` = `unsupported_tool`) | synthèse remonte **sans** référence branche/diff, sans casser (rétro-compat) |
| Worktree absent au finalize | erreur `not_found` côté runner ; la synthèse remonte sans référence |
| `git commit` échoue | `committed=false`, la synthèse le reflète (pas de branche/diff faux) |

---

## Critères d'acceptation

- [ ] `worktree_finalize` committe les changements sur la branche du worktree et rend `{branch, committed, hasChanges, diffStat}`.
- [ ] La synthèse d'un `task` avec changements porte la **branche** et le **diff résumé** + la consigne de reprise explicite.
- [ ] Sans changement : la synthèse le dit (« rien à reprendre »).
- [ ] **Jamais de merge aveugle** : la branche reste dans le dépôt, aucune écriture dans la copie de travail réelle.
- [ ] La restitution a lieu **avant** le démontage ; le worktree est **toujours** démonté (D9).
- [ ] Rétro-compat : un runner sans `worktree_finalize` ne casse pas le `task` (synthèse sans référence).
- [ ] Plafond de `task`/message : `max-delegations` s'applique.

---

## Périmètre

### Hors scope (explicite)

- Rendu terminal du `task` (SF-150-06, option — voir décision).
- Merge automatique dans la copie de travail (écarté par le cadrage §5).

---

## Technique

### Composants impactés

- `runner/WorktreeTool` : opération **`worktree_finalize`** (add/commit/diff-stat), diff borné.
- `backend/RunnerToolGateway` : `worktreeFinalize(target, callId, taskId, message)`, délai worktree.
- `backend/AtelierChatService.task()` : appelle `worktreeFinalize` **avant** le démontage, joint la référence à la synthèse (`taskRestitution`).

**Mise à jour runner nécessaire** (nouvelle op `worktree_finalize`), **rétro-compatible** (`unsupported_tool` ⇒ synthèse sans référence).

Aucune table. Migration Liquibase : **non applicable**.

### Préoccupation transversale — Plans / limites

Le nombre de `task` par message est borné par `app.atelier.max-delegations` (knob existant, partagé avec `explore`). Composant : `AtelierChatService` (gating `delegations`/`maxDelegations`).

---

## Plan de test

### Tests unitaires / intégration

- [ ] Runner `WorktreeToolTest` : `finalize` committe + rend le diff nommant le fichier ; sans changement → `hasChanges=false`.
- [ ] Backend `RunnerToolGatewayTest` : `worktreeFinalize` émet la trame avec `taskId`/`message`.
- [ ] Backend `AtelierChatServiceTaskTest` : la synthèse porte branche + diff + « merge/cherry-pick » ; `finalize` appelé **avant** `remove`.

### Isolation workspace

- [ ] Non applicable directement : la restitution reste dans le worktree/dépôt du poste ; isolation `user_id`/workspace inchangée (permission par workspace).

---

## Dépendances

### Subfeatures bloquantes

- `SF-150-02` — **Done** (sous-boucle `task` + démontage garanti).
- `SF-150-01` — **Done** (worktree + reap).

---

## Notes et décisions

- **Restitution retenue (cadrage §5)** : `task` **commit sur une branche** du worktree ; la synthèse remonte **branche + diff résumé** ; la reprise (merge/cherry-pick) est un **acte explicite ultérieur**. Écarté : appliquer le diff dans la copie de travail réelle pendant la tâche.
- **Plafond `task`/message** : décision — **réutiliser `max-delegations`** (knob existant, partagé explore+task) plutôt qu'un réglage dédié : un seul plafond de délégations par message, plus simple, déjà en place.
- **« Taille de worktree »** : décision — **non plafonnée** : un worktree git est une copie de travail (checkout) dont l'empreinte est celle du projet ; il n'y a pas de plafond artificiel pertinent. Le reap au démarrage et le démontage garanti bornent l'accumulation.
- **Nettoyage/reap** : déjà couverts (démontage en `finally` SF-150-02 ; reap au démarrage + op `worktree_reap` SF-150-01).
