# Mini-spec — [F-185 / SF-185-02] Le catalogue des événements

## Identifiant

`F-185 / SF-185-02`

## Feature parente

`F-185` — Rien ne vous attend en silence

## Statut

`in-progress`

## Date de création

2026-10-10

## Branche Git

`feat/SF-185-02-catalogue-evenements`

---

## Objectif

Donner à chaque fin de tour la notification qui dit ce qui vous attend réellement (plan à approuver, validation, délai écoulé, arrêt, poste perdu), au lieu de « Une réponse est prête » pour tout.

---

## Comportement attendu

### Cas nominal

1. **Catalogue** : l'enum `PushEvent` porte un code, un titre et un corps **neutres** (D1, D4 de F-153 : aucun sujet ni contenu).

   | Code | Titre | Corps |
   |---|---|---|
   | `TURN_DONE` | Une réponse est prête | Votre tâche est terminée. |
   | `AUTHORIZATION_REQUESTED` | Une autorisation est demandée | Ouvrez l'application pour autoriser ou refuser. |
   | `QUESTION_ASKED` | Une question vous attend | Ouvrez l'application pour répondre. |
   | `PLAN_AWAITING` | Un plan attend votre accord | Ouvrez l'application pour l'approuver ou le corriger. |
   | `VALIDATION_AWAITING` | Une validation vous attend | L'agent vous propose une décision à confirmer. |
   | `CONTINUED_WITHOUT_YOU` | L'agent a continué sans vous | Une demande est restée sans réponse dans le délai. |
   | `WORK_STOPPED` | Le travail s'est arrêté | Ouvrez l'application pour voir pourquoi. |
   | `MACHINE_LOST` | Votre poste ne répond plus | Le travail a été arrêté : vérifiez que le poste est allumé et connecté. |

2. **Charge** : en plus du titre, du corps et de l'URL, la charge contient `data.event` (le code de l'événement), dont se serviront SF-185-03, 04 et 06. La notification porte un `tag` `cg-<workspaceId>` : sur un même terminal, une nouvelle notification **remplace** la précédente au lieu de s'empiler.
3. **Relevé du tour** : `TurnSignals` enveloppe l'écouteur du tour streamé. Il transmet **tous** les événements à l'écouteur d'origine, sans rien changer, et relève :
   - **validation** : passation de sujet (`onHandoff`), proposition de gouvernance (`onGovernanceProposal`), fermeture d'attente proposée (`onAttente` de type `PROPOSED`) ;
   - **délai écoulé** : question résolue en `timeout`, ou autorisation résolue en `timeout` ;
   - **poste perdu** : `onRunnerOffline`.
4. **Classement en fin de tour** (`TurnOutcomes.classify`, pure) : **une seule** notification par tour, la plus importante :
   - **rien** si le tour a été interrompu par l'utilisateur (il est là, c'est son geste) ;
   - sinon `MACHINE_LOST` si le tour a été arrêté faute de poste (`stoppedByMachine`) ou si le poste a été perdu en cours de tour ;
   - sinon `WORK_STOPPED` si le plafond de consommation a été atteint (`budgetReached`) ;
   - sinon `PLAN_AWAITING` si un plan a été soumis ;
   - sinon `VALIDATION_AWAITING` ;
   - sinon `CONTINUED_WITHOUT_YOU` ;
   - sinon `TURN_DONE`.
5. **Erreur** : une exception qui sort de la boucle d'un tour streamé envoie `WORK_STOPPED`, puis l'exception continue sa route.
6. Les notifications d'autorisation et de question, envoyées pendant le tour, ne changent pas.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Push non configuré | Aucune émission (inchangé) | — |
| Aucun abonnement pour le compte | Aucune émission (inchangé) | — |
| Exception dans la boucle | `WORK_STOPPED` envoyé, exception relancée telle quelle | — |
| Interruption par l'utilisateur | Aucune notification | — |
| Échec d'émission | Journal d'avertissement, jamais fatal (inchangé) | — |
| Tour synchrone (`chat`, non streamé) | Aucune notification (inchangé) | — |

---

## Critères d'acceptation

- [ ] Chaque code du catalogue produit le titre et le corps du tableau, et la charge contient `data.event` et `tag`.
- [ ] Le classement respecte l'ordre de priorité, avec un test par branche.
- [ ] `TurnSignals` transmet chaque méthode de l'écouteur à l'écouteur d'origine (test qui couvre toutes les méthodes).
- [ ] Un tour streamé qui soumet un plan envoie « Un plan attend votre accord », et non « Une réponse est prête ».
- [ ] Un tour streamé qui lève une exception envoie « Le travail s'est arrêté » et l'exception remonte.
- [ ] Un tour interrompu n'envoie rien.
- [ ] Isolation : l'émission reste limitée aux abonnements de `userId` (test existant conservé).

---

## Plan de test

- **Unitaires** :
  - `PushEventTest` : titres neutres, sans aucune donnée variable ;
  - `PushNotificationServiceTest` : charge avec `event` et `tag`, et les trois méthodes historiques inchangées ;
  - `TurnSignalsTest` : relevés et transmission de chaque méthode (vérifiée par réflexion sur l'interface) ;
  - `TurnOutcomesTest` : une branche par priorité.
- **Intégration service** (`AtelierChatServicePushTest` ou ajout au test existant) : plan soumis → `PLAN_AWAITING` ; exception → `WORK_STOPPED` relancée ; interruption → rien.
- **Isolation utilisateur** : `findByUserId(userId)` seul chemin de lecture des abonnements (test existant).

---

## Composants impactés

- `push/PushEvent.java` (nouveau), `push/PushNotificationService.java` (`notify(userId, workspaceId, PushEvent)`, charge).
- `atelier/TurnSignals.java` (nouveau, décorateur), `atelier/TurnOutcomes.java` (nouveau, classement).
- `atelier/AtelierChatService.java` : les 3 variantes `chatStreaming` (enveloppe, classement, exception).
- Aucune table, aucun endpoint. Frontend : aucun changement (le service worker affiche n'importe quel titre).

## Préoccupations transversales

- Contexte tenant : **oui**. Composants qui résolvent le tenant : `PushNotificationService.deliver`, qui lit `findByUserId(userId)` ; inchangé et testé.
- Auth / Principal, plans / limites, navigation : non.

---

## Périmètre

### Hors scope (explicite)

- Pas de notification quand le terminal est regardé, et anti-doublon : SF-185-03.
- Historique et centre de notifications : SF-185-04.
- Rappel avant expiration : SF-185-05.
- Préférences par événement : SF-185-06.
- Récapitulatif des attentes : SF-185-07.
