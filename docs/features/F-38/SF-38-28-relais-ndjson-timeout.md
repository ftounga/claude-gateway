# Mini-spec — [F-38 / SF-38-28] Relais NDJSON : délai async aligné sur l'outil + erreur propre sur le flux

---

## Identifiant

`F-38 / SF-38-28`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local) — relais inter-pods (SF-38-12/13)

## Statut

`done` (PR #678, mergée le 2026-09-18)

## Date de création

2026-09-18

## Branche Git

`feat/SF-38-28-relais-ndjson-timeout`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire en sorte qu'un appel d'outil relayé qui dure longtemps (gros tour) ne soit plus tué prématurément par le délai async par défaut de Spring, et qu'une erreur survenant sur le flux `application/x-ndjson` soit rendue proprement — jamais comme une `HttpMessageNotWritableException`/stack brute.

---

## Contexte — défaut observé et tracé en production (2026-09-18, ~14:04–14:06 UTC)

```
WARN AsyncRequestTimeoutException
WARN Failure in @ExceptionHandler ... handleUnexpected
     HttpMessageNotWritableException: No converter for [ErrorResponse] with preset Content-Type 'application/x-ndjson'
```

Deux défauts cumulés sur `POST /internal/runner/call` (`RunnerRelayController`) :

1. **Aucun délai async explicite** : l'endpoint rend un `StreamingResponseBody` sans fixer de timeout async. Il retombe sur le défaut (court) du conteneur (Tomcat : 30 s). Or `RunnerCallDispatcher.call` attend `timeoutMs + grâce (5 s)` — jusqu'à **900 000 ms** pour certains outils Teams, 120 000 ms pour `bash`. Un appel plus long que le défaut async est tué par Spring → `AsyncRequestTimeoutException`. Le chat SSE, lui, fixe bien son propre délai via `SseEmitter(STREAM_TIMEOUT_MS)`.
2. **Le chemin d'erreur ne sait pas s'écrire sur un flux ndjson** : quand l'exception survient, `GlobalExceptionHandler#handleUnexpected` tente de sérialiser un `ErrorResponse` alors que la réponse est déjà engagée en `application/x-ndjson` → `HttpMessageNotWritableException`.

---

## Comportement attendu

### Cas nominal (inchangé)

Un appel relayé qui répond dans son délai d'outil rend, comme aujourd'hui : zéro à N lignes `stream`, puis une ligne `result` terminale. Aucun changement de comportement pour le chat SSE ni pour les gestes JSON du relais (`/cancel`, `/control`, `/confirm`).

### Cas d'erreur

| Situation | Comportement attendu | Rendu |
|-----------|---------------------|-------|
| Outil long (ex. `bash` de 2 min, Teams jusqu'à 15 min) | Le délai async HTTP est fixé à `timeoutMs + marge` (≥ délai d'outil) → l'appel n'est **plus** interrompu avant que le dispatcher rende son issue (timeout d'outil inclus, `runner_timeout`) | ligne `result` NDJSON normale |
| Le dispatcher ne répond pas dans `timeoutMs + grâce` | Le dispatcher rend déjà `runner_timeout` (inchangé) ; l'async HTTP, désormais plus large, ne le devance plus | ligne `result` NDJSON (`ok=false`, `runner_timeout`) |
| Exception inattendue pendant l'écriture du flux (client parti, sérialisation) | Attrapée dans le corps du `StreamingResponseBody` → tentative best-effort d'une ligne `result` terminale d'erreur, puis silence ; **jamais** de propagation vers `@ExceptionHandler` | ligne `result` NDJSON d'erreur, ou clôture nette |
| `AsyncRequestTimeoutException` sur un flux déjà engagé en ndjson (filet de sécurité) | Handler dédié : ne tente **pas** d'écrire un `ErrorResponse` objet quand la réponse est engagée / de type `application/x-ndjson` → clôture nette, **plus** de `HttpMessageNotWritableException` | clôture nette (aucune stack) |
| `AsyncRequestTimeoutException` sur un endpoint JSON classique (réponse non engagée) | Réponse d'erreur normale | `503 request_timeout` (JSON) |

---

## Critères d'acceptation

- [ ] Le délai async de `POST /internal/runner/call` est fixé **par requête** à `request.timeoutMs() + marge` (marge couvrant la grâce dispatcher de 5 s + sécurité).
- [ ] Un appel dont le traitement dépasse le délai async **par défaut** du conteneur n'est plus interrompu prématurément (test : défaut global court + dispatcher lent → issue rendue quand même).
- [ ] Une exception survenant dans le corps du flux ndjson ne produit **jamais** de `HttpMessageNotWritableException` ni d'`ErrorResponse` objet écrit sur `application/x-ndjson` ; elle produit une fin de flux propre (ligne `result` d'erreur ou clôture nette).
- [ ] Le chat SSE (`/chat/stream`, `SseEmitter` avec son propre délai) et les autres endpoints ne changent pas de comportement (aucune configuration async globale modifiée).
- [ ] Les tests relais/chat existants restent verts.

---

## Périmètre

### Hors scope (explicite)

- Le délai de lecture côté **pod appelant** (`RunnerRelayProperties.readTimeoutMs`, 135 s) n'est pas modifié — il concerne le client du relais, pas la cause tracée (côté récepteur). (Observation : pour les outils Teams > 135 s via relais multi-pods, ce délai mériterait un réexamen — hors périmètre de ce correctif ciblé.)
- `AtelierRelayController#turn-stream` (F-84) n'est pas retouché ; il bénéficie néanmoins du handler `AsyncRequestTimeoutException` durci (plus de stack sur ndjson).
- Aucun changement d'UI : la Forge sait déjà afficher une ligne `result` d'erreur / un événement SSE d'erreur. Pas de subfeature frontend requise.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Notes |
|---------|-----|------|-------|
| POST | `/internal/runner/call` (connecteur interne 8081, secret partagé) | secret relais | rend `application/x-ndjson` |

### Tables impactées

Aucune. Pas de migration Liquibase.

### Composants impactés (préoccupation transversale — anti-régression)

- `runner/relay/RunnerRelayController.java` — fixe le délai async par requête ; durcit le corps du `StreamingResponseBody` (catch → ligne terminale, jamais de propagation).
- `shared/error/GlobalExceptionHandler.java` — nouveau `@ExceptionHandler(AsyncRequestTimeoutException.class)` : ne sérialise pas d'`ErrorResponse` sur un flux engagé/ndjson. **Handlers F-128 existants inchangés** (méthodes distinctes).
- Vérifiés inchangés : chat SSE (`ChatController`, délai porté par `SseEmitter`), gestes JSON du relais (`/cancel`, `/control`, `/confirm`), `AtelierRelayController` (aucune modification ; bénéficie du handler durci).

> Préoccupation transversale cochée : **oui (chemin d'erreur global + async)**. Liste des composants impactés fournie ci-dessus → conforme à la règle anti-régression.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

---

## Plan de test

### Tests d'intégration (harnais réel : connecteur de relais, `StreamingResponseBody`, lecture ligne à ligne — cf. `RunnerRelayStreamIntegrationTest`)

- [ ] **Délai async aligné** : `spring.mvc.async.request-timeout` fixé court (ex. 800 ms) globalement + dispatcher lent (~1,5 s) + `timeoutMs=30 000` → l'issue est rendue quand même (`result.ok()==true`). Sans l'override par requête, le défaut global tuerait l'appel : ce test garde l'override.
- [ ] **Erreur propre sur ndjson** : dispatcher qui lève une `RuntimeException` → le client reçoit une ligne `result` terminale d'erreur (code `runner_protocol_error`, message dédié), **jamais** un flux coupé/`HttpMessageNotWritableException`.

### Tests unitaires

- [ ] `GlobalExceptionHandler#handleAsyncTimeout` : réponse dont le `Content-Type` est `application/x-ndjson` (ou déjà engagée) → retourne `null` (rien n'est écrit, pas d'`ErrorResponse`). Réponse non engagée / JSON → `503 request_timeout`.

### Isolation utilisateur

- [x] Non applicable — endpoint interne de relais entre pods, authentifié par secret partagé ; il ne porte aucune identité utilisateur (l'appartenance est vérifiée par le pod appelant). Aucun accès aux données par `user_id` n'est ajouté ni modifié.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-12` (relais inter-pods) — done
- `SF-38-13` (flux relayé non bufferisé) — done

### Questions ouvertes impactées

- [ ] Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Voie retenue : per-endpoint** (recommandée par le cahier des charges). Le délai async est fixé **par requête** à `timeoutMs + marge` via un override de l'`AsyncWebRequest` du `WebAsyncManager` de la requête. Écartée : `spring.mvc.async.request-timeout` **global** — il faudrait le porter à ≥ 900 s (plus grand `timeoutMs` d'outil Teams), ce qui relèverait le plafond de **tous** les endpoints async et n'est pas ciblé.
- **Marge = 15 000 ms** = grâce dispatcher (5 s, `DEFAULT_GRACE_MS`) + 10 s de sécurité. Même raisonnement que `RunnerRelayProperties.readTimeoutMs` (120 000 + 15 000 pour `bash`). Ainsi le `runner_timeout` du dispatcher (à `timeoutMs + 5 s`) est **toujours** rendu avant la coupe async (à `timeoutMs + 15 s`).
- **Drapeau** : le délai async du relais n'est pas configurable ; il se déduit du `timeoutMs` de chaque appel, lui-même déjà borné par `RunnerToolGateway`. Aucune nouvelle clé de configuration.
- Le durcissement du chemin d'erreur bénéficie aussi à `turn-stream` (F-84), autre endpoint ndjson, sans le modifier.
