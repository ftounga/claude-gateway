# Mini-spec — [F-02 / SF-02-05] Streaming des réponses de chat — frontend

## Identifiant

`F-02 / SF-02-05`

## Feature parente

`F-02` — Chat proxy Claude

## Statut

`ready`

## Date de création

2026-07-03

## Branche Git

`feat/SF-02-05-streaming-frontend`

---

## Objectif

Consommer le flux SSE de `POST /api/chat/stream` (SF-02-04) dans l'écran de chat pour afficher la
réponse de l'assistant **token-par-token** (rendu progressif), au lieu de l'apparition en un bloc.

## Comportement attendu

### Cas nominal

- À l'envoi, le composant affiche le message **USER** (optimiste) et un **placeholder ASSISTANT** vide.
- Il appelle `ChatService.streamMessage(body, handlers)` qui consomme le SSE via `fetch` +
  `ReadableStream` (EventSource ne supporte pas POST ; le JWT est ajouté en en-tête `Authorization`).
- Chaque `event: token` **concatène** le fragment au placeholder assistant (rendu Markdown progressif,
  cf. SF-02-03).
- `event: done` finalise : id réel du message, `conversationId`, rechargement de la liste (nouvelle
  conversation) ou réordonnancement, `submitting=false`, pièces jointes réinitialisées.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Échec HTTP de pré-vol (402/400/404/401) ou réseau | `onError` : retire le placeholder assistant **et** le message USER (rien persisté côté serveur), toast d'erreur |
| `event: error` en cours de flux | `onError` : retire le placeholder assistant, conserve le message USER si des tokens ont été reçus, toast d'erreur |

---

## Critères d'acceptation

- [ ] Une réponse assistant s'affiche **progressivement** : plusieurs `token` concatènent le contenu du même message.
- [ ] `done` fixe l'id réel du message, met à jour `conversationId` et recharge/reordonne la liste.
- [ ] Le corps envoyé inclut `attachmentIds` uniquement si des pièces jointes prêtes existent.
- [ ] Échec avant tout token → les deux messages optimistes sont retirés ; `submitting=false` ; toast d'erreur.
- [ ] Le rendu Markdown (SF-02-03) s'applique au contenu progressif de l'assistant.
- [ ] Le frontend ne parle qu'à `/api/chat/stream` (jamais au fournisseur) ; le JWT porte l'identité.

---

## Périmètre

### Hors scope

- Endpoint backend (SF-02-04, livré). Bouton « stop » d'interruption de flux. Reconnexion de flux.

---

## Préoccupation transversale — Navigation / routing

Aucune (modification interne à l'écran chat + service ; aucune route, guard ou tenant modifié).

---

## Technique

### Composants / services

- `core/services/chat.service.ts` — `streamMessage(body, { onToken, onDone, onError })` : `fetch` SSE,
  parsing `event:`/`data:`, callbacks. `sendMessage` (non-streamé) conservé.
- `chat/chat.component.ts` — `send()` bascule sur `streamMessage` ; placeholder assistant alimenté au fil
  de l'eau ; callbacks encapsulés dans `NgZone.run` (fetch hors zone Angular → détection de changement).

### Endpoints / Tables

- Consomme `POST /api/chat/stream` (SF-02-04). Aucune table, aucune migration.

---

## Plan de test

### Tests de composant (Jasmine)

- [ ] Envoi nominal : `streamMessage` piloté (spy) émet `token`+`done` → message assistant avec le contenu, id réel, `conversationId` mis à jour, liste rechargée.
- [ ] **Rendu progressif** : deux `token` successifs concatènent le contenu du même message assistant.
- [ ] Échec (`onError`) avant token → messages optimistes retirés, `submitting=false`.
- [ ] `attachmentIds` transmis dans le corps si pièce jointe prête ; absent sinon.

### Isolation utilisateur

- [x] Non applicable (aucun accès données ; identité via JWT, isolation backend inchangée).

---

## Dépendances

- `SF-02-04` (endpoint streaming) — Done. `SF-02-03` (rendu Markdown) — Done.

## Notes et décisions

- **`fetch` + `ReadableStream`** plutôt qu'`EventSource` : le flux nécessite un POST avec corps JSON.
- **`NgZone.run`** autour des callbacks : `fetch`/lecture de flux peuvent sortir de la zone Angular ; on
  garantit la détection de changement des signaux.
- **`sendMessage` non-streamé conservé** : rollback trivial (le composant peut y revenir).
