# Mini-spec — F-150 / SF-150-06 — Rendu terminal des sous-agents en action (explorations parallèles + `task`)

## Identifiant

`F-150 / SF-150-06`

## Feature parente

`F-150` — Sous-agent `task` capable d'agir, isolé par git-worktree sur le poste

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-150-06-rendu-sous-agents`

---

## Objectif

Rendre **visible dans le terminal que plusieurs sous-agents travaillent** : regrouper les explorations parallèles d'un tour en un bloc « N sous-agents (exploration) » (chacun avec sa question et son état en cours → terminé), et donner au sous-agent `task` un **rendu dédié** (badge « sous-agent · task ») portant sa consigne puis sa **synthèse (branche + diff)**.

---

## Comportement attendu

### Cas nominal

1. **Explorations parallèles** — quand un tour lance **plusieurs `explore` concurrents** (≥ 2 blocs `explore` consécutifs dans la transcription, ce qui correspond exactement au lot exécuté en parallèle par `exploreConcurrently`, SF-39-21), le terminal les **regroupe** en un seul bloc « N sous-agents (exploration) », listant **chaque question**, avec un **état** :
   - **en cours** pendant le tour vivant (streaming) ;
   - **terminé** une fois le tour posé (transcription relue).
   Un `explore` **isolé** (un seul) garde son rendu ligne actuel — aucune régression.
2. **Sous-agent `task`** — un bloc `task` s'affiche avec un **badge « sous-agent · task »** et un **filet** distinctif (charte §8, « filet, jamais fond »), sa **consigne** en en-tête, puis, **à la fin, sa synthèse** (qui porte déjà la **branche + le diff résumé**, SF-150-05) présentée dans le corps du bloc — plus « juste un prompt brut ».
3. La restitution `task` (synthèse + branche/diff) apparaît **au fil de l'eau** comme dans l'historique : la synthèse est relayée à l'écran comme **sortie** du bloc `task`.

### Cas d'erreur / bornes

| Situation | Comportement attendu |
|-----------|---------------------|
| Un seul `explore` dans le tour | pas de regroupement : rendu ligne actuel inchangé |
| `explore` non consécutifs (séparés par un autre outil) | seuls les runs de ≥ 2 `explore` **adjacents** se regroupent ; le reste reste en lignes |
| `task` en échec (refus worktree, runner ancien) | le bloc `task` montre le message d'échec comme corps (honnête), sans badge trompeur de succès |
| Transcription relue d'un ancien tour (avant SF-150-06) | rendu dégradé propre : le compte et l'état « terminé » restent corrects ; aucune donnée nouvelle |

---

## Critères d'acceptation

- [ ] ≥ 2 blocs `explore` consécutifs → **un** bloc groupé « N sous-agents (exploration) » listant chaque question ; N = nombre de blocs du lot.
- [ ] L'état du groupe est **en cours** en streaming, **terminé** en transcription relue.
- [ ] Un `explore` isolé n'est **jamais** regroupé (rendu ligne inchangé).
- [ ] Un bloc `task` porte le badge **« sous-agent · task »** et un filet, sa consigne en en-tête, et sa **synthèse (branche + diff)** dans le corps.
- [ ] La synthèse `task` arrive **au fil de l'eau** (live), pas seulement après rechargement.
- [ ] Aucune couleur/typo hors `DESIGN_SYSTEM.md` ; l'or de marque (`--cg-accent`) n'est **pas** détourné comme aplat de fond (charte §8).
- [ ] `npm run build` vert ; tests de rendu verts.
- [ ] Isolation `user_id`/host inchangée ; aucune donnée sensible nouvelle à l'écran.

---

## Périmètre

### Hors scope (explicite)

- Toute modification de la **sémantique d'exécution** (parallélisme `explore`, sérialité `task`, worktree, budget) : SF-150-01→05, **inchangés**.
- Barre de progression par sous-agent, minuteur par sous-agent, annulation individuelle : non — le but est informatif, sobre.
- Rendu du diff `task` en vue « fichiers modifiés » (F-37) : le worktree n'est **jamais** mergé dans la copie de travail (SF-150-05) ; on montre la **référence** (branche + diff résumé) telle qu'elle remonte dans la synthèse, pas un diff applicable.

---

## Technique

### Composants Angular

- `terminal/sub-agents.ts` (**nouveau**, fonction pure) — `exploreGroupAt(blocks, i)`, `isGroupedExploreMember(blocks, i)`, `subAgentQuestion(block)`, `isTaskBlock(block)`. Testé seul.
- `terminal/chat-steps.ts` — libellé dédié du step `task` (`sous-tâche « … »`) au lieu du cas `default` (prompt brut).
- `terminal/atelier-terminal.component.ts` — expose les helpers (mémo `WeakMap` comme `subtaskCache`).
- `terminal/atelier-terminal.component.html` — regroupement + badge `task`, dans les **deux** boucles (transcription relue + tour vivant), de façon **additive** (les blocs non-`explore`/non-`task` gardent leur markup).
- `terminal/atelier-terminal-subagents.component.scss` (**nouveau**) — styles sobres, ajouté à `styleUrls` (respect du budget F-117/F-146 : feuille dédiée plutôt qu'inflation de la feuille principale).

### Backend (minimal, justifié — 2 changements additifs, display-only)

1. `AtelierChatService` (branche `task` de `runLoop`) : après `task()`, **relayer la synthèse à l'écran** via `listener.onOutput(done.outcome().content())`.
   - **Pourquoi** : la transcription **persistée** stocke déjà la synthèse comme sortie du bloc `task` (`transcript.add(..., outcome.content(), ...)`), mais la **vue vivante** ne la reçoit jamais → le bloc `task` restait « prompt brut » jusqu'au rechargement. Cette ligne rend le live cohérent avec l'historique.
2. `AtelierChatService` (transcription du tour) : le bloc persisté d'un `explore`/`task` porte sa **consigne** (question / prompt) comme `command`, au lieu de `null` (l'ancien `auditTarget` ne les gérait pas) — via un `transcriptCommand(call)` local qui ne change `auditTarget` pour rien d'autre.
   - **Pourquoi** : sans cela, l'historique relu perd la question de chaque sous-agent (« chacun avec sa question » ne vaudrait qu'en live). Aligne live et persisté.

**Garanties communes** :
- **Sans risque cache F-134** : ce sont des **événements/champs de progression et de transcription**, **pas** la consigne système ; le préfixe stable n'est pas touché.
- **Sans changement de sémantique** : `explore`/`task` s'exécutent à l'identique ; le listener `NOOP` (mode `chat` synchrone) ignore `onOutput` par défaut.
- **Aucune** nouvelle table, **aucune** migration Liquibase, **aucun** nouvel événement SSE, **aucun** nouveau DTO, **aucun** composant cluster.

### Préoccupations transversales

- **Navigation / routing** : aucun nouveau chemin, aucun guard. Terminaux impactés (projet, poste, Teams, tuile mosaïque lecture seule) : le rendu est **additif** et piloté par `block.tool` — aucun changement pour les blocs existants. Composants vérifiés : `atelier-terminal.component.html` (2 boucles de blocs), `mosaique/live-turn-view.ts` (réutilise `chatStepsToBlocks`, non modifié).
- **Auth / tenant / plans-limites** : **non touchés** — le rendu ne lit aucune donnée nouvelle, ne résout aucun tenant, n'appelle aucun service de limites.

---

## Plan de test

### Frontend — unitaires (fonction pure `sub-agents.ts`)

- [ ] `exploreGroupAt` : ≥ 2 `explore` consécutifs → groupe avec les N questions dans l'ordre.
- [ ] `exploreGroupAt` : 1 seul `explore` → `null` (pas de groupe).
- [ ] `isGroupedExploreMember` : membres non-ouvrants d'un run ≥ 2 masqués ; l'ouvrant non.
- [ ] `subAgentQuestion` : extrait la question du libellé live (« exploration « q » » → « q »), repli propre en persisté.
- [ ] `isTaskBlock` : vrai pour `tool==='task'`, faux sinon.

### Frontend — rendu (composant terminal)

- [ ] Plusieurs `explore` d'un tour → **un** bloc « N sous-agents (exploration) » avec les questions et l'état.
- [ ] `task` → badge « sous-agent · task » + synthèse/diff dans le corps.

### Frontend — chat-steps

- [ ] Un step `task` produit un libellé dédié (`sous-tâche « … »`), pas le prompt brut par `default`.

### Backend

- [ ] `AtelierChatServiceTaskTest` — chemin `chatStreaming` : `onOutput` reçoit la synthèse (branche + diff) du `task`. Non-régression des tests `task` synchrones existants.
- [ ] Non-régression : `AtelierChatServiceSystemPromptTest` (préfixe/cache) inchangé ; tests de progression inchangés.

### Isolation `user_id`

- [ ] Non applicable directement : rendu pur d'événements déjà émis sous l'isolation du tour ; aucune lecture de données nouvelle, aucun contournement de filtre.

---

## Dépendances

### Subfeatures bloquantes

- `SF-150-02` — **Done** (sous-boucle `task`, outil et step).
- `SF-150-05` — **Done** (synthèse porte branche + diff).
- `SF-39-21` — **Done** (`explore` parallèles).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Regroupement par adjacence** : les `explore` d'un même tour sont émis **consécutivement** par `runLoop` (le lot est exécuté par `exploreConcurrently` avant la boucle d'attachement) ; un run de ≥ 2 blocs `explore` adjacents **est** le lot parallèle. Le frontend n'a besoin d'aucun identifiant de groupe backend — d'où l'absence de drapeau « parallèle » ajouté sur `AtelierStepEvent`.
- **État « en cours → terminé »** : dérivé honnêtement du contexte (tour vivant vs transcription relue), sans réordonner l'émission backend (l'`explore` step arrive déjà après exécution) ni changer la sémantique.
- **Seule ligne backend** : relais `onOutput` de la synthèse `task` (justifié ci-dessus). C'est le seul point où le frontend n'avait aucune source pour la synthèse live.
