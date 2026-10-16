# Mini-spec — F-205 / SF-205-01 — Registre des besoins : backend

## Identifiant
`F-205 / SF-205-01` — feature parente `F-205` *Registre des besoins* (cadrage
`CADRAGE-F-205-registre-des-besoins.md`). Branche : `feat/SF-205-01-registre-besoins-backend`.
Statut : `in-progress`. Date : 2026-10-16. Migration pré-assignée : **163** (`162` pris par une autre
branche en cours).

## Objectif
Quand l'utilisateur décrit un défaut ou un manque **de l'application** dans un terminal, l'agent pose une
carte « Enregistrer ce besoin ? » ; au clic, le besoin et ses pièces (copiées vers S3) entrent au registre,
que l'administrateur — et Claude Code par la route admin — relit et fait avancer.

## Comportement attendu

### Cas nominal
1. **Outil `signaler_besoin`** (outil serveur, patron F-177 `gouvernance_proposer`), offert quand
   l'utilisateur est **administrateur** (`users.role = ADMIN`, `AdministratorEntitlement`) et que le
   terminal **n'est pas** un terminal Teams. Entrée :
   `type` (`BUG`|`EVOLUTION`), `titre`, `description` (les mots de l'utilisateur, tels quels),
   `reformulation` (celle de l'agent), `contexte` (écran, étapes, ce qui a été observé), `pieces`
   (chemins de fichiers **déposés** dans ce terminal : `entrees/…` ou `.atelier/entrees/…`).
   L'outil **n'enregistre rien d'actif** : il range la ligne au statut `PROPOSE`, pose la carte
   (événement SSE `besoin`, bloc `besoin` de la transcription) et rend à l'agent « Carte posée… ».
2. **Doctrine stable** (littéral, injecté aux mêmes conditions que l'outil — cache F-134 préservé) :
   « l'utilisateur décrit un défaut ou un manque de l'application elle-même (pas de son projet) ⇒ propose
   de l'enregistrer par `signaler_besoin` ; reprends ses mots tels quels ; joins les fichiers déposés ».
3. **[Enregistrer]** `POST /api/workspaces/{wid}/app-requests/{id}/record` → statut `EN_ATTENTE`,
   `recorded_at` posé ; chaque pièce passe `copy_status = PENDING` puis est **copiée en arrière-plan**
   (pool dédié, après validation de la transaction) vers le stockage de la gateway, clé
   `besoins/{id}/{n}-{nom}` → `COPIED` (+ `storage_key`) ou `FAILED` (+ `copy_error`). Lecture : poste
   par tranches (`ProjectFileRead`), hébergé par `WorkspaceService.readFileBytes` ; borne
   `app.atelier.deposit.max-runner-bytes` (100 Mo).
4. **[Non]** `POST …/{id}/dismiss` → statut `REJETE`, rien n'est copié.
5. **Relire la carte** `GET …/{id}` → vue (statut surtout).
6. **Administration** (garde `AdminService.assertAdmin()` **et** filtre `user_id` de l'appelant) :
   - `GET /api/admin/app-requests?status=EN_ATTENTE` → liste (plus récents d'abord), pièces incluses
     (métadonnées) ; sans `status` : tout sauf `PROPOSE`.
   - `GET /api/admin/app-requests/{id}` → détail.
   - `GET /api/admin/app-requests/{id}/attachments/{n}` → octets (Content-Type stocké ; `inline` pour
     image/vidéo/PDF/texte, `attachment` sinon ; `X-Content-Type-Options: nosniff`).
   - `PATCH /api/admin/app-requests/{id}` body `{ "status": "CADRE", "featureRef": "F-210" }`
     (champs optionnels) → vue.

### Contrat API (figé — consommé par SF-205-02)
```json
// Bloc de carte (SSE event "besoin" : { toolUseId, besoin } ; transcription : block.besoin)
{ "requestId": "uuid", "kind": "BUG|EVOLUTION", "title": "…", "description": "…",
  "reformulation": "…|null", "observedContext": "…|null",
  "attachments": [ { "position": 1, "name": "capture.png", "contentType": "image/png", "size": 1234 } ] }

// AppRequestView (terminal + admin)
{ "id": "uuid", "workspaceId": "uuid", "messageId": null, "kind": "BUG", "title": "…",
  "description": "…", "reformulation": "…", "observedContext": "…",
  "status": "PROPOSE|EN_ATTENTE|CADRE|REJETE|LIVRE", "featureRef": "F-210|null",
  "createdAt": "…", "updatedAt": "…", "recordedAt": "…|null",
  "attachments": [ { "position": 1, "name": "…", "contentType": "…", "size": 1234,
                     "copyStatus": "PENDING|COPIED|FAILED|null", "copyError": "…|null" } ] }
```
Erreurs : `400 app_request_invalid`, `404 app_request_not_found`, `409 app_request_conflict`,
`403` (non-admin sur `/admin/**`).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Non-admin ou terminal Teams | outil non offert ; appel forcé → erreur outil « pas ouvert ici » |
| `type` hors BUG/EVOLUTION, titre ou description vides/trop longs | erreur outil (message à l'agent) |
| Pièce qui n'est pas un dépôt de ce terminal (autre chemin, autre compte) | erreur outil nommant le chemin |
| [Enregistrer]/[Non] sur un besoin déjà décidé | 409 |
| Besoin/terminal d'un autre compte | 404 |
| Poste hors ligne / fichier illisible / trop lourd à la copie | besoin **enregistré quand même**, pièce `FAILED` + motif |
| `PATCH` statut inconnu ou `featureRef` mal formé | 400 |
| Non-admin sur `/admin/app-requests` | 403 |

## Contraintes de validation
| Élément | Règle |
|---|---|
| `type` | `BUG` \| `EVOLUTION` |
| `titre` | obligatoire, ≤ 200 caractères |
| `description` | obligatoire, ≤ 8 000 caractères |
| `reformulation`, `contexte` | optionnels, ≤ 8 000 (tronqués au-delà) |
| `pieces` | ≤ 10, chemins de dépôts du couple (user, terminal) ; doublons ignorés |
| Taille d'une pièce | ≤ `app.atelier.deposit.max-runner-bytes` (100 Mo) |
| `featureRef` | `^F-\d{1,4}$` ou vide (efface) |
| Statuts admin | `EN_ATTENTE`, `CADRE`, `REJETE`, `LIVRE` (`PROPOSE` réservé à la carte) |

## Critères d'acceptation
- [ ] Un admin décrit un bug de l'app : la carte apparaît, rien n'est « en attente » avant le clic.
- [ ] [Enregistrer] passe le besoin `EN_ATTENTE` et copie les pièces sous `besoins/{id}/`.
- [ ] Un poste hors ligne n'empêche pas l'enregistrement : la pièce est `FAILED` avec son motif.
- [ ] `GET /api/admin/app-requests?status=EN_ATTENTE` rend le besoin avec ses pièces ; la pièce se
      télécharge ; `PATCH` pose `CADRE` + `F-210`.
- [ ] Un non-admin : outil absent de la liste, 403 sur les routes admin.
- [ ] Bob ne relit ni ne décide les besoins d'Alice (404).
- [ ] La consigne système reste identique d'un tour à l'autre (littéral stable).

## Plan de test
- **Unitaires** : `AppRequestServiceTest` (validation, pièces dépôt/hors dépôt, proposition, record →
  EN_ATTENTE + planification de copie, dismiss, 409, isolation), `AppRequestAttachmentCopierTest` (copie
  hébergée, poste, échec → FAILED, clé `besoins/{id}/`), `AppRequestAdminTest` (filtre statut, patch,
  featureRef invalide), `AtelierChatServiceAppRequestTest` (outil + doctrine offerts à l'admin seulement,
  hors Teams ; appel → carte + bloc de transcription).
- **Intégration** : `AppRequestApiIntegrationTest` (SpringBoot + H2) — admin : record, liste, détail,
  téléchargement, patch ; non-admin 403 ; Bob 404.
- **Isolation utilisateur** : chaque lecture par `id + user_id` (+ `workspace_id` côté terminal).

## Impacts
- **Tables** : `app_requests`, `app_request_attachments` (migration `163-app-requests.xml`).
- **Endpoints** : `GET|POST /workspaces/{wid}/app-requests/{id}[/record|/dismiss]`,
  `GET|PATCH /admin/app-requests[/{id}[/attachments/{n}]]`.
- **Composants** : paquet `fr.claudegateway.atelier.besoin` (entités, dépôts, service, copieur, contrôleurs,
  gestionnaire d'erreurs, pool), `AtelierChatService` (outil, doctrine, routage, bloc), `AtelierTurnReport.Block`
  (+ `besoin`), `AtelierProgressListener`/`TurnSignals`/`AtelierChatController` (événement `besoin`),
  `AccountService` (purge).

### Préoccupations transversales
- **Auth / Principal** : non (garde admin existante réutilisée telle quelle).
- **Contexte tenant** : oui — `AppRequestService` (lecture `id + user_id + workspace_id` côté terminal,
  `id + user_id` côté admin), `AppRequestAttachmentCopier` (terminal relu par `requireOwned(userId, wid)`),
  validation des pièces (dépôts filtrés `user_id + workspace_id`), purge du compte.
- **Plans / limites** : non. **Navigation** : non (SF-205-02).

## Hors périmètre
Ouverture aux non-admins ; relance automatique d'une copie échouée ; cadrage/livraison automatiques ;
notification ; écran (SF-205-02) ; doc ops (SF-205-03).

## Arbitrages (réversibles)
- **Outil réservé à l'admin** (le cadrage réserve le registre à l'ADMIN) : sinon des besoins d'autres
  comptes seraient rangés sans lecteur ; condition stable par utilisateur (cache préservé).
- **Admin = ses propres besoins** (`user_id` de l'appelant), comme le journal des ruptures (F-161).
- **`reformulation` en colonne séparée** de `description` (mots de l'utilisateur) : Claude Code distingue
  la parole du PO de l'interprétation de l'agent.
- **Copie asynchrone** (pool dédié) : une vidéo de 100 Mo par tranches de 360 Kio ne bloque pas le clic ;
  échec ⇒ pièce `FAILED`, le besoin reste enregistré.
- **Pièces limitées aux dépôts** du terminal : l'outil ne sert pas à exfiltrer un fichier quelconque.
- `workspace_id` sans clé étrangère : un besoin de l'application survit à la suppression du terminal.
