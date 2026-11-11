# Mini-spec — F-258 / SF-258-01 Blocs réguliers, erreurs nommées, une réouverture

## Identifiant
`F-258 / SF-258-01`

## Feature parente
`F-258` — Jarvis n'est plus raccroché (cadrage `CADRAGE-F-258-jarvis-n-est-plus-raccroche.md`)

## Statut
`in-review` · **Estimation :** 1,5 j · **Branche :** `feat/SF-258-01-ecoute-blocs-reguliers` · **Dépend de :** F-256 (banc)

## Objectif
Que le fournisseur de transcription ne ferme plus la session d'écoute de Jarvis pour envoi trop fréquent, et qu'une
coupure passagère ne coûte plus la phrase.

## Comportement attendu

### Cas nominal
1. **Regroupement** : le son du micro (blocs de 128 échantillons de l'AudioWorklet, ou 4 096 du ScriptProcessor) est
   accumulé au débit du micro et part **par blocs de 100 ms** (`CHUNK_MS`), rééchantillonnés d'un seul tenant
   (1 600 échantillons à 16 kHz, sans perte). Le commit forcé par l'écran pousse d'abord le reste du bloc en cours.
2. **Lissage** : tous les messages vers le fournisseur passent par une file envoyée dans l'ordre, **au plus un message
   toutes les 25 ms** (`SEND_GAP_MS`) ; un message arrivé quand rien n'attend part tout de suite (le flux vivant, un
   bloc toutes les 100 ms, n'est jamais retardé). La pré-écoute de 1,5 s (15 blocs) part donc en ≈ 375 ms au lieu
   d'un coup ; une rafale du navigateur occupé est étalée de même. Si la minuterie d'envoi part en retard (fil
   principal occupé, vue 3D), l'envoi rattrape **au plus 4 messages d'un coup** (`SEND_CATCH_UP`) : la file ne prend
   jamais de retard sur le temps réel et ne repart jamais en rafale.
3. **Erreurs nommées** : les 14 types d'erreur documentés (`queue_overflow`, `rate_limited`, `resource_exhausted`,
   `commit_throttled`, `auth_error`, `quota_exceeded`, …) sont reconnus ; le journal d'écoute reçoit
   `stream_error type=…` au message, `stream_close type=… code=… reason=…` à la fermeture, `stream_cut …` au repli.
   Jamais le texte libre de l'erreur.
4. **Une réouverture** : sur coupure **passagère** après acceptation (`queue_overflow`, `rate_limited`,
   `resource_exhausted`, `commit_throttled`, `session_time_limit_exceeded`, `error`, `transcriber_error`,
   `insufficient_audio_activity`, ou fermeture serveur sans message), l'écoute demande **un** ticket neuf, clôt
   l'ancienne session (durée comptée), ouvre la nouvelle et y renvoie (lissé) le son **depuis le dernier segment
   validé** — la phrase en cours n'est pas tronquée. `stream_reopen ok ms=… resend_ms=…` au journal.
5. **À l'ouverture** : une session fermée par le fournisseur avant `session_started` (`ouverture_fermee`) est retentée
   **une fois** avec un ticket neuf (le son attend dans le tampon du micro gardé, qui n'est plus vidé par l'échec).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Seconde coupure après la réouverture | repli batch `coupe` comme avant (aucune boucle de réouvertures) |
| Erreur durable (`auth_error`, `quota_exceeded`, `unaccepted_terms`, `invalid_request`, `input_error`, `chunk_size_exceeded`) | repli direct, type nommé au journal |
| Ticket neuf refusé ou en échec | `stream_reopen fail …`, repli `coupe` |
| L'écran clôt l'écoute pendant la réouverture | rien ne se rouvre ; le ticket neuf éventuel est clos à 0 s |
| Seconde fermeture à l'ouverture | repli sur la dictée, raison `ouverture_fermee` (inchangée) |

## Contraintes de validation
| Élément | Règle |
|---|---|
| Taille de bloc | 100 ms (recommandation ElevenLabs ≈ 100 ms, plage 20-250 ms) |
| Cadence max | 1 message / 25 ms (≤ 4× le temps réel ; exemples officiels ≈ 5×) |
| Réouvertures | au plus 1 par écoute (et 1 nouvelle tentative à l'ouverture) |
| Détail de journal | `[A-Za-z0-9 _.:/>=+,-]`, ≤ 120 caractères, raison ≤ 40 caractères |

## Critères d'acceptation
- [ ] 1 s de micro à 48 kHz en blocs de 128 part en **10** messages de 3 200 octets (au lieu de 375).
- [ ] Une rafale de 1,5 s part à ≤ 1 message / 25 ms, dans l'ordre.
- [ ] Les 14 types d'erreur documentés sont reconnus par leur nom ; un type inconnu porteur d'`error` reste une erreur.
- [ ] `queue_overflow` en cours d'écoute : un ticket neuf, ancienne session close avec sa durée, son non validé
      renvoyé, aucun repli ; une seconde coupure → repli `coupe`.
- [ ] Fermeture avant acceptation : une seconde tentative avec un ticket neuf, sans repli si elle réussit.
- [ ] Banc à vraie voix (F-256) en 2D **et** en 3D : replis et `queue_overflow` nettement en baisse par rapport à la
      référence `8ce0649d`, entendu/compris non dégradés.

## Plan de test
- **Unitaires** (`jarvis-stream-listener.spec.ts`) : reconnaissance des erreurs, classement passager/durable, détail
  de journal ; regroupement (`AudioChunker`) ; lissage (`PacedOutbox`) ; 1 s → 10 messages ; rafale lissée ; commit
  avec reste ; réouverture unique avec renvoi ; commit pendant la réouverture ; erreur durable / sans ticket ;
  fermeture par l'écran pendant la réouverture ; tampon du micro gardé après un refus d'ouverture.
- **Composant** (`jarvis.component.spec.ts`) : ticket neuf et sessions closes (ancienne avec sa durée, neuve à la fin) ;
  écoute terminée pendant la demande de ticket ; seconde tentative à l'ouverture ; double fermeture → dictée.
- **Intégration** : banc à vraie voix 2D + 3D (porte V9).
- **Isolation utilisateur** : sans objet (aucun accès aux données ; tickets émis par la passerelle par utilisateur,
  inchangée).

## Impact
- Frontend : `frontend/src/app/jarvis/jarvis-stream-listener.ts` (+ spec), `frontend/src/app/jarvis/jarvis.component.ts`
  (+ spec).
- Backend : `JarvisReplayAssembler` — l'événement `stream_cut` compte comme une erreur de flux dans le rejeu (F-255),
  comme `stream_error`.
- Tables : aucune. Endpoints : aucun nouveau (`POST /api/jarvis/stt-session` et la clôture déjà existants).
- Préoccupations transversales : aucune (ni auth, ni tenant, ni plans/limites, ni routage).

## Hors périmètre
Le service `jarvis-voice` (F-257) ; la latence de bout en bout ; consigne, outils et modèle du cerveau rapide ; la liste
des replis côté passerelle.
