# Échantillons Teams — provenance, et ce qu'ils prouvent

> **Ces fichiers sont FABRIQUÉS.** Aucun ne provient d'un vrai locataire Microsoft, d'un vrai compte
> ni d'une vraie conversation. Aucune personne réelle n'y figure ; tous les identifiants sont des
> zéros et des noms inventés.

## Pourquoi fabriqués

**Nous n'avons aucun compte Teams de test.** C'est une limite du volet, écrite au cadrage
(`docs/features/F-87/CADRAGE-volet-teams.md`) et répétée ici pour qu'elle ne se perde pas : on ne
peut donc **rien éprouver contre un vrai Teams** avant le premier branchement chez un utilisateur.

Ces échantillons ont été écrits à la main d'après la **forme publiquement documentée** des réponses
du service de conversation utilisé par le client web (messages sous `messages[]`, auteur porté par
`from` et `imdisplayname`, horodatage `originalarrivaltime`, mentions et fichiers sérialisés en
chaîne dans `properties`, pagination par `_metadata.backwardLink`).

## Ce qu'ils prouvent — et ce qu'ils ne prouvent pas

| | |
|---|---|
| **Prouvé** | que l'adaptateur **traduit** correctement une réponse de cette forme : champs lus, horodatages en UTC, mentions, pièces jointes, pagination |
| **Prouvé** | qu'une réponse **amputée** ne produit **jamais** de message à moitié lu, mais un manque nommé |
| **Prouvé** | qu'une réponse **inconnue** fait refuser la lecture au lieu de rendre une liste vide silencieuse |
| **Prouvé** | qu'aucun champ secret glissé dans une réponse ne franchit l'adaptateur |
| **NON prouvé** | que la forme supposée est **celle que Microsoft sert aujourd'hui** |

**C'est la sonde de santé (SF-87-03) qui est responsable de confronter l'hypothèse au réel** le jour
du premier branchement : elle compte les champs reconnus et, si elle ne reconnaît rien, **refuse en
nommant** ce qui a changé et la version observée. Le produit s'apercevra donc qu'il ne sait plus
lire **avant** l'utilisateur — et jamais en rendant la moitié d'un compte rendu.

## Les fichiers

| Fichier | Ce qu'il porte |
|---|---|
| `conversation-messages.json` | page nominale : trois messages, une mention, une pièce jointe, une réaction, une page suivante |
| `conversation-messages-partial.json` | un message sans auteur, un sans horodatage, un genre inconnu — et deux messages valides |
| `conversation-messages-unknown.json` | une forme que l'adaptateur ne reconnaît pas du tout |
| `conversation-messages-secrets.json` | page nominale **empoisonnée** de jetons et de cookies : le test de sécurité vérifie qu'aucun ne ressort |
| `conversation-list.json` | liste de conversations (tête-à-tête, canal, réunion) |
| `activity-feed.json` | flux d'activité : deux mentions et une réaction (qui n'en est pas une) |
| `meetings.json` | une réunion enregistrée avec transcription annoncée |
| `transcript.json` | trois répliques horodatées |
| `profile.json` | le profil de l'utilisateur relié — **inventé de bout en bout**, sur un domaine `.invalid` qui ne peut exister (F-88 / SF-88-01) |
| `search-results.json` | deux résultats de l'index de Teams, où le nom est **écrit en clair** — le deuxième gisement (F-88 / SF-88-02) |
| `conversation-messages-page2.json` | la page **précédente** du même fil, qui **chevauche** la première : elle prouve le recollement sans doublon (F-88 / SF-88-01) |

## Réponses modèles SharePoint / OneDrive (F-108)

> **Arbitrage du PO du 2026-09-13** : pas de tenant Microsoft 365 de test ; les capacités fichiers
> passent par l'**API REST SharePoint documentée publiquement** (learn.microsoft.com, « Working with
> folders and files with REST », `contextinfo`, `SP.UserProfiles.PeopleManager`), appelée depuis la
> page. Ces fichiers sont **écrits à la main d'après cette documentation**, en
> `Accept: application/json;odata=nometadata` (enveloppe `value`, `Length` en chaîne). Chaque
> adaptateur qui les lit est marqué « forme éprouvée sur documentation, à confirmer sur poste réel ».
> **Prouvé** : la lecture, la projection sur liste blanche, l'échec bruyant. **NON prouvé** : que
> SharePoint Online sert exactement cette forme au navigateur du prospect.

| Fichier | Ce qu'il porte |
|---|---|
| `sharepoint-folders.json` | `GetFolderByServerRelativePath(…)/Folders` : deux dossiers, avec des clés `odata.*` qui doivent disparaître |
| `sharepoint-files.json` | `…/Files` : deux fichiers (dont un nom avec `#`), clés annexes (`ETag`, `CheckOutType`…) écartées |
| `sharepoint-files-secrets.json` | la même liste **empoisonnée** : digest de formulaire, adresse pré-authentifiée `@content.downloadUrl` (`tempauth`), jeton, cookie — rien ne doit ressortir |
| `sharepoint-files-verbose.json` | la forme `odata=verbose` (`d.results`) : **non conforme** au modèle attendu → échec bruyant |
| `sharepoint-files-partial.json` | un fichier sans `Length` : la liste entière est refusée, jamais rendue à moitié |
| `sharepoint-file.json` | `GetFileByServerRelativePath(…)` : un fichier |
| `sharepoint-contextinfo.json` | `POST /_api/contextinfo` : le digest — la projection n'en laisse **rien** |
| `sharepoint-error-403.json`, `sharepoint-error-404.json` | erreurs `odata.error` |
| `onedrive-my-properties.json` | `GetMyProperties?$select=PersonalUrl` sur un domaine `.invalid` |
| `conversation-messages-files.json` | un message de canal portant une pièce jointe SharePoint (propriété `files`) |
| `conversation-messages-recording.json` | le message d'enregistrement du fil de réunion (`RichText/Media_CallRecording`, élément `onedriveForBusinessVideo`), adresse `.mp4` **suivie d'une requête empoisonnée** qui ne doit jamais ressortir (F-108 / SF-108-05) |
| `sharepoint-recording-file.json` | métadonnées du `.mp4` (`GetFileByServerRelativePath`) |
| `sharepoint-recordings-files.json` | le dossier `Recordings` : le `.mp4` et son `.vtt` de même nom |
| `recording-transcript.vtt` | transcription WebVTT (forme publique W3C, locuteur `<v Nom>`), deux répliques inventées |
| `sharepoint-folder-created.json` | réponse de `POST /_api/web/folders` (SP.Folder créé) (F-108 / SF-108-04) |
| `sharepoint-folder-general.json` | un dossier existant, lu par `GetFolderByServerRelativePath` |
| `sharepoint-file-uploaded.json` | réponse de `Files/AddUsingPath(…,Overwrite=false)` |
| `sharepoint-file-replaced.json` | réponse de `Files/AddUsingPath(…,Overwrite=true)` : version 4.0 |
| `sharepoint-moveto.json` | réponse de `MoveTo` (`odata.null`) — sert aussi de « succès non conforme » |
| `sharepoint-recycle.json` | réponse de `recycle()` : l'identifiant de corbeille (`value`) |
| `sharepoint-error-exists.json`, `sharepoint-error-locked.json` | « existe déjà » (400) et « verrouillé » (423) |
| `calendar-event.json` | F-89 / SF-89-05 — détail d'événement de calendrier servi par `/api/mt/{région}/v2.0/me/calendars/events/iCalUId/{id}` (chemin **relevé réel** du 2026-09-13, onglet, étape « récapitulatif ») ; le **corps** est fabriqué sur la forme publique documentée des événements Microsoft 365 (`subject`, `start`/`end` `{dateTime, timeZone}`, `attendees[].emailAddress`, `onlineMeeting.joinUrl`) — forme **héritée**, conservée en non-régression |
| `meeting-details-cagip.json` | F-89 / SF-89-13 — détail des réunions **forme RÉELLE** (`/schedulingService/meetings`) : enveloppe `items[]`, champs sous `data` (`subject`, `startTime`, `endTime`, `iCalUid`, `isCancelled`…). Corps **reconstruit à partir du squelette** du relevé CAGIP 2026-09-15 (noms + types réels, valeurs synthétiques) : `docs/features/F-100/releves/releve-teams-forme-2026-09-15-cagip.md` |
| `calendar-event-cagip.json` | F-89 / SF-89-13 — événement de calendrier **forme RÉELLE** : objet unique `objectId`, `startTime`/`endTime` en chaînes, `attendees[]` avec `address`/`name` au niveau de l'attendee, `skypeTeamsMeetingUrl`, `iCalUID`. Même `iCalUID` que `meeting-details-cagip.json` → **dédup**. Reconstruit depuis le squelette du relevé CAGIP |
| `readcollabobject-cagip.json` | F-89 / SF-89-13 — objet de collaboration (récapitulatif) **forme RÉELLE** : `resources[].metadata` porte `driveId`/`driveItemId`/`threadId`/`callId`/`meetingJoinUrl`/`startTime`/`endTime` (l'emplacement de l'enregistrement). Reconstruit depuis le squelette du relevé CAGIP |
| `calendar-view-cagip.json` | F-89 / SF-89-15 — la **liste** du calendrier **forme RÉELLE** (`/api/mt/emea/v2.0/me/calendars/default/calendarView`) : enveloppe `value[]`, chaque item = mêmes champs qu'un CALENDAR_EVENT (`objectId`, `startTime`/`endTime`, `subject`, `skypeTeamsMeetingUrl`, `iCalUID`…). 2 réunions ; la 1re partage `iCalUID` avec `calendar-event-cagip.json` (→ dédup). Reconstruit depuis le squelette du relevé catalogue CAGIP 2026-09-16 (`releve-teams-catalogue-complet-2026-09-16-cagip.json`) |
| `graph-events-cagip.json` | F-89 / SF-89-15 — la **liste** du calendrier par Microsoft Graph **forme RÉELLE** (`/v1.0/me/events`, hôte `graph.microsoft.com`), reconnue **en résilience** : enveloppe `value[]`, item = `{ id, iCalUId, subject, start:{dateTime,timeZone}, onlineMeetingUrl, isCancelled… }`. Même `iCalUId` que `calendar-view-cagip.json` (→ dédup inter-formes). Reconstruit depuis le squelette du relevé catalogue CAGIP 2026-09-16 |
| `sharepoint-driveitem-cagip.json` | F-108 / SF-108-07 — les **droits** d'un élément de drive **forme RÉELLE** (`/_api/v2.1/drives/{id}/items/{id}`) : `webUrl`, `accessViewpoint{canRead,canEdit,canDelete,canComment,canDownload,canManagePermissions}`, plus `sharepointIds.siteUrl`/`expiration`. Porte volontairement une adresse pré-authentifiée `@content.downloadUrl` (jeton `SECRET-SIGNED-TOKEN`) pour éprouver la **non-fuite**. Reconstruit depuis le squelette du relevé catalogue CAGIP 2026-09-16 |
| `sharepoint-driveitem-labelpolicies-cagip.json` | F-108 / SF-108-07 — la **protection IRM** d'un élément de drive **forme RÉELLE** (`/_api/v2.1/drives/{id}/items/{id}/labelPolicies`) : `irmCapabilities{canPrint,canExtract,canRead,canWrite}`. Reconstruit depuis le squelette du relevé catalogue CAGIP 2026-09-16 |
| `readcollabobject-fabrique.json` | F-108 / SF-108-07 (intégration) — objet de collaboration (récapitulatif) dont `metadata.threadId` = `19:meeting_fabrique@thread.v2` (le fil de la réunion fabriquée `MTG-FABRIQUE-0001`) + `driveId`/`driveItemId`, pour rattacher un récap à cette réunion et éprouver le contrôle de droits **avant** téléchargement. Même forme que `readcollabobject-cagip.json` (SF-89-13) |
