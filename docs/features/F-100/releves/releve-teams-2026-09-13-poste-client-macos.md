# Relevé réel — Teams web (F-100 / SF-100-00)

- Début : 2026-09-13T18:07:34.599Z
- Fin : 2026-09-13T18:12:25.486Z
- Navigateur : Chrome/151.0.7922.138
- Adaptateur : v1
- Chemins distincts : 19
- Réponses hors domaines Microsoft (non détaillées) : 176
- Ressources statiques écartées : 6
- Cibles attachées hors domaines Microsoft (jamais écoutées) : 2

> Ce rapport ne contient ni corps de réponse, ni chaîne de requête, ni en-tête, ni nom de tenant : les identifiants et les noms propres au client sont remplacés par `{id}`.

## Écarts avec l'adaptateur

Chemins relevés que l'adaptateur classe `UNKNOWN` (leurs corps ne sont pas lus aujourd'hui).

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `teams.microsoft.com` | `/ups/emea/v1/me/endpoints` | WORKER | Fetch |  | 200 | UNKNOWN | 1 · un fil | 1 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 3 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 2 |
| `teams.microsoft.com` | `/api/mcps/eu/contents` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 2 |
| `augloop.office.com` | `/` | TEAMS_TAB | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 8 |
| `teams.microsoft.com` | `/ups/emea/v1/pubsub/subscriptions/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 2 |
| `editor.svc.cloud.microsoft` | `/NLEditor/Config/V2` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 2 |
| `editor.svc.cloud.microsoft` | `/NLEditor/api/V1/LanguageInfo` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 2 |
| `augloop.office.com` | `/sessioninit` | TEAMS_TAB | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 2 |
| `francecentral-pb02.augloop.office.com` | `/v2/session/{id}` | TEAMS_TAB | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 15 |
| `teams.microsoft.com` | `/api/mcps/eu/collab/readcollabobject/V2/{id}/{id}/{id}` | TEAMS_TAB | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 2 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | WORKER | Fetch | application/dash+xml | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/xplatplugins.aspx` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/streamembed.aspx` | SERVICE_WORKER, TEAMS_TAB | Fetch, Document | text/html | 200 | UNKNOWN | 2 · une réunion passée | 2 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/me/calendars/events/iCalUId/{id}` | TEAMS_TAB | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |

## Vu seulement hors de l'onglet Teams

Ces chemins n'apparaissent que dans un autre onglet, un cadre intégré ou un worker : l'observation limitée à l'onglet ne les voit pas.

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `teams.microsoft.com` | `/ups/emea/v1/presence/getpresence` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 1 |
| `teams.microsoft.com` | `/ups/emea/v1/me/endpoints` | WORKER | Fetch |  | 200 | UNKNOWN | 1 · un fil | 1 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 2 |
| `teams.microsoft.com` | `/api/mcps/eu/contents` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 2 |
| `teams.microsoft.com` | `/ups/emea/v1/pubsub/subscriptions/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 2 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `teams.microsoft.com` | `/api/mt/emea/v1/schedulingService/meetings` | WORKER | Fetch | application/json | 200 | MEETING_DETAILS | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | WORKER | Fetch | application/dash+xml | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |

## Fichiers SharePoint et OneDrive (F-108)

Les adaptateurs fichiers sont écrits sur la documentation publique de l'API REST SharePoint (forme éprouvée sur documentation, à confirmer sur poste réel). Les chemins classés `SHAREPOINT_*` ou `ONEDRIVE_*` sont ceux qu'ils appellent ; les autres montrent ce que SharePoint web emprunte à la place.

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 3 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | WORKER | Fetch | application/dash+xml | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/xplatplugins.aspx` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/streamembed.aspx` | SERVICE_WORKER, TEAMS_TAB | Fetch, Document | text/html | 200 | UNKNOWN | 2 · une réunion passée | 2 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |

## Table des chemins, par hôte

### teams.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `teams.microsoft.com` | `/ups/emea/v1/presence/getpresence` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 1 |
| `teams.microsoft.com` | `/ups/emea/v1/me/endpoints` | WORKER | Fetch |  | 200 | UNKNOWN | 1 · un fil | 1 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 2 |
| `teams.microsoft.com` | `/api/mcps/eu/contents` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 2 |
| `teams.microsoft.com` | `/ups/emea/v1/pubsub/subscriptions/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 2 |
| `teams.microsoft.com` | `/api/mcps/eu/collab/readcollabobject/V2/{id}/{id}/{id}` | TEAMS_TAB | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 2 |
| `teams.microsoft.com` | `/api/mt/emea/v1/schedulingService/meetings` | WORKER | Fetch | application/json | 200 | MEETING_DETAILS | 2 · une réunion passée | 1 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/me/calendars/events/iCalUId/{id}` | TEAMS_TAB | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |

### *-my.sharepoint.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 3 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | WORKER | Fetch | application/dash+xml | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/xplatplugins.aspx` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/streamembed.aspx` | SERVICE_WORKER, TEAMS_TAB | Fetch, Document | text/html | 200 | UNKNOWN | 2 · une réunion passée | 2 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |

### augloop.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `augloop.office.com` | `/` | TEAMS_TAB | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 8 |
| `augloop.office.com` | `/sessioninit` | TEAMS_TAB | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 2 |

### editor.svc.cloud.microsoft

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `editor.svc.cloud.microsoft` | `/NLEditor/Config/V2` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 2 |
| `editor.svc.cloud.microsoft` | `/NLEditor/api/V1/LanguageInfo` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | UNKNOWN | 1 · un fil | 2 |

### francecentral-pb02.augloop.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `francecentral-pb02.augloop.office.com` | `/v2/session/{id}` | TEAMS_TAB | Preflight, XHR | application/json | 204, 200 | UNKNOWN | 1 · un fil | 15 |

