# Mini-spec — [F-128 / SF-128-04] Transcription (STT), relais Provider-First, éteinte par défaut

> Template : `project-governance/templates/subfeature-template.md`.

---

## Identifiant

`F-128 / SF-128-04`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-04-transcription-stt`

---

## Objectif

> En une phrase : transcrire l'audio d'une réunion via un **service STT relayé** (abstraction
> `TranscriptionProvider`, une implémentation HTTP compatible Whisper, **configurable**), de façon
> **asynchrone** (worker), **éteinte par défaut** (aucun endpoint ⇒ aucune donnée ne sort, erreur
> nommée « STT non configuré »).

---

## Comportement attendu

### Cas nominal

1. Le propriétaire déclenche la transcription d'une réunion capturée :
   `POST /vigie/hosts/{hostId}/meetings/{meetingId}/transcribe`.
2. Gardes API Réunions habituelles (droit Teams 403, possession + Vigie 404/409) ; la réunion doit
   avoir un **audio** (sinon 409) et ne pas être déjà en cours/terminée de transcription (idempotence :
   un `PENDING`/`TRANSCRIBING` renvoie l'état courant sans réenfiler).
3. **Garde d'opt-in (DRAPEAU FORT).** Si le fournisseur STT n'est **pas configuré**
   (`TranscriptionProperties.isConfigured()` faux : pas de base URL / clé), l'endpoint répond
   **503 `stt_not_configured`** — **rien n'est enfilé, aucun octet ne quitte le poste/la gateway**.
4. Sinon : `transcript_status = PENDING`, réponse `202` avec l'artefact. **Le travail lourd est
   asynchrone** : `TranscriptionWorker` (`@Scheduled`) réclame les réunions `PENDING`, les passe
   `TRANSCRIBING`, lit l'audio du stockage objet, appelle `TranscriptionProvider.transcribe(...)`
   (POST multipart compatible Whisper `…/audio/transcriptions`, `verbose_json`), puis stocke le
   **transcript horodaté** (segments `[mm:ss] texte`), la **langue** détectée, et passe `TRANSCRIBED`.
5. Diarisation « qui parle » : **best-effort** — Whisper ne la fournit pas ; on ne l'invente jamais
   (le locuteur actif Teams visuel est hors périmètre ici). On livre les segments horodatés.
6. Lecture : `GET /vigie/hosts/{hostId}/meetings/{meetingId}/transcript` rend le texte du transcript
   (404 si aucun). Le statut/langue sont exposés sur `MeetingResponse` (métadonnées, sans le texte).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| STT non configuré (pas d'endpoint) | Erreur nommée « STT non configuré », rien n'est enfilé, **aucun appel sortant** | 503 `stt_not_configured` |
| Réunion sans audio | Rien à transcrire | 409 `invalid_state` |
| Réunion cross-user / inconnue | Introuvable | 404 |
| Sans droit Teams / poste hors Vigie | Refus | 403 / 409 |
| Échec du service STT (worker) | `transcript_status = FAILED` + message neutre nommé, **jamais de stacktrace**, réessayable | (asynchrone, pas de réponse HTTP) |
| `GET transcript` sans transcript | Introuvable | 404 |

---

## Critères d'acceptation

- [ ] Existe une **abstraction `TranscriptionProvider`** (Provider Independence) et **une** implémentation HTTP compatible Whisper, **base URL + clé configurables, rien en dur**.
- [ ] **Éteinte par défaut** : sans configuration, `POST …/transcribe` renvoie `503 stt_not_configured` et **aucun appel HTTP sortant** n'est émis (vérifié par test : provider mocké jamais appelé).
- [ ] Configurée : `POST …/transcribe` enfile (`PENDING`, 202) ; le **worker asynchrone** transcrit et rattache le transcript (`TRANSCRIBED`, texte horodaté + langue).
- [ ] Échec STT → `FAILED` + message nommé, réessayable ; une erreur d'une réunion ne bloque pas le lot.
- [ ] `GET …/transcript` rend le texte pour une réunion transcrite du propriétaire ; `404` sinon.
- [ ] Isolation : un compte B ne déclenche ni ne lit la transcription d'une réunion du compte A (404).
- [ ] Aucun traitement STT **synchrone** (le call vit dans le worker, jamais dans le thread HTTP).

---

## Périmètre

### Hors scope (explicite)

- **Diarisation visuelle** (locuteur actif Teams) — best-effort néant ici, évolution.
- **STT 100 % local** — évolution par client (D2 du cadrage), l'abstraction le permet plus tard.
- **Exploitation** (résumé/décisions/actions/Q&A, multimodal) → SF-128-05.
- **Écran** de transcription → surfacé par SF-128-05 (frontend, planifiée, suivante).
- **Choix du service STT** (local vs hébergé) : tranché par le PO ; ici on livre le tuyau, éteint.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| transcript_status | `NONE` | à la création (colonne NOT NULL défaut `NONE`) |
| transcript | `null` | tant qu'aucune transcription |
| transcript_lang | `null` | renseignée par le provider (verbose_json) |
| transcript_error | `null` | message nommé si `FAILED` |

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format |
|-------|-------------|-------------|--------|
| `app.stt.base-url` | Non (défaut vide → éteint) | — | URL http(s) |
| `app.stt.api-key` | Non (défaut vide → éteint) | — | secret, EXCLUSIVEMENT depuis l'environnement, jamais commité/journalisé |
| `app.stt.model` | Non | — | défaut `whisper-1` |
| `app.stt.timeout` | Non | — | Duration, défaut `PT120S` |
| `app.stt.max-audio-bytes` | Non | — | défaut 25 Mo (limite Whisper) ; au-delà → `FAILED` nommé |
| transcript | — | borné (troncature défensive) | texte |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/vigie/hosts/{hostId}/meetings/{meetingId}/transcribe` | Oui (JWT) | propriétaire + droit Teams |
| GET | `/vigie/hosts/{hostId}/meetings/{meetingId}/transcript` | Oui (JWT) | idem |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `meetings` | ALTER (migration **115**) + UPDATE/SELECT | colonnes `transcript` (clob), `transcript_status` varchar(20) NOT NULL défaut `NONE`, `transcript_lang` varchar(20), `transcript_error` varchar(500) |

### Migration Liquibase

- [x] Oui — `115-meetings-transcript.xml` (prochain numéro libre après 113 (114 pris par une livraison concurrente)).

### Composants Angular

- Aucun (backend seul ; le frontend de transcription est surfacé par SF-128-05, planifiée & suivante).

---

## Plan de test

### Tests unitaires

- [ ] `HttpTranscriptionProvider` non configuré → `TranscriptionProviderUnavailableException`, **aucun appel** (RestClient jamais touché).
- [ ] `HttpTranscriptionProvider` configuré (RestClient mocké) → parse la réponse, rend texte + langue.
- [ ] `TranscriptionService.requestTranscription` : sans audio → `MeetingStateException` ; non configuré → `TranscriptionNotConfiguredException`, statut inchangé ; configuré → `PENDING`.
- [ ] `TranscriptionService.transcribePending` : réclame `PENDING`, succès → `TRANSCRIBED` + transcript ; échec provider → `FAILED` + message ; une erreur n'arrête pas le lot ; **isolation** (audio lu à la clé du couple de la réunion).

### Tests d'intégration

- [ ] `POST …/transcribe` non configuré → `503 stt_not_configured`, provider **jamais** appelé.
- [ ] `POST …/transcribe` sans audio → `409`.
- [ ] `POST …/transcribe` configuré → `202` `PENDING` ; puis worker (piloté directement) → `TRANSCRIBED` ; `GET …/transcript` → `200` texte.
- [ ] Gardes `403`/`404`/`409` ; **isolation** cross-user (404).

### Isolation utilisateur

- [ ] Applicable — B ne transcrit ni ne lit la transcription d'une réunion de A (404).

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-02` (audio déposé) — Done. `SF-128-10` (accès audio) — Done.

### Questions ouvertes impactées

- Aucune nouvelle. Le **choix service vs local** est tranché par le PO en aval (ici : tuyau éteint).

---

## Préoccupations transversales

- **Auth / Principal** : 2 nouvelles routes sous `/vigie/hosts/{hostId}/meetings` — mêmes gardes
  (`teamsAccess.requireAccess()` + `scopeResolver.requireInVigie`). Endpoints existants inchangés
  (create/stop/pause/resume/list/get + médias SF-10). Aucun changement `CurrentUser`/`RadarScope`.
- **Contexte tenant** : aucune nouvelle résolution ; le worker traite par statut, lit l'audio à la clé
  du couple `(userId, hostId)` de la réunion (isolation par construction).
- **Plans / limites** : la transcription consomme un service externe ; bornée par `max-audio-bytes` et
  le `timeout` ; l'accès suit le droit Teams (comme la capture).
- **Navigation / routing** : aucune route front (backend seul).

---

## Notes et décisions

- **DRAPEAU FORT — l'audio de réunion sort du poste vers le service STT.** Sensible en banque. C'est
  pourquoi la transcription est **opt-in par configuration** (éteinte par défaut) : sans endpoint
  configuré par le PO, **rien ne part**. Le call se fait en HTTPS ; pas de rétention imposée côté
  service (à documenter à l'activation). Révisable en STT **local** via l'abstraction (évolution).
- **Provider-First / Gateway-First** : la transcription est **relayée** (service STT), pas
  réimplémentée ; le backend **orchestre** (enfile, lit l'audio, stocke), il ne transcrit pas.
- **Async obligatoire** : le call STT vit dans `TranscriptionWorker` (`@Scheduled`), jamais dans le
  thread HTTP (règle CLAUDE.md), sur le modèle de `OcrPollingWorker`/`IngestionWorker`.
- **Déclenchement user-initié** (bouton en SF-05), pas automatique à l'arrêt : envoyer l'audio dehors
  est une décision explicite, jamais silencieuse.
