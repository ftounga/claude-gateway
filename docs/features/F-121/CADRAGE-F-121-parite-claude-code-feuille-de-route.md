# F-121 — Feuille de route de parité avec Claude Code (boucle maison)

> Cadrage du 2026-09-15, à la demande du PO : *« Fais l'audit de parité maintenant. Je ne prendrai pas
> le SDK : je garde mon modèle et je réimplémente comme on le fait depuis. »*
> **Décision PO actée : on GARDE la boucle maison (`AtelierChatService` + runner distant) et on
> RÉIMPLÉMENTE pour la parité. Aucune adoption du Claude Agent SDK ni des Managed Agents.**
> **Cadrage seul : chaque lot attend le go du PO.**

## 0. Méthode et défalcation

Audit de parité en quatre angles, lecture seule, contre le comportement réel de Claude Code :
raisonnement/mémoire, panoplie & contrats d'outils, planification/modes/permissions/pilotage,
sous-agents/vérification/formatage/prompt. Chaque écart est référencé `fichier:ligne`.

**Déjà en cours (défalqué de cette feuille de route)** :
- **F-119** — SF-119-01 *livrée* (PR #627, ré-escalade d'effort sur signal + `explore` avec effort non
  nul) ; SF-119-02 (discipline d'investigation + descriptions edit/write), SF-119-03 (preuve↔affirmation :
  fenêtre de traces, tête/queue, digest d'outils en compaction), SF-119-04 (bash rend le partiel),
  SF-119-05 (signal d'état de fichier) *cadrées, en livraison*.
- **F-120** — SF-120-01 (doctrine « réponds d'abord »), SF-120-02 (mode Réponse/Plan vs Agir),
  SF-120-03 (garde-fou) *cadrées*.

**Ce qui est DÉJÀ à parité (à ne pas toucher)** : thinking signé re-émis entre itérations et bon ordre
reasoning→text→tool_use→results (`AnthropicAgentProvider.java:706-714`, `AtelierChatService.java:1006-1014`) ;
`output_config.effort` + `thinking:{type:adaptive}` bien mappés (`:642-652`) ; cache_control sans
invalidation du raisonnement ; appariement `tool_use`/`tool_result` strict ; Read (numéros de ligne,
pagination, marqueur de troncature) ; mécanique edit/chemin/shell saine. **Le raisonnement de base est
bon** ; les vrais écarts sont ailleurs (recherche, permissions, sous-agents, style, mémoire de compaction).

## 1. Dépendance URGENTE (à livrer AVEC F-119, pas dans un lot séparé)

**F-121-00 — L'estimateur de compaction doit compter les traces d'outils.**
`estimateReplayTokens` ne compte que le **texte** (`AtelierCompactionService.java:218-232`) ; les traces
rejouées (jusqu'à 5 tours × 40 000 car.) ne sont pas comptées. SF-119-03 **élargit** la fenêtre de
traces → sans corriger l'estimateur *en même temps*, le seuil de compaction (120k) est franchi de
~50k tokens réels sans se déclencher, et c'est le filet réactif « prompt too long » (`:903-928`) qui
rattrape après coup (latence + tour relancé). **À intégrer dans SF-119-03.** *(Signalé à l'agent F-119.)*

## 2. Lot 1 — les manques qui pèsent le plus, faible risque (PRIORITÉ 1)

| SF | Écart (vs Claude Code) | Notre état (fichier:ligne) | Correctif (boucle maison) |
|---|---|---|---|
| **F-121-01** | **Pas de vrai Grep (ripgrep) ni Glob.** Sur un poste, toute recherche passe par `grep`/`find` bash, dépendant du shell (`cmd.exe` n'a pas grep). `search_files` = sous-chaîne 8 000 car., sandbox seulement. | `AtelierChatService.java:2312-2335,2447-2449` ; runner `RunnerToolGateway.java:150-158` | Outils `grep` (regex, `--include` glob, `-A/-B/-C`, modes files/content/count) **et** `glob` (motif + tri mtime), déclarés **sur poste ET hébergé** ; côté runner, normaliser vers `rg`/`grep -rn` (ou embarquer `rg`), côté sandbox un moteur regex sur l'arbre. **Plus gros levier d'investigation.** |
| **F-121-02** | **Modèle de permission binaire.** Autoriser/refuser seulement, catégories câblées (bash, écritures Teams, pages), **aucun deny**, pas de « toujours autoriser cette commande » persisté, **éditions de fichier jamais confirmées**, blanket remis à zéro chaque tour. | `RunnerConfirmationGate.java:88-92` ; `AtelierChatService.java:1912,1963-1968,708` | Politique **allow/ask/deny par outil** (+ par **préfixe de commande** pour bash) **persistée** par workspace/user ; option « toujours autoriser cette commande » qui écrit une règle ; étendre le gate aux éditions (équivalent *acceptEdits*). **Plus gros écart non couvert.** |
| **F-121-03** | **Prompt principal muet sur le style de réponse.** Pas de consigne de concision, anti-préambule, refs `fichier:ligne`, anti-émoji. La bonne doctrine n'existe que dans la sous-boucle `explore`. | `AtelierChatService.java:2484-2592` ; modèle : `AtelierExploration.java:45-47` | Bloc « Style de réponse » au prompt principal (concision terminal, pas de préambule/politesse, markdown léger, citer `chemin:ligne`, pas d'émoji sauf demande), repris d'`explore`. |
| **F-121-04** | **Aucun retry d'un appel runner transient** (≈17 % d'échecs en prod derrière proxy) : un timeout/indispo est rendu au modèle comme un **négatif**, il conclut faux puis se rétracte. | `AtelierChatService.java:2164-2167,1350-1355` | Retry borné (1-2 essais, backoff court, dans le budget de tour restant) sur `RUNNER_UNAVAILABLE`/`RUNNER_TIMEOUT` avant de rendre la main ; résultat tagué « non concluant (réessayé) ». Complète SF-119-04. |
| **F-121-05** | **Discipline « pas de *fini* sans preuve ».** Au-delà de SF-119-02 : rien n'oblige à exécuter la vérification avant d'affirmer, ni à tenir un todo, ni à résumer ce qui a été vérifié. | prompt `:2497-2505` | Ajouter au prompt : « avant d'affirmer que ça marche, exécute la commande/le test qui le prouve » ; inciter `set_plan` dès 3 étapes ; résumé final « fait / vérifié / reste ». Extension de SF-119-02. |

## 3. Lot 2 — parité structurante (PRIORITÉ 2)

| SF | Écart | Correctif (boucle maison) |
|---|---|---|
| **F-121-06** | Pas de **MultiEdit** (éditions multiples atomiques). | `multi_edit` : tableau `{old,new,replace_all}` sur **une seule** lecture+écriture, refus global si un `old` échoue (réutilise `AtelierFileText.replace`). |
| **F-121-07** | **bash** sans `run_in_background` ni `timeout` paramétrable (clampé 120 s). | Exposer `timeout` (clamp élargi) et `run_in_background` (+ relecture différée) au contrat bash et au protocole runner. |
| **F-121-08** | **Escalade d'effort plafonnée à `high`.** | `escalateEffort` configurable (défaut `high`, jusqu'à `xhigh`/`max`, déjà autorisés `AtelierProperties.java:140`) consommé par `reasoningForIteration` ; nettoyer le commentaire périmé (`:29-32`, streaming F-116 livré). |
| **F-121-09** | **Compaction en prose libre** (pas de gabarit). | Gabarit sectionné du résumé (objectif / fichiers modifiés / décisions / état / prochaines étapes) dans `SUMMARY_SYSTEM_PROMPT` (`:57-63`). Complète le digest d'outils de SF-119-03. |
| **F-121-10** | **Plan mode incomplet** (SF-120-02 : sélecteur + retrait outils mutants, mais pas d'**approbation oui/non** du plan ni **persistance du mode**) ; **todo jeté à chaque tour**. | Ajouter `mode∈{ANSWER,PLAN,ACT}` **persisté par thread** ; étape « plan prêt → approbation → bascule ACT » (vrai ExitPlanMode) ; persister le dernier plan par thread (rejeu modèle + écran). |
| **F-121-11** | **Pilotage** : steers lus seulement à la frontière d'itération, plafond 5, sans étiquetage → ressenti « messages multiples ». | Consulter la file **entre appels d'outils** ; assouplir/coalescer le cap ; **étiqueter** les steers comme interjections utilisateur datées. |
| **F-121-12** | **Prompt système plat** + CLAUDE.md injecté verbatim ; pas de guidance de choix d'outil. | Structurer `buildSystemPrompt` en sections stables (Rôle · Investigation · Style · Outils · Conventions projet), encadrer le CLAUDE.md (préambule SF-120-01), ajouter « quand `explore` vs `bash` vs `task` ». |
| **F-121-13** | **Pas de sous-agent Task capable d'agir** (seul `explore` en lecture seule ; le `multiagent` du SDK est coupé et hors voie). | Outil `task` maison : sous-boucle réutilisant `runLoop` avec panoplie complète routée vers le **même runner**, budget déduit du plafond de tour, **seule la synthèse remonte**. Réutilise confirmation/audit/cible RUNNER. Aucun SDK. |

## 4. Lot 3 — raffinements (PRIORITÉ 3-4)

- **F-121-14** — **Parallélisme lecture seule** : fan-out concurrent des `explore`/`task` en lecture d'un
  même tour (pool borné, ré-ordonné par `callId`) ; mutations en série. (P3)
- **F-121-15** — **Lecture multimodale** : `read_file` détecte le binaire et remonte image/PDF en bloc
  `image`/`document` au modèle (Provider-First : c'est Claude qui « voit »). (P3)
- **F-121-16** — **Thinking entrelacé** (en-tête beta `interleaved-thinking` quand adaptatif) : laisse
  penser entre `tool_use` parallèles. Largement émulé aujourd'hui par la boucle → raffinement. (P3)
- **F-121-17** — **END_OF_TURN** : neutraliser le crochet de relance sur les tours qui répondent à une
  question / en mode ANSWER-PLAN, ou le restreindre aux tours ayant réellement écrit. (P3)
- **F-121-18** — **Estimateur 4→~3,5 car./token** (une fois les traces intégrées, F-121-00). (P3)
- **F-121-19** — **Garde dure read-before-edit** (refuser `edit_file` sans Read récent / sur lecture
  périmée) ; **Write read-before-overwrite**. Prio basse : 0 échec `edit_file` en prod. (P3)
- **F-121-20** — **Thinking préservé inter-tours** (dernier tour seulement) — optionnel, à peser (coût
  jetons, signatures qui expirent). (P4) — **NotebookEdit** : optionnel. (P4)

## 5. Ce qu'on ne fait PAS
- Adopter le Claude Agent SDK / Managed Agents (décision PO). Réactiver le `multiagent` du SDK
  (`configmap.yaml:52-58`, coupé) est hors voie : le sous-agent sera **maison** (F-121-13).
- Ajouter un `budget_tokens` fixe de thinking (régresserait le sens adaptatif du modèle effort).

## 6. Ordre proposé
1. **F-121-00 dans F-119-03** (dépendance dure). 2. **Lot 1** (F-121-01→05). 3. **Lot 2**
(F-121-06→13). 4. **Lot 3** (F-121-14→20). F-121-03/05/12 touchent `buildSystemPrompt` comme
SF-119-02/SF-120-01 → **séquencer** sur ce fichier cœur.

## 7. Préoccupations transversales
- **Auth / tenant** : F-121-02 (politique de permission persistée par workspace/user) → filtrage strict.
- **Navigation** : F-121-10 (mode) ajoute un contrôle au composer → vérifier tous les terminaux.
- **Plans / limites** : F-121-08 (effort) et F-121-13/14 (sous-agents/parallélisme) pèsent sur les
  jetons → surveiller vs quotas et plafonds de tour (F-118).
- **Composants** : `AtelierChatService`, `AnthropicAgentProvider`, `AtelierExploration`,
  `AtelierToolTrace`, `AtelierCompactionService`, `RunnerConfirmationGate`, `RunnerToolGateway`,
  `AgentTurnRequest`, `LiveTurn`, catalogues d'outils, frontend `atelier-terminal` + composer,
  `AtelierProperties`/`application.yml`.
