# Mini-spec — [F-185 / SF-185-05] Rappel avant expiration et alerte d'onglet juste

## Identifiant

`F-185 / SF-185-05`

## Feature parente

`F-185` — Rien ne vous attend en silence

## Statut

`in-progress`

## Date de création

2026-10-10

## Branche Git

`feat/SF-185-05-rappel-avant-expiration`

---

## Objectif

Relancer une fois, 2 minutes avant l'échéance, une question restée sans réponse, et dire dans l'onglet ce qui attend réellement : question, plan ou autorisation.

---

## Comportement attendu

### Cas nominal

1. **Rappel** : une question (`demander`) attend 10 minutes (SF-164-07). Si elle est toujours sans réponse **2 minutes avant l'échéance** (à 8 min), l'émetteur envoie `QUESTION_REMINDER` : « Une question attend toujours » / « Sans réponse dans 2 minutes, l'agent décidera par défaut. ».
   - **Un seul** rappel par question.
   - Aucun rappel si la réponse arrive avant, si le tour est interrompu, ou si le délai total est de 2 minutes ou moins.
2. Le rappel est **toujours émis**, même si le terminal est regardé : c'est le dernier filet avant une décision prise à votre place. Il entre dans le centre de notifications comme tout événement.
3. La porte (`RunnerConfirmationGate.awaitAnswer`) attend en deux temps : délai moins 2 min, puis, si rien n'est venu, le rappel puis les 2 min restantes. **Le délai total ne change pas.**
4. **Alerte d'onglet** (SF-153-01, onglet caché) :
   - question posée → « Question en attente » (au lieu de « Autorisation demandée », libellé faux) ;
   - fin de tour avec plan soumis → « Plan à approuver » (au lieu de « Réponse prête ») ;
   - autorisation et fin de tour ordinaire : inchangées.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Réponse avant le rappel | Pas de rappel | — |
| Réponse entre le rappel et l'échéance | Réponse prise normalement | — |
| Interruption pendant l'attente | Pas de rappel, issue « interrompue » (inchangé) | — |
| Délai configuré ≤ 2 min | Pas de rappel, une seule attente | — |
| Rappel en échec (push) | Sans effet sur l'attente (best-effort, inchangé) | — |

---

## Critères d'acceptation

- [ ] Une question sans réponse déclenche un rappel unique à « délai − 2 min », et le délai total reste celui configuré.
- [ ] Une réponse avant le rappel l'empêche ; une réponse après le rappel est prise.
- [ ] Le rappel passe même si le terminal est regardé, et entre dans le centre de notifications.
- [ ] L'onglet caché affiche « Question en attente » pour une question et « Plan à approuver » pour un plan soumis.

---

## Plan de test

- **Unitaires backend** :
  - `RunnerConfirmationGateTest` (délais courts) : rappel à `délai − avance`, un seul ; pas de rappel si réponse avant ; réponse après le rappel prise ; délai inférieur à l'avance → pas de rappel ;
  - `PushNotificationServiceTest` : `QUESTION_REMINDER` envoyé malgré un terminal regardé ;
  - `PushEventTest` (catalogue) : titre neutre.
- **Service** (`AtelierChatServiceQuestionToolTest`) : question laissée sans réponse → `notify(QUESTION_REMINDER)` une fois.
- **Frontend** :
  - `tab-alert.service.spec.ts` : libellés « Question en attente » et « Plan à approuver » ;
  - la fin de tour avec plan appelle `signalPlanAwaiting`.
- **Isolation** : inchangée. Le rappel suit le même chemin `notify(userId, …)` scellé par `user_id`.

---

## Composants impactés

- `runner/exec/RunnerConfirmationGate` :
  - `awaitAnswer(..., Runnable onReminder)` ;
  - `REMINDER_LEAD_MS = 120_000`, réglable pour les tests par `withReminderLead(ms)` (on garde le constructeur unique : un second casserait le contexte Spring).
- `push/PushEvent` : `QUESTION_REMINDER` et `alwaysDelivered()`.
- `push/PushNotificationService` : pas de filtre de présence pour `alwaysDelivered`.
- `atelier/AtelierChatService.askQuestion` : branche le rappel.
- Frontend : `core/services/tab-alert.service.ts`, `atelier/atelier.component.ts`.
- Aucune table, aucun endpoint.

## Préoccupations transversales

Aucune : pas d'authentification, de tenant, de limite ni de route touchés. L'émission reste scellée par `user_id`.

---

## Périmètre

### Hors scope (explicite)

- Rappel pour l'autorisation (2 min de délai au total : un rappel n'aurait pas de sens).
- Rappel pour un plan (pas d'échéance).
- Préférences : SF-185-06.
