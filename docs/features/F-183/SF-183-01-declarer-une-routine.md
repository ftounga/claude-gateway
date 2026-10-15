# Mini-spec — F-183 / SF-183-01 Déclarer une routine

## Identifiant
`F-183 / SF-183-01` — Feature parente : F-183 « Les routines du sujet » (cadrage `CADRAGE-F-183-les-routines-du-sujet.md`, décision **D1**).
Statut : `ready` — 2026-10-16 — Branche `feat/SF-183-01-declarer-une-routine`.

## Objectif
Permettre de déclarer, par sujet, une routine `{sujet, consigne, rythme (quotidien / jours ouvrés / hebdo + heure, fuseau du poste), budget max par exécution, actif}` — depuis l'écran du sujet, ou proposée par l'agent et validée par une carte.

## Comportement attendu

### Cas nominal
1. **Table `subject_routines`** (migration 154) : une ligne par routine, liée au `user_id` **et** au `workspace_id` (clé étrangère, cascade à la suppression du sujet ; purge nommée à la suppression du compte).
2. **API REST** (identité exclusivement par `CurrentUser`, `requireOwned(userId, workspaceId)` ⇒ 404 indiscernable pour un sujet d'autrui) :
   - `GET  /api/workspaces/{workspaceId}/routines` → `RoutineView[]` (ordre de création) ;
   - `POST /api/workspaces/{workspaceId}/routines` → `201 RoutineView` ;
   - `PUT  /api/workspaces/{workspaceId}/routines/{routineId}` → `RoutineView` ;
   - `DELETE /api/workspaces/{workspaceId}/routines/{routineId}` → `204` (sert aussi à **refuser** une proposition) ;
   - `POST /api/workspaces/{workspaceId}/routines/{routineId}/accept` → `RoutineView` (proposition ⇒ routine active).
3. **Rythme** : `DAILY` (chaque jour), `WEEKDAYS` (lundi→vendredi), `WEEKLY` (+ `dayOfWeek` 1=lundi … 7=dimanche) ; heure `HH:mm` dans le **fuseau du poste** (`timeZone` IANA, envoyé par le navigateur à la création — même règle que la synchro du soir F-100). La vue rend `nextRunAt` (prochain créneau, calcul pur `RoutineSlots`).
4. **Le créneau déjà passé ne part pas** : à la création, à la modification du rythme et à l'activation, `last_slot_at` est posé sur le créneau dû courant — la routine partira au **prochain** créneau, jamais immédiatement pour un créneau d'avant sa déclaration.
5. **Proposée par l'agent** : outil `routine_proposer` (terminal d'un **sujet** sur poste — jamais au terminal du poste, D-hors-périmètre « routines au poste ») ; il crée la routine `proposed=true, active=false, origin=AGENT` et **ne programme rien**. Le terminal affiche une **carte de validation** en tête du fil : « Routine proposée — … [Activer] [Modifier] [Refuser] ».
6. **Écran** : commande `/routines` du terminal du sujet (panneau du fil, aucun tour modèle) — liste, création, modification, activer/mettre en pause, supprimer ; carte des propositions au-dessus du fil (`app-terminal-routines-band`).

### Contrat API (figé — consommé par le frontend de cette SF et par SF-02/03/04)
```json
// RoutineRequest (POST / PUT)
{ "name": "Point du matin", "instruction": "le CR du daily d'aujourd'hui, version scriptée",
  "cadence": "WEEKDAYS", "dayOfWeek": null, "runTime": "08:00", "timeZone": "Europe/Paris",
  "maxRunUsd": 5.00, "active": true }
// RoutineView
{ "id": "uuid", "workspaceId": "uuid", "name": "…", "instruction": "…", "cadence": "WEEKDAYS",
  "dayOfWeek": null, "runTime": "08:00", "timeZone": "Europe/Paris", "maxRunUsd": 5.00,
  "active": true, "proposed": false, "origin": "USER", "nextRunAt": "2026-10-19T08:00:00+02:00",
  "createdAt": "…", "updatedAt": "…" }
// Erreurs : {"error": "<code>", "message": "…"}
```
Champs ajoutés plus tard (additifs) : `lastRun` (SF-02), `monthlyBudgetUsd`, `monthSpentUsd` (SF-03).

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Sujet inexistant ou d'un autre utilisateur | 404 indiscernable | 404 |
| Routine d'un autre sujet / d'un autre utilisateur | `routine_not_found` | 404 |
| Nom vide ou > 80 car., consigne vide ou > 4000 car. | `routine_invalid` + message | 400 |
| Rythme inconnu ; `WEEKLY` sans jour ; jour hors 1..7 | `routine_invalid` | 400 |
| Heure non `HH:mm` ; fuseau inconnu | `routine_invalid` | 400 |
| Budget par exécution hors 0,10 $ … 50,00 $ | `routine_invalid` | 400 |
| Sujet hébergé (bac à sable) ou terminal du poste | `routine_unsupported` (« une routine tourne sur le poste d'un sujet ») | 400 |
| Plus de 10 routines sur le sujet | `routine_limit` | 409 |
| `accept` sur une routine non proposée | `routine_conflict` | 409 |
| Outil `routine_proposer` au terminal du poste / champ invalide | résultat d'outil en erreur, rien n'est créé | — |

## Contraintes de validation
- `name` 1..80 ; `instruction` 1..4000 ; `cadence` ∈ {`DAILY`,`WEEKDAYS`,`WEEKLY`} ; `dayOfWeek` ∈ 1..7, requis ssi `WEEKLY` (ignoré sinon) ; `runTime` `([01]\d|2[0-3]):[0-5]\d` ; `timeZone` IANA valide (défaut `Europe/Paris`) ; `maxRunUsd` ∈ [0,10 ; 50,00], 2 décimales, **défaut 5,00 $** ; 10 routines max par sujet ; `active` défaut `true`.

## Critères d'acceptation
- [ ] Une routine `WEEKDAYS 08:00 Europe/Paris` se crée, se relit, se modifie, se met en pause et se supprime via l'API ; `nextRunAt` = prochain jour ouvré à 8 h (le vendredi 9 h ⇒ lundi 8 h).
- [ ] Un autre utilisateur reçoit 404 sur la liste, la création, la modification, l'acceptation et la suppression.
- [ ] Les validations du tableau rendent 400/409 avec un code stable.
- [ ] L'outil `routine_proposer` crée une routine `proposed=true`, `active=false`, `origin=AGENT`, n'est déclaré que dans un terminal de sujet sur poste, et refuse au terminal du poste.
- [ ] `accept` rend la proposition active ; `DELETE` la refuse ; la carte disparaît.
- [ ] `/routines` ouvre un panneau sans aucun tour modèle ; la carte de proposition s'affiche en tête du fil.
- [ ] Jetons `--cg-*` uniquement, `mat-form-field` outline, aucune `window.confirm`.

## Plan de test
- Unitaires `RoutineSlotsTest` : prochain créneau DAILY / WEEKDAYS (vendredi soir ⇒ lundi) / WEEKLY ; créneau dû ; heure d'été.
- Unitaires `RoutineServiceTest` : validations (chaque ligne du tableau), défaut de budget, limite 10, `last_slot_at` posé à la création, `accept` d'une non-proposition ⇒ conflit.
- Intégration `RoutineApiIntegrationTest` : CRUD complet propriétaire ; **isolation** (bob ⇒ 404 partout) ; sujet bac à sable ⇒ 400 `routine_unsupported`.
- Unitaires `RoutineToolCatalogTest` / exécuteur : déclaré au sujet sur poste seulement ; proposition créée ; entrée invalide ⇒ erreur.
- Front : `routine.service.spec`, `atelier-slash-routines.component.spec` (liste, formulaire, validation, suppression via MatDialog), `terminal-routines-band.component.spec` (carte : Activer / Refuser), registre `/routines`.

## Impacts
- Tables : **`subject_routines`** (nouvelle, migration `154-subject-routines.xml`).
- Endpoints : 5 nouveaux sous `/api/workspaces/{workspaceId}/routines`.
- Composants : paquet `fr.claudegateway.atelier.routine` (entité, dépôt, service, contrôleur, gestionnaire d'erreurs, catalogue + exécuteur d'outil) ; `AtelierChatService` (déclaration + aiguillage de l'outil, par mutateur) ; `AccountService` (purge) ; front : `RoutineService`, `AtelierSlashRoutinesComponent`, `TerminalRoutinesBandComponent`, registre slash.
- **Préoccupations transversales** : Auth ☐ (aucun changement, `CurrentUser`) ; **Tenant ☑** — composants : `RoutineService` (toute lecture `user_id` + `workspace_id` + `requireOwned`), `RoutineRepository` (méthodes `…AndUserIdAndWorkspaceId`), `RoutineToolExecutor` (sujet du tour) ; Plans/limites ☐ (rien n'est encore exécuté) ; **Navigation ☐** (aucune route nouvelle : panneau du terminal).
- Provider Independence : aucune dépendance fournisseur.

## Hors périmètre
Exécution (SF-02) ; plafonds en dollars et budget mensuel (SF-03) ; compte rendu et notifications (SF-04) ; rythmes libres (cron) ; routines au poste.
