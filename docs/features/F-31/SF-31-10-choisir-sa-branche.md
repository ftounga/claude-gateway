# Mini-spec — F-31 / SF-31-10 — Choisir et créer sa branche depuis l'explorateur

## Identifiant
`F-31 / SF-31-10`

## Feature parente
`F-31` — Projet adossé à un dépôt Git

## Statut
`ready`

## Date de création
2026-08-31

## Branche Git
`feat/SF-31-10-branches-explorateur`

---

## Objectif

> Voir sur quelle branche on travaille, **en changer**, et en **créer** une — depuis l'explorateur,
> à tout moment, et pas seulement au moment de publier.

---

## Le besoin

Le terminal fait ce qu'on lui demande : Claude change de branche, en crée, commite. L'explorateur,
lui, est muet : il affiche une branche figée, celle montée à la création du projet. On ne peut ni
voir ce qu'on vient de publier, ni préparer une branche à l'avance.

Conséquence directe de SF-31-09 : après avoir publié sur `claude/edition-…`, **aucun écran ne montre
ce travail**. Il faut aller sur GitHub.

---

## Comportement attendu

### Cas nominal
1. L'explorateur affiche la branche courante et permet d'en **choisir une autre** parmi celles du dépôt.
2. Changer de branche recharge l'arborescence et les fichiers **depuis cette branche**, et devient la
   branche du projet : la **prochaine** session Claude la montera.
3. **Créer une branche** depuis la branche courante, sans commit, et s'y placer.
4. Quand une session est ouverte, un avertissement dit qu'elle travaille **toujours sur l'ancienne
   branche** et qu'une réinitialisation est nécessaire pour l'y amener. Ici ce conseil est **exact** :
   la session monte `workspace.gitBranch` à son ouverture.
5. Publier (SF-31-09) envoie désormais sur la branche courante quand elle n'est pas celle par défaut.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Branche inexistante | `409 git_branch_unknown` — jamais de projet pointant dans le vide |
| Nom de branche invalide | `400 validation_error` (mêmes règles que SF-31-04) |
| Branche à créer déjà présente | `409 git_branch_exists`, sans rien écraser |
| Changement demandé sur un projet d'archive | `409 git_workspace_required` |
| Projet d'un autre utilisateur | `404` (isolation) |
| GitHub indisponible | `502 github_unavailable` ; la branche du projet **reste inchangée** |
| Modifications non publiées en attente | L'écran demande confirmation avant de changer de branche : elles seraient perdues |

---

## Correction incluse — le refus de publication portait sur la mauvaise branche

SF-31-08 refuse de commiter sur `workspace.gitBranch`. C'était juste tant que le projet suivait
forcément la branche par défaut. Dès qu'il peut suivre une branche de travail, ce refus **empêche de
commiter sur sa propre branche** — exactement ce qu'on veut faire.

Le refus porte désormais sur la **branche par défaut du dépôt**, obtenue auprès de GitHub. Commiter
sur `master` reste interdit ; commiter sur `claude/edition-…` devient possible.

---

## Critères d'acceptation

- [ ] `GET /workspaces/{id}/git/branches` liste les branches du dépôt et désigne la branche par défaut.
- [ ] `PUT /workspaces/{id}/git/branch` change la branche du projet, après avoir vérifié qu'elle existe.
- [ ] `POST /workspaces/{id}/git/branches` crée une branche depuis la branche courante et s'y place.
- [ ] Après changement, l'arborescence et la lecture de fichiers viennent de la nouvelle branche.
- [ ] La publication refuse la branche **par défaut du dépôt**, plus celle du projet.
- [ ] Isolation `user_id` sur les trois endpoints.
- [ ] Une branche inexistante ou un nom invalide ne modifie **jamais** le projet.
- [ ] Suite backend verte.

---

## Périmètre

### Hors scope (explicite)
- **La bascule de la session en cours** : changer de branche ne réinitialise pas la session — ce
  serait détruire son environnement sans le demander. L'écran avertit, l'utilisateur décide.
- La suppression de branche, la fusion, la résolution de conflits : GitHub le fait déjà bien.
- L'écran lui-même (sélecteur, création, confirmation) → **SF-31-11**.

---

## Technique

| Méthode | URL | Rôle |
|---------|-----|------|
| GET | `/workspaces/{id}/git/branches` | Lister les branches + la branche par défaut |
| PUT | `/workspaces/{id}/git/branch` | Changer la branche du projet |
| POST | `/workspaces/{id}/git/branches` | Créer une branche depuis la courante et s'y placer |

| Fichier | Nature |
|---|---|
| `git/GitHubClient.java` | + `listBranches`, `createBranch` |
| `git/HttpGitHubClient.java` | implémentations |
| `atelier/git/GitBranchService.java` | **nouveau** — isolation, validation, changement |
| `atelier/git/GitCommitService.java` | refus porté sur la branche **par défaut du dépôt** |
| `atelier/git/GitWorkspaceController.java` | les trois endpoints |

### Migration
- [x] Non applicable — `workspaces.git_branch` existe déjà.

---

## Plan de test

### Tests unitaires
- [ ] Changement vers une branche existante → `git_branch` mis à jour.
- [ ] Branche inexistante → exception, projet **inchangé**.
- [ ] Création d'une branche déjà présente → exception, aucune écriture.
- [ ] Publication sur la branche du projet (non par défaut) → **autorisée**.
- [ ] Publication sur la branche par défaut → refusée.

### Tests d'intégration
- [ ] Les trois endpoints sur un projet d'autrui → `404`.
- [ ] Sur un projet d'archive → `409 git_workspace_required`.
- [ ] Liste des branches → la branche par défaut est signalée.

### Isolation
- [x] Applicable et testée sur les trois endpoints.

---

## Dépendances
`SF-31-08` (publication), `SF-31-09` (écran d'édition) — **done**.

## Préoccupation transversale
Aucune : ni auth, ni tenant, ni plan/limite. Le routage n'est pas touché ; trois endpoints s'ajoutent
à un contrôleur existant, gardés comme leurs voisins.
