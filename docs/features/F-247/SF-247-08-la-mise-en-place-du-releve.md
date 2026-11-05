# Mini-spec — F-247 / SF-247-08 La mise en place du relevé maximal

## Identifiant
`F-247 / SF-247-08`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.3 « Les sondes : tout ce qui se lit sans rien modifier », D5)

## Statut
`done` (2026-11-05) · **Estimation :** 1,5 j (réestimé 1 j) · **Branche :** `feat/SF-247-08-mise-en-place-releve` ·
**Dépend de :** SF-247-07

## Objectif
En un geste du PO, un tour d'agent unique fait l'inventaire de ce qui se lit sur le poste d'un client et propose une
sonde de relevé par famille disponible, que le PO valide toutes d'un geste — jamais une écriture.

## Comportement attendu

### Cas nominal
1. **`veille_poser(usage: "etat")`** (paramètre ajouté, heures devenues facultatives) : la proposition devient une
   sonde de relevé `purpose = STATE`, toutes les heures de 8 h à 20 h par défaut (13 heures au plus), plafond de 10 par
   sujet compté **par usage**. Sa réponse rappelle « Tout valider ».
2. **Verbes de lecture seulement** (`JarvisWatchProbeGuard.stateVerbProblem`), sur la commande **et** le script, en plus
   des contrôles F-230 (GET seulement, pas de mutation, pas de jeton en clair) : kubectl/oc (`get`, `describe`,
   `rollout status|history`, `config view`…), helm, argocd, glab/gh (2ᵉ mot), terraform (`output`, `show`,
   `state list|show`), git, jira/acli, aws (`describe-*`, `get-*`, `list-*`), az/gcloud (dernier mot `show`/`list`),
   psql/sqlcmd/mysql sans écriture SQL. Tout autre verbe ⇒ refus à l'enregistrement.
3. **Mise en place** : `POST /api/jarvis/state/clients/{hostId}/releve/setup` lance **un** tour d'agent (consigne fixe
   `inventaire-v1`, sans écran) dans le sujet le plus récent du client, poste allumé, runner `low_priority`, poste
   inactif (garde Z4 de SF-247-07). Le tour inventorie les CLIs authentifiés **sans lire aucun secret** et pose une
   sonde par famille disponible, testée une fois. Date de mise en place gardée (`jarvis_state_settings.releve_setup_at`).
4. **« Tout valider »** : `POST /api/jarvis/state/clients/{hostId}/releve/validate-all` teste chaque sonde de relevé
   proposée du client et active celles dont le test passe ; les autres restent proposées avec leur erreur. La
   validation reste un geste du PO (F-230 D5), distinct de l'activation du registre (d'office).
5. `GET /api/jarvis/state/clients/{hostId}/releve` : date de mise en place et sondes (sujet, plateforme, périmètre,
   commande, proposée/active, test, dernier passage).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Client d'un autre compte | refus | 404 |
| Relevé coupé / poste éteint / runner ancien / poste occupé / aucun sujet | `{code, message}` | 409 |
| Tour déjà en cours dans le sujet | `host_busy` | 409 |
| Verbe d'écriture dans la commande ou le script | `UnsafeProbeException` (proposition refusée) | — |
| Test d'une sonde en échec à « Tout valider » | reste proposée, erreur rendue | 200 |

## Contraintes de validation
13 heures de passage au plus ; 10 sondes de relevé par sujet ; `run_times` élargi à 128 caractères.

## Critères d'acceptation
- [x] `usage: etat` ⇒ STATE, 8 h → 20 h.
- [x] 19 lectures admises, 15 écritures refusées (dont `rollout restart`, `terraform plan`, `state rm`, enchaînement).
- [x] « Tout valider » : testées puis activées, isolation.
- [x] Mise en place refusée poste éteint / runner ancien ; un tour unique sinon, daté.

## Plan de test
- Unitaires : `JarvisWatchStateVerbsTest` (3).
- Intégration : `JarvisReleveSetupIntegrationTest` (3, dont isolation à deux comptes).
- Non-régression : `JarvisWatch*`, `JarvisReleve*`, `AtelierMcp*`, `SceneStepLabelsTest`, `JarvisSceneRegistryGuardTest`,
  `AtelierChatService*` (727 + 140 tests).

## Impact
- Migration `247-08-jarvis-state-releve-setup.xml` (`releve_setup_at`, `run_times` 128).
- Backend : `atelier/veille/JarvisWatchToolCatalog` (paramètre `usage`, une phrase), `JarvisWatchToolExecutor`,
  `JarvisWatchDtos` (`usage`, `purpose`, `ValidateResult`), `JarvisWatchService` (relevé, `validateAllState`),
  `JarvisWatchProbeGuard` (verbes), `JarvisWatchRepository`, `JarvisWatch` ; `atelier/AtelierMcpTurnLauncher`
  (`launchWithBanner`) ; `jarvis/state/JarvisStateSettings` ; `jarvis/state/releve/JarvisReleveSetupService`,
  `JarvisReleveController` (nouveaux).
- Endpoints : 3 nouveaux sous `/api/jarvis/state/clients/{hostId}/releve`.
- Préoccupations transversales :
  - **Contexte tenant** : `RunnerHostRepository.findByIdAndUserId` sur chaque route ; `JarvisWatchService.listForAccount`
    (sujets relus sous `user_id`) ; `WorkspaceRepository.findByUserIdAndHostId`.
  - **Auth / Principal** : nouvelles routes sous la chaîne de l'application (`CurrentUser.requireId`), aucune ouverte au
    compagnon.

## Arbitrages (réversibles)
- La mise en place est **à la demande** (bouton du volet), pas automatique : un tour d'agent coûte et agit sur le poste.
- Le tour s'ouvre dans le **sujet le plus récent** du client (les sondes de relevé, portée client, vivent dans un sujet).
- La définition de `veille_poser` change **une fois** (paramètre `usage`) : préfixe d'Opus réécrit une fois au
  déploiement, puis stable.

## Hors périmètre
Le bouton et la liste à l'écran (SF-247-15 lot 2) ; le relevé de départ (SF-247-09).
