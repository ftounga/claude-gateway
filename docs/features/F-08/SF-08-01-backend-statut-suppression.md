# Mini-spec — [F-08 / SF-08-01] Statut d'un document + suppression RGPD (backend)

## Identifiant

`F-08 / SF-08-01`

## Feature parente

`F-08` — Statut des documents

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-08-01-backend-statut-suppression`

---

## Objectif

> En une phrase : exposer l'état de traitement d'un document (`GET /documents/{id}/status`) et permettre à un utilisateur de supprimer définitivement un document et toutes ses données dérivées (chunks + vecteurs), au titre du droit à l'effacement RGPD.

---

## Comportement attendu

### Cas nominal

- **Statut** : `GET /api/documents/{id}/status` renvoie un `DocumentStatusResponse` léger (`id`, `status`, `chunkCount`, `errorMessage`) pour le document appartenant à l'utilisateur courant. Endpoint dédié au polling (charge utile minimale, sans le texte extrait ni le brut fournisseur).
- **Suppression** : `DELETE /api/documents/{id}` supprime le document de l'utilisateur courant. La suppression du document **cascade** au niveau base (FK `chunks.document_id → documents.id ON DELETE CASCADE`, migration `011`, définie pour Postgres et H2) : tous les chunks du document sont supprimés, et avec eux la colonne vectorielle `chunks.embedding` (Postgres). Réponse `204 No Content`. Idempotence non requise (404 si déjà supprimé).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `id` inexistant ou appartenant à un autre utilisateur (status) | `DocumentNotFoundException` (message neutre, ne révèle pas l'existence) | 404 |
| `id` inexistant ou appartenant à un autre utilisateur (delete) | `DocumentNotFoundException` (message neutre) | 404 |
| `id` non-UUID | Échec de conversion de type | 400 |
| Absence de JWT | Rejet par la chaîne de sécurité | 401 |

---

## Contrat API (FIGÉ — importé par SF-08-02 frontend)

### `GET /api/documents/{id}/status`
- Auth : Bearer JWT obligatoire. Isolation `user_id` appliquée dans le service.
- Réponse `200` :
```json
{
  "id": "uuid",
  "status": "UPLOADED | PROCESSING | EXTRACTED | INDEXING | INDEXED | FAILED",
  "chunkCount": 0,
  "errorMessage": "string | null"
}
```
- `404` si document introuvable pour l'utilisateur.

### `DELETE /api/documents/{id}`
- Auth : Bearer JWT obligatoire. Isolation `user_id` appliquée dans le service.
- Réponse : `204 No Content` (corps vide).
- `404` si document introuvable pour l'utilisateur.

---

## Critères d'acceptation

- [ ] `GET /documents/{id}/status` renvoie `200` avec `id/status/chunkCount/errorMessage` pour un document de l'utilisateur.
- [ ] `GET /documents/{id}/status` renvoie `404` si le document appartient à un autre utilisateur (isolation).
- [ ] `DELETE /documents/{id}` renvoie `204` et supprime le document ; un `GET` ultérieur renvoie `404`.
- [ ] `DELETE /documents/{id}` supprime en cascade les chunks du document (aucun chunk orphelin en base).
- [ ] `DELETE /documents/{id}` renvoie `404` si le document appartient à un autre utilisateur, et **ne supprime pas** le document de l'autre utilisateur (isolation).
- [ ] Aucun secret ni contenu documentaire n'est journalisé lors de la suppression.

---

## Périmètre

### Hors scope (explicite)

- L'écran frontend (bouton Supprimer + confirmation) → **SF-08-02**.
- La liste des documents et l'affichage des statuts (déjà livrés par F-05/F-06/F-07).
- Suppression en masse / purge de compte (relève de F-11 gestion du compte).
- Soft-delete / corbeille : la suppression est définitive (droit à l'effacement).

---

## Valeurs initiales

Aucune création d'entité. La suppression est un effacement définitif.

---

## Contraintes de validation

| Champ | Obligatoire | Format | Notes |
|-------|-------------|--------|-------|
| `id` (path) | Oui | UUID | Résolu contre `user_id` du JWT |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/documents/{id}/status` | Oui | USER |
| DELETE | `/api/documents/{id}` | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `documents` | SELECT / DELETE | Isolation `findByIdAndUserId` |
| `chunks` | DELETE (cascade) | Via FK `ON DELETE CASCADE` (migration `011`), colonne `embedding` supprimée avec la ligne |

### Migration Liquibase

- [ ] Non applicable — la FK `ON DELETE CASCADE` existe déjà (migration `011`, Postgres + H2). Aucun nouveau changeset. Aucune modification de schéma.

### Composants Angular

- Aucun (backend uniquement).

---

## Plan de test

### Tests unitaires (`DocumentServiceTest`)

- [ ] `getById` déjà couvert ; `delete` supprime le document via le repository (isolation `findByIdAndUserId`).
- [ ] `delete` lève `DocumentNotFoundException` si le document n'appartient pas à l'utilisateur.

### Tests d'intégration (`DocumentApiIntegrationTest`)

- [ ] `GET /documents/{id}/status` → 200 avec les bons champs pour le propriétaire.
- [ ] `GET /documents/{id}/status` → 404 pour un autre utilisateur.
- [ ] `DELETE /documents/{id}` → 204 ; `GET /documents/{id}` ultérieur → 404.
- [ ] `DELETE /documents/{id}` → cascade : les chunks du document sont supprimés (vérifié via `ChunkRepository`).
- [ ] `DELETE /documents/{id}` → 404 pour un autre utilisateur, document préservé.

### Isolation `user_id`

- [ ] Applicable — Bob ne peut ni consulter le statut ni supprimer un document d'Alice.

---

## Dépendances

### Subfeatures bloquantes

- F-05/F-06 (table `documents`, `chunks`, FK cascade) — **done**.

### Questions ouvertes impactées

- Aucune. OQ-01/02/03/10 déjà tranchées.

---

## Notes et décisions

- **Cascade DB plutôt qu'orchestration applicative** : la suppression des chunks repose sur la FK `ON DELETE CASCADE` déjà présente en `011` (Postgres + H2), ce qui évite une dépendance de package `ocr → rag` (le package `rag` dépend déjà de `ocr`, une dépendance inverse créerait un cycle). DB-agnostique, isolation garantie car on ne supprime que le document résolu par `findByIdAndUserId`.
- **Arbitrage réversible** : endpoint `/status` distinct de `GET /documents/{id}` pour un polling léger. Le frontend actuel poll la liste ; l'endpoint dédié reste disponible au contrat et pourra être adopté ultérieurement.
