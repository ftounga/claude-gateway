# Mini-spec — F-247 / SF-247-03 La file durable générique

## Identifiant
`F-247 / SF-247-03`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.0, D15)

## Statut
`done` (2026-11-05) · **Estimation :** 1,5 j (réestimé 1 j) · **Branche :** `feat/SF-247-03-la-file-durable` · **Dépend de :** SF-247-02

## Objectif
Une seule file durable dans PostgreSQL (`async_jobs`) pour tous les travaux asynchrones de F-247 (extraction, lecture
des captures, constats), sans Kafka ni Redis, sans travail perdu à un redémarrage de pod, jamais dans le fil d'un appelant.

## Comportement attendu

### Cas nominal
1. `AsyncJobQueue.enqueue(Request)` : un seul `INSERT` (dans la transaction de l'appelant s'il y en a une) ;
   `kind` texte libre (liste ouverte : `STATE_EXTRACT`, `CAPTURE_READ`, `STATE_NOTES`…) ; `payload` = références
   (JSON texte) ; `payload_bytes` ≤ 5 Mo (image d'une capture). Jamais d'exception vers l'appelant.
2. `claim(kind, n, owner)` : PostgreSQL ⇒ `SELECT … ORDER BY priority, available_at LIMIT n FOR UPDATE SKIP LOCKED`
   puis `RUNNING` + bail de 2 min dans la même transaction ; H2 ⇒ `UPDATE … WHERE status = 'PENDING'` ligne à ligne.
3. `complete` ⇒ `DONE`, `payload_bytes = NULL`, durée et coût (USD) rangés ; `fail(retryable)` ⇒ recul exponentiel
   (15 s, 30 s, 60 s… ≤ 1 h) tant que `attempts < max_attempts`, sinon `FAILED` + image effacée ; `drop(reason)` ⇒
   `DROPPED` + image effacée. Toute fin vérifie que le bail est encore au même `owner`.
4. Faucheur (30 s) : bail expiré ⇒ `PENDING` (pod mort), ou `FAILED` au-delà des essais. Purge (04:20) : `DONE` > 7 j,
   `FAILED`/`DROPPED` > 30 j.
5. Plafond de travaux en attente par type (défaut 2 000, réglé par l'exécutant) : au-delà, rangé en `DROPPED`
   (`pending_cap`), compté.
6. `AsyncJobWorker` : un pool de **démons à priorité minimale** par type, borné à la concurrence de son exécutant
   (`AsyncJobHandler`, 1 fil par défaut) ; scrutation toutes les 2 s ; coupe-circuit `app.jobs.enabled` et `active()`
   par exécutant (travaux gardés en attente).
7. `measure(userId, since)` : par type, nombres par statut, durée moyenne, coût cumulé — sous `user_id`.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Base indisponible à la mise en file | `Optional.empty()`, journal sans contenu, l'appelant continue |
| Exécutant qui lève | échec réessayable, recul, puis `FAILED` |
| Pod mort en plein travail | bail expiré, repris par un autre pod (faucheur) |
| Fin signalée par un autre `owner` | refusée (aucune ligne modifiée) |
| Plafond atteint | `DROPPED`, compté |

## Contraintes de validation
Bail 2 min ; `max_attempts` ≥ 1 (3 par défaut) ; `payload_bytes` ≤ 5 Mo (au-delà : ignoré) ; `error_code` ≤ 32 car.

## Critères d'acceptation
- [x] Prise unique (H2) ; deux à quatre consommateurs concurrents sur PostgreSQL : 500 travaux, 0 doublon, 0 perdu.
- [x] Priorité puis ancienneté ; recul ; `FAILED` au-delà des essais ; image effacée à toute fin.
- [x] Faucheur, plafond `DROPPED`, purge.
- [x] Le travail s'exécute sur un fil `job-*` démon, jamais dans le fil appelant.
- [x] La prise PostgreSQL passe par l'index partiel `idx_async_jobs_pending`.

## Plan de test
- Intégration H2 : `AsyncJobQueueIntegrationTest` (4 : cycle de vie + mesure + isolation de la mesure, recul / échec, faucheur / plafond / purge, fil de travail).
- PostgreSQL (Testcontainers) : `AsyncJobQueuePostgresTest` (4 consommateurs concurrents, 0 doublon, plan par l'index partiel).

## Impact
- Table : `async_jobs` (migration `247-03-async-jobs.xml`, réversible ; index partiel PostgreSQL, index plein ailleurs).
- Backend : `shared/jobs/AsyncJobQueue`, `AsyncJobHandler`, `AsyncJobWorker`.
- Aucun endpoint (la mesure par type est servie par `GET /api/admin/jarvis/state/measure` dès SF-247-04).
- Préoccupations transversales : Contexte tenant — `user_id` porté par chaque travail ; chaque exécutant relit ses données sous ce `user_id` (contrat de `AsyncJobHandler`) ; la mesure filtre `user_id`.

## Arbitrage
`payload` en `text` (JSON) plutôt qu'en `jsonb` : même schéma sous H2 et PostgreSQL, aucune requête ne filtre dans le JSON (références seulement). Réversible par une migration.

## Hors périmètre
Les exécutants (SF-247-04, -12, -14). Le relevé (planificateur des veilles, D5).
