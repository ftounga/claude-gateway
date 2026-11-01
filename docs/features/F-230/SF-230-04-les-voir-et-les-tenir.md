# Mini-spec — F-230 / SF-230-04 Les voir et les tenir

## Identifiant
`F-230 / SF-230-04` — Feature parente : F-230 « Les veilles Jarvis » (cadrage, décisions **D1, D8**).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-230-04-les-voir-et-les-tenir`.

## Objectif
Voir et tenir ses veilles — par sujet et pour tout le compte (dernier/prochain passage, état, dépense du mois en €), les mettre en pause, les reprendre, changer leurs heures, les lancer maintenant, les supprimer — et mesurer leur adoption (D8).

## Comportement attendu

### Cas nominal
1. `PUT /api/workspaces/{ws}/watches/{id}` `{ "active"?: bool, "runTimes"?: ["HH:mm"], "monthlyBudgetEur"?: number }` : pause / reprise (reprise ⇒ `last_slot_at` reposé sur le créneau dû : pas de rafale de rattrapage), heures, budget mensuel saisi en euros (0,50 à 100 €). Une proposition ne se met pas en pause (`watch_conflict`).
2. `GET /api/jarvis/watches?hostId=` → les veilles du compte (filtrées sur les sujets de ce poste si `hostId` est donné), avec le nom du sujet (`JarvisWatchSummaryView`) — le **tableau du client** (F-220, `app-client-board`) les montre en tête : nom, sujet, état, prochain passage.
3. Panneau `/veilles` enrichi : état (active, en pause, en panne, proposée), dernier passage (« il y a 2 h · rien de neuf »), prochain passage, dépense du mois en €, gestes [Pause]/[Reprendre], [Lancer maintenant], [Supprimer].
4. **Jarvis propose une veille à l'ouverture d'un chantier** : consigne portée par la description de `veille_poser` (posée dès SF-230-01 pour ne toucher le préfixe qu'une fois) — proposer en une phrase, ne poser qu'après accord.
5. **Mesure d'adoption (D8)** : `GET /api/admin/jarvis/watches/measure?days=7` (ADMIN, sur **ses** veilles — isolation `user_id`, même patron que la mesure du parcours F-176) → `{ watches, active, proposed, broken, passages, changes, failures, missed, alerts, alertsOpened, aiCostEur }` sur la fenêtre. Revue à J+7 et J+14.

### Contrat API
```json
// JarvisWatchSummaryView
{ "watch": { …JarvisWatchView… }, "workspaceName": "cagip" }
// JarvisWatchMeasureView
{ "days": 7, "watches": 3, "active": 2, "proposed": 1, "broken": 0, "passages": 40, "changes": 6,
  "failures": 1, "missed": 2, "alerts": 6, "alertsOpened": 4, "aiCostEur": 0.31 }
```

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Veille d'autrui | `watch_not_found` | 404 |
| Pause d'une proposition | `watch_conflict` | 409 |
| Heures invalides / budget hors bornes | `watch_invalid` | 400 |
| Mesure par un non-ADMIN | refus | 403 |

## Contraintes de validation
Budget mensuel 0,50 à 100 € ; heures comme SF-230-01 ; fenêtre de mesure 1 à 90 jours (défaut 7).

## Critères d'acceptation
- [ ] Pause ⇒ plus aucun passage ; reprise ⇒ le prochain créneau seulement.
- [ ] `/veilles` montre dernier et prochain passage, état, dépense du mois en €, et les gestes.
- [ ] `GET /api/jarvis/watches` ne rend que les veilles du compte.
- [ ] La mesure compte veilles, passages, changements, alertes et alertes ouvertes ; refusée à un non-ADMIN.

## Plan de test
- **Unitaires** : `JarvisWatchMeasureServiceTest`.
- **Intégration** : `JarvisWatchManageApiIntegrationTest` (pause/reprise, heures, budget, liste du compte, **isolation 404**, mesure 403/200).
- **Frontend** : panneau `/veilles` (états, gestes), service.

## Tables / endpoints / composants impactés
Aucune table. Endpoints ci-dessus. Frontend : `atelier-slash-veilles`, `veille.service`, `client-board` (+ `client-board-veilles`).

## Préoccupations transversales
- **Plans / limites** : budget mensuel par veille modifiable (bornes ci-dessus). Composants : `JarvisWatchAlerter` (lit le budget), `JarvisWatchService.update`.
- **Contexte tenant** : liste du compte par `findByUserIdOrderByCreatedAtAsc` ; nom du sujet lu sous `user_id`.
- **Navigation** : aucune route ajoutée.

## Hors périmètre
Veilles le week-end ; écriture sur une plateforme ; la voix (F-231).
