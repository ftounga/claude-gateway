# Doctrine — la reconnaissance des adresses Teams, calée sur le relevé réel (SF-89-10)

> Rédigé le 2026-09-15, à partir du relevé réel
> `docs/features/F-100/releves/releve-teams-2026-09-15-poste-client-avec-transcription.md`
> (poste client, runner à jour, Teams v2, tenant EMEA — 4 étapes : fil, réunion, récapitulatif,
> transcription).

## Ce que le relevé confirme

Le classifieur `TeamsUrls` reconnaît déjà, correctement, les familles de **contenu** vues sur le
poste réel :

- messages : `/…/conversations/{id}/messages` → `CONVERSATION_MESSAGES`, `/…/conversations` →
  `CONVERSATION_LIST` ;
- calendrier : `/…/me/calendars/events/{id}` → `CALENDAR_EVENT` ;
- réunion : `/…/v1/schedulingService/meetings` → `MEETING_DETAILS` ;
- récapitulatif : `/…/collab/readcollabobject/…` → `MEETING_COLLAB_OBJECT` (nommé, jamais lu).

Ces règles ne changent pas.

## Deux points de doctrine (issus des étapes 4 et 1 du relevé)

1. **La transcription passe par la lecture d'écran, pas par une règle réseau.** À l'étape
   « transcription », le réseau ne porte **aucune réponse de contenu** : seulement l'infra du
   lecteur SharePoint (`_layouts/15/SPComponentRegistry.ashx`, `spwebworkerproxy.ashx` — désormais
   `IGNORED`) et le flux vidéo (`application/dash+xml`). Le texte de la transcription est lu par
   l'écran (SF-89-06). **On n'invente pas de règle de transcription réseau** : il n'y a rien à
   classer.

2. **Les messages temps réel arrivent par socket trouter `/v4/c`.** Ces trames sont **comptées,
   jamais lues** (voir `ObservationDiagnostic.socketFrames`) et restent **hors périmètre**. Le
   *handshake* HTTP trouter (`*.trouter.teams.microsoft.com/`) est du bruit d'infra → `IGNORED` ;
   les **sockets** elles-mêmes ne passent pas par `classify` et ne sont pas touchées.

## Le nettoyage apporté (SF-89-10)

Les familles manifestement de télémétrie, présence, auth, config, abonnement/enregistrement,
éditeur assisté et coquille d'app basculent d'`UNKNOWN` vers `IGNORED` (table `TeamsUrls.IGNORE_RULES`,
une ligne par famille + un exemple du relevé dans `TeamsUrlsTest`). Effet : le diagnostic
`NOTHING_CLASSIFIED` ne se déclenche plus pour du bruit, et `topUnknownPaths` n'est plus pollué.

**Prudence** : en cas de doute, une adresse reste `UNKNOWN` (un `IGNORED` de trop masquerait une
future capacité). C'est pourquoi `/api/mt/emea/beta/users/{id}` nu, `/cookiev2`,
`/…/consumptionhorizons`, `/…/places/findPlaces`, `/api/mcps/eu/contents` et `/api/mt/emea/beta/teams/{id}`
restent volontairement `UNKNOWN`.

## Récapitulatif de réunion (`readcollabobject`) : toujours nommé, jamais lu

Le relevé **ne porte aucun corps de réponse**. Aucune forme modèle de `readcollabobject` n'est donc
connue ; en lire le corps reviendrait à **inventer** une forme et rendrait un récapitulatif à moitié
faux. `MEETING_COLLAB_OBJECT` reste **nommé, jamais lu** (invariant `NetworkObserver`). À réévaluer
seulement quand un échantillon réel de corps sera disponible.
