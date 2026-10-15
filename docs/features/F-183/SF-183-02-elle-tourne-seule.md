# Mini-spec — F-183 / SF-183-02 Elle tourne seule

## Identifiant
`F-183 / SF-183-02` — Feature parente : F-183 « Les routines du sujet » (décisions **D2**, **D5**).
Statut : `ready` — 2026-10-16 — Branche `feat/SF-183-02-elle-tourne-seule`.

## Objectif
À l'heure dite, un worker démarre **sans écran** un tour dans le terminal du sujet (mécanisme F-84 du tour vivant, comme le lanceur MCP F-112), poste requis en ligne ; poste hors ligne ⇒ exécution « manquée — poste hors ligne », rattrapée **une seule fois** à la reconnexion ; chaque exécution est tracée dans une table interrogeable avant un déploiement (D5).

## Comportement attendu

### Cas nominal
1. **Table `routine_runs`** (migration 155) : `id, routine_id (FK cascade), user_id, workspace_id (FK cascade), slot_at, trigger_kind (SCHEDULED | CATCH_UP | MANUAL), status (RUNNING | DONE | INCOMPLETE | FAILED | INTERRUPTED | MISSED_OFFLINE | SKIPPED_BUDGET), started_at, finished_at, cost_usd, cap_usd, turn_id, error_code, summary`. Index `(status, started_at)` pour la requête D5, `(routine_id, started_at)` pour l'historique. Colonne `subject_routines.missed_slot_at` ajoutée.
2. **Planificateur** `RoutinePlanner.runOnce(now)` (tâche `RoutineWorker`, toutes les 60 s, désactivable `app.routines.enabled=false`) — pour chaque routine **active, non proposée** :
   - créneau dû = dernier créneau ≤ maintenant selon le rythme ; déjà traité (`last_slot_at ≥ dû`) ⇒ rien ;
   - sujet relu sous `findByIdAndUserId` (isolation **à chaque exécution**) ; plus sur poste ⇒ rien ;
   - **poste hors ligne** (`RunnerLiveness.isAlive(userId, hostId)`) ⇒ une ligne `MISSED_OFFLINE` (une seule par créneau, `missed_slot_at`), le créneau n'est **pas** consommé ;
   - **un tour tourne déjà sur ce sujet** (sur ce pod) ⇒ rien, réessai à la minute suivante (on ne coupe jamais le travail de l'utilisateur, on ne s'y glisse pas en précision) ;
   - **verrou** : `UPDATE … SET last_slot_at = :dû WHERE id = :id AND (last_slot_at IS NULL OR last_slot_at < :dû)` — un seul pod gagne ;
   - déclencheur `CATCH_UP` si le créneau avait été manqué ou si l'on démarre plus de 10 min après lui, sinon `SCHEDULED` ; une ligne `RUNNING` est écrite (`started_at`), puis le tour part.
   - **Rattrapage une seule fois** : après plusieurs créneaux manqués, seul le **dernier** part au retour du poste ; les précédents restent `MISSED_OFFLINE`.
3. **Exécution** `RoutineTurnRunner` : ouvre un tour vivant **seulement si le sujet est libre** (`LiveTurnRegistry.openIfIdle`, atomique), publie « _Routine « Nom » — exécution automatique._ », puis la boucle `AtelierChatService.chatStreaming` (même modèle, même raisonnement, mêmes outils que le clavier — **rien n'est dégradé**). La demande persistée dans le fil est « Routine « Nom » (exécution automatique) : » + consigne. Un écran ouvert ensuite sur le sujet voit le tour en direct (F-84 / SF-84-02). Les précisions envoyées pendant la routine sont traitées comme au clavier (tour de suite).
4. **Fin** : `finished_at`, statut (`DONE`, `INCOMPLETE` si plafond, `INTERRUPTED` si interrompu, `FAILED` + `error_code` : `quota_exceeded`, `provider_unavailable`, `runner_offline`, …), `cost_usd` = somme des coûts des tours (`AtelierChatResult.costUsd`).
5. **Exécutions orphelines** : une ligne `RUNNING` plus vieille que `app.routines.stale-after` (défaut `PT3H`, > 2 × budget de tour de 60 min) est close `FAILED / interrupted` — la requête D5 ne doit pas croire un pod mort encore au travail.
6. **Lancer maintenant** : `POST /api/workspaces/{workspaceId}/routines/{routineId}/run` → `202 RoutineRunView` (`trigger=MANUAL`), mêmes gardes (poste en ligne ⇒ sinon 409 `routine_host_offline` ; sujet occupé ⇒ 409 `routine_busy`). Sert à vérifier une routine sans attendre son heure.
7. **Historique** : `GET …/routines/{routineId}/runs` → 20 dernières `RoutineRunView` ; `RoutineView.lastRun` (additif).
8. **D5** : `deploy2.sh` (hors dépôt) **n'est pas modifié** ; la requête des routines en cours est documentée dans `docs/DEPLOYMENT.md` et la PR :
   ```sql
   SELECT r.id, r.workspace_id, r.trigger_kind, r.started_at, now() - r.started_at AS depuis
   FROM routine_runs r
   WHERE r.status = 'RUNNING' AND r.finished_at IS NULL
   ORDER BY r.started_at;
   ```

### Contrat API (figé, additif)
```json
// RoutineRunView
{ "id": "uuid", "routineId": "uuid", "slotAt": "…", "trigger": "SCHEDULED", "status": "DONE",
  "startedAt": "…", "finishedAt": "…", "costUsd": 0.84, "capUsd": null, "errorCode": null, "summary": null }
// RoutineView (+)
"lastRun": RoutineRunView | null
```

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Poste hors ligne à l'heure | ligne `MISSED_OFFLINE`, rattrapage unique à la reconnexion | — |
| Deux pods au même créneau | un seul gagne le verrou, un seul tour | — |
| Sujet occupé par un tour de l'utilisateur | reporté à la minute suivante, jamais injecté en précision | — |
| Quota épuisé / fournisseur indisponible / poste perdu en cours | `FAILED` + `error_code` | — |
| Pod tué pendant la routine | `FAILED / interrupted` après `stale-after` | — |
| `run` : poste hors ligne / sujet occupé / routine en proposition | 409 `routine_host_offline` / `routine_busy` / `routine_conflict` | 409 |
| Routine ou sujet d'autrui | 404 | 404 |

## Contraintes de validation
Intervalle 60 s ; grâce de rattrapage 10 min ; `stale-after` PT3H ; historique 20 lignes ; un seul tour par sujet.

## Critères d'acceptation
- [ ] Routine `WEEKDAYS 08:00`, poste en ligne, horloge à lundi 08:00:30 ⇒ un tour démarre sans écran, ligne `RUNNING` puis `DONE` avec `started_at`/`finished_at`.
- [ ] Samedi 08:00 ⇒ rien. Créneau déjà traité ⇒ rien au tick suivant.
- [ ] Poste hors ligne ⇒ 1 seule ligne `MISSED_OFFLINE` par créneau ; retour en ligne ⇒ 1 seul tour `CATCH_UP`.
- [ ] Deux planificateurs concurrents ⇒ un seul tour.
- [ ] Tour déjà vivant sur le sujet ⇒ aucun tour, aucune précision injectée.
- [ ] `RUNNING` orpheline > 3 h ⇒ close `FAILED`.
- [ ] `run` manuel ⇒ 202 ; isolation 404 pour un autre utilisateur.
- [ ] La requête D5 est documentée dans `docs/DEPLOYMENT.md` et la PR ; `deploy2.sh` non touché.

## Plan de test
- Unitaires `RoutineSlotsTest` (créneau dû par rythme, compléments).
- Unitaires `RoutinePlannerTest` (horloge fixe, mocks liveness / registre / lanceur) : nominal, week-end, déjà traité, hors ligne ⇒ manquée unique, reconnexion ⇒ CATCH_UP unique, occupé ⇒ report, verrou perdu ⇒ rien, orphelines closes.
- Unitaires `RoutineTurnRunnerTest` : statut DONE / INCOMPLETE / INTERRUPTED / FAILED(code), coût cumulé, sujet occupé ⇒ pas de tour.
- Intégration `RoutineRunApiIntegrationTest` : `run` manuel (poste hors ligne ⇒ 409), historique, isolation 404 ; verrou SQL réel (deux claims ⇒ un seul gagnant).
- Front : panneau `/routines` montre prochain passage, dernier passage (statut en clair, « Manquée — poste hors ligne »), bouton « Lancer maintenant ».

## Impacts
- Tables : **`routine_runs`** (nouvelle), `subject_routines.missed_slot_at` (colonne) — migration `155-routine-runs.xml`.
- Endpoints : `POST …/{routineId}/run`, `GET …/{routineId}/runs` ; `RoutineView.lastRun` additif.
- Composants : `RoutinePlanner`, `RoutineWorker`, `RoutineTurnRunner`, `RoutineRunService`, `LiveTurnRegistry.openIfIdle` (nouvelle méthode, `openOrSteer` inchangée), `AtelierMcpTurnLauncher` (écouteur exposé, comportement inchangé).
- **Préoccupations transversales** : **Plans/limites ☑** — composants : garde de quota du tour (`QuotaService.assertWithinQuota` appelée par `chatStreaming`, inchangée), `AtelierTurnBudget` (plafond de tokens du message, inchangé), `UsageLedgerService`/`usage_turns` (le tour de routine est décompté comme un tour au clavier), `QuotaWindowService` (inchangé) ; **Tenant ☑** — `RoutinePlanner` relit le sujet par `findByIdAndUserId` à chaque exécution, `RoutineRunService` filtre `user_id` + `workspace_id`, `RunnerLiveness.isAlive(userId, hostId)` ; Auth ☐ ; Navigation ☐.
- Provider Independence : la boucle passe par `AtelierChatService` / `AIProvider`, aucune dépendance Anthropic directe.

## Hors périmètre
Plafond en dollars et budget mensuel (SF-03) ; notification et ligne de compte rendu (SF-04) ; modification de `deploy2.sh` ; exécution hébergée sans runner ; rythmes libres.
