# Mini-spec — [F-257 / SF-257-02] Le service voix existe

## Identifiant

`F-257 / SF-257-02`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage `CADRAGE-F-257-jarvis-converse-en-flux.md`, ADR-021)

## Statut

`in-review`

## Date de création

2026-11-11

## Branche Git

`feat/SF-257-02-service-voix`

---

## Objectif

Le service `jarvis-voice` (Python, Pipecat) existe dans le dépôt — image, manifestes k8s sobres, santé, tests, CI — et
n'accepte une connexion audio que sur présentation d'un **ticket de session vocale** émis par la passerelle, qui lui
permet ensuite d'appeler la passerelle **au nom de l'utilisateur**, sur une liste fermée de routes.

---

## Comportement attendu

### Cas nominal

1. **Passerelle — émettre le ticket.** `POST /api/jarvis/voice-session` `{ "workspaceId": "<uuid>|null" }` (utilisateur
   connecté, accès Atelier) rend `200 { ticket, url, expiresAt, sampleRate: 16000 }` :
   - `ticket` : JWT signé par la passerelle, **portée `jarvis-voice`**, `sub` = `user_id`, version de jeton `tv`
     (logout-all le coupe), sujet `ws` **vérifié** par `findByIdAndUserId` (absent si `workspaceId` nul), durée
     `APP_JARVIS_VOICE_TICKET_TTL` (défaut 30 min) ;
   - `url` : l'adresse WebSocket publique du service (`APP_JARVIS_VOICE_PUBLIC_URL`, défaut `/voice/ws`).
2. **Passerelle — la portée `jarvis-voice`.** Un jeton de cette portée n'ouvre **que** :
   `GET /jarvis/voice-session/me`, `POST /jarvis/converse`, `POST /jarvis/converse/verdict`, `POST /jarvis/speech`,
   `POST /jarvis/stt-session`, `POST /jarvis/stt-session/*/close`, `POST /jarvis/timings`, `POST /jarvis/listen-trace`.
   Hors liste : **403** `jarvis_voice_scope`. Aucun autre comportement du filtre ne change (session ordinaire,
   compagnon).
3. **Passerelle — `GET /api/jarvis/voice-session/me`** (ticket `jarvis-voice` seulement) rend
   `{ userId, workspaceId, expiresAt }` : c'est ainsi que le service vérifie un ticket, **sans secret partagé**.
4. **Service — `jarvis-voice/`** (FastAPI + Pipecat, Python 3.12) :
   - `GET /health` → `200 {"status":"ok"}` ;
   - `WS /voice/ws` : le premier message est `{"type":"hello","ticket":"…"}` (le ticket ne passe **jamais** dans
     l'URL, donc jamais dans les journaux de l'ingress) ; le service vérifie le ticket auprès de la passerelle (`/jarvis/voice-session/me`, URL
     interne `JARVIS_VOICE_GATEWAY_URL`) ; valide → message `{"type":"ready","sessionId":…,"serverTs":…}` ; il garde
     le ticket **en mémoire** pour ses appels suivants à la passerelle ; rien n'est écrit sur disque ;
   - les trames audio binaires (PCM 16 kHz mono 16 bits) sont acceptées et comptées (le pipeline Pipecat qui les
     consomme arrive en SF-257-04/05) ; `{"type":"ping"}` → `{"type":"pong","serverTs":…}` (crochet de mesure du banc
     F-256 : horodatage serveur de chaque événement) ;
   - au plus `JARVIS_VOICE_MAX_SESSIONS` (défaut 8) sessions ; au-delà fermeture `4429`.
5. **Image et k8s, préparés, non déployés** : `jarvis-voice/Dockerfile` (python:3.12-slim, utilisateur non root),
   `jarvis-voice/k8s/` (Deployment 1 réplique, requêtes 256 Mi / 100m, limite 768 Mi / 1 CPU, sondes `/health`,
   Service ClusterIP, NetworkPolicy : entrée depuis l'ingress, sortie vers la passerelle, le DNS et HTTPS sortant pour
   le fournisseur d'écoute ; patch d'ingress `/voice/` documenté). **Rien n'est ajouté à `k8s/**`** (partagé, consigne
   PO) : le déploiement est décrit dans `jarvis-voice/README-deploiement.md`… non : dans la PR et le compte rendu.
6. **CI** : `.github/workflows/jarvis-voice.yml` joue `pytest` sur `jarvis-voice/**` (aucun déploiement).
7. **Coupe-circuit** : `APP_JARVIS_VOICE_STREAM_ENABLED` (défaut `false`) — faux : `POST /jarvis/voice-session` → 503
   `voice_stream_disabled`, rien n'est émis.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Coupe-circuit fermé | `voice_stream_disabled` | 503 |
| `workspaceId` d'un autre utilisateur ou inconnu | `workspace_not_found` | 404 |
| Non connecté | refus | 401 |
| Ticket `jarvis-voice` sur une route hors liste | `jarvis_voice_scope` | 403 |
| `/voice-session/me` avec une session ordinaire | `voice_ticket_required` | 403 |
| Ticket expiré / altéré / logout-all | contexte vide → 401 ; service : fermeture WS `4401` | 401 / 4401 |
| Passerelle injoignable à la vérification | fermeture WS `4503` | — |
| Trop de sessions sur le service | fermeture WS `4429` | — |

---

## Critères d'acceptation

- [ ] CA1 — Coupe-circuit ouvert, `POST /jarvis/voice-session` rend un ticket portée `jarvis-voice` lisible par
      `/voice-session/me` (même `userId`, `workspaceId` vérifié) ; fermé → 503.
- [ ] CA2 — Le sujet d'un autre utilisateur → 404, aucun ticket.
- [ ] CA3 — Un ticket `jarvis-voice` ouvre les 8 routes de la liste et **rien d'autre** (403 sur `/workspaces`, une
      route admin, `/jarvis/voice-session` lui-même).
- [ ] CA4 — Logout-all (version de jeton) rend un ticket émis avant inutilisable.
- [ ] CA5 — Une session ordinaire et une session compagnon se comportent comme avant (non-régression du filtre).
- [ ] CA6 — Service : `/health` 200 ; `hello` absent ou illisible → 4400 ; ticket absent ou refusé → 4401 ; ticket valide → `ready` ;
      `ping` → `pong` avec `serverTs` ; audio binaire compté ; 9ᵉ session → 4429.
- [ ] CA7 — Image construite localement (`docker build jarvis-voice`) et taille rapportée ; manifestes valides
      (`kubectl apply --dry-run=client`) ; **rien poussé, rien appliqué**.

---

## Périmètre

### Hors scope (explicite)

- Le pipeline audio (VAD, smart-turn, STT, cerveau, voix) : SF-257-04 →.
- L'écran (AudioWorklet, drapeau `legacy/stream`) : SF-257-03 ; l'ajout de `/jarvis/voice-session` à la portée
  compagnon y sera fait avec l'écran.
- Le déploiement (image poussée, manifestes appliqués, ingress) : préparé, **non exécuté** (consigne PO).

---

## Contraintes de validation

| Champ | Obligatoire | Format | Règle |
|-------|-------------|--------|-------|
| `workspaceId` | Non | UUID | vérifié sous `user_id` |
| `ticket` (WS, message `hello`) | Oui | JWT portée `jarvis-voice` | vérifié par la passerelle à chaque connexion ; `hello` attendu sous 5 s, sinon 4400 |
| trame audio | — | binaire ≤ 64 Kio | au-delà : ignorée et comptée |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/jarvis/voice-session` | session ordinaire | accès Atelier |
| GET | `/api/jarvis/voice-session/me` | ticket `jarvis-voice` | — |
| GET | `jarvis-voice:/health` | aucune (cluster) | — |
| WS | `jarvis-voice:/voice/ws` (ticket au 1er message) | ticket | — |

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Préoccupations transversales

- **Auth / Principal — cochée.** Nouvelle portée de jeton `jarvis-voice`. Composants impactés : `JwtService`
  (émission), `JwtAuthenticationFilter` (branche de portée), `JarvisCompanionScope`/`JarvisCompanionSessionGuard`
  (inchangés, non-régression), `SecurityConfig` (inchangé : les routes restent authentifiées). Endpoints à revérifier
  sous ticket : les 8 de la liste ; non-régression : session ordinaire et compagnon (tests existants
  `JwtAuthenticationFilter*`, `JarvisCompanion*`).
- **Contexte tenant — cochée.** Le ticket porte `sub` = `user_id` ; le sujet est vérifié par `findByIdAndUserId` à
  l'émission ; chaque route appelée sous ticket garde son filtre `user_id` actuel.
- Plans / limites : non (aucun quota touché). Navigation : non (aucune route d'écran).

---

## Plan de test

### Tests unitaires

- [ ] `JarvisVoiceScopeTest` — liste fermée : 8 routes ouvertes, `*` = un segment, tout le reste fermé.
- [ ] `JwtServiceTest` (ou nouveau) — ticket : portée, `ws`, expiration.
- [ ] Python `test_app.py` — santé, ticket absent/refusé (4401), passerelle injoignable (4503), `ready`, `ping/pong`,
      audio compté, plafond de sessions (4429) ; passerelle simulée.

### Tests d'intégration

- [ ] `JarvisVoiceSessionApiIntegrationTest` — émission (200/503/404/401), `/me` (200 sous ticket, 403 sous session
      ordinaire), ticket sur route ouverte (`/jarvis/timings` 204) et fermée (`/workspaces` 403), logout-all.

### Isolation utilisateur

- [x] Applicable — sujet d'un autre utilisateur refusé (404) ; `/me` ne rend que le `sub` du ticket.

---

## Dépendances

- SF-257-01 fusionnée. OQ-24 tranchée.

## Notes et décisions

- Vérification du ticket par **appel à la passerelle** plutôt que par secret HMAC partagé : le service ne détient
  aucun secret (ADR-021 §2) ; coût : un appel par connexion.
