# Mini-spec — F-233 / SF-233-01 — Chronométrer le tour vocal (D2)

## Identifiant
`F-233 / SF-233-01`

## Feature parente
`F-233` — Jarvis temps réel (cadrage `CADRAGE-F-233-jarvis-temps-reel.md`, D2, D13)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-233-01-chronometre-tour-vocal`

---

## Objectif

> Chaque tour vocal laisse **une ligne de mesure sans contenu** (horodatages relatifs, fournisseurs, compteurs
> d'interruption) et l'administrateur lit p50/p90 par étape, pour décider des leviers suivants sur des chiffres.

## Comportement attendu

### Origine des temps
`t0` = **fin de parole détectée** par l'écran. Chaque marque est un entier de millisecondes **depuis t0**
(`null` si l'étape n'a pas eu lieu). La durée de la parole (`speechMs`) est mesurée avant t0.

| Marque | Posée quand |
|---|---|
| `transcriptMs` | la transcription est disponible |
| `reactionMs` | la réaction audible (clip local, SF-233-02) commence |
| `sentMs` | la demande part dans le fil (`streamChat`) |
| `firstTokenMs` | le premier delta de texte du cerveau arrive à l'écran |
| `firstOralSentenceMs` | la première phrase orale complète est connue |
| `firstAudioByteMs` | la réponse audio de la première phrase commence à arriver |
| `firstSoundMs` | le premier son de la réponse est joué |
| `doneMs` | le tour (réponse dite) est fini |

Compteurs (entiers ≥ 0, bornés à 1 000) : `interruptionsDetected`, `interruptionsRestored` (voix détectée non
confirmée, volume rétabli), `interruptionsConfirmed`, `acknowledgements`, `falseTriggers`. Fournisseurs (liste
fermée, `[a-z0-9-]{1,32}`) : `sttProvider`, `ttsProvider` ; `listenMode` ∈ `BATCH|STREAM` ; `toolTurn` booléen.

### Routes
| Route | Droit | Rend |
|---|---|---|
| `POST /api/jarvis/timings` | accès Forge (comme la voix) | 204 ; une ligne rattachée au `user_id` connecté (et au sujet s'il lui appartient, sinon `null`) |
| `GET /api/admin/jarvis/latency?days=7` | ADMIN (`assertAdmin`), ses **propres** tours | `{ days, turns, steps:[{step,count,p50,p90}], interruptions:{detected,restored,confirmed,acknowledgements,falseTriggers}, byListenMode:{BATCH,STREAM} }` |

- Purge quotidienne des lignes de plus de **90 jours** (`app.jarvis.timings.purge-cron`).
- Best-effort côté écran : un envoi raté ne gêne jamais Jarvis.

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Marque négative ou > 600 000 ms | `validation_error` | 400 |
| Fournisseur hors motif, `listenMode` inconnu | `validation_error` | 400 |
| Non-ADMIN sur la mesure | refus | 403 |
| Sans jeton | refus | 401 |
| Sujet d'autrui | ignoré (ligne sans sujet), aucune fuite | 204 |

## Critères d'acceptation
1. Un tour vocal à l'écran envoie une ligne avec `transcriptMs`, `sentMs`, `firstTokenMs`, `firstSoundMs`, `doneMs`.
2. La mesure rend p50/p90 par étape (rang le plus proche) sur les tours du compte uniquement.
3. Un autre administrateur ne voit pas les tours du premier.
4. Aucune colonne ne porte de texte dit ou lu.
5. Les lignes > 90 j sont purgées.

## Contraintes de validation
Marques `0..600000` ; compteurs `0..1000` ; `days` `1..90` (défaut 7) ; fournisseurs `^[a-z0-9-]{1,32}$`.

## Plan de test
- Unitaires : percentiles (rang le plus proche, vide, une valeur) ; validation des bornes ; `TurnClock` (écran).
- Intégration : POST puis GET admin (valeurs), 400, 403, 401, isolation entre deux ADMIN, sujet d'autrui ignoré.
- Écran : le tour parlé poste une mesure avec les marques attendues.

## Impacts
- **Table** : `jarvis_turn_timings` (migration `196-jarvis-turn-timings.xml`).
- **Endpoints** : `POST /api/jarvis/timings`, `GET /api/admin/jarvis/latency`.
- **Composants** : `JarvisComponent`, `JarvisVoiceService` (crochets premier octet / premier son), `JarvisService`,
  nouveau `jarvis-turn-clock.ts`.

## Préoccupations transversales
- Auth / Principal : non (nouvel endpoint sous la chaîne JWT existante, garde `AtelierAccessService`).
- Contexte tenant : `user_id` du `CurrentUser` ; sujet vérifié par `findByIdAndUserId`.
- Plans / limites : non. Navigation : non.

## Arbitrage (réversible)
Le « premier jeton » est mesuré **à l'écran** (arrivée du premier delta) et non côté serveur : c'est ce que
l'utilisateur perçoit, et cela évite de toucher la boucle d'agent (risque de régression sur le cerveau). La durée
serveur du tour reste dans `atelier_turn_measures` (F-188).

## Hors périmètre
Tableau de bord graphique (la mesure est un JSON admin, comme `/admin/jarvis/measure`) ; mesure côté Android.
