# Mini-spec — F-250 / SF-250-01 Relayer l'alignement

## Identifiant
`F-250 / SF-250-01`

## Feature parente
`F-250` — Le visage de Jarvis (cadrage §2.1, §3 étape 1, portes P0, P1, P5)

## Statut
`done` (2026-11-07) · **Estimation :** 1,5 j · **Branche :** `feat/SF-250-01-relayer-l-alignement` · **Dépend de :** —

## Objectif
La passerelle relaie, **dans la même requête de voix**, l'alignement par caractère qu'ElevenLabs fournit
(`/stream/with-timestamps`), en NDJSON, quand l'écran le demande et que la voix sait aligner ; sinon le mp3 de toujours.

## Vérifications bloquantes faites d'abord (banc du 2026-11-07, `docs/features/F-250/bancs/`)
Clé lue à la volée dans `backend-secrets` (jamais écrite, jamais affichée) ; voix Daniel, modèle de prod
`eleven_multilingual_v2`, 50 phrases françaises (`phrases.txt`).

| Porte | Mesure | Résultat |
|---|---|---|
| **P0 coût** | consommation du compte (`/v1/usage/character-stats`, avant/après, 60 s de latence) **et** en-tête `character-cost` de chaque réponse | `/stream` : **924** caractères ; `/stream/with-timestamps` : **924** ; en-têtes identiques phrase par phrase (50/50). **Aucun coût ajouté — PASS.** |
| **Format** | réponse réelle enregistrée (`raw`) | objets JSON séparés par **une ligne vide** (`}\n\n{`) ; clés `audio_base64`, `alignment`, `normalized_alignment`, `quality_check` ; **temps absolus** depuis le début de la phrase ; multilingual_v2 : tout l'alignement dans le 1er objet ; flash_v2_5 : alignement réparti sur plusieurs objets ; `normalized_alignment` **n'étend pas** les nombres (« 12 » reste « 12 ») et ajoute une espace de tête/fin. |
| **P1 côté fournisseur** | premier son : 1er octet mp3 vs 1er objet JSON complet, 50 phrases entrelacées | mp3 p50 322 / p95 507 ms ; aligné p50 320 / p95 503 ms ⇒ **écart p95 −4 ms** (≤ +20 ms). La mesure côté écran est faite en SF-250-02. |

## Comportement attendu

### Cas nominal
1. `AlignedSpeech` (nouvelle capacité optionnelle, `speech/`) : `synthesizeAligned(text, voice, instructions, sink)` ;
   implémentée par `ElevenLabsSpeechProvider` (même corps `{text, model_id, language_code}`, `…/stream/with-timestamps?output_format=mp3_44100_128`).
   Chaque objet est lu au fil de l'eau (`MappingIterator`) et remis au `sink` ; l'alignement relayé est
   `normalized_alignment`, sinon `alignment`.
2. `POST /api/jarvis/speech` : si `Accept` contient `application/x-ndjson` **et** `JarvisVoiceService.aligns(userId)`
   (visage + alignement allumés, voix appelable, fournisseur `AlignedSpeech`) ⇒ `200 application/x-ndjson`, une ligne
   par morceau `{"a": base64, "c": [...], "s": [...], "e": [...]}` (`c/s/e` absents si le morceau n'a pas d'alignement),
   poussée dès son arrivée. Sinon : `audio/mpeg` inchangé.
3. Comptage identique au mp3 (`account`, nombre de caractères du texte lu) ; plafond vérifié avant tout appel (inchangé).
4. `GET /api/jarvis/face` ⇒ `{enabled, aligned}` (pour l'écran, SF-250-02/06).
5. Coupe-circuits `APP_JARVIS_FACE_ENABLED` / `APP_JARVIS_FACE_ALIGN_ENABLED` (`app.jarvis.face.*`, défaut `true`).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Fournisseur en erreur avant le 1er morceau | JSON `speech_failed` (le message du fournisseur n'est pas relayé) | 502 |
| Plafond du mois atteint | `jarvis_voice_budget`, aucun appel | 429 |
| Voix OpenAI / voix non alignable / coupe-circuit éteint | mp3 de toujours (repli amplitude côté écran) | 200 |
| Alignement incohérent (longueurs différentes) | le son passe seul (`a` sans `c/s/e`) | 200 |
| Objet sans son ni alignement | ignoré | — |
| Réponse vide du fournisseur | `speech_failed` | 502 |
| Sans jeton | refus | 401 |

## Contraintes de validation
Inchangées (`maxRequestChars` 4 000, coupe à `maxChars` 600) ; identifiant de voix ElevenLabs `^[A-Za-z0-9]{8,64}$`.

## Critères d'acceptation
- [x] P0 mesurée : coût identique (924 = 924, en-têtes identiques).
- [x] Format réel enregistré (fixtures de test tirées des réponses réelles, audio tronqué).
- [x] NDJSON relayé morceau par morceau (le 1er morceau parvient avant la fin du flux fournisseur).
- [x] Sans `Accept` NDJSON, ou voix non alignable, ou coupe-circuit éteint : mp3.
- [x] Même comptage de caractères ; plafond et isolation inchangés.
- [x] Clé jamais journalisée ni renvoyée.

## Plan de test
- Unitaires : `ElevenLabsAlignedSpeechTest` (fixture réelle, temps absolus multi-morceaux, relais au fil de l'eau,
  alignement incohérent, erreurs sans la clé), `JarvisFaceAlignTest` (coupe-circuits, voix capable, refus).
- Intégration : `JarvisFaceApiIntegrationTest` (NDJSON, mp3 sans Accept, 502, 429, `GET /face`, 401) ;
  non-régression `JarvisVoiceApiIntegrationTest`, `ElevenLabsSpeechProviderTest`, `JarvisVoiceServiceTest`.
- Isolation : le chemin reste sous `atelierAccess.requireAccess()` + `currentUser.requireId()` ; le plafond d'Alice
  n'atteint pas Bob (test 429 + `GET /face` de Bob).

## Impact
- Backend : `speech/AlignedSpeech.java` (nouveau), `speech/elevenlabs/ElevenLabsSpeechProvider.java`,
  `jarvis/JarvisVoiceService.java`, `jarvis/JarvisVoiceController.java`, `jarvis/JarvisFaceProperties.java` +
  `JarvisFaceConfig.java` (nouveaux), `application.yml` (`app.jarvis.face.*`).
- Endpoints : `POST /api/jarvis/speech` (négociation NDJSON), `GET /api/jarvis/face` (nouveau).
- Tables : aucune. Migration : aucune.
- Préoccupations transversales : aucune (auth, tenant, plans, routage inchangés).

## Hors périmètre
Lecture NDJSON par l'écran (SF-250-02), visèmes (SF-250-03), clips alignés (SF-250-06), WebSocket `stream-input`.
