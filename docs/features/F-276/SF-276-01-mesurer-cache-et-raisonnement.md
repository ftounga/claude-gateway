# Mini-spec — F-276 / SF-276-01 Mesurer le cache et le raisonnement avec les outils du fournisseur

## Identifiant
`F-276 / SF-276-01`

## Feature parente
`F-276` — Le raisonnement survit d'un tour à l'autre

## Statut
`ready`

## Date de création
2026-11-24

## Branche Git
`feat/SF-276-01-mesure-cache-raisonnement`

---

## Objectif
Ranger, pour chaque tour, ce que le fournisseur dit lui-même : où le cache a cassé (diagnostic officiel) et combien de blocs de raisonnement ont été envoyés et écartés.

## Comportement attendu

### Cas nominal
1. Chaque appel du fournisseur Anthropic porte `diagnostics: {"previous_message_id": <id | null>}`. Cette option est relayée et non réimplémentée ; l'appel de test du 2026-11-24 l'a vérifiée sur `claude-opus-5-5`, avec la version `2023-06-01` et la bêta de liaison.
   - Dans un tour, `previous_message_id` prend l'`id` de la réponse précédente du même tour.
   - Au 1er appel d'un tour, il prend l'`id` de la dernière réponse du tour précédent **du même fil** : même utilisateur, même terminal, même chantier. On le lit dans `atelier_turn_measures.last_response_id`.
   - Sans tour précédent, il vaut `null`.
2. La réponse est lue en flux (`message_start.message`) et hors flux, sur le corps lui-même :
   - `id` ;
   - `diagnostics.cache_miss_reason.type` et `cache_missed_input_tokens` ;
   - le nombre d'entrées `thinking_dropped` de `input_transformations`.
   Le nombre de blocs de raisonnement envoyés (`thinking`, `redacted_thinking`) est compté sur la requête.
3. Ces faits voyagent en type neutre `AgentCallDiagnostics` sur `AgentTurn`, et `previousResponseId` sur `AgentTurnRequest`.
4. La mesure du tour range :
   - `first_cache_miss_reason` (type, ou `none` si `diagnostics` est `null`, ou `pending` si `cache_miss_reason` est `null`) ;
   - `first_cache_missed_tokens` ;
   - `thinking_replayed` et `thinking_dropped`, au 1er appel ;
   - `thinking_dropped_total`, sur tous les appels ;
   - `last_response_id`.
5. Coupe-circuit : `app.agent.features.cache-diagnostics` (`APP_AGENT_CACHE_DIAGNOSTICS`, défaut `true`). À `false`, aucun objet `diagnostics` n'est envoyé, et la requête redevient celle d'avant.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `previous_message_id` inconnu ou expiré | Le fournisseur rend `previous_message_not_found`, qui est rangé tel quel. Le tour n'est jamais bloqué : le diagnostic ne fait jamais échouer une requête. |
| Réponse sans `diagnostics`, ou objet illisible | Colonnes `null`, tour inchangé. |
| Panne d'écriture de la mesure | Journal `warn`, tour inchangé (règle existante de `TurnMeasureWriter`). |
| Appels hors Atelier (compaction, synthèse, Radar…) | Ils portent `previous_message_id: null` et ne sont pas rangés. |

## Critères d'acceptation
- [ ] Le corps envoyé contient `diagnostics.previous_message_id`, chaîné dans le tour et d'un tour à l'autre du même fil, et `null` au 1er tour (test du corps).
- [ ] À `cache-diagnostics=false`, le corps ne contient aucune clé `diagnostics` (test).
- [ ] Les réponses en flux et hors flux rendent `responseId`, `cacheMissReason`, `cacheMissedTokens` et `thinkingDropped` identiques pour une même réponse (test sur un SSE et un JSON enregistrés).
- [ ] `thinkingReplayed` compte les blocs `thinking` et `redacted_thinking` des messages envoyés (test).
- [ ] La ligne `atelier_turn_measures` porte les 6 colonnes. Le `last_response_id` d'un autre utilisateur ou d'un autre chantier n'est jamais lu (test d'intégration).
- [ ] Aucun contenu n'est rangé : uniquement des types, des nombres et l'identifiant de réponse.

## Plan de test minimal
- **Unitaires** : `AnthropicAgentProviderTest` vérifie le corps avec et sans coupe-circuit, l'`id` précédent posé, le décodage en flux et hors flux de `diagnostics`, `input_transformations` et `id`, et le comptage des blocs envoyés.
- **Intégration** : `TurnMeasureWriterIntegrationTest` vérifie l'écriture et la relecture de `last_response_id`, isolées par utilisateur, terminal et chantier.
- **Service** : `AtelierChatService` vérifie que le 2ᵉ appel d'un tour reçoit l'`id` de la 1re réponse, et que le 1er appel reçoit celui du tour précédent.

## Impacts
- **Table** `atelier_turn_measures` : 6 colonnes nullables (migration `276-01`), réversible par `dropColumn`.
- **Code** :
  - `AgentTurnRequest` (+ `previousResponseId`, constructeur à 9 arguments conservé) ;
  - `AgentTurn` (+ `diagnostics`, constructeur à 19 arguments conservé) ;
  - `AgentCallDiagnostics` (nouveau) ;
  - `AnthropicAgentProvider` ;
  - `AgentApiFeaturesProperties` ;
  - `TurnMeasure` et `TurnMeasureWriter` ;
  - `AtelierChatService`.
- **Endpoints** : aucun. **Écran** : aucun.
- **Préoccupations transversales** : aucune.

## Hors périmètre
- L'en-tête non publié `anthropic-thinking-prefix-mismatch` : le guide dit de ne jamais en dépendre.
- Un écran du bilan : il est lu en SQL à J+7.
- Toute correction du rejeu, portée par SF-276-02.
