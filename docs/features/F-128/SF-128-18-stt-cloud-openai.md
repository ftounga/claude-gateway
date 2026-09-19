# Mini-spec — F-128 / SF-128-18 — Transcription via STT cloud (OpenAI), activation manuelle par réunion

> Cadrage du 2026-09-19 (PO). **Livraison demandée.** Remplace la version « auto-hébergé » : après
> chiffrage (auto-hébergé ≈ 120 €/mois nœud dédié, ou effort d'infra pour le à-la-demande ; cloud ≈ 3 €/mois),
> le PO choisit **le cloud OpenAI**, avec **activation manuelle réunion par réunion** et **avertissement de
> confidentialité** avant tout envoi.

## Identifiant

`F-128 / SF-128-18`

## Feature parente

`F-128` — Vigie Teams (capture, transcription, exploitation des réunions)

## Statut

`in-progress`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-18-stt-cloud-openai`

---

## Objectif

Brancher le pipeline de transcription existant (SF-128-04) sur **OpenAI** et exiger un **avertissement de
confidentialité confirmé, réunion par réunion**, avant tout envoi d'audio hors du poste.

---

## La décision (cadrage PO)

- **Service** : **OpenAI** (Whisper / `gpt-4o-transcribe`), **compatible avec le pipeline SF-128-04** déjà
  livré (`HttpTranscriptionProvider`, API compatible Whisper) → intégration directe, aucune réécriture du
  worker ni des endpoints.
- **Manuel, par réunion** : bouton **« Transcrire »** (SF-128-05) sur une réunion précise. **Rien
  d'automatique.** Ne coûte que quand le PO le déclenche.
- **Confidentialité assumée (DRAPEAU FORT)** : l'audio de la réunion **quitte le poste vers OpenAI**. Le PO
  a explicitement choisi le cloud **en pilotant au cas par cas** : il ne transcrit **que** des réunions non
  sensibles, **jamais** du CAGIP confidentiel. → **Avertissement clair AVANT envoi**, à confirmer.

---

## Comportement attendu

### Cas nominal

1. Réunion avec audio, sans transcript encore : l'écran réunion affiche le bouton **« Transcrire »**.
2. Au clic, un **dialogue d'avertissement** s'ouvre : « L'audio de cette réunion sera envoyé à **OpenAI**
   (hors du poste). Ne pas utiliser pour une réunion confidentielle. » + bouton de **confirmation explicite**.
3. Si l'utilisateur **confirme** → appel `POST …/transcribe` (202), la réunion passe `PENDING`, l'écran
   affiche « Transcription demandée… ».
4. Le worker async (SF-128-04, inchangé) appelle OpenAI :
   `POST https://api.openai.com/v1/audio/transcriptions`, `multipart/form-data` (`file` + `model` +
   `response_format`), auth `Authorization: Bearer <clé>`. Réponse `{text, language, segments?}` →
   transcript horodaté rattaché.
5. L'écran reflète l'état : « disponible » / « en cours » / « indisponible ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Utilisateur **annule** le dialogue | **Aucun** appel `…/transcribe`, aucun envoi | — |
| Clé OpenAI absente (`app.stt.api-key` vide) | « STT non configuré » ; **aucun octet ne part** | 503 `stt_not_configured` |
| Réunion d'un autre utilisateur / poste | Accès refusé (isolation `user_id` + `host_id`) | 404 |
| OpenAI répond une erreur (4xx/5xx) | Transcript `FAILED`, message « la transcription a échoué, réessayer » | 202 puis FAILED async |
| Audio trop volumineux (> `max-audio-bytes`, 25 Mo) | FAILED nommé, aucun coût inutile | 202 puis FAILED async |

---

## Critères d'acceptation

- [ ] `HttpTranscriptionProvider` appelle OpenAI : `{base-url}/audio/transcriptions`, multipart `file`+`model`,
      auth `Bearer`, réponse `{text,language,segments?}` parsée en transcript horodaté.
- [ ] `model` configurable (`APP_STT_MODEL`, défaut `whisper-1`) ; `gpt-4o-transcribe` fonctionne
      (`response_format` adapté : `verbose_json` pour Whisper, `json` pour `gpt-4o-transcribe`).
- [ ] **Clé OpenAI en secret** (`APP_STT_API_KEY`, jamais en dur, jamais exposée au client) ; l'appel part de
      la **gateway**, pas du navigateur.
- [ ] Sans clé → 503 `stt_not_configured`, **aucun appel réseau émis** (l'audio ne part pas).
- [ ] **Aucun appel `…/transcribe` sans avertissement + confirmation** ; le message nomme explicitement
      **OpenAI** et « **hors du poste** ». Par réunion, à chaque fois.
- [ ] L'écran reflète l'état : disponible / en cours (PENDING/TRANSCRIBING) / indisponible.
- [ ] Isolation `user_id` + `host_id` inchangée ; rétention SF-128-07 inchangée.
- [ ] Non-régression SF-128-04 (pipeline), SF-128-05 (exploitation), SF-128-17 (lecteur audio).

---

## Périmètre

### Hors scope (explicite)

- Le **live** (SF-128-08) : non retenu.
- STT **auto-hébergé** : écarté (coût), réactivable si un besoin banque strict apparaît.
- Aucune nouvelle table, aucun nouvel endpoint, aucun changement du worker : la config + l'UI de consentement.
- Aucun runner touché.

---

## Technique

### Endpoint(s)

Inchangés (SF-128-04) — aucun nouvel endpoint.

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/vigie/hosts/{hostId}/meetings/{meetingId}/transcribe` | Oui | Teams + Vigie |
| GET | `/vigie/hosts/{hostId}/meetings/{meetingId}/transcript` | Oui | Teams + Vigie |

### Tables impactées

Aucune. Le transcript est déjà porté par l'entité réunion (SF-128-04).

### Migration Liquibase

- [x] Non applicable — aucun changement de schéma.

### Configuration

| Variable | Où | Valeur | Secret |
|----------|-----|--------|--------|
| `APP_STT_BASE_URL` | `backend-config` (configmap) | `https://api.openai.com/v1` | Non |
| `APP_STT_MODEL` | `backend-config` (configmap) | `whisper-1` (ou `gpt-4o-transcribe`) | Non |
| `APP_STT_API_KEY` | `backend-secrets` (secret k8s) | *(fournie par le PO au déploiement)* | **Oui** |

### Composants Angular

- `MeetingDetailPageComponent` — au clic « Transcrire », ouvrir `ConfirmDialogComponent` (dialogue réutilisable,
  conforme design system) nommant OpenAI + « hors du poste » ; n'appeler `service.transcribe` que si confirmé.

---

## Préoccupations transversales — analyse d'impact

- **Confidentialité (invariant)** : audio hors poste. Composants impactés : `MeetingDetailPageComponent`
  (dialogue de consentement, seul point de déclenchement de `…/transcribe`), `HttpTranscriptionProvider`
  (clé jamais loguée, `TranscriptionProperties.apiKey` lue depuis l'env). Vérifié : la clé n'est jamais
  renvoyée au client (aucun DTO ne l'expose) ; l'appel OpenAI part du worker backend.
- **Plans / limites** : coût OpenAI/minute borné par `max-audio-bytes` (25 Mo) + déclenchement manuel.
  Composant : `TranscriptionProperties.maxAudioBytes` (inchangé). Aucun service de quota nouveau appelé.
- **Auth / tenant** : inchangé. Le seul point d'appel reste le controller SF-128-04, gardé par
  `TeamsAccessService.requireAccess()` + `RadarScopeResolver.requireInVigie(user_id, hostId)`. Pas de
  nouveau Principal, pas de nouvelle résolution de tenant.
- **Navigation / routing** : inchangé (dialogue modal, pas de route).

---

## Plan de test

### Tests unitaires (backend)

- [ ] `HttpTranscriptionProviderTest` — configuré (mock serveur local) : `transcribe` produit un transcript,
      forme d'appel OpenAI correcte (multipart, `model`, `Bearer`). *(existant, conservé)*
- [ ] `HttpTranscriptionProviderTest` — `response_format` adapté au modèle : `verbose_json` pour `whisper-1`,
      `json` pour `gpt-4o-transcribe`. *(ajouté)*
- [ ] `HttpTranscriptionProviderTest` — non configuré (pas de clé) : `TranscriptionProviderUnavailableException`,
      **aucun appel réseau**. *(existant, conservé)*

### Tests d'intégration (backend)

- [ ] `POST …/transcribe` sans clé → 503 `stt_not_configured`. *(existant, conservé)*
- [ ] `POST …/transcribe` avec provider mocké → 202, transcript rattaché. *(existant, conservé)*

### Tests composant (frontend)

- [ ] Clic « Transcrire » → ouvre le dialogue nommant **OpenAI** + « hors du poste ».
- [ ] Confirmation → `service.transcribe` appelé une fois.
- [ ] Annulation → `service.transcribe` **jamais** appelé.

### Isolation utilisateur

- [ ] Applicable — un autre utilisateur/poste → 404 (garde inchangée SF-128-04). *(existant, conservé)*

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-04` (pipeline STT) — done
- `SF-128-05` (exploitation, bouton Transcrire) — done
- `SF-128-17` (lecteur audio in-app) — done

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Adaptation `response_format`** : OpenAI n'accepte `verbose_json` (segments horodatés) que pour les
  modèles Whisper. `gpt-4o-transcribe` n'accepte que `json`/`text` : on envoie `json` pour ces modèles, et
  le rendu retombe sur le texte brut (pas de segments). Décision technique documentée ici et dans la PR.
- **Clé réelle** : jamais commitée. Le code lit `APP_STT_API_KEY` depuis l'environnement/secret ; le PO la
  fournit au déploiement.
- **NE PAS déployer** dans le cadre de cette subfeature.
