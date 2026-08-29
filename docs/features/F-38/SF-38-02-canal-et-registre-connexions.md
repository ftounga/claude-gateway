# Mini-spec — F-38 / SF-38-02 — Canal et registre de connexions

## Identifiant
`F-38 / SF-38-02`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`ready`

## Date de création
2026-08-29

## Branche Git
`feat/SF-38-02-canal-et-registre-connexions`

---

## Objectif

> Ouvrir un **canal WebSocket** `/runner/ws` que le runner authentifie par son **jeton runner**
> (SF-38-01), tenir un **registre de connexions** (`RunnerRegistry`, InMemory + PgNotify) et exposer
> l'état **connecté / déconnecté** d'un workspace — connexion et heartbeat seulement, sans encore
> transporter d'exécution d'outil.

---

## Comportement attendu

### Cas nominal

1. Le runner ouvre une connexion WSS sortante sur `GET /runner/ws`, en portant son **jeton runner**
   soit en **query param** `?token=<jeton>`, soit dans le sous-protocole `Sec-WebSocket-Protocol`
   (préfixe `runner-token.`). Le handshake est authentifié par `RunnerTokenAuthenticator`
   (SF-38-01) : un jeton valide résout une `RunnerIdentity` (userId + workspaceId + tokenId).
2. À l'établissement, la connexion est **enregistrée** dans le `RunnerRegistry` (clé = workspaceId)
   et `runner_tokens.last_seen_at` est mis à `now()`.
3. Le runner envoie périodiquement un **heartbeat** (`{"type":"heartbeat"}` ou trame pong native) ;
   chaque heartbeat rafraîchit `last_seen_at` et reçoit un accusé `{"type":"heartbeat_ack"}`.
4. À la fermeture (Ctrl-C du runner, coupure réseau), la connexion est **retirée** du registre.
5. L'utilisateur (JWT, accès Atelier) consulte `GET /workspaces/{id}/runner/status` sur **un de ses**
   workspaces : réponse `{ "connected": true|false, "lastSeenAt": "..."|null }`. `connected` est vrai
   si le registre voit une connexion vivante **ou** si le dernier heartbeat est récent (fenêtre
   `app.runner.heartbeat.stale-after`, défaut 90 s) — ce second critère, adossé à `last_seen_at`
   (base partagée), rend le statut correct même quand le runner est connecté à **l'autre replica**.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Handshake WS sans jeton | Handshake refusé | 401 (interceptor) |
| Handshake WS avec jeton inconnu / expiré / révoqué | Handshake refusé (générique) | 401 |
| `GET /workspaces/{id}/runner/status` sur le workspace d'un autre utilisateur | Accès refusé (on ne révèle pas l'existence) | 404 |
| `GET /workspaces/{id}/runner/status` sans accès Atelier | Refus | 403 `atelier_forbidden` |
| `GET /workspaces/{id}/runner/status` sans JWT | Non authentifié | 401 |
| Heartbeat sur un jeton révoqué en cours de session | La fermeture retire la connexion ; le jeton révoqué n'ouvre plus de nouvelle session | — |

---

## Critères d'acceptation

- [ ] Un runner présentant un **jeton valide** (query param ou sous-protocole) établit la connexion WS ;
      un jeton absent/invalide voit son handshake **refusé (401)**.
- [ ] À l'établissement, la connexion est **enregistrée** dans `RunnerRegistry` et `last_seen_at` passe à `now()`.
- [ ] Un message heartbeat rafraîchit `last_seen_at` et reçoit `heartbeat_ack`.
- [ ] À la fermeture, la connexion est **retirée** du registre (garde anti-course : on ne retire que
      si la connexion enregistrée appartient à la session qui se ferme).
- [ ] `RunnerRegistry` a **deux implémentations derrière l'interface** : `InMemoryRunnerRegistry`
      (défaut dev/tests) et `PgNotifyRunnerRegistry` (prod, `LISTEN`/`NOTIFY`), sélectionnées par
      `app.runner.registry` — à la manière de `WorkspaceStorage`.
- [ ] `GET /workspaces/{id}/runner/status` renvoie `connected=false` sans runner, `connected=true`
      quand une connexion est enregistrée **ou** que le heartbeat est récent, avec `lastSeenAt`.
- [ ] **Isolation `user_id`** : le statut n'est lisible que par le propriétaire du workspace
      (404 cross-user), après `requireOwned` — jamais depuis un paramètre client.
- [ ] La chaîne de sécurité dédiée `/runner/**` (`@Order(1)`) **couvre le WS** ; la chaîne principale
      reste inchangée (non-régression `/me`, `/workspaces`).

---

## Périmètre

### Hors scope (explicite)
- Transport d'exécution d'outil (fichiers / bash) sur le canal → SF-38-04, SF-38-05, SF-38-07.
- Routage vers la connexion vivante d'un **autre replica** (le registre expose la présence
  cross-replica ; le relais des messages vers le pod distant est **SF-38-05**).
- Repli long-polling si un proxy tue le WS → SF-38-09.
- Écran « runner connecté / déconnecté » (frontend) → SF-38-06.
- Plusieurs runners simultanés sur un même workspace (un seul enregistré par workspace).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `runner_tokens.last_seen_at` | `null` (à l'émission, SF-38-01) | passe à `now()` à l'établissement WS et à chaque heartbeat |

Aucune nouvelle table, aucune nouvelle colonne : `last_seen_at` existe déjà (migration 047, SF-38-01).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|-------|-------------|------------------|---------------|
| `token` (handshake) | Oui | jeton opaque SF-38-01 ; query `?token=` ou sous-protocole `runner-token.<jeton>` | trim |
| message heartbeat | — | JSON `{"type":"heartbeat"}` (autres types ignorés) ; trame pong native acceptée | — |

---

## Technique

### Endpoints

| Méthode | URL | Auth | Chaîne | Rôle min |
|---------|-----|------|--------|----------|
| WS (GET upgrade) | `/runner/ws` | **jeton runner** (query/sous-protocole) | **dédiée `/runner/**` @Order(1)** | — |
| GET | `/workspaces/{id}/runner/status` | JWT | principale | accès Atelier |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_tokens` | UPDATE `last_seen_at` | heartbeat / établissement ; SELECT pour le statut (filtre `user_id`+`workspace_id`) |

### Migration Liquibase
- [x] Non applicable (aucune table / colonne nouvelle ; `last_seen_at` déjà en place).

### Composants Angular
- Aucun (SF-38-06).

### Composants backend (nouveaux)
- `runner.channel.RunnerRegistry` (interface) + `InMemoryRunnerRegistry` + `PgNotifyRunnerRegistry`.
- `runner.channel.RunnerConnection` (identité de la connexion : workspaceId, userId, tokenId, nodeId, connectedAt).
- `runner.channel.RunnerHandshakeInterceptor` (auth du handshake via `RunnerTokenAuthenticator`).
- `runner.channel.RunnerWebSocketHandler` (établissement, heartbeat, fermeture).
- `runner.channel.RunnerWebSocketConfig` (`WebSocketConfigurer`, mapping `/runner/ws`).
- `runner.RunnerHeartbeatService` (mise à jour transactionnelle de `last_seen_at`).
- `runner.RunnerStatusService` + `dto.RunnerStatusResponse` + méthode `GET /status` sur `RunnerManagementController`.
- `RunnerSecurityConfig` : ajout de `GET /runner/ws` en `permitAll` (auth déléguée au handshake interceptor).

---

## Plan de test

### Tests unitaires
- [ ] `InMemoryRunnerRegistry` — register/isConnected/find/unregister ; overwrite ; isolation par workspace.
- [ ] `RunnerHandshakeInterceptor` — jeton valide (query) → identité en attributs, `true` ;
      jeton via sous-protocole → identité ; jeton absent/invalide → `false` + 401.
- [ ] `RunnerHeartbeatService` — `touch(tokenId)` met `last_seen_at` à jour ; id inconnu → no-op.
- [ ] `RunnerStatusService` — connecté si registre le dit ; connecté si heartbeat récent alors que
      registre vide ; déconnecté sinon ; `requireOwned` → 404 pour un non-propriétaire (isolation).
- [ ] `RunnerWebSocketHandler` — établissement enregistre + touche ; heartbeat → ack + touche ;
      fermeture retire du registre.

### Tests d'intégration
- [ ] `GET /runner/ws` sans jeton → 401 (handshake refusé, prouve la couverture de la chaîne dédiée).
- [ ] `GET /runner/ws?token=<valide>` sans upgrade → 400 (a passé sécurité + interceptor, échoue au handshake).
- [ ] `GET /workspaces/{id}/runner/status` → 200 propriétaire (déconnecté) ; reflète `connected=true`
      après enregistrement d'une connexion ; 404 cross-user ; 403 sans accès Atelier ; 401 sans JWT.
- [ ] Non-régression : `/me` et `/workspaces` répondent comme avant (chaîne principale intacte).

### Isolation
- [x] Applicable — un utilisateur ne consulte le statut runner que de **ses** workspaces (404 sinon).

---

## Dépendances

### Subfeatures bloquantes
- `SF-38-01` — statut : **done** (jetons, `RunnerTokenAuthenticator`, chaîne dédiée `/runner/**`).

### Questions ouvertes impactées
- Aucune de `docs/OPEN_QUESTIONS.md`. La décision **D8** (registre PgNotify vs Redis) est tranchée
  par défaut le 2026-08-29 dans le cadrage F-38 ; formalisée ici en **ADR-016**.

---

## Préoccupation transversale — Auth / Principal (OBLIGATOIRE)

SF-38-02 branche pour la première fois le **jeton runner** (porteur d'identité non-JWT introduit en
SF-38-01) sur un endpoint : le **handshake WebSocket**. Analyse d'impact des composants qui résolvent
le Principal / le tenant :

- `JwtAuthenticationFilter` — pose l'`AuthenticatedUser` (JWT). **Inchangé** ; ne s'applique pas à
  `/runner/**` (chaîne dédiée).
- `CurrentUser` — lit l'`AuthenticatedUser`. **Inchangé** ; le WS runner ne passe jamais par lui
  (l'identité runner vient du handshake, pas du `SecurityContext` principal).
- `SecurityConfig` (chaîne principale, `anyRequest().authenticated()`). **Inchangée** — le nouvel
  endpoint utilisateur `/workspaces/{id}/runner/status` y est déjà couvert (préfixe `/workspaces/**`).
- `RunnerSecurityConfig` (chaîne dédiée `/runner/**`, `@Order(1)`). **Étendue** : ajout de
  `GET /runner/ws` en `permitAll`, l'authentification réelle étant faite par
  `RunnerHandshakeInterceptor` (rejette tout handshake sans jeton valide). Aucun autre endpoint
  n'est ouvert (`anyRequest().denyAll()` conservé).
- `AtelierAccessService` — gate Gold/ADMIN. **Inchangé** ; appliqué à `GET /status`.
- `RunnerTokenAuthenticator` (SF-38-01) — **réutilisé** tel quel pour authentifier le handshake.

**Tests de non-régression exigés :** `/me`, `/workspaces` continuent de répondre comme avant ;
`/runner/ws` sans jeton est refusé (401) et n'atteint jamais un traitement utilisateur.

---

## Notes et décisions

- **D8 / ADR-016 — Registre derrière `RunnerRegistry`** : deux implémentations sélectionnées par
  `app.runner.registry` (`in-memory` défaut dev/tests, `pg-notify` prod), calquées sur
  `WorkspaceStorage`. Postgres `LISTEN`/`NOTIFY` (canal `runner_presence`) diffuse les événements
  connect/disconnect entre les 2 replicas — aucun composant d'infra ajouté. Choix **réversible**.
- **Statut cross-replica robuste sans routage** : `connected` combine la présence du registre **et**
  la fraîcheur de `last_seen_at` (base partagée). Même si le runner est sur l'autre pod (ou si un pod
  vient de démarrer et n'a pas encore reçu les `NOTIFY` de présence existants), son heartbeat garde
  le statut correct. Le **relais** des messages vers le pod distant reste SF-38-05.
- **`find()` du registre** renvoie la connexion **locale** uniquement (routage distant = SF-38-05).
- Le jeton est accepté en query param **ou** sous-protocole : le runner (client Java, SF-38-03) usera
  du query param ; le sous-protocole reste pour un client navigateur éventuel.
</content>
</invoke>
