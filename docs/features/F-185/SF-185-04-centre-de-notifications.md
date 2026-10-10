# Mini-spec — [F-185 / SF-185-04] Le centre de notifications

## Identifiant

`F-185 / SF-185-04`

## Feature parente

`F-185` — Rien ne vous attend en silence

## Statut

`in-progress`

## Date de création

2026-10-10

## Branche Git

`feat/SF-185-04-centre-de-notifications`

---

## Objectif

Garder la trace de chaque chose qui vous a attendu (cloche, non-lus, historique) et prévenir dans l'application, avec le nom du sujet, quand une réponse arrive dans un autre terminal que celui affiché.

---

## Comportement attendu

### Cas nominal

1. **Journal** : chaque événement du catalogue (SF-185-02) passé à `PushNotificationService.notify` est inscrit dans `user_notifications`, **même sans appareil abonné ni push configuré**. Le centre ne dépend pas du push.
   - Si le terminal est **regardé** (SF-185-03), la ligne est inscrite **déjà lue** : l'utilisateur l'a sous les yeux.
   - Un doublon en moins de 30 s (SF-185-03) n'est pas inscrit.
   - L'inscription passe par l'exécuteur de l'émetteur et ne bloque jamais le tour.
2. **Le sujet** : le nom du terminal (`workspaces.name`), lu par `findByIdAndUserId`, est copié dans la ligne. Il n'est **jamais** dans la charge push (D1). Il s'affiche dans l'application authentifiée seulement.
3. **API** (toutes scellées par le `user_id` du jeton) :
   - `GET /api/notifications` → `{ unread, items: [{ id, event, title, subject, workspaceId, createdAt, read }] }` : les **30** plus récentes, la plus récente d'abord ;
   - `POST /api/notifications/{id}/read` → 204. La ligne d'un autre compte est traitée comme inexistante (404) ;
   - `POST /api/notifications/read-all` → 204.
4. **Rétention** : à chaque inscription, les lignes de ce compte de plus de **30 jours** sont purgées. La suppression du compte purge toutes ses lignes.
5. **La cloche** (`app-notification-bell`), dans la barre de l'application, avant le menu Compte :
   - une pastille indique le nombre de non-lus (« 9+ » au-delà de 9) ;
   - un clic ouvre un menu qui affiche, pour chaque notification, « titre — sujet » et l'heure relative ;
   - les non-lus sont en gras ;
   - cliquer une ligne la marque lue et ouvre le terminal (`/atelier/{workspaceId}`) ;
   - le menu propose « Tout marquer comme lu » ;
   - s'il n'y a rien : « Rien ne vous attend. ».
6. **Relevé** : `NotificationCenterService` relit le centre toutes les **30 s** tant que l'onglet est visible, et immédiatement quand il redevient visible.
7. **Bandeau dans l'app (D6)** : quand un relevé apporte une notification **nouvelle et non lue** d'un terminal **autre** que celui affiché, un bandeau (`MatSnackBar`) dit « <titre> — <sujet> » avec l'action **Ouvrir** (8 s). Un seul bandeau par relevé : la plus récente. Au premier relevé après le chargement de la page, il n'y a pas de bandeau : l'historique n'est pas une nouveauté.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Non authentifié | Refus | 401 |
| Marquer lue la notification d'un autre compte | Inexistante | 404 |
| Identifiant mal formé | Requête invalide | 400 |
| Terminal supprimé depuis | La ligne garde son sujet copié ; le clic mène à `/atelier/{id}`, qui gère déjà l'absence | — |
| Inscription en échec (base) | Journal d'avertissement ; le push part quand même | — |
| Relevé en échec (réseau) | La cloche garde son dernier état ; aucun message | — |

---

## Critères d'acceptation

- [ ] Un événement notifié crée une ligne, avec ou sans abonnement push ; un terminal regardé la crée déjà lue.
- [ ] `GET` ne renvoie que les lignes du compte, au plus 30, de la plus récente à la plus ancienne, avec le bon compte de non-lus.
- [ ] Marquer lue une ligne d'un autre compte renvoie 404 et ne change rien.
- [ ] « Tout marquer comme lu » ne touche que le compte appelant.
- [ ] Les lignes de plus de 30 jours sont purgées à l'inscription ; la suppression du compte purge tout.
- [ ] La cloche affiche les non-lus, la liste, ouvre le terminal au clic et marque lu.
- [ ] Le bandeau apparaît pour une nouveauté d'un autre terminal, jamais pour le terminal affiché ni au premier relevé.
- [ ] Charte : jetons `--cg-*`, Material, utilisable à 360 px.

---

## Plan de test

- **Unitaires backend** :
  - `NotificationCenterServiceTest` : inscription (sujet copié, déjà lue si regardé), purge des plus de 30 jours, lecture bornée à 30 ;
  - `PushNotificationServiceTest` : journal appelé sans abonnement, pas de journal pour un doublon, push toujours émis si le journal échoue.
- **Intégration** (`NotificationCenterApiIntegrationTest`) : `GET`, `read`, `read-all`, 401, 404 croisé entre deux comptes, `read-all` sans effet sur l'autre compte.
- **Isolation utilisateur** : test croisé Alice / Bob ci-dessus, et purge à la suppression du compte (`AccountService`).
- **Frontend** :
  - `notification-center.service.spec.ts` : relevé, pas de bandeau au premier relevé, bandeau pour un autre terminal, pas de bandeau pour le terminal affiché ;
  - `notification-bell.component.spec.ts` : pastille, liste, clic (lu + navigation), « Tout marquer comme lu », état vide.

---

## Composants impactés

- **Table** `user_notifications` (migration `150-user-notifications.xml`, rollback = suppression de la table) :
  - colonnes : `id uuid`, `user_id uuid`, `workspace_id uuid null`, `event varchar(40)`, `subject varchar(200) null`, `created_at timestamptz`, `read_at timestamptz null` ;
  - index : `(user_id, created_at desc)`.
- Backend, nouveau paquet `fr.claudegateway.notifications` :
  - `UserNotification`, `UserNotificationRepository`, `NotificationCenterService` (implémente `push.NotificationJournal`) ;
  - `NotificationCenterController`, DTO.
- `push/NotificationJournal` (interface), `push/PushNotificationService` (inscription dans l'exécuteur).
- `account/AccountService` : purge (mutateur facultatif, comme pour les abonnements push).
- Frontend :
  - `core/services/notification-center.service.ts` ;
  - `layout/notification-bell/notification-bell.component.ts` ;
  - `layout/shell/shell.component.{ts,html}`.
- `docs/ARCHITECTURE_CANONIQUE.md` : ajouter la table.

## Préoccupations transversales

- **Contexte tenant** : oui. Composants qui résolvent le tenant (tous testés) :
  - `NotificationCenterController`, via `CurrentUser.requireId()` ;
  - `NotificationCenterService.record`, `list`, `markRead`, `markAllRead` ;
  - `UserNotificationRepository`, dont toutes les requêtes portent `user_id` ;
  - la lecture du sujet par `findByIdAndUserId` ;
  - `AccountService`, pour la purge.
- **Navigation / routing** : oui. Aucune route ajoutée. La cloche navigue vers `/atelier/:id`, une route existante sous `authGuard`. Les chemins existants de la barre (liens, menu Compte, hamburger sous 820 px) restent inchangés : la cloche est un bouton à côté du menu Compte, testé à 360 px.
- Auth, plans / limites : non.

---

## Périmètre

### Hors scope (explicite)

- Préférences par événement : SF-185-06.
- Rappel avant expiration : SF-185-05.
- Récapitulatif des attentes : SF-185-07.
- Temps réel par SSE : un relevé toutes les 30 s suffit, et F-70 a écarté un canal persistant par onglet.
- Suppression individuelle d'une notification.
