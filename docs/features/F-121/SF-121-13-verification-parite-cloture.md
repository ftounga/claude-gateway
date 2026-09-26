# Mini-spec — F-121 / SF-121-13 — Sous-agent `task` capable d'agir : vérification de parité et clôture

## Identifiant

`F-121 / SF-121-13`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-13-parite-task`

---

## Objectif

Vérifier, **écart par écart**, que le manque de parité **F-121-13** (« pas de sous-agent Task capable
d'agir ») est bien couvert par **F-150** (Terminée), poser les **témoins de test** des deux critères
encore non prouvés automatiquement, puis **clôturer** l'écart dans la feuille de route, l'audit et
`PRODUCT_SPEC.md`.

---

## Comportement attendu

### Cas nominal

Rien de nouveau n'est **ajouté au produit** : la capacité existe déjà (F-150, PR #825/#827/#829/#834/#836/#842).
Cette subfeature **prouve** que chacun des cinq critères de l'écart F-121-13 (cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md:60` ; audit
`docs/audits/AUDIT-2026-09-23-parite-claude-code-consolidee.md` ligne P1-a) est tenu par le code de
`main`, et **verrouille** par des tests les deux critères qu'aucun test ne gardait :

| # | Critère de l'écart F-121-13 | Où c'est tenu | Témoin |
|---|---|---|---|
| 1 | Outil `task` **maison** (aucun SDK, aucun Managed Agent) | `AtelierTask` + `AtelierChatService.task(...)` | déjà : `AtelierTaskTest`, `AtelierChatServiceTaskTest` |
| 2 | Sous-boucle réutilisant le **patron** de boucle tool-use | `AtelierTask.run(...)` | déjà : `AtelierTaskTest#runsTheToolThenReturnsTheSynthesisAndAggregatesCost` |
| 3 | **Panoplie complète** (lire **et** écrire **et** `bash`) routée vers le **même runner** | `AtelierChatService#taskTools()` + `executeToolOnRunner(..., worktreePath)` | routage : déjà (`taskCreatesAWorktree…`) ; **composition de la panoplie : AUCUN témoin → à poser** |
| 4 | **Budget déduit du plafond du tour** | compteurs du tour additionnés au retour de `task(...)` + plafond `maxDelegations` | **AUCUN témoin pour `task` → à poser** (seul `explore` est gardé, `AtelierChatServiceParallelExploreTest`) |
| 5 | **Seule la synthèse remonte**, confirmation/audit/cible RUNNER réutilisés | `TaskOutcome` → `ToolOutcome.info(synthèse)` ; `executeToolOnRunner` | déjà : `AtelierChatServiceTaskTest`, `AtelierChatServiceWorktreePermissionTest` |

Deux témoins sont donc ajoutés à `AtelierChatServiceTaskTest` :

1. **Panoplie de la sous-boucle** — la panoplie offerte au fournisseur *pendant* la sous-tâche
   contient `read_file`, `write_file`, `edit_file`, `multi_edit`, `grep`, `glob` et `bash`, et
   **ne contient ni `task` ni `explore`** (pas de récursion de sous-agents).
2. **Coût imputé au tour** — les jetons consommés par la sous-boucle sont **ajoutés** aux compteurs
   du tour rendus par `chat(...)`, et le **plafond de délégations par message** (`maxDelegations`)
   s'applique à `task` : au-delà, le second `task` du même message est refusé sans créer de worktree.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Un des cinq critères n'est **pas** tenu par le code de `main` | Aucune clôture : la subfeature s'arrête, l'écart reste ouvert et le manque est décrit dans la feuille de route |
| Plafond de délégations atteint dans le message | Le `task` supplémentaire est refusé (`Limite de délégations atteinte…`), **aucun worktree n'est créé** |
| La sous-boucle échoue / est interrompue | Le worktree est **toujours** démonté, le tour continue (déjà couvert F-150, non re-testé ici) |

---

## Critères d'acceptation

- [ ] Les cinq critères de l'écart F-121-13 sont vérifiés **contre le code de `main`**, chacun avec sa référence `fichier:ligne` ou son test.
- [ ] Un test prouve que la panoplie de la **sous-boucle** `task` porte lecture + écriture + `multi_edit` + `grep`/`glob` + `bash`.
- [ ] Ce même test prouve qu'elle **ne porte ni `task` ni `explore`** (pas de sous-agent récursif).
- [ ] Un test prouve que les jetons de la sous-boucle sont **imputés au tour** (compteurs du tour = principale + sous-boucle).
- [ ] Un test prouve que le **plafond de délégations par message** s'applique à `task`, et qu'un `task` refusé **ne crée aucun worktree**.
- [ ] `docs/features/F-121/CADRAGE-…-feuille-de-route.md` marque **F-121-13 clos par F-150**.
- [ ] `docs/audits/AUDIT-2026-09-23-parite-claude-code-consolidee.md` marque **P1-a livré**.
- [ ] `docs/PRODUCT_SPEC.md` (ligne F-121 + historique) enregistre la clôture de l'écart.
- [ ] **Isolation `user_id`** : aucun nouvel accès aux données ; les tests conservent la résolution par `requireOwned(userId, workspaceId)`.
- [ ] `mvn -pl backend test` (paquet `atelier`) **vert**.

---

## Périmètre

### Hors scope (explicite)

- **Toute évolution du comportement** du sous-agent `task` : cette subfeature n'ajoute aucune capacité, aucun réglage, aucun endpoint.
- **F-121-14** (parallélisme lecture seule des sous-agents) : écart distinct, reste ouvert.
- Le repli « copie de travail isolée » sans git (décision PO 1 de F-150 : **pas de repli en V1**, refus propre `not_git`).
- Frontend : le rendu terminal du sous-agent est déjà livré (SF-150-06) ; aucun composant touché.
- Déploiement : aucun (l'orchestrateur déploie une seule fois en fin de vague).

---

## Valeurs initiales

Non applicable — aucune entité créée, aucun état initial modifié.

## Contraintes de validation

Non applicable — aucun champ d'entrée nouveau. Les bornes existantes de `task` (consigne obligatoire,
`MAX_ANSWER_CHARS = 6000`, `MAX_ITERATIONS = 30`, `maxDelegations` par message) sont **inchangées** et
déjà testées.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun.

### Fichiers touchés

| Fichier | Nature |
|---|---|
| `backend/src/test/java/fr/claudegateway/atelier/AtelierChatServiceTaskTest.java` | +2 témoins de parité |
| `docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` | clôture de l'écart F-121-13 |
| `docs/audits/AUDIT-2026-09-23-parite-claude-code-consolidee.md` | P1-a → livré |
| `docs/PRODUCT_SPEC.md` | ligne F-121 + historique |

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceTaskTest#theSubLoopGetsTheFullToolBeltAndNoNestedSubAgent` — la panoplie vue par la sous-boucle contient `read_file`, `write_file`, `edit_file`, `multi_edit`, `grep`, `glob`, `bash` ; elle ne contient ni `task` ni `explore`.
- [ ] `AtelierChatServiceTaskTest#theSubTaskSpendIsChargedToTheTurn` — les jetons du tour rendus par `chat(...)` incluent ceux de la sous-boucle.
- [ ] `AtelierChatServiceTaskTest#theDelegationCapOfTheMessageAppliesToTask` — au-delà du plafond, le `task` suivant est refusé et **aucun** `worktreeCreate` supplémentaire n'a lieu.

### Tests d'intégration

Non applicable : aucun endpoint, aucune table. La vérification porte sur la boucle d'agent, couverte
par des tests de service (patron existant du paquet `atelier`).

### Isolation workspace / `user_id`

- [x] Applicable — inchangée et conservée : la sous-tâche est résolue par `workspaceService.requireOwned(userId, workspaceId)` et la cible runner porte `(hostId, workspaceId)`. Les témoins vérifient que la cible du worktree porte bien le `workspaceId` du tour (déjà assuré par `taskCreatesAWorktree…`).

---

## Dépendances

### Subfeatures bloquantes

- `F-150` (SF-150-01 → SF-150-06) — statut : **done** (F-150 Terminée).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non touché).

---

## Notes et décisions

- **D1 — emplacement du fichier.** Le gabarit de vague suggérait `docs/features/SF-121-13/` ; la
  convention du dépôt est `docs/features/F-XX/SF-XX-YY-nom.md`. On suit la **convention du dépôt**
  (arbitrage réversible, tracé ici).
- **D2 — clôture sans code produit.** L'écart est **couvert** par F-150 ; ajouter du code pour
  « faire une subfeature » serait une régression de gouvernance. On livre donc ce qui manque
  réellement : les **témoins** des deux critères non gardés, et la **trace de clôture**.
- **D3 — pas de récursion de sous-agents.** Le témoin fige l'absence de `task`/`explore` dans la
  panoplie de la sous-boucle : c'est un **choix** (borne de coût et de temps), pas un oubli, et il
  doit casser bruyamment si quelqu'un l'ouvre un jour.
- **D4 — F-121 reste `En cours`.** Clore F-121-13 ne clôt pas F-121 : F-121-14 (parallélisme),
  F-121-16, F-121-18, F-121-20 restent ouverts.
- **Garde-fous relus** : Gateway-First (aucun moteur d'IA), Provider Independence (le modèle de la
  sous-boucle voyage en chaîne via `AiAgentProvider`), cache F-134 (consigne système de `AtelierTask`
  stable, rien de volatil), V1 gateway pure (aucun OCR/RAG/pgvector/Textract).
