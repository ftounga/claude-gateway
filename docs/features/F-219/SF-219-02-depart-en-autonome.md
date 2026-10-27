# Mini-spec — [F-219 / SF-219-02] Le départ en autonome

## Identifiant
SF-219-02

## Feature parente
F-219 — Exécuter un chantier en autonome

## Statut
Done

## Date de création
2026-10-27

## Branche Git
`feat/SF-219-02-depart-autonome`

---

## Objectif
Lancer un chantier prêt en autonome : la gateway enchaîne, sans écran, les tours de l'agent jusqu'à la clôture proposée, s'arrête proprement sur un vrai blocage, notifie le PO, et lui rend la main sur demande.

## Comportement attendu

### Cas nominal
- `POST /journey/chantiers/{n}/autonomy` : préflight relu (SF-219-01) ; s'il est vert et que le départ est possible, le chantier passe **au focus**, une **course** `RUNNING` est écrite et la boucle part en tâche de fond → `201` + la course.
- **La boucle** réutilise le tour sans écran des routines (F-183 : `LiveTurnRegistry.openIfIdle` + `AtelierChatService.chatStreaming` + écouteur headless) — même modèle, même raisonnement, mêmes outils qu'au clavier. Elle enchaîne **au plus `app.autonomy.max-turns` (8)** tours. La consigne d'autonomie (« le plan validé vaut feu vert, aucune question, `BLOQUE` + raison sur un vrai blocage, rien sans preuve, `chantier: N` ») et la liste des étapes restantes sont dans le **message** de chaque tour (jamais dans la consigne système).
- Après chaque tour, la gateway relit le plan :
  - toutes les étapes vérifiées (ou clôture proposée, ou chantier clos) → `DONE` ;
  - préflight rouge (étape `BLOQUE` / `ECHEC`, nouvelle attente, information manquante, amendement, retour en Investigation) → `BLOCKED`, raison = la première raison du préflight ;
  - aucune avancée du plan sur un tour complet → `BLOCKED` « Aucune avancée sur un tour complet » ;
  - sinon tour suivant (« Poursuis… étapes restantes »).
  - plafond de tours atteint → `STOPPED` « Plafond de 8 tours atteint ».
- **Reprendre la main** (`POST /journey/autonomy/stop`) : la course passe `STOPPED` (« Reprise en main ») **immédiatement** en base, le tour est interrompu (même geste que le bouton Interrompre, relayé entre pods) ; la boucle s'arrête à la frontière sûre suivante sans réécrire l'issue.
- **Notifications** (catalogue F-185, sonnantes F-217) : `AUTONOMY_DONE` « Un chantier autonome est terminé » ; `AUTONOMY_STOPPED` « Un chantier autonome s'est arrêté » (attend une action) sur `BLOCKED`, `FAILED` et plafond. Rien sur une reprise en main (le PO est devant). La notification de fin de tour générique est tue pendant la course.
- **Estimation** : `estimate = { usd, turns, basis }` — coût moyen d'un tour de l'utilisateur sur 30 jours (`usage_turns.provider_cost_usd`) × tours estimés (`max(1, ⌈restantes / 2⌉)`, borné par le plafond). Absente sans mesure. Aucune confirmation.
- `GET /journey/autonomy` (étend SF-219-01) : chaque chantier ouvert avec `preflight`, `run` (la course en cours ou la dernière), `estimate` ; plus `running` (courses en cours du compte) et `maxParallel` (4).
- `GET /api/atelier/autonomy/runs` : les courses du compte, en cours d'abord, puis les 20 dernières (F-220).
- **Course orpheline** (pod arrêté) : toutes les 5 min, une course `RUNNING` sans signe de vie depuis `app.autonomy.stale-after` (PT3H) passe `FAILED` (`orphaned`) et notifie.

### Cas d'erreur
- Préflight rouge → `409 autonomy_not_ready` + `reasons`.
- Une course `RUNNING` sur le sujet, ou un tour vivant → `409 autonomy_subject_busy` (« un chantier autonome par sujet à la fois »).
- 4 courses en cours sur le compte → `409 autonomy_limit_reached` (« Quatre chantiers autonomes au maximum en même temps : attendez qu'un finisse ou reprenez la main sur l'un d'eux. »).
- Poste du sujet hors ligne → `409 autonomy_host_offline`.
- Pool de tâches saturé → course `FAILED` (`executor_busy`), tour refermé.
- Erreur fournisseur / quota / BYOK pendant la course → `FAILED` + code (comme les routines), notifiée.
- Poste perdu pendant la course → `FAILED` `runner_offline`.
- Stop sans course en cours → `409 autonomy_not_running`.
- Terminal d'autrui → 404 sur toutes les routes.

## Critères d'acceptation
1. Chantier prêt + sujet libre → `201`, course `RUNNING`, chantier au focus, tour lancé avec la consigne d'autonomie et `chantier: N`.
2. Deux tours suffisent : le 1er avance, le 2e vérifie tout → `DONE`, 2 appels au modèle, coût cumulé, `AUTONOMY_DONE`.
3. L'agent pose `BLOQUE` → course `BLOCKED` avec la raison, `AUTONOMY_STOPPED`, aucun tour de plus.
4. Un tour sans avancée → `BLOCKED` « Aucune avancée… ».
5. Plafond atteint → `STOPPED` + notification.
6. Reprendre la main → `STOPPED` « Reprise en main », interruption demandée, aucune notification, l'issue n'est pas réécrite par la boucle.
7. `409` : non prêt (avec raisons), sujet occupé, limite de 4, poste hors ligne.
8. Bob : 404 sur launch, stop, board ; `/atelier/autonomy/runs` ne rend que ses propres courses.

## Périmètre
### Hors scope (explicite)
- L'écran (SF-219-03), l'outil de lancement par l'agent (SF-219-04).
- Un plafond de dépense par course : non (PO : pas de confirmation ; le quota du compte s'applique).
- Le tableau de bord du client (F-220).

## Valeurs initiales
`app.autonomy.max-turns=8`, `app.autonomy.max-parallel=4`, `app.autonomy.stale-after=PT3H`, `app.autonomy.enabled=true` (tâche de fond).

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| `n` | chantier ouvert du sujet |
| `stop_reason` | 300 caractères au plus |
| `status` | `RUNNING`, `DONE`, `BLOCKED`, `STOPPED`, `FAILED` |

## Technique
### Endpoint(s)
- `POST /api/workspaces/{ws}/journey/chantiers/{n}/autonomy` → `201 AutonomyRunView`
- `POST /api/workspaces/{ws}/journey/autonomy/stop` → `200 AutonomyRunView`
- `GET /api/workspaces/{ws}/journey/autonomy` → `AutonomyBoard` (déplacé de `ChantierAutonomyController` vers `AutonomyController`, contrat étendu)
- `GET /api/atelier/autonomy/runs` → `List<AutonomyRunView>`

### Tables impactées
Nouvelle table `chantier_autonomy_runs` : `id`, `user_id`, `workspace_id` (FK cascade), `chantier_number`, `chantier_title`, `status`, `turns`, `steps_done`, `steps_total`, `cost_usd`, `stop_reason`, `error_code`, `turn_id`, `started_at`, `heartbeat_at`, `finished_at`. Index `(status, heartbeat_at)`, `(user_id, status)`, `(workspace_id, started_at)`.

### Migration Liquibase
`175-chantier-autonomy-runs.xml` (rollback : drop table).

### Composants
Paquet `fr.claudegateway.atelier.autonomy` : `ChantierAutonomyRun`, `ChantierAutonomyRunRepository`, `AutonomyRunStatus`, `AutonomyService`, `AutonomyTurnRunner`, `AutonomyNotifier`, `AutonomyStaleWorker`, `AutonomyController`, `AutonomyConflictException` + handler. `PushEvent` : `AUTONOMY_DONE`, `AUTONOMY_STOPPED`. `UsageTurnRepository.averageCostSince`.

### Préoccupation transversale — Plans / limites
Composants impactés et vérifiés : le quota du compte (`QuotaExceededException` dans la boucle → `FAILED quota_exceeded`, inchangé) ; la limite des 4 terminaux (F-70) **n'est pas** consommée (pas d'onglet) — la limite de 4 **courses** est propre à F-219 et tenue en base (`countByUserIdAndStatus`). Aucun autre appel aux services de limites.

## Plan de test
### Tests unitaires
- `AutonomyTurnRunnerTest` : DONE en 2 tours ; BLOCKED sur `BLOQUE` ; aucune avancée ; plafond ; interruption ; erreur fournisseur → FAILED ; poste perdu ; consigne (chantier N, étapes restantes, pas de question).
- `AutonomyServiceTest` : estimation (avec / sans mesure).
### Tests d'intégration
- `AutonomyApiIntegrationTest` (boucle sur exécuteur synchrone, modèle simulé) : lancement prêt → course + focus ; non prêt → 409 + raisons ; sujet occupé ; limite de 4 ; stop ; board étendu ; runs ; course orpheline fermée.
### Isolation utilisateur
- Bob : 404 sur launch / stop / board d'Alice ; `/atelier/autonomy/runs` vide pour Bob.

## Dépendances
SF-219-01 (mergée), F-183 (tour sans écran), F-185/F-217 (notifications), F-84 (tour vivant), F-206 (focus).
