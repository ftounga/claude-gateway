# Mini-spec — F-39 / SF-39-07 · Le moteur est résolu par la gateway

## Identifiant

`F-39 / SF-39-07`

## Feature parente

`F-39` — L'Atelier comme harnais (lot 4 · Écran unique)

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-07-moteur-resolu`

---

## Objectif

Exposer, pour un projet donné, **quel moteur anime son terminal** et **s'il faudrait proposer le
runner ici et maintenant** — pour que l'écran n'ait plus à le déduire de trois signaux épars.

---

## Pourquoi

Aujourd'hui le choix du moteur est **reconstruit côté écran** à partir de la source du projet, de sa
cible d'exécution et de l'état du runner (`alignModeWithSource`, `alignModeWithTarget`,
`assistantModeDisabled`, `terminalModeDisabled`). Cette règle vit à quatre endroits dans un
composant Angular, elle a déjà été inversée une fois (F-31 puis F-38), et elle n'est vérifiable par
aucun test de contrat. Le cadrage F-39 (décision **D1**) fait du moteur un **détail
d'implémentation** : ce qui n'est plus un choix de l'utilisateur devient une **règle**, et une règle
appartient à la gateway.

S'y ajoute la décision **D6** — « le runner est le chemin *recommandé*, jamais le premier pas » : la
proposition d'installer un runner doit tomber **au moment où le bac à sable devient la limite**.
Cette limite (nombre de fichiers montés, projet Git, arborescence tronquée) n'est connue que du
backend.

---

## Comportement attendu

### Cas nominal

`GET /api/workspaces/{id}/engine` — projet possédé, accès Atelier accordé :

```json
{
  "engine": "LOCAL_MACHINE",
  "runnerConnected": true,
  "runnerLastSeenAt": "2026-09-06T09:12:41Z",
  "recommendRunner": false,
  "recommendReason": null
}
```

**Règle de résolution du moteur** (unique, exhaustive) :

| Cible d'exécution du projet | `engine` | Ce qui anime le terminal |
|---|---|---|
| `RUNNER` | `LOCAL_MACHINE` | Boucle maison, outils relayés vers la machine de l'utilisateur |
| `SANDBOX` (ou absente) | `HOSTED_SANDBOX` | Managed Agents, bac à sable hébergé |

Le moteur suit la **cible déclarée du projet**, jamais la présence instantanée d'un runner (voir
« Notes et décisions », D-L4-1). `runnerConnected` et `runnerLastSeenAt` sont rendus **en plus**,
pour que l'écran puisse dire « connecté à votre machine » ou « runner hors ligne » — ils ne changent
pas `engine`.

**Règle de recommandation du runner** (D6). `recommendRunner` vaut `true` **uniquement** si les
trois conditions sont réunies :

1. `engine` = `HOSTED_SANDBOX` (recommander le runner à qui l'utilise déjà n'aurait aucun sens) ;
2. aucun runner connecté pour ce projet (`runnerConnected` = `false`) ;
3. **et** au moins un motif :

| `recommendReason` | Condition | Pourquoi ici |
|---|---|---|
| `GIT` | `source` = `GIT` | Le clone local a tout son sens (D6, dernier tiret) |
| `FILE_LIMIT` | arborescence tronquée **ou** `fileCount` ≥ plafond de montage (`app.atelier.agent.max-session-files`, défaut 300) | Le bac à sable ne monte pas tout le projet : c'est très exactement « le moment où le bac à sable devient la limite » |

`GIT` prime sur `FILE_LIMIT` quand les deux s'appliquent : c'est le motif que l'utilisateur comprend
sans explication. `recommendReason` est `null` dès que `recommendRunner` est `false`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Utilisateur non authentifié | Refus | 401 |
| Utilisateur sans accès Atelier (ni ADMIN ni Gold actif) | `atelier_forbidden` | 403 |
| Projet inexistant | `workspace_not_found` | 404 |
| **Projet appartenant à un autre utilisateur** | `workspace_not_found` — indistinguable d'un projet inexistant, pour ne rien révéler | 404 |

Aucun cas 400 : l'endpoint ne prend aucun paramètre autre que l'identifiant de chemin.

---

## Critères d'acceptation

- [ ] `GET /api/workspaces/{id}/engine` rend `LOCAL_MACHINE` pour un projet en cible `RUNNER`.
- [ ] Il rend `HOSTED_SANDBOX` pour un projet en cible `SANDBOX`, et pour un projet dont la cible est absente (comportement historique).
- [ ] `engine` **ne dépend pas** de la présence d'un runner : cible `RUNNER` + runner déconnecté ⇒ `LOCAL_MACHINE` avec `runnerConnected` = `false`.
- [ ] `runnerConnected` / `runnerLastSeenAt` reproduisent exactement ce que rend `GET /api/workspaces/{id}/runner/status` (même service, pas de seconde règle de fraîcheur).
- [ ] Projet d'archive court, cible `SANDBOX`, aucun runner ⇒ `recommendRunner` = `false`, `recommendReason` = `null` (D6 : le premier projet ne demande rien).
- [ ] Projet Git, cible `SANDBOX`, aucun runner ⇒ `recommendRunner` = `true`, `recommendReason` = `GIT`.
- [ ] Projet d'archive dont l'arborescence est tronquée, ou dont le nombre de fichiers atteint le plafond de montage ⇒ `recommendReason` = `FILE_LIMIT`.
- [ ] Projet Git **et** tronqué ⇒ `recommendReason` = `GIT` (priorité).
- [ ] Projet en cible `RUNNER` ⇒ `recommendRunner` = `false`, quelles que soient la source et la taille.
- [ ] Projet en cible `SANDBOX` avec un runner **connecté** ⇒ `recommendRunner` = `false` : il est déjà appairé, il n'y a rien à proposer.
- [ ] **Isolation `user_id`** : un utilisateur B recevant l'identifiant d'un projet de A obtient 404.
- [ ] Un utilisateur sans accès Atelier obtient 403 avant toute lecture de projet.
- [ ] Aucune migration Liquibase, aucun champ ajouté à une table.

---

## Périmètre

### Hors scope (explicite)

- **Toute modification de l'écran** : SF-39-08 et SF-39-09 consomment ce contrat.
- **Tout changement de comportement du chat ou de l'agent** : aucun endpoint existant n'est touché,
  aucun refus existant n'est levé ni ajouté.
- Bascule de la cible d'exécution : elle existe déjà (`PUT /{id}/execution-target`) et reste un
  **réglage de projet**, jamais un mode.
- Le retrait de la cible `SANDBOX` de la boucle maison (D7) — c'est SF-39-16.
- Toute notion de coût ou de marge : la recommandation est motivée par la **limite technique**
  rencontrée, jamais par le prix.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---|---|---|---|
| GET | `/api/workspaces/{id}/engine` | Oui | Gold actif ou ADMIN (`AtelierAccessService.requireAccess()`) |

### Contrat API (figé — consommé par SF-39-08 / SF-39-09)

```ts
type AtelierEngine = 'LOCAL_MACHINE' | 'HOSTED_SANDBOX';
type AtelierRunnerRecommendation = 'GIT' | 'FILE_LIMIT';

interface AtelierEngineStatus {
  engine: AtelierEngine;
  runnerConnected: boolean;
  runnerLastSeenAt: string | null;   // ISO-8601, null si aucun runner ne s'est jamais signalé
  recommendRunner: boolean;
  recommendReason: AtelierRunnerRecommendation | null;
}
```

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `workspaces` | SELECT | via `WorkspaceService.requireOwned` (double filtre `id` + `user_id`) |
| `runner_tokens` | SELECT | via `RunnerStatusService`, déjà filtré `user_id` + `workspace_id` |

### Migration Liquibase

- [x] Non applicable — lecture seule, aucun champ nouveau.

### Composants Angular

Aucun (subfeature backend).

---

## Plan de test

### Tests unitaires

- [ ] `AtelierEngineServiceTest` — cible `RUNNER` ⇒ `LOCAL_MACHINE`.
- [ ] `AtelierEngineServiceTest` — cible `SANDBOX` ⇒ `HOSTED_SANDBOX`.
- [ ] `AtelierEngineServiceTest` — cible absente (projet antérieur à F-38) ⇒ `HOSTED_SANDBOX`.
- [ ] `AtelierEngineServiceTest` — cible `RUNNER`, runner déconnecté ⇒ `LOCAL_MACHINE` + `runnerConnected=false` (le moteur ne bascule pas).
- [ ] `AtelierEngineServiceTest` — archive courte, `SANDBOX`, pas de runner ⇒ aucune recommandation.
- [ ] `AtelierEngineServiceTest` — projet Git ⇒ `recommendReason=GIT`.
- [ ] `AtelierEngineServiceTest` — arborescence tronquée ⇒ `recommendReason=FILE_LIMIT`.
- [ ] `AtelierEngineServiceTest` — nombre de fichiers au plafond de montage ⇒ `recommendReason=FILE_LIMIT`.
- [ ] `AtelierEngineServiceTest` — Git **et** tronqué ⇒ `GIT` (priorité).
- [ ] `AtelierEngineServiceTest` — `SANDBOX` + runner connecté ⇒ aucune recommandation.
- [ ] `AtelierEngineServiceTest` — cible `RUNNER` ⇒ aucune recommandation.

### Tests d'intégration

- [ ] `GET /api/workspaces/{id}/engine` → 200, corps conforme au contrat, pour le propriétaire Gold.
- [ ] → 404 pour un identifiant inconnu.
- [ ] → 404 pour un projet d'un **autre** utilisateur (isolation).
- [ ] → 403 pour un utilisateur sans accès Atelier.
- [ ] → 401 sans jeton.

### Isolation utilisateur

- [x] Applicable — test dédié : l'utilisateur B ne peut pas lire le moteur d'un projet de A (404).

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-02` (statut runner) — done
- `SF-38-05` (cible d'exécution) — done

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

**D-L4-1 — Le moteur suit la cible déclarée, pas le battement de cœur du runner.** Le cadrage
(§2) dit « runner connecté → boucle maison ; pas de runner → Managed Agents ». Pris à la lettre,
cela ferait **basculer le moteur** dès qu'un runner se déconnecte : le tour suivant partirait dans
un bac à sable **vide**, alors que l'utilisateur croit travailler sur sa machine — exactement le
malentendu que D1 vient supprimer, transposé d'un cran. La cible d'exécution du projet est une
**intention déclarée** (`PUT /execution-target`, F-38) ; c'est elle qui décide. La connexion, elle,
est un **état de santé** : rendue à part, elle permet à l'écran de dire « runner hors ligne »
plutôt que de mentir. *Réversible* : la règle tient en une méthode, et l'inverser ne change aucune
donnée persistée.

**D-L4-2 — `LOCAL_MACHINE` / `HOSTED_SANDBOX` plutôt que `edit` / `exec`.** Les valeurs techniques
actuelles nomment un *geste* (`edit`) et une *phase* (`exec`) — c'est cette confusion entre
comportement et moteur que F-39 corrige. Les nouvelles valeurs nomment **où le code s'exécute**,
qui est la seule chose que l'utilisateur ait jamais à comprendre. Le contrat existant
`WorkspaceExecutionTarget` (`SANDBOX` / `RUNNER`) n'est **pas** renommé : il désigne un réglage de
projet et il est déjà persisté.

**D-L4-3 — La recommandation est une constatation, pas une campagne.** `recommendRunner` ne se
déclenche que sur une **limite réellement rencontrée** (dépôt Git, montage tronqué ou saturé), et
jamais sur le premier projet d'archive. C'est la lettre de D6 : « exiger un `.jar` avant d'avoir
rien montré tuerait la conversion ». L'effet de marge est assumé mais n'est jamais un critère.
