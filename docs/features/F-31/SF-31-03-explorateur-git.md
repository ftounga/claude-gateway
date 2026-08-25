# Mini-spec — [F-31 / SF-03] Arborescence et explorateur d'un workspace Git

---

## Identifiant

`F-31 / SF-03`

## Feature parente

`F-31` — Atelier sur dépôt Git

## Statut

`done` — livrée le 2026-08-25 (PR #143 backend, #144 frontend)

## Date de création

2026-08-25

## Branche Git

`feat/SF-31-03-explorateur-git-backend` puis `feat/SF-31-03-explorateur-git-front`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Afficher l'arborescence et le contenu des fichiers d'un workspace `GIT` dans l'explorateur existant,
alors que ces fichiers ne sont **pas** dans le stockage objet.

---

## Contexte

SF-31-02 crée des workspaces dont les fichiers vivent dans le dépôt, pas dans S3. L'explorateur, lui,
lit le stockage : sur un workspace `GIT` il afficherait un projet **vide**, ce qui donnerait
l'impression que le clone a échoué.

Deux sources existent et aucune n'est suffisante seule :

- le **dépôt** (via l'API GitHub, avec le jeton de l'utilisateur) donne l'état de la branche ;
- le **stockage objet** contient les fichiers que la session a réécrits (resync des sorties, F-30).

L'explorateur montre donc l'**union** des deux, la version locale primant quand elle existe.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur ouvre un workspace `GIT` : l'arborescence liste les fichiers de la branche montée,
   plus les fichiers modifiés par la session.
2. Il ouvre un fichier : la version **locale** (modifiée par la session) est servie si elle existe,
   sinon le contenu de la branche.
3. Les fichiers modifiés par la session sont **signalés** dans l'explorateur.
4. L'arborescence est bornée ; au-delà, l'utilisateur est averti que la liste est tronquée.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Jeton retiré / révoqué | `400 git_token_missing` ou `400 invalid_git_token` ; l'écran explique et renvoie aux réglages |
| GitHub injoignable | `503 github_unavailable` ; l'arborescence n'est pas remplacée par une liste vide (qui ferait croire à un projet vide) |
| Fichier binaire ou trop volumineux | `400 invalid_file_path` avec un message explicite ; aucun contenu tronqué servi comme s'il était complet |
| Fichier absent de la branche et du stockage | `404 not_found` |
| Écriture / suppression / renommage / import sur un workspace `GIT` | `409 git_workspace_read_only` |
| Mode « Assistant » (tool-use sur S3) sur un workspace `GIT` | `409 git_workspace_terminal_only` |

---

## Critères d'acceptation

- [ ] L'arborescence d'un workspace `GIT` liste les fichiers de la branche (API GitHub) et ceux du stockage
- [ ] Un fichier modifié par la session est servi depuis le stockage, pas depuis la branche
- [ ] Un fichier non modifié est servi depuis la branche
- [ ] Les chemins sont dédoublonnés et triés ; l'arborescence est bornée et le troncage est signalé
- [ ] Un workspace `ARCHIVE` conserve **exactement** le comportement actuel
- [ ] Les endpoints d'écriture refusent un workspace `GIT` (`git_workspace_read_only`)
- [ ] Les endpoints de chat « Assistant » refusent un workspace `GIT` (`git_workspace_terminal_only`)
- [ ] Isolation : les appels GitHub utilisent le jeton du **propriétaire** du workspace
- [ ] Le jeton n'est ni journalisé, ni renvoyé, ni exposé dans un message d'erreur

---

## Périmètre

### Hors scope

- Édition d'un fichier d'un workspace `GIT` depuis l'explorateur (l'agent édite dans la sandbox)
- Export `.zip` d'un workspace `GIT` (la source de vérité est le dépôt)
- Diff visuel entre la branche et l'état local
- Navigation dans l'historique Git, changement de branche à chaud

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Entrées d'arborescence | `app.atelier.git.max-tree-entries`, défaut **5000** ; au-delà, liste tronquée et signalée |
| Taille d'un fichier lu | `app.atelier.git.max-file-bytes`, défaut **1 Mo** ; au-delà → refus explicite |
| Contenu binaire | Refusé (octet nul détecté), message explicite |
| Cache | Aucun : l'arborescence est relue à chaque appel (correction > économie d'appels) |

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint : `GET /workspaces/{id}`, `GET /workspaces/{id}/file` changent de **source** selon
la source du workspace. Les endpoints d'écriture gagnent un refus explicite.

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/git/GitWorkspaceService.java` | Arborescence et lecture d'un workspace `GIT` |
| `atelier/git/GitWorkspaceReadOnlyException.java`, `GitWorkspaceModeException.java` | **Nouveaux** |
| `atelier/AtelierController.java` | Aiguillage par source + refus d'écriture |
| `atelier/AtelierChatController.java` | Refus du mode Assistant sur `GIT` |
| `git/GitHubClient.java`, `HttpGitHubClient.java` | `listTree`, `readFile` |
| `atelier/dto/WorkspaceDetailResponse.java` | Source, dépôt, branche, troncage, fichiers modifiés |
| `atelier/atelier.component.*`, `files/atelier-files.component.*` | Badge Git, lecture seule, troncage |

---

## Plan de test

### Tests unitaires

- [ ] Arborescence `GIT` = union branche + stockage, triée, sans doublon
- [ ] Fichier présent dans le stockage → servi depuis le stockage (GitHub non appelé)
- [ ] Fichier absent du stockage → servi depuis la branche
- [ ] Fichier absent des deux → `not_found`
- [ ] Arborescence au-delà de la borne → tronquée, drapeau `truncated` à vrai
- [ ] Fichier binaire / trop volumineux → refus explicite
- [ ] Workspace `ARCHIVE` → aucun appel GitHub (`verifyNoInteractions`)
- [ ] Écriture / suppression / renommage / import sur `GIT` → `git_workspace_read_only`
- [ ] Chat « Assistant » sur `GIT` → `git_workspace_terminal_only`

### Tests d'intégration

- [ ] `GET /workspaces/{id}` d'un workspace `GIT` renvoie source, dépôt, branche et arborescence
- [ ] `PUT /workspaces/{id}/file` sur un workspace `GIT` → 409
- [ ] Les endpoints exigent l'authentification et l'accès Atelier

### Isolation utilisateur

- [x] **Applicable** — `requireOwned` avant toute lecture ; le jeton résolu est celui du propriétaire
  du workspace, jamais celui de l'appelant s'il diffère (ils sont identiques par construction).

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement. |
| Contexte tenant | **Oui** | Nouveau chemin de lecture. Composants vérifiés : `AtelierController` (`requireOwned` d'abord, aucun identifiant client), `GitWorkspaceService` (jeton du propriétaire), `AtelierChatController` (inchangé hormis le refus), `WorkspaceLibraryImportService` (refus sur `GIT`). |
| Plans / limites | **Non** | Aucun quota consommé : la lecture passe par l'API GitHub, pas par la sandbox. |
| Navigation / routing | **Non** | Aucun changement de route. |

---

## Dépendances

- **SF-31-02** (workspace `GIT`).

---

## Notes et décisions

- **Lire le dépôt via l'API GitHub plutôt que via la sandbox** : demander la liste des fichiers à
  l'agent coûterait du temps de sandbox facturé à chaque ouverture d'écran, et exigerait une session
  ouverte pour un simple affichage. L'API GitHub répond sans coût runtime et sans session.
- **L'explorateur d'un workspace `GIT` est en lecture seule** : écrire dans le stockage alors que
  l'agent travaille sur le clone créerait deux vérités divergentes, sans que rien ne le signale à
  l'utilisateur. Le dépôt est la source ; les modifications passent par l'agent, puis par le push.
- **Pas de cache d'arborescence** : un arbre périmé ferait ouvrir des fichiers disparus.
