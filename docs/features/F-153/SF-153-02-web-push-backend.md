# Mini-spec — F-153 / SF-153-02 — Web Push (backend)

## Identifiant

`F-153 / SF-153-02`

## Feature parente

`F-153` — Notifications (signal + Web Push)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-153-02-web-push-backend`

---

## Objectif

Poser côté gateway le **Web Push** : une table `push_subscriptions` **filtrée `user_id`**, les
endpoints **subscribe/unsubscribe** + la **clé publique VAPID**, et un **émetteur** branché sur les
**deux transitions de tour de F-84** (**terminé** / **autorisation demandée**) qui pousse une
notification à **titre neutre** vers les seuls appareils du propriétaire, en purgeant les endpoints
morts (404/410). Clés **VAPID par secret d'environnement**, aucune valeur en dur.

---

## Comportement attendu

### Cas nominal

- **S'abonner** : `POST /api/push/subscriptions { endpoint, keys: { p256dh, auth } }` → 201, ligne
  scellée au `user_id` du jeton. Ré-abonnement idempotent (même endpoint pour le même user).
- **Se désabonner** : `DELETE /api/push/subscriptions { endpoint }` → 204, borné au `user_id`.
- **Clé publique** : `GET /api/push/vapid-public-key` → `{ key }` (publique uniquement ; la privée
  n'est **jamais** exposée). `{ key: null }` si le push n'est pas configuré.
- **Émission** : quand un tour **se termine** (`chatStreaming` renvoie) ou **passe en attente
  d'autorisation** (`askPermission`), l'émetteur charge les abonnements **du `user_id`** et pousse
  une charge **neutre** (« Une réponse est prête » / « Une autorisation est demandée » + une route
  d'ouverture, **aucun** contenu de tour, nom de projet ou commande). Un endpoint qui répond
  **404/410** est **purgé**.
- **Repli propre** : si les clés VAPID ne sont pas configurées, l'émetteur est **inactif** (aucun
  octet ne part) ; le signal in-tab (SF-153-01) continue de fonctionner.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Champ `endpoint` manquant/vide | Rejet validé | 400 |
| Désabonnement d'un endpoint inconnu | Idempotent (rien à faire) | 204 |
| Endpoint du service push mort (404/410) à l'émission | Purge de la ligne, pas d'erreur remontée | — |
| Push non configuré (pas de VAPID) | subscribe/unsubscribe marchent ; émission inactive ; `vapid-public-key` → `null` | 201/204/200 |
| Un utilisateur tente d'agir sur l'abonnement d'un autre | Impossible : tout est scellé `user_id` du jeton (jamais un id du corps) | — |

---

## Critères d'acceptation

- [ ] Migration **130** `push_subscriptions` : `id`, `user_id` (non nul), `endpoint`, `p256dh`, `auth`, `created_at` ; index `user_id` ; unicité `(user_id, endpoint)`.
- [ ] `PushSubscriptionRepository` : **toute** lecture filtre `user_id` (`findByUserId`, `deleteByUserIdAndEndpoint`, `deleteByEndpoint` pour purge, `deleteByUserId` pour purge de compte).
- [ ] Endpoints subscribe/unsubscribe/vapid-public-key, tous **authentifiés**, `user_id` = `CurrentUser.requireId()` (jamais du corps).
- [ ] Émetteur `PushNotificationService` : `notifyTurnDone(userId)` / `notifyAuthorizationRequested(userId)` ; charge par `user_id` ; **titre neutre** (aucun contenu sensible) ; purge 404/410.
- [ ] Clés VAPID lues **uniquement** de l'environnement (`APP_PUSH_VAPID_PUBLIC/_PRIVATE`, `APP_PUSH_SUBJECT`) ; **aucune** valeur en dur ; la privée n'est jamais servie ni journalisée.
- [ ] Transport Web Push standard **VAPID** (pas FCM) → **Provider Independence** : ne passe pas par `AIProvider`, aucune dépendance Anthropic.
- [ ] `AccountService.deleteAccount` purge `push_subscriptions`.
- [ ] Wiring : `notifyAuthorizationRequested` dans `askPermission(userId,…)` ; `notifyTurnDone` à la fin d'un tour (`chatStreaming`).

---

## Périmètre

### Hors scope (explicite)

- Le signal in-tab → SF-153-01 (livrée).
- Les handlers `push`/`notificationclick` du service worker + l'UI d'activation/permission →
  SF-153-03 (frontend).
- Un centre de notifications / historique persistant des alertes.

---

## Valeurs initiales / Contraintes de validation

| Champ | Obligatoire | Longueur max | Règle |
|-------|-------------|-------------|-------|
| `endpoint` | Oui | 2000 | URL du service push ; non vide |
| `p256dh` | Oui | 255 | clé publique cliente (base64url) |
| `auth` | Oui | 255 | secret d'authentification (base64url) |
| `user_id` | imposé | — | = jeton, jamais le corps |
| `created_at` | auto | — | à la création |

---

## Technique

### Endpoints

| Méthode | URL (context-path `/api`) | Auth | Rôle |
|---------|---------------------------|------|------|
| POST | `/push/subscriptions` | Oui | tout utilisateur |
| DELETE | `/push/subscriptions` | Oui | tout utilisateur |
| GET | `/push/vapid-public-key` | Oui | tout utilisateur |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `push_subscriptions` | CREATE (migration 130), INSERT/SELECT/DELETE | filtré `user_id` |

### Migration Liquibase

- [x] Oui — `130-push-subscriptions.xml`

---

## Préoccupation transversale — Auth / tenant

Composants qui résolvent le tenant : `PushSubscriptionController` (via `CurrentUser.requireId()`),
`PushSubscriptionRepository` (toutes méthodes filtrent `user_id`), `PushNotificationService`
(charge par `user_id`), `AccountService.deleteAccount` (purge). **Vérifié** : aucun accès sans
`user_id` ; le corps ne porte jamais d'identifiant d'utilisateur ; un utilisateur ne peut ni lire ni
notifier les abonnements d'un autre (test d'isolation).

---

## Plan de test

### Tests unitaires / intégration (backend, H2)

- [ ] `PushSubscriptionRepositoryTest` (@DataJpaTest, H2) : `findByUserId` ne renvoie que les lignes de l'utilisateur ; `deleteByUserIdAndEndpoint` borné ; `deleteByEndpoint` (purge) ; `deleteByUserId`.
- [ ] `PushNotificationServiceTest` (transport bouchonné) : `notifyTurnDone`/`notifyAuthorizationRequested` chargent par `user_id`, envoient une charge **neutre** (assert : aucun contenu de tour), purgent l'endpoint sur **EXPIRED** (404/410) ; transport **désactivé** ⇒ aucun envoi.
- [ ] `PushSubscriptionControllerTest` (MockMvc, `CurrentUser` bouchonné) : subscribe 201 / 400 champ manquant ; unsubscribe 204 ; `vapid-public-key` renvoie la publique (jamais la privée) ; **isolation** : le `user_id` vient du jeton, pas du corps.

### Isolation user_id

- [x] Applicable — testée (un utilisateur ne voit ni ne notifie les abonnements d'un autre).

---

## Dépendances

- **F-152 / SF-152-02** (service worker) est requis pour la **réception** (SF-153-03), pas pour ce
  backend. SF-153-01 (in-tab) reste le repli quand le push n'est pas configuré.

---

## Notes et décisions

- **D-transport-standard** : envoi via **Web Push standard (VAPID)** — bibliothèque
  `nl.martijndwars:web-push` (BouncyCastle déjà présent), **pas** FCM propriétaire (D6/hors scope du
  cadrage). Transport derrière une **interface** `WebPushTransport` ; implémentation réelle
  **active seulement si VAPID configuré**, sinon `DisabledWebPushTransport` (repli propre).
- **D-secret-env** : clés VAPID **exclusivement** par secret d'environnement (patron
  `APP_IMAGE_API_KEY`) ; `isConfigured()` faux ⇒ émission éteinte. La clé **privée** n'est jamais
  servie au frontend ni journalisée.
- **D-neutre** : la charge push ne porte **aucun** contenu sensible (D4) — titre générique + une
  route d'ouverture ; le détail n'apparaît qu'après ouverture authentifiée (SF-153-03).
- **D-branchement-F84** : émetteur branché sur les transitions **déjà** portées par le service
  (`askPermission`, fin de `chatStreaming`) — aucun moteur d'état neuf (Gateway-First).
- **DRAPEAU DÉPLOIEMENT** : générer et poser `APP_PUSH_VAPID_PUBLIC` / `APP_PUSH_VAPID_PRIVATE`
  (+ `APP_PUSH_SUBJECT`) en secret d'environnement au déploiement (comme `APP_IMAGE_API_KEY`). Sans
  eux, l'émission est inactive (repli in-tab). **Décision iOS-managé** (héritée F-152) à confirmer
  sur le parc cible.
