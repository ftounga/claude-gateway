# Mini-spec — F-233 / SF-233-04 — Session de transcription en flux, côté passerelle (D5, D13, D-PO-1→4)

## Identifiant
`F-233 / SF-233-04` · Feature parente `F-233` (D5, D13 ; D-PO-1 (b), D-PO-2, D-PO-3, D-PO-4) · Statut `in-review` ·
2026-11-01 · Branche `feat/SF-233-04-session-stt-flux`

## Objectif
> La passerelle émet, pour le compte connecté, une **session de transcription en flux** à durée bornée (jeton
> éphémère du fournisseur, la clé ne quitte jamais le serveur), plafonnée et comptée en euros à la durée.

## Décisions appliquées (validées par le PO le 2026-11-01 : « je valide tout » = propositions par défaut)
- D-PO-1 (b) : **ElevenLabs** (Scribe v2 Realtime) comme fournisseur de flux ; D-PO-2 en découle.
- D-PO-3 : **jeton éphémère**, le navigateur parle directement au fournisseur en WebSocket.
- D-PO-4 : plafond voix mensuel **100 €** (était 15), écoute en flux comprise.
- **Dormant tant que la clé n'est pas posée** : sans `APP_ELEVENLABS_API_KEY`, la route répond 503 et l'écran reste
  sur la dictée batch actuelle ; **aucune donnée ne part** chez le nouveau sous-traitant avant ce geste d'exploitation.

## Contrat API (figé)
| Route | Droit | Corps | Rend |
|---|---|---|---|
| `POST /api/jarvis/stt-session` | accès Forge | `{ workspaceId? }` | `200 { sessionId, provider, url, token, expiresAt, sampleRate, maxSeconds }` |
| `POST /api/jarvis/stt-session/{id}/close` | accès Forge | `{ seconds }` | `204` |

- `url` : WebSocket du fournisseur **sans** le jeton (l'écran ajoute `&token=`), paramètres de session fixés par la
  passerelle (modèle, langue `fr`, `audio_format=pcm_16000`, `commit_strategy=vad`, silence de fin de tour).
- `token` : jeton **à usage unique** obtenu par la passerelle (`POST /v1/single-use-token/realtime_scribe`, en-tête
  `xi-api-key`) ; jamais journalisé.
- Plafond vérifié **avant** l'émission (`JarvisVoiceService.status`) ; sujet rattaché seulement s'il appartient au
  compte (`findByIdAndUserId`).
- Coût compté **à la durée** (D13) : à la clôture, `min(secondes déclarées, temps écoulé depuis l'émission + 5 s,
  maxSeconds)` × tarif/min ; une session non close est comptée à `maxSeconds` par un balayage (chaque minute) ;
  `jarvis_usage_days.stt_stream_seconds` / `stt_stream_cost_usd` ; le plafond somme voix + écoute.

## Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Flux non configuré (pas de clé, fournisseur `none`) | `stt_stream_unavailable` | 503 |
| Plafond du mois atteint | `jarvis_voice_budget` | 429 |
| Fournisseur en échec à l'émission du jeton | `stt_stream_failed` (message du fournisseur non relayé) | 502 |
| Clôture d'une session inconnue / d'autrui | introuvable | 404 |
| `seconds` négatif ou absent | compté à 0 / borné | 204 |
| Sans jeton | refus | 401 |

## Critères d'acceptation
1. Avec une clé, la route rend une URL sans jeton, un jeton, une échéance et la durée maximale.
2. Sans clé, 503, aucun appel sortant.
3. Plafond atteint : 429 avant tout appel fournisseur.
4. La clôture compte la durée bornée ; la session d'autrui est introuvable ; une session non close est comptée au
   maximum par le balayage.
5. Le plafond par défaut vaut 100 € et inclut l'écoute.
6. La clé n'apparaît ni dans la réponse ni dans les journaux.

## Plan de test
- Unitaires : borne de la durée comptée ; construction de l'URL ; coût.
- Intégration : émission (fournisseur simulé), 503 sans clé, 429, clôture et cumul, 404 chez autrui, balayage,
  401 ; non-régression voix (plafond total).

## Impacts
- **Interface** : `StreamingTranscriptionProvider` (nouvelle, Provider Independence) ; implémentation
  `ElevenLabsStreamingTranscriptionProvider` ; `ElevenLabsProperties` (`app.elevenlabs.*`, clé par l'environnement),
  `JarvisSttStreamProperties` (`app.jarvis.stt-stream.*`).
- **Service / contrôleur** : `JarvisSttSessionService`, `JarvisSttSessionController`.
- **Table** : `jarvis_usage_days` + `stt_stream_seconds`, `stt_stream_cost_usd` (migration `197-…`).
- **Config** : `APP_JARVIS_VOICE_MONTHLY_BUDGET_EUR` défaut 100.

## Préoccupations transversales
- **Plans / limites** : oui — `JarvisVoiceService.status/prepare/synthesizeClip` (plafond = voix + écoute),
  `JarvisVoiceProperties.monthlyBudgetEur` (100), `JarvisUsageService` (`addStream`, `monthSpeechCostUsd`),
  `JarvisUsageDayRepository.sumSpeechCostUsdSince`, `JarvisAdoptionService` (coût voix affiché).
- **Auth / Principal** : nouveaux endpoints authentifiés sous la chaîne JWT, aucun changement du Principal.
- **Contexte tenant** : session rattachée au `user_id` ; clôture vérifiée par `user_id`.

## Arbitrages (réversibles)
- Interface **séparée** (`StreamingTranscriptionProvider`) plutôt qu'une méthode ajoutée à `TranscriptionProvider` :
  deux fournisseurs différents (batch OpenAI, flux ElevenLabs) sans deux beans du même type.
- Sessions ouvertes gardées **en mémoire** (un redémarrage perd au plus quelques sessions non comptées).
- **La dictée batch reste sur `whisper-1`** : basculer `APP_STT_MODEL` sur `gpt-transcribe` est un changement de prod
  qu'on ne peut pas vérifier sans la clé (risque de casser la dictée) — à faire par l'exploitation avant le
  26/02/2027 (risque résiduel tracé) ; `HttpTranscriptionProvider` adapte déjà le `response_format` au modèle.

## Hors périmètre
L'écran (SF-233-05) ; d'autres fournisseurs de flux (l'interface les accueille) ; relais WebSocket par le backend.
