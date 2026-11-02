# Banc d'écoute de Jarvis (F-239 / SF-239-03)

Mesure la justesse et la latence de la transcription de Jarvis, option par option, sur :

- des **phrases types** du PO (`phrases.json` : noms propres, pauses de réflexion, hésitations ; `real: true` = phrases
  réellement dites en prod le 2026-11-02), synthétisées par ElevenLabs (voix George et une voix française) ;
- leur **version dégradée** « vrai micro » : réverbération de pièce, bande téléphone 200-3 800 Hz, Opus 12 kb/s, bruit
  ≈ 15 dB sous la voix (ffmpeg) ;
- les **captures réelles** du PO (`--captures`), gardées 7 jours quand il allume « Garder mes énoncés pour le banc »
  dans les réglages de `/jarvis` (ADMIN seulement).

Options : Scribe v2 batch (avec / sans vocabulaire), `gpt-transcribe` (avec / sans), `gpt-4o(-mini)-transcribe`,
`whisper-1`, flux Scribe v2 Realtime (avec / sans vocabulaire) ; `--vad` mesure les phrases coupées selon le silence
de fin de tour. Mesures : WER (hésitations ignorées, nombres normalisés), noms propres manqués, latence p50 / p90, coût
en € pour 250 min / mois.

```bash
ELEVEN=… OPENAI=… node bench.mjs                 # options batch, propre + dégradé
ELEVEN=… OPENAI=… node bench.mjs --rt --vad      # + flux et seuils de silence
ELEVEN=… CG_API=https://…/api CG_TOKEN=… node bench.mjs --only=B2 --captures
```

Les clés ne sont ni écrites ni journalisées ; l'audio synthétisé est mis en cache dans `out/` (ignoré par git).
Résultats du 2026-11-02 : `docs/features/F-239/CADRAGE-F-239-jarvis-entend-juste.md`.
