# Audit consolidé — Parité Claude Code de l'Atelier (2026-09-23)

> Demandé par le PO : *« je veux que l'application raisonne comme Claude Code ; donne-lui tous les outils »*,
> après avoir constaté qu'une optimisation évidente (déléguer l'audit d'un dépôt à un sous-agent) avait été
> trouvée **en réactif** et non par une **cartographie systématique**.
>
> **Méthode :** fan-out de **6 sous-agents lecture-seule** (outillage · sous-agents · contexte/mémoire ·
> plan/pilotage · recherche/édition · raisonnement/modèle/async) + **1 passe de complétude** (angles morts)
> + **réconciliation** (le web, faussement signalé absent, est en fait présent). S'appuie sur la feuille de
> route **F-121** (20 écarts, écrite le 15/09, commitée ce jour) : **Lot 1 livré (SF-121-01→04)**, le reste
> non fait. Complète (ne remplace pas) F-121.
>
> **Décision PO 2026-09-23 :** le sous-agent capable d'**agir** (F-121-13) sera **maison, isolé par
> git-worktree SUR LE POSTE** — pas Managed Agents cloud. Voir §Décision.

## Déjà à parité (vérifié — NE PAS reconstruire)
Grep/Glob (SF-121-01) · **Web search/fetch** (outils serveur ajoutés à chaque requête, `AnthropicAgentProvider.toApiTools:725`) · Read/Write/Edit (remplacement exact unique) · TodoWrite (`set_plan`) · plan mode (F-120) · steering (F-84) · reprise après coupure (F-84, *au-dessus* de CC) · thinking signé + effort adaptatif + escalade (F-118/119) · retry (SF-121-04) · budgets · compaction proactive+réactive+micro (F-117/119/39) · cache de prompt ~85 % (F-134) · mémoire durable (F-136/137/148) · fan-out lecture parallèle (SF-39-21/22) · interruption (`/interrupt`) · visibilité coût (F-133) · diffs (F-37) · troncature des sorties · style de réponse (SF-121-03) · permissions allow/ask/deny (SF-121-02). Le confinement du poste est retiré **volontairement** (ADR-019) — pas un écart.

## Backlog de parité — écarts réels, priorisés

### P1 — impact fort sur « raisonner comme Claude Code »
| # | Écart | Réf | Que livrer | État |
|---|---|---|---|---|
| P1-a | **Sous-agent qui AGIT** (écrit/exécute une sous-tâche, pas seulement lire) | F-121-13 | Outil `task` maison : sous-boucle `runLoop` panoplie complète routée vers le **même runner**, **isolée par git-worktree sur le poste**, budget déduit du tour, **seule la synthèse remonte**, confirmation/audit réutilisés | **Livré (F-150, 2026-09-24→26) — écart CLOS**, parité vérifiée par SF-121-13 |
| P1-b | **Lecture d'images / PDF** (le modèle ne « voit » pas) | F-121-15 | Bloc `Image`/`Document` dans `AgentContentBlock` (+ `toApiBlock`), `read_file` renvoyant un tool_result multimodal ; relais fournisseur (Provider-First) | À faire |
| P1-c | **Bloc « Environnement »** (date du jour, `git status`/branche, OS, cwd) jamais injecté | — (neuf) | Bloc stable en tête de préfixe (compatible cache F-134), sur les 2 cibles | À faire |
| P1-d | **Porte de complétude générique** : un tour se dit « fini » avec un plan à étapes non DONE | F-121-05 | Contrôle `END_OF_TURN` déterministe : si `planOfTurn` a du PENDING/ACTIVE → bloquer+réinjecter (zéro LLM, in-flux) | À faire |
| P1-e | **Suivi de fraîcheur des fichiers** (modif externe non détectée) | F-121-19 | Empreinte par fichier lu ; au read/edit suivant, si le disque diffère → « relis-le » / refus edit aveugle (réutilise SHA `PromptSourceStore`/`repo_index`) | À faire |
| P1-f | **Édition de gros fichiers** (plafond 512 Kio bloque un vrai fichier) | — (neuf) | Patch ciblé côté runner sans plafonner au champ `content`, ou relever la borne | À faire |
| P1-g | **Routage de modèle** (`explore`→Sonnet, Opus dur) | SF-149-03 | Via `AIProvider`, repli Opus | **En livraison (F-149)** |
| P1-h | **Déléguer l'audit lourd** (`explore` sans bash) | SF-149-02 | Doctrine « lire/auditer un dépôt = `read_file`/`grep`/`glob` délégables » | **Livrée (F-149)** |

### P2 — parité structurante
| # | Écart | Réf |
|---|---|---|
| P2-a | **Shell d'arrière-plan / bash long** (>120 s, dev servers, builds) : `run_in_background` + relecture de sortie | F-121-07 |
| P2-b | **MultiEdit** (éditions atomiques groupées) | F-121-06 |
| P2-c | **ExitPlanMode piloté par le modèle** + plan reporté dans le tour ACT | F-121-10 |
| P2-d | **Persistance du plan entre tours** (réinjecter le dernier plan non vide) | F-121-10 |
| P2-e | **Garde de fraîcheur `write_file`** (refus d'écrasement aveugle) | F-121-19 |
| P2-f | **Brancher les hooks** (F-50 existe, rien n'y est branché ; enum fermée) | F-51 |

### P3 — raffinements / décisions de périmètre
| # | Écart | Réf |
|---|---|---|
| P3-a | **Slash commands / invocation déterministe de skill** dans le composer | — (neuf) |
| P3-b | **@-mentions de fichiers** (épinglage + autocomplétion) | — (neuf) |
| P3-c | Steering entre outils · gabarit de compaction · escalade xhigh/max · clé d'idempotence retry (rejeu sûr des 500) · thinking entrelacé · read-before-edit dur · signaux d'escalade multilingues | F-121-08/09/11/16/19 |
| P3-d | **Client MCP** (consommer des serveurs MCP externes) — **décision de périmètre** (V1 gateway pure ; relayer des outils tiers reste du relais, pas un moteur) | — |

## Décision — le sous-agent qui agit (P1-a / F-121-13) — **LIVRÉE ET CLOSE (2026-09-26)**

> **Clôture SF-121-13 (2026-09-26).** L'écart est **couvert par F-150** (Terminée) : outil `task`
> maison, sous-boucle à panoplie complète (`read_file`/`write_file`/`edit_file`/`multi_edit`/`grep`/
> `glob`/`bash`) routée vers le **même runner** dans un **worktree git isolé** créé sous la racine du
> poste, **coût imputé au tour** (et plafond de délégations par message), **seule la synthèse remonte**
> (avec branche + diff résumé, jamais de merge aveugle), porte de confirmation / audit / permissions
> **réutilisés**, worktree **toujours** démonté, refus propre `not_git` sans git (décision PO : pas de
> repli copie en V1), **aucun SDK, aucun composant cluster**. Vérification critère par critère et
> témoins de test : `docs/features/F-121/SF-121-13-verification-parite-cloture.md`.

Voie **(A) git-worktree sur le poste** retenue (mémoire `decision-sous-agent-worktree-poste`). Cadrée
puis livrée en F-150 ; le cahier des charges d'origine était :
un `task` réutilisant `runLoop` dans un **worktree isolé** créé côté runner ; **seule la synthèse remonte** ;
budget déduit du plafond de tour ; porte de confirmation routée ; **caveat : exige git** → prévoir un repli
(copie de travail isolée) ou restreindre aux projets git, sans jamais écrire hors zone. Gateway-First,
Provider Independence, aucun SDK.

## Ordre de livraison proposé
1. **Finir F-149** (SF-149-03 modèle) — en cours.
2. **P1 « gratuits/forts, faible risque »** d'abord : **P1-c** (bloc environnement), **P1-d** (porte de
   complétude), **P1-e** (fraîcheur fichier) — prompt/logique, peu de surface, fort effet « raisonne comme CC ».
3. **P1-b** (images/PDF) et **P1-f** (gros fichiers) — capacités.
4. **P1-a** (sous-agent `task` worktree) — le gros morceau, cadrage dédié.
5. **P2**, puis **P3**.

## Ce qu'on ne fait pas
Adopter le SDK/Managed Agents (décision PO) ; rétablir le confinement (ADR-019) ; embeddings de rappel
(réserve F-148, déclenché sur preuve mesurée F-140) ; NotebookEdit (public non concerné).
