# Relevé réel — Teams web (F-100 / SF-100-00)

- Début : 2026-09-15T18:18:54.577Z
- Fin : 2026-09-15T18:22:46.423Z
- Navigateur : Chrome/151.0.7922.138
- Adaptateur : v1
- Chemins distincts : 58
- Réponses hors famille d'hôtes Microsoft (non détaillées) : 2018
- Sockets WebSocket (famille Microsoft) : 3 chemin(s), 65 trame(s) reçue(s)
- Ressources statiques écartées : 117
- Cibles attachées hors domaines Microsoft (jamais écoutées) : 14
- **Mode forme : ACTIF** — des corps de réponse classés ont été **lus** ; seuls les NOMS de champs et leur TYPE JSON sont écrits, **jamais une valeur**. Corps lus : 13 ; indisponibles : 0

> Ce rapport a lu des corps en **mode forme** : il en écrit le **squelette** (noms de champs et types), **jamais une valeur**, jamais un en-tête, jamais une chaîne de requête ; les identifiants et noms propres au client sont remplacés par `{id}`.

## Écarts avec l'adaptateur

Chemins relevés que l'adaptateur classe `UNKNOWN` (leurs corps ne sont pas lus aujourd'hui).

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `teams.microsoft.com` | `/v2` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/cookiev2` | TEAMS_TAB | Fetch |  | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}` | TEAMS_TAB, WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 18 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/places/findPlaces` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/ups/emea/v1/me/endpoints` | WORKER | Fetch |  | 201, 200 | UNKNOWN | 1 · un fil | 8 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 3 |
| `outlook.office.com` | `/hosted/calendar/{id}` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/teams/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 6 |
| `teams.microsoft.com` | `/api/mt/emea/beta/chats/{id}/tabs` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `teams.microsoft.com` | `/api/mcps/eu/contents` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/objects/{id}/views/thumbnail_small` | TEAMS_TAB | Preflight, Fetch | image/jpeg | 204, 200 | UNKNOWN | 2 · une réunion passée | 3 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | TEAMS_TAB, WORKER | Preflight, Fetch | application/dash+xml | 200 | UNKNOWN | 3 · son récapitulatif | 7 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 7 |
| `graph.microsoft.com` | `/v1.0/drives/{id}/items/{id}/preview` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 4 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/streamembed.aspx` | SERVICE_WORKER, TEAMS_TAB | Fetch, Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 4 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/xplatplugins.aspx` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 3 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |

## Vu seulement hors de l'onglet Teams

Ces chemins n'apparaissent que dans un autre onglet, un cadre intégré ou un worker : l'observation limitée à l'onglet ne les voit pas.

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `eu-teams.events.data.microsoft.com` | `/OneCollector/1.0` | WORKER | XHR | application/json | 200 | IGNORED | avant les étapes | 23 |
| `teams.microsoft.com` | `/api/authsvc/v1.0/lfts/TeamsPremium/policies/selfserve` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/places/findPlaces` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/batchedDefinitions` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 10 |
| `teams.microsoft.com` | `/ups/emea/v1/presence/getpresence` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 6 |
| `teams.microsoft.com` | `/ups/emea/v1/pubsub/subscriptions/{id}` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 13 |
| `teams.microsoft.com` | `/ups/emea/v1/me/endpoints` | WORKER | Fetch |  | 201, 200 | UNKNOWN | 1 · un fil | 8 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 3 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/pinnedChannels` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/userSettings/breakthroughlist` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `substrate.office.com` | `/userknowledgebase/v1.0/{id}/teams/{id}` | WORKER | Fetch |  | 204 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/usage` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v3/teams/users/{id}/updates` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/me/engagementSurfaces` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/users/{id}/conversations/{id}` | WORKER | Fetch | application/json | 404 | CONVERSATION_LIST | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/teams/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover/events` | WORKER | Fetch |  | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/chats/{id}/tabs` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `teams.microsoft.com` | `/api/mcps/eu/contents` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |
| `teams.microsoft.com` | `/api/mt/emea/v1/schedulingService/meetings` | WORKER | Fetch | application/json | 200 | MEETING_DETAILS | 3 · son récapitulatif | 3 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/me/calendars/events/iCalUId/{id}` | WORKER | Fetch | application/json | 200 | CALENDAR_EVENT | 3 · son récapitulatif | 1 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/me/calendars/events/{id}` | WORKER | Fetch | application/json | 200 | CALENDAR_EVENT | 3 · son récapitulatif | 2 |

## Fichiers SharePoint et OneDrive (F-108)

Les adaptateurs fichiers sont écrits sur la documentation publique de l'API REST SharePoint (forme éprouvée sur documentation, à confirmer sur poste réel). Les chemins classés `SHAREPOINT_*` ou `ONEDRIVE_*` sont ceux qu'ils appellent ; les autres montrent ce que SharePoint web emprunte à la place.

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 6 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | TEAMS_TAB, WORKER | Preflight, Fetch | application/dash+xml | 200 | UNKNOWN | 3 · son récapitulatif | 7 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 7 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/streamembed.aspx` | SERVICE_WORKER, TEAMS_TAB | Fetch, Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 4 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/xplatplugins.aspx` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 3 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |

## Sockets WebSocket

Trames **comptées, jamais lues** : ce tableau dit si messages et transcriptions arrivent par socket, pas ce qu'elles contiennent.

| Hôte | Chemin | Origines | Ouvertures | Trames reçues | Étape |
|---|---|---|---|---|---|
| `augloop.office.com` | `/` | WORKER, TEAMS_TAB | 60 | 0 | 1 · un fil |
| `pub-ent-dewc-08-t.trouter.teams.microsoft.com` | `/v4/c` | WORKER | 4 | 40 | 1 · un fil |
| `go-eu.trouter.teams.microsoft.com` | `/v4/c` | TEAMS_TAB | 4 | 25 | 1 · un fil |

## Squelette des réponses classées

Pour recaler l'adaptateur : les **noms de champs** et leur **type JSON**, **jamais une valeur**. Groupé par genre ; le premier élément d'un tableau seul est déplié, à profondeur bornée.

### CONVERSATION_LIST

- `teams.microsoft.com/api/chatsvc/fr/v1/users/{id}/conversations/{id}` — origines : WORKER · API : v1 · vu 4 fois
  ```
  { errorCode: number, message: string, standardizedError: { errorCode: number, errorSubCode: number, errorSubCodeString: string, errorDescription: string } }
  ```

### MEETING_DETAILS

- `teams.microsoft.com/api/mt/emea/v1/schedulingService/meetings` — origines : WORKER · API : v1 · vu 3 fois
  ```
  { items: array<object>[ { id: string, data: { groupContext: object, links: object, views: object, globalNumericMeetingId: string, meetingType: string, autoAdmittedUsers: string, allowedUsersForMeetingDetails: string, recorderOption: string, autoRecordingEnabled: boolean, autoTranscriptionOnlyEnabled: boolean, attendanceReportEnabled: boolean, whoCanStartAnnotations: string, allowPstnUsersToBypassLobby: boolean, disableLobby: boolean, interpretationDetails: object{}, entryExitAnnouncementsEnabled: boolean, attendeeViewModes: string, productionStudioMode: string, enableProductionStudio: boolean, presenterOption: string, allowMeetingChat: string, allowTeamsMeetingReactions: boolean, raiseHands: string, disableMeetingBranding: boolean, forceAttendeeStreaming: boolean, showContentSharePreviewInManagedMode: boolean, attendeeRestrictions: string, pstnConference: object, subject: string, location: string, startTime: string, endTime: string, expiryTime: string, iCalUid: string, participants: object, isCancelled: boolean, eventType: string, numericMeetingId: string, stagingRoomDetails: object, detectSensitiveContentDuringScreenSharing: boolean, isDelegate: boolean, yammerQNAEnabled: boolean, whoCanManageQna: string, meetingEndToEndEncryption: string, isCopyRestrictionEnforced: boolean, copilotMode: string, preventScreenCapture: boolean, syntheticMediaDetection: string, whoCanAccessTranscriptAndRecording: string, transcriptAndRecordingOwner: array<empty>, usersCanAdmitFromLobby: string, allowAnonymousUsersToJoinMeeting: boolean, groupCopilotDetails: object, enableMultiLingualMeeting: boolean, producerOption: string, allowMultipleScreenshare: boolean, isRecapEnabled: boolean, aiInterpretationDetails: object{}, autoStartTranscriptionForCopilot: boolean, rtmpInCaptions: boolean, translateAttendeeCaptions: boolean, isBaseEventExperience: boolean } } ] }
  ```

### CALENDAR_EVENT

- `teams.microsoft.com/api/mt/emea/v2.0/me/calendars/events/iCalUId/{id}` — origines : WORKER · API : v2.0 · vu 1 fois
  ```
  { objectId: string, startTime: string, endTime: string, lastModifiedTime: string, eventTimeZone: string, utcOffset: number, subject: string, location: string, meetingLocations: array<object>[ { locationType: string, displayName: string } ], isOnlineMeeting: boolean, myResponseType: string, organizerName: string, appointmentSequenceNumber: number, organizerAddress: string, categories: array<empty>, isResponseRequested: boolean, attendees: array<object>[ { status: { response: string }, type: string, role: string, address: string, name: string } ], isReminderSet: boolean, reminderMinutesBeforeStart: number, showAs: string, bodyContentType: string, bodyContent: string, bodyPreview: string, conflictingMeetings: array<empty>, skypeTeamsData: string, skypeTeamsDataObject: { cid: string, private: boolean, type: string }, skypeTeamsMeetingUrl: string, schedulingServiceUpdateUrl: string, iCalUID: string, cleanGlobalObjectId: string, teamsVtcConferenceId: string, teamsVtcTenantId: string, onlineMeetingConferenceId: string, onlineMeetingTollNumber: string, doNotForward: boolean, inviteAllMembers: boolean }
  ```
- `teams.microsoft.com/api/mt/emea/v2.0/me/calendars/events/{id}` — origines : WORKER · API : v2.0 · vu 2 fois
  ```
  { objectId: string, startTime: string, endTime: string, lastModifiedTime: string, eventTimeZone: string, utcOffset: number, subject: string, location: string, meetingLocations: array<object>[ { locationType: string, displayName: string } ], isOnlineMeeting: boolean, myResponseType: string, organizerName: string, appointmentSequenceNumber: number, organizerAddress: string, categories: array<empty>, isResponseRequested: boolean, attendees: array<object>[ { status: { response: string }, type: string, role: string, address: string, name: string } ], isReminderSet: boolean, reminderMinutesBeforeStart: number, showAs: string, bodyContentType: string, bodyContent: string, bodyPreview: string, conflictingMeetings: array<empty>, skypeTeamsData: string, skypeTeamsDataObject: { cid: string, private: boolean, type: string }, skypeTeamsMeetingUrl: string, schedulingServiceUpdateUrl: string, iCalUID: string, cleanGlobalObjectId: string, teamsVtcConferenceId: string, teamsVtcTenantId: string, onlineMeetingConferenceId: string, onlineMeetingTollNumber: string, doNotForward: boolean, inviteAllMembers: boolean }
  ```

### MEETING_COLLAB_OBJECT

- `teams.microsoft.com/api/mcps/eu/collab/readcollabobject/V2/{id}/{id}/{id}` — origines : TEAMS_TAB · API : v2 · vu 3 fois
  ```
  { changeKey: string, id: string, participants: array<empty>, resources: array<object>[ { id: string, type: string, location: string, metadata: { startTime: string, endTime: string, correlationId: string, callId: string, threadId: string, messageId: string, driveId: string, driveItemId: string, thumbnailUrl: string, isWatermarkEnabled: string, meetingJoinUrl: string, isMultilingual: string, sharePointOnlineId: string }, subType: string, addedBy: string, addedByAppId: string, additionDateTime: string } ], invalidResources: array<empty>, deletedResources: array<empty>, countOfFilteredContentsByChatHistory: number }
  ```

## Table des chemins, par hôte

### francecentral-pa02.augloop.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `francecentral-pa02.augloop.office.com` | `/v2/session/{id}` | TEAMS_TAB, WORKER | Other, Preflight, XHR | application/json | 200, 204 | IGNORED | avant les étapes | 6 |

### eu-teams.events.data.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `eu-teams.events.data.microsoft.com` | `/OneCollector/1.0` | WORKER | XHR | application/json | 200 | IGNORED | avant les étapes | 23 |

### teams.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `teams.microsoft.com` | `/v2` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/v2/manifest.json` | TEAMS_TAB | Manifest | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/cookiev2` | TEAMS_TAB | Fetch |  | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}` | TEAMS_TAB, WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 18 |
| `teams.microsoft.com` | `/api/authsvc/v1.0/lfts/TeamsPremium/policies/selfserve` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/places/findPlaces` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/batchedDefinitions` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 10 |
| `teams.microsoft.com` | `/registrar/prod/V2/registrations` | WORKER, TEAMS_TAB | Fetch |  | 202 | IGNORED | 1 · un fil | 8 |
| `teams.microsoft.com` | `/ups/emea/v1/presence/getpresence` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 6 |
| `teams.microsoft.com` | `/ups/emea/v1/pubsub/subscriptions/{id}` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 13 |
| `teams.microsoft.com` | `/ups/emea/v1/me/endpoints` | WORKER | Fetch |  | 201, 200 | UNKNOWN | 1 · un fil | 8 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 3 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/pinnedChannels` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/userSettings/breakthroughlist` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/users/{id}/usage` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v3/teams/users/{id}/updates` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/me/engagementSurfaces` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/chatsvc/fr/v1/users/{id}/conversations/{id}` | WORKER | Fetch | application/json | 404 | CONVERSATION_LIST | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover` | WORKER | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/usersettings/useEndToEndEncryption` | TEAMS_TAB | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/me/settings/meetingConfiguration` | TEAMS_TAB | Fetch | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/trap/tokens` | TEAMS_TAB | XHR | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/teams/{id}` | WORKER | Fetch | application/json | 200 | UNKNOWN | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/csa/emea/api/v1/teams/users/{id}/discover/events` | WORKER | Fetch |  | 200 | IGNORED | 1 · un fil | 4 |
| `teams.microsoft.com` | `/api/mt/emea/beta/chats/{id}/tabs` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 1 |
| `teams.microsoft.com` | `/api/mcps/eu/contents` | WORKER | Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 5 |
| `teams.microsoft.com` | `/api/mcps/eu/collab/readcollabobject/V2/{id}/{id}/{id}` | TEAMS_TAB | Fetch | application/json | 200 | MEETING_COLLAB_OBJECT | 3 · son récapitulatif | 3 |
| `teams.microsoft.com` | `/api/mt/emea/v1/schedulingService/meetings` | WORKER | Fetch | application/json | 200 | MEETING_DETAILS | 3 · son récapitulatif | 3 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/me/calendars/events/iCalUId/{id}` | WORKER | Fetch | application/json | 200 | CALENDAR_EVENT | 3 · son récapitulatif | 1 |
| `teams.microsoft.com` | `/api/mt/emea/v2.0/me/calendars/events/{id}` | WORKER | Fetch | application/json | 200 | CALENDAR_EVENT | 3 · son récapitulatif | 2 |

### augloop.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `augloop.office.com` | `/` | TEAMS_TAB, WORKER | Preflight, XHR | application/json | 204, 200 | IGNORED | 1 · un fil | 62 |
| `augloop.office.com` | `/sessioninit` | TEAMS_TAB, WORKER | Preflight, XHR | application/json | 204, 200 | IGNORED | 2 · une réunion passée | 15 |

### fr-prod.asyncgw.teams.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/skypetokenauth` | TEAMS_TAB | Fetch |  | 204 | IGNORED | 1 · un fil | 8 |
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/{id}/aadtokenauth` | TEAMS_TAB | Fetch |  | 204 | IGNORED | 1 · un fil | 4 |
| `fr-prod.asyncgw.teams.microsoft.com` | `/v1/objects/{id}/views/thumbnail_small` | TEAMS_TAB | Preflight, Fetch | image/jpeg | 204, 200 | UNKNOWN | 2 · une réunion passée | 3 |

### go-eu.trouter.teams.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `go-eu.trouter.teams.microsoft.com` | `/` | WORKER, TEAMS_TAB | Fetch | text/plain | 200 | IGNORED | 1 · un fil | 8 |

### webshell.suite.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `webshell.suite.office.com` | `/api/shell/navbardata` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | IGNORED | 1 · un fil | 8 |

### substrate.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `substrate.office.com` | `/userknowledgebase/v1.0/{id}/teams/{id}` | WORKER | Fetch |  | 204 | IGNORED | 1 · un fil | 4 |

### config.teams.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `config.teams.microsoft.com` | `/config/v1/MicrosoftTeams/{id}` | TEAMS_TAB | Preflight, Fetch | application/json | 200 | IGNORED | 1 · un fil | 8 |
| `config.teams.microsoft.com` | `/config/v1/Skype/{id}` | TEAMS_TAB | Fetch, Preflight | application/json | 200 | IGNORED | 1 · un fil | 12 |

### loki.delve.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `loki.delve.office.com` | `/api/v1/livepersonacard/configuration` | TEAMS_TAB | XHR | application/json | 200 | IGNORED | 1 · un fil | 4 |

### admin.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `admin.microsoft.com` | `/admin/api/uxversion` | TEAMS_TAB | XHR | application/json | 200 | IGNORED | 1 · un fil | 4 |
| `admin.microsoft.com` | `/api/instrument/logclient` | TEAMS_TAB | XHR | text/plain | 200, 204 | IGNORED | 1 · un fil | 4 |

### outlook.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `outlook.office.com` | `/hosted/calendar/{id}` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 1 · un fil | 4 |

### eu-office.events.data.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `eu-office.events.data.microsoft.com` | `/OneCollector/1.0` | TEAMS_TAB | XHR | application/json | 200 | IGNORED | 1 · un fil | 9 |

### eu-mobile.events.data.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `eu-mobile.events.data.microsoft.com` | `/OneCollector/1.0` | TEAMS_TAB | XHR | application/json | 200 | IGNORED | 1 · un fil | 4 |

### editor.svc.cloud.microsoft

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `editor.svc.cloud.microsoft` | `/NLEditor/Config/V2` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | IGNORED | 1 · un fil | 4 |
| `editor.svc.cloud.microsoft` | `/NLEditor/api/V1/LanguageInfo` | TEAMS_TAB | Preflight, Fetch | application/json | 204, 200 | IGNORED | 1 · un fil | 4 |

### francecentral-pa04.augloop.office.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `francecentral-pa04.augloop.office.com` | `/v2/session/{id}` | TEAMS_TAB, WORKER | Preflight, XHR | application/json | 204, 200 | IGNORED | 2 · une réunion passée | 62 |

### *-my.sharepoint.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 2 · une réunion passée | 6 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/content` | TEAMS_TAB, WORKER | Preflight, Fetch | application/dash+xml | 200 | UNKNOWN | 3 · son récapitulatif | 7 |
| `*-my.sharepoint.com` | `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 7 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/streamembed.aspx` | SERVICE_WORKER, TEAMS_TAB | Fetch, Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 4 |
| `*-my.sharepoint.com` | `/personal/{id}/_layouts/15/xplatplugins.aspx` | TEAMS_TAB | Document | text/html | 200 | UNKNOWN | 3 · son récapitulatif | 3 |
| `*-my.sharepoint.com` | `/personal/{id}/_api/SP.Web.GetContextWebThemeData` | SERVICE_WORKER | Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 1 |

### graph.microsoft.com

| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |
|---|---|---|---|---|---|---|---|---|
| `graph.microsoft.com` | `/v1.0/drives/{id}/items/{id}/preview` | TEAMS_TAB, WORKER | Preflight, Fetch | application/json | 200 | UNKNOWN | 3 · son récapitulatif | 4 |

