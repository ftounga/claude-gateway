# Mini-spec — [F-220 / SF-220-02] Le tableau du client (API)

## Identifiant
SF-220-02

## Feature parente
F-220 — Le tableau de bord du client

## Statut
in-progress

## Date de création
2026-10-27

## Branche Git
`feat/SF-220-02-tableau-client`

---

## Objectif
Servir en une lecture tous les chantiers ouverts de tous les sujets d'un poste (le client), chacun avec qui le débloque, son ancienneté, sa prochaine étape, ce qui lui manque, son dernier geste et sa course autonome.

## Comportement attendu

### Cas nominal
`GET /api/runner-hosts/{hostId}/chantiers` →
```json
{
  "hostId": "…", "hostName": "EDENRED",
  "generatedAt": "…",
  "dormantAfterDays": 2,
  "counts": { "outil": 3, "moi": 2, "autrui": 4, "live": 1, "dormant": 5 },
  "autonomy": { "running": 1, "maxParallel": 4 },
  "chantiers": [ {
    "workspaceId": "…", "workspaceName": "ingress-prod", "hostTerminal": false,
    "number": 2, "title": "Certificat ingress", "phase": "EXECUTION", "phaseLabel": "Exécution", "focus": true,
    "openedAt": "…", "done": 3, "total": 7,
    "nextStep": { "rank": 4, "title": "Remplacer le certificat" },
    "unblocker": { "who": "AUTRUI", "label": "On attend quelqu'un", "detail": "…", "step": 4, "waitingOn": "Gino", "since": "…" },
    "ready": false,
    "missing": [ "Étape 4 : en attente de « cert-gino » (demandé)." ],
    "lastActivityAt": "…", "idleDays": 6, "dormant": true,
    "live": false,
    "question": null,
    "run": { …RunView F-219… } | null
  } ]
}
```
- **Sujets** : les projets du poste (`listByHost`) **et** le terminal du poste ; jamais le terminal Teams. Chaque sujet guidé contribue ses chantiers ouverts (focus + autres, F-206).
- **`unblocker`** : celui de SF-220-01 (même calcul), question en suspens comprise pour le chantier au focus.
- **`ready` / `missing`** : le préflight F-219 du chantier (`missing` = libellés des raisons ; vide si prêt).
- **`nextStep`** : la première étape ni faite ni vérifiée, ou `null`.
- **Ancienneté** : `lastActivityAt` = la plus récente de la mise à jour du chantier et, pour le chantier au focus, du dernier message du sujet ; `idleDays` = jours entiers écoulés ; `dormant` = `idleDays ≥ dormantAfterDays` (réglage `app.client-board.dormant-days`, défaut 2).
- **`live`** : un tour vivant sur le sujet (chantier au focus) **ou** une course autonome en cours sur ce chantier.
- **`question`** : le texte de la première question en suspens (ou « Autorisation demandée : <outil> ») pour le chantier au focus ; sinon `null`.
- **`run`** : la course autonome en cours de ce chantier, sinon sa dernière (F-219 `RunView`, coût compris) ; `null` s'il n'a jamais couru.
- **Ordre** : Outil, puis Ma décision, puis Quelqu'un ; dans chaque groupe le plus dormant d'abord.
- **`counts`** par couleur, chantiers vivants et dormants ; `autonomy` = courses en cours du compte et plafond (F-219).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste d'autrui ou inconnu | `RunnerHostService.requireOwned` d'abord, aucune donnée | 404 |
| Poste sans sujet guidé | `chantiers: []`, compteurs à zéro | 200 |
| Attentes, tour vivant ou courses illisibles | best-effort, le chantier reste listé | 200 |
| Non authentifié | refus | 401 |

## Critères d'acceptation
1. Deux sujets guidés d'un même poste, trois chantiers ouverts au total → les trois sont listés, chacun avec `workspaceName`, `unblocker`, `done/total`, `nextStep`.
2. Ordre : `OUTIL` avant `MOI` avant `AUTRUI` ; dans un groupe, `idleDays` décroissant.
3. Chantier mis à jour il y a 6 jours, sans message récent → `idleDays = 6`, `dormant = true`, compté dans `counts.dormant`.
4. Préflight vert → `ready = true`, `missing = []` ; préflight rouge → `missing` = libellés des raisons.
5. Question en suspens sur le tour vivant du sujet → chantier au focus `unblocker.who = MOI`, `question` = le texte, `live = true`.
6. Un sujet d'un **autre poste** n'apparaît pas ; le terminal Teams n'apparaît pas.
7. Bob → 404 sur le poste d'Alice.

## Périmètre
### Hors scope (explicite)
- Lancer, clore, reporter : routes existantes (F-219, F-176) ou SF-220-05.
- L'écran (SF-220-03), la pastille dans le sujet (SF-220-04), le rappel des dormants (SF-220-05).
- Le coût par chantier hors course autonome (non attribuable, arbitrage 6 du cadrage).

## Valeurs initiales
Aucune (lecture seule).

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| `hostId` | UUID d'un poste possédé |
| `app.client-board.dormant-days` | entier ≥ 1 (défaut 2) |
| `question` | 300 caractères au plus |

## Technique
### Endpoint(s)
| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/runner-hosts/{hostId}/chantiers` | JWT | USER |

### Tables impactées
| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_hosts` | SELECT | `id` + `user_id` |
| `workspaces` | SELECT | `user_id` + `host_id` |
| `subject_journeys`, `subject_journey_chantiers` | SELECT | `user_id` + `workspace_id` |
| `terminal_actions` | SELECT | `user_id`, via `actionOfKey` |
| `atelier_messages` | SELECT | dernière activité, `user_id` |
| `chantier_autonomy_runs` | SELECT | `user_id` |

### Migration Liquibase
- [x] Non applicable

### Composants
- `atelier/dashboard/ClientBoardService` (agrégation), `ClientBoardController` (route), `ClientBoard` (réponse).

## Préoccupations transversales
- Contexte tenant : **oui** (agrégation sur un poste). Composants qui résolvent le poste : `RunnerHostService.requireOwned` (seule résolution) ; sujets par `WorkspaceService.listByHost` / `findHostTerminal` (déjà filtrés `user_id` + `host_id`) ; parcours, attentes, messages, courses : tous lus sous `user_id`. Test d'isolation Bob → 404 et test « autre poste absent ».
- Auth, plans/limites, navigation : non.

## Plan de test
### Tests unitaires
- `ClientBoardServiceTest` : ordre des groupes et ancienneté ; `idleDays`/`dormant` à partir d'une horloge fixe ; extraction du texte de la question.
### Tests d'intégration
- `ClientBoardIntegrationTest` : critères 1, 3 (horloge réelle : mise à jour forcée en base), 4, 5, 6, 7.
### Isolation utilisateur
- [x] Applicable — Bob → 404 ; sujet d'un autre poste d'Alice absent.

## Dépendances
- SF-220-01 — Done (PR #1315). F-219 — Done.
- Questions ouvertes : aucune.

## Notes et décisions
- Préflight par `ChantierPreflightService.forOwned` (même calcul que F-219, poste déjà vérifié).
