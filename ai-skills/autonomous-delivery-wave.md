# Skill — autonomous-delivery-wave (claude-gateway)

> **Le « super prompt » de livraison autonome multi-features pour claude-gateway.**
> Livre une *vague* de N features (cible 10) du backlog `docs/PRODUCT_SPEC.md`, de bout en bout,
> en équipe d'agents coordonnés, en respectant toute la gouvernance `CLAUDE.md` et `docs/PROJECT.md`.
> Ne crée **jamais** de feature nouvelle — consomme uniquement la spec existante.

**Quand l'invoquer** :
- *« Lance une vague autonome de 10 features »*
- *« Vas-y sur le backlog V1, prends tes décisions, livre-moi le récap à la fin »*

Chapeaute `feature-autonome.md` (autonomie intra-feature) et `parallel-frontback-delivery.md`
(parallélisme back/front). Substrat d'exécution = **`.claude/workflows/autonomous-delivery-wave.js`**
(outil Workflow), pas un prompt qui « espère tenir ».

---

## Contexte claude-gateway (différences vs legalCase)

| Dimension | claude-gateway |
|---|---|
| Branche par défaut | **`main`** (merge en squash) |
| Périmètre | Passerelle **puis** traitement documentaire — dans le périmètre (amendement `PROJECT.md`, ADR-011). Gateway-First + Provider Independence obligatoires. **Hors scope : V3** (F-17/F-18) + multi-LLM runtime. |
| Features à construire | Passerelle : F-01→F-12 (F-01/02/04/09/10/11/12 livrées ; **F-03 BYOK parké 🔴** OQ-06). Documentaire : F-05→08, F-13, F-14, F-15, F-16. |
| Staging | `https://portal.ng-itconsulting.com` — deploy via CI `gh workflow run backend.yml --ref main` + `frontend.yml` |
| CI docs-coupling | **Aucun** : `backend.yml` se déclenche sur `backend/** .github/** k8s/**`, **pas `docs/**`** → commiter des docs ne redéploie pas. Pas besoin de grouper les docs pour raison CI (on groupe quand même pour la lisibilité). |
| AWS | profil `legalcase-terraform`, cluster EKS `legalcase-shared`, ns `claude-gateway-staging` |
| Package backend | `fr.claudegateway.<module>` |

## Régime d'autonomie (décision PO 2026-07-01)

| Dimension | Choix |
|---|---|
| **Gate produit réversible** (cohérence écran, nouvelle table simple, choix UX) | **Décider par défaut + tracer l'arbitrage.** Pas de pause. |
| **HALT dur 🔴** | Irréversible / sécurité critique / coûteux non réversible / **dérive hors périmètre V1** → STOP + question, parker, continuer. |
| **Autorité de merge** | Auto-merge `gh pr merge --squash --delete-branch` dès checklists vertes. |
| **File** | Features V1 « À faire » de `PRODUCT_SPEC.md`, ordre dépendances → valeur → effort. |
| **Budget** | Plafond token DUR passé en `args.budget` / directive `+NM` (le Workflow throw au plafond). |

## Principe directeur

> **Une vague = N features livrées et mergées, OU une raison écrite pour chaque non-livrée.**
> La vélocité vient du parallélisme et de la suppression des pauses — **pas** du contournement des gates.

Aucune feature inventée. **REFUS** si une candidate n'est pas dans `PRODUCT_SPEC.md`.
**REFUS** si une candidate relève de V3 (F-17/F-18) ou du multi-LLM runtime (hors périmètre actuel).

---

## Procédure

### Phase 0 — Bootstrap état
0. **Contrôle de vol** — `bash scripts/check-parallel-sessions.sh` (SF-SP-01, lecture seule) :
   `CLEAR` = la vague peut démarrer, `BUSY` = une autre session travaille (worktree actif,
   checkout partagé sale ou en avance, PR ouverte, branche partagée par 2 worktrees, **remise
   `stash` récente**). Le faire à la main, en prose, a déjà conclu « voie libre » alors que
   **3 sessions volaient**. Sur `BUSY` : ne pas écrire dans le checkout partagé, ne jamais
   lancer `prune-stale-worktrees.sh --apply` (il refuse de lui-même, sortie 5).
1. `git fetch origin` → raisonner sur **`origin/main`**, jamais sur le working tree courant.
2. Travail en cours **réellement** non fini : ⚠️ ne pas se fier à `git branch --no-merged` (squash-merge). Source de vérité = statut `PRODUCT_SPEC.md` + `gh pr list --state open`. Pour une branche suspecte : `git cherry origin/main feat/SF-X` (vide = déjà dans main).
3. Lire `MEMORY.md` + mémoires projet (déploiement staging, pivot V1 gateway-pure, git-flow-autonomy). Contraintes dures.

### Phase 1 — File d'attente
Construire la file des N features V1 « À faire » (jamais V2, jamais hors-scope). Ordonner dépendances → valeur → effort. **F-01 (auth) d'abord** (transversal, conditionne tout). Annoncer la file d'entrée.

### Phase 2 — Classification de risque (label, pas gate)
| Label | Critère | Chemin |
|---|---|---|
| 🟢 vert | Spec claire, pattern réutilisable, pas de nouvelle table, pas d'OPEN_QUESTION | Full-auto, back/front //. |
| 🟠 orange | Gate produit *réversible* (cohérence écran, choix UX, table simple) | Décider par défaut + flag. |
| 🔴 rouge | Irréversible / sécurité / coûteux non réversible / OPEN_QUESTION structurante / **hors périmètre V1** | HALT : parker + documenter la question. |

### Phase 3 — Boucle de livraison (le Workflow)
Par feature : cadrage → mini-spec(s) SF (`docs/features/F-XX/`) → readiness checklist → dev (back+front // si contrat API figé, **worktrees isolés**, UUID Liquibase + n° migration pré-assignés) → compile+tests verts → review checklist → release checklist → `gh pr create` → `gh pr merge --squash --delete-branch` (**backend avant frontend**) → fix main-red dans le même flow (2 tentatives/30 min sinon revert + parker).

**Règles dures gateway** : isolation `user_id` sur tout accès données ; **provider via interface `AIProvider`** jamais Anthropic direct ; clé (plateforme/BYOK) jamais exposée ni loggée ; secrets hors du code ; Liquibase uniquement (pas de DDL manuel).

### Phase 4 — Docs groupées + staging unique
1 commit `docs/wave-YYYY-MM-DD-complete` (statuts « Terminée », 1 entrée historique par SF, MAJ `ARCHITECTURE_CANONIQUE` si nouvelles tables). **1 seul** déploiement staging en fin : `gh workflow run backend.yml --ref main` + front, healthcheck `portal.ng-itconsulting.com/api/actuator/health`.

### Phase 5 — Récap unique d'arbitrages
Features livrées (PR + CI verts) ; liste des ARBITRAGES (quoi/pourquoi/alternative écartée/réversibilité) ; features 🔴 parkées + question ; features non atteintes (budget) ; état staging ; risques résiduels.

---

## Concurrence
`CONCURRENCY` (défaut 6) borné par le **rate-limit Anthropic** (TPM/RPM du tier), pas le CPU. Le Workflow met l'excédent en file. Calibrer : monter jusqu'aux 429, redescendre d'un cran.

## Anti-patterns interdits
| ❌ | ✅ |
|---|---|
| Inventer une feature absente de PRODUCT_SPEC | Consommer la spec uniquement |
| Réimplémenter une capacité native de Claude sans valeur ajoutée | Provider-First : relayer/orchestrer, ne pas cloner le LLM |
| Code métier couplé à Anthropic | Interface `AIProvider` |
| Réduire silencieusement la file | Lister les N, livrer ou justifier chacune |
| Pause de confirmation entre features | Enchaîner ; récap unique à la fin |
| Décider en aveugle sur de l'irréversible | HALT 🔴 + parker + tracer |
| Partager une branche entre 2 agents // | 1 worktree isolé par agent |
| Ignorer le plafond budget | Le Workflow throw au plafond — voulu |

## Lancement
```
Workflow({ name: "autonomous-delivery-wave",
           args: { waveSize: 10, dateISO: "2026-07-01", concurrency: 6 } })
# ou file imposée :
Workflow({ name: "autonomous-delivery-wave",
           args: { features: ["F-01","F-02","F-03"], dateISO: "2026-07-01" } })
```
Directive budget `+2M` dans le prompt de lancement pour le plafond dur.

⚠️ **`args` doit être un OBJET JSON, pas une chaîne JSON.** Une file passée en chaîne rend
`args.features` indéfini : la skill recalcule alors sa propre file et **ignore silencieusement** la
tienne.

## Règles apprises en production — à imposer à CHAQUE agent de la vague

Ces cinq règles viennent d'échecs réels. Sans elles, la vague les reproduit.

| Règle | Pourquoi |
|---|---|
| **Lancer Maven au PREMIER PLAN**, jamais en tâche de fond, et **merger avant de rendre la main**. | En tâche de fond les agents calent : ils rendent « terminé » sur un build jamais relevé. Vérifier `origin/main` avant de croire un agent qui se dit terminé ; `TaskStop` avant toute relance, sinon doublon et collision de branches. |
| **`git add` CIBLÉ**, jamais `git add -A`. | Plusieurs sessions partagent le même checkout : un `add -A` emporte le travail en cours d'une autre session dans ton commit. |
| **Ne jamais rédiger de rapport de retour** (`SendFeedback`) — et l'interdire explicitement aux sous-agents. | Décision PO. |
| **Travailler dans un worktree isolé — TOUT le travail, pas seulement le déploiement** : une branche par agent, jamais le checkout partagé, et `git worktree` dédié sur `origin/main` pour build et déploiement. | Le checkout principal est partagé entre sessions : y écrire, y basculer de branche ou y lancer un build casse le WIP d'une autre session. Deux agents sur la même branche = collision (signal W5 du contrôle de vol). |
| **Jamais de `git stash` nu** — ni `git stash pop`. | La pile de remise vit dans le **git-dir commun** : elle est **partagée** par le checkout principal et tous les worktrees. Un `pop` restaure — et **retire** — l'entrée d'une autre session ; une remise anonyme (`WIP on …`) n'est attribuable à personne. Préférer un **commit WIP**. Si une remise est inévitable : `git stash push -u -m "<tag unique>"`, relever son SHA (`git stash list --format='%H %gs'`), restaurer par `git stash apply <sha>` (**jamais `pop`**), puis retrouver l'entrée **par son tag** avant de la `drop`. Le contrôle de vol signale les remises récentes (W6, bloquant) et anonymes (I4). |

Et une règle de fond, qui prime sur la vitesse :

> **Vérifier l'existant avant de cadrer.** L'application est très avancée. Une feature « manquante »
> est souvent une capacité **dormante** — écrite, payée, jamais branchée (diagnostic F-156 :
> *manques réels = 0*). `grep` PRODUCT_SPEC **et le code** d'abord ; les notes TODO/risque peuvent
> être périmées. Brancher ou valider l'existant rapporte plus que développer.
