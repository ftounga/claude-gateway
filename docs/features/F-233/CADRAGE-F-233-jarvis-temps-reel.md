# CADRAGE F-233 — Jarvis temps réel

**Statut :** cadrage du 2026-11-01 · Famille « Jarvis » : F-230, F-231, F-232, **F-233**.

**Demande PO (2026-11-01)** : que Jarvis « fasse vraiment IA, humain, qui va vite, qui transcrit vite » ; qu'on puisse
**le couper en parlant** pendant qu'il parle, qu'il s'arrête net, comprenne, et ait l'air humain (niveau du mode
vocal de Claude.ai). Budget : pas un problème.

**Règles qui bornent ce cadrage (non négociables)** :
- **Justesse avant tout** : le cerveau reste **le tour d'agent Opus 5.5 du terminal** (même fil, mêmes outils, même
  modèle — F-231 D1). Aucun modèle parole-à-parole ne remplace le cerveau ; aucune mesure de vitesse ne réduit le
  raisonnement (règle « justesse avant coût »).
- **Préfixe stable** : tout ce qui est propre à la voix (consigne orale, « tu as été coupé ici ») va **dans le
  message**, jamais dans la consigne système.
- **Provider Independence** : transcription derrière `TranscriptionProvider`, voix derrière `SpeechProvider`.
- **Mesurer puis corriger** : SF-233-01 est la mesure ; les leviers suivants se décident sur ses chiffres.

## Constat (existant vérifié dans le code, 2026-11-01)

| Étape | Aujourd'hui | Où |
|---|---|---|
| Fin de parole | Silence de **1,4 s** au niveau RMS | `jarvis-mic-meter.ts` `END_OF_SPEECH_MS` |
| Transcription | **Batch** après la fin : clip MediaRecorder → `POST` dictée → `whisper-1` | `DictationService` (F-145), `HttpTranscriptionProvider`, `APP_STT_MODEL=whisper-1` |
| Envoi | `streamChat(..., 'JARVIS')` ; consigne orale **dans le message** | `JarvisOralNote` (D3 F-231) |
| Réponse | Le flux livre déjà les deltas (`onText`), **ignorés** par Jarvis ; il attend `onDone` puis dit `oralPhrase(reply)` | `jarvis.component.ts` `send()` / `onDone()` |
| Voix | `gpt-4o-mini-tts`, voix `onyx`, mp3 en flux (MediaSource) ; plafond **15 €/mois** | `JarvisVoiceService`, `jarvis-voice.service.ts`, `JarvisVoiceProperties` |
| Accusé | « Je m'en occupe. » à **5 s** | `jarvis-oral.ts` `ACK_DELAY_MS` |
| Interruption | **Pas d'interruption acoustique** : appui, mot d'éveil « Jarvis », ou « stop » seul. L'écoute du mot d'éveil se tait pendant la dictée. | `jarvis.component.ts`, `jarvis-wake.ts` |
| Parole pendant un tour | **Déjà une précision** : un envoi pendant un tour vivant devient un *steer* (F-84 / SF-84-06), lu entre deux appels d'outils (F-121 / SF-121-11), jamais une annulation | `AtelierChatController` (`openOrSteer`), `AtelierChatService.takeAndRecordSteers` |
| Écho | `echoCancellation/noiseSuppression/autoGainControl` **seulement** sur le micro du mot d'éveil ; la dictée prend `{ audio: true }` | `jarvis-wake.ts`, `dictation.service.ts` |
| Android (F-232) | `AudioRecord` source `VOICE_RECOGNITION`, pas d'`AcousticEchoCanceler` | `CompanionService.java` |
| Mesure | `jarvis_usage_days` (agrégat jour, sans latence) ; `atelier_turn_measures` (durée du tour, pas de premier jeton) | migrations 190, F-188 |

Estimation **non mesurée** : **10 à 20 s** entre la fin de la parole et le premier son utile.

### Échéances fournisseur découvertes (sources officielles OpenAI)

- **`gpt-4o-mini-tts` est arrêté le 6 janvier 2027** (dépréciation du 1er octobre 2026 ; remplaçant indiqué :
  `gpt-realtime-2.1-mini`, un modèle parole-à-parole, pas un TTS). **La voix de Jarvis et la voix off F-215 se
  taisent dans 9 semaines** si rien n'est fait. [Deprecations OpenAI](https://developers.openai.com/api/docs/deprecations)
- **`whisper-1`, `gpt-4o-transcribe`, `gpt-4o-mini-transcribe` sont arrêtés le 26 février 2027** (annonce du 26 août
  2026) ; remplaçants `gpt-transcribe` (fichier, 0,0045 $/min) et `gpt-live-transcribe` (flux, 0,017 $/min).
  [Deprecations](https://developers.openai.com/api/docs/deprecations),
  [gpt-transcribe](https://developers.openai.com/api/docs/models/gpt-transcribe),
  [gpt-live-transcribe](https://developers.openai.com/api/docs/models/gpt-live-transcribe.md)
- **Hors F-233, à signaler** : l'horodatage par mot de F-215 (`HttpTranscriptionProvider.WORDS_MODEL = "whisper-1"`)
  casse aussi le 26/02/2027 ; la page `gpt-transcribe` ne mentionne pas d'horodatage par mot. À traiter dans une
  feature dédiée (voix off), pas ici.

## Les quatre piliers

1. **Mesurer** chaque étape du tour vocal en prod.
2. **Répondre vite** : réaction < 1 s, transcription en flux, parler pendant que la réponse s'écrit, voix rapide.
3. **Sonner humain** : voix choisie à l'oreille par le PO.
4. **Se laisser couper** (barge-in) : micro ouvert pendant que Jarvis parle, arrêt net, compréhension de ce qui a été
   dit, mémoire de ce qui a été réellement prononcé.

## Décisions

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Le cerveau ne change pas.** Opus 5.5, même fil, mêmes outils, même consigne système. Les gains viennent de **tout ce qui entoure** le tour : écoute, transcription, accusés, synthèse, interruption. Un modèle parole-à-parole (`gpt-realtime-*`) n'est utilisé **ni comme cerveau, ni comme lecteur** du texte (risque de paraphrase = risque de justesse). | Règle absolue |
| D2 | **Mesurer d'abord (SF-233-01).** Une ligne par tour vocal, **sans contenu** : horodatages relatifs (fin de parole, transcription, envoi, premier jeton, première phrase orale complète, premier octet audio, premier son, fin), fournisseurs utilisés, et **compteurs d'interruption** (détectées, annulées, confirmées, acquiescements, faux déclenchements). Endpoint admin p50/p90. Les SF 03→09 gardent leur ordre mais chacune se justifie par un chiffre avant d'être lancée. | Leçon F-188/F-229 : mesurer avant le levier |
| D3 | **Réaction immédiate < 1 s** : dès la fin de parole, un court son/phrase **pré-synthétisé** (« Oui ? », « Je regarde. », « Une seconde. ») joué localement, **zéro appel réseau** ; l'accusé « Je m'en occupe » passe de 5 s à **≈ 2 s** (réglé sur mesure). Les clips sont générés une fois par voix et servis comme ressources statiques (aucun contenu client). | Le silence est ce qui fait « machine » |
| D4 | **Parler pendant que la réponse s'écrit.** Jarvis lit les deltas `onText` : dès que la ligne « 🎙 » contient **une phrase complète**, elle part en synthèse ; la suivante est synthétisée pendant que la première joue (file de phrases). La consigne orale (dans le message) est complétée : *« si tu dois utiliser des outils, commence par une ligne 🎙 qui dit en une phrase ce que tu vas faire »* — c'est le préambule qu'écrit déjà Claude Code ; il **n'ajoute aucune contrainte au raisonnement**. La phrase de résultat (ligne 🎙 de la réponse finale) est dite quand elle arrive. Les règles F-231 restent : confirmation orale (D4 F-231) seulement sur la phrase complète ; une action qui engage n'est jamais exécutée sur un « oui » mal entendu. | Gain principal sur les tours sans outils, accusé réel sur les autres |
| D5 | **Transcription en flux pendant la parole**, derrière une extension de `TranscriptionProvider` (`openStreamingSession` → paramètres de session). **Intégration par jeton éphémère** : la passerelle émet un jeton court (une session, durée bornée, plafond vérifié avant émission), le navigateur parle **directement** au fournisseur en WebSocket ; la clé ne quitte jamais le serveur. Repli : la dictée batch actuelle, passée sur `gpt-transcribe` (réglage `APP_STT_MODEL`, avant le 26/02/2027). Le relais WebSocket par le backend est écarté par défaut : un saut réseau de plus, et un déploiement couperait toutes les écoutes (cf. « déployer tue les tours »). | Latence + clé protégée ; à confirmer par le PO (D-PO-3) |
| D6 | **Fin de phrase plus fine** : fin de tour **donnée par le fournisseur de flux** (détection sémantique / end-of-turn) quand il l'offre, sinon silence réduit à **≈ 0,7 s** (réglable, mesuré). Une fin prématurée n'est pas grave : la suite dite dans la seconde est jointe comme **précision** (mécanisme existant). | 1,4 s → ≈ 0,3-0,7 s |
| D7 | **Voix plus rapide et plus humaine, choisie à l'oreille.** Le remplacement de `gpt-4o-mini-tts` est **obligatoire avant le 6 janvier 2027**. Une **audition à l'aveugle** (SF-233-07) fait entendre au PO les mêmes phrases françaises, voix masculines posées, candidats anonymisés (ElevenLabs Flash v2.5 / v4 Turbo, Cartesia Sonic 3, Azure Neural HD, OpenAI) ; il choisit, la passerelle bascule par configuration. Le candidat retenu doit offrir un **premier octet ≤ 300 ms** en flux. | Échéance + exigence « humain » |
| D8 | **Interruption naturelle (barge-in), pilier.** Pendant que Jarvis parle, le micro reste ouvert (**full duplex**) avec `echoCancellation`, `noiseSuppression`, `autoGainControl` (navigateur) ; une **détection de voix sur l'appareil** (Silero VAD, ONNX/WASM, gratuit, rien ne sort) décide qu'un humain parle — pas le haut-parleur. **Réaction en deux temps** : (1) voix détectée (~150-200 ms) → Jarvis **baisse le volume** (ducking) ; (2) parole confirmée (durée ≥ ~400 ms **ou** premiers mots de la transcription en flux) → il **coupe** sa phrase et **vide la file** de phrases à dire. Non confirmé dans ~1 s → volume rétabli, il continue. | Exigence PO |
| D9 | **Comprendre l'interruption** — classement local, sans appel modèle : **acquiescement** (« ok », « mmh », « oui oui », « d'accord ») → il **reprend** à la phrase coupée ; **arrêt** (« stop », « tais-toi », « chut », « c'est bon ») → silence, et rien d'autre ; **tout le reste** (question, correction) → écouté jusqu'à la fin et traité comme une demande. Le classement réutilise la liste fermée de `isStopCommand` / `classifyConfirmation` ; dans le doute, c'est une demande (jamais une action). | Humain sans risque |
| D10 | **Le fil retient ce qui a réellement été dit.** Jarvis connaît la position de lecture (phrases jouées + fraction de la phrase coupée, via l'horloge audio). Quand une interruption mène à une nouvelle demande, le **message** du tour suivant porte une note fixe : *« [Jarvis — ta réponse orale a été coupée après : « … ». Le reste n'a pas été entendu.] »*. **Jamais dans la consigne système.** Le texte écrit du tour précédent reste intact dans le fil (il est à l'écran) ; seule la part **entendue** est signalée. | Équivalent de la troncature de la Realtime API, sans toucher au préfixe |
| D11 | **Parole pendant un tour d'agent qui tourne** : par défaut, elle devient une **précision** du tour en cours (mécanisme existant, F-84 / SF-84-06 + F-121 / SF-121-11, lue entre deux appels d'outils, rien d'interrompu) ; une phrase d'annulation explicite (« laisse tomber », « annule », « arrête ça ») déclenche l'**interruption** existante (`interruptChat`, comme « stop »). À trancher par le PO (D-PO-5). | Réutilise l'existant, aucun nouveau mécanisme de tour |
| D12 | **Android (F-232)** : le compagnon ouvre le micro en `VOICE_COMMUNICATION` et attache `AcousticEchoCanceler` / `NoiseSuppressor` quand l'appareil les offre, pour que la voix de Jarvis au haut-parleur ne se déclenche pas elle-même. | Cas téléphone posé, haut-parleur |
| D13 | **Coûts comptés et plafonnés** comme aujourd'hui (journal d'usage, `jarvis_usage_days`, en €) ; le flux de transcription est compté **à la durée de session** (c'est ainsi qu'il est facturé) ; une session ne s'ouvre qu'après appui ou mot d'éveil (rien ne sort avant, D5 F-231) et se ferme à la fin de tour. Le plafond voix de 15 €/mois est relevé (D-PO-4). | Budget « pas un problème », mais visible |

## Budget de latence cible

Mesuré par SF-233-01 ; « aujourd'hui » = estimation non mesurée à remplacer par la mesure.

| Étape (depuis la fin réelle de la parole) | Aujourd'hui (estim.) | Cible |
|---|---|---|
| Fin de parole détectée | 1,4 s | **0,3-0,7 s** (fin de tour du fournisseur ou silence 0,7 s) |
| Transcription disponible | +1 à 3 s (batch après coup) | **+0,1-0,3 s** (finalisation du flux) |
| Réaction audible (« Oui ? », clip local) | aucune (accusé à 5 s) | **< 1 s** |
| Premier jeton du cerveau | +1,5-4 s (Opus, gros contexte en cache) — **incompressible sans toucher au raisonnement** | inchangé, **mesuré** |
| Première phrase orale complète | à la fin du tour (`onDone`) | **+0,5-1 s** après le premier jeton |
| Premier son de la réponse | +1-2 s (synthèse mp3) | **≤ 0,3 s** après la phrase complète |
| **Premier son utile, tour sans outil** | **≈ 10-20 s** | **≈ 3-5 s** |
| Tour avec outils | silence puis accusé à 5 s, puis fin | « Je regarde » < 1 s, **annonce de ce qu'il fait ≈ 3-5 s**, résultat à la fin |
| **Interruption : arrêt du son** | appui seulement | **baisse de volume ≤ 200 ms, coupure ≤ 300 ms** après le début de la parole confirmée par la VAD |
| Reprise après acquiescement | — | **≤ 500 ms** |

## Comparatif fournisseurs (chiffres publics, novembre 2026)

Prix en dollars convertis à **0,92 €/$** (taux de la passerelle `APP_COST_USD_TO_EUR`). Latences = annonces
fournisseur sauf mention ; **la qualité en français et la latence réelle depuis la France sont à mesurer** (SF-233-07
et SF-233-01).

### Transcription en flux

| Fournisseur | Prix | Latence annoncée | Fin de tour | Français | Notes |
|---|---|---|---|---|---|
| OpenAI `gpt-live-transcribe` | 0,017 $/min (≈ 0,0156 €) | « faible », réglable | non documentée sur la page | indices de langue | **Aucun nouveau sous-traitant** ; endpoint `realtime/transcription_sessions` |
| Deepgram Flux multilingue | ≈ 0,0078 $/min (≈ 0,0072 €) | fin de tour ≈ 260-400 ms ; « eager end-of-turn » | **oui, native** | oui (10 langues) | **Endpoint UE GA** (`api.eu.deepgram.com`, janv. 2026) |
| ElevenLabs Scribe v2 Realtime | 0,39 $/h (≈ 0,006 €/min) | ≈ 150 ms | VAD | oui (90+ langues) | Résidence UE = Enterprise ; « Zero Retention » sur demande |
| Azure Speech temps réel | ≈ 1 $/h (≈ 0,015 €/min) | — | oui (segmentation) | oui | Microsoft, souvent déjà sous-traitant des banques |

Sources : [gpt-live-transcribe](https://developers.openai.com/api/docs/models/gpt-live-transcribe.md),
[Deepgram Flux (quickstart)](https://developers.deepgram.com/docs/flux/quickstart),
[Flux multilingue, changelog 15/04/2026](https://developers.deepgram.com/changelog/2026/4/15),
[prix Deepgram 2026 (agrégateur)](https://diyai.io/ai-tools/speech-to-text/deepgram-pricing-2026/),
[endpoint UE Deepgram](https://deepgram.com/learn/deepgram-eu-endpoint-now-generally-available),
[ElevenLabs realtime STT](https://elevenlabs.io/realtime-speech-to-text),
[ElevenLabs pricing API](https://elevenlabs.io/pricing/api),
[ElevenLabs data residency](https://elevenlabs.io/docs/overview/administration/data-residency),
[Azure Speech pricing](https://azure.microsoft.com/pricing/details/cognitive-services/speech-services).

### Synthèse vocale

| Fournisseur / modèle | Prix | Premier son | Notes |
|---|---|---|---|
| OpenAI `gpt-4o-mini-tts` (actuel) | ≈ 0,015 $/1 000 car. (config passerelle) | lent (non mesuré) | **arrêté le 06/01/2027** |
| OpenAI `gpt-realtime-2.1-mini` (remplaçant indiqué) | audio en sortie 20 $/M jetons | — | **parole-à-parole** : lire un texte mot pour mot n'est pas garanti → écarté comme lecteur (D1) |
| ElevenLabs Flash v2.5 | 0,05 $/1 000 car. (≈ 0,046 €) | ≈ 75 ms annoncés, ≈ 197 ms mesurés (Vapi) | 32 langues |
| ElevenLabs v4 Turbo | prix à confirmer | ≈ 100 ms annoncés | sorti fin sept./oct. 2026, plus expressif ; **non disponible en routage UE** chez OpenRouter |
| Cartesia Sonic 3 | ≈ 1 crédit/car. (≈ 0,05 $/1 000 car. au plan d'entrée) | ≈ 90 ms annoncés, 166-188 ms mesurés | 42 langues |
| Azure Neural / Neural HD | 15 à 22-30 $/M car. | — | voix HD françaises ; Microsoft |

Sources : [deprecations OpenAI](https://developers.openai.com/api/docs/deprecations),
[gpt-realtime-2.1-mini](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini),
[Flash v2.5, Humanness Index](https://humannessindex.vapi.ai/models/elevenlabs-flash-v2-5),
[Eleven v4 / v4 Turbo](https://propakistani.pk/2026/09/29/elevenlabs-unveils-its-fastest-most-expressive-voice-ai-models-yet/),
[OpenRouter v4 Turbo](https://openrouter.ai/elevenlabs/eleven-v4-turbo),
[Cartesia Sonic 3, mesures](https://humannessindex.vapi.ai/models/cartesia-sonic-3),
[Cartesia Sonic 3 pricing](https://www.eesel.ai/blog/cartesia-sonic-3-pricing),
[Azure Speech pricing](https://azure.microsoft.com/pricing/details/cognitive-services/speech-services).

### Détection de voix et écho (sur l'appareil, gratuit)

- Navigateur : contraintes `getUserMedia` `echoCancellation`, `noiseSuppression`, `autoGainControl` (déjà posées pour
  le mot d'éveil, à étendre à l'écoute).
- VAD : **Silero VAD** en ONNX exécuté en WASM dans le navigateur (≈ 2 Mo, aucune donnée ne sort).
- Android : `AcousticEchoCanceler`, `NoiseSuppressor` (API Android), source `VOICE_COMMUNICATION`.

## Coût mensuel estimé (euros)

Hypothèse d'**usage soutenu** : 100 échanges/jour × 30 jours = **3 000 échanges/mois** ; ≈ 20 s de flux ouvert par
échange (≈ **1 000 min/mois**) ; ≈ 250 caractères dits par échange (≈ **750 000 caractères/mois**). Les clips
pré-synthétisés (D3) coûtent une fois (centimes). La mesure SF-233-01 remplacera ces hypothèses.

| Poste | Aujourd'hui | Scénario A — tout OpenAI (aucun nouveau sous-traitant) | Scénario B — ElevenLabs (Scribe + Flash/v4 Turbo) | Scénario C — Deepgram UE + ElevenLabs/Cartesia |
|---|---|---|---|---|
| Transcription | ≈ 5,5 € (batch whisper) | ≈ 16 € (`gpt-live-transcribe`) | ≈ 6 € | ≈ 7 € |
| Synthèse | ≈ 10 € (`gpt-4o-mini-tts`) | ≈ 10-20 € (Azure HD si retenu, sinon pas de TTS OpenAI pérenne) | ≈ 35 € (Flash) à ≈ 70 € (v4 Turbo, prix à confirmer) | ≈ 35-70 € |
| VAD, écho, mesure | — | 0 € | 0 € | 0 € |
| Cerveau (Opus) | inchangé | **inchangé** (+≈ 60 jetons de consigne par tour, négligeable) | inchangé | inchangé |
| **Total voix** | **≈ 15 €** | **≈ 25-35 €** | **≈ 40-75 €** | **≈ 40-80 €** |

Un abonnement fournisseur (ElevenLabs/Cartesia, ou Enterprise pour la résidence UE) peut s'ajouter : à chiffrer au
choix. Le plafond actuel (15 €/mois) bloquerait tout scénario : à relever (D-PO-4).

Note scénario A : OpenAI ne propose plus de TTS « texte → voix » pérenne après le 06/01/2027 ; « tout OpenAI »
impose donc soit Azure (Microsoft, autre sous-traitant), soit `gpt-realtime-2.1-mini` en lecteur — écarté par D1.

## Découpage

Ordre conseillé : 01 (mesure) d'abord ; **06-07 avant le 6 janvier 2027** (arrêt de la voix actuelle) ; 02-03 sont
sûres et rapides ; 04-05 et 08-10 se décident sur la mesure.

| SF | Objet | Estim. |
|---|---|---|
| SF-233-01 | **Chronométrer le tour vocal** : table `jarvis_turn_timings` (une ligne par tour vocal, `user_id`, horodatages relatifs en ms, fournisseurs, compteurs d'interruption ; aucun contenu ; purge 90 j), marques posées par l'écran + premier jeton côté serveur, `GET /api/admin/jarvis/latency` (p50/p90 par étape, interruptions détectées/annulées/confirmées). | 1,5 j |
| SF-233-02 | **Réagir tout de suite** : clips pré-synthétisés (« Oui ? », « Je regarde. », « Une seconde. ») joués localement à la fin de parole ; accusé à ≈ 2 s ; silence de fin de phrase réglable, défaut 0,7 s. | 1 j |
| SF-233-03 | **Parler pendant que la réponse s'écrit** : lecture des deltas `onText`, découpe en phrases de la ligne 🎙, file de phrases (synthèse de la suivante pendant la lecture), consigne orale complétée (annonce avant outils, dans le message), confirmations F-231 conservées. | 2 j |
| SF-233-04 | **Session de transcription en flux (passerelle)** : `TranscriptionProvider.openStreamingSession`, implémentation du fournisseur retenu (D-PO-1/2), `POST /api/jarvis/stt-session` (jeton éphémère, plafond vérifié, durée comptée en €) ; dictée batch passée sur `gpt-transcribe`. | 1,5 j |
| SF-233-05 | **Écouter en flux (écran)** : WebSocket direct vers le fournisseur, texte partiel affiché sous l'orbe, fin de tour du fournisseur (sinon silence 0,7 s), repli batch automatique, contraintes d'écho sur le micro de l'écoute. | 2 j |
| SF-233-06 | **Voix candidates** : adaptateurs `SpeechProvider` en flux pour 2-3 candidats (ElevenLabs, Cartesia, Azure — selon D-PO-1), tarif par fournisseur pour le compte en €, sélection par configuration ; aucune bascule encore. | 2 j |
| SF-233-07 | **Audition à l'aveugle et bascule** : page admin, mêmes phrases françaises par voix anonymisée (A, B, C…), écoute répétable, vote du PO enregistré ; la voix gagnante devient celle de Jarvis (et proposée pour la voix off F-215) ; clips de SF-233-02 régénérés ; **avant le 06/01/2027**. **Candidats imposés par le PO (2026-11-01)** : les voix ElevenLabs **Daniel** et **George** (britanniques, style « Jarvis », parlant français via le modèle multilingue), en plus d'au moins une voix créée par description (Voice Design : homme posé, élégance britannique, parle français) et d'Onyx (OpenAI) comme référence. Aucun clonage de la voix d'une personne réelle. | 1,5 j |
| SF-233-08 | **Micro ouvert pendant qu'il parle** : full duplex, écho/bruit, Silero VAD (WASM) sur l'appareil, baisse de volume à la voix détectée, coupure à la parole confirmée, file vidée, position de lecture connue ; faux déclenchements comptés (SF-233-01). | 2 j |
| SF-233-09 | **Comprendre l'interruption** : classement acquiescement / arrêt / demande (liste fermée, doute = demande), reprise à la phrase coupée, note « coupé après : … » dans le **message** suivant, parole pendant un tour → précision ou annulation (D11 / D-PO-5). | 2 j |
| SF-233-10 | **Interruption sur Android** : compagnon F-232 en `VOICE_COMMUNICATION` + `AcousticEchoCanceler` / `NoiseSuppressor`, VAD côté appareil ; à valider sur le téléphone du PO (pas de SDK Android ici). | 1,5 j |

**Total : 10 SF, ≈ 17 j.**

## Décisions à faire trancher par le PO

| # | Question | Options | Proposition par défaut |
|---|---|---|---|
| **D-PO-1** | **Confidentialité : quels nouveaux sous-traitants ?** Avec Jarvis, la voix (transcription) et le texte des réponses (synthèse), données client comprises (CAGIP, Edenred), partent chez le fournisseur. | (a) **OpenAI seul** pour la transcription + Azure (Microsoft) pour la voix ; (b) **ElevenLabs** (un seul nouveau sous-traitant pour écouter et parler, « Zero Retention », résidence UE en Enterprise) ; (c) **Deepgram UE** (écoute, traitement dans l'UE) + ElevenLabs ou Cartesia (voix) | **(b)** si le PO accepte un sous-traitant nouveau ; sinon **(a)**. Même régime que F-145 / F-231 D8 dans tous les cas : rien conservé ni journalisé par la passerelle. |
| **D-PO-2** | Fournisseur de **transcription en flux** | `gpt-live-transcribe` / Deepgram Flux UE / ElevenLabs Scribe v2 RT | Découle de D-PO-1 ; la meilleure fin de tour native est Deepgram Flux. |
| **D-PO-3** | **Intégration** du flux | jeton éphémère (navigateur ↔ fournisseur) / relais par le backend | **Jeton éphémère** (latence, déploiement sans coupure) ; le relais reste possible si une banque l'exige. |
| **D-PO-4** | **Plafond voix mensuel** (aujourd'hui 15 €) | 50 € / 100 € / autre | **100 €/mois**, coût visible dans l'écran Jarvis. |
| **D-PO-5** | **Parole pendant un tour d'agent qui tourne** | précision par défaut + annulation sur « laisse tomber » / toujours précision / toujours nouvelle demande | **Précision par défaut, annulation sur phrase explicite** (réutilise le *steer* existant). |
| **D-PO-6** | **Voix** | choisie à l'aveugle (SF-233-07) | Le PO écoute et choisit ; échéance 06/01/2027. |
| **D-PO-7** | **Annonce avant outils** (« Je regarde les logs du build… ») ajoutée à la consigne orale du message | oui / non | **Oui** (aucune contrainte sur le raisonnement, préfixe inchangé). |

## Préoccupations transversales

- **Plans / limites** : nouveau coût (flux de transcription à la durée) et plafond relevé. Composants impactés :
  `JarvisVoiceService` (plafond, conversion €), `JarvisVoiceProperties.monthlyBudgetEur`, `JarvisUsageService` /
  `jarvis_usage_days`, `SpeechUsage` (journal), `DictationService` (repli batch), `CostBudget` (non bloquant, inchangé).
- **Navigation / routing** : aucune nouvelle route utilisateur ; une page admin (audition) sous les routes admin
  existantes (garde ADMIN). À vérifier : `/jarvis`, `/jarvis?alerte=`, `/jarvis?source=compagnon`.
- **Contexte tenant** : le jeton éphémère est émis pour le `user_id` connecté et un sujet qui lui appartient
  (`findByIdAndUserId`, comme `JarvisVoiceService.prepare`) ; les mesures sont filtrées `user_id`.
- **Auth / Principal** : nouvel endpoint authentifié `POST /api/jarvis/stt-session` ; aucun changement du Principal.

## Hors périmètre

- Changer le cerveau, le modèle, ou réduire le raisonnement pour aller plus vite.
- Un modèle parole-à-parole (`gpt-realtime-*`), comme cerveau ou comme lecteur.
- Écoute permanente sans mot d'éveil (rien ne sort avant l'appui ou « Jarvis », D5 F-231).
- Migration de l'horodatage par mot de la voix off F-215 (`whisper-1`, 26/02/2027) : feature à part.
- iPhone.
