# Mini-spec — F-04 / SF-04-02 Rattachement d'un fichier uploadé à un message de chat

## Identifiant

`F-04 / SF-04-02`

## Feature parente

`F-04` — Upload & transmission fichiers (sans OCR ni indexation)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-04-02-rattachement-chat`

---

## Objectif

Permettre d'attacher un ou plusieurs fichiers déjà uploadés (SF-04-01) à un message de chat :
`POST /api/chat` accepte `attachmentIds` et transmet au fournisseur — via l'interface `AIProvider`
— les références fichier comme blocs de contenu `document`/`image`, sans OCR ni indexation.

---

## Comportement attendu

### Cas nominal

1. `POST /api/chat` avec `{ message, attachmentIds: [uuid, ...] }`.
2. Le service résout chaque `attachmentId` via `uploaded_files` **filtré sur le `user_id` courant**
   (isolation) → récupère `provider_file_id` + `media_type`.
3. Le message utilisateur est persisté (texte) comme aujourd'hui.
4. L'appel fournisseur (`AIProvider.complete`) inclut, sur le dernier message utilisateur, les
   attachements : bloc `image` si `media_type` commence par `image/`, sinon bloc `document`
   (source `{ type: file, file_id }`, en-tête beta Files API).
5. Réponse `200` identique à F-02 (message assistant + conversation).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `attachmentId` inexistant ou appartenant à un autre utilisateur | `attachment_not_found` | 404 |
| `message` vide (règle F-02 conservée) | `validation_error` | 400 |
| Trop d'attachements (> 10) | `validation_error` | 400 |
| Non authentifié | 401 | 401 |
| Fournisseur dormant / erreur | 503 / 502 (mapping F-02) | 503/502 |

---

## Critères d'acceptation

- [ ] `POST /api/chat` avec `attachmentIds` valides → 200 ; la requête transmise au fournisseur porte les blocs `document`/`image` correspondants (vérifié via stub capturant la requête).
- [ ] Un `attachmentId` appartenant à un autre utilisateur → 404 `attachment_not_found`, aucun appel fournisseur.
- [ ] `attachmentIds` absent/vide → comportement F-02 inchangé (aucune régression).
- [ ] Le bloc est `image` pour un `image/*`, sinon `document` (mapping vérifié).
- [ ] Le `user_id` du filtre provient du JWT ; aucun `provider_file_id` ni clé n'est loggé.

---

## Périmètre

### Hors scope (explicite)

- Aucun OCR / extraction / indexation.
- Pas de persistance du lien message↔fichier (les attachements s'appliquent au tour courant ; non
  re-transmis sur les tours suivants — limitation V1 documentée).
- UI → SF-04-03.

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| attachmentIds | Non | liste d'UUID ; ≤ 10 ; chaque id possédé par l'utilisateur |
| message | Oui | non vide (règle F-02) |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| POST | `/api/chat` (champ `attachmentIds` ajouté) | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `uploaded_files` | SELECT (findByIdAndUserId) | isolation `user_id` |

### Migration Liquibase

- [ ] Non applicable (aucun changement de schéma).

### Interface fournisseur

- `ChatCompletionRequest` enrichi d'`attachments` (liste `ProviderAttachment(providerFileId, mediaType)`),
  appliqués au dernier message utilisateur par `AnthropicProvider`.

---

## Plan de test

### Tests unitaires

- [ ] `ChatService` — attachments résolus et passés dans `ChatCompletionRequest` (stub provider capture).
- [ ] `ChatService` — attachment d'un autre utilisateur → `AttachmentNotFoundException`.
- [ ] `AnthropicProvider` — mapping `image/*` → bloc image, autre → bloc document (test sans réseau ou via MockRestServiceServer).

### Tests d'intégration

- [ ] `POST /api/chat` avec `attachmentIds` d'Alice → 200 + requête fournisseur portant les blocs.
- [ ] `POST /api/chat` avec un `attachmentId` de Bob (isolation) → 404.
- [ ] `POST /api/chat` sans `attachmentIds` → 200 (régression F-02).

### Isolation utilisateur

- [x] Applicable — un utilisateur ne peut attacher que ses propres fichiers.

---

## Dépendances

### Subfeatures bloquantes

- `SF-04-01` — Done (table `uploaded_files`, `AIProvider.uploadFile`).

---

## Notes et décisions

- **Arbitrage réversible** : mapping type→bloc par préfixe `image/`. Alternative écartée : table de
  correspondance explicite (surdimensionnée pour V1).
- **Limitation V1 assumée** : attachements non re-transmis aux tours suivants (pas de lien persistant
  message↔fichier). Suffisant pour « envoyer un fichier et recevoir une réponse » (PROJECT.md §5.x).
