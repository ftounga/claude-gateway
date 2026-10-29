# Mini-spec — [F-221 / SF-221-02] L'autonome par fil (backend)

## Identifiant
SF-221-02

## Feature parente
F-221 — Deux chantiers côte à côte : un fil par chantier, le terminal en split

## Statut
in-progress

## Date de création
2026-10-29

## Branche Git
`feat/SF-221-02-autonome-par-fil`

---

## Objectif
La course autonome d'un chantier tourne **dans le fil de ce chantier** : deux chantiers d'un même sujet avancent en même temps, sans se lire. L'écran peut demander quels fils sont actifs.

## Comportement attendu

### Cas nominal
- **La course dans son fil.** `AutonomyTurnRunner` ouvre le tour vivant du **fil N** (`openIfIdle(user, sujet, N, …)`). Il appelle la boucle sur le fil N (`chatStreaming(…, N, …)`). La notification de fin est tue sur ce fil seulement.
  - Les messages de la course sont écrits avec `chantier_number = N` (SF-221-01).
  - La course ne relit jamais la planification d'un autre chantier.
- **Un chantier autonome par chantier** (cadrage A6). Le départ est refusé si le chantier N a déjà une course `RUNNING` ou un tour vivant dans son fil : `409 autonomy_subject_busy`, message « Le chantier N tourne déjà ».
  - Un autre chantier du même sujet peut partir.
  - La limite de **4 courses par compte** reste.
- **Le focus** (cadrage A5).
  - Le départ déplace le focus sur N **seulement si** aucun autre fil du sujet n'est actif : pas de tour vivant ici, sur aucun fil, et pas d'autre course `RUNNING`.
  - Il ne le déplace jamais avec `keepFocus=true` (geste de l'overlay, SF-221-04).
- **[Reprendre la main]** : `POST …/journey/autonomy/stop?chantier=N` arrête la course de N et interrompt **son** fil.
  - Sans `chantier` : toutes les courses en cours du sujet (le geste « sujet » d'avant).
- **Départ programmé par l'outil** (`lancer_chantier_autonome`) :
  - le départ est clefé par chantier ;
  - il attend la fin du tour **qui l'a programmé**, dans le fil de ce tour ;
  - la garde « un à la fois » devient « ce chantier tourne déjà ».
- **Les fils actifs.** `GET /api/workspaces/{id}/chat/lanes` rend `{ lanes: [ { chantier, live, autonomy, startedAt } ] }`. On y trouve :
  - le fil commun (`chantier: null`) s'il a un tour vivant ;
  - chaque chantier qui a un tour vivant (ce pod ou un pair) ou une course `RUNNING`.

  Le tri est fait par début, du plus ancien au plus récent. C'est la source de l'écran pour l'ouverture en split (SF-221-03).

### Cas d'erreur
| Situation | Comportement | Code |
|-----------|--------------|------|
| Le chantier N tourne déjà (course ou tour vivant de son fil) | `autonomy_subject_busy` « Le chantier N tourne déjà » | 409 |
| 4 courses sur le compte | `autonomy_limit_reached` (inchangé) | 409 |
| `stop?chantier=N` sans course de N | `autonomy_not_running` | 409 |
| Sujet d'autrui (launch / stop / lanes) | `requireOwned` d'abord | 404 |

## Critères d'acceptation
- [ ] Les chantiers 1 et 2 d'un même sujet partent en autonome l'un après l'autre : les deux courses sont `RUNNING`, deux tours vivants, fils 1 et 2.
- [ ] Le second départ du chantier 1 est refusé `409` (« Le chantier 1 tourne déjà »).
- [ ] La course du chantier 2 écrit ses messages avec `chantier_number=2`, et son rejeu ne contient pas ceux du fil 1.
- [ ] Focus :
  - premier départ dans un sujet calme → focus = chantier lancé ;
  - second départ alors que le premier tourne → focus inchangé ;
  - `keepFocus=true` → focus inchangé.
- [ ] `stop?chantier=2` arrête la seule course 2. La 1 continue.
- [ ] `GET /chat/lanes` rend les chantiers 1 et 2 actifs. Bob reçoit `404`.

## Périmètre

### Hors scope (explicite)
- L'écran (SF-221-03, SF-221-04).
- Le worktree (SF-221-06) : les deux courses écrivent encore dans la même copie de travail.
- La mosaïque (F-83) et le tableau du client (F-220) lisent déjà les courses par chantier : inchangés.

## Valeurs initiales
- `keepFocus` absent = `false`.

## Contraintes de validation
- `chantier` (stop) : entier ≥ 1, facultatif.

## Technique

### Endpoint(s)
- `POST /api/workspaces/{id}/journey/chantiers/{n}/autonomy?mode=…&keepFocus=true|false`
- `POST /api/workspaces/{id}/journey/autonomy/stop?chantier=N`
- `GET /api/workspaces/{id}/chat/lanes` (nouveau)

### Tables impactées
- Aucune (les courses portent déjà `chantier_number`).

### Migration Liquibase
- [x] Non applicable

### Composants Angular (si applicable)
- Aucun : les appels d'écran (`lanes`, `keepFocus`, `stop(chantier)`) arrivent avec leurs écrans (SF-221-03/04).

## Plan de test

### Tests unitaires
- [ ] `AutonomyTurnRunner` : la boucle est appelée sur le fil N, le tour vivant est celui du fil N.
- [ ] `AutonomyService` : deux chantiers du même sujet partent, le même chantier est refusé, règles du focus, `stop` ciblé.
- [ ] `ChantierLanesService` : fils vivants locaux, courses `RUNNING`, dédoublonnage, tri.

### Tests d'intégration
- [ ] `AutonomyApiIntegrationTest` :
  - deux départs dans le même sujet → `201` ×2 ;
  - même chantier → `409` ;
  - `stop?chantier=` ;
  - Bob → `404` sur `lanes`.

### Isolation utilisateur
- [x] Applicable : `requireOwned` sur `lanes`, `launch` et `stop`. Le registre est clefé par utilisateur.

## Préoccupations transversales
- **Plans / limites** : la garde « 1 course par sujet » devient « 1 par chantier » ; « 4 par compte » est inchangé.
  - Composants : `AutonomyService.launch`, `AutonomyService.schedule`, `AutonomyService.launchDeferred` et le tableau du client (multi-sélection F-220, qui lance plusieurs chantiers et peut désormais en lancer deux d'un même sujet).
- **Contexte tenant** : `ChantierLanesService` (nouveau) lit le registre (`liveOf`, clef utilisateur), les courses (`user_id`) et le relais (`findRemoteTurn` par fil).

## Dépendances
- SF-221-01 (mergée).

## Notes et décisions
- `GET /chat/lanes` sonde le relais des pairs pour le fil commun et pour chaque chantier **ouvert** du sujet. Ce sont quelques appels par ouverture d'écran ; un pod unique en prod n'en fait aucun.
