# Mini-spec — [F-219 / SF-219-06] Qui exécute l'étape ?

## Identifiant
SF-219-06

## Feature parente
F-219 — Exécuter un chantier en autonome

## Statut
Done

## Date de création
2026-10-28

## Branche Git
`feat/SF-219-06-acteur-de-l-etape`

---

## Objectif
Chaque étape du plan dit **qui l'exécute** — l'outil, l'utilisateur, ou quelqu'un d'autre — pour que le préflight, « qui débloque ? » et le mode « avancer jusqu'au blocage » ne comptent plus comme « à la portée de l'outil » un geste que l'outil n'a aucun moyen de faire.

## Incident qui fonde le besoin (prod, 2026-10-28, prouvé)
- Sujet **agenor** (CAGIP), chantier 7 : préflight « Prêt à partir », lancé en **STRICT**. L'agent revérifie les étapes 4 à 17, fait la 19, puis s'arrête `BLOCKED` à l'étape 18 « envoyer le message DNS on-prem à Dhaou » : seul le PO peut l'envoyer.
- Même cause le matin sur **secrets-azure-devops** : `BLOCKED` à l'étape 7, « c'est à l'utilisateur d'envoyer les messages ».
- Les deux courses sont dans `chantier_autonomy_runs.stop_reason`.

**Cause** : le plan ne dit pas qui exécute. `ChantierUnblocker` (SF-220-01) et le préflight ne lisent que le statut `BLOQUE`, les attentes `waits_on` et les questions : une étape « envoyer un message » sans attente est comptée `OUTIL`. L'arbitrage F-220 « une étape EXTERNE n'est pas une attente, l'outil exécute l'irréversible » est juste quand l'outil a le canal, faux quand il ne l'a pas.

## Canaux d'envoi réels constatés (vérifiés dans le code le 2026-10-28)
| Canal | Ce qu'il permet | Écrire à un tiers ? |
|-------|-----------------|---------------------|
| `email_me` (F-110) / `courriel_m_envoyer` (F-112) | un courriel **au titulaire du compte** ; le schéma n'a **aucun champ destinataire** | **Non** |
| Écriture Microsoft 365 (F-108) | fichiers SharePoint/OneDrive : déposer, renommer, déplacer, supprimer, modifier | **Non** — « Poster un message reste exclu » (PRODUCT_SPEC F-108) |
| Radar (`radar_preparer_relance`, `preparer_reponse_manager`) | **prépare** un brouillon de relance | **Non** (rien n'est envoyé) |
| Commandes du poste (bash : `git`, `gh`, `glab`, `az`, `kubectl`…) | tout ce que les accès du poste permettent (commenter une MR, pousser, appliquer) | Oui, **seulement** par ce que ces outils publient (un commentaire de MR, par exemple) |

Conséquence : **envoyer un message à une personne** (courriel, Teams, chat) depuis la messagerie ou le Teams du PO n'est **jamais** à la portée de l'outil ; commenter une MR avec la CLI du poste l'est.

## Comportement attendu

### Cas nominal
- **Nouveau champ d'étape `actor`** : `OUTIL` | `MOI` | `TIERS`. Posé par l'agent à la planification (`set_subject_plan`, champ **requis** dans le schéma de l'étape ; alias accepté `acteur`). Persisté dans le JSON du plan **seulement s'il est donné** : un plan d'avant SF-219-06 se relit et se réécrit à l'identique.
- **Valeur effective** : sans acteur explicite, une étape vaut `OUTIL` (plans existants inchangés).
- **Règle dans la consigne de planification** (`JourneyToolCatalog.GUIDE`, littéral stable, cache F-134) : un geste que l'outil ne peut pas faire lui-même est `MOI` (l'utilisateur le fait) ou `TIERS` (quelqu'un d'autre le fait). Exemples : envoyer un message depuis la messagerie ou le Teams de l'utilisateur (aucun outil n'écrit à un tiers : `email_me` n'écrit qu'à lui, l'écriture Microsoft 365 ne poste pas de message), appeler, approuver à la place de quelqu'un, utiliser un accès que l'outil n'a pas, faire un geste physique. Une commande du poste qui publie elle-même (commentaire de MR par la CLI) reste `OUTIL`.
- **`ChantierUnblocker`** : une étape restante (à faire) `actor=MOI` → marque `MOI` « votre geste » (orange) ; `actor=TIERS` → marque `AUTRUI` « on attend quelqu'un ». Priorité : statut `BLOQUE`/`ECHEC` > attente `waits_on` ouverte > **acteur** > héritage d'une étape amont. Les étapes qui en dépendent héritent, comme aujourd'hui.
- **Préflight STRICT** : refuse « Prêt à partir » s'il reste une étape à faire non-`OUTIL`, avec un code par cas — `STEP_ACTOR_MOI` (« Étape 18 : « Envoyer le message DNS à Dhaou » — un geste à faire par vous : l'outil ne peut pas le faire. ») et `STEP_ACTOR_TIERS`.
- **Mode `UNTIL_BLOCKED`** (SF-219-05) : inchangé dans son principe ; ces étapes ne sont plus « à la portée de l'outil », la course s'arrête avant elles et elles figurent dans « ce qui restera » (causes premières `MOI`/`AUTRUI`). La consigne du mode annote « à faire par l'utilisateur » / « à faire par quelqu'un d'autre ».
- **Requalification des plans existants** : au tour d'un sujet guidé dont un chantier ouvert a un plan, tant que des étapes **restantes** (ni faites ni vérifiées) n'ont pas d'acteur explicite, la note de tour (`JourneyTurnNote`, dans le **message**, jamais dans le préfixe système) porte **une ligne** qui les cite et invite à les requalifier avec `update_plan_step` (`actor`). Elle disparaît dès que toutes les étapes restantes ont un acteur explicite (y compris `OUTIL`).
- **`update_plan_step`** : nouveau paramètre `actor` ; `status` devient facultatif quand `actor` est donné. Changer l'acteur **n'est pas un amendement** (comme l'avancement : plan courant et plan validé réécrits ensemble quand le plan est validé), et est permis dès qu'un plan existe (phase Plan, Exécution ou Vérification). Via `set_subject_plan`, l'acteur fait partie du contenu de l'étape (un changement d'acteur est visible dans l'amendement) ; un plan renvoyé sans acteur garde celui de l'étape de même rang et même titre.
- **La note de tour** affiche l'acteur de chaque étape non-`OUTIL` (« · par vous », « · par un tiers »).
- **Écrans** (sobres, `DESIGN_SYSTEM.md`, SF-220-09 respecté) :
  - tableau des chantiers d'un sujet : sur la ligne de méta de l'étape, une marque discrète **icône + libellé court** (« par vous », « par un tiers ») pour les étapes `MOI`/`TIERS`, libellé accessible complet ; rien pour `OUTIL` (le cas courant reste silencieux) ;
  - tableau du client : dans le **détail** d'un chantier, la « Prochaine étape » porte la même marque quand elle n'est pas `OUTIL`.

### Cas d'erreur
| Situation | Comportement |
|-----------|--------------|
| `actor` inconnu (`set_subject_plan` ou `update_plan_step`) | erreur outil « actor est OUTIL, MOI ou TIERS », rien d'enregistré |
| `update_plan_step` sans `status` ni `actor` | erreur outil « status ou actor est requis » |
| `update_plan_step actor` sur un sujet libre / clos / sans plan | `NOT_GUIDED` / `NOT_EXECUTING` (erreur outil inchangée) |
| Étape inexistante | `NO_SUCH_STEP` inchangé |

## Critères d'acceptation
1. Un plan JSON sans `actor` se relit avec `effectiveActor()=OUTIL`, `actor()=null`, et se réécrit à l'octet près ; un plan avec `actor` le conserve.
2. `set_subject_plan` avec `actor: "MOI"` pose l'étape `MOI` ; `actor: "XX"` → erreur, rien d'enregistré.
3. `ChantierUnblocker.steps` : étape à faire `MOI` → `MOI` ; `TIERS` → `AUTRUI` ; étape dépendante → héritage de la cause ; étape faite → `null`.
4. Préflight : plan validé, une étape `OUTIL` et une étape `MOI` à faire → `ready=false` avec `STEP_ACTOR_MOI` et le titre de l'étape ; `advance.possible=true`, `toolSteps` sans l'étape `MOI`, `remaining` la contient.
5. Plan sans acteur explicite → préflight inchangé (`ready=true` s'il l'était).
6. `update_plan_step step=N actor=MOI` (sans status) sur un plan validé → l'acteur change, la version et la validation ne bougent pas, aucun changement de phase ni d'état.
7. Note de tour : plan ouvert avec des étapes restantes sans acteur → une ligne d'invite qui cite chantier et étapes ; toutes explicites → aucune invite ; les étapes `MOI`/`TIERS` sont annotées.
8. `GUIDE` contient la règle (`MOI`, `TIERS`, `email_me` n'écrit qu'à l'utilisateur) et reste un littéral (aucune donnée volatile).
9. Consigne `UNTIL_BLOCKED` : une étape `MOI` est annotée « à faire par l'utilisateur — n'y touche pas ».
10. Écrans : étape `MOI` → marque « par vous » avec `aria-label` complet ; étape `OUTIL` → aucune marque ; détail du tableau du client : prochaine étape `TIERS` → marque « par un tiers ».
11. Isolation : aucune nouvelle route ; `update_plan_step` lit `userId`/workspace du tour (inchangé).

## Périmètre
### Hors scope (explicite)
- Un bouton « J'ai fait ce geste » dans l'écran (l'utilisateur le dit à l'agent, qui coche l'étape `FAIT` comme aujourd'hui).
- Un canal d'envoi à un tiers (aucun n'est ajouté ; « poster un message » reste exclu, F-108).
- La requalification automatique des plans existants par le serveur (c'est l'agent qui juge, invité par la note).
- Toute migration de base (le plan est un JSON).

## Valeurs initiales
Défaut : `OUTIL` pour une étape sans acteur. Invite de requalification : au plus 12 étapes citées par chantier (au-delà, comptées).

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| `actor` (plan, outil) | `OUTIL`, `MOI`, `TIERS` (insensible à la casse ; alias tolérés : `TOOL`/`AGENT`, `ME`/`USER`/`UTILISATEUR`, `OTHER`/`AUTRUI`) ; absent = non précisé |

## Technique
### Endpoint(s)
| Méthode | URL | Changement |
|---------|-----|-----------|
| GET | `/api/workspaces/{ws}/journey` (et chantiers ouverts) | `plan.steps[].actor` (valeur effective) et `actorExplicit` |
| GET | `/api/workspaces/{ws}/journey/chantiers/{n}/autonomy/preflight` | nouveaux codes `STEP_ACTOR_MOI`, `STEP_ACTOR_TIERS` |
| GET | `/api/runner-hosts/{hostId}/chantiers` | `chantiers[].nextStep.actor` |

### Tables impactées
Aucune (le plan est stocké en JSON dans `subject_journeys` / `subject_journey_chantiers` ; champ facultatif).

### Migration Liquibase
Aucune.

### Composants
- Backend : `JourneyPlan` (`Actor`, `Step.actor`, `effectiveActor`, `withActor`, JSON) ; `ChantierUnblocker` ; `ChantierPreflight` (codes) ; `JourneyToolCatalog` (GUIDE, schémas `set_subject_plan` / `update_plan_step`) ; `JourneyToolExecutor` ; `SubjectJourneyService` (`updateStep` avec acteur, `carryProgress`) ; `JourneyTurnNote` ; `AutonomyTurnRunner` (annotation) ; `SubjectJourneyResponse.Step` ; `ClientBoard.NextStep` / `ClientBoardService`.
- Frontend : `journey.models.ts`, `client-board.models.ts`, `terminal-chantiers-board.component.ts`, `client-board.component.html/.scss`, nouveau `shared/step-actor/step-actor.component.ts`.

## Préoccupations transversales
- **Plans / limites** — composants impactés et vérifiés : `ChantierPreflight.evaluate` (gate STRICT de `AutonomyService.launch` / `ChantierAutonomyLauncher.schedule`) et `ChantierPreflight.advance` (gate `UNTIL_BLOCKED`, jugement `PARTIAL` de `AutonomyTurnRunner`) : ces gates refusent désormais une étape non-`OUTIL`. `ClientBoardService` (`ready`, `canAdvance`, compteurs `outil/moi/autrui`) lit les mêmes calculs. Aucune limite chiffrée modifiée.
- Auth, tenant, navigation : non (aucune route, garde ni principal modifiés).

## Plan de test
### Tests unitaires
- `JourneyPlanTest` : JSON sans acteur relu/réécrit à l'identique ; acteur conservé ; `fromToolInput` lit `actor`/`acteur`, refuse une valeur inconnue ; `withActor`.
- `ChantierUnblockerTest` : `MOI` → `MOI`, `TIERS` → `AUTRUI`, héritage, priorité de l'attente ouverte, étape faite → `null`.
- `ChantierPreflightTest` : `STEP_ACTOR_MOI`/`STEP_ACTOR_TIERS` avec le titre ; `advance` exclut l'étape et la met dans `remaining` ; plan sans acteur inchangé.
- `JourneyTurnNoteTest` : invite présente/absente ; annotation des étapes.
- `JourneyToolCatalogTest` : GUIDE contient la règle ; `actor` requis dans l'étape de `set_subject_plan` ; `status` plus requis dans `update_plan_step`.
- `AutonomyTurnRunnerTest` : annotation « à faire par l'utilisateur ».
### Tests d'intégration
- `SubjectJourneyServiceTest` (ou intégration du parcours) : `updateStep` acteur seul → version/validation inchangées ; acteur + statut ; `carryProgress` garde l'acteur.
- `JourneyToolExecutorTest` : `update_plan_step` acteur seul, acteur inconnu, ni l'un ni l'autre.
- `AutonomyApiIntegrationTest` : préflight `STEP_ACTOR_MOI` et lancement STRICT refusé (`409 autonomy_not_ready`) ; `UNTIL_BLOCKED` accepté.
### Isolation utilisateur
- Inchangée et couverte : la route de préflight passe par `requireOwned` (test Bob → 404 existant) ; `update_plan_step` ne lit aucun identifiant dans ses paramètres.
### Frontend (Jasmine)
- `StepActorComponent` : `MOI` → « par vous » + `aria-label` ; `OUTIL`/absent → rien.
- Tableau des chantiers : étape `MOI` porte la marque, étape `OUTIL` non.
- Tableau du client : détail, prochaine étape `TIERS` → « par un tiers ».

## Dépendances
SF-219-01→05 (Done), SF-220-01 (`ChantierUnblocker`), SF-220-09 (liste fine du tableau du client), F-110 / F-108 (canaux constatés).

## Notes et décisions
- **D1 — Trois valeurs, `TIERS` et non `AUTRUI`** dans le plan (vocabulaire PO) ; la marque « qui débloque » garde `AUTRUI`, la correspondance est faite par `ChantierUnblocker`.
- **D2 — Défaut `OUTIL`, acteur nullable** : on distingue « non précisé » d'« explicitement l'outil », ce qui permet l'invite de requalification et garde les plans existants identiques à l'octet.
- **D3 — Changer l'acteur par `update_plan_step` n'est pas un amendement** : c'est une qualification, pas un changement de ce que fait l'étape ; exiger une revalidation casserait les courses sur les plans existants. Par `set_subject_plan`, il fait partie du contenu (visible dans l'amendement).
- **D4 — Priorité de l'acteur sur l'héritage** (comme l'attente propre de l'étape) : une étape `MOI` derrière une étape bloquée est sa propre cause première et figure dans « ce qui restera ».
- **D5 — Rien d'affiché pour `OUTIL`** : le cas courant reste silencieux, seule l'exception se voit (sobriété SF-220-09).
- **D6 — L'invite est répétée à chaque tour tant que la requalification n'est pas faite** (une ligne, dans le message) ; une invite « vue une fois » n'aurait aucune garantie d'être suivie, et l'état est lisible dans le plan (aucune mémoire de plus).
