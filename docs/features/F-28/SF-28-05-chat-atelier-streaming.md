# SF-28-05 — Chat Atelier en streaming (feedback tool-use live)

Feature parente : **F-28 — Atelier (Claude Code Lite)**
Type : Backend (SSE) + Frontend (Angular) — préoccupation transversale : **aucune** (endpoint additif, isolation inchangée)
Statut : En cours

## Objectif (une phrase)

Pendant que Claude lit/édite les fichiers du projet, l'utilisateur voit **en direct** les étapes (« lit `src/x` », « édite `README.md` », « recherche … ») et le commentaire de Claude, au lieu d'attendre plusieurs dizaines de secondes sur un spinner opaque — fidèle à l'expérience Claude Code.

## Comportement nominal

1. L'utilisateur envoie un message dans l'Atelier.
2. Le frontend ouvre `POST /api/workspaces/{id}/chat/stream` (SSE via `fetch`+`ReadableStream`, comme `/api/chat/stream`).
3. Le backend exécute la **même boucle tool-use** que SF-28-02 mais notifie chaque étape via un *listener* :
   - événement `action` `{ type: read|write|list|search, path? }` à chaque appel d'outil ;
   - événement `text` `{ text }` pour le commentaire de l'assistant d'un tour (le cas échéant) ;
   - événement `done` `{ reply, actions, messageId }` en fin (réponse finale + récap des actions) ;
   - événement `error` `{ error: <code> }` en cas d'échec.
4. L'UI affiche les étapes au fil de l'eau (puces d'action), puis remplace par la réponse finale rendue Markdown + blocs copiables (F-26), et rafraîchit l'arborescence.

## Cas d'erreur

| Situation | Événement SSE | UI |
|---|---|---|
| Quota atteint | `error: quota_exceeded` | message « Quota de consommation atteint… » |
| Workspace d'un autre user / inexistant | `error: workspace_not_found` | message « Projet introuvable. » |
| Fournisseur indisponible / erreur | `error: provider_unavailable` / `provider_error` | message dédié |
| Client déconnecté pendant le flux | (fermeture) | rien persisté au-delà ; pas de crash |
| Interne inattendu | `error: internal_error` | message générique |

> **Correction incluse** : les erreurs de pré-vol (quota, isolation) sont émises **dans le flux SSE** (émetteur ouvert → événement `error` → `complete`), **jamais** via `@ExceptionHandler` — ce qui évite le `HttpMediaTypeNotAcceptableException` observé quand un handler JSON rencontre un endpoint `produces=text/event-stream`.

## Critères d'acceptation (vérifiables)

- CA1 : le listener reçoit une notification **par appel d'outil**, dans l'ordre d'exécution, avec le bon `type`/`path`.
- CA2 : `chatStreaming(...)` renvoie **exactement** le même `AtelierChatResult` (reply, actions, messageId) et persiste le même échange que `chat(...)` — l'endpoint synchrone existant reste inchangé (non-régression).
- CA3 : une `QuotaExceededException` produit un événement SSE `error: quota_exceeded` (statut HTTP 200 côté flux, **pas** de 406/500).
- CA4 : l'isolation `user_id` est préservée : un workspace d'un autre utilisateur ⇒ `error: workspace_not_found`, aucun accès fichier.
- CA5 : côté frontend, à réception des événements, le fil affiche les étapes puis la réponse finale ; à `error`, un message lisible ; l'arborescence est rafraîchie après `done`.

## Plan de test minimal

- **Backend unitaires** (`AtelierChatServiceTest`) : `chatStreaming` avec un provider mocké enchaînant un `read_file` puis une réponse finale → listener notifié `action(read, path)` **avant** `done` ; résultat identique à `chat`. Cas quota (assert exception avant appel provider). Isolation : workspace non possédé ⇒ exception, provider jamais appelé.
- **Backend intégration** (`AtelierChatApiIntegrationTest` ou équivalent MockMvc async) : `POST /workspaces/{id}/chat/stream` renvoie un flux `text/event-stream` contenant `action` puis `done` ; quota épuisé ⇒ flux avec `error: quota_exceeded` (jamais 406).
- **Frontend** (`atelier.component.spec`, `atelier.service` mock) : `streamChat` invoque les handlers → le fil accumule les étapes puis la réponse finale ; `onError('quota_exceeded')` affiche un message ; arbre rafraîchi après `done`.
- **Isolation utilisateur** : couverte par CA4 (backend).

## Tables / endpoints / composants impactés

- **Backend** : `AtelierChatController` (+ `@PostMapping("/stream")` SSE, executor dédié) ; `AtelierChatService` (extraction de la boucle en `runLoop(..., AtelierProgressListener)` ; `chat()` délègue avec un listener neutre → **zéro régression** ; nouveau `chatStreaming(..., listener)`) ; nouvelle interface `AtelierProgressListener` + record d'événement. **Aucune table, aucune migration.**
- **Frontend** : `AtelierService.streamChat(id, message, handlers)` (fetch+SSE, calqué sur `ChatService.streamMessage`) ; `atelier.component` (fil live + finalisation) ; `atelier.models` (types d'événements).

## Hors périmètre

- Streaming **token-par-token** de la réponse finale (l'`AiAgentProvider` renvoie des tours discrets ; on relaie au niveau **étape** + commentaire de tour — honnête à l'architecture).
- Correction du même défaut sur `/api/chat/stream` (chat principal) → suivi séparé (petit fix transverse).
- Offre Gold / BYOK-Hosted (SF-28-06).

## Provider-First / Gateway-First

Le backend **orchestre** la boucle et **relaie** le raisonnement de Claude ; il ne réimplémente aucun moteur. `AiAgentProvider` conserve l'indépendance fournisseur. Traitement **asynchrone** (thread SSE dédié), conforme à la règle « pas d'IA synchrone bloquante ».
