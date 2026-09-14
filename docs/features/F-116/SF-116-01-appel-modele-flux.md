# Mini-spec — F-116 / SF-116-01 — L'appel modèle en flux

## Identifiant

`F-116 / SF-116-01`

## Feature parente

`F-116` — Le terminal répond mot à mot, comme Claude Code

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-116-01-appel-modele-flux`

---

## Objectif

Ajouter au fournisseur d'agent une variante **streamée** de `nextTurn` qui consomme le flux SSE
d'Anthropic (`stream:true`), émet les deltas de **texte** au fil de l'eau, puis reconstitue **le même
`AgentTurn`** qu'un appel non streamé — sans rien changer au corps de requête, au cache, au retry, ni
au décompte d'usage.

---

## Comportement attendu

### Cas nominal

1. `AtelierChatService` appelle `agentProvider.nextTurn(request, textDeltaSink)` lorsque le drapeau
   `app.atelier.streaming` est actif (défaut `true`).
2. Le fournisseur construit **exactement le même corps** que le chemin non streamé (mêmes
   `cache_control`, `system`, `tools`, `thinking`/`output_config`, `context_management`) et lui ajoute
   `stream:true`.
3. Il consomme le flux SSE : à chaque `content_block_delta` de type `text_delta`, il appelle
   `textDeltaSink` avec le fragment de texte. Le raisonnement (`thinking_delta`) **n'est jamais** émis
   comme texte.
4. À `message_stop`, il reconstitue le JSON complet de la réponse (blocs `text`/`tool_use`/`thinking`/
   `redacted_thinking` dans l'ordre, `stop_reason`, `usage` avec `input_tokens`,
   `cache_creation_input_tokens`, `cache_read_input_tokens`, `output_tokens`) et le passe à `toTurn`,
   **inchangé** — l'`AgentTurn` produit est donc strictement identique à celui du chemin non streamé.
5. `AtelierChatService` relaie chaque delta via `listener.onText(delta)` : la ligne vivante du terminal
   se remplit au fil de l'eau, sur tous les écrans qui consomment `onText`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Drapeau `app.atelier.streaming=false` | On appelle le chemin **non streamé** existant, aucun delta émis |
| Refus temporaire `429`/`529` pendant le streaming | Rejoué selon `AgentRetryPolicy`, **même corps**, exactement comme le non streamé |
| Refus permanent (`400`/`4xx`), erreur serveur (`5xx`), flux coupé, SSE illisible | **Repli** : un appel non streamé (`callWithRetry`) est effectué ; s'il échoue à son tour, `AIProviderException` remonte comme aujourd'hui |
| Interruption du thread pendant une attente de retry | Flag reposé, `AIProviderException` immédiate (inchangé) |
| Réponse sans usage / sans cache | Comptée exactement comme le non streamé (`toTurn` inchangé) |

---

## Critères d'acceptation

- [ ] **Équivalence stricte** : pour un même flux SSE, l'`AgentTurn` reconstitué est égal (texte,
      `toolCalls` avec inputs, `finished`, `truncated`, `reasoning` signé, `inputTokens`,
      `outputTokens`, `cacheReadTokens`, `cacheWriteTokens`) à l'`AgentTurn` qu'un appel non streamé
      produirait pour la réponse équivalente.
- [ ] **Deltas au fil de l'eau** : les `text_delta` sont poussés dans le sink dans l'ordre, un par
      événement, avant la fin du tour ; le raisonnement n'est jamais poussé comme texte.
- [ ] **Corps identique** : le corps envoyé en streamé est celui du non streamé + `stream:true` ; les
      marqueurs `cache_control` sont aux mêmes emplacements (préfixe stable, dernier bloc marqué).
- [ ] **Retry 429/529** : rejoué le même nombre de fois, avec le **même corps**, que le non streamé.
- [ ] **Repli** : un `400`, un `5xx`, un flux coupé ou un SSE illisible déclenchent un appel non
      streamé de repli plutôt que de tuer le tour.
- [ ] **Drapeau** : `app.atelier.streaming` (env `APP_ATELIER_STREAMING`, défaut `true`) commute entre
      streamé et non streamé sans autre changement de comportement.
- [ ] **Raisonnement signé** : les blocs `thinking` (avec `signature`) et `redacted_thinking`
      reconstitués sont identiques à ceux du non streamé et remontent dans `AgentTurn.reasoning()`.
- [ ] **Invariants préservés** : cache de prompt, décompte d'usage, reprise F-84, transport runner,
      isolation `user_id`/`host_id` inchangés (le streaming ne touche que le **moment** d'affichage).

---

## Périmètre

### Hors scope (explicite)

- L'affichage frontend incrémental et sa vérification (**SF-116-02**).
- Le chemin **Managed Agents** (`AnthropicManagedAgentProvider`), déjà streamé côté fournisseur.
- Le débit total (nombre de jetons, durée de calcul) et l'effort de réflexion (F-118).
- Les appels non-atelier de `nextTurn` (Radar, exploration) restent non streamés : pas de spectateur
  char-par-char, blast radius minimal.

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs autorisées | Défaut |
|-------|-------------|--------------------|--------|
| `app.atelier.streaming` | Non | `true` / `false` | `true` |

Notes :
- Une valeur absente/nulle retombe sur `true` (même règle que les autres drapeaux atelier).

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Le flux SSE `POST /api/workspaces/{id}/chat/stream` existant est inchangé côté
contrat ; seul le moment d'émission des événements `text` change.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable — aucun changement de schéma.

### Composants impactés

- `AiAgentProvider` — nouvelle méthode `default AgentTurn nextTurn(AgentTurnRequest, AgentTextListener)`
  (repli par défaut sur le non streamé) + interface fonctionnelle `AgentTextListener`.
- `AnthropicAgentProvider` — override streamé : consommation SSE, émission des deltas, reconstitution
  via `toTurn`, repli non streamé, `callWithRetry` généralisé à un « one attempt ».
- `AnthropicProperties` — inchangé.
- `AtelierProperties` — nouveau champ `streaming` (défaut `true`).
- `AtelierChatService` — appelle la variante streamée quand le drapeau est actif ; garde-fou
  anti-duplication du commentaire (`onText` déjà émis par les deltas).
- `application.yml` — clé `app.atelier.streaming: ${APP_ATELIER_STREAMING:true}`.

---

## Préoccupations transversales

- **Auth / Principal** : non — aucun changement de droits, de session ni de Principal.
- **Contexte tenant** : non — `user_id`/`host_id` inchangés ; le flux reste celui du tour.
- **Plans / limites** : non — le décompte d'usage et le plafond de tour sont inchangés (mêmes
  compteurs, mêmes valeurs). Composants de limite touchés : **aucun**.
- **Navigation / routing** : non.

---

## Plan de test

### Tests unitaires (`AnthropicAgentProviderTest`)

- [ ] Streamé : un flux SSE (message_start + blocs text/tool_use/thinking signé + message_delta +
      message_stop) produit un `AgentTurn` **égal** à celui du non streamé équivalent (texte, tool_use
      + input, reasoning signé, usage, cache).
- [ ] Streamé : les deltas de texte sont poussés dans le sink dans l'ordre, et le `thinking_delta`
      n'y figure pas.
- [ ] Streamé : `usage` reconstitué depuis `message_start` (input + cache) et `message_delta`
      (output final) — mêmes valeurs que le non streamé.
- [ ] Streamé : `stop_reason: max_tokens` → `truncated=true` ; `tool_use` → `finished=false`.
- [ ] Streamé : corps envoyé = corps non streamé + `stream:true`, `cache_control` aux mêmes places.
- [ ] Streamé : `429` puis flux OK → un retry, même corps ; attentes identiques au non streamé.
- [ ] Repli : `400` en streamé → un appel non streamé de repli qui réussit.
- [ ] Repli : flux coupé / SSE illisible en streamé → repli non streamé.
- [ ] Drapeau off : `nextTurn(request, sink)` n'émet aucun delta et passe par le non streamé.

### Tests d'intégration (contexte Spring)

- [ ] `AtelierProperties` lie `app.atelier.streaming` (défaut `true`) — chargement du contexte.
- [ ] `AtelierChatServiceTest` existant reste vert (équivalence de comportement).

### Isolation

- [x] Non applicable — pas d'accès données nouveau ; le tour conserve son `user_id`/`host_id`.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (socle F-28/F-39/F-84 déjà livré).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **D1 — Réutiliser `onText` plutôt qu'un nouvel événement SSE.** Les quatre écrans (projet, poste,
  Teams, mosaïque) consomment déjà `onText` en `current.text + text`. Émettre les deltas via `onText`
  fait donc défiler le texte mot à mot sur **tous** les écrans sans plomberie nouvelle. `onText` passe
  de « le commentaire complet » à « un fragment de texte à ajouter à la ligne vivante » — sémantique
  identique côté écran.
- **D2 — Reconstituer le JSON puis réutiliser `toTurn` inchangé.** Le comptage cache/usage et le
  raisonnement signé vivent dans `toTurn` ; le streaming ne les réécrit pas, il rebâtit l'objet
  `Message` équivalent et le lui passe. C'est la garantie mécanique de l'équivalence stricte.
- **D3 — Le drapeau vit dans `AtelierProperties` (domaine), pas dans le fournisseur.** Le paquet
  `agent` reste neutre (Provider Independence) ; c'est `AtelierChatService` qui décide d'appeler la
  variante streamée. Le repli refuse/coupe, lui, est **runtime** et vit dans le fournisseur.
- **D4 — Anti-duplication.** Le sink note s'il a reçu au moins un delta pour l'itération ; si oui, le
  `listener.onText(turn.text())` du commentaire intermédiaire est **sauté** (déjà affiché en flux).
  Réinitialisé à chaque itération. En repli/non streamé, aucun delta → `onText` complet émis comme
  avant.
- **D5 — Repli et coût.** Le repli après flux coupé réémet l'appel (chemin d'erreur exceptionnel) :
  il échange un tour mort contre un second appel, comme un non streamé qui aurait expiré. L'invariant
  « coût inchangé » vaut pour l'équivalence streamé/non streamé **normale**, pas pour la reprise
  d'erreur.
