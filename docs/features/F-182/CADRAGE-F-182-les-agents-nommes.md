# Cadrage — F-182 Les agents nommés (sous-agents personnalisés)

> Suite de l'audit de parité du 2026-10-06 (`docs/audits/AUDIT-2026-10-06-terminal-central-gouvernance-parite.md` §6).
> Claude Code permet de déclarer des sous-agents (`.claude/agents/<nom>.md` : consigne, outils
> autorisés, modèle). Chez nous, `explore` (lecture) et `task` (écriture, worktree F-150) sont génériques.

## 1. Objectif

Disposer, par client ou par sujet, d'agents **spécialisés et réutilisables** — « relecteur » avant une
MR, « testeur » qui écrit et lance les tests, « auditeur infra » qui vérifie un plan Terraform — que
l'agent principal délègue, ou que le PO appelle par `/agent relecteur`.

## 2. Décisions (par défaut, réversibles)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Fichier `.claude/agents/<nom>.md`** sur le poste (poste puis sujet), entête : `description`, `outils` (sous-ensemble de lecture ou d'écriture), `modele` (défaut = celui de la Forge). Découverts et annoncés comme les skills (catalogue dans le message, plafonné). | Parité, même mécanisme que les skills |
| D2 | **`explore` et `task` reçoivent `agent: "<nom>"`** : la consigne et la liste d'outils de l'agent nommé remplacent celles par défaut ; `task` garde l'isolation worktree. | Réutiliser l'existant |
| D3 | **Modèle** : Opus par défaut ; un modèle moins cher seulement s'il est **écrit** dans le fichier et justifié (règle absolue « justesse avant coût »). | Mémoire PO 2026-09-30 |
| D4 | **Gabarits** fournis dans le paquet `savoir-durable` : `relecteur`, `testeur`, `auditeur-infra` (généralise le juge indépendant `governance/juge/`). Création depuis le terminal par `gouvernance_proposer(type: AGENT)` (F-177). | Démarrer vite |

## 3. Découpage

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-182-01 | Déclarer un agent | D1, D3 (lecture, validation, catalogue, écran Gouvernance). | 1,5 j |
| SF-182-02 | Déléguer à un agent nommé | D2 (+ `/agent <nom>` dans la saisie). | 1,5 j |
| SF-182-03 | Les trois gabarits | D4. | 1 j |

## 4. Préoccupations transversales

Aucune (scope poste/sujet existant, isolation inchangée). Composants : `AtelierChatService` (explore/task), catalogue des skills, `GovernancePackageSeeder`.

## 5. Hors périmètre

Workflows multi-agents orchestrés (réserve, après F-181/F-182) ; agents exécutés côté serveur.
