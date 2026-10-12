# Mini-spec — F-196 / SF-196-01 Les suites rattachées au plan du chantier (backend)

## Identifiant
`F-196 / SF-196-01` — Feature parente : F-196 « Cap au service du chantier » (cadrage `CADRAGE-F-196-cap-au-service-du-chantier.md`, décisions PO 1, 2, 5, 6).
Statut : `ready` — 2026-10-12 — Branche `feat/SF-196-01-cap-rattachement`.

## Objectif
En mode guidé, chaque suite Cap est rattachée par la gateway à une étape réelle du plan ou marquée « approfondissement », les étapes passent d'abord et un seul approfondissement au plus est gardé tant que le chantier est ouvert.

## Comportement attendu

### Cas nominal
1. **Consigne stable** (décision 6) : `NEXT_STEPS_DOCTRINE` (préfixe système, littéral sans élément volatil) gagne une règle générale : « en mode guidé, ajoute à chaque option `"etape"` = numéro de l'étape du plan qu'elle fait avancer ou débloque, ou `null` si elle va au-delà du plan ; le chantier d'abord (la prochaine étape à faire et ce qui la débloque) ; au plus UN approfondissement, en dernier ; après la clôture, les approfondissements sont libres et une suite peut proposer d'ouvrir un nouveau chantier ; hors mode guidé, pas de champ `etape` ». Le plan lui-même reste dans la note de tour `JourneyTurnNote` (inchangée).
2. **Lecture** (`NextStepsBlock`) : champ facultatif `etape` / `étape` / `step` — entier, ou texte contenant un entier (« 3 », « étape 3 »). Absent, `null`, ≤ 0 ou illisible ⇒ aucun numéro proposé. Le numéro proposé n'est **jamais** exposé tel quel.
3. **Rattachement** (`ChantierAnchor.apply`, pur) — appliqué en fin de tour sur le parcours **relu à la fin du tour** (le plan a pu avancer pendant le tour) :
   - **chantier ouvert** = mode `GUIDE`, phase non nulle et ≠ `CLOS`, plan non vide ;
   - numéro proposé dans `1..taille du plan` ⇒ `scope = CHANTIER`, `stepIndex = n`, `stepTitle` = titre de l'étape `n` **du plan réel** tronqué à 60 caractères ; sinon ⇒ `scope = APPROFONDISSEMENT` (jamais confiance aveugle au modèle — décision 1) ;
   - **tri** : les suites `CHANTIER` d'abord (ordre du modèle conservé), puis **un seul** approfondissement (le premier proposé), en dernier — les autres sont écartés (décision 2) ;
   - **avancement** : `{number, done, total}` — `done` = étapes `FAIT` ou `VERIFIE`, `total` = taille du plan, `number` = numéro du chantier (≥ 1).
4. **Hors chantier ouvert** (Libre, Clos, guidé sans plan) : suites inchangées, champs de rattachement **absents**, pas d'avancement (décision 5).
5. **Exposition** : chaque suite porte `scope`, `stepIndex`, `stepTitle` (absents hors chantier) ; l'avancement voyage dans `nextStepsChantier` — événement SSE `done` (additif) et `terminal_json` (historique). Pas de suite ⇒ pas d'avancement.

### Contrat (figé, consommé par SF-196-02)
```json
// SSE "done" (additif) et terminal.nextSteps / terminal.nextStepsChantier de l'historique
"nextSteps": [
  {"title": "…", "why": "…", "label": "CONTINUER", "request": "…",
   "scope": "CHANTIER", "stepIndex": 2, "stepTitle": "≤ 60 car."},
  {"title": "…", "why": "…", "label": "RISQUE", "request": "…", "scope": "APPROFONDISSEMENT"}
],
"nextStepsChantier": {"number": 1, "done": 3, "total": 10}
```
Hors chantier ouvert : `scope`/`stepIndex`/`stepTitle` absents, `nextStepsChantier` absent (`null` dans `done`).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `etape` hors plan (0, 11 sur 10 étapes, négatif) | approfondissement |
| `etape` illisible (« bientôt », objet) | approfondissement |
| Plusieurs approfondissements, chantier ouvert | le premier gardé, en dernier ; les autres écartés |
| Parcours illisible / service absent | suites inchangées, sans rattachement (best-effort, jamais d'exception) |
| Plan JSON illisible | plan vide ⇒ pas de rattachement |
| Mode Libre avec `etape` fourni | ignoré, champs absents |
| Tour interrompu / au plafond | aucune suite (inchangé F-192), pas d'avancement |

## Contraintes de validation
- `stepTitle` ≤ 60 caractères (« … »). `stepIndex` ∈ `1..20` (borne `JourneyPlan.MAX_STEPS`). `scope` ∈ {`CHANTIER`, `APPROFONDISSEMENT`}. Au plus 1 `APPROFONDISSEMENT` quand le chantier est ouvert ; 4 suites au plus (inchangé).

## Critères d'acceptation
- [ ] La consigne stable contient la règle du rattachement, une seule fois, sans élément volatil (préfixe identique d'un tour à l'autre).
- [ ] Guidé, plan de 10 étapes : `etape: 2` ⇒ `CHANTIER`, `stepIndex 2`, `stepTitle` = titre réel de l'étape 2 (pas celui inventé par le modèle).
- [ ] `etape: 11` ou absente ⇒ `APPROFONDISSEMENT`.
- [ ] Ordre de sortie : étapes d'abord, un seul approfondissement en dernier.
- [ ] Libre / Clos : sortie identique à F-192, aucun champ de rattachement, pas d'avancement.
- [ ] `done` et `terminal_json` portent `nextStepsChantier {number, done, total}` en chantier ouvert.
- [ ] Isolation : le parcours lu est celui du terminal possédé du tour (`forTurn(userId, workspaceId)` existant) — aucune nouvelle route.

## Plan de test
- Unitaires `ChantierAnchorTest` : rattachement valide, hors plan, absent, tri, borne 1 approfondissement, Libre, Clos, guidé sans plan, avancement done/total, titre tronqué, parcours nul.
- Unitaires `NextStepsBlockTest` (ajouts) : lecture `etape` entier / texte / null / négatif ; `attachTo` avec avancement ; champs absents à la sérialisation hors chantier.
- `AtelierChatServiceSystemPromptTest` : la consigne contient la règle (préfixe stable inchangé par ailleurs).
- Isolation : aucune nouvelle route ; lecture via `journeyOf(userId, workspace)` existant (déjà couvert).

## Impacts
- Tables : **aucune** (champ additif dans `terminal_json`).
- Endpoints : aucun nouveau ; champs additifs sur l'événement SSE `done` et dans `terminal.*` de l'historique.
- Composants : `NextStep` (champs additifs, `@JsonInclude(NON_NULL)`), `NextStepsBlock` (lecture `etape`, `attachTo` avec avancement), nouveau `ChantierAnchor`, `AtelierChatService` (doctrine, application en fin de tour, `AtelierChatResult`), `AtelierChatController.StreamDone`.
- Préoccupations transversales : Auth ☐ ; Tenant ☐ (lecture du parcours par le chemin existant `forTurn(userId, workspaceId)`) ; Plans/limites ☐ ; Navigation ☐.
- Provider Independence : aucune dépendance fournisseur.

## Hors périmètre
Rendu (SF-196-02) ; séparateur de résolution (SF-196-03) ; flux de clôture F-176 ; mode Libre ; interdire les approfondissements.
