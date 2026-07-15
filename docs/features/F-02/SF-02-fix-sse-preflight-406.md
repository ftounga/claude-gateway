# SF-02 (fix) — Erreurs de pré-vol émises dans le flux SSE du chat (plus de 406)

Feature parente : **F-02 — Chat Hosted (streaming)**
Type : Backend (SSE) + Frontend (mapping d'erreur) — préoccupation transversale : **aucune** (endpoint existant)
Statut : En cours

## Problème

`POST /api/chat/stream` déclare `produces=text/event-stream`. Le pré-vol `chatService.prepareStream(...)` s'exécute **synchronement** dans la méthode du contrôleur ; s'il lève une exception (quota, modèle non supporté, conversation/pièce jointe/document non possédé ou non prêt), l'`@ExceptionHandler` renvoie du **JSON**, mais la négociation de contenu est verrouillée sur `text/event-stream` → **`HttpMediaTypeNotAcceptableException` (406)**. L'utilisateur voit une erreur technique cassée au lieu d'un message clair (ex. « quota atteint »). Même défaut que celui déjà corrigé pour l'Atelier (SF-28-10).

## Correctif

- **Backend** : déplacer le pré-vol dans le thread SSE (`chatStreamExecutor`) et **émettre les erreurs comme événements SSE** (`error: <code>`), jamais via l'`@ExceptionHandler`. Mapping : `QuotaExceededException`→`quota_exceeded` ; `UnsupportedModelException`→`unsupported_model` ; `ConversationNotFoundException`/`AttachmentNotFoundException`/`DocumentNotFoundException`→`not_found` ; `DocumentNotReadyException`→`document_not_ready` ; autre `RuntimeException`→`internal_error`. Le relais de streaming (provider) conserve son mapping existant (`provider_unavailable`/`provider_error`).
- **Frontend** : `chat.component` `onError(code)` mappe le code en message lisible (quota, modèle, doc non prêt…) au lieu d'un message générique unique.

## Comportement / cas d'erreur

| Situation | Avant | Après |
|---|---|---|
| Quota épuisé | 406 cassé | flux SSE `error: quota_exceeded` → « Quota de consommation atteint… » |
| Modèle hors liste | 406 | `error: unsupported_model` → message dédié |
| Conversation/pièce jointe/doc non possédé | 406 | `error: not_found` → message dédié |
| Document sans texte | 406 | `error: document_not_ready` → message dédié |

## Critères d'acceptation

- CA1 : un pré-vol qui lève `QuotaExceededException` produit un flux SSE **200** contenant `event:error data:{"error":"quota_exceeded"}` — **jamais** un 406.
- CA2 : le message USER optimiste n'est pas persisté quand le pré-vol échoue (comportement inchangé : `prepareStream` échoue avant persistance, ou rien n'est streamé).
- CA3 : le streaming nominal (token→done) est inchangé (non-régression).
- CA4 : le frontend affiche un message spécifique selon le code (au minimum `quota_exceeded` distinct du générique).
- CA5 : isolation `user_id` inchangée (`prepareStream` prend `userId` résolu sur le thread requête).

## Plan de test minimal

- **Backend** (`ChatApiIntegrationTest`, MockMvc async + exécuteur `Runnable::run` en test) : un utilisateur au quota épuisé → `POST /chat/stream` renvoie un flux `text/event-stream` avec `error: quota_exceeded` (statut 200), et **pas** de 406 ; cas nominal (token+done) inchangé.
- **Frontend** (`chat.component.spec`) : `onError('quota_exceeded')` → message quota ; `onError('provider_error')` → message générique/fournisseur ; non-régression du flux nominal.

## Composants impactés

- `chat/ChatController.java` : `stream(...)` délègue tout au `chatStreamExecutor` ; nouvelle méthode de relais avec pré-vol `try/catch` → SSE `error`. Réutilise `sendError`/`relay` existants.
- `chat/chat.component.ts` : `onError(code)` + `mapStreamError(code)`.
- **Aucune table, aucune migration, aucun changement de contrat** (l'endpoint et les événements SSE `token`/`done`/`error` sont inchangés ; seuls de nouveaux **codes** d'erreur apparaissent).

## Hors périmètre

- Refonte des messages d'erreur des autres écrans.
