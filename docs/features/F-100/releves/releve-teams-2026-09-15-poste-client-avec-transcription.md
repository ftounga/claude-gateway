# Relevé réel — Teams web (F-100 / SF-100-00)

- Début : 2026-09-14T22:42:22.522Z
- Fin : 2026-09-14T22:45:50.920Z
- Navigateur : Chrome/151.0.7922.138
- Adaptateur : v1
- Chemins distincts : 59
- Réponses hors famille d'hôtes Microsoft (non détaillées) : 1967
- Sockets WebSocket (famille Microsoft) : 3 chemin(s), 65 trame(s) reçue(s)
- Ressources statiques écartées : 117
- Cibles attachées hors domaines Microsoft (jamais écoutées) : 14

> Ce rapport ne contient ni corps de réponse, ni chaîne de requête, ni en-tête, ni nom de tenant : les identifiants et les noms propres au client sont remplacés par `{id}`.

## Écarts avec l'adaptateur

Chemins relevés que l'adaptateur classe `UNKNOWN` (leurs corps ne sont pas lus aujourd'hui).

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `teams.microsoft.com` | `/ups/emea/v1/me/endpoints` | WORKER | Fetch |  | 200, 201 | UNKNOWN | 1 · un fil | 7 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 5 |
| `teams.microsoft.com` | `/ups/emea/v1/pubsub/subscriptions/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 14 |
| `eu-teams.events.data.microsoft.com` | `/OneCollector/1.0` | WORKER | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 22 |
| `teams.microsoft.com` | `/v2` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/v2/manifest.json` | TEAMS_TAB | Manifest | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `augloop.office.com` | `/` | TEAMS_TAB, WORKER | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 62 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}` | TEAMS_TAB, WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 16 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/cookiev2` | TEAMS_TAB | Fetch |  | 200 | UNKNOWN | 1 · un fil | 4 |
| `go-eu.trouter.teams.microsoft.com` | `/` | WORKER, TEAMS_TAB | Fetch | text/plain | 200 | UNKNOWN | 1 · un fil | 8 |
| `teams.microsoft.com` | `/api/authsvc/v1.0/lfts/TeamsPremium/policies/selfserve` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/skypetokenauth` | TEAMS_TAB | Fetch |  | 204 | UNKNOWN | 1 · un fil | 8 |
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/{id}/aadtokenauth` | TEAMS_TAB | Fetch |  | 204 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/places/findPlaces` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/batchedDefinitions` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 11 |
| `teams.microsoft.com` | `/registrar/prod/V2/registrations` | WORKER, TEAMS_TAB | Fetch |  | 202 | UNKNOWN | 1 · un fil | 8 |
| `webshell.suite.office.com` | `/api/shell/navbardata` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 8 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/pinnedChannels` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `loki.delve.office.com` | `/api/v1/livepersonacard/configuration` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/userSettings/breakthroughlist` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `substrate.office.com` | `/userknowledgebase/v1.0/{id}/teams/{id}` | WORKER | Fetch |  | 204 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/me/engagementSurfaces` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `config.teams.microsoft.com` | `/config/v1/MicrosoftTeams/{id}` | TEAMS_TAB | Preflight, Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 8 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/usage` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v3/teams/users/{id}/updates` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/usersettings/useEndToEndEncryption` | TEAMS_TAB | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `config.teams.microsoft.com` | `/config/v1/Skype/{id}` | TEAMS_TAB | Fetch, Preflight | application/json | 200 | UNKNOWN | 1 · un fil | 12 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/me/settings/meetingConfiguration` | TEAMS_TAB | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/trap/tokens` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `admin.microsoft.com` | `/admin/api/uxversion` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `outlook.office.com` | `/hosted/calendar/{id}` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/teams/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `admin.microsoft.com` | `/api/instrument/logclient` | TEAMS_TAB | XHR | text/plain | 204, 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover/events` | WORKER | Fetch |  | 200 | UNKNOWN | 1 · un fil | 4 |
| `eu-office.events.data.microsoft.com` | `/OneCollector/1.0` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 8 |
| `eu-mobile.events.data.microsoft.com` | `/OneCollector/1.0` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `editor.svc.cloud.microsoft` | `/NLEditor/Config/V2` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 4 |
| `editor.svc.cloud.microsoft` | `/NLEditor/api/V1/LanguageInfo` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 4 |
| `augloop.office.com` | `/sessioninit` | TEAMS_TAB, WORKER | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 11 |
| `francecentral-pa02.augloop.office.com` | `/v2/session/{id}` | TEAMS_TAB, WORKER | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 52 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/objects/{id}/views/thumbnail_small` | TEAMS_TAB | Fetch | image/jpeg | 200 | UNKNOWN | 2 · une réunion passée | 2 |
| `teams.microsoft.com` | `/api/mcps/eu/contents` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | TEAMS_TAB, WORKER | Preflight, Fetch | application/dash+xml | 200 | UNKNOWN | 3 · son récapitulatif | 7 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 6 |
| `graph.microsoft.com` | `/v1.0/drives/{id}/items/{id}/preview` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 4 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/streamembed.aspx` | SERVICE_WORKER, TEAMS_TAB | Fetch, Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 4 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/xplatplugins.aspx` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 3 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |
| `*-my.sharepoint.com` | `/_layouts/15/SPComponentRegistry.ashx` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 4 · sa transcription | 6 |
| `*-my.sharepoint.com` | `/_layouts/15/spwebworkerproxy.ashx` | SERVICE_WORKER | Fetch | text/javascript | 200 | UNKNOWN | 4 · sa transcription | 2 |

## Vu seulement hors de l'onglet Teams

Ces chemins n'apparaissent que dans un autre onglet, un cadre intégré ou un worker : l'observation limitée à l'onglet ne les voit pas.

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `teams.microsoft.com` | `/ups/emea/v1/presence/getpresence` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 7 |
| `teams.microsoft.com` | `/ups/emea/v1/me/endpoints` | WORKER | Fetch |  | 200, 201 | UNKNOWN | 1 · un fil | 7 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 5 |
| `teams.microsoft.com` | `/ups/emea/v1/pubsub/subscriptions/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 14 |
| `eu-teams.events.data.microsoft.com` | `/OneCollector/1.0` | WORKER | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 22 |
| `teams.microsoft.com` | `/api/authsvc/v1.0/lfts/TeamsPremium/policies/selfserve` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/places/findPlaces` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/batchedDefinitions` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 11 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/pinnedChannels` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/userSettings/breakthroughlist` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `substrate.office.com` | `/userknowledgebase/v1.0/{id}/teams/{id}` | WORKER | Fetch |  | 204 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/me/engagementSurfaces` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/usage` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v3/teams/users/{id}/updates` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/users/{id}/conversations/{id}` | WORKER | Fetch | application/json | 404 | CONVERSATION_LIST | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/users/{id}/conversations/{id}/messages` | WORKER | Fetch | application/json | 200 | CONVERSATION_MESSAGES | 1 · un fil | 1 |
| `teams.microsoft.com` | `/api/mt/emea/beta/teams/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover/events` | WORKER | Fetch |  | 200 | UNKNOWN | 1 · un fil | 4 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `teams.microsoft.com` | `/api/mcps/eu/contents` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/me/calendars/events/{id}` | WORKER | Fetch | application/json | 200 | CALENDAR_EVENT | 2 · une réunion passée | 3 |
| `teams.microsoft.com` | `/api/mt/emea/v1/schedulingService/meetings` | WORKER | Fetch | application/json | 200 | MEETING_DETAILS | 3 · son récapitulatif | 3 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |
| `*-my.sharepoint.com` | `/_layouts/15/SPComponentRegistry.ashx` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 4 · sa transcription | 6 |
| `*-my.sharepoint.com` | `/_layouts/15/spwebworkerproxy.ashx` | SERVICE_WORKER | Fetch | text/javascript | 200 | UNKNOWN | 4 · sa transcription | 2 |

## Fichiers SharePoint et OneDrive (F-108)

Les adaptateurs fichiers sont écrits sur la documentation publique de l'API REST SharePoint (forme éprouvée sur documentation, à confirmer sur poste réel). Les chemins classés `SHAREPOINT_*` ou `ONEDRIVE_*` sont ceux qu'ils appellent ; les autres montrent ce que SharePoint web emprunte à la place.

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | TEAMS_TAB, WORKER | Preflight, Fetch | application/dash+xml | 200 | UNKNOWN | 3 · son récapitulatif | 7 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 6 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/streamembed.aspx` | SERVICE_WORKER, TEAMS_TAB | Fetch, Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 4 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/xplatplugins.aspx` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 3 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |
| `*-my.sharepoint.com` | `/_layouts/15/SPComponentRegistry.ashx` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 4 · sa transcription | 6 |
| `*-my.sharepoint.com` | `/_layouts/15/spwebworkerproxy.ashx` | SERVICE_WORKER | Fetch | text/javascript | 200 | UNKNOWN | 4 · sa transcription | 2 |

## Sockets WebSocket

Trames **comptées, jamais lues** : ce tableau dit si messages et transcriptions arrivent par socket, pas ce qu'elles contiennent.

| Hôte | Chemin | Origines | Ouvertures | Trames reçues | Étape |
|---|---|---|---|---|---|
| `augloop.office.com` | `/` | WORKER, TEAMS_TAB | 58 | 0 | 1 · un fil |
| `pub-ent-plce-05-t.trouter.teams.microsoft.com` | `/v4/c` | WORKER | 4 | 40 | 1 · un fil |
| `go-eu.trouter.teams.microsoft.com` | `/v4/c` | TEAMS_TAB | 4 | 25 | 1 · un fil |

## Table des chemins, par hôte

### teams.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `teams.microsoft.com` | `/ups/emea/v1/presence/getpresence` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 7 |
| `teams.microsoft.com` | `/ups/emea/v1/me/endpoints` | WORKER | Fetch |  | 200, 201 | UNKNOWN | 1 · un fil | 7 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 5 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/users/{id}/conversations/{id}/messages/{id}/properties` | TEAMS_TAB | Fetch | application/json | 200 | CONVERSATION_LIST | 1 · un fil | 1 |
| `teams.microsoft.com` | `/ups/emea/v1/pubsub/subscriptions/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 14 |
| `teams.microsoft.com` | `/v2` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/v2/manifest.json` | TEAMS_TAB | Manifest | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}` | TEAMS_TAB, WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 16 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/cookiev2` | TEAMS_TAB | Fetch |  | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/authsvc/v1.0/lfts/TeamsPremium/policies/selfserve` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/places/findPlaces` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/batchedDefinitions` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 11 |
| `teams.microsoft.com` | `/registrar/prod/V2/registrations` | WORKER, TEAMS_TAB | Fetch |  | 202 | UNKNOWN | 1 · un fil | 8 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/pinnedChannels` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/userSettings/breakthroughlist` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/me/engagementSurfaces` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/usage` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v3/teams/users/{id}/updates` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/usersettings/useEndToEndEncryption` | TEAMS_TAB | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/users/{id}/conversations/{id}` | WORKER | Fetch | application/json | 404 | CONVERSATION_LIST | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/users/{id}/conversations/{id}/messages` | WORKER | Fetch | application/json | 200 | CONVERSATION_MESSAGES | 1 · un fil | 1 |
| `teams.microsoft.com` | `/api/mt/emea/beta/me/settings/meetingConfiguration` | TEAMS_TAB | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/trap/tokens` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/teams/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover/events` | WORKER | Fetch |  | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mcps/eu/contents` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/me/calendars/events/{id}` | WORKER | Fetch | application/json | 200 | CALENDAR_EVENT | 2 · une réunion passée | 3 |
| `teams.microsoft.com` | `/api/mcps/eu/collab/readcollabobject/V2/{id}/{id}/{id}` | TEAMS_TAB | Fetch | application/json | 200 | MEETING_COLLAB_OBJECT | 3 · son récapitulatif | 3 |
| `teams.microsoft.com` | `/api/mt/emea/v1/schedulingService/meetings` | WORKER | Fetch | application/json | 200 | MEETING_DETAILS | 3 · son récapitulatif | 3 |

### eu-teams.events.data.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `eu-teams.events.data.microsoft.com` | `/OneCollector/1.0` | WORKER | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 22 |

### augloop.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `augloop.office.com` | `/` | TEAMS_TAB, WORKER | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 62 |
| `augloop.office.com` | `/sessioninit` | TEAMS_TAB, WORKER | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 11 |

### go-eu.trouter.teams.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `go-eu.trouter.teams.microsoft.com` | `/` | WORKER, TEAMS_TAB | Fetch | text/plain | 200 | UNKNOWN | 1 · un fil | 8 |

### fr-prod.asyncgw.teams.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/skypetokenauth` | TEAMS_TAB | Fetch |  | 204 | UNKNOWN | 1 · un fil | 8 |
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/{id}/aadtokenauth` | TEAMS_TAB | Fetch |  | 204 | UNKNOWN | 1 · un fil | 4 |
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/objects/{id}/views/thumbnail_small` | TEAMS_TAB | Fetch | image/jpeg | 200 | UNKNOWN | 2 · une réunion passée | 2 |

### webshell.suite.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `webshell.suite.office.com` | `/api/shell/navbardata` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 8 |

### loki.delve.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `loki.delve.office.com` | `/api/v1/livepersonacard/configuration` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 4 |

### substrate.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `substrate.office.com` | `/userknowledgebase/v1.0/{id}/teams/{id}` | WORKER | Fetch |  | 204 | UNKNOWN | 1 · un fil | 4 |

### config.teams.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `config.teams.microsoft.com` | `/config/v1/MicrosoftTeams/{id}` | TEAMS_TAB | Preflight, Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 8 |
| `config.teams.microsoft.com` | `/config/v1/Skype/{id}` | TEAMS_TAB | Fetch, Preflight | application/json | 200 | UNKNOWN | 1 · un fil | 12 |

### admin.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `admin.microsoft.com` | `/admin/api/uxversion` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `admin.microsoft.com` | `/api/instrument/logclient` | TEAMS_TAB | XHR | text/plain | 204, 200 | UNKNOWN | 1 · un fil | 4 |

### outlook.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `outlook.office.com` | `/hosted/calendar/{id}` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 1 · un fil | 4 |

### eu-office.events.data.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `eu-office.events.data.microsoft.com` | `/OneCollector/1.0` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 8 |

### eu-mobile.events.data.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `eu-mobile.events.data.microsoft.com` | `/OneCollector/1.0` | TEAMS_TAB | XHR | application/json | 200 | UNKNOWN | 1 · un fil | 4 |

### editor.svc.cloud.microsoft

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `editor.svc.cloud.microsoft` | `/NLEditor/Config/V2` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 4 |
| `editor.svc.cloud.microsoft` | `/NLEditor/api/V1/LanguageInfo` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 4 |

### francecentral-pa02.augloop.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `francecentral-pa02.augloop.office.com` | `/v2/session/{id}` | TEAMS_TAB, WORKER | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 52 |

### *-my.sharepoint.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | TEAMS_TAB, WORKER | Preflight, Fetch | application/dash+xml | 200 | UNKNOWN | 3 · son récapitulatif | 7 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 6 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/streamembed.aspx` | SERVICE_WORKER, TEAMS_TAB | Fetch, Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 4 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/xplatplugins.aspx` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 3 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |
| `*-my.sharepoint.com` | `/_layouts/15/SPComponentRegistry.ashx` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 4 · sa transcription | 6 |
| `*-my.sharepoint.com` | `/_layouts/15/spwebworkerproxy.ashx` | SERVICE_WORKER | Fetch | text/javascript | 200 | UNKNOWN | 4 · sa transcription | 2 |

### graph.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `graph.microsoft.com` | `/v1.0/drives/{id}/items/{id}/preview` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 4 |

