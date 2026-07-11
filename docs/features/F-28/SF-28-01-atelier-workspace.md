# SF-28-01 — Atelier : workspace projet + upload ZIP + fichiers (backend)

Parent : **F-28 — Atelier / Claude Code Lite (Phase 1)** · ADR-012
Type : subfeature backend (fondation)
Statut : En cours

## Objectif (une phrase)

Poser la fondation de l'Atelier : créer un **workspace projet isolé par utilisateur** à partir d'un **`.zip`** décompressé de façon sûre dans un **stockage objet** (S3), et exposer la **lecture/écriture des fichiers** — sans Claude ni exécution (ce sera SF-28-02).

## Comportement nominal

1. `POST /workspaces` (multipart : `file`=zip, `name` optionnel) → crée un `Workspace` (`user_id`), valide et décompresse le zip vers le stockage sous `atelier/{userId}/{workspaceId}/…`. Initialise un `CLAUDE.md` minimal si absent.
2. `GET /workspaces` → workspaces de l'utilisateur. `GET /workspaces/{id}` → métadonnées + arborescence.
3. `GET /workspaces/{id}/file?path=` → contenu ; `PUT …/file?path=` → écriture. `DELETE /workspaces/{id}` → suppression (préfixe + ligne).

## Cas d'erreur

| Cas | Réponse |
|-----|---------|
| **zip-slip** (entrée hors racine : `..`, absolu) | entrée **ignorée** (jamais écrite hors préfixe) |
| **zip-bomb** (total > 50 Mo, > 2000 entrées, fichier > 2 Mo) | **400** `invalid_archive` |
| `path` avec traversée (`..`/absolu) | **400** `invalid_file_path` |
| Workspace d'un autre utilisateur / inconnu | **404** `not_found` (indiscernable) |

## Critères d'acceptation

- [ ] Table `workspaces` (migration `036`, postgres+h2). Isolation `user_id` sur tout accès (`findByIdAndUserId`).
- [ ] `WorkspaceStorage` abstrait (impl `S3` + `InMemory`) ; **tests sans réseau** (InMemory).
- [ ] Sécurité zip-slip + zip-bomb + path-safety **testées**.
- [ ] `CLAUDE.md` initialisé si absent.
- [ ] Endpoints protégés, DTOs sans fuite (pas de clé S3 brute).

## Plan de test

- `WorkspaceServiceTest` (InMemory) : zip-slip ignoré, zip-bomb→400, happy path (upload/liste/tree/read/write), CLAUDE.md initialisé, path traversal→400.
- `AtelierApiIntegrationTest` (MockMvc, H2) : cycle complet + isolation cross-user→404 + 400 path/archive.

## Composants / tables

- **Table** : `workspaces` (nouvelle), migration `036-workspaces`. Aucune autre.
- **Package** `atelier` : `Workspace`, `WorkspaceRepository`, `AtelierProperties`, `AtelierConfig`, `storage/{WorkspaceStorage,S3WorkspaceStorage,InMemoryWorkspaceStorage}`, `WorkspaceService`, `AtelierController`, `dto/*`, exceptions + handlers.

## Hors périmètre

- Boucle tool-use / Claude (SF-28-02). UI (SF-28-03). Exécution de code (Phase 2). Versionnement de fichiers.
