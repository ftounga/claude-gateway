# Mini-spec — F-233 / SF-233-08 — Micro ouvert pendant qu'il parle (D8, D10 position de lecture)

## Identifiant
`F-233 / SF-233-08` · Feature parente `F-233` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-233-08-micro-ouvert`

## Objectif
> Pendant que Jarvis parle, le micro reste ouvert (annulation d'écho) et une détection de voix **sur l'appareil**
> (Silero VAD) le fait baisser le volume dès qu'un humain parle, puis **se taire** quand la parole est confirmée ;
> ce qui est dit est capté en entier et traité.

## Comportement attendu
- **Full duplex** : quand l'orbe passe en « parole », `JarvisVadService` démarre Silero VAD v5 (ONNX en WASM,
  `@ricky0123/vad-web` 0.0.31 épinglé, chargé à la demande) sur un micro `echoCancellation / noiseSuppression /
  autoGainControl`. Rien ne sort de l'appareil. Arrêté quand Jarvis ne parle plus.
- **Deux temps** : voix détectée → **baisse de volume** (gain 0,25 sur la sortie) ; parole confirmée (≥ 250 ms) →
  **coupure** : la phrase s'arrête et **la file est vidée** ; non confirmée en 1 s, ou fausse alerte → volume
  rétabli, Jarvis continue.
- **Aucun mot perdu** : la parole qui coupe est captée par la détection elle-même (avec 300 ms d'avant), encodée en
  WAV 16 kHz et transcrite par la route de dictée existante ; puis traitée comme une demande (SF-233-09 ajoutera
  acquiescement / arrêt).
- **Position de lecture** : la voix garde les phrases entendues en entier et estime la part entendue de la phrase
  coupée par l'horloge audio (`interrupt()` → `{ heard, resume }`) ; remise à zéro à chaque nouvelle réponse.
- **Mesure** (SF-233-01) : interruptions détectées / rétablies / confirmées, faux déclenchements (coupure sans mots).
- Réglage par appareil : `localStorage cg.jarvis.bargeIn = '0'` éteint la coupure par la voix.
- Ressources servies sous `/vad/` (modèle, worklet, WASM onnxruntime), types MIME forcés et cache d'un jour (nginx).

## Cas d'erreur
| Situation | Comportement |
|---|---|
| Modèle / WASM / micro indisponible | pas de coupure par la voix pour l'ouverture ; appui et mot d'éveil inchangés |
| Toux, bruit bref | fausse alerte : volume rétabli, rien coupé |
| Coupure mais transcription vide | faux déclenchement compté, Jarvis revient au repos (ou à la réflexion si un tour tourne) |
| Appui pendant la capture | ignoré (la capture finit d'elle-même) |

## Critères d'acceptation
1. Voix détectée pendant la parole ⇒ volume baissé ; confirmée ⇒ voix coupée et file vidée.
2. Fausse alerte ou délai ⇒ volume rétabli sans coupure.
3. La parole qui coupe part dans le fil (précision si un tour tourne — mécanisme existant).
4. La part entendue est connue au moment de la coupure.
5. Les compteurs d'interruption remontent dans la mesure.

## Plan de test
- Unitaires : `BargeInController` (baisse, coupure unique, délai, fausse alerte, silence), `heardPart`, `encodeWav`.
- Écran (VAD simulée) : coupure et envoi ; fausse alerte et faux déclenchement comptés.
- Le jeu réel (écho du haut-parleur, latence de coupure ≤ 300 ms) se valide à l'oreille sur l'appareil du PO.

## Impacts
`jarvis-barge-in.ts` (nouveau), `JarvisComponent`, `JarvisVoiceService` (gain, `duck`, `interrupt`, `heardPart`,
`resetHeard`), `DictationService.transcribe(blob)`, `angular.json` (ressources `/vad/`), `nginx.conf`, dépendance
`@ricky0123/vad-web@0.0.31` (+ `onnxruntime-web`). Aucun endpoint, aucune table.

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de limites, de route). Confidentialité : la détection tourne sur l'appareil.

## Arbitrages (réversibles)
- Capture de la parole qui coupe **par la VAD** (et transcription batch) plutôt que de rouvrir l'écoute en flux :
  sinon les 250 premières millisecondes — souvent le mot entier (« stop », « ok ») — seraient perdues.
- Code de la VAD en chunk paresseux (≈ 0,5 Mo brut, préchargé par le service worker comme tout `/*.js`) ; le WASM
  (≈ 15 Mo) et le modèle (≈ 2,3 Mo) ne sont chargés qu'à la première parole de Jarvis sur `/jarvis`.

## Hors périmètre
Classement acquiescement / arrêt / demande et note « coupé après » (SF-233-09) ; Android (SF-233-10).
