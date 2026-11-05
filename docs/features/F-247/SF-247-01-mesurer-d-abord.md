# Mini-spec — F-247 / SF-247-01 Mesurer d'abord

## Identifiant
`F-247 / SF-247-01`

## Feature parente
`F-247` — Le registre d'état (cadrage `CADRAGE-F-247-registre-d-etat.md`, §6, §7)

## Statut
`done` (2026-11-05) · **Estimation :** 1 j (réestimé ½ j sur le code) · **Branche :** `feat/SF-247-01-mesurer-d-abord` · **Dépend de :** —

## Objectif
Poser la base de mesure **avant** tout registre : chaque réplique de Jarvis dit si le poste était éteint, si la question
était une question d'état et ce que la réponse en a fait ; l'administrateur lit l'indicateur principal et les bases des
portes Z2, Z3, Z13 et la charge runner sur `GET /api/admin/jarvis/state/measure`.

## Comportement attendu

### Cas nominal
1. `JarvisStateQuestion.isStateQuestion(texte)` : classification **déterministe** (clé `PROJ-123`, `!42`, `#17` ;
   « où en est », « statut », « version », « déployé », « bloqué », « attend », « non lus », « qu'a dit », « m'a dit »,
   ticket / MR / PR / pipeline / CI…). Aucun appel modèle.
2. `JarvisStateTurnMeter.measure(...)`, **après** la réponse (jamais dans son chemin) : `hostOffline` (poste du sujet ;
   sans sujet, vrai si aucun des postes du compte n'est connecté ; `null` sans poste), `stateQuestion`, `stateAnswer` :
   - réponse rapide du **lexique fermé** (« je ne sais pas », « aucune information », « hors ligne », « poste éteint »…) ⇒ `UNKNOWN` ;
   - délégation à Opus d'une question d'état **poste éteint** ⇒ `UNKNOWN` (le tour finira en `runner_unavailable`, noté `OFFLINE`) ;
   - autre réponse ⇒ `KNOWN_STALE` poste éteint, `KNOWN_FRESH` sinon ; hors question d'état ou repli ⇒ `NA`.
3. L'événement SSE `done` de `/api/jarvis/converse` porte `state` ; l'écran le recopie dans le chronomètre
   (`POST /api/jarvis/timings`), qui range quatre colonnes nouvelles de `jarvis_turn_timings` : `host_offline`,
   `state_question`, `state_answer`, `state_block_ms` (rempli à SF-247-10).
4. `GET /api/admin/jarvis/state/measure?days=7` (ADMIN) : questions d'état, celles posées poste éteint, issues,
   **part des `UNKNOWN`** (indicateur principal), premier son / premier jeton rapide p50-p90 (Z3/Z13, toutes répliques
   et questions d'état seules), `state_block_ms` p95, durée des tours Opus p50-p90 (`atelier_turn_measures`, Z2),
   appels runner p50-p90 (`runner_audit`, charge). Tout sous le `user_id` de l'appelant.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `stateAnswer` hors liste dans la mesure | refus | 400 |
| `stateBlockMs` négatif ou > 600 000 | refus | 400 |
| Mesure par un USER | refus | 403 |
| Statut runner illisible au calcul | mesure neutre (`NA`), la réplique n'est pas touchée | — |
| Écran d'avant F-247 (pas de `state`) | colonnes à `null`, rien ne casse | 204 |

## Contraintes de validation
`stateAnswer` ∈ {`UNKNOWN`, `KNOWN_STALE`, `KNOWN_FRESH`, `NA`} ; `stateBlockMs` ∈ [0, 600 000] ; fenêtre 1-90 j (7 par défaut).

## Critères d'acceptation
- [x] Les 11 formes de question d'état du cadrage sont reconnues, 5 paroles ordinaires ne le sont pas.
- [x] Le lexique fermé et la délégation poste éteint donnent `UNKNOWN`.
- [x] `done.state` est recopié par l'écran dans la mesure du tour.
- [x] La mesure rend la part des `UNKNOWN` poste éteint, les p50/p90 du premier son, sans rien d'un autre compte ; 403 pour un USER.
- [x] Aucun contenu rangé (ni question, ni réponse).

## Plan de test
- Unitaires : `JarvisStateQuestionTest` (18 : formes reconnues / écartées, issues de réponse) ; `jarvis-turn-clock.spec.ts` (+1 : recopie de `state`).
- Intégration : `JarvisStateMeasureApiIntegrationTest` (colonnes rangées, 400 hors liste, indicateur, voix, 403, isolation entre deux ADMIN).
- Non-régression : suites `JarvisLatency*`, `JarvisFastBrain*`.

## Impact
- Table : `jarvis_turn_timings` (+4 colonnes, migration `247-01-jarvis-turn-timings-state.xml`, réversible).
- Endpoints : `GET /api/admin/jarvis/state/measure` (nouveau, ADMIN) ; `POST /api/jarvis/timings` (+4 champs facultatifs) ; SSE `done` (+`state`).
- Backend : `jarvis/state/JarvisStateQuestion`, `JarvisStateTurnMeter`, `JarvisStateMeasureService`, `JarvisStateMeasureController` ; `JarvisFastBrainController` (setter facultatif, pas de nouveau constructeur) ; `JarvisLatencyService`, `JarvisTurnTiming`.
- Frontend : `jarvis-fast-brain.ts`, `jarvis.component.ts`, `jarvis-turn-clock.ts`, `core/models/jarvis.models.ts`.
- Préoccupations transversales : Contexte tenant — `JarvisStateTurnMeter` (`RunnerStatusService.hostStatus` / `statusOf` sous `user_id`), `JarvisStateMeasureService` (`user_id` sur les 3 lectures). Auth, plans, navigation : non.

## Hors périmètre
Le registre (SF-02), le bloc « État connu » (SF-10). La base Z1b (latence des dépôts) n'a pas de mesure serveur : elle est prise par le banc de SF-247-12.
