# Mini-spec — F-153 / SF-153-03 — Web Push (frontend)

## Identifiant

`F-153 / SF-153-03`

## Feature parente

`F-153` — Notifications (signal + Web Push)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-153-03-web-push-frontend`

---

## Objectif

Côté navigateur : **activer** les notifications Web Push (permission + abonnement via le service
worker de F-152), **afficher** la bannière système à la réception (même app fermée) et **ouvrir le
bon terminal** au clic — de bout en bout, « comme une vraie notif téléphone ».

---

## Comportement attendu

### Cas nominal

- Une carte **Notifications** dans les Paramètres propose **Activer**. Au clic : demande de
  permission du navigateur, puis abonnement via `SwPush.requestSubscription({ serverPublicKey })`
  (clé publique VAPID lue sur `GET /api/push/vapid-public-key`), puis enregistrement via
  `POST /api/push/subscriptions` (scellé `user_id` côté backend).
- Une fois activé, la carte propose **Désactiver** : `SwPush.unsubscribe()` +
  `DELETE /api/push/subscriptions`.
- **Réception** : la charge émise par SF-153-02 est au **format ngsw** (`{ notification: { title,
  body, icon, data:{ url, onActionClick } } }`) ; le service worker de F-152 (`ngsw-worker.js`)
  **affiche** la bannière, même l'application fermée / le téléphone verrouillé.
- **Clic** : `onActionClick` (ngsw) ouvre la fenêtre sur le bon terminal (deep-link `/atelier/<id>`).
  App déjà ouverte : `SwPush.notificationClicks` route vers `data.url`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Service worker non enregistré (dev, navigateur/appareil non compatible) | La carte dit « non disponibles sur cet appareil » ; aucun appel |
| Push non configuré côté serveur (VAPID absent → clé `null`) | Snackbar « non configurées sur le serveur » ; aucun abonnement |
| Permission refusée par l'utilisateur | Snackbar « Permission refusée… » ; rien n'est enregistré |
| Déjà désabonné côté navigateur au `disable()` | On retire quand même la ligne côté gateway (idempotent) |

---

## Critères d'acceptation

- [ ] `PushActivationService` : `enable()` (permission + abonnement + `POST /push/subscriptions`), `disable()` (`unsubscribe` + `DELETE /push/subscriptions`), `supported` (SwPush enregistré), `enabled` (signal reflétant l'abonnement réel).
- [ ] `enable()` rend un résultat lisible : `enabled` / `unsupported` / `not-configured` / `denied` (ne jette jamais).
- [ ] La clé publique VAPID vient de la gateway ; **jamais** de clé en dur ; la privée n'est jamais lue.
- [ ] `SwPush.notificationClicks` → `router.navigateByUrl(data.url)` (app ouverte).
- [ ] La charge backend est au **format ngsw** pour que `ngsw-worker.js` affiche la notification et l'ouvre au clic (`onActionClick`) — reste **neutre** (aucun contenu de tour/projet/commande).
- [ ] UI d'activation conforme DESIGN_SYSTEM (MatCard, MatButton, MatSnackBar, jetons `--cg-*`) dans les Paramètres.
- [ ] Non-régression : `settings.component.spec.ts` reste vert (carte Notifications montée avec service bouchonné).

---

## Périmètre

### Hors scope (explicite)

- Le signal in-tab (SF-153-01) et le backend Web Push (SF-153-02), déjà livrés.
- Un centre de notifications / historique in-app.
- Un service worker **custom** : on réutilise `ngsw-worker.js` (F-152) — c'est lui qui affiche et
  ouvre ; SF-153-03 pilote l'abonnement et la route au clic.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| clé VAPID | lue sur `GET /api/push/vapid-public-key` ; jamais en dur |
| enregistrement | via SwPush + endpoints SF-153-02 (scellés `user_id`) |
| charge | format ngsw, **neutre** (D4) |

---

## Technique

### Endpoints consommés

`GET /api/push/vapid-public-key`, `POST /api/push/subscriptions`, `DELETE /api/push/subscriptions`
(tous livrés en SF-153-02).

### Composants / services Angular

- `PushActivationService` (`core/services/`) — abonnement/désabonnement via `SwPush`, route au clic.
- `NotificationsSettingsComponent` (`settings/notifications-settings/`) — carte d'activation
  (standalone), ajoutée aux Paramètres.

### Changement backend coordonné

- `PushNotificationService.buildPayload` — charge au **format ngsw** (`{ notification: … }`) pour
  que le service worker de F-152 affiche la notification et l'ouvre au clic. Reste neutre.

### Migration Liquibase

- [x] Non applicable (aucun schéma).

---

## Préoccupation transversale — Navigation / routing

`notificationClicks` / `onActionClick` ouvrent un **deep-link** `/atelier/<id>` (route existante,
F-58). Composant qui route : `PushActivationService` (via `Router.navigateByUrl`). Impact : aucune
route ajoutée/modifiée, aucun guard neuf. Si non connecté, le guard d'auth existant redirige vers
`/login` puis la reprise habituelle s'applique (comportement inchangé).

---

## Plan de test

### Tests (Angular / Karma)

- [ ] `push-activation.service.spec.ts` (SwPush + HttpClient bouchonnés) : `enable` nominal (permission + POST), `not-configured` (clé null), `denied` (permission refusée), `unsupported` (SW absent), `disable` (unsubscribe + DELETE), route au clic, reflet de l'abonnement réel.
- [ ] `notifications-settings.component.spec.ts` : propose Activer / Désactiver / « non disponible » selon l'état ; clic → `enable`/`disable`.
- [ ] Non-régression : `settings.component.spec.ts` vert (service push bouchonné).
- [ ] `ng build` production **vert**.

### Isolation user_id

- [x] Applicable — l'enregistrement passe par les endpoints SF-153-02, scellés `user_id` (jamais du corps). Le frontend n'envoie aucun identifiant d'utilisateur.

---

## Dépendances

- **SF-153-02** (endpoints + clé publique) et **F-152 / SF-152-02** (service worker) — livrées.

---

## Notes et décisions

- **D-ngsw** : on réutilise `ngsw-worker.js` (F-152) plutôt qu'un service worker custom ; il affiche
  la charge `{ notification: … }` et gère le clic via `onActionClick`. D'où le format de charge côté
  backend (changement coordonné, neutre).
- **D-prod-only** : en dev le service worker n'est pas enregistré (`SwPush.isEnabled` faux) → la
  carte dit « non disponibles » ; c'est le comportement attendu (le push se teste en prod).
