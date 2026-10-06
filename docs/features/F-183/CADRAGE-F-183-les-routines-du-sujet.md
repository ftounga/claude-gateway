# Cadrage — F-183 Les routines du sujet (tâches programmées)

> Suite de l'audit de parité du 2026-10-06 (`docs/audits/AUDIT-2026-10-06-terminal-central-gouvernance-parite.md` §6).
> Claude Code a `/loop` et `/schedule` ; chez nous, seuls des workers **système** tournent à heure fixe
> (synchro du soir du Radar F-100). L'utilisateur ne peut rien programmer.

## 1. Objectif

Programmer, par sujet, un travail récurrent exécuté **sans écran ouvert** : « chaque matin à 8 h, fais
le point sur les tickets et MR de CAGIP et mets à jour mes attentes », « le vendredi à 17 h, prépare le
compte rendu de la semaine ».

## 2. Décisions (par défaut, réversibles)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Routine** = `{sujet, consigne, rythme (quotidien / jours ouvrés / hebdo + heure, fuseau du poste), budget max par exécution, actif}`. Créée depuis l'écran du sujet ou proposée par l'agent (carte de validation). | Simple, pas de cron libre |
| D2 | **Exécution** : un worker planifié démarre un tour **dans le terminal du sujet** (mécanisme F-84, tour sans écran), poste requis en ligne ; sinon « manquée — poste hors ligne » et rattrapage à la reconnexion (une seule fois). | Le travail se fait chez le client |
| D3 | **Plafond** : budget en dollars par exécution (coupe nette, tour INCOMPLET) + budget mensuel par routine ; affiché dans `/cout` (F-165). | Tours sans humain = risque coût |
| D4 | **Résultat** : notification push (F-153) + ligne « Routine du matin — 3 attentes mises à jour » en tête du fil du sujet ; les attentes et le plan sont mis à jour par les outils existants. | Valeur visible |
| D5 | **Pas de déploiement pendant une routine** : `deploy2.sh` consulte les routines en cours (même vérification que `runner_audit`). | Un rollout tue le tour |

## 3. Découpage

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-183-01 | Déclarer une routine | D1 (table, API, écran). | 1,5 j |
| SF-183-02 | Elle tourne seule | D2, D5 (worker, démarrage de tour, rattrapage). | 2 j |
| SF-183-03 | Elle reste dans son budget | D3. | 1 j |
| SF-183-04 | Elle rend compte | D4. | 1 j |

## 4. Préoccupations transversales

- **Plans / limites** : ✔ — consommation hors présence humaine ; composants : `QuotaWindowService`, `CostBudget`, `UsageLedgerService`, garde de quota du tour.
- **Tenant** : routine liée au `user_id` + `workspace_id`, vérifiée à chaque exécution.

## 5. Hors périmètre

Rythmes libres (cron) ; routines au poste (le poste aiguille, il ne travaille pas) ; exécution hébergée sans runner.
