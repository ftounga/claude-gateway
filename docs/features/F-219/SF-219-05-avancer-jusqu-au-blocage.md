# Mini-spec — [F-219 / SF-219-05] Avancer jusqu'au blocage

## Identifiant
SF-219-05

## Feature parente
F-219 — Exécuter un chantier en autonome

## Statut
Done

## Date de création
2026-10-28

## Branche Git
`feat/SF-219-05-avancer-jusqu-au-blocage`

---

## Objectif
Lancer un chantier en **semi-autonome** : l'agent exécute, sans question, toutes les étapes à la portée de l'outil et celles qu'elles débloquent, contourne celles qui attendent une décision ou quelqu'un, puis s'arrête proprement en disant ce qui reste.

## Besoin (PO, 2026-10-28)
Le départ strict (préflight entièrement vert) est trop exigeant : en prod, le PO a tapé à la main « Chantier 7 fais toutes les étapes restantes à ta portée ». Le mode strict reste disponible.

## Comportement attendu

### Cas nominal
- **Condition de départ** (« peut avancer ») calculée par le serveur :
  1. le sujet est guidé, le chantier ouvert, en **Exécution** ou **Vérification** ;
  2. le plan est **validé à sa version courante** ;
  3. **au moins une étape est à la portée de l'outil** : une étape `A_FAIRE` dont la marque « qui débloque ? » (`ChantierUnblocker`, SF-220-01) est `OUTIL`, ou une étape `FAIT` (l'outil la vérifie).

  Les autres raisons du préflight strict (étape `BLOQUE`/`ECHEC`, attente ouverte, information manquante, dépendance bloquée) **n'empêchent pas** le départ : elles sont rendues comme **« ce qui restera »**, regroupées par « Ça attend votre décision » (`MOI`, avec la phrase) et « On attend quelqu'un » (`AUTRUI`, avec à qui). Seules les causes premières sont listées (une étape qui hérite d'une autre n'est pas répétée).
- **Préflight** (`GET …/autonomy/preflight` et chaque entrée de `GET …/journey/autonomy`) : nouveau champ `advance = { possible, toolSteps:[rangs], remaining:[marque], label }`.
- **Lancement** : `POST …/chantiers/{n}/autonomy?mode=UNTIL_BLOCKED` (même route ; sans `mode` ou `mode=STRICT` = comportement inchangé). Mêmes gardes : un chantier autonome par sujet, 4 courses par compte, poste en ligne. La course enregistre son **mode**.
- **Consigne** (dans le **message** du tour, jamais dans le préfixe système — F-134/F-171), adaptée au mode : le plan validé vaut feu vert pour les étapes marquées « à ta portée » et celles qu'elles débloquent ; ne touche pas aux étapes qui attendent une décision ou quelqu'un, ne pose **aucune** question (F-218) ; vérifie chaque étape (FAIT puis VERIFIE/ECHEC avec preuve, sous-étapes comme preuves) ; sur un vrai blocage d'une étape, `BLOQUE` + raison **puis passe aux autres étapes à ta portée** ; finis le tour quand plus rien n'est à ta portée. Chaque étape restante est annotée (« à ta portée », « attend votre décision : … », « attend quelqu'un : … »).
- **Après chaque tour** (mode jusqu'au blocage) :
  - chantier fermé → `STOPPED` ; tout vérifié / clôture proposée → `DONE` ;
  - plan plus validé (amendement, retour en Investigation) → `BLOCKED` avec la raison ;
  - **plus aucune étape à la portée de l'outil → `PARTIAL`** (« Avancé jusqu'au blocage : 2 décision(s) vous attendent ; on attend Gino. ») — arrêt propre, distinct de l'échec ;
  - aucune avancée sur un tour complet → `BLOCKED` « Aucune avancée… » ;
  - plafond `app.autonomy.max-turns` → `STOPPED` ;
  - sinon tour suivant.
- **Notification** : `PARTIAL` → `AUTONOMY_STOPPED` (catalogue F-185, sonnante F-217, charge neutre D1 : rien du chantier ne quitte l'application). La liste « ce qui reste » est lue **en direct** dans l'application (préflight `advance.remaining`), à un clic de la notification.
- **Relance** : une fois le blocage levé, relancer repart de l'état du plan (rien n'est mémorisé de la course précédente).
- **Écrans** :
  - tableau des chantiers d'un sujet : bouton **[Avancer jusqu'au blocage]** à côté de [Lancer en autonome] dès que `advance.possible`, avec « Ce qui restera » groupé ; la course en cours se lit « Jusqu'au blocage — étape k/n » ; dernière course `PARTIAL` → « avancée jusqu'au blocage » ;
  - tableau du client (F-220) : sur la carte, le geste **[Avancer jusqu'au blocage]** quand le chantier peut avancer ; la multi-sélection offre **[Avancer jusqu'au blocage (k)]** ;
  - outil `lancer_chantier_autonome` : paramètre `mode` (`STRICT` par défaut, `UNTIL_BLOCKED`) ; commande `/avancer N` (et `/autonome` inchangée).

### Cas d'erreur
| Situation | Comportement | Code |
|-----------|--------------|------|
| `mode` inconnu | `autonomy_invalid_mode` | 400 |
| Mode jusqu'au blocage, rien à la portée de l'outil / plan non validé | `autonomy_cannot_advance` + `advance.label` + raisons | 409 |
| Sujet occupé, 4 courses, poste hors ligne | inchangé (`autonomy_subject_busy`, `autonomy_limit_reached`, `autonomy_host_offline`) | 409 |
| Terminal d'autrui | `requireOwned` d'abord | 404 |
| Outil : `mode` inconnu | erreur outil (modes admis) | — |
| Outil : rien à la portée | erreur outil avec la raison, rien de programmé | — |

## Critères d'acceptation
1. Plan validé, une étape `OUTIL` et une étape en attente `AUTRUI` → préflight `ready=false`, `advance.possible=true`, `toolSteps=[1]`, `remaining` contient l'attente avec `waitingOn`.
2. Plan non validé, ou toutes les étapes en attente / bloquées → `advance.possible=false` et un `label` explicite.
3. `POST …?mode=UNTIL_BLOCKED` sur ce chantier → `201`, course `RUNNING` avec `mode=UNTIL_BLOCKED` ; sans `mode` → `409 autonomy_not_ready` (strict inchangé).
4. Boucle : 1er tour fait l'étape à portée, il ne reste que l'attente → `PARTIAL`, raison qui cite ce qui reste, notification `AUTONOMY_STOPPED`, aucun tour de plus.
5. La consigne du mode contient « à ta portée », « ne pose AUCUNE question », l'annotation des étapes en attente ; la consigne stricte est inchangée.
6. Sans avancée → `BLOCKED` ; plafond → `STOPPED` (bornes inchangées dans ce mode).
7. `mode=FOO` → 400 ; rien à la portée → 409 `autonomy_cannot_advance`.
8. Outil `lancer_chantier_autonome mode=UNTIL_BLOCKED` programme le départ, qui part dans ce mode.
9. Écrans : bouton visible dès `advance.possible` (sujet et client) ; multi-sélection lance avec `mode=UNTIL_BLOCKED` ; « Ce qui restera » groupé ; `PARTIAL` lisible ; `/avancer 7` produit une demande avec `UNTIL_BLOCKED`.
10. Bob → 404 sur le lancement en mode jusqu'au blocage du terminal d'Alice.

## Périmètre
### Hors scope (explicite)
- Le contenu du chantier dans la charge push (D1 maintenu : neutre). Un corps détaillé dans le centre de notifications (schéma de `user_notifications` inchangé).
- Une relance automatique quand une attente est levée (relance manuelle).
- Toute modification de `ChantierUnblocker` et du préflight strict.

## Valeurs initiales
Bornes inchangées : `app.autonomy.max-turns=8`, `max-parallel=4`, une course par sujet.

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| `mode` (route, outil) | `STRICT` (défaut) ou `UNTIL_BLOCKED` |
| `chantier_autonomy_runs.mode` | `STRICT` ou `UNTIL_BLOCKED`, non nul, défaut `STRICT` |
| `status` | + `PARTIAL` (varchar(16) existant, aucune contrainte en base) |
| `stop_reason` | 300 caractères au plus (borne existante) |

## Technique
### Endpoint(s)
| Méthode | URL | Changement |
|---------|-----|-----------|
| POST | `/api/workspaces/{ws}/journey/chantiers/{n}/autonomy?mode=` | paramètre `mode` optionnel |
| GET | `/api/workspaces/{ws}/journey/chantiers/{n}/autonomy/preflight` | + `advance` |
| GET | `/api/workspaces/{ws}/journey/autonomy` | + `preflight.advance`, `run.mode` |
| GET | `/api/runner-hosts/{hostId}/chantiers` | + `chantiers[].canAdvance` |
| GET | `/api/atelier/autonomy/runs` | + `mode` |

### Tables impactées
`chantier_autonomy_runs` : colonne `mode varchar(16) not null default 'STRICT'`.

### Migration Liquibase
`177-autonomy-run-mode.xml` (numéro revérifié au merge ; rollback : drop column).

### Composants
- Backend : `journey.AutonomyMode` (nouveau) ; `ChantierPreflight` (+ `Advance`, calcul pur à partir des marques `ChantierUnblocker`) ; `ChantierPreflightService` (lit `waitInfos`) ; `ChantierAutonomyLauncher.schedule(…, mode)` ; `JourneyToolCatalog` / `JourneyToolExecutor` (paramètre `mode`) ; `AutonomyRunStatus.PARTIAL` ; `ChantierAutonomyRun.mode` ; `AutonomyRunStore.open(…, mode)` ; `AutonomyService.launch(…, mode)` ; `AutonomyTurnRunner` (consigne et jugement par mode) ; `AutonomyNotifier` ; `AutonomyController` ; `ClientBoard.Chantier.canAdvance`.
- Frontend : `autonomy.models.ts`, `autonomy.service.ts`, `terminal-chantiers-board.component.ts`, `client-board.component.ts` (geste + multi-sélection, modifs localisées), `client-board.models.ts`, `slash-commands.ts`.

## Préoccupations transversales
- **Plans / limites** — composants impactés et vérifiés : `AutonomyService.launch` et `schedule` (limite des 4 courses, une par sujet : mêmes gardes dans les deux modes) ; `AutonomyTurnRunner.judge` (plafond de tours, aucune avancée : conservés dans le mode). Aucun autre service de limites.
- Auth, tenant, navigation : non (aucune route, garde ni principal modifiés ; `requireOwned` inchangé).

## Plan de test
### Tests unitaires
- `ChantierPreflightTest` : `advance` possible avec attente `AUTRUI` + étape `OUTIL` ; impossible sans étape à portée ; impossible plan non validé ; étape `FAIT` compte comme à portée ; `remaining` sans héritages.
- `AutonomyTurnRunnerTest` : mode jusqu'au blocage → `PARTIAL` quand plus rien n'est à portée ; consigne du mode (annotations, aucune question) ; sans avancée → `BLOCKED` ; plan amendé → `BLOCKED`.
- `AutonomyNotifier` : `PARTIAL` → `AUTONOMY_STOPPED`.
### Tests d'intégration
- `AutonomyApiIntegrationTest` : `?mode=UNTIL_BLOCKED` sur chantier avec attente → `201` + `mode` ; sans mode → `409 autonomy_not_ready` ; `mode=FOO` → 400 ; rien à portée → `409 autonomy_cannot_advance` ; board expose `advance`.
- `AutonomyDeferredIntegrationTest` : outil `mode=UNTIL_BLOCKED` programme et le départ part dans ce mode.
### Isolation utilisateur
- Bob → 404 sur `POST …?mode=UNTIL_BLOCKED` du terminal d'Alice.
### Frontend (Jasmine)
- Tableau du sujet : bouton visible quand `advance.possible` et préflight rouge ; clic → POST avec `mode=UNTIL_BLOCKED` ; « Ce qui restera » groupé ; `PARTIAL` lisible.
- Tableau du client : geste par carte ; multi-sélection en mode jusqu'au blocage.
- `slash-commands` : `/avancer 7`.

## Dépendances
SF-219-01→04 (Done), SF-220-01 (`ChantierUnblocker`, Done), F-185/F-217 (notifications).

## Notes et décisions
- **D1 — État `PARTIAL`** (et non `DONE`/`BLOCKED`) : « avancé jusqu'au blocage » est une issue normale du mode, distincte de l'échec et du blocage inattendu.
- **D2 — Notification neutre** : réutilise `AUTONOMY_STOPPED` (« s'est arrêté », « Ouvrez le sujet pour voir ce qui le bloque »), qui sonne déjà (F-217). La liste détaillée n'est pas mise dans la charge (D1 de F-185 : rien du chantier sur l'écran verrouillé) ; elle est servie **en direct** par le préflight — elle reste juste quand le PO lève un blocage. *Écarté* : un corps détaillé dans le centre de notifications (nouveau schéma, hors besoin).
- **D3 — Étape `FAIT` = à portée** : la vérifier est un travail de l'outil (cohérent avec `ChantierUnblocker.chantier`).
- **D4 — Mode persisté** sur la course (colonne `mode`) : l'écran distingue « En autonome » de « Jusqu'au blocage » et l'historique reste lisible.
- **D5 — Commande `/avancer N`** plutôt qu'une option de `/autonome` : plus lisible, `/autonome` inchangée.
