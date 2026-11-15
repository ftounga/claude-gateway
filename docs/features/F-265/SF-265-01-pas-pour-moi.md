# Mini-spec — [F-265 / SF-265-01] Pas pour moi : discordance direct/finale et silence sur « je n'ai pas compris »

## Identifiant
`F-265 / SF-265-01`

## Feature parente
`F-265` — Pas pour moi : Jarvis ne répond plus à une autre personne

## Statut
`in-progress`

## Date de création
2026-11-15

## Branche Git
`feat/SF-265-01-pas-pour-moi`

---

## Objectif
Une phrase courte dite par une autre personne de la pièce ne déclenche plus ni tour ni « Pardon, je n'ai pas compris »,
sans jamais perdre une vraie réponse brève du PO.

## Comportement attendu

### Cas nominal
- Ancienne boucle, retranscription finale disponible : direct « Lui-même ? », finale « Maman ? Maman ? » → porte de
  parole `discordant` : aucun tour, aucun « Pardon, Monsieur ? », mesure `rejected_kind=discordant`, trace
  `speech_rejected discordant chars=15 voiced=…`.
- Phrase courte acceptée (ex. « Ah bah, c'est pas dit non », direct vide), après « Voici la galerie des vidéos » (pas
  une question), sans « Jarvis », hors réveil : le cerveau répond « Pardon, Monsieur, je n'ai pas bien saisi… » → rien
  n'est dit ni affiché, l'écran garde la réponse précédente, trace `pardon_hushed`, `falseTriggers` +1.
- Même phrase après une vraie question de Jarvis (« Par quel sujet commençons-nous ? ») → le « je n'ai pas compris »
  est dit comme avant.
- Phrase courte **comprise** → sa réponse est dite comme avant.

### Cas limites (contrainte dure)
| Situation | Comportement |
|---|---|
| Réponse brève (« Oui. », « Non. », « La deuxième », « Stop », « Vas-y ») | jamais `discordant`, jamais tue |
| Direct vide, finale courte | pas de jugement de discordance |
| Phrase > 7 mots, ou direct > 8 mots | pas de discordance ; pas de silence |
| Confirmation en attente / accord oral | la porte est déjà contournée : inchangé |
| Demande corrigée au toucher, interruption (barge-in), réveil « Jarvis… » | jamais tue |
| Phrase qui nomme un sujet ou un client (« Sur data-platform », banc F-256) ou un mot du travail (« Sur le chantier 2 ») | jamais tue |
| Boucle en flux F-257 (une seule transcription) | pas de discordance ; le silence (D2) s'applique |

## Critères d'acceptation
- [ ] CA1 — les deux phrases réelles du 15/11 ne produisent aucune parole de Jarvis (tests composant).
- [ ] CA2 — 28 paires réelles direct/finale du PO (09→15/11) et 236 tours des bancs F-256 : 0 discordance.
- [ ] CA3 — « Oui », « Non », « La deuxième », « Stop » et variantes transcrites différemment : toujours envoyés au cerveau, et leur réponse dite.
- [ ] CA4 — la passerelle accepte et garde `rejected_kind=discordant` (liste fermée), toujours sans texte.
- [ ] CA5 — banc à vraie voix (2 passages) : entendu/compris non dégradés (mots courts surtout) ; voix tierce : moins de réponses et de « pardon » qu'`origin/main`.

## Périmètre — hors scope
- Juger sur le niveau du micro (crête) : à mesurer d'abord.
- Consigne du cerveau rapide : inchangée (aucun banc V8).
- Le « Pardon, Monsieur ? » de l'écran pour les autres rejets (F-251) : inchangé.
- L'historique `atelier_messages` garde l'échange tu (la passerelle l'écrit avant que l'écran ne se taise).

## Technique
- Endpoints : aucun nouveau. Tables : `jarvis_turn_timings.rejected_kind` (valeur `discordant`, varchar(16) existant). Migration : non.
- Frontend : `jarvis-addressed.ts` (neuf, pur), `jarvis-speech-gate.ts` (`liveText`, `discordant`), `jarvis.component.ts` (`closeListening`, `acceptHeard`, `rejectSpeech`, `converseOnce`), `jarvis.models.ts`.
- Backend : `JarvisLatencyService.REJECTIONS`.
- Banc : scénario `voix-tierce` (voix de synthèse tierce atténuée + 2 vraies captures du 15/11), compte des réponses aux voix tierces et des « pardon ».

### Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non. Plans/limites : non. Navigation : non.

## Plan de test
### Unitaires
- `jarvis-addressed.spec.ts` : paires réelles du PO, cas du 15/11, réponses brèves, « je n'ai pas compris » réels vs questions de précision, `awaitsAnswer`, `mayHushPardon`.
- `jarvis.component.spec.ts` : discordance → ni tour ni pardon, mesure ; 8 couples brefs/réels → la parole part ; silence après « Voici » ; pardon dit après une question ; phrase comprise et mot bref dits ; correction au toucher jamais tue.
### Intégration
- `JarvisLatencyApiIntegrationTest.rejectedSpeechIsTracedWithoutText` : `discordant` gardé.
### Isolation
- Sans objet (aucune donnée nouvelle ; la mesure reste filtrée `user_id` comme avant).
### Banc
- F-256, 2 passages + scénario `voix-tierce`, comparé à `sf257-12-voix-1/-2` et à `origin/main` sur `voix-tierce`.

## Dépendances
- F-239, F-251, F-256, F-257 — done. Aucune question ouverte impactée.
