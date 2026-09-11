# Mini-spec — F-71 / SF-71-02 — Les sous-dossiers d'un poste, listés par le runner

## Identifiant

`F-71 / SF-71-02`

## Feature parente

`F-71` — Le poste « Hébergé », et le dossier qu'on désigne sans le taper

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-71-02-dossiers-du-poste`

---

## Objectif

Exposer `GET /api/runner-hosts/{hostId}/folders` : les **sous-dossiers** d'un poste, lus sur la
machine par le runner, pour qu'on **clique** un dossier au lieu de le taper.

---

## Comportement attendu

### Cas nominal

1. L'écran appelle `GET /runner-hosts/{hostId}/folders` (optionnellement `?path=clients`).
2. La gateway vérifie l'**appartenance** du poste (`requireOwned`), puis relaie un `list_files` au
   runner de ce poste, **confiné sur `path`** — la même mécanique que l'explorateur de SF-38-17,
   avec `project = path` dans la trame (SF-48-02).
3. Les chemins rendus par le runner sont relatifs à `path`. La gateway en **dérive les sous-dossiers
   immédiats** : le premier segment de tout chemin qui en contient plusieurs.
4. Réponse :

```json
{ "path": "clients", "parentPath": "", "folders": [
    { "name": "EDENRED", "path": "clients/EDENRED", "used": false } ],
  "truncated": false }
```

- `used = true` quand un projet de **cet** utilisateur occupe déjà ce chemin sous ce poste (A4).
- `truncated = true` quand le runner a tronqué sa liste : **des dossiers peuvent manquer**, et
  l'écran le dit (SF-38-21 — une liste incomplète se dit, elle ne se devine pas).
- `parentPath` est `null` à la racine, sinon le chemin du dossier parent (navigation, A3).

5. Chaque lecture est **auditée** sous le nom d'outil `screen_list_folders` — distinct de
   `screen_list_files` (l'écran d'un projet) et de `list_files` (l'agent) : le journal doit pouvoir
   dire *ce qui a été lu sur ma machine, et pourquoi*.

### Ce qui est écarté de la liste

| Écarté | Par qui | Pourquoi |
|--------|---------|----------|
| `.runnerignore` / `.gitignore`, liste de bruit (`node_modules/`, `target/`…) | **le runner** (SF-38-10, SF-38-21) | ce qui est exclu ne quitte jamais la machine |
| dossiers dont le nom commence par `.` | la gateway (A5) | outillage, jamais un projet — proposer `.git` ferait cliquer dessus |
| les fichiers | la gateway | on désigne un **dossier** de projet |

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Runner **non connecté** | Message explicite : « Le runner de ce poste n'est pas connecté : lancez-le pour choisir un dossier. » — **jamais une liste vide** (D7) | 409 `runner_browse_unavailable` |
| Runner joignable sur un autre pod, relais éteint | Même refus explicite | 409 |
| `path` inexistant sur la machine | Le refus du runner (`not_found`) est repris tel quel | 409 |
| `path` absolu, avec `..`, avec lettre de lecteur, > 512 caractères | Refusé **avant** tout appel | 400 `invalid_project_path` |
| Poste d'un autre utilisateur, ou inexistant | Refus indifférencié (`requireOwned`) | 404 |
| Accès Atelier absent | Refus | 403 |
| Racine ne contenant que des fichiers | `folders: []` — une réponse honnête, pas une erreur | 200 |

---

## Critères d'acceptation

- [ ] `GET /runner-hosts/{hostId}/folders` rend les sous-dossiers **immédiats** de la racine,
      triés, sans doublon, et **jamais** de fichier.
- [ ] `?path=` navigue d'un niveau : `parentPath` permet de remonter, `path` situe où l'on est.
- [ ] Un dossier déjà occupé par un projet du poste est marqué `used = true`.
- [ ] Les dossiers cachés (`.` initial) n'apparaissent pas.
- [ ] Runner non connecté → **409 avec un message qui le dit**, jamais `folders: []`.
- [ ] `path` invalide → 400 **sans qu'aucun appel ne parte** vers la machine.
- [ ] Le poste d'un autre utilisateur → 404, et **aucun** appel n'est relayé.
- [ ] Chaque lecture réussie **ou refusée** écrit une ligne d'audit `screen_list_folders`.
- [ ] `truncated` est vrai quand le runner a tronqué.

---

## Périmètre

### Hors scope (explicite)

- **Créer** un dossier depuis l'application (D8).
- Lire le **contenu** d'un fichier depuis cet endpoint — `screen_read_file` existe déjà.
- Rattacher le projet : c'est `PUT /api/workspaces/{id}/host`, inchangé.
- Le nombre de fichiers ou la taille d'un dossier : une information de plus par dossier, c'est un
  balayage de plus sur la machine pour une décision que le nom suffit à prendre.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|-------|-------------|-------------|--------|---------------|
| `hostId` | oui | — | UUID de chemin | — |
| `path` | non | 512 | relatif, `/`, sans `..`, sans lettre de lecteur | `RunnerProjectPath.normalize` (réutilisé tel quel) |

Bornes de sortie : **500 dossiers** au plus par réponse (au-delà, `truncated = true`). Un sélecteur
de 500 entrées est déjà trop long à lire ; en rendre 5 000 ne rendrait service à personne.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/runner-hosts/{hostId}/folders?path=` | JWT | accès Atelier |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_audit` | INSERT | une ligne par lecture, `workspace_id` **nul** (aucun projet concerné) |
| `workspaces` | SELECT | `listByHost(userId, hostId)` pour marquer `used` |
| `runner_hosts` | SELECT | `requireOwned` |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucune colonne, aucune table. `runner_audit.workspace_id` est déjà
      nullable (il l'est depuis le coupe-circuit, qui est un geste de machine).

### Classes

- `RunnerHostFolderBrowser` (`runner/browse/`) — la lecture, la dérivation, l'audit.
- `HostFoldersResponse` (`runner/host/dto/`) — `path`, `parentPath`, `folders`, `truncated`.
- `RunnerHostController` — `GET /{hostId}/folders`.
- `RelayCallRequest.isValid()` — accepte désormais un `workspaceId` **nul** : un appel de **poste**
  n'a pas de projet par construction. `workspaceId` ne sert qu'à l'isolation des annulations en
  vol ; nul, l'appel n'est simplement pas annulable par projet — ce qui est exact.

---

## Plan de test

### Unitaires (`RunnerHostFolderBrowserTest`)

- [ ] Liste de fichiers plate → sous-dossiers immédiats dédupliqués et triés.
- [ ] Aucun sous-dossier (que des fichiers) → liste vide, sans erreur.
- [ ] Dossiers cachés écartés.
- [ ] Marqueur de troncature du runner → `truncated = true` et marqueur **absent** des dossiers.
- [ ] Plafond de 500 → `truncated = true`.
- [ ] `used = true` sur un chemin déjà occupé par un projet du poste.
- [ ] `runner_unavailable` / `runner_not_on_this_node` / `runner_timeout` → `RunnerBrowseException`
      au message « pas connecté », jamais une liste vide.
- [ ] Refus du runner (`not_found`) → message du runner repris.
- [ ] Une ligne d'audit est écrite dans **tous** les cas, sous `screen_list_folders`.

### Intégration (`RunnerHostFoldersIT`)

- [ ] `GET /runner-hosts/{id}/folders` sans runner → 409 `runner_browse_unavailable`.
- [ ] `?path=../x` → 400 `invalid_project_path`.
- [ ] Poste d'un autre utilisateur → 404.
- [ ] Sans JWT → 401.

### Isolation `user_id` (**obligatoire**)

- [x] Applicable — `requireOwned(userId, hostId)` précède **tout** ; l'utilisateur B ne fait jamais
      lister la machine de A, et le marquage `used` lit les projets de l'appelant seulement.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | chemin `/runner-hosts/**`, chaîne JWT existante, inchangée |
| Contexte tenant | **oui** | `RunnerHostController.folders` (identité du JWT), `RunnerHostService.requireOwned`, `WorkspaceService.listByHost(userId, …)`, `RunnerAuditService.recordCall(userId, …)` |
| Plans / limites | non | aucun quota touché ; la lecture ne consomme aucun jeton fournisseur |
| Navigation / routing | non | backend seul |

Le changement de `RelayCallRequest.isValid()` touche le **relais inter-pods** : vérifié que
`workspaceId` n'y sert qu'à `RunnerCallDispatcher.cancelWorkspace`, jamais à une autorisation — le
pod destinataire ne fait confiance qu'au secret partagé, et l'appartenance a déjà été vérifiée par
le pod appelant (contrat du relais §3). Les quatre autres enveloppes du relais
(`RelayGestureRequests`) gardent leur exigence de `workspaceId` : elles visent bien un projet.

---

## Notes et décisions

- **A2** : aucun outil runner nouveau. Un `list_dirs` obligerait chaque runner déjà installé à être
  mis à jour pour que l'écran marche — la friction que F-48 a précisément supprimée.
- **A3** : la navigation par `?path=` s'appuie sur le confinement du runner (`ProjectScopes`), qui
  canonicalise, suit les liens et refuse de sortir de la racine. La garde qui fait foi reste la
  sienne ; celle de la gateway refuse seulement d'émettre ce qu'aucun runner n'accepterait.
