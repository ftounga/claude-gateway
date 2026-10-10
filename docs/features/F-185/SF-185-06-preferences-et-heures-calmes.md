# Mini-spec — [F-185 / SF-185-06] Préférences et heures calmes

## Identifiant

`F-185 / SF-185-06`

## Feature parente

`F-185` — Rien ne vous attend en silence

## Statut

`in-progress`

## Date de création

2026-10-10

## Branche Git

`feat/SF-185-06-preferences-notifications`

---

## Objectif

Laisser l'utilisateur choisir quels événements font sonner ses appareils et définir des heures calmes, sans jamais couper ce qui coûte une décision (D5).

---

## Comportement attendu

### Cas nominal

1. **Préférences par compte** (table `notification_preferences`, une ligne par `user_id`, absente = valeurs par défaut) :
   - `muted_events` : événements coupés (liste de codes `PushEvent`, vide par défaut) ;
   - `quiet_from` / `quiet_to` : heures calmes au format `HH:MM` (nulles = aucune). Une plage peut passer minuit (`22:00` → `07:00`) ;
   - `time_zone` : fuseau IANA de l'utilisateur, envoyé par le navigateur (`Europe/Paris` par défaut).
2. **D5, jamais coupés** : `AUTHORIZATION_REQUESTED`, `QUESTION_ASKED` et `QUESTION_REMINDER`. Ils ne peuvent pas être mis en sourdine, et les heures calmes ne les arrêtent pas. Une demande qui tente de les couper est refusée (400).
3. **Effet** : un événement coupé, ou émis pendant les heures calmes, **ne fait pas sonner** les appareils. Il est **toujours inscrit** dans le centre de notifications (SF-185-04) : la cloche garde l'historique complet.
4. **API** (JWT, scellée par `user_id`) :
   - `GET /api/notifications/preferences` → `{ mutedEvents, quietFrom, quietTo, timeZone, events: [{ code, title, critical }] }` (le catalogue sert à l'écran) ;
   - `PUT /api/notifications/preferences` (mêmes champs, sans `events`) → la vue à jour.
5. **Écran** : Paramètres > Notifications ajoute « Ce qui vous prévient ».
   - Une case par événement, avec son titre. Les événements critiques sont cochés, grisés, et portent la mention « toujours ».
   - Deux champs horaires « Heures calmes, de … à … », plus « Aucune ».
   - Le bouton **Enregistrer** envoie aussi le fuseau du navigateur (`Intl.DateTimeFormat().resolvedOptions().timeZone`).
   - Le résultat s'affiche dans un `MatSnackBar`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Code d'événement inconnu | Refus explicite | 400 |
| Couper un événement critique (D5) | Refus explicite | 400 |
| Heure mal formée (`25:00`, `7h`) | Refus explicite | 400 |
| Une seule borne d'heures calmes | Refus explicite (les deux ou aucune) | 400 |
| Fuseau inconnu | Refus explicite | 400 |
| Bornes identiques (`08:00` → `08:00`) | Refus explicite | 400 |
| Lecture des préférences en échec à l'émission | Le push part (mieux vaut un doublon qu'un silence) | — |
| Non authentifié | Refus | 401 |

---

## Critères d'acceptation

- [ ] Par défaut, aucune ligne : tout sonne, comme avant.
- [ ] Un événement coupé ne sonne plus, mais il est inscrit dans le centre.
- [ ] Pendant les heures calmes, y compris une plage qui passe minuit dans le fuseau de l'utilisateur, seuls les événements critiques sonnent.
- [ ] Les événements critiques ne peuvent être ni coupés ni rendus silencieux (400 et test de l'émetteur).
- [ ] Les préférences d'un compte n'affectent pas un autre compte.
- [ ] L'écran affiche le catalogue, grise les événements critiques et enregistre le fuseau du navigateur.

---

## Plan de test

- **Unitaires backend** :
  - `NotificationPreferencesTest` (pur) : sourdine, plage simple, plage qui passe minuit, fuseau, critiques jamais coupés ;
  - `PushNotificationServiceTest` : coupé → pas de push mais journal ; heures calmes → critique envoyé, non critique non envoyé ; préférences illisibles → push.
- **Intégration** (`NotificationPreferencesApiIntegrationTest`) : `GET` par défaut, `PUT` puis `GET`, les 6 cas 400, 401, isolation entre deux comptes.
- **Frontend** (`notifications-settings.component.spec.ts`) : cases rendues, critiques désactivées, enregistrement avec fuseau, heures calmes vidées.
- **Isolation utilisateur** : test d'intégration croisé.

---

## Composants impactés

- **Table** `notification_preferences` (migration `151-notification-preferences.xml`, rollback = suppression de la table) :
  - colonnes : `user_id uuid PK`, `muted_events varchar(1000)`, `quiet_from varchar(5)`, `quiet_to varchar(5)`, `time_zone varchar(64) not null`, `updated_at timestamptz` ;
  - purge à la suppression du compte.
- Backend :
  - `push/PushPreferences` (interface) et `push/PushEvent.critical()` ;
  - `notifications/NotificationPreference(s)`, son dépôt, `NotificationPreferenceService` (implémente `PushPreferences`), son contrôleur et ses DTO ;
  - `PushNotificationService` : contrôle après l'inscription au centre ;
  - `AccountService` : purge.
- Frontend :
  - `core/services/notification-preferences.service.ts` ;
  - `settings/notifications-settings/*`.
- `docs/ARCHITECTURE_CANONIQUE.md` : ajouter la table.

## Préoccupations transversales

- **Contexte tenant** : oui. Composants qui résolvent le tenant :
  - contrôleur, via `CurrentUser.requireId()` ;
  - `NotificationPreferenceService.get`, `save` et `allowsPush`, qui lisent ou écrivent la ligne par clé `user_id` ;
  - `AccountService`, pour la purge.

  Tous sont testés.
- Auth, plans / limites, navigation : non (aucune route ajoutée ; la carte vit dans Paramètres).

---

## Périmètre

### Hors scope (explicite)

- Préférences par appareil (elles sont par compte).
- Autres canaux (courriel, SMS).
- Récapitulatif quotidien : SF-185-07. Son propre réglage viendra avec lui.
