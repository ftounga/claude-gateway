# Mini-spec — F-38 / SF-38-15 — Un projet qui vit déjà sur la machine

## Identifiant

`F-38 / SF-38-15`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`livrée` — PR #238, mergée le 2026-09-06

## Date de création

2026-09-06

## Branche Git

`feat/SF-38-15-source-locale`

---

## Objectif

> Permettre de créer un projet d'Atelier en désignant un dossier **qui existe déjà sur la machine**,
> sans archive `.zip` ni dépôt GitHub — les deux seules sources existantes, dont aucune n'a de sens
> quand le projet est déjà là.

---

## Déclencheur

Constat du banc d'essai (`docs/features/F-38/BANC-ESSAI-RUNNER.md`, §3.1) : pour travailler sur
`~/dev/runner-claude` avec le runner, il fallait **importer une archive dont on n'a que faire**, puis
basculer la cible d'exécution. Un détour absurde — le dossier existe, il est même vide et attend
d'être rempli.

Décision du product owner (2026-09-06) : *« Je ne vais pas importer de zip. Le dossier doit déjà
exister sur ta machine si tu veux taffer avec le runner. »*

---

## Comportement attendu

### Cas nominal

1. **Créer** — l'utilisateur choisit « sur ma machine » et donne un **nom de projet**. Rien d'autre.
   Aucun chemin n'est demandé, aucun fichier n'est téléversé.
2. Le workspace est créé avec `source = LOCAL` et **`executionTarget = RUNNER` d'emblée** : un projet
   local sans runner n'aurait aucun endroit où exister.
3. **Appairer** — la gateway génère un code (mécanique inchangée, SF-38-01) et affiche la commande
   prête à copier :
   `java -jar claude-runner.jar --gateway … --workspace <votre dossier> --code <CODE>`
4. **C'est le runner qui déclare la racine.** Il transmet, à l'appairage, le **nom du dossier**
   (`rootName`, le dernier segment seulement) en plus de son `label`. Le chemin absolu de la machine
   **n'est jamais transmis**, conformément au principe du `PathGuard` — dont les messages d'erreur
   ne citent déjà que des chemins relatifs.
5. L'écran affiche alors : *« runner-claude — sur poste-dev »*, et le terminal est utilisable.

### Pourquoi le navigateur ne choisit pas le dossier

Un navigateur ne peut pas transmettre un chemin absolu du disque, et il ne le doit pas : la gateway
n'a aucune raison de connaître l'arborescence de la machine de l'utilisateur. Le seul composant qui
connaît le chemin est celui qui s'exécute dessus — le runner. **Le dossier se désigne donc au
lancement du runner** (`--workspace`), pas dans un sélecteur de fichiers.

### Ce qu'un projet `LOCAL` refuse

| Geste | Réponse | Pourquoi |
|---|---|---|
| Importer une archive / ajouter un fichier depuis l'écran | `409 local_workspace_no_upload` | Les fichiers vivent sur la machine ; les écrire dans le stockage créerait une seconde vérité |
| Basculer la cible sur `SANDBOX` | `409 local_workspace_requires_runner` | Le bac à sable n'a pas ce dossier ; la session s'ouvrirait vide |
| Opérations Git de F-31 (commit, PR, branches) | `409 local_workspace_no_git_api` | Le dépôt est sur la machine : `git` s'y utilise par `bash`, sans jeton ni API |

Ces trois refus sont rendus **avant tout appel réseau ou fournisseur**.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Nom de projet vide ou > 255 caractères | Message explicite | 400 |
| Création demandée par un utilisateur sans accès Gold | Refus (gating inchangé) | 403 |
| Message envoyé dans un projet `LOCAL` sans runner connecté | « Aucune machine connectée : lancez le runner » — jamais une erreur technique | 409 |
| `rootName` absent de l'appairage (runner antérieur) | Le projet fonctionne ; l'écran affiche le seul `label` | 200 |
| `rootName` contenant un séparateur de chemin | Réduit à son dernier segment, borné à 255 caractères | 200 |

---

## Critères d'acceptation

- [ ] `POST /api/workspaces/local` crée un workspace `source = LOCAL`, `executionTarget = RUNNER`,
      sans aucun fichier ni préfixe de stockage alloué.
- [ ] La création n'accepte **que** le nom ; aucun chemin n'est transmis par le client.
- [ ] L'appairage accepte un `rootName` optionnel, réduit à son dernier segment et borné.
- [ ] Aucun chemin absolu de la machine n'est stocké ni journalisé (vérifié par test).
- [ ] Un projet `LOCAL` refuse l'import de fichiers, la bascule `SANDBOX` et les opérations Git de
      F-31, chacun avec son code d'erreur, **avant tout appel réseau**.
- [ ] Un message envoyé sans runner connecté rend un refus lisible, pas une trace technique.
- [ ] Isolation : `requireOwned` reste le premier geste de chaque endpoint touché ; un projet `LOCAL`
      d'un autre utilisateur est en 404.
- [ ] La suppression d'un projet `LOCAL` purge ses données (jetons, codes, audit) comme SF-38-14 —
      et ne tente pas de supprimer des fichiers dans le stockage, où il n'y en a jamais eu.
- [ ] Aucune régression sur les projets `ARCHIVE` et `GIT`.

---

## Périmètre

### Hors scope (explicite)

- **SF-38-16** — l'écran de création « sur ma machine ». *(subfeature frontend obligatoire, planifiée
  ici même : la règle interdit de merger un backend sans son écran planifié)*
- **SF-38-17** — l'explorateur de fichiers en mode runner.
- Toute synchronisation des fichiers de la machine vers le stockage objet — **écartée**, voir §Notes.
- Le choix du dossier depuis le navigateur — impossible et non souhaitable, voir ci-dessus.

---

## Valeurs initiales

| Champ | Valeur à la création | Règle |
|-------|---------------------|-------|
| `source` | `LOCAL` | imposée par l'endpoint |
| `execution_target` | `RUNNER` | imposée : un projet local sans runner n'a pas de lieu |
| `git_repository`, `git_branch` | `null` | un projet local n'a pas de dépôt distant |
| `storage_prefix` | non alloué | aucun fichier ne monte dans le stockage |
| `user_id` | utilisateur du contexte de sécurité | jamais un identifiant venu du client |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|-------|-------------|-------------|--------|---------------|
| `name` | Oui | 255 | non vide après `trim()` | `trim()` |
| `rootName` (appairage) | Non | 255 | dernier segment de chemin uniquement | segment final, `trim()`, tronqué |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle | Notes |
|---------|-----|------|------|-------|
| POST | `/api/workspaces/local` | Oui | Gold | Corps : `{ "name": "runner-claude" }` |
| POST | `/api/runner/pair` | Jeton | — | **Existant** ; accepte un `rootName` optionnel en plus |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | INSERT / SELECT | `source` accepte `LOCAL` ; nouvelle colonne `runner_root_name` (nullable) |
| `runner_tokens` | inchangée | |

### Migration Liquibase

- [x] **Oui** — `0NN-workspaces-runner-root-name.xml` (colonne nullable, aucune donnée existante
      touchée). **Le numéro sera attribué après la vague F-39**, qui en consomme déjà 050 et 051.

### Composants Angular

Aucun dans cette subfeature — **SF-38-16** les porte.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | Aucun changement d'authentification ; le gating Gold existant s'applique tel quel |
| **Contexte tenant** | **Oui** | Nouvel endpoint de création : `user_id` pris **du contexte de sécurité**, jamais du corps. Composants revus : `WorkspaceController` (création), `WorkspaceService.requireOwned` (inchangé, premier geste partout), `RunnerPairingService` (l'appairage lie déjà le jeton au couple utilisateur/workspace). Test d'isolation à deux utilisateurs. |
| Plans / limites | **Oui** | Un projet `LOCAL` ne consomme **ni stockage objet, ni heures de bac à sable** — seuls les tokens sont décomptés. Vérifier que `assertWithinSandboxLimit` n'est pas appelé sur ce chemin (il porterait un refus sans objet), et que `assertWithinQuota` l'est toujours. |
| Navigation / routing | Non | Aucune route ajoutée dans cette subfeature |

---

## Plan de test

### Tests unitaires

- [ ] Création `LOCAL` : `source`, `executionTarget`, aucun préfixe de stockage alloué.
- [ ] Nom vide / trop long ⇒ 400 ; nom conservé après `trim()`.
- [ ] `rootName` `"/home/francky/dev/runner-claude"` ⇒ stocké `"runner-claude"` ; aucun chemin absolu
      en base **ni dans les logs** (capture de log vérifiée).
- [ ] `rootName` absent ⇒ projet valide, champ nul.
- [ ] Refus d'import de fichier, de bascule `SANDBOX`, d'opération Git — chacun avec son code, et
      **sans appel réseau** (vérifié par compteur d'appels, comme SF-31-08).
- [ ] Message sans runner connecté ⇒ refus lisible.
- [ ] Suppression d'un projet `LOCAL` : purge des données runner, aucune tentative de suppression
      dans le stockage.

### Tests d'intégration

- [ ] `POST /api/workspaces/local` → 201 avec un nom valide, 400 sans nom, 403 hors Gold.
- [ ] `POST /api/workspaces/local` puis lecture du détail : `source = LOCAL`, cible `RUNNER`.
- [ ] Import de fichier sur un projet `LOCAL` → 409.

### Isolation workspace

- [x] Applicable — un utilisateur B obtient 404 sur le projet `LOCAL` de A, sur chaque endpoint
      touché.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-01` (appairage) — done · `SF-38-05` (cible d'exécution) — done
- **Vague F-39 en cours** — cette subfeature touche `Workspace.java` et ajoute une migration, deux
  choses que la vague modifie déjà (050, 051). Le développement démarre **après** son merge final :
  livrer maintenant produirait un conflit garanti, et le lot 4 de F-39 refond précisément l'écran où
  la source se choisit.

### Questions ouvertes impactées

- [ ] Aucune tranchée ici ; trois restent ouvertes au §7.3 du banc d'essai (changement de machine,
      affichage hors ligne, exclusions par défaut).

---

## Notes et décisions

**D1 — Le runner déclare la racine, pas la gateway.** C'est le seul composant qui connaît le disque,
et le seul qui doit le connaître. La gateway reçoit au plus un **nom de dossier**, pour l'afficher.
Ce choix prolonge le `PathGuard` (SF-38-04, D6) : « la vérification qui fait foi est celle du
runner », et « le chemin absolu de la machine ne remonte jamais à la gateway ».

**D2 — Pas de synchronisation des fichiers vers le stockage.** L'idée était de copier le dossier dans
le stockage objet pour que l'explorateur fonctionne. Elle est écartée : elle recrée **deux sources de
vérité** — exactement le défaut qu'il a fallu trois subfeatures pour corriger sur les projets Git
(SF-31-08, 12, 13, jusqu'à la formule « le stockage porte le travail en cours, la branche porte le
publié ») — et elle ferait monter le code privé de l'utilisateur dans notre stockage, avec le volume
et le quota qui vont avec. **SF-38-17** répond au même besoin en lisant à la demande via le runner,
qui expose déjà `list_files` et `read_file`. La consultation hors ligne, si elle est demandée un
jour, sera un cache explicite et consenti — pas un effet de bord de l'architecture.

**D3 — `executionTarget = RUNNER` imposé, pas proposé.** Un projet `LOCAL` en cible `SANDBOX`
ouvrirait une session sur un dossier vide et laisserait croire que le travail a lieu quelque part.
Mieux vaut un champ non modifiable qu'une bascule qui ment.

**D4 — Aucun préfixe de stockage alloué.** Ne pas réserver ce dont on ne se servira jamais évite un
préfixe orphelin à purger à la suppression, et rend le refus d'import structurel plutôt que
défensif.
