# Mini-spec — F-120 / SF-120-02 Un mode explicite « Réponse/Plan » vs « Agir »

## Identifiant

`F-120 / SF-120-02`

## Feature parente

`F-120` — Répondre sans agir : distinguer une question d'un ordre

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-120-02-mode-reponse-plan`

---

## Objectif

> En une phrase : donner à l'utilisateur, à l'image du *plan mode* de Claude Code, un mode explicite
> et déterministe « Réponse/Plan » (l'agent répond / propose un plan sans jamais exécuter de mutation)
> opposé au mode « Agir » (panoplie complète, comportement actuel), porté par chaque requête de tour
> depuis le composer du terminal jusqu'au provider.

---

## Comportement attendu

### Cas nominal

1. Le composer du terminal (composant partagé `atelier-terminal.component`, présent sur les terminaux
   **projet**, **poste** et **Teams**) affiche un **sélecteur** à deux positions : « Réponse/Plan »
   (`ANSWER_PLAN`) et « Agir » (`ACT`). **Défaut = Agir (ACT)** — l'usage actuel n'est pas surpris.
2. En mode **ACT** (défaut), la requête de tour porte `mode = ACT` (ou aucun mode) et le backend se
   comporte **exactement comme aujourd'hui** : `buildTools` expose la panoplie complète (selon la
   cible et les volets sous licence), `buildSystemPrompt` est inchangé. Rétrocompatibilité totale.
3. En mode **ANSWER_PLAN**, la requête de tour porte `mode = ANSWER_PLAN`. Le backend :
   - `buildTools` **retire les outils mutants** et n'expose qu'une **liste blanche** de lecture /
     exploration / organisation : `read_file`, `list_files`, `search_files`, `explore`, `set_plan`.
     Sont donc retirés : `write_file`, `edit_file`, `bash`, ainsi que tous les outils de volet
     (Teams `teams_*`, Radar, `email_me`, publication de page) — voir §Décisions par défaut.
   - `buildSystemPrompt` ajoute une **consigne de mode** (après la doctrine de retenue SF-120-01) :
     « tu réponds à la question ou tu proposes un plan ; tu n'exécutes rien ; propose et attends. »
4. **Repasser en ACT** : le mode voyage avec **chaque** requête de tour. Un bouton
   **« Passer à l'exécution »**, visible uniquement quand le sélecteur est sur « Réponse/Plan »,
   bascule le sélecteur sur `ACT` ; le tour suivant part alors avec `mode = ACT` et la panoplie
   complète. Aucun état de mode n'est persisté côté serveur (per-tour uniquement).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `mode` absent du corps de requête | Traité comme `ACT` (rétrocompatible, comportement actuel) | 200/SSE |
| `mode` avec une valeur inconnue (ex. `"PLAN"`, `"xyz"`) | Désérialisation Jackson rejette la valeur d'enum inconnue → 400 (jamais un mode « inventé » ni un fallback silencieux vers un comportement mutant) | 400 |
| Requête de tour sur un workspace d'autrui | Refus d'isolation `user_id` **inchangé** (`requireOwned` → 404 avant tout calcul de mode/outils) | 404 |
| En `ANSWER_PLAN`, le modèle tente quand même un outil mutant (`write_file`…) | L'outil n'étant pas déclaré, l'appel est traité par le chemin existant « outil non déclaré » (relayé/refusé selon le contrat en place) ; **aucun** outil mutant n'a été exposé au modèle | — |

---

## Critères d'acceptation

> Chaque critère est vérifiable et reviewé dans la PR.

- [ ] **AC1 (backend, ACT défaut absent)** : `buildTools(userId, workspace)` et une requête sans mode
      produisent la panoplie **complète actuelle** (non-régression) — `mode` absent ⇒ `ACT`.
- [ ] **AC2 (backend, ANSWER_PLAN retire les mutants)** : en `ANSWER_PLAN`, `buildTools` n'expose que
      `read_file`/`list_files`/`search_files`/`explore`/`set_plan` (selon la cible) et **jamais**
      `write_file`, `edit_file`, `bash`, ni aucun outil de volet.
- [ ] **AC3 (backend, ANSWER_PLAN garde la panoplie ACT intacte)** : en `ACT`, la panoplie est
      identique à celle d'aujourd'hui sur SANDBOX **et** RUNNER (non-régression stricte).
- [ ] **AC4 (backend, consigne de mode)** : en `ANSWER_PLAN`, la consigne système contient la règle
      « tu réponds / tu proposes un plan, tu n'exécutes rien » ; en `ACT` elle ne la contient pas ;
      la doctrine de retenue SF-120-01 et la discipline SF-119-02 restent présentes dans les deux modes.
- [ ] **AC5 (backend, mode porté jusqu'au provider)** : la requête reçue par le provider
      (`AgentTurnRequest.mode()`) porte le mode choisi ; absent ⇒ `ACT`.
- [ ] **AC6 (frontend, sélecteur)** : le composer affiche un sélecteur « Réponse/Plan » / « Agir »,
      **défaut Agir**, sur les terminaux projet, poste et Teams (composant partagé) ; charte respectée,
      **aucune couleur nouvelle**, police/taille inchangées.
- [ ] **AC7 (frontend, bascule)** : sélectionner « Réponse/Plan » puis envoyer un message émet une
      requête de tour avec `mode = ANSWER_PLAN` ; en « Agir », `mode = ACT`.
- [ ] **AC8 (frontend, « Passer à l'exécution »)** : le bouton n'est visible qu'en « Réponse/Plan »,
      et un clic bascule le sélecteur sur `ACT` (le tour suivant part en `ACT`).
- [ ] **AC9 (isolation)** : l'isolation `user_id` du tour est inchangée (aucune donnée cross-tenant ;
      `requireOwned` reste la première opération du tour) — non-régression.

---

## Périmètre

### Hors scope (explicite)

- **Approbation explicite oui/non du plan** (vrai `ExitPlanMode`) et **persistance du mode
  inter-tours / par thread** : c'est **F-121-10**, pas ici.
- **SF-120-03** (garde-fou par classifieur d'intention) : en réserve, non retenue.
- Le **chemin « Managed Agents »** (`/agent/stream` → `AtelierSessionService.runTaskStreaming`) :
  il ne passe **pas** par `runLoop`/`buildTools`/`buildSystemPrompt`. Le mode n'y est pas câblé dans
  cette SF ; il concerne la boucle maison (chat). Noté comme reste-à-faire éventuel.
- La **mosaïque** : ses tuiles sont en **lecture seule** (`readOnly=true`, pas de composer, aucun
  tour initié) → le sélecteur n'y a pas de place ; aucun changement (voir analyse transversale).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `AgentTurnRequest.mode` | `ACT` | Compact constructor : `mode == null ⇒ ACT` (rétrocompatible) |
| `AtelierChatRequest.mode` (DTO HTTP) | `null` → `ACT` | Champ optionnel ; absent ⇒ `ACT` côté service |
| Sélecteur frontend (`mode` signal) | `ACT` | Défaut « Agir » pour ne pas surprendre l'usage actuel |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `mode` (DTO `AtelierChatRequest`) | Non | enum `{ANSWER_PLAN, ACT}` | `null` ⇒ `ACT` |
| `mode` (`AgentTurnRequest`) | Non | enum `AgentTurnMode {ANSWER_PLAN, ACT}` | `null` ⇒ `ACT` |
| `mode` (frontend `AtelierTurnMode`) | — | union `'ANSWER_PLAN' \| 'ACT'` | défaut `'ACT'` |

Notes :
- Une valeur d'enum inconnue est **rejetée** par la désérialisation (400), jamais interprétée comme
  un mode mutant par défaut.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---------|-----|------|-----------|
| POST | `/api/workspaces/{id}/chat/stream` | Oui | corps `AtelierChatRequest` gagne un champ optionnel `mode` |
| POST | `/api/workspaces/{id}/chat` | Oui | idem (parité non streamée) |

Aucun nouvel endpoint. Aucune table. **Aucune migration Liquibase.**

### Backend — composants impactés

- `fr.claudegateway.agent.AgentTurnMode` — **nouvel enum** `{ANSWER_PLAN, ACT}` (provider-neutre).
- `fr.claudegateway.agent.AgentTurnRequest` — **nouveau champ** `mode` (dernier param du constructeur
  canonique) ; constructeurs de convenance existants conservés (défaut `ACT`).
- `fr.claudegateway.atelier.dto.AtelierChatRequest` — **nouveau champ** optionnel `mode`.
- `fr.claudegateway.atelier.AtelierChatService` :
  - `buildTools(userId, workspace, mode)` — surcharge ; en `ANSWER_PLAN`, filtre par liste blanche.
    L'ancienne signature `buildTools(userId, workspace)` est conservée (délègue en `ACT`).
  - `buildSystemPrompt(userId, workspace, mode)` — surcharge ; en `ANSWER_PLAN`, ajoute la consigne
    de mode. Ancienne signature conservée (délègue en `ACT`).
  - `chat(userId, workspaceId, rawMessage, mode)` / `chatStreaming(..., mode, listener)` — surcharges ;
    anciennes signatures conservées (délèguent en `ACT`, pour MCP launcher et tests).
  - `runLoop(..., mode, ...)` — porte le mode vers `buildTools`/`buildSystemPrompt` et la construction
    d'`AgentTurnRequest`.
  - Nouvelle constante `ANSWER_PLAN_TOOLS` (liste blanche) + `ANSWER_PLAN_DIRECTIVE` (consigne).
- `fr.claudegateway.atelier.AtelierChatController` — `chat` et `stream` passent `request.mode()`.

### Frontend — composants impactés

- `frontend/src/app/core/models/atelier.models.ts` — nouveau type `AtelierTurnMode` + champ `mode?`
  sur `AtelierChatRequest`.
- `frontend/src/app/core/services/atelier.service.ts` — `streamChat(id, message, mode, handlers)` et
  `chat(id, message, mode)` ajoutent `mode` au corps.
- `frontend/src/app/atelier/terminal/atelier-terminal.component.ts` + `.html` + `.scss` — nouveau
  `@Input() mode`, `@Output() modeChange`, `@Output() switchToAct` ; sélecteur `mat-button-toggle-group`
  (même patron et mêmes jetons `--mat-standard-button-toggle-*` que le sélecteur de cible existant, cf.
  DESIGN_SYSTEM §13) + bouton « Passer à l'exécution » (style `.terminal-input button`). Rendu dans le
  composer (`@if (!readOnly)`), donc **absent en mosaïque** par construction.
- `frontend/src/app/atelier/atelier.component.ts` + `.html` — signal `mode` (défaut `'ACT'`) ; bindings
  `[mode]`/`(modeChange)`/`(switchToAct)` ; `startTurn` transmet `this.mode()` à `streamChat`.

---

## Plan de test

### Tests unitaires backend (ciblés atelier/agent)

- [ ] `AtelierChatService.buildTools` — `ANSWER_PLAN` sur RUNNER : n'expose que
      `read_file`/`explore`/`set_plan` (pas `write_file`/`edit_file`/`bash`). (AC2)
- [ ] `AtelierChatService.buildTools` — `ANSWER_PLAN` sur SANDBOX : n'expose que
      `list_files`/`read_file`/`search_files`/`explore`/`set_plan` (pas `write_file`/`edit_file`). (AC2)
- [ ] `AtelierChatService.buildTools` — `ACT` sur RUNNER et SANDBOX : panoplie **identique** à
      l'actuelle (non-régression). (AC3)
- [ ] `AtelierChatService.buildTools` — `null`/absent ⇒ `ACT`. (AC1)
- [ ] `AtelierChatService.buildSystemPrompt` — `ANSWER_PLAN` contient la consigne de mode ;
      `ACT` ne la contient pas ; doctrine SF-120-01 + discipline SF-119-02 présentes dans les deux. (AC4)
- [ ] `AtelierChatService` (via `service.chat(..., ANSWER_PLAN)`) — la requête vue par le stub provider
      porte `mode == ANSWER_PLAN` et une panoplie sans outil mutant ; en `ACT`, `mode == ACT` et
      panoplie complète ; sans mode, `mode == ACT`. (AC5, AC1)
- [ ] `AgentTurnRequest` — mode `null` ⇒ `ACT` (compact constructor). (AC5)

### Tests frontend (Karma)

- [ ] `atelier-terminal.component.spec` — le sélecteur est rendu quand `!readOnly` (défaut valeur
      `ACT`) et absent quand `readOnly`. (AC6)
- [ ] `atelier-terminal.component.spec` — changer le sélecteur émet `modeChange` avec la bonne valeur ;
      « Passer à l'exécution » n'est visible qu'en `ANSWER_PLAN` et son clic émet `switchToAct`. (AC7, AC8)
- [ ] `atelier.service.spec` — `streamChat`/`chat` mettent `mode` dans le corps de requête. (AC7)
- [ ] Build/`npm test` verts (specs des terminaux existantes non régressées, y compris
      `boutons-terminaux-lisibles.spec` sur la lisibilité des toggles). (AC6)

### Isolation utilisateur

- [ ] Applicable — non-régression : `requireOwned(userId, workspaceId)` reste la **première** opération
      du tour, avant tout calcul de mode/outils. Aucun accès aux données n'est ajouté ; le mode ne
      change que la panoplie et le prompt **au sein d'un terminal déjà autorisé** (aucune élévation de
      droits, cf. cadrage §5). (AC9)

---

## Préoccupations transversales — analyse d'impact

### Navigation / routing — **cochée** (déclencheur : contrôle ajouté au composer)

Composants de navigation / terminaux vérifiés et leur état par défaut du sélecteur :

| Terminal | Composant | Composer ? | Sélecteur | Défaut |
|----------|-----------|-----------|-----------|--------|
| Projet | `atelier-terminal.component` (partagé) via `atelier.component` | Oui (`!readOnly`) | Présent | ACT |
| Poste (runner) | même composant partagé (`executionTarget=RUNNER`) | Oui | Présent | ACT |
| Teams | même composant partagé (`[teamsTerminal]=true`) | Oui | Présent | ACT |
| Mosaïque | `mosaique.component` → `atelier-terminal` en `[readOnly]=true` | **Non** (composer masqué) | **N/A** (aucun tour initié depuis une tuile) | — |

Conclusion : les **trois** terminaux interactifs partagent **un seul** composer → le sélecteur est
ajouté **une** fois et apparaît partout où l'on peut envoyer un tour. La mosaïque est en lecture seule
(pas de composer, pas d'envoi) : rien à y ajouter, aucun chemin de navigation existant n'est modifié
(aucune route, aucun guard, aucune redirection ajoutés).

Autres préoccupations : **Auth / Principal** : non (aucun changement d'auth). **Contexte tenant** :
non (`user_id` inchangé). **Plans / limites** : non (le mode restreint des outils au sein d'un
terminal déjà autorisé, il ne touche ni quota ni gate).

---

## Dépendances

### Subfeatures bloquantes

- `SF-120-01` — doctrine « réponds d'abord » — statut : **done** (mergé, PR #633). On construit
  par-dessus sans écraser `RESTRAINT_DOCTRINE`/`GOVERNANCE_PREAMBLE` ni la discipline F-119-02.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

### Décisions par défaut (drapeaux repris dans la PR)

1. **Outils retirés en `ANSWER_PLAN`** : liste blanche stricte
   `read_file`/`list_files`/`search_files`/`explore`/`set_plan`. Donc `write_file`, `edit_file`,
   `bash` **et** tous les outils de volet (Teams/Radar/mail/pages) sont retirés. Justification : le
   plus sûr, déterministe, trivial à tester ; le mode est **opt-in** (défaut ACT), donc une
   sur-restriction échoue du bon côté (ne pas agir). `set_plan` est conservé car **organisationnel**,
   pas d'exécution (cœur d'un « plan mode »).
2. **Défaut = ACT** (sélecteur et absence de mode) : rétrocompatibilité, aucun usage surpris.
3. **Portée du mode = par tour** : l'UI renvoie le mode à chaque requête ; **pas** de persistance par
   thread (F-121-10, hors scope).
4. **Mode porté jusqu'au provider** : ajouté à `AgentTurnRequest` (cadrage) même si le provider n'en
   dérive aucun comportement (la panoplie et le prompt encodent déjà le mode) — utile au contrat et
   testable de bout en bout.
