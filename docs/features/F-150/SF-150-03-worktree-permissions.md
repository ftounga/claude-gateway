# Mini-spec — F-150 / SF-150-03 — Confirmation / audit / permissions dans le worktree

## Identifiant

`F-150 / SF-150-03`

## Feature parente

`F-150` — Sous-agent `task` capable d'agir, isolé par git-worktree sur le poste

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-150-03-worktree-permissions`

---

## Objectif

Formaliser la politique de la sous-boucle `task` : **édition automatique** dans le worktree isolé (équivalent *acceptEdits*, décision PO 2) tout en gardant **`bash`/commandes toujours sous la porte** d'autorisation (`RunnerConfirmationGate` + politique SF-121-02), l'audit et l'isolation `user_id`/workspace inchangés.

---

## Comportement attendu

### Cas nominal

1. Dans un worktree (`task`), une **écriture** (`write_file`/`edit_file`/`multi_edit`) qui aurait demandé une confirmation (`app.atelier.ask-before-edit` actif) est **exécutée automatiquement** — le worktree est sûr par construction, on n'inonde pas l'utilisateur d'invites.
2. Dans un worktree, `bash`/commandes restent **sous la porte** : la politique de permission (allow/ask/deny SF-121-02) et la confirmation s'appliquent comme dans la boucle principale.
3. Chaque appel de la sous-boucle est **audité** (cible = le worktree, `RunnerAuditService.recordCall`/`recordDenied`), comme la boucle principale.
4. La permission reste résolue par **workspace** (`resolveEffect(userId, workspace, …)`), **jamais** par le chemin transitoire du worktree (D10).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Règle persistée **DENY** sur une écriture | respectée même dans le worktree (seul le **ASK** est levé, pas le DENY) |
| `bash` refusé (deny/ask non accordé/timeout) dans le worktree | commande **non émise**, refus rendu au modèle, audit `DENIED`/`TIMEOUT` |

---

## Critères d'acceptation

- [ ] Une écriture dans le worktree est **auto-allouée** même quand `ask-before-edit` est actif (aucune confirmation demandée), et exécutée.
- [ ] Une règle **DENY** persistée sur une écriture reste appliquée dans le worktree.
- [ ] `bash` dans le worktree **passe par la porte** quand `agent_ask_before_bash` est actif (confirmation demandée).
- [ ] La permission est résolue par **workspace** (pas par le chemin du worktree) ; isolation `user_id` inchangée.
- [ ] Les appels de la sous-boucle sont **audités** avec la cible worktree.

---

## Périmètre

### Hors scope (explicite)

- Modèle `task` + doctrine (SF-150-04).
- Restitution branche/diff + plafonds (SF-150-05).
- Rendu terminal (SF-150-06).

---

## Technique

### Composants impactés (préoccupation transversale — Auth / permissions)

- `AtelierChatService.executeToolOnRunner` : quand `projectOverride != null` (worktree) et outil d'écriture, un effet **ASK** devient **ALLOW** (*acceptEdits*) ; DENY et `bash` inchangés.
- `RunnerConfirmationGate`, `resolveEffect`, `AtelierPermissionService`, `RunnerAuditService` : **réutilisés**, non modifiés.

Aucune table. Migration Liquibase : **non applicable**.

---

## Plan de test

### Tests unitaires / intégration (backend)

- [ ] Écriture worktree auto malgré `ask-before-edit=true` : `confirmationGate.await` **jamais** appelé, `writeFile` exécuté.
- [ ] `bash` worktree avec `agent_ask_before_bash=true` : `confirmationGate.await` **appelé** (sous la porte).
- [ ] La permission utilise l'id **workspace** (résolution par workspace, pas par le chemin du worktree) — implicite via `resolveEffect(userId, workspace, …)`.

### Isolation workspace

- [ ] Applicable : la permission et l'audit portent le `(user_id, workspace_id)` du tour, jamais le chemin transitoire.

---

## Dépendances

### Subfeatures bloquantes

- `SF-150-02` — **Done** (sous-boucle `task` + `executeToolOnRunner(projectOverride)`).

---

## Notes et décisions

- **Décision PO 2** appliquée : édition **auto** dans le worktree (acceptEdits), `bash`/commandes **toujours** sous la porte + politique SF-121-02.
- On ne lève que le **ASK** pour les écritures ; un **DENY** persisté reste respecté (garde de sécurité).
