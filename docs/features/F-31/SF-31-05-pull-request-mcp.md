# Mini-spec — [F-31 / SF-05] Création de la pull request via le MCP GitHub

---

## Identifiant

`F-31 / SF-05`

## Feature parente

`F-31` — Atelier sur dépôt Git (ADR-015)

## Statut

`ready`

## Date de création

2026-08-25

## Branche Git

`feat/SF-31-05-pull-request-mcp` (back) puis `feat/SF-31-05-pull-request-mcp-front` (front)

---

## Objectif

Ouvrir la pull request de bout en bout depuis l'Atelier : l'agent appelle l'outil
`create_pull_request` du serveur **MCP GitHub**, authentifié par un **vault** de credentials, et
l'URL de la PR obtenue s'affiche dans la vue terminal.

---

## Contexte

SF-31-04 pousse le travail sur une branche dédiée et renvoie un **lien de comparaison**
(`/compare/base...branche?expand=1`) : l'utilisateur finit l'ouverture à la main sur GitHub. Le
cadrage (D1 = option C, arbitrage owner du 2026-08-25) va jusqu'à la pull request complète.

Le préalable — **OQ-11** : « un PAT fine-grained est-il accepté comme credential `static_bearer` du
serveur MCP GitHub, ou faut-il un jeton OAuth ? » — a été **tranché le 2026-08-25** par vérification
empirique : `initialize` puis `tools/list` sur `https://api.githubcopilot.com/mcp/` répondent `200`
avec un PAT en `Authorization: Bearer`, et exposent 44 outils dont `create_pull_request`. D2 reste
donc sur le **PAT chiffré** livré en SF-31-01 ; aucune bascule GitHub App n'est nécessaire.

Ce qui restait à vérifier — « le vault `static_bearer` transmet-il bien ce jeton au serveur ? » — est
une vérification d'**intégration**, faite en développant cette subfeature.

### Ce que le fournisseur impose (documentation Managed Agents, relue le 2026-08-25)

| Contrainte | Conséquence de conception |
|------------|---------------------------|
| Le serveur MCP se déclare sur l'**agent** (`mcp_servers`) ou en surcharge de session (`agent_with_overrides.mcp_servers`) | On surcharge **par session** : l'agent plateforme reste commun à tous, et un projet d'archive n'hérite d'aucun serveur MCP |
| `vault_ids` est **create-only** sur la session | Le vault est attaché **à l'ouverture** de la session, jamais après |
| Un vault porte au plus **une** credential par `mcp_server_url` | **Un vault par utilisateur** : le PAT de chacun est isolé (un vault partagé ne pourrait porter qu'un seul PAT) |
| Une surcharge `tools` **remplace en bloc** celle de l'agent | La surcharge renvoie le toolset complet **plus** l'entrée `mcp_toolset` |
| Les secrets d'un vault **n'entrent jamais dans le conteneur** (proxy côté fournisseur) | Le PAT reste inexfiltrable par l'agent, exactement comme au montage du dépôt |

---

## Comportement attendu

### Cas nominal

1. L'utilisateur a publié son travail (SF-31-04) : la branche existe, le bandeau de publication
   affiche le lien de comparaison.
2. Il clique **« Créer la pull request »** dans la vue terminal.
3. Le backend vérifie la propriété du projet, qu'il est bien adossé à un dépôt, que la branche est
   valide et **différente de la branche de base**, et qu'un jeton GitHub est enregistré.
4. Le backend **résout le vault** de l'utilisateur : s'il n'en a pas encore, il en crée un chez le
   fournisseur et y dépose le PAT déchiffré en credential `static_bearer` clé
   `https://api.githubcopilot.com/mcp/`. L'identifiant du vault (jamais le jeton) est mémorisé sur
   `user_git_credentials`.
5. Le tour est joué **dans la session existante** (jamais une session neuve : elle repartirait d'un
   clone vierge). L'instruction demande explicitement l'outil MCP `create_pull_request`, avec le
   dépôt, la base, la tête, le titre et le corps.
6. **On vérifie, on ne croit pas** : l'existence de la PR est constatée auprès de l'API GitHub
   (`GET /repos/{owner}/{repo}/pulls?head={owner}:{branche}&state=open`), pas déduite de la réponse
   de l'agent.
7. Réponse `200` : `{ branch, created, url, number, reply }`. L'URL s'affiche dans le bandeau de la
   vue terminal, à la place du lien de comparaison.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Projet d'un autre utilisateur (ou inexistant) | Projet introuvable — aucune fuite d'existence | 404 |
| Projet d'archive (source `ARCHIVE`) | « Ce projet n'est pas adossé à un dépôt Git » (`git_workspace_required`) — 409 et non 400 : la demande est légitime, c'est l'état du projet qui l'interdit (mapping existant de SF-31-04) | 409 |
| Branche absente / invalide / égale à la branche de base | Message explicite, **aucun appel** au fournisseur | 400 |
| Aucun jeton GitHub enregistré | « Ajoutez un jeton dans vos réglages » | 400 |
| Aucune session en cours | « Ouvrez d'abord une session » (`NoActiveSessionException`) | 409 |
| L'agent n'a pas créé la PR (droits insuffisants, PR déjà ouverte, outil indisponible) | `200` avec `created=false` et le compte rendu brut de l'agent — le tour a été facturé, le masquer par une erreur HTTP serait pire | 200 |
| GitHub injoignable à la vérification | `503`, et on ne prétend **pas** que la PR existe | 503 |
| Fournisseur indisponible (vault ou session) | `502` / `503` selon la traduction existante | 502/503 |

---

## Critères d'acceptation

- [x] `POST /api/workspaces/{id}/git/pull-request` crée la PR et renvoie son URL et son numéro.
- [x] La PR est **constatée** auprès de GitHub ; une déclaration non tenue de l'agent donne
      `created=false`, jamais une URL inventée.
- [x] Un vault est créé **au plus une fois par utilisateur** et réutilisé ensuite.
- [x] Le PAT n'apparaît **dans aucun journal**, dans aucune réponse d'API, et n'est jamais renvoyé
      par le fournisseur.
- [x] Retirer ou remplacer le jeton dans les réglages **détruit le vault** chez le fournisseur : la
      copie du secret ne survit pas à la révocation.
- [x] Une session Git ouverte alors qu'un jeton existe déclare le serveur MCP et attache le vault ;
      un projet d'archive n'en déclare aucun (aucune régression).
- [x] La migration 045 est **réversible** et fournie pour **PostgreSQL et H2**.
- [x] La vue terminal affiche l'URL de la PR après création, et un message honnête sinon.

---

## Périmètre

### Hors scope

- Mise à jour / fermeture / fusion d'une PR existante
- Revue de code automatisée, commentaires de PR, webhooks
- Autres serveurs MCP que GitHub (le catalogue MCP n'est pas un produit ici)
- Autres forges (GitLab, Bitbucket)
- Bascule sur GitHub App / OAuth (D2 option B) — inutile, OQ-11 tranchée

---

## Contraintes de validation

| Champ | Règle | Origine |
|-------|-------|---------|
| `branch` | obligatoire ; alphabet `[A-Za-z0-9._/-]`, 1 à 255 caractères, pas de `..`, ne commence pas par `-` ; **différente de la branche de base** | `GitRepositoryRef.requireValidBranch` (SF-31-04), réutilisé tel quel |
| `title` | facultatif ; ≤ 200 caractères ; vide ⇒ titre par défaut dérivé de la branche | tranchée ici (les titres de PR GitHub sont bornés en pratique) |
| `body` | facultatif ; ≤ 4 000 caractères ; vide ⇒ corps par défaut | tranchée ici |
| `mcp_server_url` | valeur de configuration publique, défaut `https://api.githubcopilot.com/mcp/` | vérification empirique d'OQ-11 |
| Vault | au plus **un** par utilisateur, au plus **une** credential par `mcp_server_url` | contrainte du fournisseur |

Aucune contrainte structurante ne reste indéterminée : les deux seules nouvelles (`title`, `body`)
sont tranchées ci-dessus, et le reste est hérité de SF-31-04 ou imposé par le fournisseur.

---

## Technique

### Endpoint(s)

| Méthode | Chemin | Corps | Réponse |
|---------|--------|-------|---------|
| `POST` | `/api/workspaces/{id}/git/pull-request` | `{ branch, title?, body? }` | `{ branch, created, url, number, reply }` |

### Tables impactées / Migration

`045-user-git-credentials-mcp-vault.xml` — `user_git_credentials` gagne :

| Colonne | Type | Null | Rôle |
|---------|------|------|------|
| `mcp_vault_id` | `varchar(64)` | oui | identifiant du vault du fournisseur (jamais un secret) |
| `mcp_credential_id` | `varchar(64)` | oui | identifiant de la credential déposée dans ce vault |

Deux changesets (`dbms="postgresql"` et `dbms="h2"`), chacun avec son `rollback` (`dropColumn`).
Aucun secret n'entre en base : le PAT reste chiffré dans les colonnes existantes.

### Fichiers impactés

**Backend**
- `db/changelog/migrations/045-user-git-credentials-mcp-vault.xml` *(nouveau)*
- `git/UserGitCredential.java` — deux champs
- `git/GitTokenService.java` — mémorisation du vault, publication de `GitTokenRevokedEvent`
- `git/GitTokenRevokedEvent.java` *(nouveau)*
- `git/GitProperties.java` — URL et nom du serveur MCP GitHub (valeurs publiques)
- `git/GitHubClient.java` / `HttpGitHubClient.java` — `findOpenPullRequest`
- `git/GitPullRequest.java` *(nouveau)*
- `atelier/agent/ManagedAgentProvider.java` — vault + `McpAccess` sur la création de session
- `atelier/agent/McpAccess.java` *(nouveau)*
- `atelier/agent/AnthropicManagedAgentProvider.java` — mapping `/v1/vaults`, `mcp_servers`, `mcp_toolset`
- `atelier/agent/AtelierSessionService.java` — session Git ouverte avec vault + MCP
- `atelier/git/GitVaultService.java` *(nouveau)* — cycle de vie du vault, écoute de la révocation
- `atelier/git/GitPullRequestService.java` *(nouveau)*
- `atelier/git/GitWorkspaceController.java` — nouvel endpoint
- `atelier/git/dto/CreatePullRequestRequest.java`, `PullRequestResponse.java` *(nouveaux)*
- `resources/application.yml` — `app.git.mcp-server-url`

**Frontend**
- `core/models/atelier.models.ts` — `GitPullRequestRequest`, `GitPullRequestResult`
- `core/services/atelier.service.ts` — `createPullRequest`
- `atelier/atelier.component.ts|html` — action et état
- `atelier/terminal/atelier-terminal.component.ts|html|scss` — bandeau et lien de PR

---

## Plan de test

### Tests unitaires (backend)

- `GitPullRequestService` : projet non possédé → 404 ; projet d'archive → refus ; branche vide,
  invalide, ou égale à la base → refus **sans** appel au fournisseur ; jeton absent → refus ;
  agent déclarant une PR inexistante → `created=false` ; PR constatée → URL et numéro renvoyés.
- `GitVaultService` : vault créé une seule fois puis réutilisé ; jeton absent → aucun vault ;
  révocation du jeton → vault supprimé chez le fournisseur ; échec de suppression → best-effort,
  jamais d'exception qui remonte à l'utilisateur.
- `AnthropicManagedAgentProvider` : corps de `/v1/vaults` et de `/v1/vaults/{id}/credentials`
  (`static_bearer`, `mcp_server_url`, `token`) ; création de session avec `vault_ids`,
  `mcp_servers` et `mcp_toolset` ; **sans** `McpAccess`, corps strictement identique à l'existant.
- `AtelierSessionService` : session Git avec jeton → MCP déclaré ; projet d'archive → aucun MCP.
- `GitTokenService` : le vault mémorisé est effacé et l'événement publié au remplacement et au
  retrait du jeton.

### Tests d'intégration

- `GitWorkspaceControllerTest` : `POST .../git/pull-request` — 200 nominal, 400 branche invalide,
  404 projet d'un autre utilisateur, 401 sans JWT.

### Isolation utilisateur

- Le vault est résolu **par `user_id`** : le PAT d'un utilisateur n'est jamais déposé dans le vault
  d'un autre.
- Toute lecture du projet passe par `requireOwned(userId, workspaceId)`.
- Test : utilisateur B appelle l'endpoint sur le projet de A → 404, aucun tour joué.

### Frontend

- Le bandeau affiche le bouton après une publication réussie, l'URL après création, un message
  honnête quand `created=false`.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants vérifiés |
|---------------|-----------|---------------------|
| Auth / Principal | non | aucun changement : `CurrentUser.requireId()` comme les autres endpoints de l'Atelier |
| Contexte tenant | **oui** | `GitTokenService` (findByUserId), `GitVaultService` (vault par `user_id`), `GitPullRequestService` (`requireOwned`), `AtelierSessionService.openGitSession` |
| Plans / limites | **oui** | même gate que le reste de l'Atelier (`AtelierAccessService.requireAccess()`) ; le tour est décompté par le mécanisme d'usage existant (SF-28-12) — aucun nouveau compteur |
| Navigation / routing | non | aucune route nouvelle |

---

## Dépendances

- SF-31-01 (jeton chiffré), SF-31-02 (workspace Git), SF-31-04 (branche poussée) — livrées.
- OQ-11 — **tranchée le 2026-08-25**.

---

## Notes et décisions

- **Un vault par utilisateur** : contrainte du fournisseur (une credential par `mcp_server_url` et
  par vault) autant que règle d'isolation.
- **Vault créé paresseusement** : rien n'est déposé chez le fournisseur tant que l'utilisateur ne
  travaille pas sur un dépôt Git.
- **Révocation propagée par événement** (`@TransactionalEventListener(AFTER_COMMIT)`) : le domaine
  `git` ne dépend pas du provider d'agents, et aucun vault n'est détruit pour une transaction qui
  n'a pas été validée.
- **Session ouverte avant cette version** : elle n'a pas de vault attaché (`vault_ids` est
  create-only). L'agent répondra qu'il n'a pas l'outil, `created=false`, et « Réinitialiser » rouvre
  une session équipée. On ne bascule pas de session en douce : l'utilisateur perdrait son contexte.
