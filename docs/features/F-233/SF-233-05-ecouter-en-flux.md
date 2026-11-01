# Mini-spec — F-233 / SF-233-05 — Écouter en flux, à l'écran (D5, D6, D8 écho)

## Identifiant
`F-233 / SF-233-05` · Feature parente `F-233` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-233-05-ecouter-en-flux`
Contrat importé de `SF-233-04` (`POST /api/jarvis/stt-session`, `POST /api/jarvis/stt-session/{id}/close`).

## Objectif
> Quand l'écoute en flux est disponible, l'écran Jarvis transcrit **pendant** la parole (texte provisoire sous
> l'orbe), clôt la phrase sur la fin de tour du fournisseur, et retombe automatiquement sur la dictée batch sinon.

## Comportement attendu
- À chaque écoute : `POST /api/jarvis/stt-session` ; si 503, le flux est marqué indisponible **pour cette
  ouverture** (plus d'essai) ; toute autre erreur ⇒ dictée batch pour cette phrase.
- Micro ouvert avec `echoCancellation`, `noiseSuppression`, `autoGainControl` (flux **et** dictée batch de Jarvis).
- WebSocket direct vers le fournisseur (`url` + `&token=`), capture PCM 16 bits mono 16 kHz (AudioWorklet, repli
  ScriptProcessor), morceaux `input_audio_chunk` en base64 ; démarrage refusé au-delà de 3 s ⇒ repli batch.
- `partial_transcript` ⇒ texte provisoire affiché sous l'orbe ; `committed_transcript` ⇒ **fin de tour du
  fournisseur** (D6), la phrase part ; silence local (0,7 s, SF-233-02) ⇒ `commit` forcé puis attente de la
  validation au plus 1,5 s, sinon le dernier provisoire.
- À la fin : socket et micro fermés, `close` déclare la durée (comptée par la passerelle) ; chronomètre :
  `listenMode = STREAM`, `sttProvider` du ticket.
- Quitter l'écran ferme la session.

## Cas d'erreur
| Situation | Comportement |
|---|---|
| 503 (flux non configuré) | dictée batch, plus d'essai pendant l'ouverture |
| 429 / 502 / réseau | dictée batch pour cette phrase |
| Micro refusé | message habituel (« Le micro est refusé… ») via la dictée |
| Fournisseur coupe la session en cours | la phrase se clôt avec ce qui a été entendu |
| Aucune validation après `commit` | dernier texte provisoire |

## Critères d'acceptation
1. Flux disponible : la phrase validée par le fournisseur part dans le fil sans dictée batch.
2. Le texte provisoire est visible pendant l'écoute.
3. La session est close et sa durée déclarée.
4. 503 ⇒ dictée batch avec contraintes d'écho, un seul essai par ouverture.
5. Le chronomètre déclare `STREAM` et le fournisseur.

## Plan de test
- Unitaires (pur) : `parseSttMessage`, `downsample`, `toPcm16`, `toBase64`, `sessionUrl`.
- Écran (mocks du service et de `JarvisStreamOpener`) : flux nominal (partiel, validé, envoi, clôture, mesure) ;
  repli 503 (contraintes d'écho, un seul essai).
- Le jeu de bout en bout avec le fournisseur réel se fait à l'activation de la clé (exploitation).

## Impacts
`jarvis-stream-listener.ts` (nouveau : `StreamingListener`, `JarvisStreamOpener`), `JarvisComponent` (+ gabarit,
style `jv__partial` en jetons existants), `JarvisService` (`sttSession`, `closeSttSession`), modèle
`JarvisSttTicket`, `DictationService.start(audio)` (paramètre optionnel, défaut inchangé pour F-145).

## Préoccupations transversales
Aucune (pas de route, d'auth, de tenant ; le plafond est vérifié par la passerelle à l'émission).

## Hors périmètre
Coupure de Jarvis par la voix pendant qu'il parle (SF-233-08) ; autres protocoles de fournisseur.
