# Mini-spec — F-182 / SF-182-02 Déléguer à un agent nommé

## Identifiant

`F-182 / SF-182-02`

## Feature parente

`F-182` — Les agents nommés (cadrage `CADRAGE-F-182-les-agents-nommes.md`, décisions D1 (annonce), D2, D3)

## Statut

`done`

## Date de création

2026-10-16

## Branche Git

`feat/SF-182-02-deleguer-a-un-agent-nomme`

---

## Objectif

Permettre à l'agent principal de déléguer à un agent nommé (`explore`/`task` avec `agent: "<nom>"`), et au PO
de l'appeler par `/agent <nom> demande` dans la saisie, avec la consigne, les outils et le modèle de l'agent.

---

## Comportement attendu

### Cas nominal

1. **Annonce (D1)** — le catalogue des agents **utilisables** (sujet puis poste, SF-182-01) est joint au
   **message** du tour (jamais à la consigne système : préfixe stable, cache F-134), plafonné à
   **12 agents** (description bornée à 160 caractères ; « … et N autre(s) »), seulement là où la délégation
   est ouverte (`max-delegations > 0`, hors terminal Teams). Rien n'est ajouté quand il n'y a aucun agent :
   message inchangé à l'octet près.
2. **Paramètre `agent` (D2)** — les schémas d'`explore` et de `task` gagnent une propriété facultative
   `agent` (chaîne). Leur description gagne une phrase **fixe** (préfixe stable).
3. **`explore` avec `agent`** — la sous-boucle reçoit la **consigne de l'agent** (encadrée d'un rappel fixe
   des contraintes : lecture seule) au lieu de la consigne générique ; ses outils = ceux de l'agent ∩ outils
   de lecture (un agent d'écriture appelé par `explore` reste en lecture) ; double verrou : l'exécuteur refuse
   tout outil hors de cette liste.
4. **`task` avec `agent`** — même remplacement de consigne et d'outils ; **l'isolation worktree F-150 est
   conservée** (création, restitution branche + diff, démontage garanti) ; `read_only: true` garde la
   variante sans worktree, outils ∩ lecture.
5. **Modèle (D3)** — un agent tourne sur **le modèle de la Forge** (le modèle principal, Opus) par défaut —
   pas sur le modèle d'exploration (Sonnet) ni de sous-tâche ; un modèle moins cher seulement s'il est écrit
   **et** justifié dans le fichier (SF-182-01). Raisonnement : celui de la boucle principale.
6. **`/agent <nom> demande`** — en tête du message, la gateway joint à la consigne du tour un bloc
   déterministe : « délègue CETTE demande à l'agent nommé `<nom>` via `task` (agent d'écriture, poste
   connecté) ou `explore` (sinon), avec `agent: "<nom>"` ». Le message persisté reste la parole de
   l'utilisateur. `/agent` est **réservé** : il n'est jamais traité comme un skill.
7. **Mêmes contraintes d'exécution** — délégations comptées dans le même plafond par message, coût imputé
   au tour, porte de confirmation / audit / permissions inchangés (`executeToolOnRunner`), interruption et
   budget de temps partagés.
8. **Écran** — l'étape et la transcription d'une délégation nommée affichent `agent <nom> — <question>` ;
   le `/` du terminal propose `/agent`, puis la liste des agents après `/agent ` (nom, description, accès,
   origine), lue sur `GET /api/workspaces/{id}/agents` (SF-182-01).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `agent` inconnu | résultat d'outil en erreur : « Agent nommé « x » introuvable. Agents disponibles : … » ; rien ne tourne | — |
| agent inutilisable (consigne vide…) | résultat d'outil en erreur avec ses erreurs | — |
| agent sans aucun outil de lecture appelé par `explore` | erreur explicite | — |
| `/agent inconnu …` | bloc « aucun agent nommé « inconnu » » + liste ; l'agent principal le dit | — |
| `/agent nom` sans délégation possible (`max-delegations = 0`) | bloc : délégation fermée ici, réponds toi-même | — |
| catalogue illisible (poste muet) | aucune annonce ; `agent` → introuvable | — |

---

## Critères d'acceptation

- [ ] CA1 — Le catalogue des agents utilisables est dans le **message** du tour, jamais dans la consigne système ; plafonné à 12 ; absent sans agent.
- [ ] CA2 — `explore`/`task` déclarent la propriété `agent` ; descriptions stables (aucun nom d'agent dedans).
- [ ] CA3 — `explore(agent)` : consigne de l'agent dans le système de la sous-boucle, outils = agent ∩ lecture, modèle = modèle principal (pas le modèle d'exploration).
- [ ] CA4 — `task(agent)` : consigne de l'agent, outils filtrés, worktree créé et démonté, modèle = modèle principal ou modèle justifié de l'agent.
- [ ] CA5 — un outil hors de la liste de l'agent est refusé par l'exécuteur (double verrou).
- [ ] CA6 — agent inconnu / inutilisable → erreur d'outil, aucune sous-boucle lancée.
- [ ] CA7 — `/agent relecteur relis` joint le bloc d'invocation au message ; le message persisté reste « /agent relecteur relis » ; aucun skill « agent » n'est chargé.
- [ ] CA8 — Le terminal propose `/agent` puis les agents ; Tab complète `/agent <nom> `.
- [ ] CA9 — Sans `agent`, `explore`/`task` se comportent exactement comme avant (non-régression).

---

## Périmètre

### Hors scope (explicite)

- Gabarits et création par `gouvernance_proposer(type: AGENT)` → SF-182-03.
- Workflows multi-agents orchestrés, agents exécutés côté serveur (cadrage §5).
- Forcer l'appel d'outil côté fournisseur (`tool_choice`) : le bloc d'invocation suffit (même patron que `/skill`).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|-------|-------------|-------------|--------|---------------|
| `agent` (outil) | Non | 64 | `[a-z0-9][a-z0-9_-]{0,63}` (après `/` éventuel) | minuscules, strip |
| `/agent <nom>` | — | 64 | idem ; suite libre | strip |

---

## Technique

### Endpoint(s)

Aucun nouveau (consomme `GET /api/workspaces/{workspaceId}/agents`, SF-182-01).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable.

### Composants

- Backend : `AtelierChatService` (schémas `explore`/`task`, `explore()`, `readOnlyTask()`, `task()`, annonce dans
  `buildPrompt`, `withInvokedAgent`, étapes/transcription), `AtelierExploration`/`AtelierTask` (surcharge avec
  consigne fournie), `NamedAgentPrompts` (encadrement fixe de la consigne d'un agent).
- Angular : `NamedAgentCatalogService` (front), `AtelierTerminalComponent` (menu `/agent`).

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceNamedAgentTest` — annonce dans le message (pas le système) ; `explore(agent)` consigne/outils/modèle ; `task(agent)` worktree + outils + modèle justifié ; agent inconnu → erreur sans sous-boucle ; `/agent` invocation ; sans agent = comportement d'avant.
- [ ] `NamedAgentPromptsTest` — encadrement lecture / écriture, outils listés.
- [ ] `AtelierExplorationTest`/`AtelierTaskTest` — la surcharge utilise la consigne fournie.
- [ ] `terminal-agents.spec.ts` — `/agent` proposé, puis les agents ; Tab complète.

### Tests d'intégration

- [ ] Couvert par `NamedAgentCatalogApiIntegrationTest` (SF-182-01) pour l'endpoint consommé ; la délégation est
  testée au niveau service (fournisseur IA simulé), comme `explore`/`task` (F-39/F-150).

### Isolation

- [x] Applicable — l'agent est chargé pour le terminal du tour (`requireOwned` en amont), sous `(user_id, workspace_id)` ;
  les outils de la sous-boucle passent par les mêmes gardes que la boucle principale.

---

## Dépendances

- SF-182-01 — done.
- F-150 (`task` worktree), F-39 (`explore`), F-177 (`/skill`) — Terminées.

## Préoccupations transversales

Aucune (cadrage §4).

---

## Notes et décisions

- **A5 (réversible)** — Un agent nommé tourne sur le modèle **principal** (Opus) même appelé par `explore`
  (dont le modèle par défaut est Sonnet) : D3 « Opus par défaut » + règle absolue « justesse avant coût ».
- **A6 (réversible)** — `/agent <nom>` délègue par un bloc d'invocation dans le message (patron `/skill`),
  sans forcer l'appel d'outil côté fournisseur.
- **A7 (réversible)** — La consigne d'un agent est encadrée d'un rappel **fixe** de ses contraintes réelles
  (lecture seule / worktree isolé) : la gateway dit la vérité de la panoplie, l'agent ne peut pas s'en affranchir.
