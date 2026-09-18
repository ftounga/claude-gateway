# Mini-spec — F-128 / SF-128-03 — Images clés du partage d'écran

## Identifiant
`F-128 / SF-128-03`

## Feature parente
`F-128` — Capturer et exploiter une réunion Teams

## Statut
`ready`

## Date de création
2026-09-18

## Branche Git
`feat/SF-128-03-images-cles`

---

## Objectif
> Pendant la capture d'onglet (SF-128-02), **échantillonner l'écran partagé** en images clés horodatées
> (détection de changement → « deck reconstitué »), puis les **remonter** à la gateway, rattachées à
> l'artefact réunion.

---

## Comportement attendu
### Cas nominal
1. Le script de capture (SF-128-02, déjà injecté) échantillonne périodiquement la **vidéo de l'onglet**
   (le partage d'écran s'y affiche) dans un canvas ; il ne **retient une image** que lorsque le contenu
   **change** (empreinte réduite comparée), horodatée, bornée (nombre, taille).
2. À l'arrêt (`teams_meeting_capture_stop`), après l'audio, le runner **récupère** les images clés (base64,
   par tranches) et **téléverse** chacune via `POST /runner/teams/meetings/{meetingId}/images`.
3. La gateway **stocke** chaque image (WorkspaceStorage, préfixe `frames/`) et met à jour
   `meetings.image_count`. L'artefact porte alors un deck reconstitué.

### Cas d'erreur (nommés)
| Situation | Comportement |
|-----------|--------------|
| Aucune image (pas de partage) | `image_count` reste 0 ; aucun échec |
| Image vide / trop lourde | dépôt refusé 400 / 413 |
| Jeton runner absent/invalide | 401 |
| Terminal d'un autre compte / réunion inconnue | 404 (isolation) |
| Sans option Teams | 403 |
| Au-delà du plafond d'images par réunion | 409 nommé |

---

## Critères d'acceptation
- [ ] `POST /runner/teams/meetings/{meetingId}/images?workspaceId=…` (jeton runner) stocke une image sous
      `teams-meetings/{userId}/{hostId}/{meetingId}/frames/…` et met à jour `image_count` **du bon couple**.
- [ ] Isolation identique au dépôt audio (401/403/404/400/413) ; plafond par réunion (409).
- [ ] `MeetingResponse` expose `imageCount`.
- [ ] Le script de capture échantillonne la vidéo (canvas), ne retient que sur changement, borné
      (`MAX_FRAMES`) ; `teams_meeting_capture_stop` remonte les images après l'audio (best-effort).
- [ ] **Vidéo pleine = OFF par défaut** (seules les images clés remontent ; la vidéo ne quitte pas la machine).

---

## Périmètre / Hors scope
- **Vidéo pleine** : hors défaut (jamais remontée). **Transcription/alignement** → SF-128-04.
  **Exploitation multimodale** → SF-128-05.
- **Détection fine du changement de partage** (début/fin exacts du partage) : v1 = détection de changement
  d'image (heuristique), pas d'analyse sémantique. Drapeau.

---

## Technique
### Tables
- `meetings` : **ALTER** (migration `113-meetings-images.xml`) → `image_count int` (nullable, défaut lu comme 0).

### Endpoints
- `POST /runner/teams/meetings/{meetingId}/images?workspaceId=…` — jeton runner, corps = octets image (jpeg/png/webp).

### Composants
- Backend : `MeetingMediaService.storeImage(...)` (+ compteur), `RunnerMeetingImageController`,
  `Meeting.imageCount`, `MeetingResponse.imageCount`, whitelist `RunnerSecurityConfig`, `MeetingAudioUploader`
  laissé tel quel (audio) + `MeetingImageUploader` (images).
- Runner : `MeetingTabCapture` (échantillonnage vidéo + pull frames), `TeamsTools.meetingCaptureStop`
  étendu (remonte les images après l'audio), `MeetingImageUploader`, wiring `ToolStack`.

---

## Plan de test
### Backend (réels)
- [ ] `MeetingMediaService.storeImage` : stocke sous `frames/`, met à jour `image_count`, plafond ; réunion hors périmètre → refus.
- [ ] `RunnerMeetingImageController` (intégration) : 200 nominal + `image_count` ; 401/403/404/400/413/409.
### Runner (best-effort)
- [ ] Helpers purs : `stripDataUrl`, réassemblage ; le script d'échantillonnage contient canvas/drawImage/toDataURL.
- [ ] `capture_stop` remonte les images (uploader capté) ; sans uploader d'images, l'audio remonte quand même.

### Isolation
- [x] Applicable : même garde que le dépôt audio (user_id du jeton + host_id du workspace + réunion par triplet).

---

## Notes et décisions
- **DRAPEAU « À VALIDER SUR CALL RÉEL »** : l'échantillonnage du partage d'écran (canvas depuis la vidéo
  d'onglet, détection de changement) et la remontée des images ne sont pas vérifiables en bac à sable ;
  livrés best-effort. Parties pures + orchestration/dépôt backend testés.
- **Bornes** : `MAX_FRAMES` par réunion (défaut 60), intervalle d'échantillonnage ~4 s, JPEG qualité 0,6 —
  défauts raisonnables, drapeau. Réutilise la route/pattern du dépôt de moments (F-90) sans le casser.
