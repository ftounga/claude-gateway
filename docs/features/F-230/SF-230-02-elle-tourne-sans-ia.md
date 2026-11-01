# Mini-spec — F-230 / SF-230-02 Elle tourne sans IA

## Identifiant
`F-230 / SF-230-02` — Feature parente : F-230 « Les veilles Jarvis » (cadrage, décisions **D3, D6, D7**).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-230-02-elle-tourne-sans-ia`.

## Objectif
Aux heures de la veille, du lundi au vendredi, la gateway lance la sonde **comme une commande** sur le poste (jamais un tour d'agent), compare la liste à l'instantané précédent et **ne fait rien** si rien n'a bougé ; poste hors ligne ⇒ passage manqué rattrapé une fois ; deux échecs d'affilée ⇒ « veille en panne ».

## Comportement attendu

### Cas nominal
1. **Planificateur** `JarvisWatchPlanner` (tâche de fond toutes les 60 s, `app.jarvis.watches.enabled`, désactivée en tests) — **réutilise** les créneaux des routines (`RoutineSlots`, rythme `WEEKDAYS`) et leur verrou par `UPDATE` conditionnel (`claimSlot`) : deux pods au même créneau n'en lancent qu'un (D7). Une veille n'est pas une routine : table, journal et exécution propres.
2. **Créneau dû** = la dernière heure passée parmi celles de la veille, un jour ouvré. Déjà consommé ⇒ rien.
3. **Garde tenant à chaque passage** : le sujet est relu par `findByIdAndUserId(workspaceId, userId)` ; s'il n'est plus un sujet sur poste, rien ne part.
4. **Poste hors ligne** ⇒ une ligne `MISSED_OFFLINE` **une seule fois** par créneau, le créneau n'est pas consommé ; à la reconnexion, seul le **dernier** créneau dû part, une fois (`CATCH_UP`).
5. **Passage** (exécuteur borné, 4 fils) : `JarvisWatchProbeRunner.run` (script épinglé revérifié, commande, liste normalisée) puis :
   - **Premier passage** (aucun instantané) ⇒ instantané posé, ligne `BASELINE`, aucune alerte.
   - **Diff** par élément (`JarvisWatchDiff`, calcul pur) : `NEW` (apparu), `GONE` (sorti du périmètre), `STATUS`, `ASSIGNEE`, `COMMENT`, `UPDATED` (titre ou date). Vide ⇒ ligne `NO_CHANGE`, rien d'autre (coût ≈ 0, D3). Non vide ⇒ instantané remplacé, ligne `CHANGED` + nombre de changements, et l'**écouteur de changement** (`JarvisWatchChangeListener`, branché par SF-230-03) reçoit le diff.
   - Le week-end ne passe pas : le passage du lundi compare avec le vendredi, en un seul diff.
6. **Pannes (D6)** : un échec (sortie illisible, code ≠ 0, script modifié, délai) ⇒ ligne `FAILED` + `error_code` fermé, `consecutive_failures + 1`. Au **2ᵉ** échec d'affilée : `broken = true`, notification « Une veille est en panne » (`PushEvent.WATCH_BROKEN`, corps neutre) et **ligne dans le fil** du sujet / chantier proposant la réparation par l'agent (« demande-moi de la réparer » ⇒ `veille_poser` du même nom). Un succès remet le compteur à zéro et lève la panne.
7. Une veille **reproposée** (même nom) perd son instantané : le prochain passage repart d'une base.
8. Endpoints : `POST /api/workspaces/{ws}/watches/{id}/run` (« Lancer maintenant », mêmes gardes, **synchrone** — la sonde dure au plus une minute — `200` + `JarvisWatchRunView` du passage terminé) ; `GET /api/workspaces/{ws}/watches/{id}/runs` (20 derniers passages).

### Contrat API (additif)
```json
// JarvisWatchView + :
{ "lastRunAt": "…", "lastRunStatus": "NO_CHANGE", "broken": false, "consecutiveFailures": 0 }
// JarvisWatchRunView
{ "id": "uuid", "watchId": "uuid", "slotAt": "…", "trigger": "SCHEDULED|CATCH_UP|MANUAL",
  "status": "BASELINE|NO_CHANGE|CHANGED|FAILED|MISSED_OFFLINE", "changeCount": 2, "errorCode": null,
  "durationMs": 840, "startedAt": "…", "finishedAt": "…" }
```

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Veille d'autrui | `watch_not_found` | 404 |
| `run` sur une proposition | `watch_conflict` | 409 |
| `run` poste hors ligne | `watch_host_offline` | 409 |
| Sonde en échec | ligne `FAILED`, compteur ; 2ᵉ ⇒ panne notifiée | — |
| Sujet devenu hébergé / supprimé | rien ne part (cascade à la suppression) | — |

## Contraintes de validation
Délai de sonde 60 s ; 500 éléments ; instantané = un par veille (remplacé) ; journal : 20 derniers passages lus, purge des passages de plus de 90 jours (`NO_CHANGE` compris) ; panne au 2ᵉ échec d'affilée.

## Critères d'acceptation
- [ ] Une veille active 09:00 passe une fois à 09:00 un jour ouvré, jamais le samedi ni le dimanche ; deux passages du planificateur dans la même minute n'en lancent qu'un.
- [ ] Rien n'a bougé ⇒ `NO_CHANGE`, aucun appel IA, aucune notification ; un ticket passé en « Bloqué » ⇒ `CHANGED` avec un changement `STATUS`, l'écouteur reçoit le diff.
- [ ] Poste hors ligne à 09:00 ⇒ une seule ligne `MISSED_OFFLINE` ; reconnecté à 11:00 ⇒ un passage `CATCH_UP`.
- [ ] Deux échecs d'affilée ⇒ `broken`, notification `WATCH_BROKEN`, une ligne dans le fil ; un succès lève la panne.
- [ ] Lecture des passages par un autre compte ⇒ 404.

## Plan de test
- **Unitaires** : `JarvisWatchDiffTest` (nouveau, sorti, statut, assigné, commentaire, rien), `JarvisWatchPlannerTest` (créneau dû, week-end, hors ligne + rattrapage, verrou, base, changement, panne au 2ᵉ échec).
- **Intégration** : `JarvisWatchRunApiIntegrationTest` (run + historique, **isolation 404**).

## Tables / endpoints / composants impactés
Tables **`jarvis_watch_snapshots`**, **`jarvis_watch_runs`** (migration **186**). `PushEvent` (+ `WATCH_BROKEN`). `AtelierMessageRepository` (ligne du fil). Frontend : bande des veilles (veille en panne), `veille.models.ts`.

## Préoccupations transversales
- **Contexte tenant** : planificateur (`findByIdAndUserId` du sujet à chaque passage), instantanés et passages portent `user_id` et sont lus sous `user_id` (+ `workspace_id`). Composants : `JarvisWatchPlanner`, `JarvisWatchPassage`, `JarvisWatchRunService`, `JarvisWatchThreadNote`.
- **Plans / limites** : aucune consommation IA dans cette SF.

## Hors périmètre
L'appel IA et la notification de changement (SF-03) ; pause/reprise (SF-04) ; week-end.
