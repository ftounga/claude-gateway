# Mini-spec — [F-111 / SF-111-04] La commande de mise à jour

## Identifiant

`F-111 / SF-111-04`

## Feature parente

`F-111` — Le runner se met à jour d'un clic (cadrage : `CADRAGE-F-111-le-runner-se-met-a-jour.md`)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-111-04-commande-update`

---

## Objectif

Un clic sur « Mettre à jour » (propriétaire du poste ou ADMIN) envoie au runner une commande `update`
par WebSocket ou long-polling ; le runner télécharge et vérifie la version (SF-111-03), attend le calme
(aucune commande, capture, synchro, téléchargement) ou « Forcer », écrit `next-version` et sort en 75 ;
la gateway suit et journalise chaque étape, et la Forge/Vigie affichent « mise à jour en cours » au lieu
de « hors ligne » pendant la bascule.

---

## Comportement attendu

### Cas nominal

1. **Écran** (composant partagé Forge/Vigie de SF-111-01) : quand `runnerUpdate.oneClick` est vrai et le
   poste en ligne, bouton **« Mettre à jour »** à côté de « Mise à jour disponible — 1.0.0 → 1.1.0 ».
2. **`POST /api/runner-hosts/{hostId}/runner-update`** `{ "force": false }` (JWT) :
   - poste lu **sans filtre** puis autorisé : propriétaire (`user_id`) **ou rôle ADMIN** ; sinon `404`
     (indiscernable d'un poste inconnu, règle F-48) ;
   - conseil recalculé : `status = AVAILABLE`, version signée servie (`updatable`), contrat ≥ 2 ; sinon
     `409 runner_update_not_possible` avec le motif ;
   - aucune mise à jour **active** sur ce poste (sinon `409 runner_update_in_progress`, sauf `force`) ;
   - ligne de journal `runner_update_journal` créée : qui (`requested_by`), quand, de → vers, `REQUESTED` ;
   - trame `{"type":"update","updateId","version","sha256","force"}` **remise** au runner : canal local
     (`RunnerCallDispatcher.sendControl`, WebSocket ou file de long-polling), sinon **diffusée** aux pods
     pairs (`POST /internal/runner/control`) ; non remise → ligne `FAILED` (« runner injoignable ») et
     `409 runner_unavailable` ;
   - réponse `202` : l'état de la mise à jour.
3. **Runner** (`update.RunnerUpdater`, un par processus, branché sur les deux transports) :
   - refuse si pas de lanceur (`no_launcher`), version pas plus récente (`not_newer`) ;
   - `update_status: downloading` → `UpdateInstaller.install` (client HTTP du runner, vérification) ;
   - `update_status: waiting` + `busy` (`commande`, `capture`, `synchro`, `téléchargement`) répété toutes
     les 30 s tant que le poste n'est pas calme ; un second `update` avec `force: true` lève l'attente ;
   - `next-version` écrit, `update_status: restarting`, puis **sortie 75** (crochet d'arrêt habituel ;
     « Forcer » interrompt donc les commandes en vol, comme `Ctrl+C`).
4. **Gateway, trames entrantes** : `update_status` → la ligne active prend l'état (`DOWNLOADING`,
   `WAITING` + détail, `RESTARTING`) ou se termine `FAILED` (motif du runner) ; `ready` d'un runner dont
   la version déclarée = version visée → `SUCCEEDED` ; `ready` d'une autre version pendant une mise à
   jour active → `FAILED` (« le runner s'est reconnecté en 1.0.0 »). L'identité vient **de la session**.
5. **Vue d'ensemble** : `runnerUpdate.progress` = dernière ligne du poste (`state`, `fromVersion`,
   `toVersion`, `detail`, `forced`, `requestedAt`, `updatedAt`, `finishedAt`, `active`) ; `active` =
   état non terminal mis à jour depuis moins de 10 minutes.
6. **Écran pendant la mise à jour** : « Téléchargement et vérification de 1.1.0… » ; « En attente de la
   fin : commande en cours » + **Forcer** (confirmation `MatDialog` : les commandes en cours sont
   interrompues) ; pendant `RESTARTING` et hors ligne, la pastille de présence (colonne et en-tête) dit
   **« Mise à jour en cours »** et non « Hors ligne » ; résultat : « Mise à jour vers 1.1.0 réussie » ou
   « Mise à jour vers 1.1.0 échouée : motif » (24 h).
7. **Journal** : `GET /api/runner-hosts/{hostId}/runner-update/journal` (propriétaire ou ADMIN) — les
   20 dernières lignes.
8. **Contrat** : `RunnerBuild.CONTRACT` passe à `2` (le runner comprend `update`).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste inconnu, ou d'un autre utilisateur non ADMIN | `not_found` | 404 |
| Droit runner absent (ni Forge ni Vigie) et non ADMIN | `forbidden` existant | 403 |
| Runner à jour / sans lanceur / Java insuffisant / version non signée / contrat < 2 | `runner_update_not_possible` + motif | 409 |
| Mise à jour déjà active (sans `force`) | `runner_update_in_progress` | 409 |
| `force` sans mise à jour active | traité comme une demande normale | 202 |
| Runner hors ligne ou trame non remise | ligne `FAILED`, `runner_unavailable` | 409 |
| Signature / empreinte / téléchargement refusés au runner | ligne `FAILED` + motif, rien d'installé, l'écran le dit | — |
| Runner sans lanceur recevant `update` (gateway ancienne) | `update_status failed no_launcher` | — |
| `update_status` d'un `updateId` inconnu ou d'un autre poste | ignoré | — |

---

## Critères d'acceptation

- [ ] CA1 — Le propriétaire déclenche ; l'ADMIN déclenche sur le poste d'autrui ; un autre utilisateur reçoit 404 (tests d'intégration).
- [ ] CA2 — La trame `update` est remise en WebSocket **et** en long-polling (tests dispatcher/canaux), et diffusée aux pods pairs sinon.
- [ ] CA3 — Préconditions : 409 motivés (à jour, sans lanceur, non signé, contrat, en cours).
- [ ] CA4 — Journal : qui, quand, de → vers, états, résultat (`SUCCEEDED` au `ready` de la version visée, `FAILED` sinon) ; lecture réservée propriétaire/ADMIN.
- [ ] CA5 — Runner : téléchargement vérifié avant écriture, attente du calme avec `busy`, « Forcer », `next-version` puis sortie 75 ; refus `no_launcher`, `not_newer`, et motif de `UpdateInstaller` remonté (tests unitaires avec sortie injectée).
- [ ] CA6 — Écran : bouton, états, Forcer avec confirmation, « Mise à jour en cours » au lieu de « Hors ligne » pendant la bascule, résultat ; aucune couleur nouvelle.
- [ ] CA7 — Isolation : `update_status`/`ready` n'écrivent que sur le poste de la session ; la vue ne montre que les postes de l'utilisateur.

---

## Périmètre

### Hors scope (explicite)

- Contrôle de santé en 90 s, retour arrière, rapport de retour, rétention (SF-111-05) — ici un `ready` d'une
  autre version marque simplement `FAILED`.
- Mise à jour automatique sans clic ; mise à jour de plusieurs postes d'un coup.
- Écran d'administration dédié (l'ADMIN agit par l'API).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `state` | `REQUESTED` | à la création |
| `forced` | `false` | `true` si la demande (ou une relance) porte `force` |
| `requested_by` | l'utilisateur courant | propriétaire ou ADMIN |
| `user_id` | le propriétaire du poste | isolation, jamais l'ADMIN |
| `requested_at`, `updated_at` | maintenant | `updated_at` à chaque état |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `force` (requête) | Non | — | booléen, défaut `false` | — | — |
| `from_version` / `to_version` | to : Oui | 64 | identifiant de version | — | — |
| `state` | Oui | 16 | `REQUESTED, DOWNLOADING, WAITING, RESTARTING, SUCCEEDED, FAILED, ROLLED_BACK` | — | — |
| `detail` | Non | 500 | texte venu du runner | — | tronqué à 500 |
| `update_status.state` (trame) | Oui | — | `downloading, waiting, restarting, failed` | — | inconnu ignoré |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/runner-hosts/{hostId}/runner-update` | JWT | propriétaire (droit runner) ou ADMIN |
| GET | `/api/runner-hosts/{hostId}/runner-update/journal` | JWT | propriétaire (droit runner) ou ADMIN |
| POST | `/api/internal/runner/control` | secret du relais | pod pair |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_update_journal` | CREATE, INSERT, UPDATE, SELECT | FK `host_id` → `runner_hosts` (cascade) |

### Migration Liquibase

- [x] Oui — `104-runner-update-journal.xml` (premier numéro libre au-dessus de 103, pris par F-110 pendant la livraison)

### Composants

- Backend : `RunnerUpdateService`, `RunnerUpdateJournalEntry` + repository, `RunnerUpdateCommandController` (dans `RunnerHostController`), `RunnerCallDispatcher` (`sendControl`, `update_status`, écouteur), `RunnerRelayBroadcaster`/`RunnerRelayController` (`/control`), `RunnerHostOverviewService` (progress), `RunnerUpdateAdvisor` (`oneClick`), `GlobalExceptionHandler`.
- Runner : `update.RunnerUpdater`, `RunnerActivity` (calme), `FrameRouter` (`update`), `RunnerConnection`/`PollingConnection` (branchement), `ToolDispatcher` (appels en vol), `LocalCapture`, `RadarSyncAgent`, `LocalToolchain`, `RunnerBuild.CONTRACT = 2`.
- Frontend : `RunnerUpdateNoticeComponent` (bouton, état, Forcer), dialogue de confirmation, `RunnerUpdateService` (API), libellé de présence (colonne, en-tête Forge et Vigie).

### Préoccupations transversales

- **Auth / Principal : oui** — nouveau geste autorisé « propriétaire OU ADMIN ». Composants impactés : `RunnerHostController` (nouvelles routes, garde `requireRunnerAccess` sauf ADMIN), `CurrentUser` (lecture du rôle), `RunnerHostService.requireOwned` (non utilisé pour l'ADMIN, inchangé pour le reste). Non-régression : routes existantes du contrôleur inchangées ; test « autre utilisateur → 404 ».
- **Contexte tenant : oui** — la ligne de journal porte `user_id` du **propriétaire** (jamais de l'ADMIN) ; `update_status`/`ready` résolvent le poste depuis la session runner (`RunnerIdentity`), jamais depuis la trame. Composants : `RunnerCallDispatcher.onFrame`, `RunnerUpdateService`.
- Plans / limites : non. Navigation : non (aucune route d'écran).

---

## Plan de test

### Tests unitaires

- [ ] backend `RunnerUpdateService` — préconditions et motifs, création, force, remise/non-remise, statuts, `ready` succès/échec, poste d'une autre session ignoré : **exercé de bout en bout** par `RunnerUpdateCommandApiIntegrationTest` (vraie base, vrai dispatcher).
- [ ] backend `RunnerCallDispatcherTest` — `sendControl` sur canal ouvert/fermé ; `update_status` confié à l'écouteur avec l'identité de session.
- [ ] runner `RunnerUpdaterTest` — refus sans lanceur / pas plus récent ; motif de refus remonté ; attente du calme puis 75 ; Forcer ; statuts émis.
- [ ] runner `RunnerActivityTest` — compteurs et libellés.
- [ ] frontend — bouton, confirmation Forcer, états, présence « Mise à jour en cours ».

### Tests d'intégration

- [ ] backend `RunnerUpdateCommandApiIntegrationTest` — propriétaire 202 + trame remise sur le canal long-polling ; ADMIN 202 sur le poste d'autrui ; autre utilisateur 404 ; 409 motivés ; journal ; migration 104.

### Isolation workspace

- [x] Applicable — autre utilisateur → 404 sur la commande et le journal ; `update_status` d'une autre session sans effet.

---

## Dépendances

### Subfeatures bloquantes

- SF-111-01, SF-111-02, SF-111-03 — done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Remise diffusée** plutôt que dirigée : le registre dit quel pod tient la socket, mais la diffusion
  (déjà utilisée pour annuler et autoriser) couvre aussi le long-polling sans cas particulier.
- **D2 — Sortie par `System.exit(75)`** après émission de `restarting` : le crochet d'arrêt existant ferme
  proprement la liaison ; attendre la fin d'un long-poll retarderait la bascule de 45 s.
- **D3 — « Forcer » confirmé** (`MatDialog`) : il interrompt les commandes en vol, c'est un geste destructif.
- **D4 — Journal = état** : une seule table porte l'historique et l'état courant (dernière ligne active).
