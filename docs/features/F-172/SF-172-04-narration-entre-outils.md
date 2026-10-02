# Mini-spec — [F-172 / SF-172-04] Narration entre outils

## Identifiant

`F-172 / SF-172-04`

## Feature parente

`F-172` — Opus 5.5 dans la Forge

## Statut

`in-progress`

## Date de création

2026-10-02

## Branche Git

`feat/SF-172-04-narration-entre-outils`

---

## Objectif

Sur les modèles qui écrivent leur narration entre deux outils dans des blocs `thinking` de progression (Opus 5.5, Fable, Sonnet 5.5), demander `display: "updates"` et faire suivre ces notes au terminal **et** au texte lu par la ré-escalade d'effort F-119 et par le tri de narration — sans toucher aux blocs rejoués.

---

## Comportement attendu

### Cas nominal

1. Pour un modèle de `app.agent.api-features.progress-update-models` (défaut : `claude-opus-5-5`, `claude-fable-5-1`, `claude-fable-5`, `claude-sonnet-5-5`), la requête porte `thinking: {type: "adaptive", display: "updates"}` et l'en-tête bêta `thinking-display-updates-2026-08-18`.
2. Dans la réponse de ces modèles, tout bloc `thinking` au texte **non vide** est une note de progression : son texte rejoint `AgentTurn.narration()` (notes jointes par une ligne vide). La phrase sentinelle « This part of the response was interrupted before it finished. » n'est pas une narration.
3. En flux, les `thinking_delta` non vides de ces modèles sont poussés au terminal au fil de l'eau (séparés du bloc précédent par une ligne vide) ; pour les autres modèles, le raisonnement ne part **jamais** au terminal (inchangé).
4. Dans la boucle de la Forge, le texte « parlé » du tour (`narration` + `text`) remplace `text` pour : la détection d'auto-contradiction F-119 (`looksLikeSelfCorrection`), la rétention de narration (`hasEssential`, `backgroundText`), et le relais au terminal hors flux.
5. Les blocs `thinking` sont rejoués **inchangés** (texte et signature) : le message assistant rejoué ne gagne aucun bloc texte.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Modèle hors liste (Opus 5, Sonnet 5, Haiku) | Requête et lecture strictement inchangées, `narration` vide |
| Bloc de progression vide | Ignoré (aucune ligne au terminal) |
| Réponse sans aucune note | `narration` vide, comportement inchangé |

---

## Critères d'acceptation

- [ ] Modèle de la liste : `thinking.display == "updates"` + en-tête ; modèle hors liste : ni l'un ni l'autre.
- [ ] Réponse `[thinking "", thinking "Je lis le fichier.", tool_use]` d'un modèle de la liste : `narration == "Je lis le fichier."`, `text` vide, les deux blocs rejoués tels quels.
- [ ] Même réponse d'un modèle hors liste : `narration` vide.
- [ ] Flux : la note défile au terminal ; le raisonnement d'un modèle hors liste ne défile pas.
- [ ] Boucle : une note « en fait, je me suis trompé » déclenche l'escalade F-119 au tour suivant ; une narration seule devient le texte de fond retenu.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Liste de modèles | configuration (`APP_AGENT_PROGRESS_UPDATE_MODELS`), comparaison exacte |
| Sentinelle | chaîne exacte de la doc fournisseur, exclue de la narration |

---

## Périmètre

### Hors scope (explicite)

- Outil « envoyer un message à l'utilisateur » et consignes de cadence : non retenus (levier 2/3 de la doc), à mesurer d'abord.
- Écran : aucun changement Angular — la note arrive par le canal de texte existant du terminal (vérification du rendu par les tests du flux).

---

## Technique

### Composants backend impactés

- `agent/AgentApiFeaturesProperties` (`progress-update-models`), `agent/AnthropicAgentProvider` (corps, en-tête, lecture, flux), `agent/AgentTurn` (`narration`, `spokenText()`).
- `atelier/AtelierChatService` : `spokenText()` pour F-119, la rétention et le relais hors flux.

Tables / endpoints / Angular / Liquibase : aucun.

### Préoccupations transversales

Aucune cochée (ni auth, ni tenant, ni limites, ni routing).

---

## Plan de test

- [ ] `AnthropicAgentProviderTest` : corps/en-tête selon la liste ; lecture des notes (non streamé, streamé, sentinelle, hors liste).
- [ ] `AtelierChatServiceTest` : escalade F-119 sur une narration ; narration retenue comme texte de fond.
- [ ] Isolation : non applicable (aucune donnée).

## Dépendances

SF-172-03 mergée (même fichier provider, ordre du cadrage).

## Notes et décisions

- La narration est un champ **à part** plutôt que fusionnée dans `text` : `text` reste exactement ce que le modèle a écrit en blocs `text` et sert au rejeu ; fusionner aurait ajouté au message rejoué un bloc que le modèle n'a pas produit.
