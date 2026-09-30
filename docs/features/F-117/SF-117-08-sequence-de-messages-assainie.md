# Mini-spec — F-117 / SF-117-08 La séquence de messages envoyée au modèle est toujours valide (correctif de bug prod critique)

## Identifiant

`F-117 / SF-117-08`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-117-08-sequence-assainie`

---

## Contexte — incident prod (fil « agenor »)

Sur le fil « agenor » (prod), **tous les tours** échouaient en `provider_error` : Anthropic renvoyait
un **400 `invalid_request`** sur `claude-opus-5` dès le **premier appel** du tour, et le repli non
streamé échouait aussi. Ce **n'est pas** « prompt too long » (le filet SF-117-02 ne s'engageait pas).

### Cause racine confirmée

`AtelierChatService.replayableHistory` rejoue **un `AgentMessage` par ligne** de `atelier_messages`,
en conservant l'ordre et le rôle de la base, **sans imposer l'alternance user/assistant**. Le code
s'appuyait explicitement sur l'hypothèse, écrite en commentaire (lignes ~1685-1686) :
« Deux messages `user` consécutifs, eux, sont acceptés par le fournisseur (vérifié) ». **Cette
hypothèse est fausse** pour `claude-opus-5` : deux messages de **même rôle consécutifs** (user/user)
provoquent un **400 `invalid_request`**.

Le mécanisme de **cascade** : quand un tour échoue, le message **USER est persisté** (avant la
boucle) mais **aucune réponse ASSISTANT** n'est écrite en cas d'échec. Chaque tour échoué laisse donc
un **USER orphelin**. Dès qu'il y a **≥ 2 USER consécutifs** en base (23:30, 23:31, 23:32…), **tout
rejeu ultérieur** produit des `user` consécutifs → **400 garanti** → chaque nouvelle tentative ajoute
un USER orphelin → **blocage dur + cascade**. Le dernier tour réussi (ASSISTANT à 16 tool_use tous
appariés, texte court) n'était pas en cause : le couplage tool_use/tool_result était intact.

Sources secondaires du même défaut, hors incident mais réelles : un texte assistant final vide suivi
d'un rejeu de trajectoire (qui se termine par un `user` tool_results) puis d'un nouveau `user` ; une
précision (`steer`) ajoutée juste après les `toolResults` (user) ; la passe de synthèse forcée qui
ajoute un `user` après des `toolResults` (user).

`AnthropicAgentProvider.toApiMessages` envoyait la liste **telle quelle** (aucun assainissement) et
`callWithRetry` ne reconnaissait comme 400 récupérable **que** « prompt is too long » (SF-117-02) —
tout autre 400 devenait une `AIProviderException` → `provider_error`, **sans récupération**.

---

## Objectif

> Garantir que la séquence de messages envoyée au fournisseur est **toujours structurellement valide**
> (alternance stricte, démarrage sur `user`, pas de bloc vide), en réparant **rétroactivement** un fil
> déjà cassé, et faire qu'un 400 « requête malformée » **ne tue plus le tour** (récupération bornée).

---

## Comportement attendu

### Cas nominal

- **Volet A — prévention + réparation rétroactive.** Un assainisseur **neutre** (domaine, sans
  dépendance Anthropic) est appliqué au **point de passage unique** `AnthropicAgentProvider.toApiMessages`,
  **avant tout envoi** (streamé, non streamé, synthèse, exploration, compaction). Il garantit, de façon
  **déterministe** et **sans perte de contenu** :
  1. la séquence **commence par un message `user`** (les messages de tête non-`user` sont écartés) ;
  2. **alternance stricte** user/assistant : deux messages de **même rôle consécutifs** sont
     **fusionnés** (concaténation fidèle de leurs blocs de contenu), jamais émis en double ;
  3. **aucun bloc de contenu vide** (bloc texte blanc retiré ; un message vidé de tout bloc est
     écarté) ;
  4. le couplage `tool_use` ↔ `tool_result` existant est **préservé** (la fusion concatène les blocs,
     elle n'en retire ni n'en réordonne aucun) ;
  5. les **consignes d'effort** (message `system` à contenu vide, F-134/SF-134-05) sont **préservées
     telles quelles** (exemptées des règles de placement par le fournisseur) et **jamais fusionnées**.
  Le préfixe stable reste stable (assainissement déterministe) → le cache de prompt n'est pas cassé
  plus que nécessaire. Un fil déjà en mauvais état (USER orphelins d'agenor) **remarche au rejeu**,
  **sans « nouveau départ »** : les USER consécutifs sont fusionnés en un seul `user` valide.

- **Volet B — récupération (cousin de SF-117-02).** `AnthropicAgentProvider.callWithRetry` reconnaît
  un **400 de requête invalide/malformée** (corps `invalid_request` mentionnant `messages`/`role`/
  `content`, insensible à la casse ; distinct de « prompt is too long », vérifié en premier) et le
  traduit en un **signal neutre** `AgentMalformedRequestException` (Provider Independence). Dans
  `AtelierChatService`, le tour l'**intercepte** (à côté du repli prompt-too-long) : il **rebâtit** la
  conversation depuis la base (`buildReplayMessages`, qui abandonne d'éventuels messages en vol du tour
  ayant créé la malformation ; l'assainisseur du provider la ré-assainit) et **relance UNE fois**
  (borné par un drapeau, au plus une ré-assainissement+relance par message). Si ça échoue encore →
  **message clair** à l'utilisateur (`MALFORMED_SEQUENCE_REPLY`), **pas** un `provider_error` sans
  issue.

- **Anti-cascade (choix documenté).** La cascade inter-tours est **neutralisée par l'assainissement au
  rejeu** (Volet A) : même si un tour échoue et laisse un USER orphelin en base, le rejeu du tour
  suivant fusionne les USER consécutifs → séquence valide. On **conserve** donc la persistance du
  message user comme avant (la parole de l'utilisateur reste dans le fil), et l'on s'appuie sur
  l'assainissement déterministe plutôt que sur une non-persistance conditionnelle (plus simple, sans
  risque de perdre la parole de l'utilisateur). Volet B empêche en plus le tour **courant** d'empiler
  un USER supplémentaire (le rebuild relit la base, il n'ajoute rien).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Fil avec ≥ 2 USER consécutifs en base (agenor) | Rejeu assaini → un seul `user` fusionné → envoi valide, le tour repart |
| 400 malformé du fournisseur au 1er appel | `AgentMalformedRequestException` → rebuild + relance une fois → succès |
| 400 malformé persistant (échoue encore après relance) | `MALFORMED_SEQUENCE_REPLY` clair, aucun `provider_error`, pas de cascade |
| Message de tête non-`user` (assistant/effort en tête) | Écarté jusqu'au premier `user` |
| Bloc texte vide | Retiré ; message vidé de tout bloc → écarté ; consigne d'effort (contenu vide) conservée |
| 400 « prompt is too long » (SF-117-02) | **Inchangé** : `AgentPromptTooLongException`, compaction + relance |
| Autre 400 non structurel (ex. modèle inconnu) | **Inchangé** : `AIProviderException` → `provider_error` |

---

## Critères d'acceptation

- [ ] Un assainisseur neutre `AgentMessageSanitizer` (package `fr.claudegateway.agent`) fusionne les
      messages de même rôle consécutifs (concaténation fidèle), écarte les messages de tête non-`user`,
      retire les blocs vides, préserve les consignes d'effort et le couplage tool_use/tool_result.
- [ ] `AnthropicAgentProvider.toApiMessages` applique l'assainisseur **avant** de bâtir le corps ;
      **tous** les chemins d'appel (streamé/non streamé/synthèse/exploration/compaction) en bénéficient.
- [ ] Le calcul des marqueurs de cache s'effectue sur la liste **assainie** et reste déterministe
      (préfixe stable inchangé pour une entrée déjà valide).
- [ ] `AgentMalformedRequestException` (neutre) existe et `callWithRetry` la lève sur un 400
      `invalid_request` structurel (messages/role/content), **après** le test « prompt too long ».
- [ ] `AtelierChatService` intercepte `AgentMalformedRequestException`, rebâtit + relance **une seule
      fois**, puis rend `MALFORMED_SEQUENCE_REPLY` si l'échec persiste (jamais de cascade).
- [ ] Le commentaire faux (« deux user consécutifs sont acceptés ») est corrigé.
- [ ] Isolation `user_id` **inchangée** : le rebuild passe par `buildReplayMessages(userId, workspace)`
      (filtre `user_id` déjà appliqué), aucun nouvel accès aux données sans filtre.

---

## Périmètre

### Hors scope (explicite)

- **Bug distinct signalé (NON traité ici)** : `HttpMessageNotWritableException: No converter for
  [ErrorResponse] ... application/x-ndjson` côté transport runner (`RunnerCallDispatcher.onToolStream`
  / relais ndjson). C'est un défaut de **transport** (le filet global `handleUnexpected` tente de
  sérialiser un `ErrorResponse` objet sur un flux `application/x-ndjson` déjà engagé ;
  `GlobalExceptionHandler.handleAsyncTimeout` gère déjà ce cas pour le seul `AsyncRequestTimeoutException`).
  Il **ne fabrique pas** la séquence de messages invalide qui a causé l'incident (le couplage
  tool_use/tool_result et la structure de conversation sont bâtis indépendamment de la sérialisation de
  l'erreur runner). **À traiter à part** (généraliser la garde ndjson à `handleUnexpected`) — ne pas
  faire dériver le périmètre de ce correctif.
- Pas de « nouveau départ » automatique, pas de suppression de messages (l'affichage garde tout).
- Pas de changement de la compaction (SF-117-01) ni du repli prompt-too-long (SF-117-02).
- Pas de frontend (correctif purement backend, aucun écran).

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| Fusion de messages | Concaténation **fidèle** des listes de blocs (aucune perte, aucun réordonnancement, aucune altération de contenu) — correctif de **structure**, pas de contenu |
| Consigne d'effort | `role=system` + contenu vide + `effort` non blanc : conservée telle quelle, jamais fusionnée, jamais retirée |
| Bloc texte | Retiré si `text` blanc ; les blocs tool_use / tool_result / reasoning ne sont jamais retirés |
| Détection 400 malformé | corps contient `invalid_request` **et** (`role` ou `messages` ou `content`), insensible à la casse, et **pas** « prompt is too long » |
| Récupération | au plus **une** ré-assainissement + relance par message |

---

## Technique

### Endpoint(s)

Aucun endpoint nouveau ni modifié.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_messages` | SELECT (via `buildReplayMessages`) | filtre `user_id` déjà appliqué, inchangé |

### Migration Liquibase

- [x] Non applicable (aucun changement de schéma).

### Fichiers impactés (backend)

| Fichier | Volet | Rôle |
|---------|-------|------|
| `agent/AgentMessageSanitizer.java` (nouveau) | A | assainisseur neutre + `isValidSequence` (contrat testable) |
| `agent/AnthropicAgentProvider.java` | A + B | applique l'assainisseur dans `toApiMessages` ; lève `AgentMalformedRequestException` sur 400 structurel |
| `agent/AgentMalformedRequestException.java` (nouveau) | B | signal neutre distinct |
| `atelier/AtelierChatService.java` | A + B | corrige le commentaire ; intercepte le signal, rebâtit + relance une fois, `MALFORMED_SEQUENCE_REPLY` |

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `AgentMessageSanitizerTest` — **reproduit la séquence fautive** (contrôle d'utilité) :
      `isValidSequence` renvoie **faux** sur `[assistant, user, user, user]` (USER consécutifs comme
      agenor) et **vrai** après `sanitize` ; les textes des user fusionnés sont **tous préservés**.
- [ ] `AgentMessageSanitizerTest` — assistant à tool_use → tool_results (user) → nouveau user :
      fusionné en un `user` valide, couplage tool_use/tool_result intact.
- [ ] `AgentMessageSanitizerTest` — message de tête assistant écarté ; bloc texte vide retiré ;
      consigne d'effort conservée et non fusionnée ; deux assistants consécutifs fusionnés.
- [ ] `AnthropicAgentProviderTest` — corps envoyé sur entrée `[user, user]` : `messages` a **une**
      entrée `user` fusionnée (les deux textes présents), **aucun** rôle consécutif dupliqué (prouve
      l'assainissement au point d'envoi).
- [ ] `AnthropicAgentProviderTest` — 400 `invalid_request` structurel (`messages: unexpected role`)
      → `AgentMalformedRequestException` (nouveau) ; 400 non structurel (modèle inconnu) →
      `AIProviderException` (repointé) ; 400 « prompt is too long » → `AgentPromptTooLongException`
      (**non-régression** SF-117-02).

### Tests d'intégration (niveau service)

- [ ] `AtelierChatServiceMalformedSequenceTest` — le fournisseur lève `AgentMalformedRequestException`
      **une fois** → rebuild + relance → **succès** (réponse rendue) ; 3 snapshots (malformé, rebuild…),
      un seul USER persisté (pas d'empilement).
- [ ] `AtelierChatServiceMalformedSequenceTest` — le fournisseur lève **deux fois** →
      `MALFORMED_SEQUENCE_REPLY`, pas de `provider_error`, un seul USER persisté (pas de cascade).

### Isolation workspace/utilisateur

- [x] Applicable — le rebuild passe par `buildReplayMessages(userId, workspace)` : filtre `user_id`
      **inchangé**, aucun nouvel accès aux données sans filtre. Vérifié par les tests service (userId
      fixé, repo mocké par workspaceId+userId).

### Non-régression exigée

- [ ] SF-117-01 (compaction) et SF-117-02 (400 too-long) : suites `AtelierChatServiceCompactionTest`,
      `AtelierChatServicePromptOverflowTest`, `AnthropicAgentProviderTest` vertes.
- [ ] Couplage tool_use/tool_result (rejeu de trajectoire) : `AtelierChatServiceTest` vert.
- [ ] Effort par message (F-134) : `anEffortDirectiveIsNeverMarked` et les tests d'effort verts.
- [ ] Recall (F-162) et porte/`demander` (SF-164-01) : suites atelier vertes.

---

## Dépendances

### Subfeatures bloquantes

- `SF-117-01` (compaction) — done
- `SF-117-02` (repli 400 prompt-too-long) — done (ce correctif en est le cousin structurel)

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Placement de l'assainisseur** : au **point de passage unique** du provider (`toApiMessages`),
  plutôt qu'à chaque construction de liste dans le service — un seul choke point couvre tous les
  chemins (synthèse, exploration, compaction) et garantit « avant tout envoi ». L'assainisseur reste
  **neutre** (package `agent`), le provider en dépend (sens autorisé), le domaine ne dépend pas
  d'Anthropic.
- **Anti-cascade par assainissement au rejeu** (et non par non-persistance du message user) : choix
  retenu car déterministe, rétroactif (répare agenor sans intervention) et sans risque de perdre la
  parole de l'utilisateur.
- **Justesse d'abord** : la fusion est une concaténation fidèle des blocs — correctif de **structure**,
  jamais de contenu ; le sens de la conversation est préservé.
