# Mini-spec — F-39 / SF-39-10 — Le harnais raisonne : `thinking` adaptatif, `effort`, `claude-opus-5`

## Identifiant

`F-39 / SF-39-10`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-10-raisonnement`

---

## Objectif

> La boucle maison appelle le fournisseur **avec un raisonnement**, sur le **modèle qu'elle a
> choisi** et à un **effort configuré** — au lieu d'hériter, sans le dire, du modèle par défaut du
> chat et d'un raisonnement éteint.

---

## Déclencheur

Lot 5 du cadrage F-39, décision **D2** (« au-delà de la parité »). L'écran est unifié depuis le lot 4 :
le moteur le plus abouti — celui qui exécute sur la machine de l'utilisateur — est la **boucle
maison**. Or cette boucle appelle aujourd'hui :

| Réglage | Boucle maison (aujourd'hui) | Chemin Managed Agents |
|---|---|---|
| Modèle | `modelCatalog.defaultModel()` → `claude-opus-4-8` | `claude-opus-5` (`app.atelier.agent.model`) |
| Raisonnement | **aucun** (paramètre jamais envoyé) | adaptatif, porté par la plateforme |
| Effort | **non envoyé** (défaut serveur) | `xhigh`, choisi (SF-28-17) |

Deux moteurs, un seul écran, et le plus utilisé des deux raisonne **moins**. Ce n'est pas un
arbitrage : personne ne l'a pris. Le modèle de la boucle est celui que le **chat** propose par
défaut (F-02) — un réglage qui appartient à une autre feature et qui bouge pour d'autres raisons.

---

## Comportement attendu

### Cas nominal — un tour de la boucle maison

Chaque itération d'un tour envoie au fournisseur, en plus de ce qu'elle envoyait déjà (consigne
système cachée, historique, outils) :

- `model` = `app.atelier.model` (défaut **`claude-opus-5`**) ;
- `thinking` = `{"type": "adaptive"}` — le modèle décide **quand** et **combien** raisonner ;
- `output_config` = `{"effort": "<app.atelier.effort>"}` (défaut **`high`**).

La réponse peut alors contenir, **avant** le texte et les `tool_use`, des blocs de raisonnement
**signés**. Ils sont capturés et remis en tête du message assistant rejoué à l'itération suivante,
**inchangés**.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Effort inconnu en configuration (`APP_ATELIER_EFFORT=turbo`) | Retombe sur le défaut `high` ; **aucun** échec au démarrage (même règle que SF-28-17) |
| Modèle vide en configuration | Retombe sur `claude-opus-5` |
| Le fournisseur refuse le modèle (clé BYOK sans accès à `claude-opus-5`) | `AIProviderException`, message neutre ; le diagnostic d'erreur fournisseur (SF-30-08) est inchangé |
| Réponse coupée au plafond de sortie (le raisonnement partage `max_tokens` avec le texte) | Chemin SF-28-18 inchangé : rien n'est exécuté, message explicite |
| Bloc `redacted_thinking` rendu par le fournisseur | Rejoué **tel quel**, comme un bloc de raisonnement ordinaire |
| Bloc de raisonnement sans signature | Rejoué sans le champ `signature` (jamais `null` sur le fil) |

---

## Critères d'acceptation

- [ ] La requête d'un tour porte `"thinking": {"type": "adaptive"}`.
- [ ] Elle porte `"output_config": {"effort": "<valeur configurée>"}`.
- [ ] Le modèle envoyé est celui de `app.atelier.model` (défaut `claude-opus-5`), **pas** le modèle
      par défaut du catalogue de chat.
- [ ] Les blocs `thinking` de la réponse sont rendus par le provider et remis **en tête** du message
      assistant de l'itération suivante, avant le texte et les `tool_use`, texte et signature
      inchangés.
- [ ] Un bloc `redacted_thinking` est rejoué tel quel (`{"type":"redacted_thinking","data":…}`).
- [ ] Le texte de raisonnement n'est **jamais** confondu avec la réponse : il n'alimente pas
      `AgentTurn.text()`.
- [ ] Aucun bloc de raisonnement n'est persisté dans `tool_trace`, ni rejoué depuis l'historique
      d'un message précédent.
- [ ] Un effort invalide en configuration retombe sur le défaut sans exception.
- [ ] Le comptage de quota (F-10) est inchangé : les tokens de raisonnement sont déjà dans
      `usage.output_tokens` du fournisseur.
- [ ] Le nombre de marqueurs `cache_control` d'une requête reste ≤ 4 (les blocs de raisonnement
      n'en portent pas).

---

## Périmètre

### Hors scope (explicite)

- **Afficher** le raisonnement à l'écran. `thinking.display` reste à sa valeur par défaut
  (`omitted`) : un résumé de raisonnement dans la ligne vivante est un sujet d'écran (F-30 /
  SF-30-13), avec son flux SSE, son rendu et ses tests d'acquis. Voir D-L5-5.
- Le chemin **Managed Agents**, déjà réglé par SF-28-17 : rien n'y change.
- Le **chat** (F-02) et son catalogue de modèles : la liste blanche `ANTHROPIC_MODELS` dit ce que
  l'utilisateur peut **choisir** dans le chat, pas ce que le harnais exécute.
- Compaction, retry 429/529, timeout HTTP câblé : lot 6 (SF-39-11 / SF-39-12).
- Plafond de dépense par message : lot 8 (SF-39-15).
- Toute évolution de `max_tokens` (`app.ai.anthropic.agent-max-tokens`), qui appartient au lot 6 :
  voir « Risques résiduels ».

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs autorisées | Défaut | Normalisation |
|---|---|---|---|---|
| `app.atelier.model` | Non | identifiant de modèle non vide | `claude-opus-5` | vide/blanc ⇒ défaut |
| `app.atelier.effort` | Non | `low`, `medium`, `high`, `xhigh`, `max` | `high` | inconnu/vide ⇒ défaut |

Notes :
- La liste des efforts est la même que celle de `AtelierAgentProperties` (SF-28-17) : deux chemins,
  un seul vocabulaire.
- Le modèle **n'est pas** validé contre `ModelCatalog.availableModels()` : ce catalogue est celui du
  chat (F-02) et ne contient pas `claude-opus-5`. Le valider contre lui interdirait la cible.

---

## Technique

### Endpoint(s)

Aucun. Aucune API n'est ajoutée ni modifiée.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucun changement de schéma.

### Classes impactées

| Classe | Changement |
|---|---|
| `agent/AgentReasoning.java` (nouveau) | Réglage de raisonnement neutre : `adaptive` + `effort` |
| `agent/AgentContentBlock.java` | Deux blocs : `Reasoning(text, signature)`, `RedactedReasoning(data)` |
| `agent/AgentTurnRequest.java` | Composant `reasoning` (+ constructeur historique à 5 arguments) |
| `agent/AgentTurn.java` | Composant `reasoning` (blocs rendus par le tour) |
| `agent/AnthropicAgentProvider.java` | Envoi de `thinking` / `output_config`, lecture et rejeu des blocs |
| `atelier/AtelierProperties.java` | `model` + `effort` validés |
| `atelier/AtelierChatService.java` | Modèle lu dans la configuration ; blocs de raisonnement remis en tête du message assistant ; `ModelCatalog` retiré (plus aucun usage) |
| `resources/application.yml` | `app.atelier.model`, `app.atelier.effort` |

### Composants Angular

Aucun. Subfeature **backend seule** : aucun contrat d'API ne bouge, aucun écran ne change.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant (`user_id`) | Non | Aucun nouvel accès aux données ; le tour reste borné par `requireOwned` |
| Plans / limites | **Oui, indirectement** | `QuotaService.recordUsage` : inchangé — les tokens de raisonnement sont déjà comptés dans `output_tokens` renvoyé par le fournisseur, additionnés depuis SF-39-01 |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `AnthropicAgentProviderTest` — la requête porte `thinking.type = adaptive` quand le
      raisonnement est demandé, et **rien** quand il ne l'est pas.
- [ ] `AnthropicAgentProviderTest` — la requête porte `output_config.effort`, et l'omet si l'effort
      est vide.
- [ ] `AnthropicAgentProviderTest` — les blocs `thinking` de la réponse sont rendus dans
      `AgentTurn.reasoning()` avec leur signature, et n'alimentent pas `text()`.
- [ ] `AnthropicAgentProviderTest` — un bloc `redacted_thinking` est rendu et réémis sous sa forme
      d'origine.
- [ ] `AnthropicAgentProviderTest` — un message assistant porteur de blocs de raisonnement est
      traduit avec ces blocs **en tête**, et le compte de marqueurs `cache_control` reste ≤ 4.
- [ ] `AtelierPropertiesTest` — défauts `claude-opus-5` / `high`, effort inconnu ⇒ `high`, effort
      valide conservé.
- [ ] `AtelierChatServiceReasoningTest` — le tour envoie le modèle configuré et le réglage de
      raisonnement.
- [ ] `AtelierChatServiceReasoningTest` — les blocs de raisonnement d'une itération sont rejoués en
      tête du message assistant de l'itération suivante.
- [ ] `AtelierChatServiceReasoningTest` — la trajectoire persistée (`tool_trace`) ne contient aucun
      bloc de raisonnement.

### Tests d'intégration

- [ ] Aucun nouvel endpoint : la couverture d'intégration existante (`AtelierChatApiIntegrationTest`)
      doit rester verte — c'est le test de non-régression du contrat.

### Isolation workspace

- [x] **Non applicable** — aucun nouvel accès aux données. Le tour continue de passer par
      `requireOwned(userId, workspaceId)` ; aucune requête n'est ajoutée.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-01` (cache de prompt) — done : le comptage des tokens cachés y a été réglé, celui-ci n'y touche pas.
- `SF-39-03` (mémoire de la trajectoire) — done : c'est elle qui rejoue les tours passés, et qui définit ce qui n'est **pas** rejoué.
- `SF-39-08` (écran unique) — done : c'est ce qui rend l'écart de raisonnement entre les deux moteurs visible.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

**D-L5-1 — Le modèle de la boucle maison est un réglage à elle.** Il était emprunté au catalogue du
chat (`ModelCatalog.defaultModel()`). Deux features distinctes ne doivent pas partager un réglage
par hasard : changer le modèle proposé aux utilisateurs dans le chat changeait silencieusement le
modèle qui **exécute des commandes sur leur machine**. `app.atelier.model` (défaut `claude-opus-5`)
met le harnais au niveau du chemin Managed Agents, qui y est déjà.

**D-L5-2 — Le raisonnement adaptatif est envoyé explicitement.** Sur `claude-opus-5`, omettre
`thinking` revient déjà à `{"type":"adaptive"}` ; sur `claude-opus-4-8`, l'omission veut dire
**aucun raisonnement**. Un paramètre dont le sens dépend du modèle n'est pas un réglage : on
l'écrit. Le jour où la configuration ramène le modèle en arrière, le comportement ne change pas en
silence.

**D-L5-3 — Les blocs de raisonnement sont rejoués tels quels, dans le tour, et nulle part
ailleurs.** C'est le point dur de cette subfeature. Le raisonnement actif fait précéder les
`tool_use` de blocs **signés** ; le fournisseur exige de les retrouver inchangés sur le dernier tour
d'assistant lorsqu'on lui renvoie les `tool_result`. Les omettre casserait la boucle dès la
**deuxième itération** — c'est-à-dire presque tous les tours utiles. Ils sont donc capturés et remis
en tête du message assistant. En revanche ils ne sont **pas persistés** dans `tool_trace` : d'un
message à l'autre, le raisonnement des tours précédents est ignoré par le fournisseur, et un bloc
signé stocké puis rejoué hors de son contexte serait au mieux du poids mort, au pire un refus. La
règle tient en une phrase : **le raisonnement vit le temps d'un tour**.

**D-L5-4 — `effort` par défaut à `high`, pas `xhigh`.** `high` est le défaut du fournisseur : le
poser explicitement ne change donc **rien** au comportement d'aujourd'hui, et rend le levier
réglable sans livraison. `xhigh` est le meilleur réglage pour du travail d'agent, mais la boucle
maison appelle en **non-streamé**, avec un budget de tour de 10 minutes partagé entre 30 itérations
(SF-38-07) et un timeout HTTP qui n'est pas encore câblé (lot 6, SF-39-12) : monter l'effort avant
d'avoir traité la tenue longue échangerait de la profondeur contre des tours coupés au budget, ce
que l'utilisateur lit comme une panne. Le chemin Managed Agents reste à `xhigh` parce qu'il est
streamé côté fournisseur. `APP_ATELIER_EFFORT=xhigh` suffira à basculer une fois le lot 6 livré :
décision **réversible en une variable d'environnement**.

**D-L5-5 — Le raisonnement n'est pas affiché.** `thinking.display` reste à `omitted` : les blocs
reviennent avec un texte vide et leur signature, et c'est la signature qui compte pour le rejeu.
Afficher un résumé demanderait de le faire descendre dans le flux SSE, de le rendre dans la ligne
vivante et de le protéger par un test d'acquis §4 — une subfeature d'écran, pas un effet de bord de
celle-ci.

### Risques résiduels

- **Troncature plus fréquente.** Le raisonnement partage `max_tokens` (16 384) avec le texte de
  sortie. Un tour qui réécrit un gros fichier **et** raisonne longuement peut être coupé plus
  souvent qu'avant. Le cas est déjà traité (SF-28-18 : rien n'est exécuté, message explicite), et
  `max_tokens` appartient au lot 6 — le relever ici, sans timeout câblé ni streaming, remplacerait
  une troncature visible par une attente sans fin.
- **Clé BYOK sans accès à `claude-opus-5`.** L'appel échoue avec le message neutre habituel. Le
  réglage est une variable d'environnement, donc réversible immédiatement.
