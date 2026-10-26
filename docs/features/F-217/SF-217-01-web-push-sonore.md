# Mini-spec — F-217 / SF-217-01 Web Push sonore, urgent, et notification de test

## Identifiant
`F-217 / SF-217-01`

## Feature parente
`F-217` — Les notifications qui sonnent (cadrage `CADRAGE-F-217-les-notifications-qui-sonnent.md`, décisions D1–D4)

## Statut
`done`

## Date de création
2026-10-25

## Branche Git
`feat/SF-217-01-web-push-sonore`

---

## Objectif
Faire de chaque notification système une notification **qui sonne** (non silencieuse, persistante pour ce qui attend une action, urgente), l'émettre aussi pour le terminal hébergé, et permettre d'en envoyer une de test à ses appareils.

## Comportement attendu

### Cas nominal
1. **Charge** (`PushNotificationService.buildPayload`) — format ngsw inchangé, champs ajoutés :
   - `silent: false` ; `renotify: true` ; `timestamp` (ms, horloge du service) ;
   - `requireInteraction: true` si l'événement **attend une action** (`PushEvent.requiresAction()` : `AUTHORIZATION_REQUESTED`, `QUESTION_ASKED`, `QUESTION_REMINDER`, `PLAN_AWAITING`, `VALIDATION_AWAITING`), `false` sinon ;
   - `vibrate` : `[200,100,200,100,200]` pour l'action, `[200]` sinon ;
   - `tag` : information → `cg-<terminal|forge>-<EVENEMENT>` ; action → `cg-<terminal|forge>-<EVENEMENT>-<timestamp>` (une occurrence = une notification, donc un son, y compris Safari/Firefox sans `renotify`) ;
   - `data.workspaceId` (identifiant opaque, déjà dans l'URL) pour que l'écran ferme les notifications de ce terminal (SF-217-02).
   Titre, corps, URL et `data.event` inchangés (charge neutre, D4 F-153).
2. **Urgence et TTL** (`PushDelivery.forEvent`) : `AUTHORIZATION_REQUESTED`, `QUESTION_ASKED`, `QUESTION_REMINDER` → `HIGH`, TTL 900 s ; `PLAN_AWAITING`, `VALIDATION_AWAITING` → `HIGH`, TTL 86 400 s ; les autres → `NORMAL`, TTL 86 400 s. Le transport réel pose les en-têtes `Urgency` et `TTL` (`Notification.builder().urgency(..).ttl(..)`).
3. **Terminal hébergé** (`AtelierAgentController.relay`) : `onConfirmationRequest` → `notifyAuthorizationRequested(userId, workspaceId)` ; run terminé (`done`) → `notifyTurnDone` ; exception inattendue → `notify(WORK_STOPPED)`. Injection par **setter facultatif** (aucun second constructeur).
4. **Notification de test** : `POST /api/push/test` (authentifié) → `PushNotificationService.sendTest(userId)` : lit les abonnements **du seul compte courant**, rend `{ configured, devices }` aussitôt, et envoie en asynchrone une charge de test (titre « Notification de test », corps « Si vous l'entendez, le son de vos notifications est bien réglé. », `silent:false`, `requireInteraction:false`, `vibrate`, tag unique `cg-test-<timestamp>`, URL `/settings`, urgence `HIGH`, TTL 300 s). Hors cloche, hors anti-doublon, hors préférences (geste explicite). Endpoint mort (404/410) purgé comme d'habitude.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Push non configuré (pas de VAPID) | `{ configured: false, devices: n }`, rien n'est envoyé | 200 |
| Aucun appareil abonné | `{ configured: true, devices: 0 }` | 200 |
| Non authentifié | refus | 401 |
| Transport en échec / endpoint mort | best-effort ; endpoint mort purgé, jamais d'exception remontée | 200 |
| Push lève dans le relais hébergé | journalisé, n'interrompt jamais le tour | — |

## Critères d'acceptation
- [ ] CA1 — Toute charge porte `silent:false`, `renotify:true`, `timestamp`, `vibrate`, `data.workspaceId` (si terminal).
- [ ] CA2 — `requireInteraction` vaut `true` exactement pour les 5 événements « action », `false` sinon.
- [ ] CA3 — Tag information = `cg-<ws>-<EVENT>` ; tag action = `cg-<ws>-<EVENT>-<ts>` (deux accords successifs → deux tags différents).
- [ ] CA4 — Le transport reçoit `HIGH`/900 s pour accord, question, rappel ; `HIGH`/86 400 s pour plan, validation ; `NORMAL`/86 400 s sinon.
- [ ] CA5 — Le transport réel construit la notification avec urgence et TTL (en-têtes posés).
- [ ] CA6 — Terminal hébergé : une demande de confirmation et une fin de run notifient le propriétaire ; sans service push, rien ne casse.
- [ ] CA7 — `POST /api/push/test` rend `devices` = nombre d'abonnements **du compte courant uniquement** et n'envoie qu'à eux ; un autre compte ne reçoit rien (isolation).
- [ ] CA8 — La notification de test n'entre pas dans la cloche et ignore l'anti-doublon (deux tests de suite partent tous les deux).
- [ ] CA9 — Charge toujours neutre : aucun contenu de tour, nom de projet ni commande.

## Plan de test
- **Unitaires** (`PushNotificationServiceTest`) : CA1–CA4, CA8, CA9 ; tests existants adaptés à la signature `send(sub, payload, delivery)`.
- **Unitaires** (`PushDeliveryTest`) : table événement → urgence/TTL.
- **Unitaires** (`MartijndwarsWebPushTransportTest` ou test de la fabrique de notification) : CA5 (la `Notification` construite porte urgence et TTL).
- **Contrôleur hébergé** : CA6 via test unitaire du relais (listener → push appelé ; push absent → pas d'erreur).
- **Intégration** (`PushSubscriptionApiIntegrationTest`) : CA7 — Alice 2 appareils, Bob 1 : le test d'Alice rend 2 ; 401 sans jeton.
- **Isolation** : CA7 (lecture `findByUserId(currentUser)` uniquement).

## Périmètre — hors scope
Front (carillon, onglet, préférences, bouton « Tester le son » : SF-217-02/03) ; son personnalisé (impossible) ; changement de la règle D7 (terminal regardé ⇒ pas de push) ; nouvel événement vidéo.

## Valeurs initiales
Aucune entité créée.

## Contraintes de validation
`POST /api/push/test` : aucun corps. Réponse `{ configured: boolean, devices: int ≥ 0 }`.

## Technique
| Méthode | URL | Auth | Rôle |
|---|---|---|---|
| POST | `/api/push/test` | JWT | utilisateur authentifié |

Fichiers : `push/PushEvent` (`requiresAction`), `push/PushDelivery` + `PushUrgency` (nouveaux), `push/WebPushTransport` (méthode `send(sub, payload, delivery)` par défaut), `push/MartijndwarsWebPushTransport`, `push/PushNotificationService` (charge, `sendTest`), `push/PushSubscriptionController` (`/test`), `push/dto/PushTestResponse`, `atelier/AtelierAgentController` (setter + 3 appels).

Tables : aucune. Migration : aucune.

## Préoccupations transversales
- **Contexte tenant** : coché — composants : `PushSubscriptionController.test` (lit `currentUser.requireId()`), `PushNotificationService.sendTest` (`repository.findByUserId(userId)`), `AtelierAgentController.relay` (`userId` du jeton, `workspaceId` possédé en aval par `AtelierSessionService`). Test d'isolation CA7.
- Auth / Plans / Navigation : non concernés.
