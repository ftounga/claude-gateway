# Mini-spec — [F-31 / SF-01] Jeton GitHub de l'utilisateur

---

## Identifiant

`F-31 / SF-01`

## Feature parente

`F-31` — Atelier sur dépôt Git

## Statut

`done` — livrée le 2026-08-25 (PR #138 backend, #139 frontend)

## Date de création

2026-08-25

## Branche Git

`feat/SF-31-01-jeton-github-backend` (backend) puis `feat/SF-31-01-jeton-github-front` (frontend)

---

## Objectif

> En une phrase : que fait cette subfeature ?

Permettre à l'utilisateur d'enregistrer, vérifier et retirer un **jeton d'accès GitHub**, chiffré au
repos, qui servira à cloner ses dépôts dans l'Atelier.

---

## Contexte

F-31 (ADR-015) fait entrer le dépôt Git dans l'Atelier : la session monte un `github_repository` avec
un `authorization_token`. Il faut donc d'abord **détenir ce jeton**, et le détenir correctement.

Le projet sait déjà faire : F-03 (BYOK) chiffre la clé Claude de l'utilisateur par enveloppe (AWS KMS)
via `ByokKeyCipher`, ne la journalise jamais, et n'en réaffiche que les quatre derniers caractères. Ce
mécanisme est réutilisé **tel quel** ; seule la nature du secret change.

Cette subfeature ne clone rien : elle prépare le terrain de SF-31-02.

---

## Comportement attendu

### Cas nominal

1. Depuis les réglages, l'utilisateur colle un **PAT fine-grained** GitHub.
2. Le backend **vérifie le jeton** auprès de GitHub avant de l'enregistrer, et retient le nom de compte
   auquel il donne accès.
3. Le jeton est **chiffré** (`ByokKeyCipher`) puis persisté ; le clair n'est ni stocké ni journalisé.
4. L'écran affiche l'état : compte GitHub associé et jeton masqué (`ghp_…last4`).
5. L'utilisateur peut **remplacer** le jeton (même parcours) ou le **retirer** (confirmation).
6. Un utilisateur détient **au plus un** jeton GitHub.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Jeton refusé par GitHub (invalide, révoqué, expiré) | Refus **avant toute persistance**, message explicite ; l'éventuel jeton précédent reste intact |
| Jeton sans les droits attendus | Enregistré malgré tout, avec **avertissement** : les droits exacts ne se vérifient qu'à l'usage, et un jeton en lecture seule reste utile (clone) |
| GitHub injoignable | Refus temporaire explicite, aucune persistance ; l'utilisateur peut réessayer |
| Chiffrement non configuré | Même traitement que BYOK : service indisponible (503), aucun stockage en clair — **jamais** de repli non chiffré |
| Retrait d'un jeton inexistant | Sans effet, réponse identique au retrait réussi (pas de divulgation d'état) |

---

## Critères d'acceptation

- [ ] Un `POST` vérifie le jeton auprès de GitHub **avant** toute écriture, et refuse un jeton invalide
- [ ] Le jeton est chiffré via `ByokKeyCipher` ; **aucune** valeur en clair en base ni dans les logs
- [ ] La réponse d'état expose le compte GitHub et un jeton **masqué**, jamais le jeton
- [ ] Un `DELETE` retire le jeton ; un retrait sans jeton existant se comporte à l'identique
- [ ] Un utilisateur a **au plus un** jeton (contrainte d'unicité sur `user_id`)
- [ ] Isolation : un utilisateur ne peut ni lire ni retirer le jeton d'un autre
- [ ] Chiffrement indisponible → 503, **jamais** de stockage en clair
- [ ] L'écran des réglages permet d'ajouter, remplacer et retirer, avec confirmation au retrait
- [ ] La clé Claude (F-03) est **strictement inchangée** : table, endpoints et écran existants intacts

---

## Périmètre

### Hors scope

- **Clonage d'un dépôt** et création d'un workspace `GIT` → SF-31-02
- Push, branche, pull request → SF-31-04 / SF-31-05
- GitHub App / OAuth : écarté pour cette version (ADR-015 §Alternatives)
- Vérification fine des **droits** du jeton (`Contents: Read` vs `Read and write`) : l'API GitHub ne
  les expose pas de façon fiable pour un PAT fine-grained ; ils se constatent à l'usage
- Autres forges (GitLab, Bitbucket) : l'API ne monte que GitHub

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Longueur du jeton | 1 à 255 caractères, non vide après élagage |
| Vérification | `GET https://api.github.com/user`, jeton en en-tête `Authorization` ; 200 → accepté |
| Stockage | Chiffrement enveloppe (`ByokKeyCipher`), **table dédiée** |
| Affichage | Compte GitHub + 4 derniers caractères uniquement |
| Unicité | Un jeton par `user_id` |
| Migration | `042-user-git-credentials.xml`, réversible, Postgres **et** H2 |

---

## Technique

### Endpoint(s)

| Méthode | Chemin | Rôle |
|---------|--------|------|
| `GET` | `/user/git-token` | État : présence, compte GitHub, jeton masqué |
| `POST` | `/user/git-token` | Vérifie puis enregistre (ou remplace) le jeton |
| `DELETE` | `/user/git-token` | Retire le jeton (204) |

Chemins alignés sur `/user/api-key` (F-03) pour rester lisibles.

### Tables impactées / Migration

**Nouvelle table** `user_git_credentials` : `id`, `user_id` (unique), `github_login`,
`encrypted_data_key`, `cipher_iv`, `ciphertext`, `token_last4`, `created_at`, `updated_at`.

> **Table dédiée, pas d'extension de `user_api_keys`.** Cette table porte la clé Claude, en production,
> et son unicité est posée sur `user_id` seul : y loger un second secret imposerait de modifier une
> contrainte vivante sur la fonctionnalité la plus critique du produit. Le **chiffrement** est réutilisé,
> pas le stockage.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `db/changelog/migrations/042-user-git-credentials.xml` | Migration (Postgres + H2) |
| `git/UserGitCredential.java`, `UserGitCredentialRepository.java` | **Nouveaux** |
| `git/GitTokenService.java` | **Nouveau** — vérification, chiffrement, état, retrait |
| `git/GitTokenController.java` | **Nouveau** — les trois endpoints |
| `git/GitHubClient.java` | **Nouveau** — vérification du jeton (`GET /user`) |
| `git/dto/*` | **Nouveaux** — requête et réponse d'état |
| `settings/settings.component.*` | Section « Compte GitHub » |
| `core/services/git-token.service.ts` | **Nouveau** — appels API |

---

## Plan de test

### Tests unitaires

- [ ] Jeton valide → vérifié, chiffré, persisté ; le clair n'apparaît **nulle part**
- [ ] Jeton refusé par GitHub → exception, **aucune écriture** (`verifyNoInteractions` sur le dépôt)
- [ ] GitHub injoignable → erreur temporaire, aucune écriture
- [ ] Remplacement : un second enregistrement **écrase** le premier, toujours une seule ligne
- [ ] Chiffrement indisponible → 503, aucune écriture
- [ ] État sans jeton → réponse « absent », sans erreur
- [ ] État avec jeton → compte + `last4`, **jamais** le jeton ni le chiffré
- [ ] Retrait sans jeton existant → même réponse qu'un retrait réussi
- [ ] Frontend : ajout, remplacement, retrait (avec confirmation), messages d'erreur

### Tests d'intégration

- [ ] Les trois endpoints exigent l'authentification (401 sans jeton applicatif)
- [ ] Cycle complet `POST` → `GET` → `DELETE` → `GET`
- [ ] La réponse JSON ne contient **jamais** le jeton en clair (vérification explicite du corps)
- [ ] Migration 042 appliquée sur base propre (H2)

### Isolation utilisateur

- [x] **Applicable** — le jeton de B n'est ni lisible ni supprimable par A : `GET` de A après
  enregistrement par B renvoie « absent », et le `DELETE` de A laisse celui de B intact. Tout accès
  passe par le `user_id` du JWT, jamais par un identifiant fourni par le client.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement d'authentification ; les endpoints suivent le patron de `/user/api-key` et résolvent l'utilisateur via `CurrentUser`. |
| Contexte tenant | **Oui** | Nouveau secret rattaché à l'utilisateur. Composants vérifiés : `GitTokenService` (accès par `user_id` du JWT uniquement), `UserGitCredentialRepository` (lecture/suppression **par `user_id`**, jamais par `id` seul), `GitTokenController` (aucun identifiant accepté du client), `AccountService` (suppression de compte RGPD — **doit** effacer aussi ce jeton : vérifié et complété). Aucun autre composant n'accède à cette table. |
| Plans / limites | **Non** | Aucun quota consommé ; détenir un jeton n'engage aucun coût fournisseur. |
| Navigation / routing | **Non** | Section ajoutée dans un écran existant ; aucune route. |

---

## Dépendances

- Aucune bloquante. **SF-31-02** (clonage) dépend de celle-ci.

---

## Notes et décisions

- **Vérifier avant d'enregistrer** : un jeton invalide stocké ne se manifesterait qu'au premier clone,
  loin du geste qui l'a causé. La vérification coûte un appel et supprime cette classe d'erreur.
- **Ne pas prétendre vérifier les droits** : l'API GitHub n'expose pas de façon fiable les permissions
  d'un PAT fine-grained. Annoncer « jeton en écriture validé » serait une promesse intenable ; on
  avertit, et les droits se constatent à l'usage (SF-31-04).
- **Table dédiée** : le chiffrement de F-03 est réutilisé, sa table ne l'est pas — modifier une
  contrainte d'unicité vivante sur le stockage de la clé Claude serait un risque sans contrepartie.
- **Suppression de compte** : le jeton doit disparaître avec le compte. C'est une obligation RGPD déjà
  outillée (F-11) ; l'oublier créerait un secret orphelin.
