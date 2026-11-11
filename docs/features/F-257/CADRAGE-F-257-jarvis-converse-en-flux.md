# Cadrage F-257 — Jarvis converse en flux

**Statut :** cadrage du 2026-11-11 · aucun code · famille « Jarvis » (F-233, F-251, F-252, F-253 ; F-255 rejeu de
séance et F-256 banc à vraie voix, en cours ailleurs).

**Demande PO (2026-11-11)** : un assistant vocal **conversationnel mains libres**, au niveau des modes voix de ChatGPT
et de Claude.ai ; il parle « comme en interview », sans geste. **Push-to-talk refusé définitivement.**

**Règles qui bornent ce cadrage** : justesse avant vitesse et coût (le cerveau, ses outils, sa consigne et ses bancs ne
changent pas) ; Gateway-First (la passerelle orchestre, facture, journalise) ; Provider Independence (cerveau derrière
`AIProvider`, écoute derrière `StreamingTranscriptionProvider`, voix derrière `SpeechProvider`) ; aucune bascule sans
mesure.

## 1. Constat (séance du 09/11, chiffres fournis par le PO et le journal d'écoute)

| # | Défaut | Preuve | Cause de fond |
|---|---|---|---|
| 1 | Le flux de transcription se ferme à l'ouverture **une fois sur deux** | 31 replis ; fermeture serveur en 250-300 ms | L'écran ouvre **une nouvelle session Scribe par tour** (`JarvisSttSessionService.open`, jeton éphémère) : limite de débit/simultanéité très probable (§3.4) |
| 2 | Fin de tour décidée **au silence, dans le navigateur** | 1,2 à 2 s d'attente (`END_OF_SPEECH_MS` 1 200, grâce 700, `SUSPENDED_EXTRA_MS` 1 500) ; bruits pris pour de la parole ; débuts coupés | Le silence ne dit pas si la phrase est finie ; la session ouverte au tour rate les premières syllabes |
| 3 | Chaîne **séquentielle** | Premier son **5,5-6 s** perçues : fin de parole 1,2-2 s → transcription 0,8 s → prép. 0,3 s → 1er jeton `claude-sonnet-5-5` 1,9 s → phrase et contrôles 0,8 s → voix 0,4 s ; réponse spéculative (F-252) adoptée **1 fois sur 5** | Chaque étape attend la précédente ; la spéculation dépend d'un texte de flux qui diverge du texte de référence batch |

Les correctifs F-251/F-252 ont traité les symptômes **dans** cette architecture (tampon de 1,5 s, grâce, spéculation,
préparation parallèle). Le plafond est structurel : ouvrir une session par tour et décider le tour au silence sur le
téléphone ne donnent ni la fiabilité ni la latence des références.

## 2. Comment font les références

Recherche du 2026-11-11. « (tiers) » = source non fournisseur, à revérifier avant de citer un chiffre.

| Référence | Ce qui est public | Ce qu'on en retient |
|---|---|---|
| **ChatGPT Advanced Voice / OpenAI Realtime** | Modèle parole-à-parole `gpt-realtime` (GA 28/08/2025), transports WebRTC, WebSocket, SIP. Fin de tour `server_vad` (seuil, `prefix_padding_ms`, `silence_duration_ms`) ou **`semantic_vad`** : un classifieur juge si la pensée est finie, réglé par `eagerness` (low/medium/high/auto). Interruption : en WebRTC le serveur coupe lui-même l'audio non entendu ; en WebSocket le client arrête la lecture sur `speech_started` et envoie `conversation.item.truncate` avec `audio_end_ms`. Session ≤ 60 min. | **Fin de tour sémantique côté serveur**, troncature à ce qui a été entendu (déjà notre F-233 D10). Le parole-à-parole reste écarté comme cerveau (F-233 D1). |
| **Claude.ai voix** | Mains libres par défaut (« répond aux pauses naturelles »), push-to-talk en option, l'utilisateur peut couper Claude en parlant ; mêmes modèles que le texte ; audio converti en texte puis supprimé. ElevenLabs figure parmi les sous-traitants d'Anthropic pour la synthèse. | Chaîne **parole → texte → modèle → voix** (déduite de l'aide), comme Jarvis. **Non public** : fournisseur de transcription, méthode de détection de tour, latences, annulation d'écho. Rien n'est inventé ici. |
| **LiveKit Agents** | Audio en WebRTC via un SFU LiveKit ; un worker (Python ou Node, **pas de SDK Java**) rejoint la salle ; chaîne STT→LLM→TTS ou modèle temps réel. **Détecteur de fin de tour** : modèle texte multilingue (Qwen2.5-0.5B, 396 Mo, < 500 Mo de RAM, CPU, 50-160 ms, français inclus) ou détecteur audio (14 langues). Délais par défaut min 0,5 s / max 3 s (0,3 / 2,5 s avec le détecteur audio). **Génération préemptive** activée par défaut (le LLM part au texte final avant la confirmation du tour). Interruption : `min_duration` 0,5 s. | Préemption + fin de tour par modèle + plafond de repli : le schéma cible. Le SFU est lourd pour notre cluster. |
| **Pipecat (Daily)** | Cadre Python de pipeline de trames ; transports `FastAPIWebsocketTransport`, `SmallWebRTCTransport` (pair à pair), `DailyTransport` ; Silero VAD local ; interruptions activées par défaut ; service STT ElevenLabs. **Smart Turn v3** : poids ouverts (BSD-2), **23 langues dont le français**, ≈ 8 M paramètres (base Whisper-Tiny), 8 Mo en int8, **≈ 12 ms sur CPU moderne, 60 ms sur petite instance AWS** ; v3.1/v3.2 remplaçables à chaud (même ONNX). Décide sur l'**audio** (intonation), pas seulement sur le texte. | Cadre léger, sans SFU, WebSocket possible ; le détecteur audio comprend une pause de réflexion qu'un silence prend pour une fin. |
| **Budgets publiés** | Cible courante < 800 ms p50 / < 1 500 ms p95 de voix à voix (fin de tour 150-200, STT 100-200, 1er jeton 200-500, TTS 80-200, réseau 20-100 ms) ; mesure de production 1,4-1,7 s en moyenne sur 4 M d'appels, le LLM pesant ≈ 70 % (données fournisseur). | Notre cerveau (Sonnet, gros contexte, 1er jeton 1,1-1,9 s) rend < 1 s hors de portée sans toucher au modèle : la cible de 2 s est réaliste, pas 0,8 s. |

Sources : [OpenAI gpt-realtime](https://openai.com/index/introducing-gpt-realtime/),
[Realtime VAD](https://developers.openai.com/api/docs/guides/realtime-vad),
[Realtime conversations / troncature](https://developers.openai.com/api/docs/guides/realtime-conversations),
[Claude — voice mode](https://support.claude.com/en/articles/11101966-use-voice-mode),
[ElevenLabs sous-traitant d'Anthropic (tiers)](https://the-decoder.com/anthropics-claude-uses-elevenlabs-technology-for-speech-features-rather-than-an-in-house-model/),
[LiveKit Agents](https://docs.livekit.io/agents/),
[LiveKit turn detector](https://docs.livekit.io/agents/build/turns/turn-detector/),
[LiveKit réglage des tours](https://docs.livekit.io/agents/logic/turns/tuning/),
[Pipecat transports](https://docs.pipecat.ai/learn/transports),
[Pipecat entrée vocale](https://docs.pipecat.ai/learn/speech-input),
[Pipecat STT ElevenLabs](https://docs.pipecat.ai/server/services/stt/elevenlabs),
[Smart Turn v3 (Daily)](https://www.daily.co/blog/announcing-smart-turn-v3-with-cpu-inference-in-just-12ms/),
[smart-turn (GitHub)](https://github.com/pipecat-ai/smart-turn),
[budget 800 ms (tiers)](https://www.twig.so/blog/voice-ai-agents-latency-budget-800ms),
[Hamming, mesures de production (tiers)](https://hamming.ai/blog/voice-ai-latency-whats-fast-whats-slow-how-to-fix-it).

**Le point commun des références** : un **flux audio continu** vers un serveur, une **session d'écoute qui ne se rouvre
pas à chaque tour**, une **fin de tour décidée par un modèle côté serveur** avec un plafond de silence en repli, une
**génération qui démarre avant la confirmation** et une **interruption gérée côté serveur** (troncature à ce qui a été
entendu). Jarvis fait l'inverse sur les trois premiers points.

## 3. Architecture cible

### 3.1 Principe

**Un flux audio permanent par conversation, des décisions de tour prises côté serveur, une session de transcription
longue, une chaîne en recouvrement.**

```
Navigateur / PWA (micro AEC+NS+AGC, lecteur, visage F-250)
   │  WebSocket unique, PCM 16 kHz montant ; audio + événements descendants
   ▼
jarvis-voice (nouveau service, cluster EKS)            ← traitement du signal uniquement, aucune clé fournisseur
   ├─ VAD Silero (local)  ─┐
   ├─ détecteur de fin de tour sémantique smart-turn (local, CPU)
   ├─ session STT longue ──┼─► fournisseur (jeton éphémère émis par la passerelle)
   ├─ orchestrateur de tour : candidat → préemption → confirmation / annulation
   └─ interruption (VAD pendant la voix, coupure, position entendue)
   │  HTTP interne (cluster)
   ▼
backend Spring (inchangé sur le fond) : /jarvis/stt-session (jeton), /jarvis/converse (cerveau rapide, SSE),
/jarvis/converse/verdict (adoption), /jarvis/speech (voix + alignement), budget, journaux, jarvis_turn_timings
```

### 3.2 Ce qui change, ce qui ne change pas

| Inchangé | Change |
|---|---|
| Cerveau rapide `JarvisFastBrainService` (modèle, consigne v20+, outils `afficher`/`univers`, gardes, « compris: ») | **Écoute** : plus de session STT par tour ; une session longue tenue par le service, rouverte seule si le fournisseur la coupe, avec rejeu du tampon |
| Délégation au poste, poste éteint (SF-253-05), sujets, fil, mémoire, portrait | **Fin de tour** : décidée par le serveur (VAD + modèle de fin de tour), plus par le RMS du navigateur |
| Budget, conversion €, `jarvis_usage_days`, `jarvis_turn_timings`, `jarvis_listen_traces` (colonnes ajoutées) | **Recouvrement** : le cerveau part au *candidat* fin de tour, est annulé si la parole reprend, libéré à la confirmation |
| `SpeechProvider` ElevenLabs + alignement (visage F-250), clips de réaction | **Voix** : premier fragment synthétisé dès qu'il est sûr (pas la phrase entière), audio renvoyé dans le même WebSocket |
| Règles F-252 D1/D2 : rien n'est dit ni écrit sans adoption | La référence n'est plus la retranscription *batch* : c'est le texte **final** de la session longue (même normalisation) |
| Ancienne boucle (`jarvis.component.ts`) | Conservée derrière un drapeau jusqu'à la bascule mesurée (§5) |

**Décisions antérieures explicitement révisées (CLAUDE.md : ne rien remplacer en silence)** :
- **F-233 D5 / D-PO-3** (« navigateur ↔ fournisseur en direct, relais écarté ») : l'audio passe désormais par un service
  à nous. Raison : c'est la condition pour tenir une session longue et décider le tour côté serveur. La clé reste
  côté passerelle (le service reçoit un jeton éphémère, comme l'écran aujourd'hui).
- **F-233 D6 / F-239 SF-239-10 / F-251 SF-251-04** (seuils de silence, phrase en suspens, grâce) : remplacés par le
  détecteur sémantique ; les seuils restent comme **plafond** de repli (une phrase en suspens n'est jamais close avant
  `SUSPENDED_EXTRA_MS`).
- **F-252 D3** (verdict entre pods par la base) : inutile pour la nouvelle boucle (le service tient le tour de bout en
  bout) ; l'endpoint `verdict` est réutilisé tel quel pour déclencher les effets à l'adoption.
- **F-231 D5** (rien ne sort avant le réveil) : **maintenu**. Le flux ne s'ouvre qu'une fois la conversation engagée
  (SF-233-12) et se ferme après 60 s de silence (SF-233-11).

### 3.3 Où vit chaque morceau — variante de pile signalée

**Variante de pile (à trancher, D-PO-1)** : un **service Python** apparaît dans le cluster. La pile du projet est
Java/Angular ; il existe un précédent de service séparé non-Java (`diagram-renderer`, Node + scripts Python, F-142).

| Option | Description | Pour | Contre |
|---|---|---|---|
| **A (recommandée)** — `jarvis-voice` en Python avec **Pipecat** | Pipeline de trames Pipecat : transport WebSocket, Silero VAD, smart-turn, services *maison* minces : STT (jeton passerelle), « LLM » = appel SSE à `/jarvis/converse`, TTS = appel à `/jarvis/speech` | Interruption, préemption, VAD, fin de tour : **cadre éprouvé**, pas une 4ᵉ réimplémentation maison (leçon « parité » : la boucle maison reconstruite pièce par pièce est la cause des défauts) ; un déploiement du backend (fréquent) **ne coupe plus** la conversation audio ; aucune clé fournisseur dans le service | Nouveau langage en production (image, CI, dépendances) ; un saut réseau interne (≈ 1-5 ms) |
| B — tout dans le backend Java | Spring WebSocket (déjà utilisé par le runner), Silero et smart-turn en ONNX Runtime Java | Pas de nouvelle pile | Tout à écrire (orchestration de tour, préemption, interruption) ; chaque déploiement backend coupe toutes les conversations ; mémoire du pod (limite 2 Gi) ; WebSocket épinglé à un pod parmi 2-4 |
| C — LiveKit Agents | SFU WebRTC + worker agent (Python/Node) | Le plus complet (WebRTC, détecteur de tour multilingue) | Un serveur SFU à héberger (ou LiveKit Cloud = nouveau sous-traitant de l'audio) ; le plus lourd pour un cluster sans marge mémoire |

Dans les trois options, **le cerveau reste dans la passerelle** (`AIProvider`), les clés fournisseur et le budget aussi :
le service voix ne fait que du signal et de l'ordonnancement. Gateway-First et Provider Independence sont respectés
(le service ne connaît que des interfaces de la passerelle ; le fournisseur STT est un adaptateur remplaçable).

### 3.4 Transcription : garder ElevenLabs en session longue, Deepgram en alternative mesurée

- **Scribe v2 Realtime** : 0,39 $/h, ≈ 150 ms, `commit_strategy` `manual` (défaut), `vad` ou `turn_prediction`,
  `keepalive_interval_ms` ([API](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime),
  [commit](https://elevenlabs.io/docs/eleven-api/guides/how-to/speech-to-text/realtime/transcripts-and-commit-strategies),
  [prix](https://elevenlabs.io/pricing/api)). Mode retenu : **`manual`**, le *commit* est envoyé par notre détecteur de
  tour (le fournisseur ne décide plus la fin de tour).
- **Simultanéité publiée** (STT temps réel) : Creator 15, Pro 30, Scale/Business 45 sessions
  ([aide ElevenLabs](https://elevenlabs.io/docs/help-center/product/core-capabilities/speech-to-text/how-many-speech-to-text-requests-can-i-make-and-can-i-increase-it) ;
  une version antérieure donne 10/20/30/30). Ces plafonds ne s'expliquent pas par un seul utilisateur : la fermeture
  en 250-300 ms vient plus probablement d'un plan inférieur (Free/Starter, non publié ici), de sessions précédentes
  **non encore fermées** (une par tour, fermées par balayage) ou d'une limite de création de jetons. **À vérifier en
  SF-257-04, avant tout code** : plan réel du compte, code de fermeture WebSocket des 31 replis, sessions ouvertes
  simultanément au moment des échecs. Une session par conversation ramène le besoin à **1** simultanée par
  utilisateur.
- **Durée maximale d'une session : non publiée.** Le service la traite comme finie : reconnexion transparente avec
  rejeu d'un tampon circulaire de 2 s, sans phrase perdue (critère §5).
- **Facturation** à la durée de session : 1 h de conversation ≈ 0,36 € (à 0,92 €/$) — négligeable devant la voix.
- **Alternative mesurée** : **Deepgram Flux** multilingue (français, fin de tour par modèle < 400 ms, endpoint UE GA,
  ≈ 0,0078 $/min (tiers)) — [Flux multilingue](https://deepgram.com/learn/introducing-flux-multilingual) ; nouveau
  sous-traitant ⇒ D-PO-2. AssemblyAI Universal-Streaming (français, 0,15 $/h,
  [prix](https://www.assemblyai.com/pricing.md)) et `gpt-live-transcribe` (`semantic_vad`) restent des adaptateurs
  possibles, non prévus.

### 3.5 Transport

**WebSocket** (443, déjà proxifié par l'ingress nginx du cluster, passe les proxys d'entreprise) par défaut ;
**WebRTC** (Pipecat `SmallWebRTCTransport`, sans SFU) en variante si le prototype montre que l'annulation d'écho du
navigateur ne traite pas la voix de Jarvis jouée hors WebRTC (§4.2, risque R4). Le choix est mesuré, pas présumé.

## 4. Budget de latence et risques

### 4.1 Budget cible — premier son ≤ 2 s en médiane

Temps comptés depuis la **fin réelle de la parole** (mesure F-256 sur l'enregistrement, pas l'horloge de l'écran).
Premier son = premier son **de la réponse** (un clip « Oui ? » ne compte pas).

| Étape | 09/11 | Cible | Comment |
|---|---|---|---|
| Candidat fin de tour | 1,2-2 s | **0,2-0,3 s** | VAD Silero (≈ 200 ms de silence) + smart-turn sur les 8 dernières secondes |
| Texte final | +0,8 s (batch) | **≈ 0** sur le chemin critique | partiels en continu ; *commit* manuel au candidat, final en ≈ 150-300 ms, **pendant** que le cerveau démarre |
| Préparation serveur | +0,3 s | **0** (recouverte) | lancée au candidat, univers en cache (F-252 B) |
| Premier jeton cerveau rapide | +1,9 s | **1,2-1,5 s** (inchangé sur le fond) | modèle inchangé ; préfixe en cache gardé chaud ; mesuré séparément |
| Premier fragment dicible | +0,8 s | **0,2-0,3 s** | fragment sûr (≥ 4 mots jusqu'à une virgule/fin), contrôles F-239 inchangés sur le fragment |
| Voix : premier octet → son | +0,4 s | **0,15-0,25 s** | connexion TTS gardée ouverte, audio poussé dans le WebSocket existant |
| **Total** | **5,5-6 s** | **≈ 1,9-2,4 s** | |

**Lecture honnête** : la cible de 2 s en médiane suppose un premier jeton ≤ 1,3 s et un fragment ≤ 0,3 s. Si le
premier jeton reste à 1,9 s, la médiane plafonne vers 2,5 s : le seul levier restant serait la ligne « compris: »
écrite avant la phrase (consigne ⇒ bancs ×2, hors de ce cadrage). Le prototype mesure chaque ligne.

**Phrases entendues ≥ 95 %** (phrase du PO transcrite en entier et traitée) : obtenue par (1) une session ouverte une
fois, donc plus d'échec à l'ouverture par tour ; (2) un flux continu, donc plus de début coupé ; (3) VAD serveur +
durée minimale de parole contre les bruits ; (4) fin de tour sémantique contre les phrases coupées aux pauses
(corpus des 52 phrases à pause de SF-239-10).

### 4.2 Risques

| # | Risque | Parade |
|---|---|---|
| R1 | **Coût** : la session d'écoute est facturée sur toute la conversation, pas seulement la parole ; la préemption appelle le cerveau pour des tours annulés | écoute ≈ 0,36 €/h ; préemptions annulées comptées (`speculation`), critère « coût ≤ 1,5 × » (§5) ; la préemption ne part qu'au candidat de smart-turn, pas à chaque silence |
| R2 | **Limites ElevenLabs** (simultanéité STT et TTS, durée de session non publiée, contexte TTS multi-flux fermé après 20 s d'inactivité) | vérification du plan en SF-257-04 ; reconnexion + tampon ; contexte TTS rouvert à chaque tour ([multi-context](https://elevenlabs.io/docs/eleven-api/guides/how-to/websockets/multi-context-web-socket)) ; repli automatique sur l'ancienne boucle |
| R3 | **Cluster EKS sans marge mémoire** (incident du 10/10) | 1 réplique, limite 768 Mi ; smart-turn 8 Mo et Silero ≈ 2 Mo sur CPU (pas de GPU, pas le modèle LiveKit de 500 Mo) ; nœud vérifié avant déploiement ; aucun HPA au départ |
| R4 | **Écho** : l'annulation d'écho du navigateur peut ne pas soustraire la voix jouée hors WebRTC (lecteur `MediaSource`) → Jarvis s'interrompt lui-même | même lecture qu'aujourd'hui (le cas est connu et tracé : faux déclenchements F-233) ; mesuré au banc avec haut-parleur ; si > seuil, variante WebRTC (`SmallWebRTCTransport`) |
| R5 | **Téléphone / PWA** : onglet en arrière-plan, veille, changement de réseau coupent le WebSocket | reprise automatique avec identifiant de conversation ; tour en cours conservé côté passerelle ; compagnon Android en SF conditionnelle |
| R6 | **Réseau d'entreprise** (proxy, inspection TLS, UDP fermé) | WebSocket sur 443 via l'ingress existant (comme le runner) ; WebRTC seulement en variante |
| R7 | **Déploiement du service voix** coupe les conversations en cours | déploiements rares, arrêt gracieux (`preStop` : plus de nouvelle session, attente de fin de tour), reprise côté écran ; vérifier les sessions actives avant (règle « déployer tue les tours ») |
| R8 | **Nouvelle pile (Python)** à maintenir | service mince, sans état durable ni secret fournisseur ; CI et image sur le modèle de `diagram-renderer` |

## 5. Transition mesurée

1. **À côté, pas à la place** : drapeau par appareil (`cg.jarvis.loop = legacy | stream`, réglage visible) et
   coupe-circuit serveur `APP_JARVIS_VOICE_STREAM_ENABLED` (défaut `false`). L'ancienne boucle reste le défaut.
2. **Même mesure pour les deux** : `jarvis_turn_timings.loop` (nouvelle colonne) ; mêmes étapes, plus la fin réelle de
   parole mesurée hors ligne par le banc.
3. **Banc à vraie voix F-256** : corpus réel du PO (séances enregistrées avec son accord, F-255) + synthétique (phrases à
   pause, bruits, interruptions, haut-parleur), joué aux deux boucles, deux passages.
4. **Critères de bascule (tous requis, fixés avant la mesure)** :

| Critère | Seuil |
|---|---|
| Premier son, médiane / p90 (banc et usage réel) | **≤ 2,0 s / ≤ 3,0 s** |
| Phrases entendues en entier | **≥ 95 %** (et ≥ ancienne boucle + 10 points) |
| Fins de tour prématurées (corpus à pause) | **≤ 5 %** |
| Interruptions : coupure du son après parole confirmée | ≤ 300 ms p90 ; faux déclenchements ≤ 1 par 10 min de voix de Jarvis |
| Échecs de session STT | **0 repli par tour** ; reconnexion du fournisseur sans phrase perdue |
| Justesse | bancs CONVERSATION / PIÈCES / ORAL inchangés (aucune consigne touchée) ; taux d'adoption de la préemption mesuré, réponse jamais dite sur un texte non confirmé |
| Usage réel | ≥ 3 séances PO sur ≥ 2 jours, ≥ 50 tours, avis du PO |
| Coût voix | ≤ 1,5 × l'ancienne boucle à l'heure de conversation |

5. **Bascule** : `stream` devient le défaut ; l'ancienne boucle reste **disponible 2 semaines** puis son retrait fait
   l'objet d'une décision séparée (aucun abandon avant mesure).

## 6. Découpage (chaque SF ≤ 2 j)

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-257-01 | Mesurer les deux boucles pareil | colonne `loop` (migration), fin réelle de parole lue par le banc F-256, tableau comparatif admin ; protocole et seuils §5 figés | 1 j |
| SF-257-02 | Le service voix existe | `jarvis-voice` (Pipecat), image, k8s (1 réplique, requêtes 256 Mi / limite 768 Mi), santé, CI ; ticket de session émis par la passerelle (`POST /api/jarvis/voice-session`, `user_id`, sujet vérifié) et vérifié par le service ; aucun secret fournisseur | 2 j |
| SF-257-03 | Un flux audio permanent | écran : AudioWorklet PCM 16 kHz, contraintes `LISTEN_AUDIO`, WebSocket unique, reprise après coupure réseau, drapeau `legacy/stream`, lecture de l'audio descendant au `SourceBuffer` existant | 2 j |
| SF-257-04 | Une session de transcription longue | adaptateur STT du service (jeton passerelle, *commit* manuel), reconnexion avec rejeu du tampon circulaire, durée comptée en € côté passerelle, traces `stt_reopen` | 2 j |
| SF-257-05 | Le serveur décide du tour | Silero VAD + smart-turn (CPU), candidat / confirmé / annulé, plafond de repli (phrase en suspens), bruits filtrés, traces sans contenu | 2 j |
| SF-257-06 | Répondre en recouvrement | appel préemptif `/jarvis/converse` au candidat, annulation à la reprise, adoption sur texte final normalisé (règles F-252 D1/D2, `verdict` réutilisé) ; taux d'adoption mesuré | 2 j |
| SF-257-07 | La voix au premier fragment | découpe en fragments sûrs, synthèse en flux via `/jarvis/speech` (alignement F-250 gardé), audio et alignement renvoyés dans le WebSocket | 2 j |
| SF-257-08 | Se laisser couper, côté serveur | VAD pendant la voix, baisse puis coupure, position entendue renvoyée par l'écran, note « coupé après » (F-233 D10), classement acquiescement/arrêt/demande existant | 2 j |
| SF-257-09 | Banc, verdict, bascule | banc F-256 sur les deux boucles ×2, rapport chiffré contre §5, bascule du défaut si tous verts (sinon rapport et arrêt) | 1,5 j |
| SF-257-10 *(conditionnelle)* | Le compagnon Android sur le flux | F-232 envoie son audio au service au lieu de sa propre chaîne ; seulement après SF-257-09 vert | 2 j |

**Total : 16,5 j** (+ 2 j si SF-257-10). Ordre : 01 → 02 → 03/04 → 05 → 06 → 07 → 08 → 09. Prérequis : banc F-256
disponible pour 09 (01 peut démarrer avant).

**Préoccupations transversales** :
- **Auth / Principal** : nouvel endpoint `POST /api/jarvis/voice-session` (authentifié, ticket court) ; le service
  appelle `/jarvis/converse`, `/jarvis/speech`, `/jarvis/stt-session` **au nom de l'utilisateur** par ce ticket, jamais
  par un compte technique global. Endpoints à revérifier : les quatre cités + `/jarvis/converse/verdict`.
- **Contexte tenant** : le ticket porte le `user_id` ; tout ce que le service déclenche reste filtré par la passerelle
  (`findByIdAndUserId` sur le sujet, comme aujourd'hui). Le service ne stocke rien.
- **Plans / limites** : `JarvisVoiceService` (plafond voix), `JarvisSttSessionService` (durée de session comptée),
  `JarvisBudgetService` ; la session longue change l'assiette (durée de conversation, pas de tour).
- **Navigation / routing** : aucune route nouvelle ; `/jarvis`, `/jarvis?source=compagnon` à vérifier sous les deux
  boucles. Ingress : un chemin WebSocket vers `jarvis-voice`.

## 7. Décisions à faire trancher par le PO

| # | Question | Options | Recommandation |
|---|---|---|---|
| **D-PO-1** | Accepter un **service Python** (Pipecat) dans le cluster pour la boucle vocale ? | A Pipecat / B tout Java / C LiveKit | **A** : cadre éprouvé, déploiements backend sans coupure audio, cerveau et clés restent dans la passerelle |
| **D-PO-2** | **Fournisseur d'écoute** de la session longue | ElevenLabs Scribe v2 (actuel) / Deepgram Flux UE (nouveau sous-traitant, fin de tour native) | **ElevenLabs** si la limite de simultanéité du plan est vérifiée et la session longue tient (SF-257-04) ; Deepgram branché en alternative **mesurée** au banc, adopté seulement s'il gagne |
| **D-PO-3** | Révision de **F-233 D-PO-3** : l'audio passe par notre service (et non plus navigateur ↔ fournisseur en direct) | oui / non | **Oui** : condition de la session longue et du tour côté serveur ; rien n'est conservé (même régime que F-145) |

## 8. Hors périmètre

- Changer le cerveau rapide, son modèle ou sa consigne ; un modèle parole-à-parole comme cerveau (F-233 D1 maintenue).
- Retirer l'ancienne boucle (décision séparée après bascule).
- Écoute permanente sans réveil ; iPhone ; voix off F-215.
