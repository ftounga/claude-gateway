# Mini-spec — F-233 / SF-233-06 — Voix candidates (D7, D-PO-1 b)

## Identifiant
`F-233 / SF-233-06` · Feature parente `F-233` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-233-06-voix-candidates`

## Objectif
> La voix de Jarvis peut être servie par **ElevenLabs** (en flux, tarifée à son prix) aussi bien que par OpenAI,
> choisie **par configuration** ; aucune bascule n'est faite ici (la voix reste `openai:onyx`).

## Comportement attendu
- `ElevenLabsSpeechProvider` (implémente `SpeechProvider`) : `POST /v1/text-to-speech/{voice_id}/stream?output_format=mp3_44100_128`,
  en-tête `xi-api-key`, corps `{ text, model_id: eleven_flash_v2_5, language_code: fr }`, mp3 recopié au fil de
  l'eau. `voice` = identifiant de voix ElevenLabs (alphanumérique, 8-64) ; les consignes de ton sont ignorées.
- `SpeechProvider.name()` (défaut `openai`) ; `OpenAiSpeechProvider` devient `@Primary` : la voix off (F-215), les
  échantillons et l'outil `voix_off` restent sur OpenAI, inchangés.
- `app.jarvis.voice.provider` (`openai` défaut | `elevenlabs`) + `app.jarvis.voice.voice` ; `JarvisVoiceService`
  route la lecture et les clips vers le fournisseur choisi, compte au tarif du fournisseur
  (`app.elevenlabs.tts-cost-usd-per-thousand-chars`, défaut 0,05 $), et `GET /api/jarvis/voice` rend `provider`
  (additif). La clé des clips devient `fournisseur:voix`.
- L'écran déclare `ttsProvider` dans la mesure (SF-233-01).

## Cas d'erreur
| Situation | Comportement | Code (route voix) |
|---|---|---|
| Fournisseur `elevenlabs` sans clé | voix indisponible, rien ne part | 503 |
| Identifiant de voix illisible | voix indisponible (aucun chemin forgé) | 503 |
| ElevenLabs répond une erreur | `speech_failed`, message non relayé | 502 |
| Plafond atteint | inchangé | 429 |

## Critères d'acceptation
1. Avec `provider=elevenlabs` et une clé, la lecture part chez ElevenLabs avec l'identifiant de voix, jamais chez OpenAI.
2. Le coût est compté au tarif ElevenLabs.
3. Sans configuration, rien ne change (OpenAI `onyx`, voix off intacte).
4. Aucun identifiant illisible n'atteint l'URL du fournisseur.

## Plan de test
- `ElevenLabsSpeechProviderTest` (serveur HTTP local : chemin, en-tête, corps, flux ; refus sans clé, voix illisible ;
  erreur nommée).
- `JarvisVoiceServiceTest` (routage, tarif, état, indisponible sans clé) ; non-régression `SpeechApiIntegrationTest`,
  `JarvisVoiceApiIntegrationTest`, `JarvisClipApiIntegrationTest`.

## Impacts
`SpeechProvider` (méthode par défaut `name()`), `OpenAiSpeechProvider` (`@Primary`), `ElevenLabsSpeechProvider`
(nouveau), `ElevenLabsProperties` (+ `ttsModel`, `ttsCostUsdPerThousandChars`), `JarvisVoiceProperties`
(+ `provider`), `JarvisVoiceService` (routage, `VoiceRef`, `VoiceStatus.provider`), écran (mesure). Aucune table.

## Préoccupations transversales
Plans / limites : oui — `JarvisVoiceService.costUsd` (tarif par fournisseur), plafond inchangé. Aucune autre.

## Arbitrage (réversible)
**Cartesia et Azure ne sont pas construits** : D-PO-1 (b) retient ElevenLabs comme seul nouveau sous-traitant, et
les candidats imposés par le PO pour l'audition (SF-233-07) sont ElevenLabs (Daniel, George, voix décrite) et OpenAI
(Onyx). L'interface en accueillera d'autres sans réécriture.

## Hors périmètre
L'audition et la bascule (SF-233-07).
