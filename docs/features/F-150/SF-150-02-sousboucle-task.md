# Mini-spec — F-150 / SF-150-02 — Backend : la sous-boucle `task`

## Identifiant

`F-150 / SF-150-02`

## Feature parente

`F-150` — Sous-agent `task` capable d'agir, isolé par git-worktree sur le poste

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-150-02-sousboucle-task`

---

## Objectif

Ajouter un outil **`task`** à la boucle principale : une **sous-boucle** (patron `runLoop`/`explore`) qui reçoit la **panoplie complète** (lecture + écriture + `bash`) routée vers le **runner** avec `project` = le **worktree isolé** (SF-150-01), impute son coût **au tour**, et ne remonte que sa **synthèse**.

---

## Comportement attendu

### Cas nominal

1. Le modèle appelle `task({prompt, path?})` en cible RUNNER.
2. La gateway génère un `taskId`, demande au runner `worktree_create` (SF-150-01) → obtient `worktreePath` (relatif à la racine) + branche.
3. Elle lance `AtelierTask.run(...)` : sous-boucle bornée, panoplie complète, chaque outil exécuté **sur le runner avec `project` = worktreePath** (via `executeToolOnRunner` avec projet surchargé) — porte de confirmation/audit/permissions **réutilisés**.
4. Le coût (jetons entrée/sortie/cache) de la sous-boucle est **additionné aux compteurs du tour** (D4).
5. **Seule la synthèse** (texte final de la sous-boucle) remonte comme `tool_result` (D5) ; les fichiers lus/écrits et traces d'outils du worktree **ne remontent pas**.
6. En fin de sous-tâche — succès, échec **ou** interruption — le worktree est **toujours démonté** (`worktree_remove`, D9).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Cible non-RUNNER (SANDBOX) | `task` non déclaré ; si forcé → refus « `task` n'est disponible que sur un poste connecté » |
| `prompt` absent/vide | refus « consigne requise » |
| `worktree_create` → `not_git` | refus **propre** avec message guidant (relayé du runner : « `task` requiert un projet git… ») |
| `worktree_create` → `unsupported_tool` (runner ancien) | refus guidant « mets à jour le runner, ou utilise `explore` » |
| Sous-boucle lève / échoue | synthèse d'échec « poursuis toi-même » ; worktree démonté quand même |
| Plafond de délégations atteint (`maxDelegations`) | refus « limite atteinte : poursuis toi-même » |
| Interruption / échéance du tour | sous-boucle s'arrête à sa frontière (stop partagé) ; worktree démonté |

---

## Critères d'acceptation

- [ ] `task` déclaré dans la panoplie **uniquement** en cible RUNNER et si délégations autorisées.
- [ ] Un `task` crée un worktree, exécute la sous-boucle **routée sur le worktree** (le `project` des appels runner = worktreePath), puis **démonte le worktree** (vérifié même sur échec).
- [ ] Le coût de la sous-boucle est **imputé au tour** (compteurs entrée/sortie/cache agrégés).
- [ ] **Seule la synthèse** remonte comme résultat d'outil (pas les fichiers/traces).
- [ ] `not_git` / `unsupported_tool` → refus propre avec message guidant, worktree jamais laissé.
- [ ] `task` est **distinct** d'`explore` (écrit/exécute vs lit) et **sériel** (pas dans le pool parallèle d'explore).
- [ ] Isolation `user_id`/workspace inchangée : la sous-boucle tourne pour le **même** `(user_id, host_id, workspace_id)` ; permission résolue par **workspace**, pas par le chemin transitoire.
- [ ] Cache F-134 préservé : `AtelierTask.SYSTEM` **stable**, rien de volatil (taskId/chemin/horodatage voyagent dans le message). Boucle principale inchangée (au plus une description d'outil stable ajoutée).

---

## Périmètre

### Hors scope (explicite)

- Modèle `task` configurable + doctrine `task` vs `explore` vs `bash` (SF-150-04) — ici on utilise le modèle **principal**.
- La formalisation *acceptEdits-in-worktree* et l'audit dédié (SF-150-03) — ici on **réutilise** la porte existante telle quelle.
- La restitution branche/diff et les plafonds/réglages dédiés (SF-150-05) — ici, nettoyage = `worktree_remove`.
- Rendu terminal du `task` (SF-150-06, option).

---

## Technique

### Composants impactés (préoccupations transversales)

**Plans / limites** : coût de `task` imputé au tour (F-118) — mêmes compteurs `inputTokens/outputTokens/cacheReadTokens/cacheWriteTokens` de `runLoop`, agrégés sur le thread du tour après retour (D4, patron explore). Réutilise `maxDelegations` comme plafond (SF-150-05 ajoutera un plafond dédié). Composants : `AtelierChatService` (`runLoop`, `task()`, `TaskOutcome`).

**Exécution / runner** : la sous-boucle route chaque outil via `executeToolOnRunner` avec un `RunnerTarget` dont `projectPath` = worktreePath ; création/retrait du worktree via `RunnerToolGateway` (SF-150-01). Composants : `AtelierChatService.executeToolOnRunner` (surcharge projet), `RunnerToolGateway`, `RunnerTarget`.

**Auth / tenant** : sous-boucle au **même** `(user_id, host_id, workspace_id)` ; permission résolue par workspace (D10). Composants : `RunnerConfirmationGate`, `resolveEffect` — inchangés (réutilisés).

**Concurrence** : `task` est **sériel** (hors pool parallèle d'`explore`) ; coût agrégé sur le thread du tour. Aucun compteur muté par un ouvrier.

### Tables impactées

Aucune. Migration Liquibase : **non applicable**.

---

## Plan de test

### Tests unitaires / intégration (backend)

- [ ] `task` crée le worktree, route les appels sur le worktree, démonte le worktree (provider bouchon qui émet un `write_file` puis finit ; vérifier `worktreeCreate`/`worktreeRemove` appelés et le `project` des appels runner = worktreePath).
- [ ] Coût de la sous-boucle imputé au tour (jetons agrégés).
- [ ] Seule la synthèse remonte (le `tool_result` de `task` = texte de synthèse, pas les fichiers).
- [ ] `not_git` → refus propre, message guidant, aucun worktree laissé.
- [ ] `unsupported_tool` (runner ancien) → refus guidant.
- [ ] Échec de la sous-boucle → worktree démonté quand même (finally).
- [ ] `task` **non déclaré** en cible SANDBOX ; déclaré en RUNNER.
- [ ] `AtelierTask` (unité) : boucle bornée, synthèse tronquée, stop respecté.

### Isolation workspace

- [ ] Applicable : la sous-boucle utilise le `(user_id, workspace_id)` du tour ; test que le `RunnerTarget` porte le workspaceId du tour et le worktreePath (pas un autre workspace).

---

## Dépendances

### Subfeatures bloquantes

- `SF-150-01` — **Done** (worktree runner + `RunnerToolGateway.worktreeCreate/Remove/Reap`).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Réutilisation, pas de moteur neuf** (Gateway-First) : `AtelierTask` reprend le patron de `AtelierExploration`, avec la panoplie complète et l'exécution routée sur le worktree.
- **Distinct d'`explore`** : `task` est **sériel** (les mutations ne se parallélisent pas), placé dans la boucle séquentielle et non dans le pré-passage parallèle d'`explore`.
- **Confirmation** : la sous-boucle passe par `executeToolOnRunner` → la porte SF-121-02 s'applique déjà (édition auto par défaut `askBeforeEdit=false`, `bash` sous porte si `agentAskBeforeBash`). La formalisation *acceptEdits-in-worktree* est **SF-150-03**.
- **Restitution** : SF-150-02 démonte le worktree en fin de tâche (nettoyage garanti). La restitution branche/diff (commit + report) est **SF-150-05**.
