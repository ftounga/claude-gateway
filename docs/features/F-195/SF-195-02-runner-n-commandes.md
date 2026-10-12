# SF-195-02 — Runner : plusieurs commandes à la fois

**Feature parente :** F-195 Plusieurs sujets à la fois · **Type :** runner + backend (lecture de capacité) · **Estimation :** 0,5 j

## Objectif
Le runner accepte N commandes `bash` de premier plan simultanées (défaut 4) et l'annonce, pour que la gateway puisse paralléliser.

## Comportement nominal
- `BashTool` : sémaphore à N jetons (défaut 4, réglable par `CLAUDE_RUNNER_BASH_CONCURRENCY`, ramené dans [1, 16]). Chaque appel a son `ToolContext` : sorties diffusées par `call_id`, sans mélange (le `ToolDispatcher` exécutait déjà chaque appel sur un worker dédié).
- Au-delà de N : refus `denied` + `BUSY_MESSAGE` **inchangé** (contrat F-188-02, test de contrat ajouté).
- Annonce dans `ready` : capacité `bash_parallel_<N>` (format accepté par `RunnerDeclaration` `[a-z0-9_-]{1,32}`, stocké dans `runner_hosts.runner_capabilities` existant — aucune migration).
- Gateway : `RunnerHostService.bashConcurrency(hostId)` lit la capacité ; absente (vieux runner) ⇒ 1 ; bornée à 16.

## Cas limites / erreurs
- Réglage illisible (`beaucoup`) ⇒ 4 ; `0` ou négatif ⇒ 1 ; `999` ⇒ 16.
- `--no-bash` ⇒ ni `bash` ni `bash_parallel_N` annoncés.
- Vieux runner (sans capacité) ⇒ gateway compte 1 : rien n'est envoyé en parallèle.
- Capacité forgée (`bash_parallel_x`) ⇒ écartée, 1.

## Critères d'acceptation
1. Borne 2 : deux commandes `sleep 1` finissent en < 1,9 s ensemble ; la 3e reçoit `BUSY_MESSAGE` ; chaque sortie reste dans son appel.
2. Borne 1 : comportement d'avant (2e refusée).
3. `ready` annonce `files, bash, bash_parallel_4` par défaut.
4. `bashConcurrency` rend 4 pour `bash_parallel_4`, 1 sans capacité, 16 au-delà.

## Plan de test
- Runner : `BashToolTest` (N simultanés, borne 1, résolution env, message de contrat), `ToolRouterTest`, `ProjectScopesTest`, `TeamsRoutingTest`, `ToolDispatcherTest` (annonce).
- Backend : `RunnerHostServiceTest.readsHowManyCommandsTheRunnerAcceptsAtOnce`.
- Isolation utilisateur : la lecture passe par `hostId` du workspace déjà possédé (inchangé).

## Impacts
- Runner : `BashTool`, `ToolRouter`, `ProjectScopes`. Backend : `RunnerHostService`.
- Aucune table, aucun endpoint. Préoccupations transversales : aucune.
- Version runner : identifiant de construction (stamp+commit) change à la construction ⇒ mise à jour d'un clic ; `CONTRACT` inchangé (aucune nouvelle trame).

## Hors périmètre
- L'envoi parallèle côté gateway (SF-195-03).
