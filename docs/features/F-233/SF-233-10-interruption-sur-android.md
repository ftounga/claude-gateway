# Mini-spec — F-233 / SF-233-10 — Interruption sur Android (D8, D12)

## Identifiant
`F-233 / SF-233-10` · Feature parente `F-233` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-233-10-interruption-android`

## Objectif
> Le compagnon Android (F-232) écoute avec la source « communication » et l'annulation d'écho de l'appareil, et se
> laisse **couper en parlant** pendant qu'il dit une annonce : il se tait net et ouvre Jarvis, qui écoute.

## Comportement attendu
- **Micro** (D12) : `AudioRecord` passe de `VOICE_RECOGNITION` à `VOICE_COMMUNICATION` ; `AcousticEchoCanceler` et
  `NoiseSuppressor` sont attachés à la session quand l'appareil les offre (sinon rien, sans erreur) et libérés avec
  le micro. Vaut pour le mot d'éveil, l'écoute de la réponse et l'écoute pendant une annonce.
- **Pendant une annonce** (D8) : le micro reste ouvert (avant : il était rendu pour éviter l'écho). Un mot entendu
  (≥ `MIN_WORD_FRAMES` trames — la détection de voix de l'appareil déjà portée par le mot d'éveil, rien ne sort),
  hors des 300 ms qui suivent le début d'une phrase (convergence de l'annulation d'écho), **coupe** la synthèse
  (`tts.stop()`) et **ouvre Jarvis** comme le mot d'éveil (la PWA écoute aussitôt, SF-232-02 ; sa coupure par la voix
  est celle de SF-233-08). Une seule coupure par prise de parole.
- Sans micro autorisé : l'annonce est dite sans écoute, comme avant.

## Cas d'erreur
| Situation | Comportement |
|---|---|
| Effets audio absents ou en échec | ignorés (journal), l'écoute continue |
| Mot trop court, toux, écho du début de phrase | ignoré |
| Erreur de synthèse | parole close, micro rendu, état recalculé |
| Coupure puis fin d'annonce tardive | ignorée (déjà coupé) |

## Critères d'acceptation
1. Le micro du compagnon utilise `VOICE_COMMUNICATION` et l'annulation d'écho quand elle existe.
2. Un mot dit pendant une annonce la coupe et ouvre Jarvis, une seule fois.
3. Le début de chaque phrase (300 ms) ne déclenche pas de coupure.
4. Sans permission micro, rien ne change.

## Plan de test
- Unitaires (cœur pur, `test-core.sh`) : `SpeechInterruptPolicyTest` (coupure unique, silencieux, mot court, écho
  d'attaque, remise à zéro).
- Compilation de l'application : étape Docker `jarvis-build` (SDK Android).
- **À valider sur le téléphone du PO** (pas d'appareil ici) : écho réel au haut-parleur, coupure perçue.

## Impacts
`CompanionService` (source micro, effets audio, écoute pendant l'annonce, `onInterruptedWhileSpeaking`),
`core/SpeechInterruptPolicy` (nouveau, pur). Aucun endpoint, aucune table, aucune permission nouvelle.

## Préoccupations transversales
Aucune.

## Arbitrage (réversible)
Détection de voix Android = le segmenteur d'énergie du mot d'éveil (pur, déjà embarqué) plutôt que Silero sur
Android : pas de dépendance ONNX native dans l'APK ; la coupure fine (Silero) est celle de la PWA (SF-233-08), que le
compagnon ouvre.

## Hors périmètre
Silero VAD natif ; version iPhone.
