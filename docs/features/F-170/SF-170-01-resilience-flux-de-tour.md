# Mini-spec — F-170 / SF-170-01 — Résilience du flux de tour au proxy corporate

## Identifiant

`F-170 / SF-170-01`

## Feature parente

`F-170` — Résilience du flux au proxy corporate

## Statut

`ready`

## Date de création

2026-10-01

## Branche Git

`feat/SF-170-01-resilience-flux-proxy`

---

## Objectif

Garder le flux SSE d'un tour vivant à travers un proxy d'entreprise (heartbeat périodique) et traiter
proprement la coupure client, pour que ni le proxy ni le gestionnaire d'erreur ne tronquent le tour ou
ne produisent de cascade d'exceptions.

---

## Comportement attendu

### Cas nominal

1. Un tour s'ouvre (`LiveTurn`) sur ce pod ; son flux SSE (`/stream` et/ou `/attach`) est branché.
2. Un **battement de cœur** (`TurnHeartbeat`, planificateur unique du pod) parcourt à intervalle fixe
   (défaut 15 s, env `APP_ATELIER_STREAM_HEARTBEAT`, en ms) **tous les tours vivants** du pod et, pour
   chacun, émet à ses spectateurs SSE un **commentaire SSE** (`: ping`).
3. Le commentaire garde la connexion TCP vivante pendant les phases **sans événement** (réflexion
   modèle, outil long) — le proxy ne voit plus une connexion inactive.
4. Le heartbeat **n'entre pas** dans le tampon du tour, **ne consomme aucun numéro d'ordre** (curseur
   inchangé), **n'est pas persisté** et **n'est pas rendu** à l'utilisateur (le client SSE ignore
   nativement les lignes `:`).
5. À la **fin du tour** (`LiveTurn.finish()` → `live()==false`), le battement cesse de pinguer ce tour
   (il n'est plus dans la liste des tours vivants).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Spectateur SSE parti (navigateur fermé, proxy a coupé) lors d'un battement | Le spectateur est **détaché** du tour (`heartbeat()` rend `false`), le tour **continue** sans lui ; aucun log d'erreur | — (flux) |
| Client déconnecté en cours de flux (`AsyncRequestNotUsableException`) remonté au handler global | Log **debug** « client déconnecté », **aucune** écriture d'`ErrorResponse` (retour `null`) | — (flux déjà engagé) |
| `HttpMessageNotWritableException` sur une réponse **déjà engagée** ou de type `application/x-ndjson` / `text/event-stream` | Log **warn** « écriture impossible sur un flux déjà engagé », retour `null` — **fin de la cascade `No converter`** | — (flux) |
| `HttpMessageNotWritableException` sur une réponse JSON **non engagée** (vraie panne de sérialisation) | `internal_error` normal | 500 |
| `APP_ATELIER_STREAM_HEARTBEAT` ≤ 0 | Battement **désactivé** (aucun planificateur armé) ; comportement d'avant F-170 | — |

---

## Critères d'acceptation

- [ ] Le battement émet un **commentaire SSE** aux spectateurs d'un tour vivant à l'intervalle
      configuré, pendant toute la durée du tour (y compris les phases sans événement).
- [ ] Le battement **n'avance pas** le curseur du tour (`LiveTurn.cursor()` inchangé après un
      `heartbeat()`) et **n'ajoute aucun** événement au tampon.
- [ ] Le battement n'émet **rien de persistable** ni de visible : c'est un commentaire SSE, et les
      parseurs SSE du frontend l'ignorent (vérifié par lecture : `if (!data) return;`).
- [ ] Un spectateur dont l'émetteur est mort est **détaché** lors d'un battement, et le tour
      **continue** (`live()==true`).
- [ ] Un tour **terminé** ne reçoit plus de battement (il quitte la liste des tours vivants).
- [ ] L'intervalle est **réglable par env** (`APP_ATELIER_STREAM_HEARTBEAT`, ms, défaut 15000) ; une
      valeur ≤ 0 **désactive** le battement sans erreur.
- [ ] `AsyncRequestNotUsableException` au handler global → **log discret**, **aucun** `ErrorResponse`
      écrit (retour `null`) : plus de « client déconnecté » traité comme panne.
- [ ] `HttpMessageNotWritableException` sur flux engagé / ndjson / event-stream → **retour `null`**,
      plus de cascade `No converter for [ErrorResponse] ... application/x-ndjson` ; sur JSON non engagé,
      `internal_error` 500 préservé.
- [ ] **Isolation inchangée** : le battement parcourt les tours vivants du pod par `LiveTurn` (clé
      `(userId, workspaceId)` interne) et n'expose ni ne mélange aucune donnée entre utilisateurs ; il
      ne lit aucun payload.

---

## Plan de test minimal

### Unitaires

- `LiveTurnTest` (ajouts) :
  - `heartbeat()` appelle `heartbeat()` sur chaque spectateur vivant, **sans** changer le curseur ni
    le nombre d'événements du tampon.
  - un spectateur dont `heartbeat()` rend `false` est **détaché** ; le tour reste `live`.
  - un tour **terminé** : `heartbeat()` est un no-op (aucun spectateur notifié).
- `SseTurnSubscriberTest` (nouveau) :
  - `heartbeat()` envoie un **commentaire** sur l'émetteur et rend `true` ;
  - sur un émetteur clos / en échec, `heartbeat()` rend `false` **sans** lever d'exception.
- `TurnHeartbeatTest` (nouveau) :
  - `beat()` pingue **tous** les tours vivants du registre (via un spectateur de test) ;
  - un tour terminé n'est pas pingué ;
  - intervalle ≤ 0 → désactivé (le planificateur n'est pas armé).
- `GlobalExceptionHandlerClientDisconnectTest` (nouveau) :
  - `AsyncRequestNotUsableException` → retour `null` ;
  - `HttpMessageNotWritableException` sur réponse `application/x-ndjson` / `text/event-stream` /
    committée → `null` ;
  - `HttpMessageNotWritableException` sur réponse JSON non engagée → `500 internal_error`.

### Intégration

- `TurnHeartbeat` est un `@Component` chargé par le contexte (vérifié indirectement par le démarrage
  du contexte dans la suite d'intégration existante).

### Isolation utilisateur

- Le battement ne prend aucun `userId` en entrée et ne lit aucun payload ; il opère sur les objets
  `LiveTurn` déjà clés par `(userId, workspaceId)`. Test `TurnHeartbeatTest` : deux tours de deux
  utilisateurs différents sont tous deux pingués **sur leur propre spectateur** (aucun croisement).

---

## Préoccupations transversales

### Streaming / flux + gestion d'erreur — **COCHÉE**

Composants impactés (listés et vérifiés) :

- `fr.claudegateway.atelier.live.TurnSubscriber` — ajout d'une méthode `heartbeat()` **par défaut**
  (`return true;`) : aucun implémenteur existant ne casse.
- `fr.claudegateway.atelier.live.SseTurnSubscriber` — override `heartbeat()` : envoie un commentaire SSE.
- `fr.claudegateway.atelier.live.WindowedTurnSubscriber` — override `heartbeat()` : délègue au
  spectateur enveloppé, rend `false` si la fenêtre est close (détachement cohérent).
- `fr.claudegateway.runner.relay.NdjsonTurnSubscriber` — **non modifié** : garde le `heartbeat()` par
  défaut (no-op), car il a déjà son propre `ping` (20 s) dans `awaitFinish`. Pas de double-ping.
- `fr.claudegateway.atelier.live.LiveTurn` — ajout `heartbeat()` : sous le verrou du tour, pingue les
  spectateurs vivants, détache ceux qui échouent, **sans** toucher au tampon ni au curseur.
- `fr.claudegateway.atelier.live.LiveTurnRegistry` — ajout `liveTurns()` (liste des tours vivants du
  pod) ; **aucun changement de constructeur** (les tests existants `new LiveTurnRegistry(objectMapper)`
  restent valides).
- `fr.claudegateway.atelier.live.TurnHeartbeat` — **nouveau** `@Component` : planificateur unique
  (thread démon), injecte `LiveTurnRegistry` + l'intervalle (`@Value`), pingue périodiquement.
- `fr.claudegateway.shared.error.GlobalExceptionHandler` — deux handlers ajoutés
  (`AsyncRequestNotUsableException`, `HttpMessageNotWritableException`) ; cohérents avec le handler
  `AsyncRequestTimeoutException` déjà présent (même logique « ne jamais écrire d'ErrorResponse objet sur
  un flux engagé / ndjson »).
- **Non touché** : `AtelierChatController` (le battement est centralisé au niveau du registre, pas au
  niveau de la requête — les tests `new AtelierChatController(...)` restent valides), `LiveTurn.publish`,
  le compteur d'événements, la persistance (`atelier_messages`), l'isolation.

---

## Tables / endpoints / composants impactés

- **Tables** : aucune. Pas de migration Liquibase.
- **Endpoints** : aucun nouvel endpoint ; comportement de flux de `POST /workspaces/{id}/chat/stream`
  et `GET /workspaces/{id}/chat/attach` rendu résilient (heartbeat sur leurs spectateurs). Contrat
  inchangé (le commentaire SSE est transparent).
- **Config** : `app.atelier.stream.heartbeat` (ms) ← env `APP_ATELIER_STREAM_HEARTBEAT` (défaut 15000),
  déclarée dans `application.yml` **et** `k8s/base/backend/configmap.yaml`.
- **Frontend** : aucun changement (parseurs SSE tolèrent déjà les commentaires — vérifié).

---

## Contraintes de validation

- `APP_ATELIER_STREAM_HEARTBEAT` : entier **millisecondes**, défaut `15000`. ≤ 0 → désactivé.
  Doit rester **strictement sous** le seuil de coupure d'inactivité du proxy (CAGIP/Netskope ;
  l'ingress et les proxys usuels coupent au-delà de ~30-60 s → 15 s donne une marge ≥ 2×).
- Le commentaire SSE est une ligne `:` sans `data:` : ignorée par `EventSource` natif **et** par les
  deux parseurs maison (`if (!data) return;`).

---

## Périmètre — hors scope (explicite)

- **PAS** le refactor « le tour survit à la coupure » (persistance du tour indépendante du flux /
  reprise transparente sans tronquage). Mitigation seule, choix PO.
- **PAS** de modification du rebranchement par fenêtres (`/attach?waitMs=`, F-84 / SF-84-04).
- **PAS** de modification du relais ndjson entre pods (`NdjsonTurnSubscriber`, ping 20 s déjà présent).
- **PAS** de changement d'isolation `user_id` / `workspace_id`.
- **PAS** de changement du catalogue d'outils, de la boucle modèle, ni du Gateway-First.
