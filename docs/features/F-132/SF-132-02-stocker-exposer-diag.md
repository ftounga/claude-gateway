# Mini-spec — [F-132 / SF-132-02] Stocker + exposer les événements de diagnostic (gateway)

## Identifiant

`F-132 / SF-132-02`

## Feature parente

`F-132` — Observabilité du runner (journal de diagnostic)

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-132-02-stocker-exposer-diag`

---

## Objectif

> En une phrase : recevoir la trame `runner_diag` (SF-132-01), la persister dans un **anneau borné par poste** avec **TTL 7 j + purge planifiée**, et l'exposer via `GET /runner-hosts/{hostId}/diag` (paginé, filtres niveau/temps), **isolé `user_id`+`host_id`**.

---

## Comportement attendu

### Cas nominal

1. **Réception** : le canal WebSocket runner reçoit une trame `type=runner_diag`. `RunnerCallDispatcher.onFrame` publie un `RunnerDiagFrameEvent(userId, hostId, frame)` — l'identité vient **toujours de la session** (`RunnerIdentity`), jamais d'un champ du message. Un service `@EventListener` persiste chaque événement du lot dans `runner_diag_events` (best-effort, non bloquant, comme `runner_audit`/`update_status`).
2. **Anneau borné par poste** : après ingestion, si le nombre de lignes d'un `(user_id, host_id)` dépasse la capacité (2000), les plus anciennes au-delà sont supprimées.
3. **TTL 7 j + purge** : un worker planifié (`@Scheduled`, cron externalisé, désactivable) supprime chaque nuit les lignes de plus de 7 jours.
4. **Exposition** : `GET /runner-hosts/{hostId}/diag` (JWT, chaîne principale) rend les derniers événements du poste **possédé**, du plus récent au plus ancien, avec filtres optionnels : `level` (niveau **minimum**), `since`/`until` (fenêtre temporelle), `limit` (défaut 100, plafond 500).
5. **Lecture par l'assistant** : via la base (table lisible comme `runner_audit`).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `hostId` d'un autre utilisateur, ou inexistant | Accès refusé, indiscernable | 404 |
| Utilisateur sans droit runner (Forge/Vigie) | Accès refusé | 403 |
| `limit` hors bornes | Ramené dans `[1, 500]` (défaut 100) | 200 |
| `level` inconnu | Ignoré (aucun filtre de niveau appliqué) | 200 |
| Trame `runner_diag` mal formée / events absents | Ingestion best-effort : rien n'est persisté, aucune exception ne remonte | — |
| Erreur d'écriture en base | Absorbée (log warn) — ne casse jamais la liaison runner | — |

---

## Critères d'acceptation

- [ ] Une trame `runner_diag` reçue persiste ses événements dans `runner_diag_events` avec `user_id`+`host_id` **issus de la session runner** (jamais du message).
- [ ] `GET /runner-hosts/{hostId}/diag` rend les événements du poste possédé, du plus récent au plus ancien, paginé (`limit` défaut 100, plafond 500).
- [ ] **Isolation** : un utilisateur A ne peut pas lire le journal d'un poste de B → **404** (test).
- [ ] Filtre `level` (niveau minimum) et fenêtre `since`/`until` fonctionnent.
- [ ] **Anneau borné** : au-delà de 2000 lignes par poste, les plus anciennes sont supprimées.
- [ ] **TTL 7 j** : la purge planifiée supprime les lignes de plus de 7 jours (test du service de purge).
- [ ] Ingestion **best-effort** : une trame mal formée ou une erreur d'écriture ne fait jamais échouer la réception (aucune exception propagée).
- [ ] Aucune logique métier dans le controller/l'entité ; DTO de réponse distinct de l'entité.

---

## Périmètre

### Hors scope (explicite)

- L'**émission** côté runner (SF-132-01, livrée).
- Le **panneau UI** (SF-132-03).
- La **commande** de passage en DEBUG (SF-132-05).
- Le snapshot à la demande (SF-132-04, option).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `created_at` | `NOW()` | horodatage serveur (base) |
| `observed_at` | horloge runner (trame) | facultatif ; horodatage d'observation côté poste |
| capacité anneau | 2000 / poste | supprime les plus anciennes au-delà |
| TTL | 7 jours | purge planifiée nocturne |

Comportements à la création :
- `user_id` et `host_id` = ceux de la **session runner** (`RunnerIdentity`).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs |
|-------|-------------|-------------|------------------|
| `level` | Oui | 8 | `DEBUG`/`INFO`/`WARN`/`ERROR` (défaut `INFO` si absent/illisible) |
| `category` | Oui | 32 | texte court (tronqué) |
| `code` | Oui | 64 | texte court (tronqué) |
| `message` | Non | 500 | texte court (tronqué) |
| `fields` | Non | 2000 | JSON compact (tronqué si dépassement) |

Notes :
- L'expurgation est faite **à la source** (SF-132-01) ; la gateway **borne** en plus (longueurs) et ne fait **jamais** confiance au message pour l'identité.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/runner-hosts/{hostId}/diag` | Oui (JWT) | accès runner (Forge **ou** Vigie) + poste possédé |

Trame entrante : `type=runner_diag` sur le canal WebSocket runner existant (SF-132-01).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_diag_events` | CREATE (migration `114`) / INSERT / SELECT / DELETE | anneau borné + TTL |

### Migration Liquibase

- [x] Oui — `114-runner-diag-events.xml` (changeSets postgresql + h2, index `idx_runner_diag_events_user_host_created`). Réversible (`dropTable`).

### Composants Angular (si applicable)

- Aucun (SF-132-03).

---

## Plan de test

### Tests unitaires (service)

- [ ] `RunnerDiagService.ingest` — persiste chaque événement d'un lot (user_id/host_id de la session).
- [ ] `ingest` best-effort — trame mal formée / events absents → rien persisté, pas d'exception.
- [ ] Anneau borné — au-delà de la capacité, les plus anciennes sont supprimées.
- [ ] `purgeExpired` — supprime les lignes plus vieilles que le seuil, garde les récentes.
- [ ] `list` — clamp du `limit`, filtre `level` (minimum) et fenêtre temporelle.

### Tests d'intégration (endpoint + repository + isolation)

- [ ] `GET /api/runner-hosts/{hostId}/diag` → 200 avec les événements du poste possédé (ordre décroissant).
- [ ] `GET …/diag` → **404** pour un poste d'un autre utilisateur (isolation).
- [ ] `GET …/diag?level=WARN` → ne rend que WARN/ERROR.
- [ ] `GET …/diag` sans droit runner → 403.
- [ ] Réception d'une trame `runner_diag` (dispatch) → lignes persistées et relisibles par le propriétaire.

### Isolation utilisateur

- [x] Applicable — lecture filtrée `user_id`+`host_id` ; test cross-user → 404.

---

## Dépendances

### Subfeatures bloquantes

- SF-132-01 (émission) — **Done** (le contrat de trame `runner_diag` est fixé).

### Questions ouvertes impactées

- Aucune (défauts PO confirmés : 7 j, INFO, RDS).

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Composants |
|--------------|-----------|-----------|
| **Auth / Principal** | Non (réutilise l'existant) | `GET …/diag` protégé par `AtelierAccessService.requireRunnerAccess()` + `CurrentUser.requireId()` (même schéma que les autres endpoints `RunnerHostController`) |
| **Contexte tenant** | Oui | Nouveau point d'accès aux données : lecture et écriture filtrées `user_id`+`host_id`. Écriture : `user_id`/`host_id` de `RunnerIdentity` (session), jamais du message. Lecture : `requireOwned(userId, hostId)` (404 sinon). Composants : `RunnerDiagService`, `RunnerDiagEventRepository`, `RunnerHostController#diag`, `RunnerCallDispatcher#onFrame`. |
| Plans / limites | Non | — |
| Navigation / routing | Non (backend) | — |

---

## Notes et décisions

- **Réception par événement applicatif** (`RunnerDiagFrameEvent`), comme `RunnerUpdateFrameEvent` : découple le protocole runner du service de persistance et **n'ajoute aucune dépendance au constructeur** du dispatcher.
- **Anneau** implémenté par suppression des lignes au-delà de la capacité après ingestion (pas de structure d'anneau native) ; TTL par purge planifiée. Les deux bornent le stockage (cadrage §5–6 : négligeable sur la RDS).
- Nouvelle table → mise à jour `docs/ARCHITECTURE_CANONIQUE.md` à l'étape 6.
