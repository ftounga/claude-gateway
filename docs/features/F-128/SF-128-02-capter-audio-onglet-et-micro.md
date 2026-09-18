# Mini-spec — F-128 / SF-128-02 — Capter l'audio (onglet + micro)

## Identifiant
`F-128 / SF-128-02`

## Feature parente
`F-128` — Capturer et exploiter une réunion Teams

## Statut
`ready`

## Date de création
2026-09-18

## Branche Git
`feat/SF-128-02-capter-audio`

---

## Objectif

> Capturer l'**audio de l'onglet Teams** (Chrome managé) **mixé avec le micro local**, via un script
> injecté par CDP (`getDisplayMedia({preferCurrentTab:true})` + `getUserMedia` + WebAudio + `MediaRecorder`),
> puis **remonter le média** à la gateway (stockage objet) rattaché à l'artefact réunion.

---

## Comportement attendu

### Cas nominal
1. À « Rejoindre & capturer » (SF-128-01), après le join (SF-128-01b), le backend ordonne
   `teams_meeting_capture_start` au runner.
2. Le runner **injecte** dans l'onglet Teams un script qui : capture l'audio de l'onglet
   (`getDisplayMedia`, `preferCurrentTab`) **et** le micro (`getUserMedia`), les **mixe** (WebAudio
   `MediaStreamDestination`), et enregistre en `audio/webm` (`MediaRecorder`) — chunks accumulés dans la page.
3. À l'arrêt (SF-128-01 `stop`), le backend ordonne `teams_meeting_capture_stop` : le runner **stoppe**
   l'enregistrement, **récupère** les octets (base64, par tranches via `Runtime.evaluate`), les **écrit**
   localement puis **les téléverse** vers `POST /runner/teams/meetings/{meetingId}/audio` (jeton runner).
4. La gateway **stocke** l'audio (WorkspaceStorage) et renseigne `meetings.audio_key` / `audio_bytes`.
   L'artefact porte alors un audio (base de la transcription SF-128-04).

### Cas d'erreur (nommés)
| Situation | Comportement |
|-----------|--------------|
| Micro refusé par l'utilisateur | runner : `capture_start` → échec nommé `mic_denied` ; réunion reste rejointe (join ok), pas d'audio |
| Onglet perdu / Chrome injoignable | échec nommé `browser_unreachable` |
| Aucun enregistrement en cours à l'arrêt | `capture_stop` → échec nommé `no_active_capture` |
| Audio vide / trop lourd (> borne) | dépôt refusé 400 / 413, meeting inchangé |
| Dépôt : jeton runner absent/invalide | 401 générique |
| Dépôt : terminal d'un autre compte / réunion inconnue | 404 (isolation) |

---

## Critères d'acceptation
- [ ] `POST /runner/teams/meetings/{meetingId}/audio?workspaceId=…` (jeton runner) stocke l'audio et
      renseigne `audio_key`+`audio_bytes` sur la réunion **du bon couple (user_id, host_id)**.
- [ ] Isolation : un jeton d'un autre compte, un `workspaceId` non possédé, ou une réunion inconnue → 404 ;
      sans jeton → 401 ; sans option Teams → 403 ; workspace non-Teams → 400.
- [ ] Bornes : audio vide → 400 ; au-delà de `MAX_AUDIO_BYTES` → 413.
- [ ] `MeetingResponse` expose `audioBytes` et `hasAudio`.
- [ ] `TeamsMeetingService.create` ordonne `teams_meeting_capture_start` après le join (échec de capture
      **ne défait pas** la réunion rejointe — best-effort, drapeau) ; `stop` ordonne `teams_meeting_capture_stop`.
- [ ] Runner : tools `teams_meeting_capture_start`/`_stop` (hors `CATALOG` agent), script injecté avec
      `userGesture:true`, upload par tranches ; échecs nommés.

---

## Périmètre / Hors scope
- **Transcription** → SF-128-04. **Images clés** → SF-128-03. **Exploitation** → SF-128-05.
- **Vidéo pleine** : hors défaut (seul l'audio est remonté ici ; les images = SF-03).
- **Lecture/streaming de l'audio dans l'UI** : hors scope (l'audio sert la transcription ; un lecteur
  éventuel = évolution).

---

## Technique
### Tables
- `meetings` : **ALTER** (migration `112-meetings-audio.xml`) → `audio_key varchar(300)` (nullable),
  `audio_bytes bigint` (nullable).

### Endpoints
- `POST /runner/teams/meetings/{meetingId}/audio?workspaceId=…` — jeton runner (`X-Runner-Token`),
  corps = octets audio (`audio/webm`).

### Composants
- Backend : `MeetingMediaService` (stockage + MAJ meeting), `RunnerMeetingAudioController`, champs
  `Meeting.audioKey/audioBytes`, `MeetingResponse.audioBytes/hasAudio`, wiring `TeamsMeetingService`,
  constantes `TeamsToolCatalog.MEETING_CAPTURE_START/STOP` (hors CATALOG) + timeouts `RunnerToolGateway`.
- Runner : `TeamsTools.meetingCaptureStart/Stop` + script d'injection (constantes JS) + `MeetingAudioUploader`
  + wiring `ToolStack`.

---

## Plan de test
### Backend (tests réels)
- [ ] `MeetingMediaService` : stocke l'audio, renseigne audio_key/audio_bytes ; réunion d'un autre couple → refus.
- [ ] `RunnerMeetingAudioController` (intégration) : nominal 200 ; 401 sans jeton ; 404 autre compte / réunion inconnue ; 400 vide ; 413 trop lourd ; 403 sans option ; 400 workspace non-Teams.
- [ ] `TeamsMeetingService` : `create` appelle `teams_meeting_capture_start` après join ; `stop` appelle `_stop` ; échec capture_start ne défait pas la création.
### Runner (best-effort)
- [ ] Helpers purs : réassemblage des tranches base64, parsing du résultat d'eval, construction d'URL de l'uploader — testés unitairement.
- [ ] Routage `dispatch` vers les nouveaux handlers ; `capture_stop` sans capture → échec nommé ; tools absents du `CATALOG`.

### Isolation
- [x] Applicable : dépôt filtré `user_id` (jeton) + `host_id` (workspace) ; réunion résolue par (id, user, host).

---

## Notes et décisions
- **DRAPEAU « À VALIDER SUR CALL RÉEL »** : la capture par onglet (getDisplayMedia/getUserMedia/WebAudio/
  MediaRecorder pilotée par CDP `userGesture`), le mixage et la remontée par tranches **ne sont pas
  vérifiables en bac à sable** (pas de Chrome managé + réunion + micro). Livrés best-effort ; validation
  sur le call réel du PO. Les parties **pures** (réassemblage, parsing, URL, stockage, isolation) sont testées.
- **Sensibilité (cadrage §6)** : l'audio **quitte le poste** vers la gateway (stockage objet), puis vers le
  STT en SF-04. Chiffrement en transit (HTTPS). Rétention portée par `meetings.retention_days` (purge = SF-07).
- **Format** : `audio/webm` (Opus) — léger, natif `MediaRecorder`. Défaut raisonnable ; drapeau.
