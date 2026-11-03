# Mini-spec — F-244 / SF-244-01 — Étiqueter tout ce que coûte Jarvis

## Identifiant
`F-244 / SF-244-01` · Feature parente `F-244` « Le budget propre de Jarvis » · Statut `in-review` · 2026-11-03 ·
Branche `feat/SF-244-01-etiqueter-couts-jarvis` · Estimation 1 j · Cadrage `CADRAGE-F-244-le-budget-propre-de-jarvis.md`

## Objectif
> Chaque dollar dépensé par Jarvis porte l'origine `JARVIS` et son poste au journal du coût (`usage_turns`), et la
> journée Jarvis (`jarvis_usage_days`) n'en perd aucun — sans encore rien changer au quota (SF-244-02).

## Comportement attendu

### Cas nominal
- `usage_turns` gagne `origin` (`GATEWAY` par défaut, toutes les lignes existantes) et `jarvis_part` (`FAST`, `VOICE`,
  `STT_STREAM`, `STT_UTTERANCE`, `DICTATION`, `OPUS`, `MAIL`, `AGENDA`, nul hors Jarvis) — D1. Enums Java : aucun texte
  libre n'entre dans la table.
- **Cerveau rapide** (échanges, pré-chauffage, appel annexe du portrait) : ligne `ANNEXE` étiquetée `JARVIS / FAST`.
- **Voix** (D2) : la ligne est écrite par la journée Jarvis en `ANNEXE` `JARVIS / VOICE`, **au tarif réel** du fournisseur
  (ElevenLabs ou OpenAI), modèle = fournisseur ; `SpeechUsage.record` (ligne `TOUR` au tarif OpenAI) n'est plus appelé
  pour Jarvis. La voix off des pages (F-215) est inchangée.
- **Écoute en flux** : ligne `ANNEXE` `JARVIS / STT_STREAM`.
- **Retranscription** (D3) : colonnes propres `stt_utterance_seconds` / `stt_utterance_cost_usd` (elle partageait celles
  du flux), ligne `JARVIS / STT_UTTERANCE`.
- **Dictée de repli** (D3) : `POST /api/atelier/transcription` accepte `via=JARVIS` (envoyé par `/jarvis` seulement) ;
  la durée est estimée de la taille (WAV PCM 16 kHz, sinon ≈ 32 kb/s, 1 à 120 s), au tarif
  `app.jarvis.dictation.cost-usd-per-minute` (défaut 0,006 $, whisper-1) ; colonnes `dictation_seconds` /
  `dictation_cost_usd`, ligne `JARVIS / DICTATION`. Sans `via`, rien ne change.
- **Tour Opus né de Jarvis** (`VoiceMark`) : `recordUsage` reçoit l'étiquette `JARVIS / OPUS` (ligne `TOUR`) ; un tour
  Jarvis **coupé** est aussi compté à la journée (`addOpus`, il manquait) et étiqueté — D3.
- `JarvisUsageDay.totalCostUsd()` couvre **tout** (voix, flux, retranscription, dictée, rapide, Opus, courrier,
  agenda) ; `voiceCostUsd()` garde l'assiette voix (mesure admin, plafond voix — qui couvre désormais retranscription et
  dictée, comme avant pour la retranscription).
- Colonnes `mail_cost_usd`, `agenda_cost_usd` prévues pour F-242 / F-243 (0 aujourd'hui).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| L'écriture d'une ligne échoue | Avalée (warn), l'appel Jarvis n'échoue jamais | — |
| `via` inconnu sur la dictée | Ignoré : dictée Gateway ordinaire | 200 |
| Extrait < 2 000 octets | Aucun appel fournisseur, rien compté | 200 |
| Dépense nulle ou négative | Aucune ligne au journal | — |

## Critères d'acceptation
- [x] Un tour Opus délégué écrit `usage_turns` avec `origin = JARVIS` et `jarvis_part = OPUS`.
- [x] Un tour Jarvis coupé est compté dans `jarvis_usage_days` (une fois) et étiqueté.
- [x] La voix ElevenLabs est comptée au tarif ElevenLabs, en `ANNEXE`.
- [x] Une dictée de repli depuis `/jarvis` est comptée ; sans `via`, elle ne l'est pas.
- [x] Les tours tapés (et coupés) restent en `GATEWAY`, sans contact avec la journée Jarvis.
- [x] Isolation : une ligne ne porte que le `user_id` qui l'a causée (B n'a aucune ligne).

## Plan de test
- Unitaires : `UsageLedgerServiceTest` (étiquette par défaut `GATEWAY`), `JarvisVoiceServiceTest` (plus de `TOUR`,
  `addSpeech` au tarif du fournisseur), `JarvisUtteranceServiceTest` (`addUtterance`), `AtelierChatServiceBudgetTest`
  (tour Jarvis étiqueté, tour Jarvis coupé compté, tour tapé coupé sans contact Jarvis).
- Intégration : `JarvisCostLabelIntegrationTest` (voix/flux/retranscription/dictée, rapide/Opus/tapé, dictée par l'API
  avec et sans `via`, isolation A/B), `JarvisUtteranceApiIntegrationTest` (colonnes séparées).
- Liquibase : colonnes additives à défaut, historique en `GATEWAY` (suite complète sur H2).
- Front : spec `jarvis.component` — la dictée de repli part avec `'JARVIS'`.

## Impacts
- Tables : `usage_turns` (+`origin`, +`jarvis_part`, index `user_id, origin, occurred_at`), `jarvis_usage_days`
  (+6 colonnes). Migrations `244-usage-turns-origin.xml`, `244-jarvis-usage-parts.xml`.
- Endpoint : `POST /api/atelier/transcription` (+ paramètre facultatif `via`).
- Composants : `UsageTurn`, `UsageTag` (nouveau), `UsageTurnWriter`, `UsageLedgerService`, `QuotaService`,
  `AtelierChatService`, `JarvisUsageService`, `JarvisUsageDay(Repository)`, `JarvisVoiceService`,
  `JarvisUtteranceService`, `JarvisFastBrainService`, `JarvisAdoptionService`, `DictationController` ;
  front `DictationService`, `JarvisComponent`.

## Préoccupations transversales
- **Plans / limites** : non touché ici (le quota est inchangé : `recordUsage` incrémente toujours `usage_counters`).
  Composants vérifiés : `QuotaService.recordUsage` (toutes surcharges, comportement identique pour `GATEWAY`),
  `UsageLedgerService.recordTurn/recordAnnex` (même écriture qu'avant sans étiquette), plafond voix
  (`sumSpeechCostUsdSince` : + retranscription + dictée — la retranscription y était déjà via le flux).
- Auth / tenant / navigation : non touchés.

## Arbitrages
- Les bancs (cerveau rapide, oral) restent `GATEWAY` : outillage d'admin, pas de l'usage de Jarvis.
- En BYOK, la journée Jarvis compte toujours Opus (inchangé) ; aucune ligne `usage_turns` n'est écrite, comme pour un
  tour tapé BYOK (inchangé).
- Le tarif de la dictée est un réglage (0,006 $/min) ; la durée est estimée, comme la retranscription.

## Hors périmètre
Changer le quota (SF-244-02) ; le plafond Jarvis (SF-244-03) ; l'affichage (SF-244-04).
