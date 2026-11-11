# Mini-spec — [F-257 / SF-257-04] Une session de transcription longue

## Identifiant

`F-257 / SF-257-04`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage §3.4, ADR-021, OQ-24 décisions 2 et 3)

## Statut

`in-review`

## Date de création

2026-11-11

## Branche Git

`feat/SF-257-04-session-ecoute-longue`

---

## Objectif

Le service `jarvis-voice` tient **une seule session de transcription par conversation** (ElevenLabs Scribe v2
Realtime, *commit* manuel), la rouvre seul quand le fournisseur la coupe sans perdre de phrase, et chaque session est
comptée en durée côté passerelle.

---

## Comportement attendu

### Cas nominal

1. **Passerelle** : `POST /api/jarvis/stt-session` accepte un corps facultatif `{"mode":"long"}`. Sans corps (écran
   actuel) : inchangé. En `long` : jeton à usage unique, URL `commit_strategy=manual` (sans seuil de silence),
   vocabulaire du compte (≤ 50 termes, ≤ 20 caractères), même plafond voix, comptée à la clôture
   (`min(déclaré, écoulé + 5 s, 1 800 s)`), balayée à 1 800 s si jamais close. Interface
   `StreamingTranscriptionProvider.openManualCommitSession` (défaut : non prise en charge → 503).
2. **Service** : à `ready`, `LongTranscription` demande une session longue à la passerelle **avec le ticket de
   l'utilisateur**, se connecte au fournisseur (jeton dans l'URL du fournisseur, jamais journalisée), attend
   `session_started` (5 s) puis :
   - pousse le micro par blocs de **3 200 octets (≈ 100 ms)** — jamais de rafale de petits blocs (`queue_overflow`) ;
   - `commit()` (appelé par la fin de tour, SF-257-05) envoie le reste puis `commit: true` ;
   - `partial_transcript` → `{"type":"transcript","final":false,"text":…}` vers l'écran ;
     `committed_transcript*` non vide → `final:true` ; **vide** (le *commit* automatique des ≈ 36 s) : compté, ignoré ;
   - garde l'audio **envoyé et pas encore confirmé** par un texte final (≤ 2 s).
3. **Reprise** : fermeture du fournisseur (≈ 24,5 min, `queue_overflow`, réseau) → la session est comptée
   (`close` avec sa durée), une nouvelle est ouverte, l'audio non confirmé est **rejoué** (4× le temps réel), puis le
   flux reprend. Événements `{"type":"stt","state":"open|reopen|closing|closed","detail":…}` : le détail est un
   **type** de message (`queue_overflow`, `rate_limited`…) ou un code (`code=1006`), jamais un texte.
4. Fin de conversation (WebSocket de l'écran fermé) : la session est fermée et comptée.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Ticket refusé à l'ouverture (401/403) | `stt closed ticket`, plus d'écoute |
| Passerelle 503/429/injoignable | jusqu'à 5 essais rapprochés puis `stt closed passerelle …` |
| Fournisseur refuse (`rate_limited`, `auth_error`) | trace `closing <type>`, reprise ; 5 refus d'affilée → `closed fournisseur` |
| File d'envoi pleine (≈ 6 s pendant une reprise) | le plus ancien bloc non envoyé est jeté et compté |
| `{"mode":"long"}` sans fournisseur capable | 503 `stt_stream_unavailable` |

---

## Critères d'acceptation

- [ ] CA1 — Sans corps, `/jarvis/stt-session` est inchangé (même URL `commit_strategy=vad`, maximum 120 s).
- [ ] CA2 — `{"mode":"long"}` rend une URL `commit_strategy=manual` sans seuil de silence, `maxSeconds` 1 800 ; la
      clôture compte jusqu'à 1 800 s ; le balayage compte une session longue oubliée à 1 800 s.
- [ ] CA3 — Le service envoie des blocs de 3 200 octets, un `commit` sur demande, et rend partiels et finals.
- [ ] CA4 — Les finals vides (≈ 36 s) ne sont pas rendus et sont comptés.
- [ ] CA5 — À une coupure, la session est comptée, rouverte, et seul l'audio non confirmé (≤ 2 s) est rejoué.
- [ ] CA6 — Aucun texte entendu ni jeton n'apparaît dans les journaux du service.
- [ ] CA7 — Essai réel contre ElevenLabs : phrase du corpus transcrite après `commit` en < 500 ms ; coupure forcée →
      reprise et phrase suivante transcrite.

---

## Périmètre

### Hors scope

- La décision de fin de tour (qui appelle `commit()`) : SF-257-05. Ici, rien n'appelle encore `commit()` en
  conversation : les finals viennent du *commit* automatique du fournisseur.
- L'écran : aucun changement (le lien de SF-257-03 reçoit déjà les événements).
- Deepgram Flux (adaptateur à mesurer au banc, OQ-24 décision 2) : plus tard, derrière la même interface.

---

## Technique

### Endpoints

| Méthode | URL | Auth |
|---|---|---|
| POST | `/api/jarvis/stt-session` (corps facultatif `{"mode":"long"}`) | session, compagnon, ticket `jarvis-voice` |

### Tables impactées

Aucune (le comptage passe par `JarvisUsageService.addStream`, existant).

### Composants

- Back : `StreamingTranscriptionProvider` (méthode par défaut), `ElevenLabsStreamingTranscriptionProvider`
  (`openManualCommitSession`, `manualSessionUrl`, jeton factorisé), `JarvisSttSessionService` (`openLong`,
  maximum par session), `JarvisSttSessionController` (`OpenRequest`).
- Service : `app/stt.py` (`LongTranscription`), `app/gateway.py` (`open_stt`, `close_stt`), `app/main.py` (branchement,
  envois sérialisés vers l'écran).

### Préoccupations transversales

- **Plans / limites — cochée** : le plafond voix (`JarvisVoiceService.status().budgetReached`) est vérifié à chaque
  ouverture, y compris aux reprises ; l'assiette change (durée de conversation). Composants :
  `JarvisSttSessionService.open/openLong/close/sweep`, `JarvisUsageService.addStream`.

---

## Plan de test

### Unitaires

- [ ] `JarvisSttSessionServiceTest.longSession` — commit manuel, comptage 600 s, balayage à 1 800 s, plafond.
- [ ] `ElevenLabsStreamingTranscriptionProviderTest.manualSessionUrl`.
- [ ] Python `test_stt.py` — blocs de 3 200 o, commit, partiels/finals, finals vides, reprise + rejeu du seul audio non
      confirmé, anneau borné à 2 s, refus du fournisseur, échecs de passerelle bornés, ticket refusé, rien de sensible
      dans `classify`/`repr`.
- [ ] Python `test_app.py` — audio poussé à la session, transcripts et états rendus à l'écran ; client passerelle
      `open_stt`/`close_stt`.

### Intégration

- [ ] Suites complètes back ; essai réel ElevenLabs (CA7, script hors dépôt, clé lue à la volée).

### Isolation

- [x] Inchangée : la session est ouverte et close **sous le ticket de l'utilisateur** (`user_id`), une session
      d'autrui reste introuvable (test existant `closeIsolation`).

## Dépendances

- SF-257-02/03 fusionnées.
