# Mini-spec — F-121 / SF-121-16 — Thinking entrelacé : vérification de parité et clôture

## Identifiant

`F-121 / SF-121-16`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Écart **Lot 3 / P3** du cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` §4 :

> **F-121-16** — **Thinking entrelacé** (en-tête beta `interleaved-thinking` quand adaptatif) : laisse
> penser entre `tool_use` parallèles. Largement émulé aujourd'hui par la boucle → raffinement. (P3)

## Statut

`done` — **clos sans développement de capacité** : l'écart n'existe plus, et le correctif proposé
(poser l'en-tête beta) serait aujourd'hui **faux**. Un témoin verrouille la propriété.

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-16-thinking-entrelace`

---

## Objectif

Établir, preuve à l'appui, que le **thinking entrelacé** est déjà obtenu par la boucle maison et par
le mode de raisonnement demandé au fournisseur, et **verrouiller** le fait qu'aucun en-tête beta
`interleaved-thinking` ne doit être posé — puis clore l'écart.

---

## Contexte — ce que l'écart visait, et pourquoi il est caduc

L'en-tête beta `interleaved-thinking-2025-05-14` a été introduit pour la génération **Claude 4** :
sans lui, ces modèles ne pouvaient pas penser **entre** deux appels d'outils d'un même tour. Depuis
la génération 4.6, le **thinking adaptatif** (`thinking: {"type": "adaptive"}`) active l'entrelacement
**de lui-même**, et **aucun en-tête beta n'est requis**. Le harnais tourne sur `claude-opus-5`
(`app.atelier.model`, `application.yml:155`), avec le thinking adaptatif écrit explicitement
(`AnthropicAgentProvider.applyReasoning`, décision D-L5-2 de SF-39-10).

Poser l'en-tête aujourd'hui ne serait donc pas un raffinement mais une **régression de risque** :
au mieux un en-tête mort, au pire un beta inconnu rejeté par le fournisseur — pour une capacité
**déjà active**.

Le reste de l'entrelacement est structurel et déjà en place :

1. **La boucle pense à chaque étape.** `AtelierChatService` rappelle le fournisseur après **chaque**
   lot de `tool_result` ; le modèle produit alors un nouveau bloc de raisonnement. C'est exactement
   la forme « penser entre les appels d'outils ».
2. **Le raisonnement est réémis tel quel, signature comprise**, dans le bon ordre
   reasoning → text → tool_use → results (`AnthropicAgentProvider.toApiBlock`, `AtelierToolTrace`) —
   condition sans laquelle l'entrelacement casse (un bloc retouché est un bloc refusé).
3. **Rien ne le coupe** : `cache_control` n'est jamais posé sur un bloc de raisonnement, et l'édition
   de contexte n'active pas `clear_thinking`.

**Provider-First** : c'est le fournisseur qui pense, la gateway ne réimplémente rien.
**Provider Independence** : la propriété vérifiée porte sur `AiAgentProvider` / son implémentation
Anthropic, aucun code métier n'est concerné.

---

## Comportement attendu

### Nominal

1. Un tour à raisonnement adaptatif envoie `thinking: {"type": "adaptive"}` (déjà couvert par
   `asksForAdaptiveThinkingAndTheConfiguredEffort`).
2. L'appel **ne porte aucun en-tête beta** `interleaved-thinking` — quel que soit son millésime. Les
   seuls betas posés restent ceux qui sont **explicitement justifiés** : édition de contexte
   (SF-39-12) et effort par message (SF-134-05), chacun conditionné à un usage réel.
3. Entre deux lots d'outils d'un même tour, le modèle est rappelé et son raisonnement précédent est
   réémis intact (déjà couvert par `replaysTheReasoningOfTheTurnAheadOfItsTextAndToolCalls`).

### Cas d'erreur / limites

- **Modèle antérieur à 4.6 configuré** (`APP_ATELIER_MODEL` rétrogradé) : l'entrelacement natif ne
  serait plus garanti. Ce n'est pas un cas à couvrir par un en-tête posé en permanence — le harnais
  déclare un modèle cible, et ce choix d'exploitation se traite par le réglage, pas par un beta mort.
- **Beta inconnu** : un en-tête beta obsolète peut être rejeté (400). C'est précisément le risque que
  la clôture évite, et que le témoin empêche de réintroduire par inadvertance.

---

## Critères d'acceptation

1. Aucun en-tête `anthropic-beta` posé par `AnthropicAgentProvider` ne contient `interleaved`.
2. Sur un tour adaptatif **sans** édition de contexte ni effort par message, **aucun** en-tête
   `anthropic-beta` n'est envoyé.
3. Le corps de l'appel porte bien `thinking.type = adaptive` sur un tour adaptatif (vérifié par
   l'existant).
4. Un témoin dédié garde les points 1 et 2 : une future tentative de « poser l'en-tête
   interleaved-thinking » fait **échouer la suite**.
5. **Aucun code de production n'est modifié** par cette subfeature.

---

## Plan de test minimal

### Unitaires (`AnthropicAgentProviderTest`)

- `postsNoInterleavedThinkingBetaOnAnAdaptiveTurn` (**nouveau**) : sur un tour adaptatif ordinaire,
  la liste des en-têtes `anthropic-beta` est **vide**, et aucune valeur ne contient `interleaved` ;
  le corps porte `thinking.type = adaptive`.
- `asksForAdaptiveThinkingAndTheConfiguredEffort` (existant) : le mode adaptatif est bien demandé.
- `asksTheProviderToClearStaleToolResults` (existant) : les betas légitimes restent posés — le témoin
  n'interdit pas les betas, il interdit **celui-là**.

### Intégration (`AtelierChatServiceReasoningTest`, existant)

- `replaysTheReasoningOfTheTurnAheadOfItsTextAndToolCalls` : le raisonnement d'une étape est réémis
  en tête de l'étape suivante — l'entrelacement observable côté boucle.

### Isolation utilisateur

- Sans objet : aucun accès données, aucun endpoint, aucune requête nouvelle. La subfeature n'ajoute
  qu'un témoin sur la forme d'un appel sortant.

---

## Tables / endpoints / composants impactés

- **Tables** : aucune. **Migration** : aucune.
- **Endpoints** : aucun. **Frontend** : aucun. **Protocole runner** : aucun.
- **Code de production** : **aucun** (clôture par vérification).
- **Tests** : `AnthropicAgentProviderTest` (un témoin).

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | aucun changement de consommation : rien n'est ajouté à l'appel |
| Navigation / routing | Non | — |

---

## Hors périmètre

- **Poser l'en-tête beta `interleaved-thinking`** : caduc sur la génération cible, et risqué.
- **`thinking.display`** (résumé du raisonnement rendu à l'écran) : autre sujet, non demandé par le
  cadrage, et qui toucherait l'affichage — hors de cet écart.
- **Thinking préservé entre les tours** : c'est SF-121-20, subfeature distincte.
