# Mini-spec — [F-23 / SF-23-01] Dossier de fichiers par conversation — backend

## Identifiant

`F-23 / SF-23-01`

## Feature parente

`F-23` — Dossier de fichiers par conversation (espace listant tous les fichiers uploadés dans une conversation)

## Statut

`ready`

## Date de création

2026-07-03

## Branche Git

`feat/SF-23-01-backend-dossier-fichiers`

---

## Objectif

> En une phrase : exposer `GET /api/conversations/{id}/files` listant les fichiers téléversés
> rattachés à une conversation de l'utilisateur courant, en persistant l'association fichier↔conversation
> établie au moment où le fichier est joint à un tour de chat.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur téléverse un fichier via `POST /api/upload` (F-04) → `uploaded_files` (métadonnées, `conversation_id` NULL).
2. L'utilisateur envoie un message avec `attachmentIds` (F-04 SF-04-02) via `POST /chat` ou `POST /chat/stream`.
   Lors de la résolution des pièces jointes, chaque `uploaded_files` joint **dont `conversation_id` est NULL**
   se voit affecter la `conversation_id` de la conversation ciblée (association « premier rattachement gagne »).
3. `GET /api/conversations/{id}/files` retourne la liste des fichiers de cette conversation appartenant à
   l'utilisateur courant, triés du plus récent au plus ancien : `{ id, filename, mediaType, sizeBytes, createdAt }`.

Gateway-First : aucune lecture/traitement de contenu documentaire, aucun appel fournisseur — lecture relationnelle pure.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Conversation inexistante ou appartenant à un autre utilisateur | 404 neutre (indistinct) | 404 |
| Non authentifié | Rejet | 401 |
| Conversation sans fichier | Liste vide `[]` | 200 |

---

## Critères d'acceptation

- [ ] `GET /api/conversations/{id}/files` renvoie 200 + la liste des fichiers de la conversation possédée, triée `createdAt` desc.
- [ ] Chaque élément expose uniquement `id, filename, mediaType, sizeBytes, createdAt` (jamais `providerFileId` ni `userId`).
- [ ] Après un tour de chat avec `attachmentIds`, les fichiers joints portent la `conversation_id` de la conversation.
- [ ] Un `uploaded_files` déjà associé à une conversation n'est pas ré-associé (association immuable, « premier rattachement gagne »).
- [ ] Sécurité — isolation `user_id` : conversation d'un autre utilisateur → 404 ; fichiers d'un autre utilisateur jamais listés.
- [ ] La suppression d'une conversation détache les fichiers (`conversation_id` → NULL) sans supprimer les métadonnées (FK `ON DELETE SET NULL`).
- [ ] `POST /chat` inchangé (compat) ; l'ordre « résolution attachements avant écriture » (404 sans persistance) est préservé.

---

## Périmètre

### Hors scope (explicite)

- L'écran/panneau frontend (→ SF-23-02).
- Le téléchargement ou l'aperçu du contenu des fichiers (V1 = pas de persistance de contenu, PROJECT.md §11.6).
- Le détachement manuel d'un fichier d'une conversation, le renommage de fichier.
- Toute analyse documentaire (OCR/RAG) — hors périmètre.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `uploaded_files.conversation_id` | NULL | Renseigné au premier rattachement à un tour de chat (si NULL) |

Comportements : `conversation_id` reste NULL pour un fichier téléversé jamais joint à un message.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Notes |
|-------|-------------|------------------|-------|
| `{id}` (path) | Oui | UUID | Conversation possédée par l'utilisateur (sinon 404) |

---

## Technique

### Contrat API (figé — importé par SF-23-02)

`GET /api/conversations/{id}/files`
- Auth : Bearer JWT obligatoire.
- Réponse 200 : `ConversationFileResponse[]`
  ```json
  [
    { "id": "uuid", "filename": "rapport.pdf", "mediaType": "application/pdf",
      "sizeBytes": 12345, "createdAt": "2026-07-03T10:00:00Z" }
  ]
  ```
- 404 si la conversation n'appartient pas à l'utilisateur (ou n'existe pas).
- 401 si non authentifié.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `uploaded_files` | ALTER (add `conversation_id` nullable), SELECT, UPDATE | FK → `conversations(id)` `ON DELETE SET NULL`, index sur `conversation_id` |
| `conversations` | SELECT (ownership) | Isolation `user_id` |

### Migration Liquibase

- [x] Oui — `033-uploaded-files-conversation.xml` (changesets postgresql + h2 ; addColumn + addForeignKeyConstraint SET NULL + createIndex). Réversible (drop colonne/FK/index).

### Composants

- `UploadedFile` : + champ `conversationId` (nullable).
- `UploadedFileRepository` : + `findByConversationIdAndUserIdOrderByCreatedAtDesc`.
- `ChatService` : associe la conversation aux pièces jointes après résolution de la conversation (reply + prepareStream).
- `ConversationService` : + `filesOf(conversationId, userId)` (ownership + requête isolée).
- `ConversationController` : + `GET /{id}/files`.
- `ConversationFileResponse` (DTO).

---

## Plan de test

### Tests unitaires

- [ ] `ConversationService.filesOf` — nominal : renvoie les fichiers de la conversation triés desc.
- [ ] `ConversationService.filesOf` — conversation d'autrui → `ConversationNotFoundException` (404).
- [ ] `ChatService` — après reply avec attachments, `conversation_id` stampé sur les fichiers.
- [ ] `ChatService` — attachment d'un autre utilisateur → 404, aucune association ni persistance.

### Tests d'intégration

- [ ] `GET /api/conversations/{id}/files` → 200 + liste après upload + chat.
- [ ] `GET /api/conversations/{id}/files` → 404 conversation d'un autre utilisateur.
- [ ] `GET /api/conversations/{id}/files` → 200 `[]` si aucun fichier.

### Isolation user_id

- [x] Applicable — un utilisateur A ne voit jamais les fichiers/conversations de B (404 + requête filtrée `user_id`).

---

## Dépendances

### Subfeatures bloquantes

- F-04 (upload + attachmentIds) — done.
- F-02 (conversations) — done.

### Questions ouvertes impactées

- Aucune (OPEN_QUESTIONS non impacté).

---

## Notes et décisions

- **Arbitrage réversible (association)** : un fichier est associé à **une** conversation (colonne unique `conversation_id`),
  stampée au premier rattachement. Alternative écartée : table de liaison N-N `conversation_files` (les fichiers sont
  téléversés puis joints une fois côté frontend ; N-N = complexité non justifiée en V1, extensible plus tard).
- **Arbitrage réversible (cascade)** : `ON DELETE SET NULL` (le fichier reste une donnée de l'utilisateur, exportable RGPD,
  même si la conversation est supprimée). Alternative écartée : `CASCADE` (perdrait la trace côté RGPD/usage).
- Gateway-First : lecture relationnelle, aucun appel `AIProvider`, aucun contenu documentaire.
