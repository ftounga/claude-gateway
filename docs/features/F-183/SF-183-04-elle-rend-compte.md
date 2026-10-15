# Mini-spec — F-183 / SF-183-04 Elle rend compte

## Identifiant
`F-183 / SF-183-04` — Feature parente : F-183 « Les routines du sujet » (décision **D4**).
Statut : `ready` — 2026-10-16 — Branche `feat/SF-183-04-elle-rend-compte`.

## Objectif
À la fin d'une exécution, l'utilisateur est **notifié par push** (F-153) et trouve **en tête du fil du sujet** une ligne « Routine du matin — 3 attentes mises à jour » ; les attentes et le plan ont été mis à jour par les outils existants pendant le tour.

## Comportement attendu

### Cas nominal
1. **Compte rendu de l'exécution** (`RoutineReport`, calcul pur + une lecture) : à la fin du tour, la gateway compte les **attentes du sujet** créées ou modifiées pendant l'exécution (`terminal_actions.updated_at ∈ [started_at, finished_at]`, filtre `user_id` + `workspace_id`) et écrit dans `routine_runs` : `attentes_updated` et `summary` = « Nom — 3 attentes mises à jour » / « Nom — 1 attente mise à jour » / « Nom — terminée, aucune attente modifiée » ; suffixe « · INCOMPLET (budget atteint) » si `INCOMPLETE`, « · échec » si `FAILED`, « · manquée — poste hors ligne » pour `MISSED_OFFLINE`, « · non lancée — budget du mois atteint » pour `SKIPPED_BUDGET`. Migration 157 : `routine_runs.attentes_updated`, `routine_runs.acknowledged_at`.
2. **Notification push** (catalogue F-185, titres et corps **neutres**, sans nom ni contenu) :
   - `ROUTINE_DONE` « Une routine a rendu compte » / « Ouvrez le sujet pour lire son compte rendu. » ;
   - `ROUTINE_STOPPED` « Une routine s'est arrêtée » / « Ouvrez le sujet pour voir pourquoi. » (INCOMPLETE, FAILED, INTERRUPTED, SKIPPED_BUDGET) ;
   - `ROUTINE_MISSED` « Une routine n'a pas pu tourner » / « Le poste était hors ligne : elle repartira à sa reconnexion. ».
   Émis **même terminal regardé** (une routine n'est pas un tour que l'on suit) ; sourdine et heures calmes F-185 respectées (non critiques).
3. **Pas de doublon** : la notification générique de fin de tour (`TURN_DONE` / `WORK_STOPPED` …) est **tue** pour un tour de routine (`AtelierChatService.muteOutcomePush`, armé par le lanceur) — l'utilisateur reçoit **une** notification par exécution, celle de la routine. Les notifications critiques en cours de tour (autorisation, question) restent émises.
4. **En tête du fil** : `GET /api/workspaces/{workspaceId}/routines/runs/unseen` rend les exécutions closes non acquittées (≤ 5, plus récentes d'abord) ; la bande `app-terminal-routines-band` (au-dessus du fil, avec la carte de proposition de SF-01) affiche chaque ligne : icône d'état, `summary`, « il y a 2 h », coût ; [Voir] fait défiler jusqu'à la demande de la routine dans le fil ; [×] acquitte (`POST …/routines/runs/{runId}/ack` → 204). La bande se relit à l'ouverture du sujet et à chaque fin de tour.

### Contrat API (figé, additif)
```json
// RoutineRunView (+) "attentesUpdated": 3, "summary": "Point du matin — 3 attentes mises à jour",
//                    "routineName": "Point du matin", "acknowledgedAt": null
GET  /api/workspaces/{workspaceId}/routines/runs/unseen   → RoutineRunView[]
POST /api/workspaces/{workspaceId}/routines/runs/{runId}/ack → 204
```

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Exécution d'un autre utilisateur / sujet | `routine_not_found` | 404 |
| Ack déjà fait | idempotent | 204 |
| Push non configuré / abonnement absent | centre de notifications seulement (F-185), jamais d'erreur | — |
| Comptage des attentes en échec | `summary` sans compte (« Nom — terminée »), jamais d'échec de l'exécution | — |

## Contraintes de validation
`summary` ≤ 300 caractères ; bande ≤ 5 lignes ; corps de notification sans chiffre ni nom (garde existante F-185).

## Critères d'acceptation
- [ ] Une routine qui met à jour 3 attentes produit `summary` « Nom — 3 attentes mises à jour » et une notification `ROUTINE_DONE`, une seule (pas de `TURN_DONE`).
- [ ] Une routine coupée au budget produit `ROUTINE_STOPPED` et « · INCOMPLET (budget atteint) ».
- [ ] Une routine manquée produit `ROUTINE_MISSED` une fois par créneau.
- [ ] La bande montre la ligne en tête du fil ; [×] l'acquitte ; elle ne revient pas.
- [ ] Isolation : `unseen` et `ack` d'un autre utilisateur ⇒ 404 / liste vide.
- [ ] Les corps des nouveaux événements ne contiennent aucun chiffre ni nom.

## Plan de test
- Unitaires `RoutineReportTest` : pluriels, suffixes par statut, troncature.
- `RoutineTurnRunnerTest` (+) : push routine émis, générique tu (armement/désarmement), compte des attentes.
- `RoutinePlannerTest` (+) : `ROUTINE_MISSED` à la 1re détection seulement.
- `AtelierChatService` : `muteOutcomePush` supprime la notification de fin de tour et seulement elle.
- `PushEvent` : nouveaux événements non critiques, `alwaysDelivered`, corps sans chiffre.
- Intégration `RoutineRunApiIntegrationTest` (+) : `unseen`/`ack`, isolation.
- Front : `terminal-routines-band` (ligne, ack, voir), rafraîchissement à la fin de tour.

## Impacts
- Tables : `routine_runs.attentes_updated`, `routine_runs.acknowledged_at` — migration `157-routine-run-report.xml`.
- Endpoints : `GET …/routines/runs/unseen`, `POST …/routines/runs/{runId}/ack`.
- Composants : `PushEvent` (3 valeurs), `AtelierChatService` (sourdine du résultat par clé de tour), `RoutineTurnRunner`, `RoutinePlanner`, `RoutineReport`, `TerminalActionRepository` (comptage), front : bande, service.
- **Préoccupations transversales** : Tenant ☑ — `unseen`/`ack` filtrent `user_id` + `workspace_id` ; comptage des attentes par `user_id` + `workspace_id` ; Plans/limites ☐ ; Auth ☐ ; Navigation ☐ (défilement dans le terminal, aucune route).
- Provider Independence : aucune.

## Hors périmètre
Résumé rédigé par un second appel au modèle (interdit : le compte rendu est le tour lui-même) ; courriel de compte rendu ; notification nominative.
