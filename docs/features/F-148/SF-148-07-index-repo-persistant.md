# Mini-spec — F-148 / SF-148-07 Index de repo persistant (chemins) pour la localisation

> Base : `project-governance/templates/subfeature-template.md`

---

## Identifiant

`F-148 / SF-148-07`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-148-07-index-repo-persistant`

---

## Objectif

> En une phrase.

Persister côté gateway, par `(user_id, host_id, workspace_id)`, l'index des **chemins de fichiers**
d'un projet (rafraîchi après tour), et **servir l'outil `glob` depuis la base** quand c'est sûr, pour
supprimer des allers-retours runner « trouver le fichier ».

---

## Niveau retenu (décision explicite)

**Chemins seuls, PAS de symboles.** Le cadrage autorise ce repli : *« Si tu juges le ROI faible ou le
risque élevé sans mesure, livre une version minimale et sûre (index des chemins seulement) plutôt que
les symboles — et dis-le. »* Indexer les symboles imposerait une analyse par langage sur la gateway
(fragile, par-langage, potentiellement lourde) qui frôlerait le « moteur » proscrit par Gateway-First.
Les chemins suffisent à l'aide de localisation visée (`glob`).

---

## Comportement attendu

### Cas nominal

1. **Post-tour** : `RepoIndex.refreshAfterTurn` liste les fichiers du projet (cible RUNNER), hors
   chemin critique, throttlé (~30 s/projet), et range la liste **bornée** en base avec un compteur.
2. **Appel `glob`** : si l'index est amorcé **et** qu'aucune mutation du projet n'a eu lieu pendant
   ce tour, `glob` est **évalué depuis la base** (match du motif sur les chemins indexés) sans
   aller-retour runner. Sinon → `glob` **en direct** sur le runner (comportement actuel).
3. **Non-régression** : `grep` (recherche de contenu) reste **toujours** sur le runner (l'index ne
   porte pas le contenu). `bash` inchangé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Index non amorcé (1er tour, cache froid) | `glob` en direct (comportement actuel), refresh post-tour amorce |
| Mutation du projet pendant le tour (`write_file`/`edit_file`/`bash`) | `glob` bascule **en direct** pour le reste du tour → un fichier créé ce tour n'est jamais manqué |
| Repo trop gros (au-delà de la borne) | index **non rangé** (ligne supprimée) → `glob` toujours en direct (jamais de résultat incomplet servi) |
| Runner injoignable au refresh | rien détruit : l'ancienne copie reste (repli passant) |
| Cible `SANDBOX` | jamais d'index (les fichiers vivent dans le stockage objet) ; `glob` inchangé |
| Magasin (DB) en panne | repli passant : `glob` en direct |

---

## Critères d'acceptation

- [ ] Table `repo_index_paths` (migration `127`), unicité `(user_id, workspace_id)`, index
      `(user_id, workspace_id)`, `host_id`, `paths`, `path_count`, `observed_at`, réversible, sans FK.
- [ ] `RepoIndexStore.refresh` liste et range la liste bornée ; deux refresh < 30 s → une lecture.
- [ ] Repo au-delà de la borne → non rangé (ligne supprimée), `isPrimed` faux.
- [ ] `glob` servi depuis l'index (primed + non muté) rend les chemins qui matchent le motif, sans
      appel runner ; **mêmes chemins** qu'un `glob` runner pour un motif donné (ordre par chemin).
- [ ] Après une mutation dans le tour, `glob` repart en direct (test).
- [ ] `grep` toujours en direct (jamais servi par l'index).
- [ ] Isolation `user_id`+`workspace_id` sur toute lecture/écriture ; purge compte.
- [ ] Runner injoignable au refresh → rien détruit.

---

## Périmètre

### Hors scope (explicite)

- **Symboles** (fonctions/classes) — repli assumé (voir « Niveau retenu »).
- Recherche de **contenu** (`grep`) — reste sur le runner (l'index ne porte pas le contenu).
- Remplacement de `bash` — l'index est une aide de localisation, pas un système de fichiers.

---

## Technique

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `repo_index_paths` (nouvelle) | INSERT / SELECT / UPDATE / DELETE | une ligne par `(user_id, workspace_id)`, liste de chemins bornée |

### Migration Liquibase

- [x] Oui — `127-repo-index-paths.xml` (réversible via `createTable`).

### Composants impactés (préoccupations transversales)

- **Contexte tenant / isolation** : nouvelle table `user_id`+`host_id`+`workspace_id`. Composants :
  - `RepoIndexStore` / `RepoIndexPathRepository` (nouveaux) — toute requête filtre `(userId, workspaceId)`.
  - `AtelierChatService.executeToolOnRunner` — intercepte `glob` (fast path) quand sûr ; `runLoop`
    planifie le refresh post-tour + suit la **mutation du tour** (`mutatedTurns`, nettoyé début/fin
    de tour, comme `blanketAllowedTurns`/`machineOfTurn`).
  - `AccountService.deleteAccount` — purge `deleteByUserId`.
- **Cache de prompt (F-134)** : **non touché** — `glob` est un OUTIL (message/résultat), pas le
  préfixe. Aucune modification de `buildSystemPrompt`.
- **Auth / Principal** : inchangé. **Plans / limites** : inchangé (refresh borné/async).

---

## Plan de test

### Tests unitaires (`RepoIndexStoreTest`)

- [ ] refresh liste et range la liste bornée ; throttle 30 s ; runner injoignable → rien détruit
- [ ] repo au-delà de la borne → non rangé / ligne supprimée
- [ ] `glob` sur l'index : match d'un motif (`**/*.java`, base `path`), ordre par chemin
- [ ] lecture porte toujours `user_id`+`workspace_id` ; `null` → vide

### Tests de service (`AtelierChatServiceRepoIndexTest`)

- [ ] `glob` servi depuis l'index (primed, non muté) → aucun appel `runnerToolGateway.glob`
- [ ] après `write_file`/`bash` dans le tour → `glob` repart en direct (appel runner)
- [ ] index non amorcé → `glob` en direct
- [ ] `grep` toujours en direct

### Isolation

- [x] Applicable — l'index du workspace A n'est jamais servi au workspace B ni à un autre user.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF-148-06 mergée, indépendante — table et consommateur distincts).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Sécurité anti-résultat-faux** : l'index ne sert `glob` que lorsqu'il ne peut pas mentir — amorcé
  et aucune mutation du tour. Un fichier créé/modifié pendant le tour bascule `glob` en direct. Un
  repo trop gros n'est pas indexé du tout (jamais de liste incomplète servie).
- **Divergence d'ordre assumée** : le `glob` runner trie par date de modification (plus récent
  d'abord) ; l'index sert par **chemin** (l'index ne garde pas de date). Pour une **aide de
  localisation** (trouver un fichier par nom/extension), l'ordre n'est pas porteur de justesse ;
  documenté et accepté.
- **Aucun composant cluster** : Postgres existant + pool de threads borné.
