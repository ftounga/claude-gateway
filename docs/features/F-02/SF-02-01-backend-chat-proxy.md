# Mini-spec — [F-02 / SF-01] Backend — Proxy de chat Claude (Hosted) + persistance conversations/messages

## Identifiant

`F-02 / SF-01`

## Feature parente

`F-02` — Chat proxy Claude (Hosted)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-02-01-backend-chat-proxy`

---

## Objectif

> En une phrase : relayer les messages de l'utilisateur vers Claude en mode Hosted via une interface `AIProvider` abstraite, en persistant conversations et messages, isolés par `user_id`.

Exposer `POST /api/chat` et la gestion des conversations (`GET`/`PATCH`/`DELETE`), le tout branché sur une couche fournisseur (`AIProvider`) jamais couplée directement à Anthropic dans le code métier.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur authentifié envoie `POST /api/chat` avec `{ conversationId?, message, model? }`.
2. Le service valide le message et le modèle (liste blanche configurable, défaut `claude-opus-4-8`).
3. Si `conversationId` est absent → création d'une conversation appartenant à l'utilisateur (titre dérivé du premier message, tronqué à 60 car.).
4. Si `conversationId` présent → vérification d'appartenance (`user_id`), sinon 404.
5. Le message utilisateur est persisté (`role=USER`).
6. L'historique de la conversation est transmis à `AIProvider.complete(...)` (jamais l'API Anthropic directement).
7. La réponse de Claude est persistée (`role=ASSISTANT`, `model`), `updated_at` de la conversation est rafraîchi.
8. Réponse `200` : `{ conversationId, message: {...assistant...}, model }`.

La clé plateforme (`ANTHROPIC_API_KEY`) provient exclusivement de l'environnement, n'est jamais exposée au client ni journalisée.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `message` absent / vide (après trim) | Erreur de validation | 400 |
| `model` hors liste blanche | Erreur de validation | 400 |
| Requête non authentifiée | `unauthorized` (entry point JSON existant) | 401 |
| `conversationId` inexistant **ou** appartenant à un autre utilisateur | `not_found` (pas de fuite d'existence) | 404 |
| Fournisseur non configuré (clé absente) | `provider_unavailable` | 503 |
| Échec/timeout du fournisseur en amont | `provider_error` (jamais l'exception brute) | 502 |

---

## Critères d'acceptation

- [ ] `POST /api/chat` sans `conversationId` crée une conversation + 2 messages (USER, ASSISTANT) et renvoie 200.
- [ ] `POST /api/chat` avec `conversationId` d'un autre utilisateur → 404 (isolation).
- [ ] `POST /api/chat` avec `message` vide → 400 ; avec `model` inconnu → 400.
- [ ] `GET /api/conversations` ne renvoie que les conversations de l'utilisateur courant (tri `updated_at` desc).
- [ ] `GET /api/conversations/{id}` renvoie la conversation + ses messages ; 404 si non possédée.
- [ ] `PATCH /api/conversations/{id}` renomme (titre non vide, ≤ 200) ; 404 si non possédée.
- [ ] `DELETE /api/conversations/{id}` supprime (cascade messages) ; 404 si non possédée ; 204 sinon.
- [ ] Le code métier dépend de l'interface `AIProvider`, jamais d'une classe Anthropic directement.
- [ ] La clé plateforme n'apparaît ni dans les logs ni dans les réponses (test de non-fuite).
- [ ] Fournisseur non configuré → 503 ; erreur amont → 502.

---

## Périmètre

### Hors scope (explicite)

- Streaming SSE (réponse renvoyée en un bloc en V1 ; endpoint additif ultérieur — arbitrage tracé).
- BYOK (clé utilisateur) → F-03.
- Upload / transmission de fichiers → F-04.
- Quotas / entitlements → F-10 (le point d'appel `AIProvider` est prêt à recevoir un gate ultérieur).
- Rendu Markdown / UI → SF-02-02 (frontend).
- OCR / RAG / embeddings / pgvector → hors V1.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| conversation.title | 60 premiers caractères du 1er message (trim) | dérivé à la création ; renommable via PATCH |
| conversation.model | `model` fourni, sinon `claude-opus-4-8` (défaut config) | figé à la création de la conversation |
| conversation.user_id | utilisateur du contexte de sécurité | `CurrentUser.requireId()` |
| message.role | `USER` puis `ASSISTANT` | imposé par le flux |
| message.model | null (USER) / modèle appelé (ASSISTANT) | — |
| created_at / updated_at | `now()` | base + `@CreationTimestamp`/`@UpdateTimestamp` |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-------------|----------------------------|---------------|
| message | Oui | 32000 | non vide après trim | trim conservé côté contenu |
| model | Non | 64 | ∈ `app.ai.anthropic.models` (défaut : opus-4-8, sonnet-5, haiku-4-5) | — |
| title (PATCH) | Oui | 200 | non vide après trim | trim |

---

## Technique

### Contrat API (figé — importé par SF-02-02)

**`POST /api/chat`** — Auth requise
Request : `{ "conversationId": "uuid|null", "message": "string", "model": "string|null" }`
Response 200 :
```json
{
  "conversationId": "uuid",
  "message": { "id": "uuid", "role": "ASSISTANT", "content": "string", "model": "claude-opus-4-8", "createdAt": "ISO-8601" },
  "model": "claude-opus-4-8"
}
```

**`GET /api/conversations`** — Auth requise
Response 200 : `[ { "id":"uuid", "title":"string", "model":"string", "createdAt":"ISO", "updatedAt":"ISO" } ]` (tri updatedAt desc)

**`GET /api/conversations/{id}`** — Auth requise
Response 200 : `{ "id","title","model","createdAt","updatedAt", "messages": [ { "id","role","content","model","createdAt" } ] }`

**`PATCH /api/conversations/{id}`** — Auth requise
Request : `{ "title": "string" }` → Response 200 : `{ "id","title","model","createdAt","updatedAt" }`

**`DELETE /api/conversations/{id}`** — Auth requise → 204

**`GET /api/chat/models`** — Auth requise
Response 200 : `{ "defaultModel":"claude-opus-4-8", "models":["claude-opus-4-8","claude-sonnet-5","claude-haiku-4-5"] }`

Erreurs homogènes : `{ "error": "code", "message": "..." }`.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| conversations | CREATE (migration 005) | `id, user_id, title, model, created_at, updated_at` |
| messages | CREATE (migration 006) | `id, conversation_id (FK cascade), user_id, role, content, model, created_at` |

### Migration Liquibase

- `005-conversations.xml` — changesets postgresql + h2 (id générés app, `@UuidGenerator`)
- `006-messages.xml` — FK `conversation_id → conversations(id) ON DELETE CASCADE` ; index `(user_id)` et `(conversation_id)`

### Composants backend

- `ai/AIProvider` (interface), `ai/ChatCompletionRequest`, `ai/ChatCompletionResult`, `ai/ChatMessage`, `ai/AIProviderException`, `ai/AIProviderUnavailableException`
- `ai/AnthropicProvider` (impl Hosted, `RestClient`, clé env, jamais loggée) — actif si clé présente
- `chat/Conversation`, `chat/Message`, `chat/MessageRole`, repositories
- `chat/ConversationService`, `chat/ChatService`
- `chat/ChatController`, `chat/ConversationController`
- DTO : `ChatRequest`, `ChatResponse`, `ConversationSummaryResponse`, `ConversationDetailResponse`, `MessageResponse`, `RenameConversationRequest`, `ModelsResponse`
- Exceptions métier + handlers dans `GlobalExceptionHandler` (`ConversationNotFoundException`, `UnsupportedModelException`, provider exceptions)

---

## Plan de test

### Tests unitaires

- [ ] `ChatService` — nominal : crée conversation + persiste USER/ASSISTANT, appelle `AIProvider` (mock).
- [ ] `ChatService` — modèle inconnu → `UnsupportedModelException`.
- [ ] `ChatService` — conversation d'un autre user → `ConversationNotFoundException`.
- [ ] `ChatService` — provider indisponible → 503 mappé ; erreur amont → 502 mappé.
- [ ] `ConversationService` — list/detail/rename/delete filtrent sur `user_id`.

### Tests d'intégration (MockMvc, provider stubbé)

- [ ] `POST /api/chat` 200 (création) avec un `AIProvider` de test injecté.
- [ ] `POST /api/chat` 400 message vide / modèle inconnu.
- [ ] `POST /api/chat` 401 sans token.
- [ ] `POST /api/chat` 404 conversation d'un autre user.
- [ ] `GET/PATCH/DELETE /api/conversations/{id}` 404 cross-user.
- [ ] Réponse et logs ne contiennent jamais la clé plateforme.

### Isolation utilisateur

- [ ] Applicable — un utilisateur A ne lit/écrit jamais les conversations/messages de B (tests dédiés list + detail + chat + rename + delete).

---

## Dépendances

### Subfeatures bloquantes

- `SF-01-01` (socle sécurité `CurrentUser` / JWT) — done.

### Questions ouvertes impactées

- [ ] OQ-04 (modèles Claude disponibles) — **contournée** : liste blanche + modèle défaut configurables par environnement ; aucune valeur codée en dur bloquante.

---

## Notes et décisions (arbitrages)

- **Nouvelles tables `conversations` + `messages`** : `messages` alignée sur `spec.md §4` mais `user_id`/`conversation_id` en `uuid` (cohérent F-01) + FK cascade et colonne `model`. `conversations` ajoutée (implicite `PROJECT.md §11.4`). Réversible.
- **Non-streaming en V1** : `POST /chat` renvoie la réponse complète (aligné `spec.md §5`). Le streaming (`PROJECT.md §11.5`) est additif et sera un endpoint séparé. Réversible.
- **Titre auto** dérivé du 1er message (renommable). Réversible.
- **`AIProvider` dormant si clé absente** : l'app démarre normalement (profils dev/test), les appels chat renvoient 503. Cohérent avec le pattern OAuth conditionnel existant.
