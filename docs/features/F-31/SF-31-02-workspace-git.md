# Mini-spec — [F-31 / SF-02] Workspace depuis un dépôt Git

---

## Identifiant

`F-31 / SF-02`

## Feature parente

`F-31` — Atelier sur dépôt Git

## Statut

`done` — livrée le 2026-08-25 (PR #141 backend, #142 frontend)

## Date de création

2026-08-25

## Branche Git

`feat/SF-31-02-workspace-git-backend` puis `feat/SF-31-02-workspace-git-front`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Créer un workspace d'Atelier **depuis l'URL d'un dépôt GitHub** : le dépôt est cloné dans la sandbox
par le fournisseur (`github_repository`) au lieu d'être téléversé en `.zip`.

---

## Contexte

SF-31-01 a mis en place le jeton GitHub chiffré. L'API Managed Agents monte nativement un dépôt
(`resources: [{type: "github_repository", url, authorization_token, mount_path, checkout}]`) et le
jeton **n'entre jamais dans le sandbox** (proxy git côté Anthropic, ADR-015).

Le workspace gagne donc une **source** : `ARCHIVE` (existant, inchangé) ou `GIT` (nouveau, D3 du
cadrage). Tout le reste — session persistante, mode Terminal, historique, décompte d'usage — est
réutilisé sans modification.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur colle l'URL d'un dépôt GitHub (`https://github.com/owner/repo`) et, optionnellement,
   une branche.
2. Le backend **valide l'URL**, puis **vérifie l'accès au dépôt** avec le jeton de l'utilisateur
   (`GET /repos/{owner}/{repo}`) et retient la **branche par défaut** si aucune n'est fournie.
3. Un workspace de source `GIT` est créé : URL, propriétaire, dépôt, branche. **Aucun fichier n'est
   copié** dans le stockage objet — le dépôt vit chez le fournisseur.
4. Au premier message, la session monte le dépôt sur `/workspace` (`checkout: {type: branch, name}`),
   avec le jeton déchiffré à la volée. Le plafond `maxSessionFiles` (300) **ne s'applique pas**.
5. Réinitialiser la sandbox (SF-30-06) referme la session ; la suivante **re-clone** le dépôt à jour.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun jeton GitHub enregistré | `400 git_token_missing`, aucun workspace créé, message orientant vers les réglages |
| URL non GitHub / malformée | `400 invalid_git_repository`, aucun appel réseau |
| Dépôt inexistant ou hors de portée du jeton | `400 invalid_git_repository` (GitHub ne distingue pas les deux — 404) |
| Jeton révoqué entre-temps | `400 invalid_git_token` |
| GitHub injoignable | `503 github_unavailable`, aucun workspace créé |
| Jeton retiré alors qu'un workspace `GIT` existe (OQ-B) | Le message suivant échoue en `400 git_token_missing`, message explicite ; le workspace reste, il redevient utilisable dès qu'un jeton est réenregistré |

---

## Critères d'acceptation

- [ ] `POST /workspaces/git` crée un workspace de source `GIT` avec URL, owner, repo et branche
- [ ] La branche par défaut du dépôt est utilisée quand aucune branche n'est fournie
- [ ] Sans jeton enregistré, la création est refusée **avant tout appel réseau** (`git_token_missing`)
- [ ] Une URL non GitHub est refusée sans appel réseau
- [ ] La session d'un workspace `GIT` monte un `github_repository` (et **aucun** fichier téléversé)
- [ ] Le jeton est déchiffré à la volée, **jamais journalisé**, jamais renvoyé par l'API
- [ ] Un workspace `ARCHIVE` conserve **exactement** le comportement actuel (aucune régression)
- [ ] Isolation : un workspace `GIT` n'est ni lisible ni utilisable par un autre utilisateur
- [ ] Le jeton utilisé au montage est celui du **propriétaire du workspace**, jamais un autre

---

## Périmètre

### Hors scope

- Arborescence / lecture des fichiers du dépôt → **SF-31-03**
- Push d'une branche → **SF-31-04** ; création de PR → **SF-31-05**
- Autres forges (GitLab, Bitbucket) : l'API ne monte que GitHub
- `git pull` automatique en cours de session (voir Notes, OQ-A)

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| URL de dépôt | `https://github.com/<owner>/<repo>` (suffixe `.git` toléré), `owner`/`repo` ∈ `[A-Za-z0-9._-]{1,100}`, 1 à 500 caractères |
| Branche | 1 à 255 caractères, `[A-Za-z0-9._/-]`, ne commence ni par `-` ni par `/` ; optionnelle |
| Nom du workspace | Optionnel ; défaut = nom du dépôt |
| Source | Enum `ARCHIVE` \| `GIT`, `ARCHIVE` par défaut (données existantes) |
| Migration | `043-workspaces-git-source.xml`, réversible, Postgres **et** H2 |

---

## Technique

### Endpoint(s)

| Méthode | Chemin | Rôle |
|---------|--------|------|
| `POST` | `/workspaces/git` | Crée un workspace depuis une URL de dépôt |

### Tables impactées / Migration

`workspaces` : ajout de `source` (varchar 16, défaut `ARCHIVE`, non nul), `git_repo_url` (varchar 500),
`git_owner` (varchar 100), `git_repo` (varchar 100), `git_branch` (varchar 255). Colonnes nullables ou
à valeur par défaut : aucune donnée existante cassée. Rollback = `dropColumn`.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `db/changelog/migrations/043-workspaces-git-source.xml` | Migration |
| `atelier/WorkspaceSource.java` | **Nouveau** — enum |
| `atelier/git/GitRepositoryRef.java` | **Nouveau** — parsing/validation d'URL |
| `atelier/git/GitWorkspaceService.java` | **Nouveau** — création d'un workspace `GIT` |
| `atelier/git/GitWorkspaceController.java` | **Nouveau** — `POST /workspaces/git` |
| `atelier/Workspace.java`, `WorkspaceService.java` | Source + création sans archive |
| `atelier/agent/RepositoryMount.java` | **Nouveau** — montage de dépôt |
| `atelier/agent/ManagedAgentProvider.java`, `AnthropicManagedAgentProvider.java` | `createSession` avec dépôt |
| `atelier/agent/AtelierSessionService.java` | Montage `GIT` au lieu du téléversement |
| `git/GitHubClient.java`, `HttpGitHubClient.java` | `getRepository` |
| `core/services/atelier.service.ts`, `atelier.component.*` | Bouton « Depuis GitHub » + dialogue |

---

## Plan de test

### Tests unitaires

- [ ] URL valide → owner/repo extraits ; URL non GitHub, chemin incomplet, schéma `http` → refus
- [ ] Sans jeton → `git_token_missing`, **aucun** appel `GitHubClient` (`verifyNoInteractions`)
- [ ] Dépôt inaccessible (404) → `invalid_git_repository`, aucun workspace créé
- [ ] Branche absente → branche par défaut du dépôt retenue
- [ ] Ouverture de session `GIT` → `createSession` reçoit un `RepositoryMount` et **zéro** `FileMount`
- [ ] Ouverture de session `GIT` sans jeton → `git_token_missing`, aucune session créée
- [ ] Ouverture de session `ARCHIVE` → comportement inchangé (fichiers téléversés)
- [ ] Le jeton n'apparaît dans aucun log (message d'erreur neutre)

### Tests d'intégration

- [ ] `POST /workspaces/git` exige l'authentification (401 sans JWT) et l'accès Atelier (403 non-Gold)
- [ ] Cycle : création → `GET /workspaces` → le workspace apparaît avec sa source
- [ ] Migration 043 appliquée sur base propre (H2)

### Isolation utilisateur

- [x] **Applicable** — le workspace est créé avec le `user_id` du JWT ; `requireOwned` couvre lecture,
  suppression et session. Le jeton monté est résolu par le `user_id` **propriétaire du workspace**.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement ; `CurrentUser` comme les autres endpoints Atelier. |
| Contexte tenant | **Oui** | Nouveau chemin de création de workspace. Composants vérifiés : `GitWorkspaceController` (aucun identifiant client), `GitWorkspaceService` (`user_id` du JWT), `WorkspaceService.createFromGit` (`user_id` obligatoire), `AtelierSessionService.openSession` (résout le jeton du **propriétaire**, `requireOwned` d'abord), `AtelierController`/`AtelierChatController` (inchangés, `requireOwned`). |
| Plans / limites | **Oui** | Gating Gold `AtelierAccessService.requireAccess()` appliqué au nouvel endpoint, comme aux autres. Quota et plafond sandbox inchangés (pré-vol dans `AtelierSessionService`). `maxSessionFiles` ne s'applique plus aux workspaces `GIT` (aucun fichier téléversé). |
| Navigation / routing | **Non** | Aucun nouvel écran ; un dialogue dans l'écran Atelier existant. |

---

## Dépendances

- **SF-31-01** (jeton GitHub) — livrée.

---

## Notes et décisions

- **OQ-A (rafraîchissement du clone)** — tranchée pour cette version : le clone est fait **à
  l'ouverture de la session** ; il n'y a pas de `git pull` automatique en cours de session. Le geste
  qui rafraîchit existe déjà et est explicite : « Réinitialiser la sandbox » (SF-30-06). Un `pull`
  automatique écraserait silencieusement le travail en cours dans la sandbox.
- **OQ-B (jeton expiré)** — tranchée : le workspace **survit** au retrait du jeton ; c'est le montage
  de session qui échoue, avec un message explicite. Supprimer le workspace serait destructeur pour une
  cause temporaire.
- **Pas de `CLAUDE.md` semé** sur un workspace `GIT` : le dépôt appartient à l'utilisateur, y injecter
  un fichier serait une modification non demandée qui finirait dans une PR.
