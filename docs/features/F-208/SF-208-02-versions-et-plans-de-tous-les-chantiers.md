# Mini-spec — [F-208 / SF-208-02] Versions du plan, plan de tous les chantiers ouverts, parcours en direct

## Identifiant
`F-208 / SF-208-02` — Feature parente `F-208` (Le tableau des chantiers) — Statut `in-review` — Créée 2026-10-24
Branche : `feat/SF-208-02-versions-et-direct` — cadrage : décisions 4, 5, 6. Dépend de SF-208-01 (mergée).

## Objectif
L'écran peut lire, pour chaque chantier ouvert, le plan complet avec ce qui bloque chaque étape, l'historique des versions du plan avec ce qui a changé, et il est prévenu pendant le tour dès que l'agent touche au parcours.

## Comportement nominal
- **Historique** : table `subject_journey_plan_versions` (migration **166**) — une ligne par `set_subject_plan` accepté (`user_id`, `workspace_id`, `chantier_number`, `version`, `plan_json`, `created_at`). Écrite par `SubjectJourneyService.setPlan` (focus ou chantier visé), best-effort : un historique qui casse ne casse pas le plan.
- **Route** `GET /workspaces/{id}/journey/chantiers/{n}/versions` : les versions du chantier N, de la plus ancienne à la plus récente, chacune avec `version`, `createdAt`, `stepCount`, et ce qui a changé par rapport à la précédente — `added`, `removed`, `modified` (titres d'étapes ; appariement par titre, « modifiée » = même titre, contenu différent : risque, vérification, retour arrière, attente, dépendances). Calculé côté serveur. La première version n'a que `added`. `requireOwned` d'abord (404 sur le terminal d'autrui), lecture sous `(user_id, workspace_id)`.
- **Tous les chantiers ouverts détaillés** : `GET /journey` → `chantiers[].plan` = le plan complet de chaque chantier ouvert (focus compris), même forme que `plan`. Chaque étape (`plan.steps[]` et `chantiers[].plan.steps[]`) gagne : `dependsOn` (rangs déclarés), `unlocked` (toutes ses dépendances faites/vérifiées — déclarées, ou à défaut toutes les étapes précédentes — et son attente n'est pas ouverte), `blockedBy` (libellés : « étape 3 », « attente cert-gino »), `inProgress`, `subSteps[{title, done, evidence}]`. Les états des attentes sont lus pour les clés des plans de **tous** les chantiers ouverts.
- **Direct** : nouvel événement SSE `journey` `{tool, chantier}` émis par la boucle après chaque outil du parcours **appliqué sans erreur** pendant le tour (plan posé/amendé, étape, sous-étapes, diagnostic, retour en investigation, proposition). Aucun contenu, l'écran relit `GET /journey`. Les écrans d'avant ignorent l'événement.
- Purge du compte : `purgeUser` nommée (comme les chantiers) ; cascade sur le terminal.

## Cas d'erreur
| Situation | Comportement attendu | Code |
|---|---|---|
| Terminal d'autrui | introuvable | 404 |
| Chantier sans historique (plans d'avant F-208) | liste vide | 200 |
| Outil du parcours en erreur | aucun événement `journey` | — |
| Écriture de l'historique en échec | journalisée en debug, plan posé quand même | — |

## Critères d'acceptation
- [ ] CA1 Deux `set_subject_plan` successifs ⇒ deux versions ; la seconde liste les étapes ajoutées, retirées et modifiées.
- [ ] CA2 `GET /journey` rend le plan détaillé de chaque chantier ouvert (focus et non focus), avec `unlocked`, `blockedBy`, `dependsOn`, `subSteps`, `inProgress`.
- [ ] CA3 Une étape dont une dépendance n'est pas faite est `unlocked=false` avec `blockedBy=["étape N"]` ; une étape dont l'attente est ouverte porte `attente <clé>`.
- [ ] CA4 Bob reçoit 404 sur `/chantiers/{n}/versions` du terminal d'Alice ; les lignes portent `user_id`.
- [ ] CA5 La boucle émet `journey` après un outil du parcours réussi, rien après un échec.
- [ ] CA6 Non-régression : `JourneyGate*`, parcours, chantiers, suite complète verts.

## Plan de test
- `JourneyPlanTest` : `pendingDependencies` (déclarées, implicites), diff de versions (ajout, retrait, modification).
- `SubjectJourneyChantiersIntegrationTest` : versions de bout en bout + isolation 404 (Bob) ; `chantiers[].plan` détaillé pour le focus et un chantier non focus ; `unlocked/blockedBy`.
- `AtelierChatServiceJourneyTest` : événement `journey` émis après un outil réussi, absent après un refus.
- Isolation utilisateur : test 404 sur la route versions.

## Préoccupations transversales
- **Contexte tenant** : coché. Composants impactés : `SubjectJourneyService.planVersions` (requireOwned + filtre user_id/workspace_id), `SubjectJourneyPlanVersionRepository` (toutes les requêtes portent `user_id`), `SubjectJourneyService.setPlan` (écrit `user_id` du parcours possédé), `AccountService` (purge nommée). Aucun autre composant ne résout le tenant.
- Auth : non. Plans/limites : non. Navigation : non (aucune route Angular).

## Impacts
- Migration `166-subject-journey-plan-versions.xml`, entité + dépôt `SubjectJourneyPlanVersion`, `SubjectJourneyService`, `SubjectJourneyController`, `SubjectJourneyResponse` (Step, OpenChantier.plan, PlanVersion), `JourneyPlan` (dépendances en attente, diff), `AtelierProgressListener.onJourney`, `AtelierChatService` (émission), `AtelierChatController` (relais SSE `journey`), `AccountService` (purge). `ARCHITECTURE_CANONIQUE.md` : nouvelle table à l'étape 6.

## Hors périmètre
Écran (SF-208-03/04). Restauration d'une ancienne version.
