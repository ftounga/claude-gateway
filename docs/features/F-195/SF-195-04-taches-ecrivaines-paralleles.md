# SF-195-04 — Sous-agents écrivains en parallèle

**Feature parente :** F-195 Plusieurs sujets à la fois · **Type :** backend · **Estimation :** 1 j

## Objectif
Plusieurs `task` écrivains émis dans une même réponse tournent ensemble, chacun dans son worktree, et le plafond de délégations vaut par demande.

## Comportement nominal
- Une suite de `task` écrivains consécutifs d'une même réponse est menée en parallèle, bornée par `app.atelier.task-parallelism` (défaut 3 ; 0/1 = l'une après l'autre, coupe-circuit) et par le reste du plafond de délégations.
- Chaque sous-tâche garde SON worktree et SA branche (F-150) : rien n'est écrit dans la copie de travail réelle, rien n'est écrasé. Création, restitution et démontage du worktree passent un par un (verrou par projet : deux `git worktree add` simultanés se disputent les verrous du dépôt) ; les sous-boucles tournent en parallèle.
- Synthèses rattachées dans l'**ordre d'émission**. Deux sous-tâches qui ont modifié les mêmes fichiers (résumé `diff --stat`) : la seconde le dit (« Attention : une autre sous-tâche de ce tour a aussi modifié … réconcilie-les avant de les intégrer, rien n'a été écrasé »).
- Invites d'autorisation des sous-tâches : relayées **une à la fois** (verrou par projet sur `askPermission`) ; les sorties de commandes des sous-tâches parallèles ne sont pas diffusées (elles s'entremêleraient) — la synthèse de chacune l'est, dans l'ordre.
- **Plafond de délégations par demande** : `max-delegations` × (1 + lots d'interjections pris en compte dans le tour). Arbitrage : « par demande » plutôt que « relevé », pour ne pas ouvrir la vanne du coût d'un message simple.
- Description de l'outil `task` : quand le parallèle est actif, elle dit de grouper les sous-tâches écrivaines indépendantes (condition stable par réglage, cache F-134 préservé).

## Cas limites / erreurs
- Panne d'une sous-tâche ⇒ SON résultat en erreur, les autres intactes.
- Plafond atteint au milieu d'un groupe ⇒ le groupe est tronqué, les suivantes reçoivent « Limite de délégations atteinte ».
- `task` en lecture seule : inchangé (pré-passage SF-121-14).

## Critères d'acceptation
1. Deux `task` écrivains : 2 sous-boucles en vol ensemble ; résultats tA puis tB.
2. Même fichier touché ⇒ avertissement sur la seconde.
3. `task-parallelism: 0` ⇒ une à la fois.
4. Plafond 1 : la 2e reçoit la limite ; après une interjection, une nouvelle délégation est acceptée.

## Plan de test
- `AtelierChatServiceParallelTasksTest` (5 cas) ; suite backend complète.
- Isolation utilisateur : verrous et tâches clés `(userId, workspaceId)` ; aucun nouvel accès données.

## Impacts
- `AtelierChatService` uniquement. Aucune table, aucun endpoint. Préoccupations transversales : aucune.

## Hors périmètre
- Fusion automatique des branches (toujours un acte explicite, F-150-05).
