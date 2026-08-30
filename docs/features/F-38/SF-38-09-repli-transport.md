# Mini-spec — [F-38 / SF-38-09] Repli de transport (long-polling HTTP)

## Identifiant

`F-38 / SF-38-09`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`done`

## Date de création

2026-08-30

## Branche Git

`feat/SF-38-09-repli-transport`

---

## Objectif

Quand un proxy d'entreprise tue le WebSocket, le runner bascule **tout seul** sur un
**long-polling HTTP** qui transporte **les mêmes enveloppes de messages**, sans qu'aucune autre
brique de F-38 (boucle tool-use, dispatcher, audit, écrans) ne sache quel transport est utilisé.

---

## Comportement attendu

### Cas nominal

**Côté gateway** — trois endpoints ajoutés à la chaîne dédiée `/runner/**` (`@Order(1)`, D9) :

| Verbe | URL | Rôle |
|-------|-----|------|
| POST | `/runner/poll` | le runner **réclame** les trames sortantes en attente (long-poll) |
| POST | `/runner/send` | le runner **dépose** ses trames entrantes (une ou un lot) |
| POST | `/runner/disconnect` | fermeture propre (`Ctrl-C`) |

1. Le runner poste `POST /runner/poll?waitMs=25000` avec l'en-tête **`X-Runner-Token: <jeton>`**.
2. Le contrôleur **authentifie lui-même** le jeton via `RunnerTokenAuthenticator.authenticate()`
   (aucun filtre HTTP ne connaît le jeton runner) et n'écrit **jamais** d'`AuthenticatedUser` dans
   le `SecurityContext` — le jeton runner n'est pas un JWT utilisateur (D9).
3. Au **premier** poll, une « connexion » long-polling est ouverte : elle s'enregistre dans le
   `RunnerRegistry` avec le **même record `RunnerConnection`** (workspaceId, userId, tokenId,
   nodeId **de ce pod**, connectedAt) — donc `GET /workspaces/{id}/runner/status` et
   `findLocal()` restent exacts, et le routage du contrat §8 est inchangé.
4. Le canal est branché sur `RunnerCallDispatcher` comme une socket : c'est une file de trames
   sortantes. Un `tool_call` émis par la boucle tool-use y est **mis en file** au lieu d'être
   écrit sur la socket.
5. Le poll **attend** (au plus `waitMs`, borné par `app.runner.poll.max-wait-ms`, 25 s) qu'au moins
   une trame arrive, puis rend `200 {"frames":[ {...}, {...} ]}` — trames du contrat **octet pour
   octet**, aucun type nouveau. Rien à rendre au bout du délai → `200 {"frames":[]}`.
6. Le runner exécute l'outil et poste `POST /runner/send` avec
   `{"frames":[<tool_stream>…,<tool_result>]}` (ou une trame nue). Chaque trame est aiguillée vers
   `RunnerCallDispatcher.onFrame()` **avec l'identité issue du jeton**, jamais d'un champ du message.
7. `poll` **et** `send` rafraîchissent `runner_tokens.last_seen_at`
   (`RunnerHeartbeatService.touch`) : en mode long-polling, **le poll est le heartbeat** — le runner
   n'ouvre pas de minuteur séparé. Une trame `heartbeat` postée reste acceptée et met un
   `{"type":"heartbeat_ack"}` en file (compatibilité).
8. `POST /runner/disconnect` ferme le canal, termine les appels en vol en `runner_unavailable`, et
   `unregister(workspaceId, tokenId)` — **avec la garde anti-course par `tokenId`** : la fin d'un
   polling ne doit jamais effacer la connexion WS d'un runner qui vient de se reconnecter.
9. Sans poll pendant `app.runner.poll.idle-timeout-ms` (90 s), un balayage périodique ferme le
   canal exactement comme une socket coupée.

**Côté runner** — nouvelle option `--transport auto|websocket|polling`
(`CLAUDE_RUNNER_TRANSPORT`), **défaut `auto`** :

- `auto` : le runner tente le WebSocket. Après **2 échecs consécutifs** de transport —
  échec de handshake **ou** session qui meurt en moins de 5 s (signature exacte d'un proxy qui
  coupe l'upgrade) — il **bascule en long-polling** pour le reste de la session et le dit en clair.
  Un refus `401` n'est **pas** un échec de transport : le jeton est effacé (comportement SF-38-03).
- `websocket` : jamais de repli. `polling` : long-polling d'emblée (réseau connu comme hostile).
- Le long-polling réutilise **le même `ToolDispatcher`, le même `FrameSender`, les mêmes gardes**
  (`PathGuard`, exclusions, `--allow-bash`) : le confinement (D6) et les exclusions (D10) sont
  identiques, seul le tuyau change.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `X-Runner-Token` absent | Réponse générique `runner_unauthorized`, aucun détail | 401 |
| Jeton inconnu / expiré / révoqué | Idem, aucune distinction (pas d'oracle) | 401 |
| Corps de `send` illisible / non JSON | Trame ignorée en silence (jamais une coupure), accusé vide | 204 |
| Trame de `type` inconnu | Ignorée en silence (compatibilité ascendante, contrat §0) | 204 |
| `tool_result` d'un `id` inconnu ou d'un autre workspace | Jetée (log debug), jamais une erreur | 204 |
| File sortante saturée (runner qui ne poll plus) | L'émission échoue → l'appel finit en `runner_unavailable` | — |
| Jeton révoqué pendant le polling | Prochain poll en 401 ; côté runner, jeton effacé et arrêt | 401 |
| Coupe-circuit / révocation pendant un polling | Canal fermé, appels en vol terminés en `runner_unavailable` ; le poll **en vol** est réveillé et répond 409 `runner_channel_closed` (les polls d'après voient un jeton révoqué → 401) | 409 |
| Runner qui ne poll plus (proxy coupe aussi le POST) | Balayage d'inactivité : canal fermé, statut redevient déconnecté | — |
| Autre URL sous `/runner/**` | `denyAll` inchangé | 403 |

---

## Critères d'acceptation

- [x] `POST /runner/poll` **sans** en-tête `X-Runner-Token` → **401** générique.
- [x] `POST /runner/poll` avec un jeton **révoqué** → **401**, sans distinction du cas précédent.
- [x] `POST /runner/poll` avec un jeton valide → **200** `{"frames":[]}` et
      `GET /workspaces/{id}/runner/status` répond `connected=true` juste après.
- [x] Une trame mise en file par le dispatcher est rendue par le poll suivant **verbatim**
      (`type`, `id`, `tool`, `input`, `timeoutMs` inchangés) — aucun type de message nouveau.
- [x] `POST /runner/send` d'un `heartbeat` → **204**, et le poll suivant rend
      `{"type":"heartbeat_ack"}`.
- [x] `POST /runner/send` d'un corps illisible ou d'un type inconnu → **204**, canal intact.
- [x] `POST /runner/disconnect` → **204**, puis `unregister` appelé **avec le `tokenId` du jeton
      présenté** (garde anti-course) et le statut redevient déconnecté.
- [x] Un canal long-polling fermé (coupe-circuit, `disconnect`, balayage) termine ses appels en vol
      en `runner_unavailable` ; le poll **qui attendait** est réveillé aussitôt et répond
      **409 `runner_channel_closed`** au lieu de courir jusqu'au bout de son délai.
- [x] Sécurité : aucun `AuthenticatedUser` n'est posé dans le `SecurityContext` par ces endpoints
      (une requête runner ne peut pas atteindre un endpoint utilisateur) ; la chaîne principale
      (`/me`, `/workspaces`) reste inchangée.
- [x] Isolation : le poll du workspace A ne rend **jamais** une trame destinée au workspace B, et un
      `tool_result` posté avec le jeton de A ne peut pas terminer un appel de B.
- [x] Runner : `--transport` accepte `auto|websocket|polling`, refuse toute autre valeur (code 2).
- [x] Runner : après 2 échecs de transport WS consécutifs en mode `auto`, le repli long-polling est
      engagé ; un `401` ne déclenche **pas** le repli.
- [x] Runner : en long-polling, un `tool_call` reçu produit exactement **une** trame terminale
      `tool_result` postée sur `/runner/send`.
- [x] Aucune migration Liquibase, aucune table, aucun écran.

---

## Périmètre

### Hors scope (explicite)

- **Relais inter-pods** : inchangé (contrat §8). Le long-polling s'enregistre sur **son** pod ;
  si le tour tourne sur l'autre replica, c'est toujours `runner_not_on_this_node`. Le mode `RUNNER`
  en production suppose toujours un replica unique ou une affinité d'ingress.
- **Server-Sent Events** ou tout autre troisième transport.
- **Reprise d'un appel en vol** lors d'une bascule WS → polling : aucun rejeu (contrat §7).
- **Écran** : rien dans l'UI ne dit quel transport est utilisé (le statut runner ne change pas).
- Compression, chiffrement applicatif, pagination des trames.

---

## Valeurs initiales

Aucune entité créée. Le canal long-polling est un objet **en mémoire** :

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `open` | `true` | passe à `false` une seule fois (fermeture idempotente) |
| `queue` | vide | file bornée à 256 trames ; saturation = émission refusée |
| `lastPollAt` | instant du premier poll | rafraîchi à chaque poll ; base du balayage d'inactivité |

- `RunnerConnection.nodeId` = identifiant du pod (informatif : `findLocal()` est une carte locale).
- `connectedAt` = instant du premier poll.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `X-Runner-Token` (en-tête) | Oui | 512 | jeton runner SF-38-01 (Base64URL) | — | `trim()` |
| `waitMs` (query, poll) | Non | — | entier `[0 .. app.runner.poll.max-wait-ms]` (25 000) | — | clampé, défaut = max |
| corps de `send` | Non | 1 Mio | objet JSON : `{"frames":[…]}` **ou** une trame nue | — | non-objet → ignoré |
| `frames` | Non | 64 éléments | tableau d'objets JSON du contrat §2 | — | éléments non-objets ignorés |
| `type` (dans une trame) | Oui | 64 | contrat §2 ; inconnu = ignoré en silence | — | — |
| `--transport` (runner) | Non | — | `auto` \| `websocket` \| `ws` \| `polling` \| `http` | — | `trim().toLowerCase()`, défaut `auto` |

Notes :
- Le jeton voyage **en en-tête**, jamais en query : une query finit dans les journaux d'accès du
  proxy et de l'ingress. Aucun endpoint ne journalise le jeton (règle projet).
- La borne de 1 Mio du corps de `send` est celle du contrat §5 (tampon de trame), appliquée des
  deux côtés.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/runner/poll` | Jeton runner (`X-Runner-Token`) | — (chaîne `/runner/**`) |
| POST | `/runner/send` | Jeton runner (`X-Runner-Token`) | — |
| POST | `/runner/disconnect` | Jeton runner (`X-Runner-Token`) | — |

`RunnerSecurityConfig` : les trois URL passent en `permitAll` **explicitement**, sans quoi le
`anyRequest().denyAll()` final les refuse en 403 (piège identifié au cadrage). L'authentification
réelle est faite par le contrôleur.

### Tables impactées

Aucune, sauf `runner_tokens.last_seen_at` (UPDATE, via `RunnerHeartbeatService.touch`, déjà en
place).

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular (si applicable)

Aucun.

### Composants backend

| Composant | Nature | Rôle |
|-----------|--------|------|
| `RunnerOutbound` | interface (nouvelle) | abstraction d'émission vers un runner : WS **ou** file de long-polling |
| `WebSocketRunnerOutbound` | classe (nouvelle) | adapte le `ConcurrentWebSocketSessionDecorator` existant |
| `LongPollingRunnerOutbound` | classe (nouvelle) | file bornée + attente bloquante + fermeture idempotente |
| `RunnerPollingSessions` | `@Component` (nouveau) | cycle de vie des canaux : registre, dispatcher, balayage d'inactivité |
| `RunnerPollController` | `@RestController` (nouveau) | les 3 endpoints, auth par jeton, aucun `SecurityContext` |
| `RunnerCallDispatcher` | modifié | `Map<UUID, RunnerOutbound>` au lieu de `Map<UUID, WebSocketSession>` |
| `RunnerSecurityConfig` | modifié | 3 `permitAll` de plus |

### Composants runner

| Composant | Rôle |
|-----------|------|
| `PollingClient` / `HttpPollingClient` | I/O HTTP du repli (poll / send / disconnect) |
| `PollingConnection` | boucle de repli : `ready`, aiguillage des trames, arrêt propre |
| `TransportFallbackPolicy` | décide du repli (2 échecs de transport, session < 5 s comptée en échec) |
| `ToolStack` | fabrique commune du `ToolDispatcher` (partagée WS / polling, zéro duplication de gardes) |
| `RunnerConfig` | `--transport`, `pollUrl()`, `sendUrl()`, `disconnectUrl()` |

---

## Plan de test

### Tests unitaires

- [x] `LongPollingRunnerOutbound` — une trame mise en file est rendue par `drain`
- [x] `LongPollingRunnerOutbound` — `drain` rend une liste vide au bout du délai (pas de blocage infini)
- [x] `LongPollingRunnerOutbound` — `close()` réveille un `drain` en attente **et** n'exécute le nettoyage qu'une fois
- [x] `LongPollingRunnerOutbound` — `send` après fermeture lève `IOException` (l'appel finit en `runner_unavailable`)
- [x] `LongPollingRunnerOutbound` — file saturée → `send` lève
- [x] `RunnerPollingSessions` — premier poll : `register` + attache au dispatcher
- [x] `RunnerPollingSessions` — poll suivant du même jeton : même canal, aucun ré-enregistrement
- [x] `RunnerPollingSessions` — jeton différent : l'ancien canal est fermé et remplacé
- [x] `RunnerPollingSessions` — `close` appelle `unregister(workspaceId, tokenId)` **du jeton présenté**
- [x] `RunnerPollingSessions` — un autre jeton ne peut pas raccrocher le canal de ce runner
- [x] `RunnerPollingSessions` — une présence qui n'est plus la nôtre n'est pas effacée à la fermeture
- [x] `RunnerPollingSessions` — balayage : un canal inactif au-delà du délai est fermé
- [x] `RunnerCallDispatcher` — un `tool_call` routé vers un canal long-polling est mis en file (non-régression du routage)
- [x] Runner `RunnerConfig` — `--transport` : défaut `auto`, valeurs acceptées, valeur inconnue refusée
- [x] Runner `TransportFallbackPolicy` — 2 échecs → repli ; session saine → compteur remis à zéro ; mode `websocket` → jamais de repli
- [x] Runner `PollingConnection` — `ready` émis en premier ; `tool_call` exécuté → un `tool_result` posté ; type inconnu ignoré ; `stop()` termine la boucle et appelle `disconnect`

### Tests d'intégration

- [x] `POST /runner/poll` sans en-tête → 401
- [x] `POST /runner/poll` jeton révoqué → 401
- [x] `POST /runner/poll` jeton valide → 200 `{"frames":[]}` et statut `connected=true`
- [x] `POST /runner/send` `heartbeat` → 204, poll suivant → `heartbeat_ack`
- [x] `POST /runner/send` corps illisible / type inconnu → 204
- [x] `POST /runner/disconnect` → 204 et statut redevenu déconnecté
- [x] Poll en vol au moment d'une coupure → 409 `runner_channel_closed`
- [x] Un jeton runner n'ouvre **jamais** `/me` (ni en `X-Runner-Token`, ni en `Bearer`)
- [x] Non-régression chaîne principale : `GET /me` avec JWT → 200 ; `/runner/inconnu` → 403

### Isolation workspace

- [x] Applicable — le poll d'un runner du workspace A ne rend jamais une trame destinée au
      workspace B (deux canaux ouverts simultanément dans le même test), et l'identité utilisée pour
      router une trame entrante vient **toujours** du jeton présenté.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-01` (jetons) — done
- `SF-38-02` (registre + statut) — done
- `SF-38-03` (runner, connexion WS) — done
- `SF-38-05` (dispatcher, routage) — done
- `SF-38-07` / `SF-38-08` (bash, audit, coupe-circuit) — done, non modifiées

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|---------------|-----------|-------------------------------------|
| **Auth / Principal** | **Oui** | Nouveaux endpoints HTTP portant un **jeton runner**. Composants : `RunnerSecurityConfig` (3 `permitAll` ajoutés sous `securityMatcher("/runner/**")`, chaîne principale intouchée), `RunnerPollController` (authentifie lui-même, ne pose **aucun** `Authentication` dans le `SecurityContext`), `JwtAuthenticationFilter` (non concerné : le jeton runner voyage dans `X-Runner-Token`, jamais dans `Authorization: Bearer` — aucune collision possible). Non-régression testée : `/me` + JWT, `/workspaces` + JWT, `/runner/inconnu` → 403. |
| **Contexte tenant** | **Oui** | Le `workspaceId`/`userId` vient **exclusivement** de `RunnerIdentity` résolue depuis le jeton. Composants : `RunnerPollController`, `RunnerPollingSessions`, `RunnerCallDispatcher.onFrame` (garde d'isolation déjà en place : une trame ne peut pas terminer l'appel d'un autre workspace). Testé. |
| **Plans / limites** | Non | Aucun quota, aucun gate touché. |
| **Navigation / routing** | Non | Aucun changement frontend. |

---

## Notes et décisions

- **D-09.1 — En-tête `X-Runner-Token`, pas de query param** (réversible). Le WS porte le jeton en
  query faute de mieux (les navigateurs n'envoient pas d'en-tête au handshake) ; en HTTP rien
  n'oblige à cela, et une query finit dans les journaux d'accès du proxy. `Authorization: Bearer`
  est volontairement **écarté** : le `JwtAuthenticationFilter` est un `OncePerRequestFilter`
  enregistré comme bean, un jeton runner dans cet en-tête serait donné à manger au parseur JWT.
- **D-09.2 — `RunnerOutbound` plutôt qu'un second dispatcher** (réversible). Un seul chemin
  d'émission, donc un seul endroit où l'audit, l'annulation, le coupe-circuit et les délais
  s'appliquent. Dupliquer le dispatcher aurait créé deux comportements à maintenir.
- **D-09.3 — Le poll est le heartbeat** (réversible). Un minuteur de heartbeat séparé en mode
  polling doublerait le trafic pour la même information. Le poll long (25 s) rafraîchit
  `last_seen_at` très en deçà de la fenêtre de 90 s.
- **D-09.4 — Repli après 2 échecs, session < 5 s comptée en échec** (réversible). Le cas réel
  n'est pas seulement « l'upgrade est refusé » mais « l'upgrade passe et le proxy coupe la socket
  aussitôt » : sans le second critère, le runner boucle indéfiniment en reconnexion sans jamais
  basculer, ce qui est précisément le symptôme que cette subfeature doit supprimer.
- **D-09.5 — Le repli est unidirectionnel** : une fois en long-polling, le runner y reste jusqu'à
  son redémarrage. Revenir au WS à chaud demanderait une sonde périodique pour un gain nul.
- **D-09.6 — Le long-poll bloque un thread servlet** (réversible, **à surveiller**). L'attente de
  25 s se fait sur le thread Tomcat de la requête : simple, testable, et sans machinerie asynchrone.
  Le plafond est donc le pool Tomcat (200 threads par défaut), soit ~200 runners simultanés par pod —
  très au-dessus des besoins actuels. Passer en `DeferredResult` est la porte de sortie si ce plafond
  devient réel ; le contrat HTTP ne changerait pas.
- **D-09.7 — Un POST par trame sortante** (réversible). Le corps de `/runner/send` accepte déjà un
  lot, donc grouper les `tool_stream` d'un `bash` bavard est un changement purement local, sans
  toucher au protocole. Non fait en v1 : la file d'émission mono-thread du runner sérialise déjà les
  envois, et une commande bavarde reste rare.
- **Course WS ↔ polling sur le même jeton** : la garde du registre porte sur le `tokenId`, or le même
  runner utilise le même jeton avec les deux transports. La fermeture **tardive** d'un WebSocket peut
  donc effacer la présence du polling qui vient de prendre le relais. Deux gardes traitent le cas :
  le nettoyage ne désenregistre que si la présence est **exactement** la sienne, et chaque poll
  repose la présence si plus personne ne l'occupe. Au pire, le statut affiché clignote le temps d'un
  cycle de poll — jamais un appel routé vers un canal mort.
- **Flag multi-replica** : inchangé et toujours ouvert (contrat §8) — le long-polling n'ajoute
  aucun relais inter-pods. Le mode `RUNNER` en production suppose toujours un replica unique ou une
  affinité d'ingress.
