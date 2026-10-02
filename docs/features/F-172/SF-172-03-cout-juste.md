# Mini-spec — [F-172 / SF-172-03] Coût juste

## Identifiant

`F-172 / SF-172-03`

## Feature parente

`F-172` — Opus 5.5 dans la Forge

## Statut

`in-progress`

## Date de création

2026-10-02

## Branche Git

`feat/SF-172-03-cout-juste`

---

## Objectif

Mesurer juste le coût d'un tour de la Forge quand le modèle change ou qu'un repli intervient : tarif `claude-opus-5-5` dans la grille, coût calculé par tentative (`usage.iterations`) au tarif du modèle qui l'a servie, et modèle **servi** enregistré dans `usage_turns.model`.

---

## Comportement attendu

### Cas nominal

1. La grille (`ProviderPricingProperties.defaultModels()` **et** `application.yml`) contient `claude-opus-5-5` : 4,00 / 20,00 / 0,20 / 8,00 $ par million (entrée, sortie, lecture de cache, écriture de cache 1 h).
2. `AnthropicAgentProvider` lit `usage.iterations` quand il est présent : chaque entrée est une tentative (refusée ou servie) avec ses tokens et son modèle. Les **totaux** du tour sont alors la **somme des tentatives** (le `usage` de premier niveau ne couvre que la tentative qui a produit le message). La répartition par modèle remonte dans `AgentTurn.usageByModel()`.
3. Sans `iterations`, rien ne change : une seule part, au modèle servi (ou demandé).
4. La boucle de la Forge cumule, pour le tour entier, les parts servies par un modèle **autre** que le modèle demandé. À la clôture, le coût du relevé (`usage_turns.provider_cost_usd`, rapport de tour) = coût de la part du modèle demandé à son tarif + coût de chaque autre part à son tarif ; `usage_turns.model` = dernier modèle servi.
5. Le **décompte de quota** (tokens facturés au client, F-63) est inchangé : mêmes volumes totaux, mêmes tarifs de conversion.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Entrée d'`iterations` sans `model` | Type `fallback_message` ⇒ modèle de la réponse ; sinon modèle demandé |
| Modèle d'une part absent de la grille | Tarif de repli + `pricing_fallback=true` (comportement F-133 existant) |
| `iterations` absent ou vide | Calcul identique à aujourd'hui |

---

## Critères d'acceptation

- [ ] `pricingOf("claude-opus-5-5")` rend 4 / 20 / 0,20 / 8 (code et `application.yml`).
- [ ] Une réponse avec deux tentatives (`message` refusée sur `claude-opus-5-5`, `fallback_message` sur `claude-opus-4-8`) rend des totaux = somme, et deux parts par modèle.
- [ ] Un tour de la Forge entièrement servi par le modèle demandé : coût et modèle enregistrés strictement identiques à avant.
- [ ] Un tour avec une part servie par un autre modèle : coût = somme des parts à leur tarif ; modèle enregistré = modèle servi.
- [ ] Le décompte de quota (`recordUsage` → tokens facturés) ne change pas.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Tarifs `claude-opus-5-5` | relevés le 2026-10-02 (grille officielle) : 4 / 20 / 0,20 / 8 (écriture 1 h = 2× l'entrée, TTL posé par la boucle depuis F-130) |
| `pricing-version` | passe à `2026-10-02` |

---

## Périmètre

### Hors scope (explicite)

- Les autres chemins de coût (chat passerelle, Radar, sous-boucles Sonnet 5 de l'exploration — déjà comptées au tarif du modèle demandé, défaut antérieur non traité ici).
- Le décompte commercial (quota) : inchangé par principe (F-133 mesure, ne facture pas).
- Aucune table nouvelle, aucune colonne (`usage_turns.model` existe déjà), aucun écran.

---

## Technique

### Composants backend impactés (préoccupation « Plans / limites »)

- `quota/ProviderPricingProperties` (grille), `application.yml` (grille, version).
- `quota/ProviderCostCalculator` : coût d'un tour multi-modèles.
- `quota/QuotaService` : surcharge `recordUsage(..., parts hors modèle demandé, modèle servi, ...)` et `costOf(...)` multi-modèles — le compteur de quota et `billedTokens` sont calculés exactement comme avant.
- `agent/AgentTurn` (`usageByModel`), `agent/AnthropicAgentProvider` (lecture `usage.iterations`, streamé et non streamé).
- `atelier/AtelierChatService` : cumul des parts hors modèle demandé, coût et modèle du relevé.
- Lecture de coût F-133 (`TurnCostView`, alerte SF-133-12) : non modifiée, elle lit `provider_cost_usd`.
- Budget de tour `AtelierTurnBudget` : inchangé (il compte des tokens, dont la somme des tentatives).

### Tables

`usage_turns` (colonnes existantes `model`, `provider_cost_usd`) — aucune migration.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Plans / limites | Oui | listés ci-dessus ; le quota client n'est pas modifié |
| Auth, tenant, routing | Non | — |

---

## Plan de test

- [ ] `ProviderPricingPropertiesTest` / `ProviderCostCalculatorTest` : tarif `claude-opus-5-5` ; coût multi-modèles.
- [ ] `AnthropicAgentProviderTest` : `usage.iterations` (non streamé et streamé) ⇒ totaux et parts.
- [ ] `AtelierChatServiceTest` : modèle servi et coût multi-modèles transmis au relevé ; tour mono-modèle inchangé.
- [ ] Isolation : `recordUsage` reçoit toujours l'`userId` du contexte de sécurité (inchangé).

## Dépendances

SF-172-02 (modèle servi) mergée.

## Notes et décisions

- **Arbitrage (réversible)** : `usage_turns.model` = **dernier** modèle servi du tour (le tour est l'unité du relevé, un repli est collant ~1 h côté fournisseur, donc le dernier servi est le plus représentatif). Alternative écartée : une ligne par modèle (changerait le grain du relevé F-61).
