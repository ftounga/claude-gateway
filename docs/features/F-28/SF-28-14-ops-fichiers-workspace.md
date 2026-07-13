# SF-28-14 — Opérations fichiers du workspace : supprimer / renommer / exporter (backend)

## Objectif
Permettre à l'utilisateur de supprimer, renommer et exporter (en `.zip`) les fichiers d'un workspace de l'Atelier, côté backend, dans le respect strict de l'isolation `user_id` et des garde-fous de chemin existants.

## Comportement nominal
- **Supprimer** : `DELETE /workspaces/{id}/file?path=...` → 204. Le fichier disparaît de l'arborescence.
- **Renommer/déplacer** : `POST /workspaces/{id}/file/rename` `{from, to}` → 200 + arborescence à jour. Le contenu passe sous `to`, `from` disparaît.
- **Exporter** : `GET /workspaces/{id}/export` → 200 `application/zip`, `Content-Disposition: attachment; filename="<nom-assaini>.zip"`. Chaque fichier devient une entrée nommée par son chemin relatif (round-trip cohérent avec l'import de création).

## Cas d'erreur
- Workspace non possédé / inexistant → 404 (`WorkspaceNotFoundException`) — isolation `user_id` via `requireOwned` en premier.
- Fichier inexistant (delete / rename source) → 404 (`WorkspaceNotFoundException`, même exception que `readFile`).
- Chemin invalide (zip-slip / `..` / absolu) en source ou destination → 400 (`InvalidFilePathException`).
- `from`/`to` vides → 400 (validation `@NotBlank`).
- Accès non-Gold → 403 (`atelierAccess.requireAccess()`).

## Critères d'acceptation
- Les 3 endpoints existent et respectent les statuts ci-dessus.
- `requireOwned` est appelé en premier dans chaque opération de service.
- `deleteFile` supprime la clé **exacte** (pas de collision de préfixe `x.js` vs `x.js.bak`).
- `renameFile` réutilise `readFile`/`writeFile`/`deleteFile` internes (aucun garde-fou dupliqué) et ne s'auto-supprime pas si `from == to`.
- `exportZip` produit un zip dont les entrées sont les chemins relatifs (sans préfixe de stockage).

## Plan de test
- **Unitaires `WorkspaceServiceTest`** : delete (absent de l'arbre ; inexistant → 404 ; autre user → 404) ; rename (contenu sous `to`, `from` disparu ; `to` invalide → 400 ; source inexistante → 404) ; export (zip non vide, round-trip des chemins).
- **Unitaires storage** : `InMemoryWorkspaceStorage.deleteFile` retire la clé exacte, conserve la voisine.
- **Intégration `AtelierApiIntegrationTest`** : DELETE / rename / export nominal + isolation cross-user (404 sur delete/rename/export du workspace d'un autre user).

## Impacts
- `WorkspaceStorage` (+`deleteFile`), `InMemoryWorkspaceStorage`, `S3WorkspaceStorage`.
- `WorkspaceService` (+`deleteFile`, `renameFile`, `exportZip`).
- `AtelierController` (+3 endpoints), DTO `RenameFileRequest`.

## Préoccupations transversales
- **Auth / tenant** : aucun nouveau mode d'auth ; `user_id` résolu comme les endpoints existants (`CurrentUser.requireId()` + `requireOwned`). Endpoints impactés : uniquement les 3 nouveaux, alignés sur `readFile`/`writeFile`/`delete` existants.

## Hors périmètre
- Frontend (écran / boutons) — subfeature dédiée.
- Renommage de workspace, déplacement de dossiers en masse, corbeille / versioning.
