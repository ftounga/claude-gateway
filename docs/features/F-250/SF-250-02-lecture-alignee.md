# Mini-spec — F-250 / SF-250-02 Lecture alignée

## Identifiant
`F-250 / SF-250-02`

## Feature parente
`F-250` — Le visage de Jarvis (cadrage §3 étape 2, porte P1, P4)

## Statut
`done` (2026-11-07) · **Estimation :** 1,5 j · **Branche :** `feat/SF-250-02-lecture-alignee` · **Dépend de :** SF-250-01

## Objectif
L'écran demande la voix alignée quand le visage est allumé et que la voix du compte sait aligner, joue l'audio du flux
NDJSON exactement comme l'mp3 (même `SourceBuffer`), et garde l'alignement de la phrase en cours lisible sur l'horloge
audio (`audio.currentTime`).

## Comportement attendu

### Cas nominal
1. `JarvisVoiceService.faceStatus()` (`GET /api/jarvis/face`) ; `setAligned(on)` pose `Accept: application/x-ndjson, audio/mpeg`.
2. Réponse `application/x-ndjson` : chaque ligne `{a, c, s, e}` → audio base64 décodé, ajouté au `SourceBuffer`
   (MediaSource) dès son arrivée ; `c/s/e` ajoutés à l'alignement de la phrase. Sans MediaSource : lecture entière
   puis `Blob` (même alignement).
3. Réponse `audio/mpeg` : chemin inchangé, alignement `null`.
4. `playhead()` = `audio.currentTime` de la phrase en cours (−1 en silence/pause) ; `alignment()` = son alignement.
5. Clips : alignement compagnon s'il existe (SF-250-06), sinon `null`.
6. Crochets `onFirstByte` / `onPlaying` (chronomètre SF-233-01, `jarvis_turn_timings`) inchangés.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Ligne NDJSON illisible | ignorée (le reste joue) | — |
| Alignement incohérent (longueurs, non numérique) | le son passe seul, alignement non complété (repli amplitude) | — |
| Base64 illisible | morceau vide, ignoré | — |
| Flux coupé | `endOfStream('network')` comme l'mp3 | — |
| 429 / 503 / 502 | erreurs de toujours (`budget`, `non-configure`, `echec`) | 429/503/502 |

## Critères d'acceptation
- [x] NDJSON en morceaux recollé ligne à ligne ; audio décodé ; alignement cumulé (temps absolus).
- [x] Repli sans erreur si la ligne ou l'alignement est mal formé (P4).
- [x] mp3 inchangé quand le visage ou l'alignement est éteint.
- [x] P1 : premier son ≤ +20 ms p95 (mesure ci-dessous).

## Porte P1 (premier son)
Mesuré le 2026-11-07, 50 phrases entrelacées, voix et modèle de prod, du poste (même réseau que l'écran) :
1ᵉʳ octet mp3 p50 322 / p95 507 ms ; 1ᵉʳ objet aligné complet p50 320 / p95 503 ms ⇒ **−4 ms p95**. Le relais
passerelle écrit chaque ligne dès sa lecture (test `streamsChunkByChunk`) ; côté écran, le décodage base64 d'un
morceau de ≈ 10 ko coûte < 1 ms. **PASS.** Le suivi en production se lit dans `jarvis_turn_timings`
(`first_audio_ms`) après déploiement, comparé à la semaine précédente.

## Plan de test
- Unitaires : `jarvis-voice-aligned.spec.ts` (lignes en morceaux, alignement cumulé, repli), non-régression
  `jarvis-voice.service.spec.ts`.
- Intégration : côté passerelle `JarvisFaceApiIntegrationTest` (SF-250-01).
- Isolation : sans objet côté écran (jeton du compte, route inchangée).

## Impact
- Frontend : `core/services/jarvis-voice.service.ts` ; `jarvis/jarvis.component.ts` (statut du visage).
- Endpoints : consomme `GET /api/jarvis/face`, `POST /api/jarvis/speech` (NDJSON).
- Préoccupations transversales : aucune.

## Hors périmètre
Les visèmes (SF-03), la bouche (SF-06), les clips alignés (SF-06).
