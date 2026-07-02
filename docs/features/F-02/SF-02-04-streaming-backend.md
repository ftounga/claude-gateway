# Mini-spec — [F-02 / SF-02-04] Streaming des réponses de chat — backend (SSE)

## Identifiant

`F-02 / SF-02-04`

## Feature parente

`F-02` — Chat proxy Claude

## Statut

`ready`

## Date de création

2026-07-03

## Branche Git

`feat/SF-02-04-streaming-backend`

---

## Objectif

Exposer un endpoint de chat **en streaming** (`POST /chat/stream`, `text/event-stream`) qui **relaie** le flux token-par-token de Claude vers le client, en réutilisant la persistance, l'isolation `user_id`, le quota et le BYOK existants — sans réimplémenter de moteur IA (Provider-First).

---

## Contexte

`PRODUCT_SPEC` (F-02) notait « streaming = amélioration ultérieure ». L'endpoint actuel `POST /chat` renvoie la réponse **en un bloc** (`aiProvider.complete`). Cette SF ajoute le **relais SSE** côté backend. La consommation côté frontend est **SF-02-05** (séparée). L'endpoint non-streamé existant reste **inchangé** (compat).

---

## Comportement attendu

### Cas nominal

- `POST /api/chat/stream` (corps identique à `POST /chat` : `message`, `conversationId?`, `model?`, `attachmentIds?`).
- **Pré-vol synchrone** (mêmes garanties que `/chat`) : quota (`assertWithinQuota`), validation du modèle (liste blanche), résolution/création de la conversation possédée, persistance du message **USER**, chargement de l'historique, résolution de la clé BYOK. Toute erreur ici → **réponse HTTP normale** (402/400/404/401), aucun flux ouvert.
- Puis réponse `200 text/event-stream` ; le backend appelle Claude avec `"stream": true` et émet, au fil de l'eau :
  - `event: token` / `data: {"text":"..."}` pour chaque delta de texte,
  - `event: done` / `data: {"conversationId":"...","messageId":"...","model":"..."}` à la fin.
- **À la fin du flux** : le message **ASSISTANT** complet est persisté et l'usage est comptabilisé (`recordUsage(input, output)`), exactement comme en non-streamé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Quota atteint | `402 quota_exceeded` (JSON) **avant** ouverture du flux ; aucun message persisté |
| Modèle hors liste blanche | `400 validation_error` avant flux |
| Conversation d'un autre utilisateur / inexistante | `404 not_found` avant flux (isolation `user_id`) |
| Non authentifié | `401` avant flux |
| Échec fournisseur **en cours** de flux | `event: error` / `data: {"error":"provider_error"}` puis clôture ; **aucun** message ASSISTANT persisté, **aucun** usage compté |
| Fournisseur non configuré | `event: error` (`provider_unavailable`) |

---

## Critères d'acceptation

- [ ] `POST /api/chat/stream` renvoie `Content-Type: text/event-stream` et une suite d'événements `token` puis un `done` final portant `conversationId`/`messageId`.
- [ ] Après un flux nominal, le message **ASSISTANT** est persisté (contenu = concaténation des deltas) et `quotaService.recordUsage` est appelé une fois avec les tokens du fournisseur.
- [ ] Quota atteint → `402` JSON **sans** ouvrir de flux ni persister (vérifié par test).
- [ ] Isolation : streamer sur une conversation d'un autre utilisateur → `404`, aucun accès (filtre `user_id`).
- [ ] Un échec fournisseur en cours de flux émet `event: error` et **ne persiste pas** de message ASSISTANT ni d'usage.
- [ ] L'endpoint non-streamé `POST /chat` et ses tests restent **inchangés** (non-régression).
- [ ] Aucune clé (plateforme ou BYOK) n'est journalisée.

---

## Périmètre

### Hors scope (explicite)

- **Consommation frontend** du flux (SF-02-05).
- Rendu Markdown (SF-02-03, livré).
- Streaming des pièces jointes / de l'upload (F-04 inchangé).
- Reprise/reconnexion de flux interrompu (best-effort clôture uniquement).

---

## Préoccupation transversale — Auth / Plans-limites (analyse d'impact)

Réutilise l'auth JWT et le contexte tenant existants ; ajoute un endpoint consommant **quota** et **BYOK**. Composants recensés et vérifiés :

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `SecurityConfig` | `/chat/**` déjà authentifié (aucun matcher public ajouté) | Le nouvel endpoint hérite de `anyRequest().authenticated()` |
| `CurrentUser` / isolation `user_id` | Réutilisé tel quel ; conversation résolue par `findByIdAndUserId` | Test isolation 404 |
| `QuotaService` | `assertWithinQuota` (pré-vol) + `recordUsage` (fin de flux) | Test quota 402 ; usage compté une fois |
| `ByokKeyService` | Clé BYOK résolue au pré-vol, passée au provider | Inchangé (même chemin que `/chat`) |
| `AIProvider` (interface) | **Nouvelle méthode** `streamComplete(request, onDelta)` → Provider Independence conservée (callback neutre `Consumer<String>`) | `AnthropicProvider` implémente ; aucun couplage domaine↔Anthropic |
| `GlobalExceptionHandler` | Les erreurs de **pré-vol** y transitent (402/400/404) ; les erreurs **en flux** sont émises en `event: error` | Testé |

---

## Technique

### Endpoints

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| POST | `/api/chat/stream` | Oui (JWT) | Streaming SSE de la réponse |

> Choix **POST + SseEmitter** (pas `EventSource`, limité au GET) : le corps porte `message`/`conversationId`. Le frontend (SF-02-05) consommera via `fetch` + `ReadableStream`.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| conversations | SELECT/INSERT/UPDATE | inchangé (réutilise `ConversationService`/repo) |
| messages | INSERT | USER au pré-vol, ASSISTANT en fin de flux |
| usage_counters | UPDATE | via `QuotaService.recordUsage` (inchangé) |

### Migration Liquibase

- [x] Non applicable (aucun changement de schéma).

### Composants backend

- `ai/AIProvider` — ajout `ChatCompletionResult streamComplete(ChatCompletionRequest request, java.util.function.Consumer<String> onDelta)` : émet les deltas via `onDelta`, renvoie le résultat complet (texte concaténé + usage) une fois le flux terminé.
- `ai/AnthropicProvider` — implémente `streamComplete` : `POST /v1/messages` avec `stream:true`, lecture du corps en flux (`RestClient ... .exchange(...)`), parsing SSE Anthropic (`content_block_delta`→`text_delta`→`onDelta`), usage depuis `message_start`/`message_delta`.
- `chat/ChatService` — `prepareStream(...)` (pré-vol synchrone → `StreamContext`) et `runStream(StreamContext, SseEmitter)` (relais + persistance/usage en fin). `reply(...)` **inchangé**.
- `chat/ChatController` — `@PostMapping("/stream", produces=TEXT_EVENT_STREAM_VALUE)` : pré-vol, crée le `SseEmitter`, délègue le relais à un exécuteur dédié.
- Exécuteur SSE borné (`ThreadPoolTaskExecutor` ou `@Async`) pour ne pas bloquer le pool servlet.

### Événements SSE

| event | data (JSON) | quand |
|-------|-------------|-------|
| `token` | `{"text":"<delta>"}` | à chaque delta de texte |
| `done` | `{"conversationId","messageId","model"}` | fin nominale (après persistance) |
| `error` | `{"error":"provider_error\|provider_unavailable"}` | échec en cours de flux |

---

## Plan de test

### Tests unitaires

- [ ] `AnthropicProviderStreamTest` — un corps SSE simulé (`content_block_delta`×N + `message_delta` usage) produit les bons deltas via `onDelta` et un `ChatCompletionResult` (texte concaténé + tokens) ; une erreur HTTP amont lève `AIProviderException`.
- [ ] `ChatServiceStreamTest` — `prepareStream` applique quota/modèle/isolation et persiste le USER ; `runStream` persiste l'ASSISTANT (texte = deltas) et appelle `recordUsage` une fois ; un provider en échec ne persiste pas d'ASSISTANT ni d'usage.

### Tests d'intégration

- [ ] `POST /api/chat/stream` (provider mocké) → `200 text/event-stream`, contient `event: token` puis `event: done`.
- [ ] Quota atteint → `402` JSON, aucun flux.
- [ ] Conversation d'un autre utilisateur → `404`.
- [ ] Non authentifié → `401`.
- [ ] Non-régression : les tests de `POST /chat` (non-streamé) passent inchangés.

### Isolation utilisateur

- [x] Conversation résolue par `findByIdAndUserId` ; test cross-user 404.

---

## Dépendances

### Subfeatures bloquantes

- `SF-02-01` (proxy backend), `SF-02-02` (écran chat), `F-10` (quota) — Done.

### Questions ouvertes impactées

- Aucune (OQ-04 modèle déjà contournée par liste blanche).

---

## Notes et décisions

- **Provider-First** : on relaie le streaming natif de Claude (`stream:true`), on ne construit aucun moteur de génération. `AIProvider.streamComplete` reste neutre (callback `Consumer<String>`), aucune fuite d'Anthropic dans le domaine (Provider Independence).
- **Persistance/facturation en fin de flux** : le message ASSISTANT et l'usage ne sont écrits qu'au terme du flux réussi (usage fourni par `message_delta`). Un flux interrompu ne persiste rien → pas de demi-message ni de facturation partielle.
- **Pré-vol synchrone** : quota/modèle/isolation validés avant d'ouvrir le flux pour conserver des codes HTTP corrects (402/400/404), au lieu d'un `event: error` tardif.
- **Compat** : `POST /chat` conservé ; le frontend basculera vers `/chat/stream` en SF-02-05, ce qui permet un rollback trivial.
