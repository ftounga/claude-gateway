# Mini-spec — F-60 / SF-60-01 — L'état de mission, côté gateway

---

## Identifiant

`F-60 / SF-60-01`

## Feature parente

`F-60` — Cycle de vie des postes, visible d'un coup d'œil (`docs/PRODUCT_SPEC.md`)

## Statut

`done` — PR #351, mergée le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-60-01-etat-mission-gateway`

---

## Objectif

Donner au **poste** un **état de mission déclaré par son propriétaire** — `ACTIVE`, `PENDING`,
`CLOSED` — que la gateway range, rend et laisse changer, **sans toucher à rien d'autre** : ni le
runner, ni les jetons, ni les projets, ni le journal.

---

## Comportement attendu

### Cas nominal

1. **Tout poste porte un état**, toujours. Il vaut `ACTIVE` (« en cours ») à la création, et les
   postes déjà en base le reçoivent par la migration. Aucune valeur nulle n'existe : un poste sans
   état serait un poste dont on ne saurait pas dire s'il est en cours ou rangé.
2. **L'état se change par un geste explicite** : `PUT /api/runner-hosts/{hostId}/mission` avec
   `{"missionStatus": "ACTIVE" | "PENDING" | "CLOSED"}`. La réponse est le poste à jour
   (`RunnerHostResponse`), état compris — c'est lui qui fait foi, jamais la valeur demandée.
3. **Changer d'état n'écrit qu'une colonne.** Le service ne révoque aucun jeton, ne coupe aucune
   liaison, ne détache aucun projet, ne ramène aucune cible d'exécution à `SANDBOX`, n'efface aucune
   ligne de journal. **Clôturer une mission n'est pas un coupe-circuit** : couper une machine reste
   `POST /api/runner-hosts/{hostId}/kill`, et lui seul.
4. **L'état sort partout où le poste sort** : `missionStatus` est rendu par `GET /runner-hosts`,
   `GET /runner-hosts/{hostId}`, `PUT /runner-hosts/{hostId}` et
   `GET /runner-hosts/overview`. Un seul champ, un seul nom, une seule énumération.
5. **La vue d'ensemble rend tout, y compris les clôturés.** Le rangement est une affaire d'écran,
   pas de contrat (arbitrage n° 3) : la gateway dit *où en est chaque poste*, l'écran décide de ce
   qu'il met au premier plan.
6. **Isolation** : `hostId` vient du chemin, mais le poste est lu par
   `RunnerHostService.requireOwned(userId, hostId)`. Le poste d'un autre compte est introuvable —
   `404`, jamais `403`, pour ne pas donner d'oracle d'existence.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `missionStatus` absent, nul ou vide | Requête rejetée par la validation, poste inchangé | 400 |
| `missionStatus` hors énumération (`"DONE"`, `"archivé"`, minuscules) | Requête rejetée, poste inchangé, **valeur jamais relayée à l'écran** | 400 |
| `hostId` inconnu | « Poste introuvable » | 404 |
| `hostId` appartenant à un autre utilisateur | **Identique** au cas inconnu — aucun oracle d'existence | 404 |
| Utilisateur sans droit Atelier | Accès refusé par `AtelierAccessService.requireAccess()` | 403 |
| Appel sans JWT | Non authentifié | 401 |
| Même état réappliqué (`CLOSED` → `CLOSED`) | **Idempotent** : 200, poste inchangé, aucun effet de bord | 200 |

---

## Critères d'acceptation

- [ ] La colonne `runner_hosts.mission_status` existe, `NOT NULL`, valeur par défaut `ACTIVE`, et
      **tous les postes existants** la portent après migration.
- [ ] `POST /api/runner-hosts` crée un poste dont `missionStatus` vaut `ACTIVE`.
- [ ] `PUT /api/runner-hosts/{id}/mission` avec `PENDING` → 200 et `missionStatus: "PENDING"` ;
      une relecture (`GET /{id}`) rend la même valeur.
- [ ] `PUT …/mission` avec une valeur hors énumération → 400, et l'état en base est **inchangé**.
- [ ] `PUT …/mission` sur le poste d'un **autre utilisateur** → 404, et l'état de ce poste est
      **inchangé** en base.
- [ ] **Le runner n'est pas coupé** : après un passage à `CLOSED`, les jetons du poste existent
      toujours, aucun n'est révoqué, et la cible d'exécution de ses projets est inchangée.
- [ ] **L'historique n'est pas touché** : après un passage à `CLOSED`, le journal (`runner_audit`)
      du poste compte exactement les mêmes lignes qu'avant, et ses projets lui sont toujours
      rattachés.
- [ ] `GET /api/runner-hosts/overview` rend `missionStatus` pour **chaque** poste, **y compris**
      les `CLOSED`, qui restent présents dans la réponse.
- [ ] Réappliquer le même état est idempotent : 200, aucune erreur, aucun effet de bord.
- [ ] `./mvnw -pl backend test` vert.

---

## Périmètre

### Hors scope (explicite)

- **Dates, jalons, facturation à la mission** — hors périmètre de F-60 par `PRODUCT_SPEC.md`.
- **Tout état automatique** : rien ne clôture, ne met en attente ni ne réactive un poste tout seul.
  Ni l'inactivité, ni la déconnexion du runner, ni la suppression du dernier projet.
- **Un état sur le projet** : `workspaces` n'est pas touchée. La mission est celle du poste.
- **Filtrer la vue d'ensemble côté gateway** : aucun paramètre de requête n'est ajouté. Le
  rangement est un choix d'écran (SF-60-02).
- **Un historique des changements d'état** : on range l'état courant, pas sa chronologie — ce
  serait le jalon que le périmètre exclut.
- **Toute action de machine déclenchée par l'état** : couper, révoquer, détacher restent leurs
  propres endpoints.

---

## Valeurs initiales

| Entité | Champ | Valeur |
|--------|-------|--------|
| Poste créé (`POST /runner-hosts`) | `missionStatus` | `ACTIVE` |
| Poste déjà en base (migration `067`) | `mission_status` | `ACTIVE` |

Le repli est `ACTIVE` et non `PENDING` : un poste qu'on vient de créer, ou qui travaillait déjà
avant F-60, **est** une mission en cours. Le mettre en attente par défaut inventerait un feu rouge
que personne n'a posé.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `missionStatus` (corps de `PUT …/mission`) | **Oui** (`@NotNull`) | — | Énumération stricte : `ACTIVE`, `PENDING`, `CLOSED` — désérialisation Jackson sur l'enum, toute autre valeur est un 400 | Non | Aucune : la valeur est écrite telle quelle ou refusée. Pas de tolérance à la casse — une énumération de contrat n'est pas un texte libre |
| `runner_hosts.mission_status` (colonne) | Oui — `NOT NULL`, défaut `ACTIVE` | `varchar(16)` | `ACTIVE` / `PENDING` / `CLOSED` (`@Enumerated(EnumType.STRING)`) | Non | — |

`varchar(16)` : la plus longue valeur fait 7 caractères ; la marge couvre un quatrième état sans
migration de type. Aucun quatrième état n'est prévu ici.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum | Changement |
|---------|-----|------|-------------|------------|
| PUT | `/api/runner-hosts/{hostId}/mission` | JWT | utilisateur authentifié + droit Atelier | **Nouveau** |
| GET | `/api/runner-hosts` | JWT | idem | **Additif** : `missionStatus` |
| GET | `/api/runner-hosts/{hostId}` | JWT | idem | **Additif** : `missionStatus` |
| PUT | `/api/runner-hosts/{hostId}` (renommage) | JWT | idem | **Additif** : `missionStatus` dans la réponse |
| GET | `/api/runner-hosts/overview` | JWT | idem | **Additif** : `missionStatus` par poste |

Chemin `/{hostId}/mission` — un sous-chemin nommé plutôt qu'un `PUT /{hostId}` élargi : renommer et
changer d'état sont deux gestes différents, et un corps de renommage qui écraserait silencieusement
l'état serait un piège.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_hosts` | `ALTER` (ajout `mission_status`) + `SELECT` / `UPDATE` | Toute lecture et toute écriture filtrent `user_id` (méthodes existantes `list` / `requireOwned`) |

Aucune table créée : `docs/ARCHITECTURE_CANONIQUE.md` n'a pas à changer de liste de tables ; la
colonne y est signalée.

### Migration Liquibase

- [x] **Oui** — `067-runner-hosts-mission-status.xml`
  - `addColumn` `mission_status` `varchar(16)`, `defaultValue="ACTIVE"`, `NOT NULL`.
  - Un seul changeSet, valable Postgres **et** H2 (aucun type propre à un moteur).
  - `rollback` : `dropColumn`.
  - Ne casse aucune donnée : les lignes existantes reçoivent le défaut.

### Composants Angular

Aucun — SF-60-02.

### Backend

- `runner/host/HostMissionStatus.java` — énumération métier : `ACTIVE`, `PENDING`, `CLOSED`, avec
  le défaut déclaré à un seul endroit.
- `runner/host/RunnerHost.java` — champ `missionStatus`, `@Enumerated(EnumType.STRING)`,
  `nullable = false`.
- `runner/host/RunnerHostService.java` — `setMissionStatus(userId, hostId, status)` : `requireOwned`
  puis une écriture. **Rien d'autre.**
- `runner/host/dto/HostMissionRequest.java` — `record HostMissionRequest(@NotNull HostMissionStatus missionStatus)`.
- `runner/host/dto/RunnerHostResponse.java` — composante `missionStatus`.
- `runner/host/dto/RunnerHostOverviewResponse.java` — composante `missionStatus`.
- `runner/host/RunnerHostOverviewService.java` — recopie l'état dans la vue.
- `runner/host/RunnerHostController.java` — `@PutMapping("/{hostId}/mission")`, mappage seul.

---

## Plan de test

### Tests unitaires (backend)

- [ ] `RunnerHostServiceTest` — un poste créé porte `ACTIVE`.
- [ ] `RunnerHostServiceTest` — `setMissionStatus` écrit la valeur demandée et la relit.
- [ ] `RunnerHostServiceTest` — `setMissionStatus` sur un poste d'un autre utilisateur lève
      `RunnerHostNotFoundException` et **n'écrit rien**.
- [ ] `RunnerHostServiceTest` — réappliquer le même état ne lève pas et ne change rien.
- [ ] `RunnerHostOverviewServiceTest` — la vue d'ensemble rend l'état du poste, et **inclut** un
      poste `CLOSED`.

### Tests d'intégration (backend, Spring)

- [ ] `PUT /api/runner-hosts/{id}/mission` `{"missionStatus":"PENDING"}` → 200,
      `$.missionStatus == "PENDING"`.
- [ ] `PUT …` avec `{"missionStatus":"DONE"}` → 400, état en base inchangé.
- [ ] `PUT …` avec corps vide `{}` → 400.
- [ ] `PUT …` sans JWT → 401.
- [ ] `PUT …` sur un `hostId` inconnu → 404.
- [ ] `GET /api/runner-hosts/overview` → `$[*].missionStatus` présent, poste `CLOSED` compris.
- [ ] **Non-régression coupe-circuit** : après `PUT …/mission` à `CLOSED`, le jeton du poste est
      toujours présent et non révoqué, et les lignes de journal du poste sont inchangées.

### Isolation utilisateur

- [x] **Applicable** — `PUT /api/runner-hosts/{id}/mission` sur le poste de Bob avec le JWT
      d'Alice → **404**, et le `mission_status` du poste de Bob relu en base est **inchangé**.
      Même test que l'oracle d'existence : inconnu et non-possédé sont indiscernables.

---

## Dépendances

### Subfeatures bloquantes

- `SF-48-01` — statut : **done** (le poste existe)
- `SF-49-01` — statut : **done** (la vue d'ensemble à enrichir)

### Questions ouvertes impactées

Aucune. `OQ-15` (divergence interne de `DESIGN_SYSTEM.md`) n'est pas touchée : cette SF n'a pas de
frontend.

---

## Notes et décisions

### Arbitrage 1 — Une colonne sur `runner_hosts`, pas une table d'états

**Décision** : un `varchar(16)` sur le poste.
**Pourquoi** : l'état courant est ce que le périmètre demande. Une table `host_mission_events`
donnerait une chronologie — c'est-à-dire des **jalons**, explicitement hors périmètre.
**Alternative écartée** : table d'historique. À rouvrir si un besoin de traçabilité apparaît.
**Réversible** : oui — la migration porte son rollback.

### Arbitrage 2 — `PUT /{hostId}/mission`, pas un champ de plus sur le renommage

**Décision** : un sous-chemin dédié.
**Pourquoi** : `PUT /{hostId}` prend `RunnerHostRequest`, dont le seul champ est le nom. Y ajouter
l'état exposerait au piège classique — un client qui renomme sans envoyer `missionStatus` remettrait
la mission à `ACTIVE` sans le vouloir. Deux gestes, deux chemins, aucune perte silencieuse.
**Réversible** : oui.

### Arbitrage 3 — La gateway ne filtre pas les clôturés

**Décision** : `GET /overview` rend **tous** les postes, y compris `CLOSED`. Aucun paramètre de
requête.
**Pourquoi** : « se range sans disparaître » est une exigence d'**écran**. Un filtre côté gateway
obligerait, pour rendre les clôturés consultables, soit un second appel, soit un paramètre — donc
deux états de vue à synchroniser sur un écran qui se rafraîchit toutes les quinze secondes. Le
volume est celui des postes d'un consultant : quelques unités.
**Alternative écartée** : `?includeClosed=true`. À rouvrir si un compte accumule des dizaines de
postes clôturés.
**Réversible** : oui — champ additif, aucun contrat cassé.

### Arbitrage 4 — Le défaut est `ACTIVE`, pour l'existant comme pour le neuf

**Décision** : `defaultValue="ACTIVE"` en base, `ACTIVE` à la création.
**Pourquoi** : un poste qui travaillait la veille de la migration **est** une mission en cours.
Le mettre en attente inventerait un feu rouge que personne n'a posé, et clôturerait de fait des
missions vivantes en les rangeant hors de la vue principale.
**Réversible** : oui.

### Arbitrage 5 — Aucune casse tolérée sur l'énumération

**Décision** : `"active"` en minuscules est un **400**, pas un `ACTIVE`.
**Pourquoi** : la valeur vient d'un client. Une énumération de contrat se respecte ou se refuse ;
la tolérance à la casse est une dette qui finit par accepter des valeurs qu'on n'a jamais voulues.
`RunnerShell.fromDeclared` normalise, mais il traite une **déclaration de runner**, pas un ordre
d'utilisateur.
**Réversible** : oui.
