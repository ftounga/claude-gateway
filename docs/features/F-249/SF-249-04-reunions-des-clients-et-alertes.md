# Mini-spec — F-249 / SF-249-04 Les réunions des clients dans l'agenda, alertes avant réunion

## Identifiant
`F-249 / SF-249-04`

## Feature parente
`F-249` — Les mails et l'agenda Outlook de vos clients (cadrage D14, D15)

## Statut
`done` (2026-11-07)

## Date de création
2026-11-07

## Branche Git
`feat/SF-249-04-reunions-clients`

## Objectif
Les invitations Outlook/Teams des mails déposés alimentent un agenda tous clients, et l'application prévient avant
chaque réunion (pop-up et notification), au délai réglé.

## Comportement attendu

### Cas nominal
1. **Dans le travail `MAIL_DEPOSIT`**, l'invitation d'un courrier déposé (`.ics` joint ou composé depuis une demande
   de réunion Outlook, SF-249-01) est lue **sans IA** par `JarvisIcsParser` (déjà là) :
   - `REQUEST` : ses occurrences dans la fenêtre J-1 → J+60 (récurrences comprises pour un `.ics`) deviennent des
     **réunions du client** (`jarvis_client_meetings`, titre/lieu/description **scellés**, `source_received_at` = date
     du mail) ; **sans « oui »** (aucune écriture chez Google) ; une version plus récente du même `UID` remplace la
     précédente, une plus ancienne est ignorée ;
   - `CANCEL` : les occurrences de ce `UID` sont retirées (toutes, ou celle de `RECURRENCE-ID`).
   Les invités ne sont jamais lus.
2. **Agenda tous clients** : `JarvisCalendarReader.between` (vue d'agenda F-243, outil `agenda` du cerveau rapide)
   **fusionne** ces réunions avec les flux iCal, libellé « Outlook EDENRED » ; dédoublonnage par `UID` + début (une
   invitation déjà présente dans un flux Google n'apparaît qu'une fois). L'outil `agenda` répond même sans flux iCal
   relié si des réunions de clients existent.
3. **Alertes avant réunion** (`ClientMeetingAlerts`, chaque minute) : pour chaque compte, les réunions (flux iCal
   **et** clients) qui commencent dans le délai réglé (`meeting_alert_minutes`, défaut 10, 0 = coupé), hors journée
   entière et annulées, **une seule fois** par (`UID`, début) (`jarvis_meeting_alerts`, clé unique) : annonce
   `MEETING_SOON` « Réunion « Point Fortec » (EDENRED) dans 10 min », phrase « « Point Fortec » commence dans dix
   minutes. », échéance au début de la réunion ; push F-185 `MEETING_SOON` (corps neutre, lien `/jarvis`).
4. Purges : poste supprimé, compte supprimé, réunions finies depuis plus de 7 jours, alertes de plus de 2 jours.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `.ics` illisible | courrier gardé, aucune réunion |
| `CANCEL` d'un `UID` inconnu | rien |
| Invitation plus ancienne (même `UID`) déposée après une plus récente | ignorée |
| Délai réglé à 0 | aucune alerte |
| Réunion d'un autre compte | jamais lue, jamais annoncée |

## Contraintes de validation
| Champ | Règle |
|---|---|
| fenêtre | J-1 → J+60 |
| occurrences par invitation | celles de la fenêtre (borne du parseur : 2 000) |
| délai d'alerte | 0 à 60 min (réglage SF-249-05) |

## Critères d'acceptation
- [ ] Une invitation déposée apparaît dans `GET /api/jarvis/calendar/events` sous « Outlook EDENRED », sans flux iCal
      relié ; une hebdomadaire donne ses occurrences.
- [ ] Une annulation la retire ; une mise à jour la déplace ; une version plus ancienne ne revient pas en arrière.
- [ ] Une réunion à 10:10 donne une annonce à 10:00 (délai 10), une seule fois ; rien avec un délai 0.
- [ ] Une réunion d'un flux Google donne aussi son alerte.
- [ ] **Isolation** : B ne voit ni les réunions ni les alertes de A.

## Plan de test
- **Intégration** `ClientMeetingsIntegrationTest` : dépôt d'un `.eml` avec `.ics` (REQUEST, RRULE), CANCEL, mise à
  jour, ordre inversé, vue d'agenda fusionnée, outil `agenda` sans flux, alertes (horloge réglable, délai 0, une
  fois, réunion d'un flux iCal), **isolation A/B**, purge du poste.
- Suite complète backend à la review.

## Impact
- **Tables** : `jarvis_client_meetings`, `jarvis_meeting_alerts` (migration `249-04-jarvis-client-meetings.xml`,
  réversible).
- **Endpoints** : `GET /api/jarvis/calendar/events` enrichi (aucun nouveau).
- **Backend** : `jarvis/clientmail/ClientMeeting*`, `ClientMeetingAlerts`, `ClientMailDepositJobHandler`,
  `JarvisCalendarReader` (fusion, outil sans flux), `JarvisCalendarEventRepository` (requête des débuts), `PushEvent`
  (`MEETING_SOON`).
- **Consignes** : aucune ne change (le texte de l'outil `agenda` garde son format).

## Préoccupations transversales
- **Contexte tenant** : réunions écrites sous le `user_id` du travail ; lecture par `user_id` ; le travail des alertes
  lit par fenêtre de début puis écrit chaque annonce sous le `user_id` de la ligne. Composants :
  `ClientMeetingService`, `ClientMeetingAlerts`, `JarvisCalendarReader`.

## Hors périmètre
Répondre à une invitation Outlook ; l'écran (SF-249-07/08).
