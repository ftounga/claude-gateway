# Cadrage F-258 — Jarvis n'est plus raccroché

**Statut :** cadrage du 2026-11-11 · famille « Jarvis » (F-233, F-239, F-251, F-256 banc à vraie voix ; F-257 converse en
flux, en cours à côté) · **correctif de la boucle d'écoute ACTUELLE** (celle qui reste par défaut pendant des semaines,
le temps que F-257 fasse ses preuves). **Aucune consigne, aucun outil, aucun modèle du cerveau rapide ne change.**

## 1. Constat prouvé (banc à vraie voix F-256, référence `8ce0649d`)

- ElevenLabs (Scribe v2 Realtime) **ferme la session de transcription** avec `queue_overflow` — « *Session terminated:
  audio data is being sent too frequently. Please reduce the rate at which you send audio chunks.* » : **45/45**
  sessions en vue 3D (navigateur chargé), 1/25 en 2D. En prod, le 09/11 : 66 % de replis.
- Le client **ne reconnaît pas** ce message (`parseSttMessage` ignore `queue_overflow`, `rate_limited`,
  `resource_exhausted`, `commit_throttled`) : le journal dit « fermé » sans raison.
- Une session coupée en pleine phrase **tronque la phrase** (« Est-ce que tu vois des sujets » pour « … en cours côté
  WAF sur EDENRED ? ») et Jarvis répond « Pardon, Monsieur, je n'ai pas saisi ».
- Mécanisme lu dans le code : le module de capture (AudioWorklet) poste **un bloc de 128 échantillons (≈ 2,7 ms à
  48 kHz) par message**, que l'écran envoie **aussitôt** au fournisseur — ≈ 375 messages/s, **en rafale** quand le fil
  principal est occupé (3D) ; et depuis SF-251-02 la pré-écoute de 1,5 s part **d'un coup** (15 envois) à l'ouverture.
  Au passage, rééchantillonner chaque bloc de 128 seul perd 2 échantillons sur 128 (128 / 3 = 42,67 → 42).

## 2. Ce que dit le fournisseur

- Référence des événements Scribe v2 Realtime (14 types d'erreur ; le serveur envoie le message **puis ferme**) :
  https://elevenlabs.io/docs/eleven-api/guides/how-to/speech-to-text/realtime/event-reference — `queue_overflow` :
  « *The processing queue is full. Please send fewer requests made in a short period of time* ».
- Taille de bloc : « *Small PCM chunks of around 100ms minimize latency to the first partial* », plage pratique
  ≈ 20-250 ms (https://elevenlabs.io/blog/real-time-speech-to-text-under-200ms) ; les exemples officiels de diffusion
  côté client envoient des blocs de 4 096 échantillons à 16 kHz (256 ms) **toutes les 50 ms**, soit ≈ 5× le temps réel
  (https://elevenlabs.io/docs/eleven-api/guides/how-to/speech-to-text/realtime/client-side-streaming). Aucune cadence
  maximale chiffrée n'est documentée.

## 3. Le correctif (une subfeature)

| SF | Quoi | Estimation |
|---|---|---|
| SF-258-01 | Blocs de 100 ms, envoi lissé (≤ 1 message / 25 ms, soit ≤ 4× le temps réel pour un retard), tous les types d'erreur reconnus et tracés (type, code, raison), **une** réouverture avec un ticket neuf sur coupure passagère (en cours d'écoute : le son non validé est renvoyé ; à l'ouverture : nouvelle tentative) avant le repli batch | 1,5 j |

**Porte** (règle V9, F-256) : banc à vraie voix en 2D **et** en 3D sur la branche, comparé à la référence ; fusion
seulement si replis et `queue_overflow` baissent nettement sans dégrader le reste.

## 4. Résultat au banc à vraie voix (2026-11-11, code `88809cc7`)

Mêmes 8 scénarios et 33 enregistrements que la référence. **Voix ElevenLabs comme en prod** (`eleven_multilingual_v2`,
voix « Daniel » de la liste d'audition) alors que la référence a tourné avec la voix OpenAI `onyx` : le **premier son**
n'est donc pas comparable à réglage identique (la voix seule pèse ≈ 1,7 s d'écart d'après la prod). Référence relue avec
la mesure actuelle (`relire.mjs`). Rapports : `~/.local/share/claude-gateway/banc-jarvis-voix/f258-88809cc7-2d/` et
`…-3d/` (passages intermédiaires `f258-9d7f78e6-*` sur le premier commit, mêmes conclusions).

| Mesure | 2D réf. → F-258 | 3D réf. → F-258 |
|---|---|---|
| Fermetures `queue_overflow` | 1 → **0** | **45 → 0** |
| Replis de transcription | 1 → **0** | **26 → 0** |
| Phrases entendues | 24/25 → **25/25** | 23/25 → **24/25** |
| Phrases comprises (WER ≤ 0,25) | 24/25 → 23/25 | **15/25 → 23/25** |
| Premier son, médiane / p90 (voix différente) | 6,1 / 8,2 s → 4,2 / 6,3 s | 5,7 / 14,8 s → 6,5 / 9,1 s |
| Fin de la phrase → commit du fournisseur (médiane) | 0,63 s → 0,59 s | — → 0,60 s |
| Réponses répétées | 0 → 1 | 0 → 0 |
| « Voici » sans image | 1 → 1 | 0 → 1 |

Lecture : la cause est **confirmée** — sans rafale de petits messages, plus aucune session n'est tuée, même navigateur
chargé, et les phrases ne sont plus tronquées (3D : 23/25 comprises contre 15/25). La réouverture n'a pas eu à servir
(aucune coupure). Les écarts restants ne relèvent pas de l'écoute : la réponse répétée (cerveau, un enregistrement
différent à chaque passage), « Voici » sans image (galerie refusée, connu depuis la référence), une phrase tronquée par
la fin de tour de l'écran sur une pause (« Attends. En fait, parle-moi de… », 2D) et la seconde question d'un
enchaînement rapide (connu, F-257). Le premier son reste loin de l'objectif de 2 s (fin de tour, voix : F-257). La
porte F-256 n'est donc pas franchie ; ce correctif montre l'écart avant / après comme le prévoit la release-checklist.

Une première version (`9d7f78e6`) envoyait au rythme d'une minuterie : en 3D, le fournisseur validait la phrase en
0,97 s (médiane) contre 0,57 s en 2D, une minuterie retardée par le fil principal limitant le débit. Le rattrapage borné
(4 messages au plus) ramène ce délai à 0,60 s en 3D.

## 5. Hors périmètre

Le nouveau service `jarvis-voice` (F-257) ; la latence de bout en bout (fin de tour, voix) ; la consigne, les outils et
le modèle du cerveau rapide ; le serveur (aucun changement d'API, la liste des replis reste la même).
