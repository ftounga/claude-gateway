# Mini-spec — F-38 / SF-38-13 — Relais du flux, de l'annulation, de la porte et de l'interruption

---

## Identifiant

`F-38 / SF-38-13`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`done` — mergée le 2026-08-30 (PR #202)

## Date de création

2026-08-30

## Branche Git

`feat/SF-38-13-relais-flux-annulation-porte`

---

## Objectif

Faire traverser le relais interne (SF-38-12) aux **quatre gestes qui restaient pod-dépendants** — le
flux de sortie au fil de l'eau, l'annulation d'un appel, la décision de la porte de confirmation et
l'interruption d'un tour (y compris la marque F-32) — pour qu'un second replica ne fige plus le mode
`RUNNER` ni ne fasse refuser toute commande au bout de 120 s.

---

## Comportement attendu

### Cas nominal

**(1) Flux au fil de l'eau, de bout en bout.** Le pod A relaie un `bash` au pod B (SF-38-12). Chaque
`tool_stream` reçu par B devient **immédiatement** une ligne NDJSON `{"type":"stream","chunk":"…"}`
écrite et *flushée* ; A la lit **ligne à ligne** et la remet à `onChunk` — c'est-à-dire
`listener::onOutput`, donc l'événement SSE `output` de `AtelierChatController`. La sortie apparaît
dans l'écran **pendant** que la commande tourne, exactement comme en mono-pod. Le `streamed` rendu au
modèle vient **exclusivement** de la ligne `result` : aucune ré-agrégation locale, donc pas de double
affichage ni de double comptage.

**(2) Annulation.** `POST /api/internal/runner/cancel` (`{workspaceId, reason}`) →
`RunnerCallDispatcher.cancelWorkspace(workspaceId, reason)` sur le pod destinataire, réponse
`200 {"cancelled":N}`. Un pod qui n'héberge pas la socket rend `0` : ce n'est pas une erreur. Cette
route est appelée en **diffusion** (jamais dirigée) — sauf le cas particulier du *read timeout* du
relais, où l'émetteur envoie un `cancel` **dirigé** vers le même pod, best-effort. L'annulation ne
ferme jamais le flux NDJSON en cours : le runner tue son processus et émet quand même sa trame
terminale (contrat §2.5), le `result` part normalement.

**(3) Porte de confirmation.** `POST /api/internal/runner/confirm`
(`{userId, workspaceId, callId, allow, reason}`) — **diffusée**. Le pod destinataire tente
`RunnerConfirmationGate.resolve(...)` : `{"resolved":true}` s'il détenait la demande,
`{"resolved":false}` sinon (`NoPendingConfirmationException` attrapée). HTTP **toujours 200** :
l'absence de demande n'est pas une erreur de transport. Côté `AtelierChatService.confirmToolUse` :
`requireOwned` d'abord (404 sinon), tentative locale, puis diffusion, et 409 `no_pending_confirmation`
seulement si personne n'a résolu.

**(4) Interruption.** `POST /api/internal/atelier/interrupt`
(`{userId, workspaceId, reason}`) — **diffusée**. Trois gestes, dans l'ordre exact de
`AtelierChatService.interruptChat` : marque `interruptedTurns`, `confirmationGate.cancelWorkspace`,
`runnerCallDispatcher.cancelWorkspace`. Réponse `200 {"marked":true,"released":N,"cancelled":M}`.
L'émetteur applique d'abord les trois gestes **localement**, puis diffuse ; les échecs de diffusion
sont journalisés et n'empêchent pas le 204 au navigateur.

**(5) Interruption de session F-32.** `POST /api/internal/atelier/session-interrupt`
(`{sessionId, mark}`) — **diffusée**. `mark:true` ⇒ `interruptedSessions.add`, `mark:false` ⇒
`remove`. `AtelierSessionService.interruptSession` garde sa précaution : marque posée **avant** le
relais fournisseur (locale + diffusion `true`), retirée (locale + diffusion `false`) si le
fournisseur lève, puis relance de l'exception. `consumeInterrupted` est **inchangé**.

**Adressage.** `tool_call` est **dirigé** (adresse du registre) ; `cancel`, `confirm`, `interrupt` et
`session-interrupt` sont **diffusés** à tous les pairs résolus par DNS sur le Service headless
`claude-gateway-backend-internal` (`InetAddress.getAllByName`, sa propre `POD_IP` retirée), en
parallèle sur un exécuteur borné à 4 threads. Motif : la porte et la marque d'interruption vivent sur
le pod qui exécute `runLoop` et tient le `SseEmitter`, **pas** sur celui du registre — le navigateur
et le runner sont deux clients équilibrés séparément.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Relais désactivé (secret vide) ou registre `in-memory` | Aucune diffusion, aucun contrôleur interne : comportement strictement identique à avant SF-38-13 (409 `no_pending_confirmation`, interruption locale seule) | — |
| Enveloppe interne incomplète (`workspaceId`/`userId`/`callId`/`sessionId` absents) | 400, corps vide | 400 |
| Requête interne reçue sur le port public, ou sans secret valide | 404 / 401, corps vide (barrières SF-38-12, inchangées) | 404 / 401 |
| `confirm` diffusé et **aucun** pair ne détient la demande | `{"resolved":false}` partout ⇒ `NoPendingConfirmationException` relancée ⇒ 409 `no_pending_confirmation` | 409 |
| Pair injoignable pendant une diffusion (DNS, connexion refusée, timeout) | Échec **ignoré et journalisé** ; les autres pairs sont servis ; jamais de rejeu | — |
| Diffusion `confirm` partielle et un pair résout | Succès : 204 au navigateur (comportement actuel) | 204 |
| Personne n'atteint la porte qui attend | La porte expire à 120 s en `TIMEOUT` ⇒ **refus**. Le silence ne vaut jamais autorisation, y compris en multi-pod | — |
| Read timeout du relais d'appel (135 s) | `RUNNER_TIMEOUT` + `cancel` **dirigé** best-effort vers le même pod ; échec de ce `cancel` ignoré | — |
| Consommateur de flux (SSE) parti pendant un relais | Les fragments suivants sont abandonnés ; l'appel se poursuit et son `result` part normalement | — |
| Résolution DNS du Service headless en échec | Aucun pair, aucune diffusion, journalisé une fois ; comportement local inchangé | — |

---

## Critères d'acceptation

- [x] Un `bash` relayé remet ses fragments au consommateur **avant** la ligne `result` (preuve
      chronométrée : premier fragment observé nettement avant l'issue, aucune bufferisation).
- [x] Le `streamed` du modèle vient de la ligne `result`, jamais d'une ré-agrégation des fragments.
- [x] `POST /internal/runner/cancel` appelle `cancelWorkspace` et rend `{"cancelled":N}`, `0` inclus.
- [x] `POST /internal/runner/confirm` rend `{"resolved":true}` quand la porte locale détenait la
      demande, `{"resolved":false}` sinon, **toujours en 200**.
- [x] `confirmToolUse` : `requireOwned` d'abord ; local ; puis diffusion ; 409 seulement si personne
      n'a résolu.
- [x] `POST /internal/atelier/interrupt` applique les trois gestes dans l'ordre exact et rend
      `{"marked":true,"released":N,"cancelled":M}`.
- [x] `interruptChat` applique les trois gestes localement **puis** diffuse ; un échec de diffusion
      ne change pas la réponse.
- [x] `POST /internal/atelier/session-interrupt` pose/retire la marque F-32 ; `interruptSession`
      diffuse `true` avant le relais fournisseur et `false` si celui-ci lève, puis relance.
- [x] Le résolveur de pairs retire sa propre `POD_IP` de la liste des pairs.
- [x] Relais désactivé ⇒ aucune diffusion émise, aucun bean de contrôleur interne atelier.
- [x] Les nouvelles routes internes vivent dans `fr.claudegateway.runner.relay` (invariant T5 tenu).
- [x] Aucun secret, aucun `chunk`, aucun contenu de fichier journalisé.
- [x] Le contrat de messages runner est inchangé : aucun nouveau type de trame.

---

## Périmètre

### Hors scope (explicite)

- L'enveloppe NDJSON, l'authentification, le port et le connecteur interne : figés par SF-38-12.
- Toute route dirigée pour `tool_cancel` : la diffusion couvre le pod propriétaire.
- La fusion des deux clefs d'interruption (`userId:workspaceId` et `sessionId`) : deux routes, deux
  enveloppes, assumé.
- Toute migration Liquibase : aucune n'est requise.
- Tout déploiement (`kubectl apply`, `gh workflow run`) : manuel, après cette subfeature.
- Redis ou tout composant d'infra supplémentaire.

---

## Contraintes de validation

| Champ | Obligatoire | Format / valeurs | Normalisation |
|---|---|---|---|
| `workspaceId` | Oui (cancel, confirm, interrupt) | UUID | — |
| `userId` | Oui (confirm, interrupt) | UUID — **critère d'appartenance rejoué par la porte**, jamais une authentification | — |
| `callId` | Oui (confirm) | non vide, identifiant `tool_use` verbatim | `trim` côté appelant |
| `allow` | Oui (confirm) | booléen | — |
| `reason` | Non | ≤ 500 caractères (tronqué par la porte) | `trim`, vide ⇒ `null` |
| `sessionId` | Oui (session-interrupt) | non vide, identifiant fournisseur | — |
| `mark` | Oui (session-interrupt) | booléen | — |
| `X-Internal-Relay-Secret` | Oui | comparaison temps constant | jamais journalisé |

---

## Technique

### Endpoint(s)

| Méthode | URL | Port | Adressage | Auth |
|---|---|---|---|---|
| POST | `/api/internal/runner/cancel` | 8081 | diffusé (+ dirigé sur read timeout) | secret partagé |
| POST | `/api/internal/runner/confirm` | 8081 | diffusé | secret partagé |
| POST | `/api/internal/atelier/interrupt` | 8081 | diffusé | secret partagé |
| POST | `/api/internal/atelier/session-interrupt` | 8081 | diffusé | secret partagé |

Aucune route publique n'est ajoutée ni modifiée. `POST /atelier/workspaces/{id}/chat/interrupt`,
`.../chat/confirm` et `.../agent/interrupt` gardent leur contrat (204 / 409 / 404).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Oui
- [x] Non applicable

### Composants Angular

Aucun — le relais est interne à la gateway ; l'écran ne voit aucune différence.

### Manifests k8s

Aucun nouveau : `service-internal.yaml` (headless, `publishNotReadyAddresses: true`), `POD_IP` et
`APP_RUNNER_RELAY_PORT` sont livrés par SF-38-12 et suffisent à la diffusion.

### Préoccupations transversales

| Préoccupation | Concernée | Composants impactés vérifiés |
|---|---|---|
| Auth / Principal | Non | Aucune route utilisateur touchée ; le filtre du relais ne pose rien dans le `SecurityContext` (D9) |
| Contexte tenant | **Oui** | `AtelierChatService.interruptChat` / `confirmToolUse` (`requireOwned` conservé **en premier**), `AtelierSessionService.interruptSession` (`requireOwned` conservé), `RunnerConfirmationGate.resolve` (compare `userId` **et** `workspaceId`). Le `userId` d'une enveloppe interne n'authentifie rien : il est rejoué comme critère d'appartenance |
| Plans / limites | Non | Aucun quota ni gate de plan touché |
| Navigation / routing | Non | Aucune route Angular modifiée |

---

## Plan de test

### Tests unitaires

- [x] `RelayPeerResolver` — retire sa propre adresse de la liste des pairs ; DNS en échec ⇒ liste vide.
- [x] `RunnerRelayBroadcaster` — relais désactivé ⇒ aucun appel réseau, `confirm` rend `false`.
- [x] `RunnerRelayBroadcaster` — deux pairs, l'un répond `resolved:true` ⇒ `true` ; aucun pair ne
      résout ⇒ `false` ; pair injoignable ⇒ ignoré, l'autre est servi.
- [x] `RunnerRelayClient` — read timeout ⇒ `RUNNER_TIMEOUT` **et** `cancel` dirigé émis vers le pair.
- [x] `AtelierChatService.interruptChat` — les trois gestes locaux dans l'ordre, puis diffusion ; un
      échec de diffusion ne lève pas.
- [x] `AtelierChatService.confirmToolUse` — résolution locale ⇒ aucune diffusion ; pas de demande
      locale + pair qui résout ⇒ pas d'exception ; personne ⇒ `NoPendingConfirmationException`.
- [x] `AtelierSessionService.interruptSession` — diffusion `true` avant le relais ; diffusion `false`
      + relance si le fournisseur lève.

### Tests d'intégration

- [x] `/internal/runner/confirm` sur le port relais : demande en attente ⇒ `{"resolved":true}` et la
      porte rend `ALLOW` ; sans demande ⇒ 200 `{"resolved":false}`.
- [x] `/internal/runner/cancel` ⇒ 200 `{"cancelled":0}` sans runner local.
- [x] `/internal/atelier/interrupt` ⇒ 200 `{"marked":true,...}` ; `/internal/atelier/session-interrupt`
      ⇒ 200, marque posée puis retirée.
- [x] Les quatre routes sur le **port public** ⇒ 404 corps vide ; sur le port relais **sans secret**
      ⇒ 401 corps vide.
- [x] Flux bout en bout : un dispatcher qui émet deux fragments espacés puis l'issue ⇒ les fragments
      sont observés par le client **avant** l'issue, dans l'ordre (preuve d'absence de bufferisation).
- [x] Relais désactivé (contexte par défaut) ⇒ aucun bean `AtelierRelayController`.

### Isolation utilisateur

- [x] `confirmToolUse` / `interruptChat` / `interruptSession` : `requireOwned` **avant** toute
      diffusion — un workspace d'autrui rend 404 et n'émet **rien** sur le réseau interne.
- [x] `RunnerConfirmationGate.resolve` refuse une décision dont le `userId` ou le `workspaceId` ne
      correspond pas à la demande en attente, y compris quand elle arrive par le relais.

---

## Décisions prises en cours de livraison

- **D-13-1** — la diffusion vit dans un `RunnerRelayBroadcaster` **inconditionnel** qui ne fait rien
  quand le secret est vide, plutôt qu'un bean conditionnel : les services appelants gardent une
  dépendance unique et le chemin « relais éteint » reste le chemin par défaut, testé partout.
  Réversible.
- **D-13-2** — les contrôleurs internes atteignent `AtelierChatService` / `AtelierSessionService` par
  deux interfaces déclarées dans `runner.relay` (`RelayInterruptTarget`, `RelaySessionInterruptTarget`)
  : le paquet du relais n'importe rien de l'atelier, et l'invariant T5 (tous les mappings
  `/internal` dans `runner.relay`) tient sans inverser la dépendance. Réversible.
- **D-13-3** — l'interruption F-32 emprunte ce relais plutôt qu'un second mécanisme (arbitrage du
  cadrage §7.3, `docs/features/F-38/CADRAGE-multi-replica.md`).
- **D-13-4** — `/internal/runner/cancel` existe et est testée, mais **aucun appelant ne la diffuse** :
  la diffusion de `/internal/atelier/interrupt` fait déjà `dispatcher.cancelWorkspace` sur chaque pod
  (contrat §6, geste 3), et diffuser en plus un `cancel` doublonnerait le même geste. Son seul
  émetteur est donc l'annulation **dirigée** du read timeout (contrat §7). Le contrat §4 restait
  ambigu sur ce point ; on retient l'option sans doublon. Réversible : rebrancher une diffusion ne
  coûte qu'un appel.
- **D-13-5** — la diffusion ne peut pas faire échouer le geste de l'utilisateur : `broadcast` attrape
  aussi bien un échec de résolution qu'un rejet de l'exécuteur. Le pire cas est le comportement
  d'avant SF-38-13 (interruption locale seule, 409 sur une confirmation orpheline), jamais une erreur
  inventée.
