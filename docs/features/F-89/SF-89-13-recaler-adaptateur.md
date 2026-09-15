# Mini-spec — F-89 / SF-89-13 — Recaler `TeamsAdapterV1` sur la forme réelle des réponses Teams (CAGIP)

## Identifiant

`F-89 / SF-89-13`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams (Terminée ; cette SF est un correctif d'une feature livrée, comme SF-89-10/11/12).

## Statut

`in-progress`

## Date de création

2026-09-15

## Branche Git

`feat/SF-89-13-recaler-adaptateur`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Recaler `TeamsAdapterV1` (runner) sur la **forme réelle** des réponses Teams relevée en prod (SF-89-12, poste CAGIP) — réunions (`items[].data`), événements de calendrier (objet `objectId`) et objet de collaboration/récapitulatif (`resources[].metadata`) — pour que les outils Teams cessent de revenir **vides** et que la sonde de santé passe de **PARTIAL** à **OK**.

---

## Contexte

Cause racine confirmée (SF-89-12) : l'observation réseau marche, mais l'adaptateur a été écrit d'après une forme **fabriquée**. La vraie forme est dans `docs/features/F-100/releves/releve-teams-forme-2026-09-15-cagip.md` (section « Squelette des réponses classées ») :

- **MEETING_DETAILS** (`/schedulingService/meetings`) : enveloppe `items[]`, champs de la réunion **imbriqués sous `data`** (`subject`, `startTime`, `endTime`, `iCalUid`, `isCancelled`, `numericMeetingId`…).
- **CALENDAR_EVENT** (`/me/calendars/events/{id}` et `.../iCalUId/{id}`) : **objet unique** (pas d'enveloppe tableau) avec `objectId`, `startTime`, `endTime` (chaînes), `subject`, `organizerName`, `skypeTeamsMeetingUrl`, `attendees[]`, `iCalUID`, `cleanGlobalObjectId`.
- **MEETING_COLLAB_OBJECT** (`/collab/readcollabobject/V2/…`) : `resources[].metadata` porte l'**emplacement de l'enregistrement** — `driveId`, `driveItemId`, `threadId`, `callId`, `meetingJoinUrl`, `startTime`, `endTime`.

L'ancien adaptateur attendait `value`/`meetings` avec `id`/`startTime` au niveau item et `start`/`end` objets, et **ne lisait jamais** le corps de l'objet de collaboration.

---

## Comportement attendu

### Cas nominal

1. `meetings(url, body)` reconnaît **trois** formes :
   - `items[]` présent → forme **MEETING_DETAILS** : chaque réunion lue sous `item.data` ; `id = data.iCalUid` (à défaut `item.id`/`data.numericMeetingId`), `subject = data.subject`, `start = data.startTime`, `end = data.endTime`, `location = data.location`, `isCancelled = data.isCancelled`, `conversationId` déduit du join URL si présent.
   - `objectId` présent (objet unique) → forme **CALENDAR_EVENT** : `id = iCalUID` (à défaut `objectId`/`cleanGlobalObjectId`), `subject`, `start = startTime`, `end = endTime`, `organizerId = organizerName`, `joinUrl = skypeTeamsMeetingUrl`, `attendees[]` mappés.
   - `value`/`meetings` (ancienne forme supposée) ou objet unique legacy avec `id` → **conservés** (non-régression : `meetings.json`, corps inline des tests existants).
   - **Dédup** par `iCalUid`/`iCalUID` : une réunion vue en MEETING_DETAILS et en CALENDAR_EVENT ne compte qu'une fois (même clé `id`).
2. `recap(url, body)` (nouvelle méthode d'adaptateur) parse l'objet de collaboration : pour chaque `resources[].metadata`, rend un `TeamsRecap` exposant `conversationId (=threadId)`, `startedAt`, `endedAt`, `callId`, `driveId`, `driveItemId`, `joinUrl (=meetingJoinUrl)`. Rien n'est recopié en aveugle : seuls les champs nommés sont lus (un champ inconnu comme `recap` ne franchit jamais la couche).
3. La sonde de santé (`inspect`) lit la vraie forme : `EXPECTED_MEETING_FIELDS`, `EXPECTED_CALENDAR_EVENT_FIELDS` et un nouvel `EXPECTED_COLLAB_FIELDS` sont calés sur les noms réellement présents → verdict **OK** (recognized == expected) au lieu de PARTIAL.
4. La chaîne d'observation lit désormais le corps de `MEETING_COLLAB_OBJECT` (`NetworkObserver`) et le registre (`TeamsLedger`) l'absorbe ; l'outil `teams_meeting_recording` expose l'emplacement de l'enregistrement (drive/threadId/callId/joinUrl) quand un récapitulatif a été observé pour le fil de la réunion.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Corps `meetings` d'une forme non reconnue (ni `items`, ni `objectId`, ni `value`/`meetings`, ni objet `id`) | Lecture **vide** porteuse d'un manque `UNRECOGNIZED_PAYLOAD` ; aucune exception |
| Réunion sans `subject`, ou sans `startTime`, ou sans `id` | **Non rendue** (`isReadable()` exige id + start + subject) ; un manque `MISSING_FIELD` est nommé |
| Horodatage d'événement au fuseau Windows (non sûr) | Non deviné → réunion non lisible + manque nommé (comportement SF-89-05 conservé) |
| Objet de collaboration sans `resources[].metadata` exploitable (ex. corps `{recap:…}`) | Récapitulatif vide ; aucun champ inconnu ne fuit ; aucune exception |
| Corps `null` / non-objet | Lecture vide porteuse d'un manque ; aucune exception |

---

## Critères d'acceptation

- [ ] Un corps **MEETING_DETAILS** (`items[].data`, valeurs synthétiques) rend **1 réunion lisible** (subject/start/end/id corrects).
- [ ] Un corps **CALENDAR_EVENT** (objet `objectId`) rend **1 réunion lisible** ; **dédup** avec la précédente si même `iCalUid`/`iCalUID` (une seule réunion au registre).
- [ ] Un corps **readcollabobject** rend un récapitulatif dont `driveId`/`driveItemId`/`threadId` sont **lisibles**.
- [ ] Non-régression : un corps de l'**ancienne** forme (`value`/`meetings`) reste lu (best-effort, pas d'exception) — les tests existants `reads_meetings`, RadarVerify/RadarCollector, TeamsGisements restent verts.
- [ ] La **sonde de santé** passe de **PARTIAL** à **OK** sur les vraies formes MEETING_DETAILS, CALENDAR_EVENT et MEETING_COLLAB_OBJECT.
- [ ] Sécurité « rien recopié en aveugle » : un champ inconnu présent dans un corps d'objet de collaboration **ne fuit pas** dans la sortie.
- [ ] `cd runner && ./mvnw -q test` (paquet teams) **vert**.

---

## Périmètre

### Hors scope (explicite)

- **CONVERSATION_LIST / CONVERSATION_MESSAGES** : non recalés ici. Le seul corps CONVERSATION_LIST capté par le relevé était une **erreur 404** (pas la vraie forme d'une liste) et CONVERSATION_MESSAGES n'a pas été capté. On **ne casse pas** le best-effort actuel ; un relevé « forme » ouvrant un fil avec messages est nécessaire (SF ultérieure). Documenté dans la PR.
- **Classification par URL** (`TeamsUrls`) : inchangée (ces chemins sont déjà reconnus).
- **Relevé** (SF-89-12) et **repli visible d'échec** (SF-89-11) : non touchés.
- **Bloc « carte de réunion » / rendu terminal** : inchangé (la carte lit déjà `TeamsMeeting`).
- Aucun champ ajouté au record `TeamsMeeting` (pas de ripple sur la carte) : l'emplacement d'enregistrement passe par le nouveau `TeamsRecap`, surfacé uniquement dans `teams_meeting_recording`.

---

## Préoccupations transversales

Aucune des préoccupations transversales listées (Auth/Principal, Contexte tenant, Plans/limites, Navigation/routing) n'est touchée : la SF est **runner-local** (adaptateur d'observation Teams), sans endpoint, sans base, sans frontend, sans auth. Le tenant n'est pas résolu ici (le runner observe le navigateur du poste). Composants impactés listés ci-dessous.

---

## Technique

### Endpoint(s) / Tables / Migration / Frontend

Aucun. **Pas d'endpoint, pas de table, pas de migration Liquibase, pas de composant Angular.** Changement 100 % runner (`runner/src/main/java/fr/claudegateway/runner/teams`).

### Composants impactés

| Composant | Opération |
|-----------|-----------|
| `TeamsAdapterV1` | `meetings()` (3 formes + dédup), nouvelle `recap()`, `EXPECTED_*` recalés (meeting/calendar + nouveau collab) |
| `TeamsAdapter` (interface) | ajout additif `recap(url, body)` |
| `TeamsMeeting` | `isReadable()` exige id + startTime + **subject** |
| `TeamsRecap` (nouveau record) | emplacement d'enregistrement : conversationId/startedAt/endedAt/callId/driveId/driveItemId/joinUrl |
| `TeamsPayloadKind` | javadoc MEETING_COLLAB_OBJECT (désormais lu) |
| `NetworkObserver` | lit le corps de MEETING_COLLAB_OBJECT (retire le « nommé, jamais lu ») ; garde 401/403 conservée |
| `TeamsLedger` | absorbe MEETING_COLLAB_OBJECT → stocke les récapitulatifs par fil ; `recap(conversationId)` |
| `TeamsTools` / `TeamsViews` | `teams_meeting_recording` expose l'emplacement quand un récapitulatif du fil existe |
| `NetworkSurvey` (doc) | commentaire aligné (forme désormais connue) |

---

## Plan de test

### Tests unitaires (adaptateur — nouvelles fixtures reconstruites depuis le squelette, valeurs synthétiques)

- [ ] `TeamsAdapterV1` — MEETING_DETAILS `items[].data` → 1 réunion lisible (subject/start/end/id).
- [ ] `TeamsAdapterV1` — CALENDAR_EVENT objet `objectId` → 1 réunion lisible.
- [ ] `TeamsAdapterV1` — dédup MEETING_DETAILS + CALENDAR_EVENT de même `iCalUid` → une seule réunion au registre.
- [ ] `TeamsAdapterV1` — recap readcollabobject → `driveId`/`driveItemId`/`threadId`/`callId`/`meetingJoinUrl` lisibles.
- [ ] `TeamsAdapterV1` — non-régression ancienne forme `value` → best-effort/vide propre, pas d'exception.
- [ ] `TeamsAdapterV1` — champ inconnu dans un corps collab ne fuit pas.
- [ ] Santé : `inspect` sur MEETING_DETAILS / CALENDAR_EVENT / MEETING_COLLAB_OBJECT réels → verdict **OK** ; sur forme fabriquée d'antan → PARTIAL/NONE (comportement de refus conservé).

### Tests d'intégration (chaîne d'observation)

- [ ] `NetworkObserver` : le corps de MEETING_COLLAB_OBJECT est désormais **demandé** (mise à jour du test `calendar_event_is_read...` : renommé, l'objet de collaboration est lu mais un champ inconnu ne fuit pas).
- [ ] `TeamsLedger`/`teams_meeting_recording` : un récapitulatif observé pour le fil d'une réunion en expose l'emplacement (drive/thread/call/joinUrl).

### Isolation workspace / tenant

- [ ] Non applicable — le runner observe le navigateur du poste ; aucune donnée multi-tenant côté backend n'est touchée.

---

## Dépendances

### Subfeatures bloquantes

- `SF-89-12` — relevé « forme » — **Done** (fournit la forme réelle).

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`. Le recalage conversations/messages est renvoyé à une SF ultérieure (nécessite un relevé ouvrant un fil).

---

## Notes et décisions

- **Décision par défaut** : ne pas étendre `TeamsMeeting` (éviter le ripple sur la carte de réunion et ses tests) ; l'emplacement d'enregistrement vit dans `TeamsRecap`, surfacé au seul endroit qui « localise l'enregistrement » (`teams_meeting_recording`).
- **Décision par défaut** : lever le « nommé, jamais lu » de l'objet de collaboration — sa forme est désormais connue (SF-89-12). La garantie « rien recopié en aveugle » (lecture par nom de champ) préserve la non-fuite, testée explicitement.
- **Dédup** : la clé stable est `iCalUid`/`iCalUID`, commune à MEETING_DETAILS.data et CALENDAR_EVENT.
