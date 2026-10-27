# Mini-spec — [F-219 / SF-219-01] Le contrôle avant départ

## Identifiant
SF-219-01

## Feature parente
F-219 — Exécuter un chantier en autonome

## Statut
Done

## Date de création
2026-10-27

## Branche Git
`feat/SF-219-01-preflight`

---

## Objectif
Calculer côté serveur, pour chaque chantier ouvert, s'il est « Prêt à partir » en autonome — ou la liste précise de ce qui l'empêche — et donner à l'agent un statut d'étape `BLOQUE` pour dire un vrai blocage.

## Comportement attendu

### Cas nominal
- **Statut `BLOQUE`** : `update_plan_step status=BLOQUE evidence=<raison>` marque l'étape bloquée (raison obligatoire). `status=A_FAIRE` la débloque (sans preuve). Une étape `BLOQUE` n'est ni faite ni « à faire » : le chantier reste en Exécution tant qu'elle existe. Le résultat de l'outil dit à l'agent de s'arrêter et d'expliquer.
- **Préflight** d'un chantier ouvert (focus ou non) : `ready=true` si et seulement si :
  1. le sujet est guidé et le chantier ouvert ;
  2. le plan est **validé à sa version courante** (aucun amendement en attente) ;
  3. le chantier est en **Exécution** ou en **Vérification** ;
  4. il reste au moins une étape non vérifiée ;
  5. aucune étape `BLOQUE` ni `ECHEC` ;
  6. aucune étape restante n'attend une attente F-175 ouverte (`A_FAIRE` / `DEMANDE`) ;
  7. aucune étape restante n'attend une information **non suivie** (`waits_on` sans attente enregistrée) ;
  8. aucune étape restante ne dépend (`depends_on`) d'une étape bloquée ou en échec ;
  9. aucune question ni demande d'autorisation n'est en suspens sur le tour vivant du sujet.
- Sinon `ready=false` et `reasons` liste **toutes** les raisons, chacune `{code, label, step}` (`step` = rang de l'étape concernée ou `null`). Libellés en français, lisibles tels quels par l'écran.
- `GET /journey/autonomy` rend le préflight de **chaque** chantier ouvert du sujet, par numéro croissant.

### Cas d'erreur
- Terminal d'autrui → 404 (`requireOwned` en premier), aucune donnée.
- Chantier non ouvert (numéro inconnu ou clos) → 400 `journey_invalid` (« Le chantier N n'est pas ouvert. »), comme les autres routes par chantier.
- `BLOQUE` sans raison → erreur outil « une raison est requise », rien n'est enregistré.
- Statut inconnu → erreur outil (liste des statuts admis).
- Attentes illisibles (service absent / erreur) → best-effort : la clé est traitée comme **non suivie** (prudence : non prêt).

## Critères d'acceptation
1. Un chantier en Exécution, plan validé courant, sans attente ni blocage → `ready=true`, `reasons=[]`.
2. Plan amendé non revalidé → `PLAN_NOT_VALIDATED`.
3. Chantier en Investigation ou en Plan → `NOT_EXECUTING` (ou `PLAN_NOT_VALIDATED` sans plan validé).
4. Étape `BLOQUE` → `STEP_BLOCKED` avec la raison dans le libellé ; étape `ECHEC` → `STEP_FAILED`.
5. Étape restante `waits_on=k` avec attente `DEMANDE` → `WAIT_OPEN` ; sans attente → `MISSING_INFO` ; attente `FAIT` → aucune raison.
6. Étape restante dépendant d'une étape `BLOQUE` → `DEPENDENCY_BLOCKED`.
7. Question en suspens sur le tour vivant → `QUESTION_PENDING`.
8. Toutes étapes vérifiées → `NOTHING_LEFT`.
9. `update_plan_step BLOQUE` sans preuve → erreur ; avec preuve → étape `BLOQUE`, phase inchangée.
10. Bob → 404 sur les deux routes de lecture d'Alice.

## Périmètre
### Hors scope (explicite)
- Le lancement, la boucle, l'arrêt, les notifications (SF-219-02).
- L'écran (SF-219-03), l'outil de lancement par l'agent (SF-219-04).
- L'état « poste hors ligne » et « tour en cours » : conditions **de départ**, pas du plan — vérifiées au lancement (SF-219-02).

## Valeurs initiales
Aucune.

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| `status` (outil) | `FAIT`, `VERIFIE`, `ECHEC`, `BLOQUE`, `A_FAIRE` |
| `evidence` | requise pour `VERIFIE`, `ECHEC`, `BLOQUE` ; 500 caractères au plus (borne existante) |
| `n` (route) | entier ≥ 1, chantier ouvert |

## Technique
### Endpoint(s)
- `GET /api/workspaces/{ws}/journey/chantiers/{n}/autonomy/preflight` → `ChantierPreflight`
- `GET /api/workspaces/{ws}/journey/autonomy` → `{ chantiers: [{ number, title, preflight }] }` (SF-219-02 y ajoute `run`, `estimate`, `running`, `maxParallel`)

### Tables impactées
Aucune (le statut `BLOQUE` vit dans le JSON du plan existant).

### Migration Liquibase
Aucune.

### Composants
- `JourneyPlan.StepStatus.BLOQUE` ; `SubjectJourneyService.updateStep` / `parseStepStatus` ; `JourneyToolCatalog` (enum + description + une phrase du GUIDE) ; `JourneyToolExecutor` (issue `BLOCKED`, `REOPENED_STEP`) ; `ChantierAnchor` (une étape bloquée reste à faire, non actionnable).
- Nouveaux : `ChantierPreflight` (calcul pur), `ChantierPreflightService`, `ChantierAutonomyController`.

### Préoccupation transversale — aucune
Ni auth, ni tenant, ni plans/limites, ni navigation. Littéraux d'outils modifiés une fois (préfixe stable : changement ponctuel, non volatil).

## Plan de test
### Tests unitaires
- `ChantierPreflightTest` : prêt ; amendement ; phase ; BLOQUE ; ECHEC ; WAIT_OPEN / MISSING_INFO / attente FAIT ; DEPENDENCY_BLOCKED ; QUESTION_PENDING ; NOTHING_LEFT ; plusieurs raisons à la fois.
- `JourneyPlanTest` : `BLOQUE` relu / écrit.
### Tests d'intégration
- `ChantierAutonomyPreflightIntegrationTest` : préflight prêt ; `BLOQUE` via l'outil (sans preuve → erreur ; avec → raison) ; débloquer `A_FAIRE` ; route liste ; chantier non ouvert → 400.
### Isolation utilisateur
- Bob → 404 sur `/autonomy/preflight` et `/autonomy` du terminal d'Alice.

## Dépendances
F-208 (plan détaillé), F-206 (chantiers ouverts), F-175 (attentes), F-164 (question état du tour). F-218 mergée.
