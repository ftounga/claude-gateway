# Mini-spec — F-164 / SF-164-01 — L'outil `demander` (cœur : backend + contrat)

## Identifiant

`F-164 / SF-164-01`

## Feature parente

`F-164` — Questions structurées à l'utilisateur (parité Claude Code `AskUserQuestion`)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-164-01-outil-demander`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Ajouter au catalogue d'outils de la boucle de l'Atelier un outil **`demander`** qui met le tour **en
pause** pour poser à l'utilisateur **1 à 4 questions structurées** (choix simple ou multiple, option
libre « autre » systématique, une option recommandée par question), collecte la réponse **depuis
n'importe quel appareil**, et **reprend** le tour avec les réponses injectées comme résultat d'outil —
en **réutilisant et généralisant** le mécanisme de pause interrogeable de la porte d'autorisation
(`RunnerConfirmationGate`, F-84) pour supporter **des pauses répétées dans un même tour**.

---

## Comportement attendu

### Cas nominal

1. Le modèle appelle l'outil `demander` avec un lot de **1 à 4 questions**. Chaque question porte :
   un **intitulé** (`header`), un **texte** (`question`), un mode **`multiSelect`** (bool), et une
   liste d'**`options`** (`label` + `description` + `recommended`). L'option libre « autre / tape ta
   réponse » est **implicite** : jamais déclarée par le modèle, toujours acceptée à la réponse.
2. La boucle (`AtelierChatService`) **valide** le lot. Invalide → résultat d'outil d'erreur explicite
   rendu au modèle (échouer bruyamment, aucun tour figé) — le tour continue, le modèle se corrige.
3. Valide → la boucle **suspend** le tour sur la porte généralisée (`RunnerConfirmationGate.awaitAnswer`) :
   la question devient **l'état du tour** (`PendingQuestion`, publiée dans le tour vivant F-84), relayée
   à l'écran via l'événement de flux **`question_request`** ; un spectateur qui se branche après coup la
   retrouve (aparté **`question_state`** avec le temps restant recalculé).
4. L'utilisateur répond via **`POST /workspaces/{id}/chat/answer`** (choix + éventuelle réponse libre).
   L'endpoint vérifie l'**isolation** (`requireTerminalAccess` + `requireOwned` : 404 sur un projet
   d'autrui), compose un compte rendu lisible des réponses, et **tranche** la porte
   (`answerQuestion`). Si la boucle tourne sur un autre pod, la réponse est **diffusée aux pairs**
   (best-effort, même chemin que la confirmation existante).
5. La porte **reprend** : les réponses sont rendues au modèle comme **résultat d'outil** ; l'événement
   **`question_resolved`** retire l'invite de l'écran. Le modèle **raisonne** puis peut **rappeler**
   `demander` — nouvelle pause, nouveau `callId` — autant de fois que nécessaire dans le tour.
6. Sans réponse dans le délai imparti (`timeoutMs` de la porte) → la question expire : résultat d'outil
   « aucune réponse dans le délai » rendu au modèle (le silence ne vaut pas réponse). *(La politique
   décider-par-défaut sur l'option recommandée = SF-164-03, hors de cette SF ; le champ `recommended`
   est néanmoins déjà porté par le contrat pour elle.)*

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Lot d'appel `demander` sans question / plus de 4 questions | Résultat d'outil d'erreur explicite rendu au modèle (pas d'HTTP) | — (tool_result erreur) |
| Question sans texte, ou option sans `label`, ou > 1 option `recommended` | Résultat d'outil d'erreur explicite rendu au modèle | — (tool_result erreur) |
| `POST /answer` corps sans `callId` ou `answers` vides / réponse ni choix ni texte libre | Erreur de validation, message explicite | 400 |
| `POST /answer` sur un projet non possédé (autre `user_id`) | Accès refusé, aucune fuite | 404 |
| `POST /answer` avec un `callId` qui n'attend plus rien (double réponse, réponse tardive, tour fini) | Best-effort : diffusion aux pairs ; si personne n'attend, 409 sans rien casser | 409 |
| Délai dépassé sans réponse | Résultat d'outil « aucune réponse dans le délai » ; le tour reprend | — |

---

## Critères d'acceptation

> Chaque critère est vérifiable et reviewé dans la PR.

- [ ] **CA1 (nominal)** — L'outil `demander` est déclaré au modèle (catalogue) sur tout terminal, avec
  le schéma JSON : `questions` (1-4), chacune `header`/`question`/`multiSelect`/`options[]`
  (`label`/`description`/`recommended`).
- [ ] **CA2 (nominal)** — Un appel `demander` valide suspend le tour, émet `question_request` et rend au
  modèle, après réponse, un résultat d'outil contenant les choix (et l'éventuelle réponse libre).
- [ ] **CA3 (pauses répétées)** — L'outil est appelable **plusieurs fois dans un même tour** : deux
  pauses successives (deux `callId` distincts) sont chacune correctement attendues puis reprises. Un
  `callId` déjà en attente est refusé (pas d'écrasement).
- [ ] **CA4 (validation)** — Un lot invalide (0 ou > 4 questions, question vide, option sans label,
  > 1 recommandée) produit un résultat d'outil d'erreur explicite, sans figer le tour.
- [ ] **CA5 (option libre systématique)** — Une réponse portant uniquement une réponse libre (« autre »),
  sans option cochée, est acceptée et rendue au modèle.
- [ ] **CA6 (option recommandée)** — Le contrat porte `recommended` par question (au plus une) ; il est
  transporté jusqu'à l'événement de flux (servira de défaut à SF-164-03).
- [ ] **CA7 (sécurité — isolation)** — `POST /answer` d'un utilisateur A sur le projet de B renvoie 404
  et **ne tranche jamais** la question de B ; la porte n'accepte une réponse que du **propriétaire**
  du workspace qui a posé la question (test d'isolation).
- [ ] **CA8 (robustesse)** — Une réponse **en double** ou **tardive** ne casse rien : 409 (rien
  n'attend) ou diffusion best-effort, jamais d'exception non maîtrisée ni de reprise erronée.
- [ ] **CA9 (interrogeable / cross-device)** — La question en attente est l'**état du tour** : un
  spectateur qui se branche après coup reçoit `question_state` avec le temps restant recalculé.
- [ ] **CA10 (consigne système)** — Le prompt système porte, **textuellement**, la règle par défaut
  obligatoire, le signal de déclenchement manuel, et la discipline anti-spam (§ Comportement).
- [ ] **CA11 (non-régression)** — La porte d'autorisation existante (`RunnerConfirmationGate` :
  `await`/`resolve`/`cancelWorkspace`), la boucle, `recall`, la compaction et F-84 restent inchangés ;
  tous les tests existants de la porte restent verts.

---

## Périmètre

### Hors scope (explicite)

- **Rendu terminal soigné** (cartes de questions, choix tactiles, champ libre stylé) → **SF-164-02**.
  En 01, un contrat de flux minimal (`question_request` / `question_resolved` / `question_state`) et
  l'endpoint de réponse suffisent pour tester le end-to-end ; **aucune** finition graphique.
- **Politique décider-par-défaut + flag + notification push** → **SF-164-03** (le champ `recommended`
  est déjà porté, mais aucune décision automatique n'est prise en 01).
- **Unification des prompts ad-hoc** (Nouveau départ F-117, reprise F-39, la porte) → **SF-164-04**.
- **Persistance en base** d'une « question en attente » : **non nécessaire en 01** — on réutilise le
  **tour vivant en mémoire** (F-84) + la diffusion cross-pod existante, exactement comme la porte
  d'autorisation. Aucune table, aucune migration (cf. CADRAGE §7 : table reflétée à l'étape 6 *si*
  introduite ; elle ne l'est pas ici).
- **Questions inter-projets** (une question traversant plusieurs postes) — hors périmètre F-164.
- **Exposer `pendingQuestion` dans le snapshot JSON `/turn`** et le `RemoteTurnSource` : la visibilité
  cross-device est déjà assurée par l'aparté `question_state` à l'attache ; l'enrichissement du snapshot
  JSON est laissé à SF-164-02 (rendu) — noté dans « Notes et décisions ».

---

## Valeurs initiales

> Aucune entité persistée créée par cette subfeature. L'état « question en attente » vit **en mémoire**
> dans le tour vivant (F-84), le temps du tour, comme la demande d'autorisation.

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `PendingQuestion.requestedAtMs` | horloge serveur | posé au relais de la question |
| `PendingQuestion.timeoutMs` | `RunnerConfirmationGate.timeoutMs()` | délai de la porte, dit à l'écran |
| `multiSelect` (par question) | `false` | choix simple par défaut si absent |
| `recommended` (par option) | `false` | au plus **une** option recommandée par question |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur / bornes | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-------------------|----------------------------|---------------|
| `questions` (appel outil) | Oui | **1 à 4** | tableau d'objets question | — |
| `question.question` | Oui | ≤ 2000 | texte non vide après trim | trim |
| `question.header` | Non | ≤ 120 | texte court ; défaut = début de `question` | trim |
| `question.multiSelect` | Non | — | booléen (défaut `false`) | — |
| `question.options` | Oui | **1 à 8** | tableau d'options | — |
| `option.label` | Oui | ≤ 200 | texte non vide après trim | trim |
| `option.description` | Non | ≤ 500 | texte libre | trim |
| `option.recommended` | Non | — | booléen ; **au plus une** `true` par question | — |
| `AgentAnswerRequest.callId` | Oui | ≤ 200 | non vide | trim |
| `AgentAnswerRequest.answers` | Oui | **1 à 4** | une entrée par question posée | — |
| `answer.selected` | Cond. | ≤ 8 labels | labels choisis (vide si réponse libre seule) | — |
| `answer.other` | Cond. | ≤ 2000 | réponse libre ; requise si `selected` vide | trim |
| `answer.header` | Non | ≤ 120 | rappel d'intitulé pour le compte rendu lisible | trim |

Notes :
- Une entrée `answer` doit porter **au moins** un choix **ou** une réponse libre non vide (sinon 400).
- Les `selected` sont acceptés tels quels (le rendu et la contrainte stricte d'appartenance aux options
  relèvent de SF-164-02) : robustesse d'abord, on ne casse jamais la reprise sur une divergence.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Isolation |
|---------|-----|------|-----------|
| POST | `/workspaces/{id}/chat/answer` | Oui (JWT) | `requireTerminalAccess(id)` + `requireOwned(user, id)` (404 cross-user) |
| (interne) POST | `/api/internal/atelier/answer` | pair (relay) | diffusion cross-pod best-effort (mirroir de `/confirm`) |

L'outil `demander` n'est **pas** un endpoint : c'est un outil de la boucle, dispatché dans
`AtelierChatService.executeTool` **avant** le routage par cible (comme `set_plan`).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| — | — | **Aucune** : état en mémoire (tour vivant F-84), pas de migration. |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucun schéma modifié (état en mémoire, cf. CADRAGE §7).

### Composants Angular (si applicable)

- **Aucun en 01.** Le rendu (cartes, choix tactiles, champ libre) est SF-164-02. Un contrat de flux
  minimal est fourni ici pour permettre le test end-to-end (événements + endpoint de réponse).

### Classes backend (création / modification)

- `RunnerConfirmationGate` (modif) — **généralisé** : primitive d'attente/isolation/annulation partagée ;
  ajout `awaitAnswer` + `answerQuestions` + `AnswerOutcome` + discriminant `Kind` interne. **API
  existante `await`/`resolve`/`cancelWorkspace`/`Outcome`/`Decision` inchangée.**
- `AtelierQuestionForm` (création) — le lot de questions parsé/validé depuis l'input outil (`from`).
- `AtelierQuestionRejectedException` (création) — validation « échouer bruyamment ».
- `AtelierProgressListener` (modif) — `onQuestion` / `onQuestionResolved` + records
  `AtelierQuestionRequest` / `AtelierQuestionResolved` (défauts neutres, additifs).
- `LiveTurn` (modif) + `PendingQuestion` (création) — `question_request` / `question_resolved` /
  `question_state`, `pendingQuestion` (mirroir de `PendingApproval`).
- `AtelierChatService` (modif) — outil `demander` déclaré (`buildToolsFull`, ajout à
  `ANSWER_PLAN_TOOLS`), dispatch `applyQuestion`, helper `askQuestion`, service `answerQuestion`,
  consigne système.
- `AtelierChatController` (modif) — bridge streaming `onQuestion`→`publishQuestionRequest`,
  record `StreamQuestion`, endpoint `POST /answer`.
- `AgentAnswerRequest` (création, `dto`) — corps de la réponse.
- `RunnerRelayBroadcaster` + `RunnerRelayController` (modif) — `broadcastAnswer` + `ANSWER_PATH` +
  handler de réception (mirroir de `/confirm`).

---

## Plan de test

### Tests unitaires

- [ ] `RunnerConfirmationGateTest` (existant) — **inchangé, reste vert** (non-régression de la porte).
- [ ] `RunnerConfirmationGate.awaitAnswer` — un « répondre » explicite du propriétaire renvoie les
  réponses ; le silence renvoie `TIMEOUT` ; l'interruption (`cancelWorkspace`) renvoie `INTERRUPTED`.
- [ ] `RunnerConfirmationGate` — **pauses répétées** : deux `awaitAnswer` successifs (deux `callId`)
  chacun tranché, chacun résolu correctement ; un `callId` déjà en attente est refusé.
- [ ] `RunnerConfirmationGate` — **isolation** : un autre `user_id` / un autre `workspace_id` ne peut
  pas trancher une question en attente (`NoPendingConfirmationException`).
- [ ] `RunnerConfirmationGate` — une `resolve` (confirmation) sur un `callId` de question, ou l'inverse,
  ne tranche pas (discriminant `Kind`), et la reprise reste sûre.
- [ ] `AtelierQuestionForm.from` — parse un lot valide ; rejette 0/> 4 questions, question vide, option
  sans label, > 1 recommandée ; `multiSelect` défaut `false`.
- [ ] Rendu du compte rendu de réponses (choix multiples, réponse libre seule, mixte).

### Tests d'intégration

- [ ] `POST /workspaces/{id}/chat/answer` → 400 avec corps invalide (callId absent, answers vides,
  entrée sans choix ni texte).
- [ ] `POST /workspaces/{id}/chat/answer` → 404 sur un projet d'un autre utilisateur (isolation).
- [ ] `POST /workspaces/{id}/chat/answer` → 409 quand aucune question n'attend ce `callId` (double /
  tardive), sans effet de bord.
- [ ] Bout-en-bout (service) : un `demander` valide suspend le tour, une réponse le reprend avec le
  compte rendu injecté comme résultat d'outil ; un second `demander` dans le même tour se comporte
  de même (pauses répétées).

### Isolation workspace / user

- [x] **Applicable** — test : un utilisateur du projet A ne peut ni voir ni trancher la question du
  projet B ; la porte exige le couple (`user_id`, `workspace_id`) du poseur.

---

## Dépendances

### Subfeatures bloquantes

- **F-84** (pause/reprise cross-pod, tour vivant) — **livrée** : socle réutilisé/généralisé.
- Aucune autre SF-164 (01 est la première).

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` n'est tranchée par cette SF (pas de sujet ouvert
  touché ; le CADRAGE F-164 est validé PO).

---

## Préoccupations transversales — analyse d'impact

| Préoccupation | Touchée ? | Composants impactés + vérification |
|--------------|-----------|-------------------------------------|
| **Auth / Principal** | Non | Le nouvel endpoint réutilise le Principal existant (`currentUser.requireId()`) et le même contrôle d'accès que `/confirm`, `/steer`, `/interrupt`. Aucun changement du Principal ni de session. |
| **Contexte tenant** | Non (réutilisé) | Isolation résolue par `AtelierAccess.requireTerminalAccess` + `WorkspaceService.requireOwned`, comme tous les endpoints `/chat/*`. Aucun nouveau moyen de résoudre le tenant. La porte exige (`user_id`, `workspace_id`). |
| **Plans / limites** | Non | `demander` ne consomme aucun quota nouveau ni gate (mécanisme d'interaction ; le coût jetons du tour reste compté par la boucle existante). |
| **Navigation / routing** | Non (backend) | Aucune route Angular ajoutée en 01 (rendu = SF-164-02). Un seul endpoint REST ajouté sous le mapping existant `/workspaces/{id}/chat`. |

---

## Notes et décisions

- **Généralisation de la porte (non-régression garantie)** : `RunnerConfirmationGate` porte désormais un
  `CompletableFuture<Object>` générique + un `Kind` (`CONFIRMATION` / `QUESTION`) + une valeur
  d'annulation par entrée. `await`/`resolve`/`cancelWorkspace` gardent une signature et un comportement
  **identiques** (le `cancelValue` d'une confirmation reste `Outcome(DENY, "Tour interrompu…")`). Une
  réponse d'un mauvais `Kind` est refusée (`NoPendingConfirmationException`), donc jamais de reprise
  erronée. C'est la même primitive de pause/isolation/annulation, un payload de plus.
- **Pauses répétées** : elles marchent parce que la boucle traite les `tool_call` **séquentiellement**
  et que la porte est indexée par `callId` (nettoyé en `finally`). Chaque `demander` a son `callId`,
  bloque, reprend, puis le suivant. Rien de spécial à ajouter côté boucle — c'est **testé** au niveau
  de la porte (deux `awaitAnswer` successifs) et du service.
- **Cross-pod de la réponse** : mirroir exact de `confirmToolUse` — résolution locale, sinon
  `broadcastAnswer` best-effort aux pairs, sinon 409. La **visibilité** cross-device de la question est
  gratuite : elle est publiée dans le tampon du tour vivant, que le `RemoteTurnSource` rejoue déjà.
- **Snapshot `/turn`** : l'exposition de `pendingQuestion` dans `AtelierTurnStateResponse` (JSON) est
  laissée à SF-164-02 (elle sert le rendu) ; la visibilité à l'attache est déjà couverte par l'aparté
  `question_state`.
- **Gateway-First / Provider-First** : `demander` est un mécanisme d'interaction (suspendre / présenter /
  collecter / reprendre), aucun « moteur IA ». La décision de poser appartient au modèle.
