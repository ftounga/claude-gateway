# Mini-spec — F-121 / SF-121-10 — Plan mode piloté par le modèle + persistance du plan

## Identifiant

`F-121 / SF-121-10`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-121-10-plan-mode-modele`

---

## Objectif

> En une phrase : donner au modèle un vrai `ExitPlanMode` (il soumet son plan à approbation, un geste
> « Approuver & exécuter » l'injecte en tête du tour ACT) **et** persister le mode et le dernier plan
> par thread (workspace) pour les réinjecter au tour suivant et les rendre à l'écran, au lieu de jeter
> le plan à chaque tour.

---

## Contexte et existant (vérifié avant cadrage)

- **F-120 / SF-120-02** a livré le mode du tour `AgentTurnMode ∈ {ANSWER_PLAN, ACT}` (opt-in, défaut
  ACT), le retrait des outils mutants en `ANSWER_PLAN` (liste blanche `ANSWER_PLAN_TOOLS`), la consigne
  `ANSWER_PLAN_DIRECTIVE`, et l'UI (sélecteur de mode + bouton « Passer à l'exécution »). **Le mode est
  per-tour, non persisté ; le bouton ne fait que basculer le mode localement.**
- **F-39 / SF-39-13** a livré `AtelierPlan` + l'outil `set_plan` + le rendu du plan à l'écran (`onPlan`,
  `StreamPlan`). **Le plan vit dans le TOUR (`planOfTurn`), il est jeté à la fin du tour.**
- **F-121 / SF-121-05** a livré la porte de complétude `PlanCompletudeCheckpoint` (END_OF_TURN) qui
  refuse la clôture si `planOfTurn` porte des étapes `pending`/`active`.
- La persistance du fil vit sur l'entité `Workspace` (le « thread ») : `chat_thread_started_at`,
  `chat_thread_summary` (F-117). `restart` (SF-39-04) efface le résumé. **C'est là que le mode et le
  plan doivent vivre.**

Ce qui MANQUE (écart F-121-10, audit consolidé P2-c/P2-d) : (a) pas d'approbation oui/non pilotée par
le modèle (`ExitPlanMode`), pas d'injection du plan validé en tête du tour ACT ; (b) mode et plan non
persistés — le plan est jeté à chaque tour.

---

## Comportement attendu

### Cas nominal — (a) ExitPlanMode piloté par le modèle

1. L'utilisateur est en mode `ANSWER_PLAN`. Le modèle lit/explore, puis **appelle l'outil
   `exit_plan_mode`** (déclaré **uniquement** en `ANSWER_PLAN`) avec les étapes de son plan.
2. La boucle normalise le plan (réutilise `AtelierPlan.from`), le **persiste** comme dernier plan du
   thread, l'émet à l'écran (`onPlan`), **marque le tour `planSubmitted=true`**, et rend au modèle un
   compte rendu : « Plan soumis à l'approbation de l'utilisateur — n'exécute rien de plus, attends. »
   Le modèle conclut alors son tour (aucun outil mutant ne lui est de toute façon donné).
3. L'écran, voyant `planSubmitted`, propose le geste **« Approuver & exécuter »**.
4. Au clic, l'écran bascule le mode en `ACT` et **lance un tour d'exécution** ; la boucle, voyant un
   plan persisté en mode ACT, **injecte le plan validé en TÊTE de la consigne** du tour (« Plan validé
   par l'utilisateur — exécute-le : … »). Le modèle exécute.

### Cas nominal — (b) persistance du mode et du plan par thread

1. À la **fin de chaque tour**, la boucle persiste sur le workspace : le **mode** du tour
   (`chat_thread_mode`, `null`=ACT) et le **dernier plan non vide** (`chat_thread_plan`, JSON), via
   `set_plan` **ou** `exit_plan_mode`. Un plan **entièrement terminé** (toutes étapes `done`) n'est pas
   reporté (rien à reprendre) → colonne remise à `null`.
2. Au **début du tour suivant**, si un plan est persisté, il est **réinjecté dans la CONSIGNE** (jamais
   la consigne système : cache F-134 préservé) et **émis à l'écran** (`onPlan`) — le modèle retrouve son
   plan, l'utilisateur le revoit après un rechargement.
3. À l'**ouverture du projet**, `GET …/chat/resume` renvoie le `mode` et le `plan` persistés ; l'écran
   **restaure le sélecteur de mode** et affiche le plan.
4. Un **« nouveau départ »** (`restart`, SF-39-04) efface mode et plan (comme le résumé).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `exit_plan_mode` appelé avec des étapes mal formées / vides | Normalisé par `AtelierPlan.from` (jamais de refus, D2 de SF-39-13) ; plan vide ⇒ pas de soumission, compte rendu neutre | 200 (tool_result) |
| `exit_plan_mode` appelé en mode ACT (le modèle nomme un outil non déclaré) | La boucle relaie les outils non déclarés ; l'outil reste traité (normalise + persiste) mais ne bloque rien — inoffensif | 200 |
| Plan persisté illisible (JSON corrompu) | `AtelierPlan.fromJson` rend `EMPTY` (best-effort) ; aucun crash, comportement d'avant | — |
| Persistance mode/plan échoue (DB) | Best-effort : logué en debug, **ne casse pas le tour** (patron F-115/F-137/F-148) | — |
| Workspace d'un autre utilisateur | `requireOwned`/`findByIdAndUserId` ⇒ 404, aucune écriture | 404 |

---

## Critères d'acceptation

- [ ] En `ANSWER_PLAN`, l'outil `exit_plan_mode` est **déclaré** (et **absent** en `ACT`).
- [ ] Un appel `exit_plan_mode` : normalise le plan, l'émet (`onPlan`), le persiste, marque
      `planSubmitted=true` dans le résultat, et rend un compte rendu « soumis à approbation ».
- [ ] `exit_plan_mode` **ne déclenche pas** la porte de complétude (SF-121-05) : `planOfTurn` reste vide
      (le plan soumis vit dans un champ dédié), donc un plan tout `pending` soumis ne rebloque pas le tour.
- [ ] `planSubmitted` voyage jusqu'au frontend (réponse synchrone `AtelierChatResponse` + événement SSE
      `done` `StreamDone`).
- [ ] À la fin d'un tour, mode et dernier plan non vide (via `set_plan` ou `exit_plan_mode`) sont
      **persistés** sur le workspace ; un plan tout `done` remet le plan à `null`.
- [ ] Au tour suivant, un plan persisté est **réinjecté dans la consigne** (message), **pas** dans la
      consigne système (préfixe byte-stable, cache F-134 préservé), et **émis à l'écran**.
- [ ] La réinjection **ne seed pas** `planOfTurn` ⇒ la porte de complétude (SF-121-05) n'est **pas**
      déclenchée par un plan simplement reporté (non-régression).
- [ ] `GET …/chat/resume` renvoie `mode` et `plan` persistés ; `restart` efface les deux.
- [ ] Isolation : toute lecture/écriture du mode/plan filtre `user_id` (`findByIdAndUserId`).
- [ ] Rétrocompatibilité stricte : sans plan/mode persisté (tous les projets existants) et sans le
      collaborateur de persistance branché (tests historiques), la boucle se comporte **exactement**
      comme avant — panoplie, consigne système et résultat inchangés à l'octet près en ACT.
- [ ] Frontend : le sélecteur de mode est restauré depuis `resume` ; le geste « Approuver & exécuter »
      bascule en ACT et lance un tour d'exécution.

---

## Périmètre

### Hors scope (explicite)

- **F-121-17** (neutralisation fine du crochet END_OF_TURN en mode Réponse/Plan) : hors scope ; ici on
  évite l'interaction en NE seedant PAS `planOfTurn` (le plan soumis/reporté vit à part).
- **F-121-11** (pilotage entre outils, étiquetage des steers) : hors scope.
- Historique/versionnage des plans : on ne garde que **le dernier** plan par thread.
- Aucune capacité IA réimplémentée (V1 gateway pure) : réglage de boucle/prompt/outils + persistance.

---

## Valeurs initiales

| Champ (workspaces) | Valeur initiale | Règle |
|-------|----------------|-------|
| `chat_thread_mode` | `null` | `null` ⇒ ACT (rétrocompat) ; posé en fin de tour au mode du tour |
| `chat_thread_plan` | `null` | `null` ⇒ aucun plan ; JSON du dernier plan non vide et non entièrement terminé |

- `restart` remet les deux à `null`.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Notes |
|---------|-----|------|-------|
| POST | `/api/workspaces/{id}/chat` (+ `/stream`) | Oui | `planSubmitted` ajouté à la réponse / `done` (additif) |
| GET | `/api/workspaces/{id}/chat/resume` | Oui | `mode` + `plan` ajoutés (additif) |
| POST | `/api/workspaces/{id}/chat/restart` | Oui | efface aussi mode + plan |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | ALTER (add columns) + UPDATE/SELECT | `chat_thread_mode` varchar(16), `chat_thread_plan` text |

### Migration Liquibase

- [x] Oui — `129-atelier-thread-mode-plan.xml` (addColumn PostgreSQL + H2, nullable, `dropColumn` en rollback)

### Nouvel outil (contrat modèle, pas runner)

- `exit_plan_mode` : `{ steps: [{title, status?}] }` (même schéma que `set_plan`). **Aucune** mise à jour
  runner : l'outil est traité par la gateway avant le routage par cible (comme `set_plan`).

### Composants Angular (si applicable)

- `atelier-terminal.component` — `@Input() planAwaitingApproval` ; libellé du bouton « Approuver &
  exécuter » quand un plan est en attente.
- `atelier.component` — restaure `mode` depuis `resume` ; `approveAndExecute()` (bascule ACT + tour) ;
  `planAwaitingApproval` posé sur `done.planSubmitted`.
- `atelier.models` / `atelier.service` — `AtelierResume.mode`/`.plan`, `AtelierStreamDone.planSubmitted`.

---

## Préoccupations transversales — analyse d'impact

| Préoccupation | Impactée ? | Composants vérifiés |
|--------------|-----------|---------------------|
| **Auth / Principal** | Non | Aucun nouveau type d'auth. |
| **Contexte tenant** | **Oui** | Persistance mode/plan : `findByIdAndUserId(id, userId)` (filtre `user_id`) dans le store ; `AtelierThreadService.resumeState`/`restart` passent déjà par `requireOwned(userId, workspaceId)` ; `runLoop` charge via `requireOwned` (404 sinon). Aucune lecture/écriture sans filtre `user_id`. |
| **Plans / limites** | Non | Réinjection bornée (≤ 20 étapes, `AtelierPlan.MAX_STEPS`) dans la consigne ; aucun nouvel appel LLM ; aucun impact quota. |
| **Navigation / routing** | **Oui** | Le mode ajoute un contrôle au composer (déjà présent SF-120-02) ; terminaux vérifiés : `atelier-terminal` (composer principal). La mosaïque lecture seule n'initie aucun tour (`!readOnly`), inchangée. |

### Cache de prompt (F-134) — garde-fou explicite

- Le mode persisté pilote la **consigne système** via `ANSWER_PLAN_DIRECTIVE` (déjà le cas SF-120-02) —
  stable dans un mode donné ; ne mute qu'au changement de mode (geste utilisateur, rare). ✓
- Le plan réinjecté va dans la **CONSIGNE du tour** (message), jamais dans le préfixe système — patron
  F-137/F-148. Le préfixe reste byte-stable. ✓

### F-119 / Provider Independence / Gateway-First

- La discipline d'investigation (F-119) et l'ordre des blocs ne sont pas touchés.
- `exit_plan_mode` est un `AgentTool` neutre, traité par la gateway ; **aucun modèle en dur**, tout passe
  par `AIProvider`. Provider Independence intacte.
- Gateway-First : outil d'**organisation**/soumission, aucune logique de « moteur IA ».
- **Aucun composant cluster.**

---

## Plan de test

### Tests unitaires (backend)

- [ ] `AtelierPlan.toJson`/`fromJson` — aller-retour ; JSON corrompu ⇒ `EMPTY` ; `isComplete()`.
- [ ] `buildTools` — `exit_plan_mode` déclaré en `ANSWER_PLAN`, absent en `ACT` (les deux cibles).
- [ ] `exit_plan_mode` end-to-end (stub provider) — plan émis (`onPlan`), `planSubmitted=true`, compte
      rendu « soumis à approbation », `planOfTurn` **reste vide** (pas de blocage complétude).
- [ ] Persistance : après un tour avec `set_plan`, le store reçoit mode + plan ; plan tout `done` ⇒ plan
      `null` ; mode ACT ⇒ mode `null`.
- [ ] Réinjection : un plan persisté est présent dans la **consigne** envoyée au provider et **absent**
      de la consigne système ; `onPlan` émis au début.
- [ ] Non-régression : sans store branché (`AtelierThreadStateStore.NONE`), aucun accès DB, comportement
      d'avant ; sans plan persisté, consigne inchangée.

### Tests d'intégration

- [ ] `GET …/chat/resume` renvoie `mode`/`plan` persistés.
- [ ] `restart` efface `chat_thread_mode` et `chat_thread_plan`.

### Isolation utilisateur

- [x] Applicable — le store persiste via `findByIdAndUserId(id, userId)` ; test : un plan/mode n'est
      jamais écrit ni lu pour un workspace non possédé.

### Frontend

- [ ] `atelier.component` — `resume.mode` restaure le sélecteur ; `approveAndExecute()` bascule ACT +
      lance un tour ; `done.planSubmitted` pose `planAwaitingApproval`.
- [ ] `atelier-terminal.component` — bouton « Approuver & exécuter » quand `planAwaitingApproval`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-120-02` (mode ANSWER_PLAN/ACT) — done.
- `SF-39-13` (AtelierPlan + set_plan + rendu) — done.
- `SF-121-05` (porte de complétude) — done (interaction gérée : `planOfTurn` non seedé).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non concerné).

---

## Notes et décisions

- **D1 — Le thread = le Workspace.** Mode et plan vivent sur `workspaces`, à côté de `chat_thread_summary`
  (F-117). Cohérent avec `restart` (SF-39-04) qui efface le résumé.
- **D2 — Le plan soumis vit à part de `planOfTurn`.** `exit_plan_mode` remplit un champ dédié
  (`submittedPlan`), pas `planOfTurn`, pour ne PAS déclencher la porte de complétude (SF-121-05) sur un
  plan de proposition tout `pending`. La réinjection au tour suivant va, elle aussi, dans la consigne et
  **pas** dans `planOfTurn` (même raison).
- **D3 — Réinjection cache-safe.** Le plan réinjecté augmente la CONSIGNE du tour (patron F-137/F-148),
  jamais le préfixe système ; le cache F-134 tient.
- **D4 — Store optionnel (patron F-148).** `AtelierThreadStateStore` injecté par mutateur, défaut `NONE`
  (no-op) : les tests historiques et toute forme sans store se comportent comme avant, à l'octet près.
- **D5 — Un plan terminé ne se reporte pas.** Persister un plan tout `done` re-nagerait un travail fini ;
  on remet la colonne à `null` (le « dernier plan » signifie « le dernier plan encore actif »).
