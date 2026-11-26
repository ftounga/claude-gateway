# Mini-spec — F-277 / SF-277-01 Le canal Bedrock pour les deux interfaces IA

## Identifiant
`F-277 / SF-277-01`

## Feature parente
`F-277` — Second accès à Claude via AWS Bedrock, avec bascule automatique

## Statut
`ready`

## Date de création
2026-11-26

## Branche Git
`feat/SF-277-01-canal-bedrock`

---

## Objectif
Pouvoir servir n'importe quel appel `AIProvider` ou `AiAgentProvider` par AWS Bedrock (profil `global.anthropic.*`), avec la même sérialisation et la même lecture que l'API Anthropic.

## Comportement attendu

### Cas nominal
1. `BedrockChannel` construit une **seconde instance** de `AnthropicAgentProvider` et de `AnthropicProvider`. Ce ne sont pas des beans. Leur client HTTP porte l'intercepteur `BedrockRequestAdapter`, qui agit ainsi :
   - Il remplace l'URL `/v1/messages` par `https://bedrock-runtime.{région}.amazonaws.com/model/{profil}/invoke`. Si le corps porte `stream: true`, la fin de l'URL devient `/invoke-with-response-stream`.
   - Il calcule le profil ainsi : la correspondance configurée en priorité, sinon `global.anthropic.` + l'identifiant.
   - Il nettoie le corps :
     - il retire `model`, `stream`, `diagnostics`, `fallbacks`, `thinking.block_binding` et les outils serveur (`web_search_*`, `web_fetch_*`, `code_execution_*`) ;
     - il retire aussi les blocs serveur rejoués de l'historique. Un contenu resté vide reçoit un texte neutre ;
     - il remplace une source `file` ou `url` par un texte « pièce jointe indisponible » ;
     - il ajoute `anthropic_version: bedrock-2023-05-31`.
   - Il transporte les bêtas de l'en-tête `anthropic-beta` dans `anthropic_beta`. Seules les bêtas de la liste autorisée passent : `context-management-2025-06-27`, `mid-conversation-output-config-2026-07-01` et `thinking-display-updates-2026-08-18`.
   - Il retire `x-api-key`, `anthropic-version` et `anthropic-beta`, puis signe la requête en SigV4 (service `bedrock`). Les identifiants viennent de la chaîne par défaut : IRSA dans le pod, profil en local.
   - En flux, il transcrit les trames `vnd.amazon.eventstream` en lignes SSE `data: {...}`. Une trame d'exception devient un événement `error`, qui déclenche le repli non streamé existant.
   - Il transcrit une erreur AWS en corps Anthropic `{"type":"error","error":{"type":…,"message":…}}`. Une entrée trop longue reçoit un message qui commence par `prompt is too long`, pour que la compaction existante se déclenche.
2. Les outils serveur retirés sont journalisés en INFO (« recherche web non proposée sur le canal Bedrock »).
3. Configuration `app.ai.bedrock.*` : `region` (`eu-west-3`), `model-prefix` (`global.anthropic.`), `model-overrides`, `allowed-betas`, `base-url` (facultatif). Le mode `off`/`auto`/`bedrock` est lu ici, mais l'aiguillage arrive en SF-277-02. Sans aiguillage, rien ne change.
4. Droits IAM : `bedrock:InvokeModel` et `bedrock:InvokeModelWithResponseStream` sur les profils `global.`/`eu.anthropic.*` et les modèles de fondation `anthropic.*` de toutes les régions. Ils sont appliqués par la CLI et documentés dans `docs/ops/bedrock-second-canal.md`.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Identifiants AWS introuvables | `AIProviderUnavailableException` (« canal Bedrock non configuré »). Aucun appel n'est émis. |
| 400 ValidationException (champ refusé) | Corps traduit en `invalid_request_error`. Le chemin existant s'applique : échec du fournisseur ou réassainissement. |
| Entrée trop longue | Message « prompt is too long… » ; la boucle compacte puis relance (F-117). |
| 429 ThrottlingException | Statut 429 conservé, donc relancé par `AgentRetryPolicy`. |
| 503 ServiceUnavailable | Statut traduit en 529 `overloaded_error`, donc relancé. |
| Trame event-stream corrompue (CRC) ou exception en flux | Repli non streamé (comportement F-116). |

## Critères d'acceptation
- [ ] Le corps envoyé à Bedrock ne contient ni `model`, ni `stream`, ni `diagnostics`, ni `fallbacks`, ni `block_binding`, ni outil serveur. Il porte `anthropic_version` et les seules bêtas autorisées (test).
- [ ] La correspondance des modèles marche : `claude-opus-5-5` donne `global.anthropic.claude-opus-5-5`, et `claude-haiku-4-5` donne `global.anthropic.claude-haiku-4-5-20251001-v1:0` (test).
- [ ] La requête porte `Authorization: AWS4-HMAC-SHA256`, `X-Amz-Date`, et aucun `x-api-key` (test).
- [ ] Une suite de trames event-stream est relue par `AnthropicAgentProvider` en un `AgentTurn` identique à celui du SSE équivalent : texte, `tool_use`, usage (test).
- [ ] Une erreur AWS est traduite en corps de forme Anthropic, avec le bon statut (test).
- [ ] L'instance Anthropic ne change pas : son corps est identique à l'octet près (les tests existants restent verts).
- [ ] Un appel réel `claude-haiku-5-5` (non streamé et streamé) aboutit par le canal, avec un test manuel gardé par la variable `BEDROCK_LIVE_TEST`.

## Périmètre

### Hors scope (explicite)
- L'aiguillage, la bascule, le disjoncteur, BYOK et la colonne `provider` : SF-277-02.
- La recherche web sur Bedrock, les Managed Agents, Message Batches et le rapprochement de la facture AWS.

## Valeurs initiales
- `region` = `eu-west-3`, `model-prefix` = `global.anthropic.`.

## Contraintes de validation
- Une région vide prend le défaut. Une liste de bêtas vide signifie qu'aucune bêta n'est transmise.

## Technique

### Endpoint(s)
Aucun.

### Tables impactées
Aucune.

### Migration Liquibase
- [x] Non applicable

### Composants Angular (si applicable)
Aucun.

## Plan de test

### Tests unitaires
- [ ] `BedrockRequestAdapterTest` : nettoyage du corps, bêtas, URL `invoke` et `invoke-with-response-stream`, correspondance des modèles, signature présente, traduction des erreurs.
- [ ] `BedrockEventStreamTest` : décodage des trames (en-têtes, base64), trame d'exception, CRC faux.
- [ ] `BedrockChannelAgentTest` : `AnthropicAgentProvider` en canal Bedrock sur un serveur simulé (`MockRestServiceServer`), en mode non streamé et streamé.

### Tests d'intégration
- [ ] Démarrage du contexte complet : la suite `./mvnw verify` reste verte.
- [ ] `BedrockLiveTest` (manuel, `BEDROCK_LIVE_TEST=1`) : vrais appels à quelques centimes.

### Isolation workspace
- [x] Non applicable : le transport ne lit ni n'écrit aucune donnée utilisateur. La clé BYOK n'est jamais transmise à Bedrock (règle portée en SF-277-02).

## Préoccupations transversales
- Auth / Principal : non concerné. Contexte tenant : non concerné. Plans / limites : non concerné. Navigation : non concerné.

## Dépendances
Aucune. SF-277-02 dépend de cette subfeature.
