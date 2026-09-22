# Mini-spec — F-147 / SF-147-02 — Le dépôt appartient à un sujet, et devient une réunion

## Identifiant
`F-147 / SF-147-02` — feature parente `F-147`

## Objectif
Qu'un enregistrement déposé soit **rattaché à un sujet dès le geste**, et que son texte devienne une
**réunion exploitable** — pas une preuve Radar de plus.

## Le défaut
> *« Ça aurait eu bien plus de sens de récupérer une réunion depuis un sujet déjà précis. »*
> *« Choisir le sujet obligatoirement. »*

Aujourd'hui le texte transcrit remonte en **échanges Radar** (`LOCAL_RECORDING`, `RadarExchanges`) :
une preuve parmi d'autres. Donc **ni résumé, ni décisions, ni actions, ni Q&A, ni promotion vers la
carte** — alors que tout cela existe déjà pour une **réunion** (F-128 / SF-128-05 et SF-128-11).
Et rien ne relie le dépôt à un sujet au moment où l'utilisateur, lui, sait très bien duquel il s'agit.

## Comportement attendu
1. Le dialogue de dépôt **exige un sujet** : la liste des sujets vivants du poste, pas de dépôt sans choix.
2. À la fin du transfert, la gateway crée une **réunion** rattachée à ce sujet, à l'état
   « transcription en cours », avec le titre et la date donnés au dépôt.
3. Le poste, **au terme de la transcription lancée par SF-147-01**, dépose le texte sur la gateway par
   le chemin runner déjà éprouvé (jeton runner, isolation par le compte du jeton).
4. La réunion devient **exploitable telle quelle** : résumé, décisions, actions, Q&A — **rien n'est
   réimplémenté**, c'est `MeetingExploitationService` qui travaille.
5. L'écran, à la fin du suivi, propose **d'ouvrir la réunion**.

| Cas d'erreur | Comportement |
|---|---|
| Aucun sujet choisi | le dépôt **ne part pas** ; le bouton reste inactif, la raison est écrite |
| Sujet d'un autre compte ou d'un autre poste | **404 indiscernable** — le sujet est revalidé côté serveur, jamais cru sur parole |
| Sujet clos entre le choix et l'envoi | refus dit, avec la raison |
| Transcription échouée | la réunion **existe** et porte l'échec (`transcriptStatus = FAILED` + phrase du poste) ; le fichier reste sur la machine |
| Poste hors ligne au moment de déposer le texte | la réunion reste « en attente » ; **SF-147-06** la rattrape (hors de cette subfeature) |
| Texte vide (aucune parole reconnue) | la réunion porte l'échec nommé, elle n'est pas créée vide en silence |

## Critères d'acceptation
- [x] Le dépôt **exige** un sujet : sans lui, rien ne part.
- [x] Le sujet est **revalidé côté serveur** (possession, poste, sujet vivant) — jamais cru sur parole.
- [x] Une **réunion** est créée, rattachée à ce sujet, avec le titre et la date du dépôt.
- [x] Le texte transcrit **arrive dans cette réunion**, déposé par le poste.
- [x] La réunion est **exploitable** (résumé / décisions / actions) sans code nouveau d'exploitation.
- [x] Un échec de transcription est **porté par la réunion**, pas perdu.
- [x] **ISOLATION** : réunion et sujet filtrés `user_id` **et** `host_id` ; jeton runner isolé par compte.

## Hors scope
La promotion vers la carte du poste, **proposée et rappelée** (**SF-147-03**) · l'outil
`meeting_transcript` (**SF-147-04**) · le retrait de la surveillance périodique (**SF-147-05**) · le
**rattrapage** d'un texte resté sur un poste hors ligne (**SF-147-06**, à ajouter au cadrage).

## Technique
| Élément | Changement |
|---|---|
| `DepositRequest` | gagne `subjectId` **obligatoire** ; validé par le registre des sujets du périmètre |
| `RadarRecordingDepositService.finish` | crée la réunion et **transmet son identifiant au poste** |
| `RecordingMeetingService` *(nouveau)* | crée la réunion « depuis un enregistrement » et y range le texte |
| `RunnerRecordingTranscriptController` *(nouveau)* | `POST /runner/teams/meetings/{meetingId}/local-transcript` — patron de `RunnerMeetingAudioController` (jeton runner, droit Vigie, borne de taille) |
| `RadarDepositReceiver` | retient le `meeting_id` du dépôt et **poste le texte** au terme du travail |
| `radar-deposit-dialog` | liste des sujets, choix **obligatoire**, et « Ouvrir la réunion » à la fin |
| Migration | `meeting_url` devient **nullable** : une réunion venue d'un enregistrement n'a pas d'URL — et lui en inventer une serait un mensonge stocké |

**Consentement** : le dépôt concerne un enregistrement **déjà réalisé** par le client ; la réunion est
créée avec `consentAcknowledged = true` et l'origine `LOCAL_RECORDING` écrite, pour que rien ne laisse
croire que la gateway a capté quoi que ce soit.

## Plan de test
### Gateway — `RadarRecordingApiIntegrationTest` (6) + `RunnerRecordingTranscriptApiIntegrationTest` (6)
- [x] Dépôt sans sujet → **400** ; sujet d'un autre compte → **404** ; aucun appel au poste.
- [x] `finish` crée la réunion rattachée au sujet, avec titre et date, **sans URL**, et le poste reçoit son adresse.
- [x] Le dépôt du texte par le poste remplit la réunion ; sans jeton → **401** ; sans option Teams → **403**.
- [x] **ISOLATION** : le jeton d'un autre compte ne remplit pas cette réunion (**404**) — et rien n'est écrit.
- [x] Un texte vide devient un **échec dit** (`FAILED` + raison), pas une réunion vide en silence.

### Runner — `RadarDepositReceiverTest`, 15 verts
- [x] Le sujet voyage avec le dépôt et revient à la fin — le poste ne le juge pas.
- [x] La réunion annoncée reçoit le texte **au terme** du travail.
- [x] Sans moyen de remonter (`NO_UPLINK`), le dépôt le **dit** ; une remontée qui échoue laisse le fichier en place.
- [x] Une réunion annoncée sans identifiant est refusée.

### Écran — `radar-deposit-dialog.component.spec.ts`, 8 verts
- [x] Sans sujet choisi, le dépôt est impossible.
- [x] `openDeposit` transmet le sujet ; à la fin, la réunion créée est offerte à l'ouverture.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | **oui** *(jeton runner)* | Une route runner de plus : `RunnerTokenAuthenticator` (401 générique), isolation **par le compte du jeton**, droit `EntitlementSpace.VIGIE` — exactement les gardes de `RunnerMeetingAudioController` et `RunnerMeetingImageController`, sans en inventer d'autres. |
| **Contexte tenant** | **oui** | `RadarScope` côté utilisateur (dépôt, sujet) ; côté runner, le couple `user_id`/`host_id` vient **du jeton**, jamais de la requête. Composants : `RadarRecordingDepositService`, `RecordingMeetingService`, `MeetingRepository` (toutes lectures filtrées), registre des sujets. |
| Plans / limites | **oui** | Droit **Vigie** exigé des deux côtés ; l'exploitation de la réunion reste facturée comme aujourd'hui (F-128), la transcription reste **locale** et gratuite. |
| **Navigation / routing** | **oui** *(un écran)* | Le dialogue de dépôt gagne un champ et un lien de sortie vers la réunion ; aucune route d'écran nouvelle. |
