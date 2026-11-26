# SF-277-03 — La consigne d'effort traverse Bedrock intacte

**Feature parente** : F-277 — Second accès à Claude via AWS Bedrock
**Type** : fix (régression de SF-277-01, constatée en prod le 2026-11-26)
**Estimation** : < 1 h

## Objectif
Un tour de l'Atelier servi par Bedrock aboutit même quand le fil contient des consignes d'effort.

## Constat (prod, 2026-11-26 13:04 UTC, staging-13073b74)
Le crédit Anthropic est épuisé. La bascule vers Bedrock a bien lieu, mais Bedrock refuse ensuite chaque tour de
l'Atelier : `messages.N: role 'system' must precede an 'assistant' message…`. Le chat simple passe.

Cause : l'Atelier glisse dans le fil des **consignes d'effort**, des messages `role: system` à `content: []` accompagnés
de `output_config`. Ce format « consigne seule » est accepté à n'importe quelle position. Or
`BedrockRequestAdapter.cleanContent` remplit **tout** message vide avec la note « résultats de recherche web omis ».
La consigne devient alors un message système ordinaire, que le fournisseur refuse à cette position.

## Comportement nominal
- Le canal ajoute la note uniquement dans un message qu'**il** a vidé en retirant des blocs web.
- Un message déjà vide, la consigne d'effort, est transmis tel quel.

## Cas d'erreur
- Un message composé uniquement de blocs web reçoit toujours la note, sans changement.

## Critères d'acceptation
- [x] Une consigne d'effort, en tête comme au milieu du fil, sort du nettoyage avec `content: []` et son
      `output_config`, sans note (test unitaire).
- [x] Un message dont il ne restait que des blocs web reçoit toujours la note (test existant).
- [x] Vrai appel Bedrock sur Opus 5.5 avec deux consignes d'effort dans le fil : il aboutit avec le correctif et
      échoue sans lui, avec l'erreur constatée en prod (`BedrockLiveTest`).
- [ ] En prod, un tour de l'Atelier aboutit via Bedrock (`usage_turns.provider=BEDROCK`, modèle Opus 5.5).

## Plan de test
- Unitaire : `BedrockRequestAdapterTest.keepsTheEffortDirectiveEmpty`.
- Réel (manuel) : `BedrockLiveTest.midConversationEffortDirectivesAreAccepted`.
- Non-régression : `FailoverNonRegressionTest` (requête Anthropic identique à l'octet près), puis la suite complète.
- Isolation utilisateur : non applicable. Le changement porte sur le transport ; aucune donnée n'est lue.

## Impact
- `BedrockRequestAdapter` uniquement, sur le chemin Bedrock. Le chemin Anthropic n'est pas touché.
- Tables, endpoints et écrans : aucun.
- Préoccupations transversales : aucune.

## Hors périmètre
- La normalisation du nom de modèle servi par Bedrock (`anthropic.claude-…`) en id Anthropic.
