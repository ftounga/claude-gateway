# SF-117-11 — Un tour qui a cherché sur le web ne bloque plus le fil

**Feature parente :** F-117 — Le contexte d'un terminal ne déborde jamais
**Type :** correctif de bug prod (sujet « waf », terminal edenred, 2026-10-26)
**Estimation :** < 1 jour

## Objectif
Le fil d'un terminal reste envoyable au modèle après un tour où l'agent a utilisé une recherche ou une lecture web (outils serveur du fournisseur).

## Diagnostic (prouvé)
- Depuis 10:06, chaque envoi du sujet `2185878b…` échoue : `400 invalid_request` puis « Je n'ai pas pu envoyer cette conversation au modèle… ».
- Corps exact, obtenu en rejouant le fil de la base via `count_tokens` :
  `messages.N.content.15: thinking or redacted_thinking blocks in the latest assistant message cannot be modified`.
- Cause : l'étape 3 du dernier tour réussi avait **8 blocs de raisonnement** entrelacés avec des blocs d'**outils serveur** (`web_search`/`web_fetch`). La trace (`AtelierToolTrace`) garde les raisonnements mais pas les blocs serveur (choix délibéré F-188). Au rejeu, le message assistant n'est plus celui d'origine ; le fournisseur refuse.
- Le filet SF-117-08 ne répare que la **structure** (rôles, blocs vides) : il relance la même requête, qui échoue de nouveau. Le fil est bloqué pour toujours.
- Vérifié : le même fil passe si l'on retire les raisonnements de cette étape (145 064 tokens), ou tous les raisonnements (99 616 tokens).

## Comportement nominal
1. À l'enregistrement d'une étape, la trace note si la réponse contenait des blocs d'outils serveur (`serverTools`).
2. Au rejeu, une étape marquée `serverTools` est rejouée **sans ses raisonnements** (texte, appels et résultats inchangés).
3. Traces déjà écrites (sans le champ) : une étape portant **plus d'un** raisonnement est traitée pareil. Une réponse ne contient plusieurs blocs de raisonnement que lorsqu'ils sont entrelacés avec des outils serveur.
4. Filet : si un 400 structurel survient malgré tout, la relance unique (SF-117-08) part **sans aucun raisonnement rejoué**. Un fil ne peut plus rester bloqué par un raisonnement signé.
5. Le message d'erreur du fournisseur (champ `error.message`, borné à 300 caractères) est journalisé sur un 400 structurel. Aujourd'hui, le diagnostic exige de reconstruire le fil à la main.

## Cas d'erreur
- Trace illisible → inchangé (rejeu texte seul).
- 400 structurel persistant même sans raisonnement → `MALFORMED_SEQUENCE_REPLY`, comme avant.

## Critères d'acceptation
- CA1 : une étape `serverTools=true` est rejouée sans `Reasoning`/`RedactedReasoning` ; ses `tool_use`/`tool_result` sont intacts.
- CA2 : une étape ancienne à 2+ raisonnements est rejouée sans raisonnement ; une étape à 1 raisonnement sans `serverTools` le garde (cache F-134 préservé).
- CA3 : la trace sérialisée porte `serverTools` ; une trace sans ce champ se relit (`false`).
- CA4 : après un `AgentMalformedRequestException`, la relance envoie un historique sans aucun bloc de raisonnement.
- CA5 : le sujet `waf` en prod renvoie de nouveau une réponse après déploiement.

## Plan de test
- Unitaires `AtelierToolTraceTest` : CA1, CA2, CA3.
- `AtelierChatServiceMalformedSequenceTest` : CA4 (la 2ᵉ requête ne contient aucun raisonnement).
- `AnthropicAgentProviderTest` : non-régression du repérage malformé et du « prompt too long ».
- Isolation utilisateur : inchangée (aucune requête nouvelle ; le rejeu reste filtré par `workspace_id` + `user_id`).
- Vérification prod : envoyer un message dans le sujet `waf` après déploiement (CA5).

## Impact
- `AtelierToolTrace` (champ `serverTools` sur `Step`, rejeu), `AtelierChatService` (enregistrement, filet de relance), `AnthropicAgentProvider` (log du message d'erreur).
- Aucune table, aucune migration, aucun endpoint, aucun frontend.
- Préoccupations transversales : aucune (ni auth, ni tenant, ni plans, ni navigation).
- Cache de prompt : une seule réécriture du préfixe pour les fils dont une étape tracée a utilisé le web. Les autres fils ne changent pas d'un octet.

## Hors périmètre
- Persister les blocs d'outils serveur dans la trace pour les rejouer à l'identique. Cela gonflerait la trace : les résultats web sont volumineux et `MAX_TRACE_CHARS` = 60 000.
