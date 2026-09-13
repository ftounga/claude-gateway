# Mini-spec — F-100 / SF-100-02 — La planification

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §5 (« chaque jour, à une heure choisie par poste,
> 22 h 00 par défaut », « Synchroniser maintenant », « portable fermé à l'heure prévue : la synchro part
> à la prochaine connexion ») et §12 bis SF-100-02 ; §14 (prérequis contractuel à l'activation). Le
> cadrage est validé : cette mini-spec l'applique.

## Identifiant

`F-100 / SF-100-02`

## Feature parente

`F-100` — Le Radar : la synchro du soir

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-100-02-planification`

---

## Objectif

Faire partir la synchro de chaque poste **tous les soirs à son heure** (22 h 00 par défaut, dans le
fuseau du poste) et **à la demande**, **une seule à la fois par poste tous pods confondus**, **rattrapée
à la prochaine connexion** quand le portable était fermé — et poser le **protocole** par lequel le
runner rend compte d'une synchro (battement, fin) à la gateway.

---

## Contexte

La collecte est longue et se fait sur la machine (§5) : la gateway **déclenche** et **suit**, le runner
**travaille en tâche de fond** et rend compte. Cette sous-feature livre le déclenchement, le verrou par
poste, le rattrapage et le squelette du travail côté runner ; **la collecte Teams elle-même** est
SF-100-03 (d'ici là, le travail du runner se termine en échec **nommé** « collecte non disponible sur ce
runner » — jamais une synchro vide présentée comme réussie). Couverture détaillée, progression et
annulation : SF-100-04. Écrans : F-102 (tracé au cadrage).

---

## Comportement attendu

### Cas nominal

**Activer et régler** — `PUT /api/radar/hosts/{hostId}/schedule`
`{ "enabled": true, "syncTime": "22:00", "timeZone": "Europe/Paris", "clientAuthorizationConfirmed": true }`

1. Première activation : `clientAuthorizationConfirmed=true` est **exigé** (§14 : l'utilisateur confirme
   que son client autorise la conservation d'extraits) ; l'instant est gardé (`client_authorized_at`).
2. `syncTime` (`HH:mm`, 00:00–23:59) et `timeZone` (identifiant IANA) sont enregistrés ; valeurs par
   défaut 22:00 et `Europe/Paris`.
3. À chaque réglage, le **dernier créneau passé** est marqué traité : activer à 23 h ne déclenche pas la
   synchro de 22 h ; la première synchro planifiée est le prochain créneau.
4. `GET …/schedule` rend : `enabled`, `clientAuthorizedAt`, `syncTime`, `timeZone`, `nextSyncAt`
   (prochain créneau, instant absolu), `missedSlotAt` (créneau manqué en attente de rattrapage),
   `running` (synchro en cours : identifiant, déclencheur, début) ou `null`.

**Le planificateur** — tâche de fond toutes les 60 s (`RadarSyncWorker` → `RadarSyncPlanner.runOnce`),
sur les postes activés :

5. **Synchro abandonnée** : une synchro `RUNNING` sans battement depuis 15 min est close `FAILED`
   (« interrompue : le poste ne répond plus ») et le poste est libéré.
6. Créneau dû = le dernier `syncTime` passé dans le fuseau du poste. S'il n'est pas encore traité :
   - le compte n'a plus le droit (Teams, provisoire) → rien (réessayé plus tard) ;
   - **runner vivant** (battement frais, F-97) → démarrage, déclencheur `SCHEDULED` si l'on est à moins de
     15 min du créneau, **`CATCH_UP`** au-delà ; le créneau est marqué traité ;
   - runner muet → `missed_slot_at` = le créneau (la synchro partira dès la prochaine connexion, au plus
     tard 60 s après) ;
   - une synchro est déjà en cours (manuelle par ex.) → le créneau est marqué traité, rien n'est lancé.

**Synchroniser maintenant** — `POST /api/radar/hosts/{hostId}/syncs` → 202, déclencheur `MANUAL`.

**Démarrer une synchro** (commun) :

7. Dans une transaction : le poste doit être activé ; la synchro est créée (`RUNNING`, déclencheur,
   `scheduled_for` le cas échéant, `heartbeat_at` = maintenant), puis le poste est **pris** par mise à
   jour conditionnelle (`running_sync_id` nul → la synchro) : **un seul démarrage gagne, tous pods
   confondus** ; le perdant annule sa transaction.
8. Hors transaction : la gateway demande au runner du poste `teams_radar_collect`
   (`sync_id`, `trigger`, `first_sync`, `window_from` = maintenant − 30 jours pour la première synchro,
   sinon le début de la dernière synchro réussie ou partielle). Le runner **accepte et rend la main**
   (délai 20 s) ; le travail tourne en tâche de fond sur la machine et ne bloque ni terminaux ni
   commandes.
9. Refus du runner (occupé, sans jeton, volet absent, injoignable) → la synchro est close `FAILED` avec
   la raison dans sa couverture, le poste est libéré.

**Le runner rend compte** (jeton runner `X-Runner-Token`, le périmètre vient **du jeton**) :

10. `POST /runner/radar/syncs/{syncId}/progress` `{ "phase", "done", "total" }` — battement ; rend
    `{"status":"RUNNING"}`. Si la synchro n'est plus en cours (annulée, abandonnée) → 409
    `{"status": "…"}` : le runner **s'arrête**.
11. `POST /runner/radar/syncs/{syncId}/finish` `{ "status": "SUCCEEDED|PARTIAL|FAILED", "coverage": {…} }`
    → `RadarRegistry.finishSync` (couverture JSON bornée), poste libéré. La première fin gagne ; une fin
    sur une synchro déjà close → 409.
12. Côté runner : un seul travail à la fois (`RadarSyncAgent`), qui envoie son battement au moins toutes
    les 60 s et sa fin ; **sans collecteur** (avant SF-100-03), il se termine `FAILED`
    (`COLLECTOR_UNAVAILABLE`, « collecte Teams non disponible sur ce runner »).
13. `GET /api/radar/hosts/{hostId}/syncs` expose en plus, par synchro : `trigger`, `scheduledFor`,
    `heartbeatAt` — de quoi écrire « synchro d'hier soir non faite, rattrapée à 8 h 12 ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste d'autrui / inconnu (routes utilisateur) | `not_found`, runner jamais appelé | 404 |
| Première activation sans `clientAuthorizationConfirmed` | `radar_invalid` | 400 |
| `syncTime` hors `HH:mm` ou `timeZone` inconnu | `radar_invalid` | 400 |
| *Synchroniser maintenant* sur un poste non activé | `radar_state_conflict` | 409 |
| *Synchroniser maintenant* alors qu'une synchro tourne | `radar_sync_running` (identifiant de la synchro en cours) | 409 |
| *Synchroniser maintenant*, runner hors ligne | `radar_runner_unavailable`, **aucune synchro créée** | 409 |
| Runner qui refuse le travail (occupé, sans jeton, volet absent) | synchro close `FAILED` avec la raison, poste libéré, `radar_runner_unavailable` / `radar_teams_disabled` | 409 |
| Jeton runner absent ou refusé (routes runner) | 401 générique | 401 |
| Synchro d'un autre poste ou compte (routes runner) | 404 | 404 |
| Battement ou fin sur une synchro close | 409 `{status}` | 409 |
| Fin avec un statut `RUNNING` ou inconnu, couverture > 16 000 caractères | 400 | 400 |

---

## Critères d'acceptation

- [ ] Migration `087-radar-sync-schedule.xml` : colonnes de planification sur `radar_host_settings`
      (`enabled`, `client_authorized_at`, `sync_time`, `time_zone`, `last_slot_date`, `missed_slot_at`,
      `running_sync_id`) et de suivi sur `radar_syncs` (`trigger_kind`, `scheduled_for`, `heartbeat_at`,
      `progress`) ; PostgreSQL et H2 ; rollback.
- [ ] Première activation refusée sans confirmation d'autorisation du client ; réglages validés.
- [ ] Activer après l'heure ne déclenche pas le créneau passé.
- [ ] Créneau dû + runner vivant → une synchro `SCHEDULED` (ou `CATCH_UP` après 15 min), créneau traité ;
      runner muet → `missed_slot_at`, puis synchro `CATCH_UP` dès que le runner bat.
- [ ] Fuseau respecté : 22:00 `Europe/Paris` et 22:00 `America/New_York` ne tombent pas au même instant.
- [ ] **Un seul démarrage par poste** : deux démarrages concurrents → un seul gagne, l'autre 409 ; une
      synchro sans battement depuis 15 min est close `FAILED` et libère le poste.
- [ ] Le runner est appelé avec `sync_id`, `trigger`, `first_sync`, `window_from` (30 jours à la première
      synchro) ; un refus close la synchro `FAILED`.
- [ ] Routes runner : jeton requis ; périmètre tiré du jeton ; battement → `heartbeat_at` ; fin → statut,
      couverture, poste libéré ; synchro close → 409.
- [ ] Runner : un seul travail à la fois ; battement et fin envoyés ; sans collecteur → `FAILED` nommé.
- [ ] **Isolation** : Bob ne règle, ne lance ni ne lit la planification du poste d'Alice ; le jeton du poste B
      ne peut ni battre ni finir une synchro du poste A ; la purge efface les réglages.

---

## Périmètre

### Hors scope (explicite)

- La collecte Teams, les lots, les curseurs (SF-100-03) ; la forme détaillée de la couverture, la
  progression affichable, l'annulation, *lire ce canal* / *ignorer ce fil* (SF-100-04) ; le dossier de
  dépôt (SF-100-05).
- L'écran (F-102) ; l'activation dans la Vigie (F-106) qui appellera `PUT …/schedule`.
- La réserve de synchro et le droit Vigie (F-107) : le droit Teams reste le droit provisoire du Radar.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `enabled` | `false` | activé par `PUT …/schedule` |
| `sync_time` | `22:00` | `app.radar.sync.default-time` |
| `time_zone` | `Europe/Paris` | `app.radar.sync.default-zone` |
| intervalle du planificateur | 60 s | `app.radar.sync.interval` |
| grâce avant `CATCH_UP` | 15 min | fixe |
| abandon sans battement | 15 min | `app.radar.sync.stale-after` |
| fenêtre de la première synchro | 30 jours | `app.radar.sync.first-window` |
| délai de l'appel `teams_radar_collect` | 20 s | fixe |

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| `enabled` | Oui | booléen |
| `syncTime` | Non | `HH:mm`, 00:00 → 23:59 |
| `timeZone` | Non | identifiant IANA reconnu par `ZoneId` |
| `clientAuthorizationConfirmed` | À la 1ʳᵉ activation | `true` |
| `progress.phase` | Non | ≤ 64 caractères |
| `progress.done` / `total` | Non | entiers ≥ 0 |
| `finish.status` | Oui | `SUCCEEDED`, `PARTIAL`, `FAILED` |
| `finish.coverage` | Non | objet JSON, ≤ 16 000 caractères sérialisé |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/schedule` | JWT | droit Teams (Radar) |
| PUT | `/api/radar/hosts/{hostId}/schedule` | JWT | droit Teams (Radar) |
| POST | `/api/radar/hosts/{hostId}/syncs` | JWT | droit Teams (Radar) |
| GET | `/api/radar/hosts/{hostId}/syncs` (enrichi) | JWT | droit Teams (Radar) |
| POST | `/api/runner/radar/syncs/{syncId}/progress` | jeton runner | droit Teams du compte du jeton |
| POST | `/api/runner/radar/syncs/{syncId}/finish` | jeton runner | — (une fin est toujours acceptée) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_host_settings` | SELECT / INSERT / UPDATE | colonnes de planification (087) |
| `radar_syncs` | INSERT / UPDATE / SELECT | colonnes de suivi (087) |

### Migration Liquibase

- [x] Oui — `087-radar-sync-schedule.xml`

### Composants

| Composant | Rôle |
|-----------|------|
| `radar/sync/RadarScheduleService` | réglages, prochain créneau |
| `radar/sync/RadarSlots` | calcul du créneau dû dans le fuseau (pur, testable) |
| `radar/sync/RadarSyncLauncher` | démarrage commun, verrou par poste, appel `teams_radar_collect` |
| `radar/sync/RadarSyncPlanner` + `RadarSyncWorker` | créneaux dus, rattrapage, abandons |
| `radar/sync/RadarSyncSessionService` + `RunnerRadarSyncController` | battement et fin rendus par le runner |
| `radar/sync/RadarSyncProperties` | réglages `app.radar.sync.*` |
| `radar/RadarReadService` / `RadarViews.SyncView` | `trigger`, `scheduledFor`, `heartbeatAt` |
| `runner/RunnerSecurityConfig` | deux routes runner déclarées une par une |
| runner `teams/RadarSyncAgent`, `RadarSyncJob`, `RadarUplink`, `RadarCollector` | travail en tâche de fond, remontée par jeton |
| runner `ToolStack` / `TeamsTools` | montage de la remontée ; `teams_radar_collect` |

### Composants Angular

- Aucun (F-102).

---

## Plan de test

### Tests unitaires

- [ ] `RadarSlotsTest` — créneau dû avant/après l'heure, fuseaux Paris / New York, changement d'heure
      d'été, grâce `SCHEDULED` / `CATCH_UP`.
- [ ] runner `RadarSyncAgentTest` — un travail à la fois (`BUSY`), battement et fin envoyés, arrêt quand la
      gateway répond 409, sans collecteur → `FAILED` `COLLECTOR_UNAVAILABLE`, sans jeton → refus nommé.

### Tests d'intégration

- [ ] `RadarScheduleApiIntegrationTest` — activation (confirmation exigée), validation, créneau passé non
      déclenché, *Synchroniser maintenant* (202, appel runner avec la fenêtre de 30 jours ; poste non
      activé 409 ; hors ligne 409 sans synchro ; refus du runner → `FAILED` et poste libéré ; déjà en
      cours 409).
- [ ] `RadarSyncPlannerIntegrationTest` — créneau dû + vivant → `SCHEDULED` ; muet → `missed_slot_at`
      puis `CATCH_UP` ; synchro en cours → créneau traité sans lancement ; abandon après 15 min ; deux
      démarrages concurrents → un seul.
- [ ] `RunnerRadarSyncApiIntegrationTest` — jeton requis ; battement ; fin ; synchro close → 409 ;
      `GET /syncs` enrichi.

### Isolation utilisateur

- [x] Applicable — Bob → 404 sur `schedule` et `syncs` d'Alice ; jeton du poste B d'Alice → 404 sur une
      synchro du poste A ; jeton de Bob → 404 ; purge efface les réglages.

---

## Dépendances

### Subfeatures bloquantes

- SF-100-01 (réglages du poste) — en cours de merge, prérequis de branche. F-99 (synchros) — `done`.
  F-97 (battement du runner) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants impactés : `RadarScopeResolver` (routes utilisateur, inchangé) ;
  `RunnerTokenAuthenticator` (routes runner : le périmètre `user_id` + `host_id` vient **du jeton**,
  inchangé) ; `RadarSyncLauncher`, `RadarSyncPlanner`, `RadarSyncSessionService` (toute requête à
  `user_id` + `host_id`) ; `RadarReadService.syncs`.
- **Plans / limites : oui (réemploi).** Composants : `TeamsAccessService.requireAccess` (routes
  utilisateur) et `hasAccess(userId)` (planificateur, battement) — droit Teams provisoire du Radar ;
  `RunnerLiveness.isAlive` (vivant = battement frais, F-97, inchangé).
- **Auth / Principal : non** pour les utilisateurs ; **chaîne runner** : deux routes ajoutées à
  `RunnerSecurityConfig`, déclarées une par une (jamais `/runner/radar/**`), authentifiées par le
  contrôleur, rien posé dans le `SecurityContext` (D9, inchangé).
- **Navigation / routing : non.**

---

## Notes et décisions

- **Verrou par poste = colonne `running_sync_id`** prise par mise à jour conditionnelle : même famille que
  le bail de F-101, sans table de plus. Réversible.
- **Le planificateur interroge, le runner ne tire pas** : 60 s de latence au plus pour le rattrapage, et
  aucun appel périodique ajouté côté machine. Réversible.
- **Le dernier créneau passé est marqué traité au réglage** : régler l'heure ne doit pas déclencher de
  synchro surprise.
- **Un seul rattrapage** : un portable fermé trois soirs de suite rattrape **une** synchro (la collecte
  est incrémentale, elle lit tout le nouveau depuis la dernière réussie).
