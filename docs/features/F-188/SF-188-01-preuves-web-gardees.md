# Mini-spec — [F-188 / SF-188-01] Les preuves web gardées

## Identifiant

`F-188 / SF-188-01`

## Feature parente

`F-188` — Les gros tours

## Statut

`in-progress`

## Date de création

2026-10-10

## Branche Git

`feat/SF-188-01-preuves-web-gardees`

---

## Objectif

Que l'agent garde, d'une étape à l'autre de son tour, les recherches et lectures web qu'il vient de faire, et qu'une pause du fournisseur ne soit plus prise pour une fin de tour.

---

## Comportement attendu

### Cas nominal

1. **Lecture de la réponse** (`AnthropicAgentProvider`) : les blocs `server_tool_use`, `web_search_tool_result`, `web_fetch_tool_result` (et tout autre bloc `*_tool_result` d'outil serveur) sont conservés **tels quels**, en JSON brut, dans l'ordre d'émission, au sein de la liste des blocs à rejouer, avec les blocs de raisonnement. Le nouveau type `AgentContentBlock.ServerTool(JsonNode raw)` les porte.
2. **Rejeu** : à l'étape suivante, le message assistant rejoue ces blocs **à l'identique** (même JSON, contenu chiffré compris), à la place exacte où le raisonnement est déjà rejoué. Le modèle voit donc ses preuves.
3. **Flux** : en mode streaming, le bloc `server_tool_use` reçoit son `input` reconstitué depuis les `input_json_delta`, comme un `tool_use`. Les blocs de résultat arrivent entiers dans `content_block_start`.
4. **`pause_turn`** : le fournisseur a suspendu un tour long (outils serveur).
   - `AgentTurn.paused()` est vrai, et le tour n'est pas « fini » ;
   - la boucle rejoue le message assistant (raisonnement, blocs serveur, texte) **sans** message utilisateur, puis rappelle le modèle, qui reprend là où il s'était arrêté ;
   - chaque reprise compte comme une étape (le plafond d'étapes reste la borne).
5. **Entre deux messages** : rien ne change. Les blocs serveur ne sont jamais persistés, comme le raisonnement. Seul le texte de la réponse l'est (D4).
6. **Autres fournisseurs** : le type est propre à Anthropic. Un fournisseur qui ne le connaît pas l'ignore au rejeu. C'est le comportement par défaut de l'interface `AiAgentProvider`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Bloc serveur inconnu (nouveau type) | Conservé tel quel s'il finit en `_tool_use` ou `_tool_result` ; sinon ignoré (comportement actuel) | — |
| Bloc serveur avant un repli (`fallback`) | Jeté, comme `thinking`/`tool_use` avant le dernier repli (SF-172-02) | — |
| `pause_turn` répété jusqu'au plafond d'étapes | Le tour s'arrête au plafond, comme aujourd'hui | — |
| Refus (`stop_reason=refusal`) | Inchangé (SF-172-01) | — |
| Fil bloqué par un contrôle de fin de tour | Le rejeu du tour bloqué garde aussi les blocs serveur | — |

---

## Critères d'acceptation

- [ ] Une réponse contenant recherche et résultat web rend un `AgentTurn` dont la liste à rejouer contient ces blocs, à l'identique et dans l'ordre.
- [ ] La requête suivante du même tour renvoie ces blocs dans le message assistant, avec un JSON identique.
- [ ] En streaming, le `server_tool_use` reconstitué porte son `input`.
- [ ] `pause_turn` : la boucle rappelle le modèle avec le message assistant en dernier, sans message utilisateur, et le tour ne se termine pas sur la pause.
- [ ] Les blocs serveur ne sont ni persistés dans le fil ni inscrits dans la trace d'outils.
- [ ] Les tours sans outil serveur sont inchangés : les tests existants du fournisseur et de la boucle passent.

---

## Plan de test

- **Unitaires** (`AnthropicAgentProviderTest` ou équivalent) :
  - lecture d'une réponse avec `server_tool_use` et `web_search_tool_result` : blocs conservés dans l'ordre ;
  - sérialisation du message assistant : JSON identique ;
  - flux SSE avec `server_tool_use` et `input_json_delta` : `input` reconstitué ;
  - `stop_reason=pause_turn` : `paused`, non fini ;
  - bloc serveur placé avant un `fallback` : jeté.
- **Boucle** (`AtelierChatService…Test`, fournisseur bouchonné) :
  - une étape avec bloc serveur, puis un appel d'outil → la 2e requête contient le bloc serveur ;
  - une étape en `pause_turn` → la 2e requête finit par le message assistant, et le tour continue jusqu'à la réponse ;
  - rien de serveur dans la persistance.
- **Isolation utilisateur** : sans objet (aucun accès aux données ajouté).
- **Suite complète backend.**

---

## Composants impactés

- `agent/AgentContentBlock` : `ServerTool(JsonNode raw)`.
- `agent/AgentTurn` : `paused`.
- `agent/AnthropicAgentProvider` : lecture, flux, sérialisation.
- `atelier/AtelierChatService` : reprise sur `pause_turn` ; les blocs serveur passent par la liste déjà rejouée.
- Tout `switch` exhaustif sur `AgentContentBlock` (le compilateur les signale).
- Aucune table, aucun endpoint, aucun écran.

## Préoccupations transversales

Aucune (ni auth, ni tenant, ni limites, ni navigation).

---

## Périmètre

### Hors scope (explicite)

- Persister les résultats web entre deux messages.
- Afficher les citations à l'écran.
- Poste occupé : SF-188-02. Mesure par tour : SF-188-03.
