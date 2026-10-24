# Mini-spec — F-210 / SF-210-01 — Découper et suivre les questions (backend)

## Identifiant
`F-210 / SF-210-01` — feature parente `F-210` *Deux questions, deux fils* (cadrage
`CADRAGE-F-210-deux-questions-deux-fils.md`). Branche : `feat/SF-210-01-decouper-demande`.
Statut : `in-progress`. Date : 2026-10-24. Aucune migration (`terminal_json` est du JSON).

## Objectif
Quand un message (ou une interjection) porte au moins deux demandes distinctes, l'agent l'annonce par un
outil serveur sans effet de bord ; la gateway tient l'état des questions du tour, rattache chaque action à
la question en cours, le relaie à l'écran (SSE `questions`) et le range dans `terminal_json.questions`.

## Comportement attendu

### Cas nominal
1. **Deux outils serveur** (paquet `fr.claudegateway.atelier.questions`, catalogue à définitions
   **littérales stables**), donnés dans **tout** terminal (aucune garde d'espace : ils n'écrivent rien) et
   dans le mode « répondre / planifier » (liste blanche `ANSWER_PLAN_TOOLS`) :
   - `decouper_demande({questions:[{id, libelle, depend_de?}], fusion?:{raison}})` — à n'appeler **que**
     si le message ou une interjection contient **au moins deux demandes distinctes**, dans la même
     réponse que les premiers outils (jamais seul). Un nouvel appel (interjection) **ajoute** des questions
     aux ids croissants. `fusion` = une seule réponse attendue (la 2e remplace la 1re ou en dépend
     fortement), avec sa raison.
   - `suivre_question({id, etat})` — `etat` ∈ `EN_COURS | EN_ATTENTE | TERMINEE | NON_ABOUTIE`.
2. **État du tour** (`TurnQuestions`, propre au tour, en mémoire) : à la découpe, si aucune question n'est
   en cours, la première question **sans dépendance** passe `EN_COURS` (pas d'appel de plus) ; les autres
   `EN_ATTENTE`. La question **courante** est la dernière passée `EN_COURS` encore en cours ; une question
   passée `TERMINEE`/`NON_ABOUTIE` rend la main à la précédente encore en cours.
3. **Rattachement des actions** : chaque étape visible (`action` SSE) émise pendant qu'une question est
   courante porte `question: N`, et l'identifiant d'appel est ajouté à `toolUseIds` de la question.
   Attribution **par la question courante** — approximative pour des lectures parallèles (assumé, cadrage
   D2).
4. **SSE `questions`** : à chaque découpe / changement d'état, l'instantané complet
   `{questions:[{id, libelle, dependsOn, state, toolUseIds}], fusion:{raison}|null}` ; un dernier instantané
   à la fin du tour (états clos).
5. **Clôture** : tour abouti → les questions encore `EN_COURS`/`EN_ATTENTE` passent `TERMINEE` (la réponse
   finale couvre toutes les demandes, `MULTI_SUBJECT_DOCTRINE`) ; tour interrompu, coupé au plafond de
   dépense ou au budget d'étapes → `NON_ABOUTIE`. Les états explicitement posés par l'agent sont gardés.
6. **Persistance** : `terminal_json.questions` = l'instantané clos (absent sans découpage). Rien d'autre ne
   change : la réponse, l'essentiel, les suites et l'historique restent ceux d'aujourd'hui.
7. **Sans bruit** : ni étape visible, ni bloc de transcription pour ces deux outils (l'état a son propre
   événement) ; l'appel reste dans la trace rejouée (paire `tool_use`/`tool_result`).
8. **Budget, porte, parallélisme** : classés **LECTURE** par `JourneyRiskClassifier` (jamais refusés par la
   porte du mode guidé) ; ils ne cassent pas un groupe de lectures parallèles (F-195) — ils sont sautés
   par la constitution du groupe ; une étape dont **tous** les appels sont des outils de questions rend
   l'étape au budget (au plus 2 fois par tour).

### Cas d'erreur (résultat d'outil en erreur, jamais une exception)
| Situation | Comportement |
|---|---|
| Première découpe avec moins de 2 questions et sans fusion | erreur : « n'appelle cet outil que pour ≥ 2 demandes distinctes » |
| `id` absent, ≤ 0, déjà utilisé ou non croissant | erreur nommant l'id |
| `libelle` vide | erreur ; > 80 caractères → tronqué « … » |
| `depend_de` vers une question inconnue ou elle-même | dépendance ignorée (dit dans le résultat) |
| Plus de 8 questions dans le tour | erreur |
| `suivre_question` avant toute découpe, id inconnu, état inconnu | erreur |

## Contraintes de validation
| Élément | Règle |
|---|---|
| `questions` | 2 à 8 à la première découpe (1+ ensuite, total ≤ 8) |
| `id` | entier > 0, unique, supérieur aux ids déjà posés |
| `libelle` | obligatoire, ≤ 80 caractères (tronqué) |
| `fusion.raison` | optionnelle, ≤ 200 caractères (tronquée) |
| `etat` | `EN_COURS`, `EN_ATTENTE`, `TERMINEE`, `NON_ABOUTIE` (casse indifférente) |

## Critères d'acceptation
- [ ] Un tour où l'agent appelle `decouper_demande` avec 2 questions émet un SSE `questions` (Q1 en cours,
      Q2 en attente) et range `terminal_json.questions`.
- [ ] Une lecture faite pendant Q1 porte `question: 1` dans l'événement `action` et son id d'appel est
      dans `toolUseIds` de Q1 ; après `suivre_question(2, EN_COURS)`, la suivante est rattachée à Q2.
- [ ] Un tour sans découpage : aucun SSE `questions`, `terminal_json` inchangé (pas de clé `questions`).
- [ ] Tour interrompu → questions non closes `NON_ABOUTIE` ; tour abouti → `TERMINEE`.
- [ ] Découpe invalide (1 question, id en double) → résultat d'outil en erreur, le tour continue.
- [ ] La consigne et les définitions d'outils sont identiques d'un tour à l'autre (préfixe stable).
- [ ] Les deux outils ne sont pas refusés par la porte du mode guidé, et une lecture voisine reste groupée.

## Plan de test
- **Unitaires** : `TurnQuestionsTest` (découpe, auto-EN_COURS, dépendance, ids, troncature, fusion,
  suivi, courant/repli, rattachement, clôture abouti/non abouti, JSON), `QuestionToolCatalogTest`
  (définitions stables, noms), `JourneyRiskClassifierTest` (LECTURE).
- **Intégration service** : `AtelierChatServiceQuestionsTest` (fournisseur bouchonné : découpe + lecture →
  SSE `questions`, action rattachée, `terminal_json.questions` ; tour sans découpe inchangé ; outils
  présents et stables dans la requête).
- **Isolation utilisateur** : aucun accès données nouveau — l'état vit dans le tour (clé user+terminal déjà
  portée par la boucle) ; le message est écrit par le chemin existant (`user_id` + `workspace_id`).
- Suite complète `./mvnw test`.

## Impacts
- **Tables** : aucune (`atelier_messages.terminal_json`, clé JSON additive `questions`).
- **Endpoints** : aucun ; nouvel événement SSE `questions`, champ additif `question` sur `action`.
- **Composants** : `atelier.questions.QuestionToolCatalog`, `atelier.questions.TurnQuestions`,
  `AtelierChatService` (outils, routage, rattachement, clôture, persistance), `AtelierProgressListener`
  (`onQuestions`, `AtelierStepEvent.question`), `TurnSignals`, `AtelierChatController` (relais SSE),
  `JourneyRiskClassifier`.

### Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan/limite, de navigation).

## Hors périmètre
La consigne de réponse par question et `answers[]` (SF-210-02) ; l'écran (SF-210-03/04) ; des sous-agents
par question ; un plafond de budget par question.
