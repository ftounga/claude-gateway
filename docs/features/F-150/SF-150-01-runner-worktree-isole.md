# Mini-spec — F-150 / SF-150-01 — Runner : worktree isolé (create / remove / reap) + refus si pas de git

## Identifiant

`F-150 / SF-150-01`

## Feature parente

`F-150` — Sous-agent `task` capable d'agir, isolé par git-worktree sur le poste

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-150-01-worktree-runner`

---

## Objectif

Doter le runner du poste d'une **opération de cycle de vie de worktree git** (créer / retirer / réaper), routée par le protocole d'outils existant, pour que la sous-boucle `task` (SF-150-02) puisse agir dans un **worktree git isolé sous la racine du poste**, avec **refus propre** si le projet n'est pas un dépôt git.

---

## Comportement attendu

### Cas nominal

1. La gateway émet `worktree_create` (trame `tool_call`) avec `project` = le projet cible (relatif à la racine du poste) et `input = {taskId}`.
2. Le runner vérifie que le dossier du projet est un dépôt git (`<projet>/.git` présent, `git rev-parse` OK). Il élague d'abord les entrées mortes (`git worktree prune`), puis crée un worktree isolé : `git -C <projet> worktree add -b atelier/task/<taskId> <racine>/.atelier-worktrees/<taskId> HEAD`.
3. Le runner répond `ok` avec un contenu JSON : `{"worktreePath": ".atelier-worktrees/<taskId>", "branch": "atelier/task/<taskId>"}`. `worktreePath` est **relatif à la racine du poste** → réutilisable tel quel comme `project` d'un appel de la sous-boucle.
4. `worktree_remove` (`input = {taskId}`, `project` = le projet cible) démonte le worktree : `git -C <projet> worktree remove --force <racine>/.atelier-worktrees/<taskId>` ; en cas d'échec, suppression récursive du dossier + `git worktree prune`. **Idempotent** (worktree déjà absent = succès).
5. `worktree_reap` (`input = {}` ou `{keep:[taskId…]}`) supprime les worktrees orphelins sous `<racine>/.atelier-worktrees/` non listés dans `keep`. Un **reap au démarrage du runner** supprime tous les orphelins (best-effort).
6. Côté gateway, `RunnerToolGateway` expose `worktreeCreate / worktreeRemove / worktreeReap`, bornant le `taskId` avant émission (le runner refait foi).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Projet cible n'est pas un dépôt git | Refus propre, aucun worktree créé, aucune écriture | `not_git` |
| Binaire `git` absent du poste | Refus propre | `not_git` |
| `taskId` manquant / malformé (hors `[A-Za-z0-9_-]{1,64}`) | Refus avant exécution | `invalid_input` |
| Chemin de worktree hors racine du poste (impossible par construction, contrôlé) | Refus | `path_outside_root` |
| `git worktree add` échoue (ex. HEAD absent, dépôt vide) | Erreur remontée avec message court | `io_error` |
| Runner ancien sans l'opération (rétro-compat) | `unsupported_tool` → `task` refusera proprement (SF-150-02) | `unsupported_tool` |
| Échéance dépassée pendant `git` | Interruption, process tué | `timeout` |

---

## Critères d'acceptation

- [ ] `worktree_create` sur un vrai dépôt git crée un worktree sous `<racine>/.atelier-worktrees/<taskId>`, une branche `atelier/task/<taskId>`, et renvoie `worktreePath` **relatif à la racine** + `branch`.
- [ ] `worktree_create` sur un dossier non-git renvoie `not_git` **sans rien écrire**.
- [ ] `worktree_remove` démonte le worktree et est **idempotent** (deuxième appel = succès).
- [ ] `worktree_reap` supprime les orphelins sous `.atelier-worktrees/` et respecte `keep`.
- [ ] Le worktree vit **toujours** sous la racine du poste (jamais d'écriture hors zone).
- [ ] `taskId` malformé est refusé côté gateway (`invalid_input`) et côté runner.
- [ ] Rétro-compat : `ToolRouter` d'un runner sans worktree → `unsupported_tool` (comportement `FileTools` inchangé) ; les outils existants ne changent pas.
- [ ] Les trames `tool_call`/`tool_result` transportent les nouvelles opérations sans nouveau transport ni nouveau champ d'enveloppe.

---

## Périmètre

### Hors scope (explicite)

- La sous-boucle `task` elle-même (SF-150-02).
- La confirmation/permissions des écritures dans le worktree (SF-150-03).
- Le modèle `task` et la doctrine de prompt (SF-150-04).
- La restitution branche/diff et les plafonds (SF-150-05).
- **Repli copie** si pas de git : **écarté** (décision PO 1 = refus propre, pas de copie en V1).
- Tout composant cluster ; tout SDK.

---

## Technique

### Composants impactés

**Préoccupation transversale — Exécution / runner (DRAPEAU majeur).** Liste des composants :

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `runner/WorktreeTool` (nouveau) | création | crée/retire/réape les worktrees via `git` (ProcessBuilder, pas le shell élu) |
| `runner/GitCli` (nouveau) | création | invocation `git` bornée, détection binaire absent |
| `runner/ToolRouter` | modif | route `worktree_*` vers `WorktreeTool` (préfixe), sinon inchangé |
| `runner/ProjectScopes` | modif | construit le `WorktreeTool` par projet (racine du poste + dossier du projet) |
| `runner/ToolStack` | modif | **reap au démarrage** des orphelins sous `.atelier-worktrees/` |
| `backend/RunnerToolGateway` | modif | `worktreeCreate/Remove/Reap` + bornage `taskId`, délai dédié |

Aucun autre appelant de l'auth/tenant/limites n'est touché : l'isolation `user_id`/workspace reste résolue en amont (gateway), le worktree n'est qu'un dossier transitoire sous la racine du poste. Cible RUNNER exclusivement.

### Migration Liquibase

- [ ] Non applicable (aucune table).

---

## Plan de test

### Tests unitaires (runner)

- [ ] `WorktreeToolTest` — create sur dépôt git réel → worktree + branche + `worktreePath` relatif.
- [ ] `WorktreeToolTest` — dossier non-git → `not_git`, rien créé.
- [ ] `WorktreeToolTest` — remove idempotent (2 appels).
- [ ] `WorktreeToolTest` — reap supprime orphelins, respecte `keep`.
- [ ] `WorktreeToolTest` — `taskId` malformé → `invalid_input`.
- [ ] `ToolRouterTest` — `worktree_create` routé vers le WorktreeTool ; tout le reste inchangé.

### Tests unitaires (backend)

- [ ] `RunnerToolGatewayTest` — `worktreeCreate` émet la trame `worktree_create` avec `input.taskId` et le délai worktree.
- [ ] `RunnerToolGatewayTest` — `taskId` malformé → `invalid_input` avant émission.
- [ ] `RunnerToolGatewayTest` — `unsupported_tool` d'un runner ancien remonte tel quel.

### Tests d'intégration / contrat

- [ ] `ToolFramesContractTest` reste vert (aucune dérive d'enveloppe) ; nouvelle op transportée par le même canal.

### Isolation workspace

- [ ] Non applicable directement : aucune donnée persistée ; le worktree est confiné sous la racine du poste (test `path_outside_root`/chemin sous racine). L'isolation `user_id` reste garantie en amont (gateway), inchangée.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (enabler).

### Mise à jour runner nécessaire

**OUI — nouvelle opération runner.** Rétro-compat garantie : un runner antérieur (sans `WorktreeTool`) route `worktree_*` vers `FileTools` qui répond `unsupported_tool` ; la gateway remonte ce code et `task` (SF-150-02) refusera proprement.

### Questions ouvertes impactées

- Aucune (décision PO 1 tranche le repli : refus propre, pas de copie).

---

## Notes et décisions

- **Décision PO 1** appliquée : projet non-git → **refus propre** (`not_git`), pas de repli copie en V1.
- `git` est invoqué **directement** (ProcessBuilder `git …`), pas via le shell élu : portable multi-OS quand git est présent, et hors du champ de la porte d'autorisation (opération d'orchestration, pas une commande utilisateur).
- Emplacement des worktrees : `<racine>/.atelier-worktrees/<taskId>`, **sous la racine du poste** (sinon `ProjectScopes` rejetterait `path_outside_root`).
- Branche du worktree : `atelier/task/<taskId>` (SF-150-05 en fera la référence de restitution).
