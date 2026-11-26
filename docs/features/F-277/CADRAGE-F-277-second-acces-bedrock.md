# Cadrage F-277 — Second accès à Claude via AWS Bedrock, avec bascule automatique

**Date** : 2026-11-26. **Décision PO** : « profil mondial et bascule automatique, lance et livre en autonome ».
**ADR** : ADR-022.

## 1. Le problème

Depuis le 25/11 à 18:25 UTC, le crédit de l'API Anthropic de la plateforme est épuisé. Le PO ne peut pas le
recharger, car ses cartes sont refusées par la console. Toute l'IA de l'application renvoie 400 « Your credit balance is
too low » : Atelier, Jarvis, chat, Ask, Radar et Plume.

## 2. Ce qui est vérifié (26/11, vrais appels, compte 504895205419, eu-west-3)

- Les profils `global.anthropic.*` sont ACTIVE pour chaque modèle de l'application : `claude-opus-5-5`,
  `claude-opus-5`, `claude-opus-4-8`, `claude-sonnet-5-5`, `claude-sonnet-5`, `claude-haiku-5-5`, `claude-fable-5*`.
  Il y a une exception de nommage : `claude-haiku-4-5` correspond à `global.anthropic.claude-haiku-4-5-20251001-v1:0`.
- L'appel `InvokeModel` accepte le corps Anthropic, avec ces adaptations :
  - `anthropic_version: bedrock-2023-05-31` ;
  - aucun champ `model` ;
  - les bêtas sont passées dans le corps (`anthropic_beta`).
- Ces fonctions marchent sur Bedrock :
  - les outils, y compris `strict` ;
  - `thinking` adaptatif, y compris `display: updates` et sa bêta ;
  - `output_config.effort`, avec la bêta par message ;
  - `cache_control` en TTL 1 h (même usage) ;
  - `context_management` ;
  - les PDF et les images en base64 ;
  - le rejeu des blocs de raisonnement signés.
- La réponse a la même forme : `model` vaut l'identifiant court (`claude-sonnet-5-5`), donc la grille de coût
  s'applique telle quelle.
- Ces éléments sont refusés : `diagnostics`, `thinking.block_binding`, l'outil serveur `web_search_*` et la bêta
  `files-api-2025-04-14`. D'après la documentation, sont aussi absents : `web_fetch`, `fallbacks`, les sources
  `file`/`url`, Message Batches et Managed Agents.
- L'endpoint « bedrock-mantle » (`/anthropic/v1/messages`, SSE natif) a été évalué. Il n'existe pas en eu-west-3 et,
  en eu-west-1, il ne connaît aucun des modèles (404 `not_found_error`). Il est **écarté**. Le flux passe par
  `InvokeModelWithResponseStream`, dont les trames `vnd.amazon.eventstream` transportent les événements SSE
  d'Anthropic encodés en base64.

## 3. La solution

1. **SF-277-01, le canal Bedrock.** Un adaptateur de requête HTTP se branche sur une *seconde instance* des
   fournisseurs Anthropic existants (`AnthropicAgentProvider`, `AnthropicProvider`). Il agit ainsi :
   - il récrit `/v1/messages` en `/model/{profil}/invoke[-with-response-stream]` ;
   - il nettoie le corps ;
   - il signe la requête en SigV4 avec les identifiants IRSA du pod ;
   - il transcrit le flux event-stream en SSE et les erreurs AWS en erreurs de forme Anthropic.

   La sérialisation, la lecture, le rejeu des refus temporaires, la surveillance d'emballement et le repli non
   streamé sont **réutilisés tels quels**.
2. **SF-277-02, l'aiguillage.** Deux fournisseurs `@Primary` (`AIProvider`, `AiAgentProvider`) choisissent le canal
   et basculent automatiquement :
   - le disjoncteur s'ouvre pour 15 min ;
   - un client BYOK n'est jamais basculé ;
   - la colonne `provider` de `usage_turns` et de `atelier_turn_measures` indique le canal utilisé.

## 4. Décisions par défaut (tracées dans les PR)

| # | Décision |
|---|---|
| D1 | Le profil est `global.anthropic.<id>` (décision PO). Une table de correspondance gère les exceptions de nommage. Un modèle sans profil reçoit le refus Bedrock (404/400) : rien n'est remplacé en silence. |
| D2 | Le transport est un adaptateur HTTP sur le client existant, avec SigV4 du SDK AWS v2 (`http-auth-aws`, déjà présent par Textract). Il n'utilise pas le client `bedrockruntime` : son flux exige un client asynchrone (Netty) et un second chemin de lecture. |
| D3 | Sur Bedrock, les outils serveur web ne sont pas proposés, et les blocs web rejoués de l'historique sont retirés. Une note INFO est journalisée. La recherche web n'est pas réimplémentée. |
| D4 | Une source `file` (Files API) devient un texte « pièce jointe indisponible sur le canal Bedrock ». L'appel n'est pas refusé. `uploadFile` reste sur Anthropic. |
| D5 | La bascule se déclenche sur : crédit ou facturation (400 « credit balance » / `billing`), 401, 403, clé plateforme absente, et 5xx/529 restés en échec après les relances existantes. Elle ne se déclenche jamais sur un autre 400 ni sur un 429. |
| D6 | Le disjoncteur ne s'ouvre que pour le crédit, la clé ou l'absence de clé (15 min, `APP_AI_BEDROCK_OPEN_MINUTES`). Une surcharge ne fait basculer que l'appel en cours. |
| D7 | Un client BYOK n'est jamais basculé, même en mode `bedrock` forcé. Son erreur reste la sienne. |
| D8 | Si du texte est déjà parti à l'écran, la bascule n'a pas lieu : un rejeu dupliquerait la réponse. |
| D9 | Le canal qui a servi le **dernier** appel du fil d'exécution est relu au moment d'écrire `usage_turns` et `atelier_turn_measures`. À défaut, la route courante est utilisée. Les lignes existantes sont `ANTHROPIC`. |
| D10 | Les droits IAM sont posés en politique *inline* `claude-gateway-backend-staging-bedrock-policy` par la CLI, et non par un merge dans `legalCase/k8s/**` (risque de redéploiement, incident du 10/10). Ils sont documentés dans `docs/ops/bedrock-second-canal.md`. |

## 5. Hors périmètre

- Le rapprochement de la facture AWS.
- La recherche web sur Bedrock.
- Les Managed Agents.
- Message Batches.
- Le routage vers un autre LLM.
