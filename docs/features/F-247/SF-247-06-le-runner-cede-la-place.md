# Mini-spec — F-247 / SF-247-06 Le runner cède la place

## Identifiant
`F-247 / SF-247-06`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.3, portes Z4 et Z5, préoccupation « Protocole runner »)

## Statut
`done` (2026-11-05) · **Estimation :** 1,5 j (réestimé 0,5 j) · **Branche :** `feat/SF-247-06-runner-cede-la-place`

## Objectif
Le runner sait exécuter une commande de **basse priorité** (une sonde de relevé) qui ne gêne jamais le travail du PO :
refusée quand le poste travaille, interrompue dès qu'une commande du PO arrive, en priorité basse, bornée, CPU remonté.

## Comportement attendu

### Cas nominal
1. Trame `tool_call` portant `priority: "low"` (ou entrée `priority: "low"`) pour `bash` : le dispatcher la porte
   jusqu'à l'outil. Un runner ancien ignore le champ.
2. `BashTool` : une commande `low` est **refusée** (`busy_deferred`) si une commande du PO (premier plan, preuve) est
   en vol, si une autre activité occupe le poste (capture, synchro, téléchargement — `RunnerActivity`), ou si une autre
   sonde tourne (**une à la fois**). Elle ne prend aucun jeton du premier plan.
3. Lancée en **priorité basse** : `nice -n 19` devant la ligne là où `nice` existe (POSIX), `BelowNormal` sous Windows
   (au mieux). **20 s au plus** (`timeout`).
4. **Préemption** : toute commande du PO (premier plan, arrière-plan, preuve) tue d'abord les sondes en cours (et
   leurs descendants) ; la sonde rend `preempted` en ≤ 500 ms. La commande du PO part sans attendre.
5. **CPU** relevé (processus et descendants) et remonté dans la trame `tool_result` (`cpuMs`), succès ou préemption.
6. Capacité **`low_priority`** annoncée dans `ready` dès que `bash` est autorisé ; **sans elle, la gateway n'envoie
   aucun relevé** (SF-247-07).
7. Note de version du runner (mise à jour requise pour le relevé).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Commande du PO en vol / autre activité / autre sonde | `busy_deferred`, rien n'est lancé |
| Commande du PO arrivée pendant la sonde | sonde tuée, `preempted` (≤ 500 ms) |
| Sonde > 20 s | tuée, `timeout` |
| `--no-bash` | `unsupported_tool` (inchangé), capacité non annoncée |

## Contraintes de validation
1 sonde à la fois ; 20 s par sonde ; préemption ≤ 500 ms ; relevé CPU au pas de 50 ms.

## Critères d'acceptation
- [x] `busy_deferred` quand le poste travaille ; une sonde à la fois.
- [x] Préemption mesurée ≤ 500 ms, la commande du PO passe.
- [x] Priorité basse (`nice 19`) ; CPU remonté.
- [x] Capacité annoncée ; contrats existants (`BUSY_MESSAGE`, capacités) inchangés hormis l'ajout.

## Plan de test
- Unitaires : `BashToolLowPriorityTest` (6).
- Non-régression : suite runner complète (1 470 tests), listes de capacités mises à jour (`ToolRouterTest`,
  `ProjectScopesTest`, `TeamsRoutingTest`, `ToolDispatcherTest`).
- Isolation : sans objet (aucune donnée ; le poste est celui du compte).

## Impact
- Runner : `BashTool`, `ToolDispatcher`, `ToolRouter`, `ToolOutcome` (`cpuMs`), `release-notes.txt`.
- Aucune table, aucun endpoint, aucune migration.
- Préoccupations transversales : **Protocole runner** — `BashTool`/`ToolDispatcher` (priorité, refus, préemption),
  annonce des capacités (`ToolRouter.capabilities`), trame `tool_result` (`cpuMs`). Un vieux runner ignore le champ ;
  la passerelle conditionne le relevé à `low_priority` (SF-247-07).

## Arbitrages (réversibles)
- Toute commande du PO préempte, y compris un lancement en arrière-plan et une session de preuve.
- Une activité non-commande (capture, synchro, téléchargement) suffit à refuser la sonde.

## Hors périmètre
Le côté passerelle du relevé (SF-247-07), la mise en place (SF-247-08), le départ (SF-247-09).
