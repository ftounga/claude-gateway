# Mini-spec — F-97 / SF-97-01 — La gateway ne croit plus une socket muette

## Identifiant

`F-97 / SF-97-01`

## Feature parente

`F-97` — Le statut du poste dit vrai (cadrage : `CADRAGE-F-97-le-statut-dit-vrai.md`)

## Statut

`in-review`

## Date de création

2026-09-13

## Branche Git

`feat/SF-97-01-le-battement-fait-foi`

---

## Objectif

Faire du **battement** (`runner_tokens.last_seen_at` de moins de `stale-after`) la seule preuve de vie
d'un poste : statut, routage des appels et présence inter-pods cessent de croire une socket
WebSocket enregistrée mais muette, et la gateway ferme elle-même ces sockets.

---

## Comportement attendu

### Cas nominal

1. **Une seule définition de la fraîcheur** — nouveau composant `RunnerLiveness`
   (`fr.claudegateway.runner`) : `isFresh(lastSeenAt)` ⇔ `lastSeenAt > now − stale-after` (90 s, propriété
   `app.runner.heartbeat.stale-after` inchangée) ; `isAlive(userId, hostId)` et `isAliveForRouting(hostId)` lisent
   le `max(last_seen_at)` des jetons du poste.
2. **`RunnerStatusService.statusOf`** : `connected = isFresh(lastSeen)`. Le registre n'est plus lu
   pour le statut (la dépendance est retirée du service).
3. **Balayage des sockets WebSocket** (`RunnerWebSocketHandler.sweepSilentSockets`,
   `@Scheduled(fixedDelayString = "${app.runner.websocket.sweep-ms:15000}")`, même cadence que
   `RunnerPollingSessions`) : chaque session WebSocket locale dont le poste n'a plus de battement frais
   est fermée (`CloseStatus.SESSION_NOT_RELIABLE`), puis libérée par **le même chemin** que
   `afterConnectionClosed` : `dispatcher.detach` (garde par canal) puis retrait du registre **seulement
   si la présence enregistrée est encore exactement celle de cette session** (garde par jeton du
   registre + égalité de la connexion, comme le long-polling). La fermeture tardive d'une vieille
   socket n'efface donc jamais une reconnexion plus récente, même sous le même jeton.
4. **`PgNotifyRunnerRegistry`** : la ré-annonce périodique et la réponse à un `SYNC_REQUEST` ne
   ré-émettent **pas** `CONNECT` pour une connexion locale dont le battement est périmé. `register`
   annonce toujours (une connexion qui s'établit est vivante par définition).
5. **`RunnerCallDispatcher.call`** : poste sans battement frais → `runner_unavailable` **immédiat**,
   sans émettre de `tool_call` ni attendre `timeoutMs + grâce`. Même règle quand la socket n'est pas
   locale : `runner_not_on_this_node` n'est rendu que si le poste est vivant, sinon `runner_unavailable`.
6. **`RunnerCallRouter.call`** : même règle avant tout relais — poste sans battement frais →
   `runner_unavailable` immédiat, aucun saut HTTP vers un pod pair.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Socket enregistrée, dernier battement il y a 91 s | `connected=false` | — (statut 200) |
| Aucun battement jamais reçu (`last_seen_at` nul) | `connected=false`, `lastSeenAt=null` | — |
| Appel d'outil vers un poste au battement périmé (socket locale ou distante) | `runner_unavailable` immédiat, aucune trame émise | erreur d'outil |
| Lecture de la fraîcheur en échec (base indisponible) pendant le balayage | la socket **n'est pas** fermée (le doute ne coupe pas) | — |
| Lecture de la fraîcheur en échec pendant la ré-annonce | la connexion est ré-annoncée (comportement antérieur) | — |
| Lecture de la fraîcheur en échec au moment d'un appel | l'appel suit son chemin normal (délai d'appel inchangé) | — |
| Reconnexion (autre jeton, ou même jeton) pendant le balayage de l'ancienne socket | la nouvelle présence **n'est pas** effacée | — |
| Poste d'autrui | inchangé : `requireOwned` → 404 avant tout calcul | 404 |

---

## Critères d'acceptation

- [ ] Socket enregistrée + battement vieux de 91 s → `RunnerStatus.connected == false`.
- [ ] Battement de 10 s sans socket locale (socket sur l'autre pod) → `connected == true`.
- [ ] Le balayage ferme une socket WebSocket dont le poste est muet (`SESSION_NOT_RELIABLE`), vide le
      registre et termine ses appels en vol en `runner_unavailable`.
- [ ] Le balayage ne ferme pas une socket dont le poste bat encore.
- [ ] Reconnexion sous un nouveau jeton (et sous le même jeton) pendant le balayage → présence de la
      nouvelle connexion **conservée**.
- [ ] `PgNotifyRunnerRegistry` : ré-annonce muette pour une connexion périmée, émise pour une vivante.
- [ ] `RunnerCallDispatcher.call` sur poste périmé → `runner_unavailable` sans émission, en bien moins
      que le délai d'appel.
- [ ] `RunnerCallRouter.call` sur poste périmé avec adresse distante connue → `runner_unavailable`,
      aucun relais.
- [ ] Durées de battement inchangées, aucune migration, aucun changement du runner.

---

## Périmètre

### Hors scope (explicite)

- Toute modification du runner, de l'intervalle de battement (30 s) ou de `stale-after` (90 s).
- Le frontend (SF-97-02).
- Le balayage du long-polling (`RunnerPollingSessions`), déjà existant et inchangé.
- La fermeture des sockets orphelines **remplacées** par une reconnexion dont le poste bat : elles ne
  mentent plus sur le statut et restent bornées par l'ingress (900 s).
- Le `proxy-read-timeout` de l'ingress.

---

## Valeurs initiales

Non applicable — aucune entité créée.

---

## Contraintes de validation

Non applicable — aucun champ saisi. Seule règle : fraîcheur = `last_seen_at` strictement postérieur à
`now − app.runner.heartbeat.stale-after` (défaut `PT90S`, inchangé).

---

## Technique

### Endpoint(s)

Aucun endpoint nouveau. Comportement modifié : `GET /api/workspaces/{id}/runner/status`,
`GET /api/runner-hosts`, `/api/runner-hosts/overview`, `/api/runner-hosts/{id}/status` (champ `connected`), tous les appels d'outils runner (atelier, Teams,
navigation de dossiers, gouvernance), relais interne entre pods.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_tokens` | SELECT `max(last_seen_at)` | par `user_id` + `host_id` quand l'utilisateur est connu (statut, dispatcher, balayage, ré-annonce) ; par `host_id` seul dans le routeur, dont la cible ne porte pas l'utilisateur — voir Notes |

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

Aucun.

### Préoccupations transversales

- Auth / Principal : non. Contexte tenant : non (le statut reste lu après `requireOwned`).
  Plans / limites : non. Navigation : non.
- **Inter-pods : oui.** Composants impactés et vérifiés : `PgNotifyRunnerRegistry` (ré-annonce,
  réponse au `SYNC_REQUEST`), `RunnerCallRouter` (relais), `RunnerCallDispatcher` (appel local et
  appel relayé reçu par `RunnerRelayController`), `RunnerWebSocketHandler` (fermeture),
  `RunnerPollingSessions` (garde de nettoyage inchangée, compatible), `InMemoryRunnerRegistry`.

---

## Plan de test

### Tests unitaires

- [ ] `RunnerLivenessTest` — frais à 89 s, périmé à 91 s, nul = périmé ; lecture filtrée par
      `user_id` + `host_id`.
- [ ] `RunnerStatusServiceTest` — socket enregistrée + battement 91 s → `connected=false` ; battement
      10 s → `true` ; jamais vu → `false`.
- [ ] `RunnerWebSocketHandlerTest` — balayage : poste muet → fermeture `SESSION_NOT_RELIABLE`,
      registre vidé, appel en vol terminé ; poste vivant → rien ; reconnexion nouveau jeton / même jeton
      pendant le balayage → présence conservée ; lecture en échec → rien fermé ; fermeture normale
      toujours retirée du registre.
- [ ] `PgNotifyRunnerRegistryTest` — ré-annonce : `CONNECT` émis pour la connexion vivante, pas pour la
      périmée ; `register` annonce toujours.
- [ ] `RunnerCallDispatcherTest` — poste périmé → `runner_unavailable` immédiat, aucune trame ;
      socket distante mais poste périmé → `runner_unavailable` (et non `runner_not_on_this_node`).
- [ ] `RunnerCallRouterTest` — poste périmé avec adresse distante → `runner_unavailable`, relais jamais
      appelé ; poste vivant → comportements existants inchangés.

### Tests d'intégration

- [ ] `RunnerStatusApiIntegrationTest` — un jeton au `last_seen_at` vieux de 5 min avec une présence
      enregistrée dans le registre → `connected=false` via l'API.
- [ ] Suites existantes vertes : `RunnerRelayStreamIntegrationTest`, `RunnerPollingApiIntegrationTest`,
      `AtelierChatService*`, tests d'architecture.

### Isolation workspace

- [x] Applicable — le statut reste servi après `requireOwned` (poste d'autrui → 404, test existant
      conservé) ; la lecture de fraîcheur du statut filtre `user_id` + `host_id`.

---

## Dépendances

### Subfeatures bloquantes

- Aucune.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Une base, pas une mémoire locale** : la fraîcheur est lue dans `runner_tokens` (base partagée)
  partout, y compris pour la socket locale. Le battement arrive sur le pod de la socket et y est écrit ;
  le routeur d'un pod pair n'a que la base. Une seule source évite deux vérités.
- **Routeur et `user_id`** : `RunnerTarget` ne porte pas l'utilisateur ; les appelants ont tous déjà
  vérifié le poste possédé. Le routage est indexé par poste, comme le registre ; la lecture ne rend
  qu'un horodatage qui ne quitte pas la gateway. Quand une connexion locale existe, le dispatcher
  relit avec `user_id` + `host_id`.
- **Le doute ne coupe pas** : une lecture de fraîcheur en échec ne ferme aucune socket, ne supprime
  aucune annonce et ne refuse aucun appel — le comportement d'avant s'applique.
- **Garde renforcée** : `afterConnectionClosed` et le balayage partagent `release()`, qui ne retire du
  registre que la présence exacte de la session (même principe que `RunnerPollingSessions.cleanup`).
  Corrige au passage la fermeture tardive d'une socket sous le même jeton qui effaçait une reconnexion.
