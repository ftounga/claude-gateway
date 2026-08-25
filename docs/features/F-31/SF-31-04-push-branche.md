# Mini-spec — [F-31 / SF-04] Push du travail sur une branche dédiée

---

## Identifiant

`F-31 / SF-04`

## Feature parente

`F-31` — Atelier sur dépôt Git

## Statut

`ready`

## Date de création

2026-08-25

## Branche Git

`feat/SF-31-04-push-branche-backend` puis `feat/SF-31-04-push-branche-front`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Publier le travail de la session sur une **branche dédiée** du dépôt et donner à l'utilisateur le lien
de comparaison GitHub pour ouvrir sa pull request.

---

## Contexte

SF-31-02/03 permettent de travailler sur un dépôt réel. Sans push, le travail reste dans la sandbox et
disparaît à la réinitialisation : c'est exactement l'export/réimport manuel que F-31 supprime.

Le push est réalisé **dans la sandbox, par l'agent**, via le proxy git du fournisseur : c'est le seul
chemin où le jeton n'entre pas dans le conteneur (ADR-015). Le backend n'écrit rien dans le dépôt
lui-même — il pilote, puis **vérifie**.

---

## Comportement attendu

### Cas nominal

1. Depuis l'Atelier, l'utilisateur demande « Pousser une branche » ; il peut nommer la branche, sinon
   un nom est proposé (`claude/atelier-<horodatage>`).
2. Le backend envoie à la session en cours une instruction de publication (créer la branche, indexer,
   commiter, pousser) et attend la fin du tour.
3. Le backend **vérifie auprès de GitHub que la branche existe réellement** — ce que l'agent déclare
   ne suffit pas.
4. La réponse porte la branche, l'état `pushed`, et l'**URL de comparaison**
   `https://github.com/{owner}/{repo}/compare/{base}...{branche}?expand=1`.
5. L'utilisateur ouvre la PR depuis ce lien (la création automatique relève de SF-31-05).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Workspace `ARCHIVE` | `409 git_workspace_required` |
| Aucune session en cours | `409 no_active_session` — rien n'a été fait dans la sandbox, il n'y a rien à pousser |
| Branche demandée = branche de base | `400 invalid_git_branch` — **jamais** de push sur la branche par défaut |
| Nom de branche invalide | `400 invalid_git_branch` |
| Jeton absent / révoqué | `400 git_token_missing` / `400 invalid_git_token` |
| Le push n'a pas eu lieu (droits en lecture seule, rien à commiter, échec) | `200` avec `pushed=false`, sans URL de comparaison, et le compte rendu de l'agent — l'utilisateur voit **pourquoi** |
| GitHub injoignable à la vérification | `503 github_unavailable` — on ne prétend pas que la branche existe |
| Session expirée pendant le run | `409 no_active_session` après nettoyage ; aucune session neuve n'est ouverte pour un push (elle repartirait d'un clone vierge) |

---

## Critères d'acceptation

- [ ] `POST /workspaces/{id}/git/push` publie sur une branche **dédiée**, jamais sur la branche de base
- [ ] Le nom de branche par défaut est généré et valide ; un nom fourni est validé
- [ ] La réussite est **vérifiée auprès de GitHub**, pas déduite de la réponse de l'agent
- [ ] `pushed=false` est renvoyé, sans URL, quand la branche n'existe pas après le tour
- [ ] Aucune session en cours ⇒ refus, et **aucune session n'est ouverte** (aucun coût engagé)
- [ ] Le jeton n'est ni journalisé, ni renvoyé, ni transmis dans l'instruction envoyée à l'agent
- [ ] Isolation : `requireOwned` d'abord ; le jeton est celui du propriétaire du workspace
- [ ] L'usage du tour est décompté comme tout autre tour (quota + plafond sandbox)
- [ ] L'écran affiche la branche, le lien de comparaison, et le compte rendu en cas d'échec

---

## Périmètre

### Hors scope

- **Création de la pull request** → SF-31-05 (subordonnée à la levée du risque MCP, ADR-015)
- Résolution de conflits, rebase, force-push, suppression de branche
- Choix des fichiers à commiter (le tour publie l'état du clone)

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Nom de branche | 1 à 255 caractères, `[A-Za-z0-9._/-]`, ne commence ni par `-` ni par `/`, pas de `..`, différent de la branche de base |
| Message de commit | Optionnel, 1 à 500 caractères ; défaut « Travaux de l'Atelier Claude Gateway » |
| Vérification | `GET /repos/{owner}/{repo}/branches/{branch}` — 200 ⇒ poussée, 404 ⇒ non poussée |
| Coût | Un push = **un tour** de session, décompté comme un message |

---

## Technique

### Endpoint(s)

| Méthode | Chemin | Rôle |
|---------|--------|------|
| `POST` | `/workspaces/{id}/git/push` | Publie le travail sur une branche dédiée |

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/git/GitPushService.java` | **Nouveau** — orchestration du push |
| `atelier/git/dto/GitPushRequest.java`, `GitPushResponse.java` | **Nouveaux** |
| `atelier/git/GitWorkspaceController.java` | Endpoint de push |
| `atelier/agent/AtelierSessionService.java` | `runInExistingSession` (aucune ouverture implicite) |
| `git/GitHubClient.java`, `HttpGitHubClient.java` | `branchExists` |
| `atelier/terminal/*`, `atelier.component.*` | Bouton « Pousser une branche » + dialogue + lien |

---

## Plan de test

### Tests unitaires

- [ ] Workspace `ARCHIVE` → `git_workspace_required`, aucun appel provider
- [ ] Aucune session → `no_active_session`, **aucune** session créée (`verifyNoInteractions`)
- [ ] Branche = branche de base, nom invalide, `..` → `invalid_git_branch` avant tout appel
- [ ] Nom par défaut généré → valide, préfixé `claude/atelier-`
- [ ] Branche existante après le tour → `pushed=true` + URL de comparaison correcte
- [ ] Branche absente après le tour → `pushed=false`, pas d'URL, compte rendu conservé
- [ ] GitHub injoignable à la vérification → `github_unavailable`
- [ ] L'instruction envoyée à l'agent **ne contient pas** le jeton
- [ ] Isolation : push sur le workspace d'un autre utilisateur → 404

### Tests d'intégration

- [ ] L'endpoint exige l'authentification (401) et l'accès Atelier (403 non-Gold)
- [ ] Réponse JSON conforme (branche, `pushed`, `compareUrl`, `reply`), sans jeton

### Isolation utilisateur

- [x] **Applicable** — `requireOwned` avant tout ; aucun identifiant de workspace ni de jeton n'est
  accepté du client ; le jeton est résolu par le `user_id` propriétaire.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement. |
| Contexte tenant | **Oui** | Nouvel endpoint agissant sur un dépôt externe. Composants vérifiés : `GitWorkspaceController` (`CurrentUser` seul), `GitPushService` (`requireOwned` puis jeton du propriétaire), `AtelierSessionService` (session résolue depuis le workspace possédé). |
| Plans / limites | **Oui** | Le push consomme un tour de sandbox : le pré-vol quota/plafond de `AtelierSessionService` s'applique, et l'usage est décompté par le même chemin que les autres tours. Gating Gold appliqué. |
| Navigation / routing | **Non** | Bouton dans un écran existant. |

---

## Dépendances

- **SF-31-02** (workspace `GIT`), **SF-31-03** (contexte d'écran).

---

## Notes et décisions

- **Le push est fait par l'agent, pas par le backend** : c'est le seul chemin où le jeton reste hors
  du conteneur (proxy git). Reconstruire un commit via l'API REST (blobs/trees/commits/refs) serait
  réimplémenter côté Gateway ce que le fournisseur fait déjà — contraire à Provider-First.
- **On vérifie, on ne croit pas** : un agent peut répondre « poussé » sans l'avoir fait (droits en
  lecture seule notamment). L'existence de la branche est constatée auprès de GitHub avant d'annoncer
  quoi que ce soit — c'est aussi le premier retour réel sur les droits du PAT (SF-31-01).
- **Jamais de session ouverte pour un push** : une session neuve repartirait d'un clone vierge et
  pousserait une branche identique à la base — un succès trompeur.
- **Échec de push = `200 pushed=false`** et non une erreur HTTP : le tour a bien eu lieu et a été
  facturé, son compte rendu est l'information utile. Une 5xx masquerait ce compte rendu.
