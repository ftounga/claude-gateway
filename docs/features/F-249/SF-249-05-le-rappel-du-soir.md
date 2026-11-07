# Mini-spec — F-249 / SF-249-05 Le rappel du soir et les réglages

## Identifiant
`F-249 / SF-249-05`

## Feature parente
`F-249` — Les mails et l'agenda Outlook de vos clients (cadrage D12, D16, D20)

## Statut
`done` (2026-11-07)

## Date de création
2026-11-07

## Branche Git
`feat/SF-249-05-rappel-du-soir`

## Objectif
En fin de journée, l'application rappelle « Déposez vos mails <client> » pour chaque client actif, deux ou trois fois
au plus, et se tait dès qu'un dépôt a eu lieu ce jour-là pour ce client ; les réglages sont exposés à l'écran.

> Ordre de livraison : SF-249-05 passe **avant** SF-249-04, parce que le délai des alertes avant réunion (SF-249-04)
> est un réglage de la table créée ici.

## Comportement attendu

### Cas nominal
1. **Client actif** : une ligne `jarvis_client_mail_hosts` (compte, poste) avec `reminder_enabled`. Créée d'office,
   **active**, au premier dépôt d'un poste ; activable/désactivable à la main pour tout poste du compte.
2. **Réglages du compte** (`jarvis_client_mail_settings`, défauts si absents) : heure du rappel **18:30**, **3**
   rappels au plus, **45 min** entre deux, **jours ouvrés** seulement (lun→ven), délai des alertes avant réunion
   **10 min** (servi en SF-249-04).
3. **Travail chaque minute** (`ClientMailReminder`) : pour chaque client actif, le n-ième rappel du jour est dû à
   `heure + (n-1) × intervalle` (heure de Paris). S'il est dû, non encore envoyé, et qu'**aucun dépôt** n'a eu lieu
   ce jour-là pour ce client : annonce `DEPOSIT_REMINDER` (clé `reminder:<poste>:<date>`) « Déposez vos mails
   EDENRED », phrase dite « Pensez à déposer vos mails EDENRED de la journée. », échéance à minuit ; push F-185
   `CLIENT_MAIL_DEPOSIT_REMINDER` (corps neutre, lien `/jarvis`). Le compte d'envoi du jour est tenu dans
   `jarvis_client_mail_reminders` (unique compte, poste, jour ; avance par `UPDATE` conditionnel : deux pods
   n'envoient jamais le même rappel).
4. **Le dépôt fait taire** : tout dépôt reçu (même un doublon) note `deposited_at` du jour et **ferme** l'annonce du
   rappel ; plus aucun rappel ce jour-là pour ce client.
5. **Routes** (compte authentifié) :
   - `GET /api/jarvis/client-mail` : réglages + un élément par poste du compte : `hostId`, `name`, `active`
     (rappel), `upToDate` (le runner annonce `mail_deposit`), `folder` (`<racine>/jarvis/mails/`, d'après le nom de
     racine déclaré), `lastDepositAt`, `mails` (courriers déposés en cache), `depositedToday` ;
   - `PUT /api/jarvis/client-mail/settings` ;
   - `PUT /api/jarvis/client-mail/hosts/{hostId}` `{active}`.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Heure illisible (`25:00`, `ab`) | refus | 400 |
| Rappels hors 1-3, intervalle hors 15-180 min, délai réunion hors 0-60 min | refus | 400 |
| Poste d'un autre compte | introuvable | 404 |
| Samedi, réglage « jours ouvrés » | aucun rappel | — |
| Dépôt avant l'heure du rappel | aucun rappel ce jour-là | — |
| Coupe-circuit `APP_JARVIS_CLIENT_MAIL_ENABLED=false` ou annonces coupées | aucun rappel | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `reminderTime` | `HH:MM`, 00:00-23:59 |
| `reminderMax` | 1 à 3 |
| `reminderEveryMinutes` | 15 à 180 |
| `meetingAlertMinutes` | 0 (coupé) à 60 |
| `weekdaysOnly` | booléen |

## Critères d'acceptation
- [ ] Sans dépôt : rappels à 18:30, 19:15 et 20:00, puis plus rien ; un seul par échéance même appelé deux fois.
- [ ] Un dépôt à 19:00 ferme l'annonce et empêche le rappel de 19:15.
- [ ] Le samedi (jours ouvrés), rien.
- [ ] Un client désactivé ne reçoit rien ; le premier dépôt d'un poste l'active.
- [ ] Les réglages s'enregistrent, sont bornés (400) et changent l'échéance.
- [ ] **Isolation** : B ne lit ni ne règle les postes de A (404) ; les rappels de A ne vont qu'à A.

## Plan de test
- **Unitaires** `ClientMailReminderScheduleTest` : échéances dues à une heure donnée (avant, entre, après), jours
  ouvrés, validation des réglages.
- **Intégration** `ClientMailReminderIntegrationTest` : horloge réglable — trois rappels puis silence, idempotence,
  dépôt qui fait taire, samedi, client désactivé, premier dépôt actif, routes (GET/PUT, 400, 404), **isolation A/B**.
- Suite complète backend à la review.

## Impact
- **Tables** : `jarvis_client_mail_settings`, `jarvis_client_mail_hosts`, `jarvis_client_mail_reminders` (migration
  `249-05-jarvis-client-mail-settings.xml`, réversible).
- **Endpoints** : `GET /api/jarvis/client-mail`, `PUT /api/jarvis/client-mail/settings`,
  `PUT /api/jarvis/client-mail/hosts/{hostId}`.
- **Backend** : `ClientMailSettings*`, `ClientMailHost*`, `ClientMailReminder*`, `ClientMailController`,
  `ClientMailDepositService` (dépôt ⇒ silence, client actif), `PushEvent` (`CLIENT_MAIL_DEPOSIT_REMINDER`).
- **Consignes** : aucune ne change.

## Préoccupations transversales
- **Contexte tenant** : routes sous le `user_id` du principal ; poste vérifié par (`id`, `user_id`)
  (`RunnerHostRepository.findByIdAndUserId`). Le travail parcourt les lignes de chaque compte sous leur `user_id`.
  Composants : `ClientMailController`, `ClientMailReminder`.

## Hors périmètre
L'écran (SF-249-07), les alertes avant réunion (SF-249-04), les règles « ignorer » (SF-249-06).
