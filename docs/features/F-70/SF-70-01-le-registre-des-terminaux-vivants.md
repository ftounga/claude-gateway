# Mini-spec — F-70 / SF-70-01 — Le registre des terminaux vivants

---

## Identifiant

`F-70 / SF-70-01`

## Feature parente

`F-70` — Plusieurs terminaux, et l'on voit lesquels vivent

## Statut

`done` — mergée le 2026-09-12 (PR #378)

## Date de création

2026-09-12

## Branche Git

`feat/SF-70-01-registre-terminaux-vivants`

---

## Objectif

Tenir côté gateway, en base et donc juste sous plusieurs replicas, la liste des **terminaux
réellement vivants** d'un utilisateur — au plus **quatre**, le cinquième étant **refusé
explicitement** — et rendre quatre flux SSE simultanés réellement parallèles.

---

## Comportement attendu

### Cas nominal

**(1) Prendre une place.** Un terminal qui s'ouvre appelle
`POST /api/workspaces/{id}/terminal/live` avec `{"sessionId": "<identifiant d'onglet>"}`. La gateway
vérifie d'abord la propriété du projet (`requireOwned` — 404 sur le projet d'autrui), purge les
fiches expirées de **cet** utilisateur, puis :

- si une fiche existe déjà pour ce `sessionId`, elle est **renouvelée** (`last_seen_at = now`, et son
  `workspace_id` suit si l'onglet a changé de projet) — c'est le **battement de cœur**, le même appel
  sert à prendre et à tenir ;
- sinon une fiche est créée, puis la gateway **recompte** : si le demandeur n'est pas dans les
  `limit` fiches les plus anciennes, sa fiche est retirée et l'appel est **refusé**.

La réponse est toujours l'**état complet** du registre de l'utilisateur :

```json
{ "limit": 4,
  "live": 2,
  "terminals": [
    { "workspaceId": "…", "workspaceName": "mon-projet", "hostId": "…", "hostName": "CAGIP",
      "openedAt": "2026-09-12T09:00:00Z" } ]}
```

**(2) Lire le registre.** `GET /api/terminals/live` rend le même corps, sans rien prendre. C'est ce
que l'écran interroge pour nommer les terminaux à fermer.

**(3) Libérer.** `DELETE /api/workspaces/{id}/terminal/live?sessionId=…` supprime la fiche de cet
onglet et rend `204`. Appelé à la fermeture du terminal. **Idempotent** : libérer une place déjà
libre n'est pas une erreur.

**(4) Expiration.** Une fiche dont `last_seen_at` est plus vieille que `app.terminals.live.ttl`
(défaut `PT90S`, pour un battement de 30 s) **n'est plus vivante** : elle n'est comptée nulle part,
n'apparaît dans aucune réponse, et sa ligne est supprimée à la première prise de place de son
propriétaire. Un onglet fermé brutalement libère donc sa place tout seul, au pire en 90 s.

**(5) Ce que la vue d'ensemble en dit.** `GET /api/runner-hosts/overview` gagne deux champs
**additifs, sans migration** : `liveTerminals` sur le poste (nombre de terminaux vivants sur ses
projets) et `liveTerminal` (booléen) sur chaque projet. Une seule lecture du registre par appel, puis
un `Map` en mémoire — pas une requête par projet.

**(6) Quatre flux qui vivent vraiment.** `chatStreamExecutor` passe en **remise directe** :
`queue-capacity = 0` (donc `SynchronousQueue`), `core-threads = 8`, `max-threads = 32`, threads au
repos recyclés au bout de 60 s. Un flux supplémentaire crée un thread au lieu d'attendre dans une
file. Au-delà de `max`, `RejectedExecutionException` est **attrapée** et le navigateur reçoit
`error: stream_busy` + `complete` — un refus dit, jamais une attente muette.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| 5ᵉ terminal alors que 4 sont vivants | `{"error":"terminal_limit_reached","message":"Quatre terminaux actifs au maximum, fermez-en un pour en ouvrir un autre."}` — aucune fiche n'est laissée en base | 409 |
| `sessionId` absent ou vide | Erreur de validation, message explicite | 400 |
| `sessionId` de plus de 64 caractères | Erreur de validation | 400 |
| Projet d'un autre utilisateur | Traité comme inexistant — jamais « interdit », qui révélerait l'existence | 404 |
| Utilisateur non authentifié | Refus standard | 401 |
| Libération d'une place déjà libre / d'un autre `sessionId` | Aucun effet, pas d'erreur | 204 |
| Pool SSE saturé (au-delà de 32 flux sur le pod) | `error: stream_busy` **dans le flux**, puis `complete` | 200 (flux) |

---

## Critères d'acceptation

1. Quatre prises de place successives avec quatre `sessionId` distincts réussissent ; la cinquième
   rend **409 `terminal_limit_reached`**.
2. Après le 409, `GET /api/terminals/live` rend toujours **exactement 4** terminaux : la tentative
   refusée n'a laissé aucune ligne.
3. Un second appel avec un `sessionId` **déjà pris** ne consomme pas de place et met à jour
   `last_seen_at` (renouvellement).
4. Une fiche dont `last_seen_at` remonte à plus que le `ttl` n'est ni comptée ni rendue, et une
   nouvelle prise de place réussit à sa place.
5. `DELETE` libère la place : une prise refusée juste avant réussit juste après.
6. La prise de place sur le projet d'un **autre utilisateur** rend **404** et ne crée **aucune** ligne.
7. `GET /api/terminals/live` d'un utilisateur ne montre **jamais** un terminal d'un autre, même sur
   le même projet.
8. `GET /api/runner-hosts/overview` rend `liveTerminal: true` sur le projet dont un terminal est
   vivant, et `liveTerminals` = le compte par poste ; `false` / `0` sinon.
9. La suppression de compte supprime les fiches de l'utilisateur (`deleteByUserId` appelé dans la
   purge).
10. Le pool SSE démarre **8 threads de cœur** et une file de capacité **0** : un test vérifie que
    quatre tâches longues soumises coup sur coup tournent **en parallèle** (elles s'attendent sur une
    barrière, qui se franchit — ce qu'un `core=2` rendrait impossible).
11. `limit` est borné à `[1,4]` : une configuration à 9 est ramenée à 4 (garde-fou de dépense).

---

## Plan de test minimal

### Unitaires (`LiveTerminalServiceTest`)

- prise, renouvellement, libération, expiration ;
- 5ᵉ refusée et **aucune ligne résiduelle** ;
- borne du `limit` (0 → 1, 9 → 4) ;
- ordre stable : les plus anciennes places gardées.

### Intégration (`LiveTerminalControllerIT`)

- `POST` / `GET` / `DELETE` avec JWT, codes et corps ;
- 409 avec le code d'erreur exact ;
- 400 sur `sessionId` vide ;
- 401 sans jeton.

### Isolation `user_id` (**obligatoire**)

- `POST` sur le projet d'un autre → **404**, table inchangée ;
- deux utilisateurs, quatre terminaux chacun : chacun voit **ses** quatre, et le plafond de l'un
  n'empêche pas l'autre d'ouvrir ;
- `GET /api/terminals/live` ne fuit aucun `workspaceName` ni `hostName` d'autrui.

### Vue d'ensemble (`RunnerHostOverviewServiceTest`)

- `liveTerminal` / `liveTerminals` corrects, et un terminal vivant **d'un autre utilisateur** sur le
  même projet ne les allume pas.

### Pool SSE (`ChatStreamConfigTest`)

- quatre tâches soumises coup sur coup se rejoignent sur une `CyclicBarrier` en moins de 2 s ;
- la configuration expose bien `queueCapacity = 0`.

---

## Tables / endpoints / composants impactés

### Table (migration **`071-live-terminals.xml`**, PostgreSQL + H2, `rollback` fourni)

`live_terminals` : `id` (uuid, PK), `user_id` (uuid, NOT NULL), `workspace_id` (uuid, NOT NULL),
`session_id` (varchar 64, NOT NULL), `opened_at` (timestamptz, NOT NULL), `last_seen_at`
(timestamptz, NOT NULL).
Index unique `(user_id, session_id)` ; index `(user_id, last_seen_at)`.

### Endpoints

| Méthode | Chemin | Rôle |
|---|---|---|
| `POST` | `/api/workspaces/{id}/terminal/live` | Prendre **ou** renouveler une place |
| `DELETE` | `/api/workspaces/{id}/terminal/live?sessionId=` | Libérer |
| `GET` | `/api/terminals/live` | Lire le registre |

Modifié : `GET /api/runner-hosts/overview` (deux champs additifs).

### Classes

`LiveTerminal`, `LiveTerminalRepository`, `LiveTerminalService`, `LiveTerminalController`,
`LiveTerminalLimitReachedException`, `dto/LiveTerminalsResponse`, `dto/LiveTerminalClaimRequest` ;
modifiées : `GlobalExceptionHandler`, `RunnerHostOverviewService`, `RunnerHostOverviewResponse`,
`AccountService`, `ChatStreamConfig`, `AtelierChatController`, `ChatController`,
`AtelierAgentController`.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| `sessionId` | obligatoire, 1 à 64 caractères, `[A-Za-z0-9_-]` |
| `app.terminals.live.limit` | entier, **borné à [1,4]** — 4 par défaut (décision PO) |
| `app.terminals.live.ttl` | durée, bornée à [30 s, 10 min] — `PT90S` par défaut |

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés / vérification |
|---|---|---|
| Auth / Principal | **Non** | Aucun nouveau type d'auth ; les routes utilisent `CurrentUser.requireId()` comme toutes les autres. |
| Contexte tenant | **Non** | Aucun nouveau moyen de résoudre le tenant : `user_id` vient du jeton, jamais du corps. |
| Plans / limites | **Oui** | Nouveau plafond, **indépendant** des quotas de tokens et du droit d'Atelier. Composants appelant des limites : `UsageService` (quota tokens), `AtelierAccess` (droit Forge), `HostSeatService` (sièges) — **aucun** n'est modifié, le nouveau plafond ne passe par aucun d'eux et ne change aucun gate existant. Vérifié par test : un utilisateur au plafond de terminaux garde ses quotas intacts. |
| Navigation / routing | **Non** (backend) | Aucune route frontend. |

---

## Hors périmètre

- Tout affichage (SF-70-02).
- Un agent qui travaille onglet fermé.
- Redimensionner le pool de diffusion du relais inter-pods.
- Facturer autrement selon le nombre de terminaux : le plafond est un garde-fou, pas une tarification.
