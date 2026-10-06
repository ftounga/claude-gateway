# Mini-spec — [F-164 / SF-164-05] Push « une question vous attend »

---

## Identifiant

`F-164 / SF-164-05`

## Feature parente

`F-164` — Questions structurées à l'utilisateur (parité Claude Code `AskUserQuestion`)

## Statut

`in-review`

## Date de création

2026-10-06

## Branche Git

`feat/SF-164-05-push-question-en-attente`

---

## Objectif

Quand l'agent suspend son tour sur une question structurée (outil `demander`), envoyer une notification Web Push « Une question vous attend » au propriétaire, exactement comme pour une demande d'autorisation (cadrage F-164 §3 « Cross-device (F-84) + notification push (F-153) »).

---

## Comportement attendu

### Cas nominal

1. Le modèle appelle `demander` avec un lot valide (1..n questions).
2. `AtelierChatService.askQuestion` appelle `PushNotificationService.notifyQuestionAsked(userId, workspaceId)` **avant** `confirmationGate.awaitAnswer`, null-safe comme `askPermission`.
3. L'émetteur (F-153) pousse, en asynchrone best-effort, aux seuls abonnements du `user_id` : titre « Une question vous attend », corps « Ouvrez l'application pour répondre. », deep link `/atelier/{workspaceId}` (`onActionClick.default = openWindow`), même format ngsw que les autres notifications.
4. **Une seule** notification par appel `demander`, quel que soit le nombre de questions du lot.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Push non configuré (transport inactif, pas de VAPID) | Aucune émission, le tour continue normalement | — |
| `userId` null | Aucune émission | — |
| Émetteur absent (bean non câblé, formes historiques/tests) | Aucune émission, aucune erreur | — |
| Lot `demander` invalide (rejeté avant l'attente) | Aucune émission (rien n'attend l'utilisateur) | — |
| Endpoint mort (404/410) | Purgé (comportement F-153 existant) | — |
| Échec réseau | Journalisé (warn), jamais fatal pour le tour | — |

---

## Critères d'acceptation

- [ ] CA1 — `notifyQuestionAsked` émet le titre « Une question vous attend » et le corps « Ouvrez l'application pour répondre. », avec le deep link `/atelier/{workspaceId}`.
- [ ] CA2 — Confidentialité : la charge ne contient aucun contenu de question (titre/corps littéraux, identifiant opaque).
- [ ] CA3 — Inactif sans transport configuré et pour un `userId` null.
- [ ] CA4 — Un appel `demander` valide (lot de 2 questions) déclenche exactement **une** notification `notifyQuestionAsked(userId, workspaceId)`.
- [ ] CA5 — Un autre outil (ex. `set_plan`) ne déclenche pas `notifyQuestionAsked`.
- [ ] CA6 — Un lot invalide (rejeté avant l'attente) ne déclenche pas de notification.
- [ ] CA7 — Isolation : l'émission ne cible que les abonnements du `user_id` du tour (`findByUserId`, inchangé F-153).

---

## Périmètre

### Hors scope (explicite)

- Toute modification frontend : le service worker Angular (`ngsw-worker.js`, F-152/F-153) affiche déjà toute charge `{notification:{…}}` et gère `onActionClick` — rien à changer.
- Préférences de notification par type (activer/désactiver les questions séparément).
- Contenu de la question dans la notification (exclu volontairement, D4 F-153).
- Notification à la résolution (réponse/timeout) de la question.

---

## Valeurs initiales

Sans objet (aucune entité créée).

---

## Contraintes de validation

Sans objet (aucune entrée utilisateur nouvelle).

---

## Technique

### Endpoint(s)

Aucun nouveau.

### Tables impactées

Aucune (lecture de `push_subscriptions` existante, scellée par `user_id`).

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

Aucun.

### Composants backend

- `push/PushNotificationService` — nouvelle méthode `notifyQuestionAsked`.
- `atelier/AtelierChatService.askQuestion` — appel null-safe avant `awaitAnswer`.

### Préoccupations transversales

Aucune cochée (ni auth/principal, ni tenant, ni plans/limites, ni routing).

---

## Plan de test

### Tests unitaires

- `PushNotificationServiceTest` : titre/corps/deep link de `notifyQuestionAsked` ; silence sans transport et pour `userId` null.
- `AtelierChatServiceQuestionToolTest` : `demander` (lot de 2) ⇒ 1 seul `notifyQuestionAsked(userId, workspaceId)` ; `set_plan` ⇒ jamais ; lot invalide ⇒ jamais.

### Tests d'intégration

Suite backend complète (contexte Spring : aucun constructeur ajouté, câblage par mutateur existant).

### Isolation workspace

Inchangée : `PushNotificationService.deliver` ne charge que `findByUserId(userId)` ; l'appel passe le `userId` du tour.

---

## Dépendances

### Subfeatures bloquantes

SF-164-01 (outil `demander`, livrée), SF-153-02 (émetteur Web Push, livrée).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- La notification part avant l'attente, comme `askPermission` : le push est asynchrone (exécuteur dédié), il ne retarde pas l'affichage de la question.
