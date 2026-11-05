# Mini-spec — F-247 / SF-247-09 Le relevé de départ

## Identifiant
`F-247 / SF-247-09`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.3 « Quand le relevé passe », D6, porte Z5)

## Statut
`done` (2026-11-05) · **Estimation :** 1 j · **Branche :** `feat/SF-247-09-releve-de-depart` · **Dépend de :** SF-247-07

## Objectif
Avant que le poste s'éteigne ou se mette en veille, un dernier relevé passe — à l'inactivité, et à l'arrêt propre du
runner dans un plafond de 8 s — pour que l'information dite poste éteint soit la plus fraîche possible.

## Comportement attendu

### Cas nominal
1. **Inactivité** (`JarvisReleveTriggers.idleTick`, toutes les 5 min) : pour chaque poste qui a des sondes de relevé
   actives — en ligne, relevé permis (coupe-circuit, réglage, `low_priority`), aucun tour vivant, non désarmé — si la
   dernière commande hors relevés date de **plus de 20 min**, qu'elle est **postérieure** au dernier relevé et que
   celui-ci date de **plus de 30 min**, un relevé passe (déclencheur `IDLE`), sondes les plus anciennes d'abord. Un seul
   pod le prend (réservation par `UPDATE` conditionnel).
2. **Départ propre** : le crochet d'arrêt du runner (`RunnerShutdown.task`, le vrai) appelle
   `POST /runner/departure` **avant** de couper ses transports et attend **8 s au plus** ; un échec, une gateway
   ancienne ou muette n'allongent jamais l'arrêt.
3. `POST /runner/departure` (jeton du runner, identité tirée du jeton seul) : si un relevé est dû (dernier il y a plus
   de **15 min**), les sondes passent dans un budget de 7,5 s (délai de la dernière ramené au temps restant,
   déclencheur `DEPARTURE`) ; un `YIELDED` arrête la série. Réponse `{probes: n}`.
4. Une extinction brutale ne peut pas être captée : le relevé horaire borne l'âge, et Jarvis le dit.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Sans jeton / jeton invalide | refus | 401 |
| Relevé récent (< 15 min), coupé, runner ancien, tour vivant, désarmé | rien ne passe, `{probes: 0}` | 200 |
| Gateway lente ou absente au départ | l'arrêt continue à 8 s | — |
| Poste actif il y a moins de 20 min, ou rien de neuf depuis le dernier relevé | pas de relevé d'inactivité | — |

## Contraintes de validation
Inactivité 20 min ; écart 30 min ; dû au départ 15 min ; plafond 8 s (runner) / 7,5 s (passerelle).

## Critères d'acceptation
- [x] Départ : dû ⇒ sondes passées, faits rangés ; récent ⇒ rien ; < 8 s quel que soit le nombre de sondes.
- [x] Crochet réel : départ avant coupure, borné, échec sans effet.
- [x] Inactivité : une seule fois tant que le poste ne retravaille pas.
- [x] 401 sans jeton runner.

## Plan de test
- Runner : `RunnerShutdownDepartureTest` (4) ; non-régression `RunnerShutdownTest`.
- Intégration : `JarvisReleveTriggersIntegrationTest` (4).
- Non-régression : `JarvisWatch*`, `JarvisReleve*`.
- Isolation : l'identité vient du jeton ; les sondes sont lues sous `user_id` + `host_id`.

## Impact
- Runner : `RunnerShutdown` (départ borné), `RunnerDeparture` (nouveau), `RunnerConfig.departureUrl`, `RunnerMain`,
  note de version.
- Backend : `jarvis/state/releve/JarvisReleveTriggers`, `RunnerDepartureController` (nouveaux) ;
  `RunnerSecurityConfig` (`/runner/departure`) ; `JarvisWatchPassage`/`JarvisWatchProbeRunner` (délai donné) ;
  `JarvisWatchRepository.claimRun` ; `JarvisWatchRun` (`IDLE`, `DEPARTURE`).
- Aucune table, aucune migration.
- Préoccupations transversales :
  - **Auth / Principal** : nouvelle route `/runner/departure` dans la chaîne `/runner/**`, jeton runner vérifié par le
    contrôleur, rien dans le contexte de sécurité (même régime que `/runner/disconnect`, `RunnerPollController`).
  - **Protocole runner** : `RunnerShutdown.task` (nouvel ordre : départ puis coupure) ; un vieux runner n'appelle rien.

## Arbitrages (réversibles)
- Le relevé d'inactivité exige du **neuf** depuis le dernier relevé (sinon un poste inactif toute la nuit serait relevé
  toutes les 30 min).
- Au départ, un `YIELDED` arrête la série (le PO travaille encore).

## Hors périmètre
La capture d'une extinction brutale (D6).
