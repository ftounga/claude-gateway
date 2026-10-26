# Mini-spec — F-217 / SF-217-03 Le son branché : terminal, cloche, onglet qui clignote

## Identifiant
`F-217 / SF-217-03`

## Feature parente
`F-217` — Les notifications qui sonnent (cadrage, décisions D5 branchements, D6, D7)

## Statut
`in-progress`

## Date de création
2026-10-26

## Branche Git
`feat/SF-217-03-branchements-du-son`

---

## Objectif
Faire sonner l'application ouverte à chaque événement qui compte (accord, question, plan, fin de tour, nouvelle notification de la cloche), faire clignoter l'onglet caché, et fermer les notifications système d'un terminal dès qu'on le regarde.

## Comportement attendu

### Cas nominal
1. **Terminal** (`AtelierComponent`) :
   - demande d'accord affichée → `sound.actionRequired('confirm:<toolUseId>')` ; l'invite retirée (décision, expiration, fin de tour, erreur) → `sound.settle(...)` ;
   - question **en attente** affichée → `sound.actionRequired('question:<callId>')` ; une question rejouée déjà répondue ne sonne pas ; question retirée → `settle` ;
   - fin de tour sur plan soumis → son « action » ; fin de tour ordinaire → son « information » ; un tour de suite (`followUp`) ne sonne pas.
   Le son est joué **même terminal regardé et onglet caché** (le Web Push se tait quand le terminal est regardé, D7 de F-153).
2. **Cloche** (`NotificationCenterService`) : une nouveauté d'un autre terminal (celle qui ouvre déjà le bandeau) sonne — « action » pour accord, question, rappel, plan, validation ; « information » sinon. Jamais au premier relevé.
3. **Onglet qui clignote** (`TabAlertService`, D6) : tant que l'onglet est caché, le titre alterne chaque seconde entre « ● <libellé> — <titre> » et le titre d'origine ; libellé de l'accord : **« Accord requis »** ; retour au premier plan (ou `clear()`) → titre et favicon rétablis, clignotement arrêté.
4. **Notifications périmées** (`LiveTerminalService`, D7) : quand le terminal ouvert devient regardé, les notifications système de **ce** terminal encore affichées (`registration.getNotifications()`, `data.workspaceId` ou tag `cg-<id>-…`) sont fermées.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Aucun service worker / API Notifications absente | rien n'est fermé, aucune erreur |
| `getNotifications` rejette | ignoré |
| Son coupé dans les préférences | aucun son, le clignotement reste |
| Rejeu d'état (`question_state`) d'une question déjà répondue | pas de son |

## Critères d'acceptation
- [ ] CA1 — Une demande d'accord déclenche `actionRequired('confirm:<id>')` ; son retrait déclenche `settle('confirm:<id>')`.
- [ ] CA2 — Une question en attente déclenche `actionRequired('question:<callId>')` ; son retrait `settle`.
- [ ] CA3 — Fin de tour : plan → son action ; sinon → son information.
- [ ] CA4 — Cloche : nouveauté d'un autre terminal → son (action pour les 5 événements « action », information sinon) ; premier relevé muet.
- [ ] CA5 — Onglet caché : le titre alterne chaque seconde ; libellé « Accord requis » ; tout est rétabli au retour, minuteur arrêté.
- [ ] CA6 — Terminal regardé → fermeture des seules notifications de ce terminal ; sans service worker, rien ne casse.

## Plan de test
- **Unitaires** `tab-alert.service.spec.ts` (CA5, `jasmine.clock`), `notification-center.service.spec.ts` (CA4), `live-terminal.service.spec.ts` ou fonction pure `closeTerminalNotifications` (CA6), `atelier.component.spec.ts` (CA1–CA3, espion sur `NotificationSoundService`).
- **Intégration / isolation** : aucun nouvel endpoint ni accès de données (la cloche lit déjà `/api/notifications`, filtré par `user_id`).

## Périmètre — hors scope
Nouveaux événements serveur ; réglages (SF-217-02) ; son personnalisé des notifications système.

## Valeurs initiales
Aucune.

## Contraintes de validation
Aucun champ saisi.

## Technique
Fichiers : `atelier/atelier.component.ts` (+spec), `core/services/notification-center.service.ts` (+spec), `core/services/tab-alert.service.ts` (+spec), `core/services/live-terminal.service.ts` (+spec). Tables : aucune. Endpoints : aucun.

## Préoccupations transversales
Auth, tenant, plans, navigation : non concernés.
