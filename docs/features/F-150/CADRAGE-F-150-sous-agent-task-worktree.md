# F-150 — Sous-agent `task` capable d'agir, isolé par git-worktree sur le poste

> Cadrage du 2026-09-23, à la demande du PO, sur l'écart **P1-a / F-121-13** de la parité Claude Code.
> Sources : audit consolidé `docs/audits/AUDIT-2026-09-23-parite-claude-code-consolidee.md` (§Décision) et
> feuille de route `docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` (F-121-13).
>
> **Décision PO déjà prise (voie A) :** le sous-agent qui **agit** sera **maison**, isolé par
> **git-worktree SUR LE POSTE** — **pas** Managed Agents cloud, **aucun SDK**, **aucun composant
> cluster**. Ce document cadre ; **il ne livre aucun code, aucune mise à jour runner.**

## 0. Objectif

Doter la boucle maison (`AtelierChatService` + runner distant) d'un outil **`task`** : une **sous-boucle
qui réutilise `runLoop`** avec la **panoplie complète** (lecture **et** écriture **et** `bash`), routée
vers le **même runner du poste**, mais confinée à un **worktree git isolé** créé côté runner. Le budget
est **déduit du plafond du tour** ; **seule la synthèse remonte** à la boucle principale ; la porte de
confirmation, l'audit et la cible RUNNER existants sont **réutilisés**. C'est la brique « déléguer une
sous-tâche qui écrit » qui manque aujourd'hui — `explore` (F-39) ne sait que **lire**.

## 1. Ce qui existe déjà (vérifié — NE PAS reconstruire)

- **`explore`** (`AtelierExploration`, F-39 / SF-39-14) : sous-boucle **lecture seule**, bornée, coût
  imputé au tour, **seule la conclusion remonte**, parallélisable (SF-39-21), modèle configurable
  (`app.atelier.explore-model`, SF-149-03 via `AiAgentProvider`). **`task` en est le pendant écrivain** :
  on **réutilise le patron**, on n'invente pas un système multi-agents.
- **Routage runner par projet** : `ProjectScopes.forProject(project)` (runner) traduit une chaîne
  `project` **relative à la racine du poste** en un dossier de départ et rend un `ToolRouter`
  (`FileTools` + `BashTool` + Teams), **caché par projet** (`byProject`). Un worktree n'est qu'**un autre
  dossier sous la racine** → routage `task` = `forProject(<chemin-du-worktree>)`. **Aucun nouveau
  transport** : le WebSocket unique, la corrélation par `callId`, la cible RUNNER sont réutilisés.
- **Pas de confinement** (F-73 / ADR-019, retiré **volontairement**) : la seule garde restante est de
  **validité** — un chemin doit rester **sous la racine du poste** (`path_outside_root` sinon). Le
  worktree **doit donc vivre sous la racine du poste**.
- **Porte de permission** allow/ask/deny persistée par `(user_id, workspace_id)` (SF-121-02,
  `atelier_permission_rules`, `RunnerConfirmationGate`) + audit. **Réutilisés tels quels.**
- **Budget & coût** : plafond par message/tour (F-118), compteurs thread-safe imputés au tour (patron
  SF-39-21 D4), visibilité coût (F-133).
- **Cache de prompt ~85 %** (F-134) : préfixe **stable**, **rien de volatil** dedans. **Discipline
  d'investigation** (F-119) intacte. **Provider Independence** : le modèle voyage comme **chaîne** via
  `AgentTurnRequest` → `AiAgentProvider` (patron SF-149-03), jamais un modèle en dur.

## 2. `task` ≠ `explore` (distinction nette)

| | `explore` (F-39, livré) | `task` (F-150, ce cadrage) |
|---|---|---|
| Panoplie | **lecture seule** (`read_file`/`list_files`/`search_files`/`grep`/`glob`) | **complète** : + `write_file`/`edit_file`/`bash` (+ outils de volet selon décision §5) |
| Isolation | inutile (ne peut rien casser) | **git-worktree isolé** sous la racine du poste |
| Effets de bord | aucun | écritures/commandes **dans le worktree**, jamais dans la copie de travail réelle de l'utilisateur |
| Parallélisme | oui (lectures concurrentes, SF-39-21) | **série** en V1 (mutations jamais concurrentes ; cf. §7 hors périmètre) |
| Remontée | conclusion | **synthèse** (+ référence de branche/diff, §5) |
| Commun | sous-boucle `runLoop`, budget imputé au tour, cible RUNNER, cache F-134, `AiAgentProvider` | idem |

## 3. Décisions de conception

- **D1 — Sous-boucle, pas de moteur neuf.** `task` réutilise `runLoop` (Gateway-First : on orchestre, on
  ne réimplémente pas un moteur d'IA). La panoplie complète est **routée vers le même runner**, avec le
  `project` pointé sur le **worktree** au lieu du dossier de projet.
- **D2 — Isolation par worktree créé côté runner.** Avant la sous-boucle, la gateway demande au runner de
  **matérialiser un worktree** du projet cible dans un dossier **isolé sous la racine du poste** (p. ex.
  `<racine>/.atelier-worktrees/<taskId>`). La sous-boucle agit **là** ; la copie de travail réelle de
  l'utilisateur n'est **jamais** touchée pendant la tâche.
- **D3 — Repli si pas de git (caveat dur).** Un worktree exige **git** *et* un projet git. Défaut retenu :
  **worktree git**. Si le projet **n'est pas un dépôt git** (ou git absent du poste) → **repli** =
  **copie de travail temporaire isolée** sous la racine, *ou* **refus propre** de `task` avec message
  guidant (« `task` requiert un projet git ; utilise `explore` pour lire, ou initialise git »). Le
  choix repli-copie **vs** refus est une **décision de SF-150-01** (voir points durs §8). **Dans tous les
  cas : jamais d'écriture hors de la zone isolée sous la racine du poste.**
- **D4 — Budget déduit du plafond du tour.** Tous les jetons de la sous-boucle `task` s'**additionnent**
  aux compteurs du **tour** (patron SF-39-14/39-21 D4) : déléguer ne permet **jamais** de passer sous le
  plafond par message. Compteurs et `AtelierProgressListener` **thread-safe**.
- **D5 — Seule la synthèse remonte.** Le contexte principal ne reçoit **pas** les fichiers lus/écrits ni
  les traces d'outils du worktree : uniquement une **synthèse** (ce qui a été fait / vérifié / reste) et
  une **référence de restitution** (branche + diff, §5). C'est le levier de rentabilité (coût en N²,
  F-134) — comme `explore`, mais pour un agent qui écrit.
- **D6 — Confirmation / audit / permissions réutilisés.** Les écritures et `bash` de la sous-boucle
  passent par `RunnerConfirmationGate` + la politique SF-121-02, cible RUNNER, avec audit. **Question à
  trancher en SF-150-03** : dans un worktree **isolé** (donc sûr par construction), applique-t-on
  l'équivalent *acceptEdits* (`ask-before-edit=false`) par défaut pour ne pas noyer l'utilisateur
  d'invites, tout en gardant `bash` sous la porte ? Défaut proposé : **oui** (édition auto dans le
  worktree, `bash`/commandes toujours sous politique) — à valider.
- **D7 — Modèle via `AiAgentProvider`.** `task` prend un modèle configurable `app.atelier.task-model`
  (patron SF-149-03), **repli** sur le modèle principal si non configuré. Provider Independence : le
  modèle voyage comme **chaîne**, aucun couplage direct à Anthropic.
- **D8 — Cache F-134 préservé.** Le préfixe système de la sous-boucle `task` est **stable** (comme
  `AtelierExploration.SYSTEM`) ; **rien de volatil** (taskId, chemin de worktree, horodatage) n'entre
  dans le préfixe — ces valeurs voyagent dans le **message**, pas dans la consigne système. Le préfixe de
  la **boucle principale** n'est **pas** modifié (au plus une **doctrine littérale stable** « quand
  `task` vs `explore` vs `bash` » ajoutée en fin de préfixe, à l'identique à chaque tour — patron
  SF-149-02).
- **D9 — Interruption & échéance partagées + nettoyage garanti.** `stop`/`deadline` du tour sont partagés
  (patron SF-39 D7). Le worktree est **toujours démonté** en fin de tâche — **y compris** sur échec,
  interruption ou mort du tour (« le tour vit dans le flux » : quitter l'écran tue le tour). Un **reap**
  des worktrees orphelins au démarrage du runner / au prochain `task` complète le filet (§8).
- **D10 — Isolation `user_id` inchangée.** La sous-boucle tourne pour le **même** `(user_id, host_id,
  workspace_id)` que le tour. La politique de permission reste résolue par **workspace**, pas par le
  chemin transitoire du worktree (à vérifier explicitement, §6).

## 4. Découpage en subfeatures (avec ordre)

| SF | Titre | Contenu | Dépend de |
|----|-------|---------|-----------|
| **SF-150-01** | **Runner : worktree isolé (create / remove / reap) + repli** | Opération côté runner : créer un worktree git du projet cible **sous la racine du poste**, le retirer, réaper les orphelins ; **repli** copie isolée **ou** refus propre si pas de git (D3). Protocole gateway↔runner. **C'est l'enabler et le point dur.** | — |
| **SF-150-02** | **Backend : la sous-boucle `task`** | Outil `task` = `runLoop` réutilisé, panoplie complète, cible RUNNER avec `project` = chemin du worktree ; budget déduit du tour (D4), isolation d'échec/interruption (D9), **seule la synthèse remonte** (D5). | SF-150-01 |
| **SF-150-03** | **Confirmation / audit / permissions dans le worktree** | Brancher `RunnerConfirmationGate` + politique SF-121-02 sur les écritures/`bash` de la sous-boucle ; trancher l'*acceptEdits-in-worktree* (D6). | SF-150-02 |
| **SF-150-04** | **Modèle `task` (`AiAgentProvider`) + doctrine `task` vs `explore` vs `bash`** | `app.atelier.task-model` + repli (D7) ; doctrine **littérale stable** dans la description d'outil / fin de préfixe (D8, cache F-134 préservé). **Prompt/config seulement** → parallélisable avec SF-150-03. | SF-150-02 |
| **SF-150-05** | **Restitution des changements + nettoyage + plafonds** | Remonter dans la synthèse la **branche/diff** du worktree ; **jamais de merge aveugle** dans la copie de travail de l'utilisateur (D5) ; nettoyage garanti + reap (D9) ; réglages (taille de worktree, nb de `task` par message). | SF-150-02 |
| **SF-150-06** *(option, si UI)* | **Rendu terminal du sous-agent `task`** | Badge/rendu du `task` dans l'atelier (worktree, synthèse, diff), charte `DESIGN_SYSTEM.md`. À planifier si écran nécessaire (règle « backend mergé sans frontend planifié »). | SF-150-02 |

**Ordre :** **SF-150-01** (enabler runner, à livrer en premier) → **SF-150-02** (sous-boucle) →
**{ SF-150-03 ∥ SF-150-04 }** en parallèle (l'un permissions, l'autre prompt/config, surfaces
disjointes) → **SF-150-05** (restitution/nettoyage/plafonds) → **SF-150-06** (option UI). SF-150-04 touche
la doctrine de prompt comme SF-149-02 → **séquencer** sur ce littéral si un autre chantier prompt est en
vol au même moment.

## 5. Restitution des changements (point produit à trancher en SF-150-05)

Un worktree travaille sur une **branche** (ou HEAD détaché). Que deviennent les changements ?
- **Retenu par défaut :** `task` **commit** sur une branche du worktree ; la **synthèse remonte la
  référence (branche + diff résumé)**. La reprise dans la copie de travail réelle (merge/cherry-pick)
  reste un **acte explicite** ultérieur (jamais un merge aveugle dans un arbre potentiellement sale).
- **Écarté :** appliquer automatiquement le diff dans la copie de travail de l'utilisateur pendant la
  tâche (casse l'isolation, risque de conflit avec un travail en cours).

## 6. Préoccupations transversales (liste de composants impactés)

- **Plans / limites** : budget de `task` déduit du plafond de tour (F-118) ; nouveaux réglages
  (`task-model`, plafond de `task`/message, taille de worktree). **Composants** : `AtelierChatService`
  (agrégation coût, `AtelierProgressListener`), `AtelierProperties`/`application.yml`, `QuotaService`,
  visibilité coût F-133.
- **Exécution / runner (DRAPEAU majeur)** : cycle de vie du worktree, chemin **sous la racine du poste**,
  nettoyage/reap, repli git, sérialisation WebSocket. **Composants** : `ProjectScopes`, `PathResolver`,
  `BashTool`, `FileTools`, `ToolRouter`, protocole runner, `RunnerToolGateway` côté gateway. **Voir §8.**
- **Auth / tenant** : la sous-boucle tourne pour le **même** `(user_id, host_id, workspace_id)` ; la
  politique de permission reste résolue par **workspace**, **pas** par le chemin transitoire du worktree.
  **Composants** : `RunnerConfirmationGate`, `AtelierPermissionService` (SF-121-02), résolution de cible
  runner, `AtelierChatService`.
- **Concurrence** : compteurs de jetons, `AtelierProgressListener`, agrégation des résultats sous accès
  concurrent (patron SF-39-21). Cache `ProjectScopes.byProject` : un chemin de worktree transitoire crée
  un `ToolRouter` caché → **évincer à la destruction** du worktree (§8).
- **Navigation** : uniquement si SF-150-06 (UI) est retenue → vérifier les terminaux (projet/poste/Teams,
  mosaïque lecture seule) comme SF-120-02.

## 7. Hors périmètre

- **Managed Agents / Claude Agent SDK / `multiagent`** (décision PO ; `configmap.yaml` coupé reste coupé).
- **Sous-agents cloud / composant cluster** : rien de nouveau côté cluster.
- **Fan-out de plusieurs `task` écrivains en parallèle** (mutations concurrentes) : **série en V1**
  (comme F-39 sérialise les mutations). Extension éventuelle plus tard, hors ce cadrage.
- **Merge automatique** des changements dans la copie de travail de l'utilisateur (§5).
- **Rétablir le confinement** (ADR-019) : le worktree isole par **espace de travail git**, pas par une
  garde de confinement réintroduite.
- **Toucher `explore`** (reste lecture seule) et **le raisonnement F-119** (intact).
- **Support multi-LLM runtime** (hors périmètre produit).

## 8. Points durs runner (SF-150-01) — à traiter au cadrage de la SF

1. **git requis + exécuté côté runner.** Créer/retirer un worktree = **nouvelle opération runner**
   (`git worktree add/remove/prune`, via `bash` élu ou une frame dédiée). Le worktree **doit vivre sous
   la racine du poste** (sinon `ProjectScopes` rejette `path_outside_root`). Emplacement proposé :
   `<racine>/.atelier-worktrees/<taskId>`.
2. **Repli sans git (caveat D3).** Projet non-git **ou** git absent → **copie de travail isolée** sous la
   racine (lente/lourde, pas de « diff/branche » propre) **ou** **refus propre** de `task` avec message
   guidant. **Décision de SF-150-01.** Invariant absolu : **jamais d'écriture hors zone isolée**.
3. **Nettoyage garanti même si le tour meurt.** « Le tour vit dans le flux » : une déconnexion tue le
   processus → worktree orphelin. Prévoir un **reap** (au démarrage runner + au prochain `task`) et un
   `git worktree prune` ; démontage `--force` idempotent.
4. **Restitution.** Faire remonter **branche + diff** dans la synthèse sans faire transiter les fichiers
   (D5) ; ne jamais merger à l'aveugle (§5).
5. **WebSocket unique / sérialisation.** Un seul transport multiplexé par `callId` : la sous-boucle
   `task` partage le canal avec la boucle principale (qui l'attend) ; **pas** de `task` concurrents en
   V1. Éviction du `ToolRouter` caché (`ProjectScopes.byProject`) à la destruction du worktree.
6. **Windows / `cmd.exe`.** `git worktree` marche multi-OS **si git présent** ; le repli copie doit
   fonctionner sur poste Windows/macOS verrouillé (cohérent F-44/F-45).
7. **Exclusions `.runnerignore`** : un worktree neuf ne porte le `.runnerignore` du projet que s'il est
   **suivi par git** ; le vérifier (impact listage seulement, F-73).

## 9. Garde-fous respectés

- **Gateway-First** : `task` orchestre une sous-boucle, ne réimplémente aucun moteur d'IA.
- **Provider Independence** : modèle via `AiAgentProvider` (chaîne), jamais un modèle en dur (D7).
- **Cache F-134** : préfixes stables, rien de volatil ; boucle principale inchangée (D8).
- **F-119** : discipline d'investigation intacte.
- **Isolation `user_id`** : sous-boucle au même tenant, permissions résolues par workspace (D10, §6).
- **Aucun composant cluster.** **Aucun SDK.**
