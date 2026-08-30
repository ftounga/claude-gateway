# Mini-spec — F-38 / SF-38-12 — Relais inter-pods des appels d'outils

---

## Identifiant

`F-38 / SF-38-12`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`done` — mergée le 2026-08-30 (PR #201)

## Date de création

2026-08-30

## Branche Git

`feat/SF-38-12-relais-inter-pods-appels`

---

## Objectif

Permettre au pod qui exécute la boucle d'agent de **relayer un appel d'outil** au pod qui héberge
réellement la socket du runner, par un connecteur HTTP interne authentifié par secret partagé et
inatteignable depuis l'ingress — de sorte qu'un second replica (créé par l'HPA `min 1 / max 4`) ne
fasse plus échouer le mode `RUNNER` en `runner_not_on_this_node`.

---

## Comportement attendu

### Cas nominal

1. Le pod A reçoit une requête de chat en cible `RUNNER`. La boucle tool-use appelle
   `RunnerToolGateway`, qui délègue désormais à **`RunnerCallRouter`** (nouveau) au lieu d'appeler
   `RunnerCallDispatcher` directement.
2. `RunnerCallRouter` interroge le registre :
   - `findLocal(workspaceId)` présent → appel local, chemin **strictement inchangé** ;
   - sinon `findRemote(workspaceId)` rend un `RemoteRunnerNode(nodeId, baseUrl)` dont l'adresse
     diffère de la sienne, et le relais est actif → **relais HTTP** ;
   - sinon → `RunnerCallResult.backendError(isConnected ? RUNNER_NOT_ON_THIS_NODE : RUNNER_UNAVAILABLE)`,
     c'est-à-dire exactement le comportement d'aujourd'hui.
3. Le relais émet `POST http://{podIP}:8081/api/internal/runner/call` avec
   `X-Internal-Relay-Secret`, `X-Relay-Origin: {nodeId}`, `Accept: application/x-ndjson` et
   l'enveloppe `{workspaceId, callId, tool, input, timeoutMs}`.
4. Le pod B (propriétaire de la socket) appelle `RunnerCallDispatcher.call(...)` **directement** —
   jamais le routeur, ce qui rend un second saut inexprimable — et répond `200`
   `application/x-ndjson`, `Cache-Control: no-store`, corps *chunked* :
   `{"type":"stream","chunk":"..."}` (0..N, une ligne par `tool_stream`, écrite et *flushée* dès
   réception) puis exactement une `{"type":"result", ...}` en dernier, miroir champ pour champ de
   `RunnerCallResult`.
5. Le pod A lit **ligne à ligne** (jamais `.body(String.class)`), remet chaque `chunk` à `onChunk`,
   et reconstruit le `RunnerCallResult` **uniquement** depuis la ligne `result` (le `streamed` du
   modèle vient de là, jamais d'une ré-agrégation locale).
6. Le contrat de messages runner est **inchangé** : le runner ne voit aucune différence.

Convergence de présence (`PgNotifyRunnerRegistry`) : la charge `pg_notify` gagne `address`
(`http://{POD_IP}:8081`) ; chaque pod ré-annonce ses connexions locales toutes les
`app.runner.presence.announce-ms` (15 s) ; une présence distante non revue depuis
`app.runner.presence.stale-after-ms` (45 s) est périmée, y compris pour `isConnected` ; au démarrage
un `SYNC_REQUEST` est émis, et tout pod qui le reçoit ré-émet ses `CONNECT` locaux (jamais rediffusé,
donc pas de boucle).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Requête `/internal/**` reçue sur le port public 8080 (donc via l'ingress) | 404, **corps vide**, aucune trace | 404 |
| En-tête `X-Internal-Relay-Secret` absent ou vide | 401, **corps vide**, pas de `WWW-Authenticate`, pas d'`ErrorResponse` | 401 |
| Secret erroné (comparaison temps constant `MessageDigest.isEqual`) | 401, corps vide | 401 |
| Enveloppe incomplète (`workspaceId`/`callId`/`tool` absents) | 400, corps vide | 400 |
| Secret non configuré (dev, tests, prod avant configuration) | Aucun connecteur, aucun contrôleur interne, aucun relais : `runner_not_on_this_node` comme aujourd'hui | — |
| Adresse distante inconnue (carte `remote` non convergée) | `runner_unavailable` (comportement actuel de `RunnerCallDispatcher`) | — |
| Pod distant injoignable (connexion refusée, DNS, timeout de connexion) | **une** tentative, puis `RUNNER_NOT_ON_THIS_NODE`. Aucun retry, aucun rejeu | — |
| Réponse 401 du pair (rotation de secret en cours de rolling update) | `RUNNER_NOT_ON_THIS_NODE`, WARN au plus une fois par minute | — |
| Réponse 404 / 5xx / corps non NDJSON | `RUNNER_NOT_ON_THIS_NODE` | — |
| Flux commencé puis coupé sans ligne `result` (pod distant redémarré/OOMKilled) | `RUNNER_UNAVAILABLE` ; les fragments déjà poussés restent affichés, le modèle reçoit l'erreur (`streamed=""`) | — |
| Read timeout du relais (135 s) | `RUNNER_TIMEOUT` | — |
| Adresse distante = la sienne (garde anti-auto-appel) | Pas de relais : `runner_not_on_this_node` / `runner_unavailable` | — |

---

## Critères d'acceptation

- [x] `RunnerRegistry` expose `Optional<RemoteRunnerNode> findRemote(UUID)` ; `InMemoryRunnerRegistry`
      rend toujours `Optional.empty()`.
- [x] La charge `pg_notify` porte `address` ; la carte distante porte `(nodeId, address, seenAt)`.
- [x] Ré-annonce périodique, péremption à 45 s (y compris pour `isConnected`), `SYNC_REQUEST` au
      démarrage, jamais rediffusé.
- [x] Le relais vit sur un **second connecteur TCP** (`app.runner.relay.port`, défaut 8081), créé
      **uniquement** si `app.runner.relay.secret` est non vide.
- [x] `RunnerCallRouter` applique l'ordre local → distant → erreur existante ; le contrôleur interne
      appelle `RunnerCallDispatcher` directement (un seul saut, garanti par la structure).
- [x] La route `/internal/runner/call` répond en NDJSON `stream`* + `result` unique et final, écrites
      sous un verrou dédié à la réponse (aucune ligne coupée en deux).
- [x] Le client relais lit ligne à ligne ; aucun `.body(String.class)`.
- [x] T1 : `POST http://localhost:{server.port}/api/internal/runner/call` **avec** secret valide → 404, corps vide.
- [x] T2 : même appel sur le port relais **sans** en-tête → 401, corps vide.
- [x] T3 : secret erroné de même longueur → 401, corps vide.
- [x] T4 : bon secret sur le port relais → 200 et réponse fonctionnelle NDJSON.
- [x] T5 : aucun mapping `/internal*` déclaré hors du paquet `fr.claudegateway.runner.relay`.
- [x] T6 : secret vide → aucun bean contrôleur interne, aucun connecteur supplémentaire.
- [x] Isolation : aucun accès aux données par le relais ; l'identité d'une trame runner continue de
      venir de la session, jamais du message.
- [x] Aucun secret journalisé ; aucun `chunk` ni contenu de fichier journalisé.

---

## Périmètre

### Hors scope (explicite)

- Le **branchement effectif de `onChunk` sur le SSE à travers le relais** bout en bout et sa preuve
  d'absence de bufferisation : SF-38-13 (l'enveloppe NDJSON, elle, est livrée ici).
- Les routes `/internal/runner/cancel`, `/internal/runner/confirm`, `/internal/atelier/interrupt`,
  `/internal/atelier/session-interrupt` et la **diffusion** par DNS headless : SF-38-13.
- Les modifications de `AtelierChatService.interruptChat` / `confirmToolUse` et
  `AtelierSessionService.interruptSession` : SF-38-13.
- L'émission best-effort d'un `cancel` sur read timeout : nécessite la route de SF-38-13.
- Tout déploiement (`kubectl apply`, `gh workflow run`) : manuel, après SF-38-13.
- Redis ou tout composant d'infra supplémentaire (hors stack).

---

## Contraintes de validation

| Champ | Obligatoire | Format / valeurs | Normalisation |
|---|---|---|---|
| `workspaceId` | Oui | UUID | — |
| `callId` | Oui | non vide, verbatim (identifiant `tool_use` du fournisseur) | — |
| `tool` | Oui | `list_files\|read_file\|write_file\|search_files\|bash` (validé en aval par le dispatcher) | — |
| `input` | Non (défaut `{}`) | objet JSON recopié **verbatim**, aucun retraitement | — |
| `timeoutMs` | Oui | > 0, borné à `bash` 120 000 par l'appelant | — |
| `X-Internal-Relay-Secret` | Oui | comparaison temps constant sur octets UTF-8 | jamais journalisé |

---

## Technique

### Endpoint(s)

| Méthode | URL | Port | Auth | Rôle minimum |
|---|---|---|---|---|
| POST | `/api/internal/runner/call` | **8081 uniquement** | secret partagé `X-Internal-Relay-Secret` | — (jamais d'utilisateur) |

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

Aucun — le relais est interne à la gateway.

### Manifests k8s

- `k8s/base/backend/deployment.yaml` : `POD_IP` (downward API `status.podIP`),
  `APP_RUNNER_RELAY_PORT: "8081"`, `containerPort: 8081` (documentaire).
- `k8s/base/backend/service-internal.yaml` (nouveau) : Service headless
  `claude-gateway-backend-internal`, `clusterIP: None`, `publishNotReadyAddresses: true`, port 8081,
  visé par **aucun** Ingress.
- `.github/workflows/backend.yml` : `APP_RUNNER_RELAY_SECRET` depuis le secret GitHub
  `RUNNER_RELAY_SECRET`, dans `backend-secrets` (même cycle de vie que `APP_JWT_SECRET`).

---

## Plan de test

### Tests unitaires

- [x] `RunnerCallRouter` — socket locale présente → délègue au dispatcher, aucun relais.
- [x] `RunnerCallRouter` — pas de socket locale, pas de relais actif → `runner_not_on_this_node` si
      `isConnected`, `runner_unavailable` sinon.
- [x] `RunnerCallRouter` — adresse distante = la sienne → pas de relais (garde anti-auto-appel).
- [x] `RunnerCallRouter` — adresse distante différente → relais appelé une seule fois.
- [x] `RunnerRelayClient` — flux `stream`+`result` → chunks relayés dans l'ordre, `streamed` pris
      dans `result`.
- [x] `RunnerRelayClient` — 401 / 404 / corps non NDJSON → `RUNNER_NOT_ON_THIS_NODE`.
- [x] `RunnerRelayClient` — flux coupé sans `result` → `RUNNER_UNAVAILABLE`.
- [x] `InMemoryRunnerRegistry.findRemote` → toujours vide.
- [x] `RunnerToolGateway` — délègue au routeur avec les délais du contrat (tests existants adaptés).

### Tests d'intégration

- [x] T1 404 corps vide sur le port public avec secret valide.
- [x] T2 401 corps vide sans en-tête sur le port relais.
- [x] T3 401 corps vide avec secret erroné de même longueur.
- [x] T4 200 NDJSON avec le bon secret (une seule ligne `result`, dernière).
- [x] T5 aucun mapping `/internal*` hors du paquet `runner.relay`.
- [x] T6 secret vide → aucun bean contrôleur ni connecteur.

### Isolation workspace

- [x] Applicable — le relais ne lit aucune donnée : il ne transporte qu'un `workspaceId` déjà vérifié
      possédé par l'appelant (`requireOwned` en amont, inchangé). Côté pod destinataire,
      `RunnerCallDispatcher` route sur `findLocal` et l'identité des trames vient de la **session**,
      jamais du message : un appel adressé au mauvais pod ne peut pas s'exécuter chez le mauvais
      runner, il rend `runner_unavailable`. Le `userId` n'apparaît pas dans cette enveloppe.

---

## Dépendances

### Subfeatures bloquantes

- SF-38-02 (canal et registre) — done
- SF-38-05 (cible d'exécution runner) — done
- SF-38-07 (bash, flux) — done

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non modifié).

---

## Notes et décisions

- **D-12.1 — second connecteur 8081 (principe tranché au cadrage)** :
  `application.yml` fixe `server.servlet.context-path: /api` et l'ingress route `/api` en `Prefix`.
  Une route interne publiée sur 8080 s'appellerait `/api/internal/...` et serait joignable depuis
  Internet. Le relais vit donc sur un second connecteur TCP absent du Service public, doublé d'un
  filtre `getLocalPort()` qui répond 404 sur le port public, et d'un secret partagé : trois barrières
  indépendantes, chacune testable.
- **D-12.2 — un seul saut par structure** : le contrôleur interne appelle le dispatcher, jamais le
  routeur. Aucun compteur de sauts n'est nécessaire.
- **D-12.3 — aucun rejeu** : une seule tentative. Rejouer un `write_file` serait destructeur.
- **D-12.4 (réversible)** : l'émission best-effort d'un `cancel` sur read timeout est reportée à
  SF-38-13, faute de route `cancel` avant elle. Le read timeout rend déjà `RUNNER_TIMEOUT`, et le pod
  distant abandonne de lui-même à `timeoutMs + 5 s` — le cas est théorique.
- **D-12.5 (réversible)** : la ré-annonce périodique et la péremption utilisent un
  `ScheduledExecutorService` démon local au registre plutôt que `@Scheduled` — le registre n'est actif
  qu'en profil `pg-notify` et ne doit rien imposer au contexte des autres profils.
