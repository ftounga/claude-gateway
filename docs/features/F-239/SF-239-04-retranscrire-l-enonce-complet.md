# Mini-spec — F-239 / SF-239-04 — Retranscrire l'énoncé complet (tous les chemins d'écoute)

## Identifiant
`F-239 / SF-239-04` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 ·
Branche `feat/SF-239-04-retranscrire-l-enonce`

## Objectif
> Ce que Jarvis comprend n'est plus le texte provisoire de l'écoute en flux mais l'énoncé complet retranscrit à la fin
> du tour par Scribe v2 (batch) avec le vocabulaire de l'univers du compte — sur tous les chemins d'écoute de Jarvis.

## Décision (banc du 2026-11-02, 24 énoncés, voir cadrage)
Scribe v2 batch + vocabulaire : WER 0 %, 0 nom manqué, 630 / 789 ms (p50 / p90) ; sans vocabulaire 6,3 % ;
whisper-1 (chemin actuel de la dictée et de l'interruption) 11 %, 17 noms manqués. Même sous-traitant que l'écoute en
flux (D-PO-1 F-233). Coût ≈ 0,27 $/h (0,22 $ + 0,05 $ de mots-clés), ≈ 1 €/mois en usage soutenu.

## Comportement attendu
### Cas nominal
- **Vocabulaire** (`JarvisVocabulary`) : construit sous `user_id` depuis l'univers (clients, sujets, interlocuteurs,
  clés de tickets et titres de chantiers courts, « Jarvis ») ; nettoyé (caractères interdits `<>{}[]\` retirés,
  ≤ 50 caractères, ≤ 5 mots), dédoublonné, **≤ 100 termes** ; gardé 5 min par compte. Jamais dans une consigne.
- **Écoute en flux** : la session reçoit aussi ce vocabulaire (`keyterms`) — le texte provisoire s'améliore et le
  repli aussi.
- **Retranscription** : `POST /api/jarvis/utterance` (multipart `audio`, ≤ la borne de la dictée) ⇒
  `{ text, provider, ms }`. La passerelle appelle le fournisseur derrière `UtteranceTranscriptionProvider` (Scribe v2
  batch par défaut, langue `fr`, vocabulaire), compte la durée en € (`jarvis_usage_days`, plafond voix), ne garde
  rien (ni disque, ni base, ni journal).
- **Écran — tous les chemins** :
  - flux (`STREAM` / `CONV_STREAM`) : l'écoute garde l'audio envoyé (pré-écoute comprise) ; à la fin de la parole,
    la retranscription part **aussitôt**, en parallèle de l'attente du dernier segment ; le texte en flux reste
    affiché comme provisoire ;
  - dictée (repli sans flux) : l'enregistrement part à la retranscription au lieu de `whisper-1` ;
  - interruption (`onBargeInSpeech`) : l'audio capté part à la retranscription au lieu de `whisper-1`.
  Aucun chemin de Jarvis ne passe plus par `/api/atelier/transcription` (la dictée du terminal, hors Jarvis, ne
  change pas).
- **Repli** : retranscription en échec, éteinte (503), budget atteint (429) ou plus lente que **2 500 ms** ⇒ texte du
  flux (ou, sans flux, l'ancienne dictée). La mesure du tour porte `sttProvider = elevenlabs-batch` ou le repli.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Aucun audio | Refus | 400 |
| Audio trop gros | Refus avant lecture | 413 |
| Fournisseur non configuré / `none` | Rien n'est envoyé | 503 |
| Plafond voix du mois atteint | Rien n'est envoyé | 429 |
| Échec du fournisseur | Message générique (détails non relayés) | 502 |
| Sans accès Forge | Refus | 403 |

## Critères d'acceptation
- [ ] Le vocabulaire d'un compte ne contient jamais un nom d'un autre compte (test d'isolation).
- [ ] Le vocabulaire respecte les bornes du fournisseur (≤ 100 termes, ≤ 50 caractères, ≤ 5 mots, sans `<>{}[]\`).
- [ ] L'appel au fournisseur porte `model_id=scribe_v2`, `language_code=fr` et les `keyterms` (test du corps).
- [ ] Flux : le texte envoyé au cerveau est la retranscription ; repli sur le flux au-delà de 2,5 s ou en erreur.
- [ ] Interruption et dictée : plus aucun appel à `dictation.transcribe` / `dictation.stop` transcrivant pour Jarvis.
- [ ] Durée comptée en € dans `jarvis_usage_days` ; 429 au plafond.

## Périmètre — hors scope
Relecture contextuelle `compris:` (SF-239-05) ; capture audio réelle et banc complet (SF-239-03) ; fin de tour
(SF-239-10).

## Contraintes de validation
| Champ | Obligatoire | Borne |
|---|---|---|
| `audio` | Oui | ≤ `app.stt.max-audio-bytes` (borne de la dictée) |
| vocabulaire | — | ≤ 100 termes, ≤ 50 car., ≤ 5 mots |

## Technique
- Back : `stt/utterance/UtteranceTranscriptionProvider` (+ `ElevenLabsUtteranceTranscriptionProvider`),
  `jarvis/stt/JarvisUtteranceProperties` (`app.jarvis.utterance.*`), `JarvisVocabulary`, `JarvisUtteranceService`,
  `JarvisUtteranceController` ; `StreamingTranscriptionProvider.openStreamingSession(…, keyterms)`.
- Front : `jarvis.service.ts` (`utterance(blob)`), `jarvis-stream-listener.ts` (audio gardé), `dictation.service.ts`
  (`stopRecording()` rend l'enregistrement sans le transcrire), `jarvis.component.ts`.
- Aucune table, aucune migration.

## Plan de test
- Unitaires : vocabulaire (bornes, nettoyage, ordre, cache) ; fournisseur (corps multipart, erreurs) ; service
  (budget, comptage).
- Intégration : `POST /api/jarvis/utterance` 200 / 400 / 403 / 413 / 429 / 503 ; isolation du vocabulaire.
- Front : flux ⇒ retranscription envoyée au cerveau ; repli à 2,5 s ; interruption et dictée passent par la
  retranscription ; audio gardé par l'écoute (pré-écoute comprise).

## Configuration prod
- `APP_JARVIS_UTTERANCE_PROVIDER=elevenlabs` (défaut), `APP_JARVIS_UTTERANCE_MODEL=scribe_v2` (défaut) — rien à poser
  si les défauts conviennent ; `APP_ELEVENLABS_API_KEY` déjà présente. Coupe-circuit : `APP_JARVIS_UTTERANCE_PROVIDER=none`.
