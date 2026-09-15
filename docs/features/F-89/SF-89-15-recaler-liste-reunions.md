# Mini-spec — F-89 / SF-89-15 — Recaler la liste des réunions (calendarView + Graph events) sur la forme réelle CAGIP

## Identifiant

`F-89 / SF-89-15`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams (Terminée ; cette SF est un correctif d'une feature livrée, comme SF-89-10/11/12/13).

## Statut

`in-progress`

## Date de création

2026-09-16

## Branche Git

`feat/SF-89-15-recaler-liste-reunions`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Recaler la **liste** des réunions Teams sur la forme réelle relevée en prod (SF-89-14, relevé catalogue complet CAGIP `docs/features/F-100/releves/releve-teams-catalogue-complet-2026-09-16-cagip.json`) — l'enveloppe `value[]` de `/api/mt/emea/v2.0/me/calendars/default/calendarView` (et, en résilience, `/v1.0/me/events`) — pour que `teams_find_meetings` cesse de revenir **vide** (il interrogeait `calendarView`, resté `UNKNOWN`) et que la sonde de santé valide cette forme.

---

## Contexte

Cause racine confirmée par le débogage du 2026-09-15 puis le relevé complet du 2026-09-16 : SF-89-13 a bien recalé une réunion **ouverte** (`/calendars/events/{id}`, objet unique), mais **la liste** du calendrier — `/api/mt/emea/v2.0/me/calendars/default/calendarView` — reste classée `UNKNOWN`, donc son corps n'est jamais lu, donc `teams_find_meetings` ne voit rien tant qu'aucune réunion n'a été ouverte une à une.

Formes réelles (section `unknownShapes` du relevé) :

- **`/api/mt/emea/v2.0/me/calendars/default/calendarView`** (hôte `teams.microsoft.com`) : enveloppe **`value[]`**, chaque item = **exactement les mêmes champs qu'un CALENDAR_EVENT** (`objectId`, `startTime`, `endTime`, `subject`, `isOnlineMeeting`, `organizerName`, `skypeTeamsMeetingUrl`, `iCalUID`, `cleanGlobalObjectId`, …). Une liste de CALENDAR_EVENT.
- **`/v1.0/me/events`** (hôte `graph.microsoft.com`) : enveloppe **`value[]`**, chaque item = `{ id, iCalUId, subject, bodyPreview, isOnlineMeeting, onlineMeetingUrl, isCancelled, start:{dateTime,timeZone}, end:{...}, … }`. Même famille (liste de réunions), forme Graph — reconnue **en résilience**.

L'adaptateur `meetings()` (SF-89-13) sait déjà lire l'enveloppe `value[]/items[]`, l'objet unique `objectId`, et déduplique par `iCalUid`/`iCalUID`. Il **manque seulement** : (1) la classification de ces deux chemins comme `CALENDAR_EVENT`, (2) la clé `iCalUId` (forme Graph) dans la dédup.

---

## Comportement attendu

### Cas nominal

1. `TeamsUrls.classify` rend **`CALENDAR_EVENT`** pour :
   - `…/calendars/default/calendarView` (hôte de conversation) ;
   - `…/me/events` **exactement** sur l'hôte `graph.microsoft.com` (résilience) — **jamais** `…/me/events/{id}/instances`, dont la forme (id/iCalUId/start/end sans sujet) n'est pas une liste de réunions lisibles.
2. Le registre (`TeamsLedger`) route déjà `CALENDAR_EVENT` → `adapter.meetings()`. L'enveloppe `value[]` de `calendarView` rend **N réunions lisibles** via le mapping CALENDAR_EVENT existant (id = `iCalUID` à défaut `objectId`/`cleanGlobalObjectId` ; `subject` ; `startTime`/`endTime` ; joinUrl = `skypeTeamsMeetingUrl`).
3. **Dédup** par clé stable : une réunion vue en liste (`calendarView`) et une même réunion vue ouverte (CALENDAR_EVENT) ou par Graph (`iCalUId`) ne comptent qu'une fois. `iCalUId` est ajouté aux candidats d'identifiant pour que la forme Graph déduplique avec les formes Teams.
4. `teams_find_meetings` rend donc les réunions depuis `calendarView` (source réseau observée), sans qu'aucune réunion ait à être ouverte une à une.
5. La sonde de santé (`inspect`) valide `calendarView` : `EXPECTED_CALENDAR_EVENT_FIELDS` (objectId/subject/startTime/endTime) sont présents dans les items `value[]` → verdict **FULL**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Corps `calendarView` sans `value[]` exploitable | Lecture **vide** porteuse d'un manque `UNRECOGNIZED_PAYLOAD` ; aucune exception (comportement `meetings()` conservé) |
| Item de liste sans `subject`, sans `startTime` ou sans id | **Non rendu** (`isReadable()` exige id + start + subject) ; un manque `MISSING_FIELD` est nommé |
| Forme Graph `/me/events` : horodatage `start` en fuseau Windows | Non deviné → réunion non lisible + manque nommé (SF-89-05 conservé) |
| `…/me/events/{id}/instances` (Graph) | Reste `UNKNOWN` (pas classé) : le corps n'est pas lu |
| Corps `null` / non-objet | Lecture vide porteuse d'un manque ; aucune exception |

### Conversations / messages — hors recalage (décision documentée)

Le relevé complet du 2026-09-16 ne porte **aucune** forme réelle de **liste de messages** (`/conversations/{id}/messages`) : la seule entrée classée `CONVERSATION_LIST` est `/conversations/{id}/messages/{id}/properties`, dont le squelette est un simple **`number`** (une réponse de propriétés, pas une liste). Le vrai fil de messages n'a pas été capté (cache Chrome). **On ne l'invente pas** : le best-effort actuel des conversations/messages est **conservé tel quel**, et ce manque est **dit** dans la PR. Un futur relevé « forme » ouvrant un fil non caché le complètera.

---

## Critères d'acceptation

- [ ] `TeamsUrls.classify(".../calendars/default/calendarView")` == `CALENDAR_EVENT`.
- [ ] `TeamsUrls.classify("https://graph.microsoft.com/v1.0/me/events")` == `CALENDAR_EVENT`.
- [ ] `TeamsUrls.classify("https://graph.microsoft.com/v1.0/me/events/{id}/instances")` == `UNKNOWN`.
- [ ] Un corps `calendarView` (`value[]`, valeurs synthétiques reconstruites du squelette) rend **N réunions lisibles** (subject/start/end/id corrects).
- [ ] Un corps Graph `/me/events` (`value[]`) rend une réunion lisible (id via `id`/`iCalUId`, start via l'objet `start`).
- [ ] **Dédup** : une réunion de `calendarView` et un CALENDAR_EVENT de même `iCalUID` (ou une réunion Graph de même `iCalUId`) → une seule au registre.
- [ ] La sonde de santé passe à **FULL** (et non PARTIAL) sur la vraie forme `calendarView`.
- [ ] Non-régression : formes SF-89-13 (MEETING_DETAILS, CALENDAR_EVENT objet, MEETING_COLLAB_OBJECT) et ancienne forme `value`/`meetings` toujours lues ; garde « rien recopié en aveugle » conservée (aucun champ inconnu ne franchit la couche).
- [ ] `cd runner && ./mvnw -q test` (paquet teams) **vert**.

---

## Périmètre

### Hors scope (explicite)

- **CONVERSATION_LIST / CONVERSATION_MESSAGES** : non recalés (aucune forme réelle de liste de messages dans ce relevé — voir ci-dessus). Best-effort actuel conservé.
- **Fichiers / transcription SharePoint** : traité par **SF-108-07** (livrée conjointement, branche séparée).
- **Relevé** (SF-89-12/14) et **repli visible d'échec** (SF-89-11) : non touchés.
- **Bloc « carte de réunion » / rendu terminal** : inchangé (la carte lit déjà `TeamsMeeting`).
- Aucun champ ajouté au record `TeamsMeeting`.

---

## Préoccupations transversales

Aucune préoccupation transversale (Auth/Principal, Contexte tenant, Plans/limites, Navigation/routing) n'est touchée : SF **runner-local** (adaptateur d'observation Teams), sans endpoint, sans base, sans frontend, sans auth. Le tenant n'est pas résolu ici (le runner observe le navigateur du poste). Composants impactés listés ci-dessous.

---

## Technique

### Endpoint(s) / Tables / Migration / Frontend

Aucun. **Pas d'endpoint, pas de table, pas de migration Liquibase, pas de composant Angular.** Changement 100 % runner (`runner/src/main/java/fr/claudegateway/runner/teams`).

### Composants impactés

| Composant | Opération |
|-----------|-----------|
| `TeamsUrls` | classer `calendarView` (règle de chemin) et le Graph `/me/events` (hôte graph + fin exacte) en `CALENDAR_EVENT` ; `/me/events/{id}/instances` reste `UNKNOWN` |
| `TeamsAdapterV1.readMeeting` | ajouter `iCalUId` aux candidats d'identifiant (dédup forme Graph ↔ formes Teams) |
| Fixtures de test | `calendar-view-cagip.json` (liste `value[]`, 2 réunions), `graph-events-cagip.json` (liste Graph `value[]`, 1 réunion) |
| `TeamsUrlsTest` | lignes de classification calendarView + Graph events + instances |
| `TeamsAdapterV1Test` | liste calendarView → N réunions ; Graph events → 1 réunion ; dédup ; santé FULL sur calendarView |

---

## Plan de test

### Tests unitaires (adaptateur — fixtures reconstruites du squelette, valeurs synthétiques)

- [ ] `TeamsUrls` — `calendarView` → `CALENDAR_EVENT` ; Graph `/me/events` → `CALENDAR_EVENT` ; Graph `/me/events/{id}/instances` → `UNKNOWN`.
- [ ] `TeamsAdapterV1` — corps `calendarView` `value[]` → 2 réunions lisibles.
- [ ] `TeamsAdapterV1` — corps Graph `/me/events` → 1 réunion lisible (start via objet `start`).
- [ ] `TeamsAdapterV1` — dédup `calendarView` + CALENDAR_EVENT de même `iCalUID` → une seule réunion au registre.
- [ ] Santé : `inspect` sur `calendarView` réel → **FULL**.
- [ ] Non-régression : formes SF-89-13 et ancienne forme `value` restent vertes ; un champ inconnu ne fuit pas.

### Tests d'intégration (chaîne d'observation)

- [ ] `TeamsLedger.absorb` d'un `calendarView` (kind `CALENDAR_EVENT`) → les réunions entrent au registre ; dédup avec un CALENDAR_EVENT de même `iCalUID`.

### Isolation workspace / tenant

- [ ] Non applicable — le runner observe le navigateur du poste ; aucune donnée multi-tenant côté backend n'est touchée.

---

## Dépendances

### Subfeatures bloquantes

- `SF-89-14` — relevé « forme » des chemins UNKNOWN — **Done** (fournit la forme réelle de `calendarView` et de Graph `/me/events`).
- `SF-89-13` — recalage de l'adaptateur (objet unique + enveloppe + dédup) — **Done** (base réutilisée).

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`. Le recalage conversations/messages est renvoyé à une SF ultérieure (nécessite un relevé ouvrant un fil non caché).

---

## Notes et décisions

- **Décision par défaut** : router `calendarView` et Graph `/me/events` vers le genre `CALENDAR_EVENT` existant (plutôt qu'un nouveau genre) — l'adaptateur `meetings()` gère déjà l'enveloppe `value[]` et l'objet unique, et le registre route déjà `CALENDAR_EVENT → meetings()`. Le plus simple, sans ripple.
- **Décision par défaut** : reconnaître le Graph `/me/events` par **fin de chemin exacte** (`endsWith("/me/events")`) sur l'hôte `graph.microsoft.com`, pour ne PAS avaler `…/me/events/{id}/instances` (forme sans sujet, non lisible comme réunion).
- **Décision par défaut** : la forme Graph reste une **résilience**. `EXPECTED_CALENDAR_EVENT_FIELDS` reste calé sur la forme Teams native (objectId/subject/startTime/endTime), donc `calendarView` valide **FULL** ; un `/me/events` Graph seul donnerait **PARTIAL** (« on lit, et on le dit ») — jamais un faux OK. Documenté.
- **Dédup** : la clé stable est `iCalUid`/`iCalUID`/`iCalUId`, commune aux trois formes.
