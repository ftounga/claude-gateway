# Mini-spec — F-31 / SF-31-08 — Commiter et pousser ses propres modifications (backend)

## Identifiant
`F-31 / SF-31-08`

## Feature parente
`F-31` — Projet adossé à un dépôt Git

## Statut
`ready`

## Date de création
2026-08-30

## Branche Git
`feat/SF-31-08-ecriture-git-commit-push`

---

## Objectif

> Permettre à l'utilisateur de publier **ses propres** modifications d'un projet Git : un commit
> atomique sur une **branche dédiée**, créé via l'API GitHub avec son jeton, sans qu'aucune session
> de bac à sable soit ouverte.

---

## Contexte

L'explorateur d'un projet `GIT` est en lecture seule (SF-31-03) et le seul chemin d'écriture passe
par l'agent (SF-31-04). Le jeton `Contents: Read and write` sert déjà à pousser — mais uniquement
depuis la sandbox. L'arbitrage du 2026-08-30 (`CADRAGE.md` §Amendement) retient d'écrire **là où
l'explorateur lit déjà** : l'API GitHub.

---

## Comportement attendu

### Cas nominal
1. `POST /workspaces/{id}/git/commit` avec : les fichiers modifiés (chemin + contenu), un message de
   commit, et une branche cible.
2. Le backend déchiffre le jeton GitHub de l'utilisateur, crée la branche si elle n'existe pas
   (depuis la branche du workspace), puis crée **un seul commit** portant tous les fichiers
   (API Git Data : blobs → arbre → commit → mise à jour de la référence).
3. La réponse porte : la branche, le SHA du commit, l'URL de comparaison, et l'URL de la PR ouverte
   s'il en existe déjà une pour cette branche.
4. Un second appel sur la même branche ajoute un commit par-dessus, sans recréer la branche.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Branche cible = branche par défaut du dépôt | `409 git_default_branch_refused` — jamais de commit direct sur `master`/`main` |
| Aucun jeton GitHub enregistré | `409 git_token_missing` (comportement existant réutilisé) |
| Jeton sans `Contents: Read and write` | `403` de GitHub remonté en `409 git_push_denied`, message explicite |
| Workspace non `GIT` | `409 git_workspace_required` — l'endpoint n'a pas de sens sur un projet d'archive |
| Workspace d'un autre utilisateur | `404` (isolation `user_id`, jamais `403`) |
| Aucun fichier fourni, ou message vide | `400 validation_error` |
| Chemin hors dépôt (`..`, absolu) | `400 validation_error`, rien n'est envoyé à GitHub |
| Fichier au-delà de la borne | `400` — même borne que la lecture (SF-31-03) |
| GitHub indisponible | `502 github_unavailable` (comportement existant réutilisé) |
| Référence modifiée entre-temps (course) | L'échec GitHub est remonté tel quel : on ne force jamais la référence |

---

## Critères d'acceptation

- [ ] Un commit **unique** porte tous les fichiers de l'appel, sur la branche demandée.
- [ ] La branche est créée depuis la branche du workspace quand elle n'existe pas ; réutilisée sinon.
- [ ] Un commit sur la **branche par défaut** est refusé (`409`), avant tout appel d'écriture à GitHub.
- [ ] L'isolation `user_id` est vérifiée avant tout accès au jeton comme au workspace.
- [ ] Le jeton n'apparaît **jamais** dans un log, une réponse ou un message d'erreur.
- [ ] La réponse porte branche, SHA, URL de comparaison, et l'URL de PR si une PR ouverte existe.
- [ ] `GitHubClient` reste une interface : l'implémentation HTTP est seule à connaître l'API GitHub.
- [ ] Suite backend verte.

---

## Périmètre

### Hors scope (explicite)
- **L'écran** : éditeur, marquage « non publié », bouton de publication → **SF-31-09**.
- **L'ouverture de la pull request** : déjà couverte par SF-31-05 (MCP) et le lien de comparaison de
  SF-31-04 en repli. Cet endpoint publie un commit, il n'ouvre pas de PR.
- **La resynchronisation du clone de l'agent** : le clone devient périmé après un commit fait ici.
  L'avertissement et la réinitialisation sont **SF-31-09** (écran). Le backend n'y touche pas.
- La suppression et le renommage de fichiers : cette subfeature crée et modifie, rien d'autre.
- Les conflits d'édition simultanée entre l'agent et l'utilisateur : hors périmètre v1, la branche
  dédiée les rend visibles à la relecture plutôt que silencieux.

---

## Technique

### Endpoint

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| POST | `/workspaces/{id}/git/commit` | JWT + accès Atelier + `requireOwned` | Publier un commit sur une branche dédiée |

### Corps de requête

| Champ | Obligatoire | Contrainte |
|---|---|---|
| `branch` | Oui | Forme de branche valide (`GitRepositoryRef.requireValidBranch`), ≠ branche par défaut |
| `message` | Oui | 1 à 500 caractères après élagage |
| `files[]` | Oui | 1 à 50 entrées |
| `files[].path` | Oui | Chemin relatif, sans `..`, sans racine absolue |
| `files[].content` | Oui | ≤ 512 Kio, texte UTF-8 |

### Composants

| Fichier | Nature |
|---|---|
| `git/GitHubClient.java` | + `createBlob`, `createTree`, `createCommit`, `updateRef`, `getRef`, `getDefaultBranch` |
| `git/HttpGitHubClient.java` | implémentation HTTP de ces appels |
| `atelier/git/GitCommitService.java` | **nouveau** — orchestration : isolation, branche, commit atomique |
| `atelier/AtelierGitController.java` | + l'endpoint |
| `atelier/dto/` | `GitCommitRequest`, `GitCommitResponse` |

### Tables impactées
Aucune. **Aucune migration Liquibase.**

---

## Plan de test

### Tests unitaires
- [ ] Refus de la branche par défaut, **avant** tout appel d'écriture (client mocké, aucune interaction).
- [ ] Chemin invalide (`..`, absolu) → rejet sans appel à GitHub.
- [ ] Branche absente → création depuis la branche du workspace ; branche présente → réutilisation.
- [ ] Ordre des appels : blobs → arbre → commit → référence.

### Tests d'intégration
- [ ] `POST` sur un workspace d'un autre utilisateur → `404`.
- [ ] `POST` sur un workspace d'archive (non `GIT`) → `409 git_workspace_required`.
- [ ] Sans jeton GitHub → `409 git_token_missing`.
- [ ] Corps invalide (aucun fichier, message vide) → `400`.
- [ ] Sans JWT → `401`.

### Isolation
- [x] Applicable : le workspace **et** le jeton sont résolus par `user_id`. Testé.

---

## Dépendances
`SF-31-01` (jeton chiffré), `SF-31-03` (lecture GitHub), `SF-31-04` (validation de branche) — toutes **done**.

## Préoccupation transversale
Aucune : ni auth, ni tenant, ni plan/limite, ni routage. Un endpoint de plus sur la chaîne
principale, gardé comme ses voisins de F-31.
