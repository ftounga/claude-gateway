# Mini-spec — F-48 / SF-48-01 — Le poste : modèle, appairage unique et routage par poste

## Identifiant

`F-48 / SF-48-01`

## Feature parente

`F-48` — Le poste comme unité (`docs/PRODUCT_SPEC.md`, cadrage `docs/features/CADRAGE-postes-et-gouvernance.md`)

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-48-01-poste-modele`

---

## Objectif

Déplacer l'unité du runner du **projet** vers le **poste** : une machine, une racine, un runner, un
seul appairage — et faire voyager le **projet** dans chaque appel d'outil.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur crée un **poste** (`POST /runner/hosts`, un nom libre). Le poste lui appartient.
2. Il génère **un** code d'appairage pour ce poste (`POST /runner/hosts/{hostId}/pairing-code`).
3. Le runner lancé à la racine du poste échange le code (`POST /runner/pair`) et déclare sa racine,
   son système, ses droits. La gateway enregistre ces déclarations **sur le poste**, jamais sur un
   projet, et rend un jeton lié au poste.
4. Un projet est **rattaché** au poste avec son **chemin relatif** sous la racine
   (`PUT /workspaces/{id}/host`). Ouvrir un second projet sous la même racine ne demande **aucun**
   nouvel appairage.
5. Un tour en cible `RUNNER` route l'appel vers la connexion du **poste** et transmet le
   **chemin relatif du projet** dans la trame `tool_call` (champ `project`).
6. Le journal (`runner_audit`) conserve **quel projet** a exécuté quoi, et gagne le poste.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Poste d'un autre utilisateur (ou inconnu) | Traité comme introuvable | 404 |
| Nom de poste vide ou > 100 caractères | Message explicite | 400 |
| Rattachement à un poste non possédé | Traité comme introuvable | 404 |
| Chemin relatif absolu, avec `..`, ou > 512 caractères | Message explicite | 400 |
| Projet en cible `RUNNER` sans poste rattaché | `runner_unavailable` rendu au modèle, rien n'est émis | 200 (issue d'outil) |
| Code d'appairage inconnu / expiré / déjà consommé | Inchangé (`PairingInvalidException`) | 400 |

---

## Critères d'acceptation

- [ ] Table `runner_hosts` créée (`id`, `user_id`, `name`, `root_name`, `os`, `shell`,
      `elevated`, `last_seen_at`, `created_at`, `updated_at`), Postgres **et** H2.
- [ ] Les colonnes `runner_root_name`, `runner_elevated`, `runner_shell` disparaissent de
      `workspaces`, qui gagne `host_id` (nullable) et `project_path`.
- [ ] `runner_tokens`, `runner_pairing_codes` passent de `workspace_id` à `host_id` (non nul) ;
      `runner_audit` gagne `host_id` et **conserve** `workspace_id`.
- [ ] **Table rase** : les trois tables runner sont vidées par la migration, aucune colonne de
      transition, aucune double lecture.
- [ ] `RunnerIdentity` vaut `(tokenId, userId, hostId)`.
- [ ] `RunnerRegistry`, `RunnerCallDispatcher`, `RunnerPollingSessions`, le relais inter-pods et le
      registre PgNotify sont indexés par **poste** (routage correct sous HPA).
- [ ] La trame `tool_call` porte `project` (chemin relatif sous la racine, `""` = la racine).
- [ ] Tout accès à un poste, un jeton, un journal filtre `user_id`.
- [ ] `GET /workspaces/{id}/runner/status` continue de répondre, en résolvant le poste du projet.
- [ ] `mvn -pl backend test` vert.

---

## Périmètre

### Hors scope

- Le **confinement par sous-dossier côté runner** (SF-48-02).
- Les **écrans** (SF-48-03).
- La vue d'ensemble des postes (F-49), le catalogue de gouvernance (F-50→52).
- Le partage d'un poste entre comptes (F-17, V3).

---

## Impacts

### Tables

`runner_hosts` (nouvelle), `workspaces`, `runner_tokens`, `runner_pairing_codes`, `runner_audit`.
Migration `064-runner-hosts.xml`.

### Endpoints

| Verbe | Chemin | Effet |
|---|---|---|
| POST | `/runner/hosts` | Crée un poste |
| GET | `/runner/hosts` | Liste les postes de l'utilisateur |
| DELETE | `/runner/hosts/{id}` | Supprime un poste (détache ses projets) |
| POST | `/runner/hosts/{id}/pairing-code` | Code d'appairage du poste |
| GET | `/runner/hosts/{id}/tokens` | Jetons du poste |
| DELETE | `/runner/hosts/{id}/tokens/{tokenId}` | Révoque un jeton |
| POST | `/runner/hosts/{id}/kill` | Coupe-circuit du poste |
| GET | `/runner/hosts/{id}/status` | État du poste |
| PUT | `/workspaces/{id}/host` | Rattache un projet à un poste |
| GET | `/workspaces/{id}/runner/status` | Inchangé (résout le poste) |
| GET | `/workspaces/{id}/runner/audit` | Inchangé (journal du projet) |

Endpoints **supprimés** (table rase) : `POST /workspaces/{id}/runner/pairing-code`,
`GET /workspaces/{id}/runner/tokens`, `DELETE /workspaces/{id}/runner/tokens/{tokenId}`,
`POST /workspaces/{id}/runner/kill`.

### Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | **oui** | `RunnerIdentity`, `RunnerTokenAuthenticator`, `RunnerHandshakeInterceptor`, `RunnerPollController`, `RunnerSecurityConfig` (chaîne `/runner/**` : les nouveaux `/runner/hosts/**` sont **JWT**, pas jeton runner) |
| Contexte tenant | **oui** | `RunnerHostService`, `RunnerTokenService`, `RunnerPairingService`, `RunnerAuditService`, `RunnerStatusService`, `WorkspaceService`, `AccountService` (purge) |
| Plans / limites | non | inchangé (`AtelierAccessService` conservé sur les endpoints de gestion) |
| Navigation / routing | non | frontend traité en SF-48-03 |

---

## Plan de test

### Unitaires

- `RunnerHostServiceTest` — création, listing, suppression, isolation `user_id` (404 chez autrui).
- `RunnerTokenServiceTest` — émission/listing/révocation **par poste**.
- `RunnerPairingServiceTest` — appairage : le code du poste rend un jeton du poste, déclarations
  (racine, os, droits) écrites sur le poste.
- `RunnerCallDispatcherTest` — la trame `tool_call` porte `project` ; routage par `hostId`.
- `RunnerCallRouterTest`, `RunnerRelay*Test` — relais indexé par poste.
- `WorkspaceServiceTest` — rattachement, refus d'un chemin relatif invalide, isolation.

### Intégration

- `RunnerPairingApiIntegrationTest` — parcours complet poste → code → appairage → jeton.
- `RunnerStatusApiIntegrationTest` — statut d'un projet rattaché.
- `RunnerAccountDeletionApiIntegrationTest` — purge du domaine runner à la suppression du compte.

### Isolation utilisateur

Chaque endpoint neuf a un test « 404 chez autrui ».
