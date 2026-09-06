# Mini-spec — F-39 / SF-39-12 · Le contexte d'un tour ne déborde plus

## Identifiant

`F-39 / SF-39-12`

## Feature parente

`F-39` — L'Atelier comme harnais (lot 6 · Tenue longue)

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-12-edition-de-contexte`

---

## Objectif

Demander au fournisseur d'**écarter les résultats d'outils périmés** d'un tour long
(`clear_tool_uses`), pour qu'un tour de trente itérations cesse de pouvoir dépasser la fenêtre de
contexte — sans rien résumer, ni rien perdre de la conversation elle-même.

---

## Comportement attendu

### Contexte : où la fenêtre déborde exactement

La mémoire **d'un message à l'autre** est déjà bornée depuis SF-39-03 : un résultat conservé à
4 000 caractères, une trajectoire de tour à 40 000, un rejeu limité aux 5 derniers tours.

Ce qui n'est borné **nulle part**, c'est le contexte **à l'intérieur d'un tour**. À chaque itération,
la boucle réempile `assistant(tool_use…)` + `user(tool_result…)` dans la même liste `messages`, et
renvoie le tout. Or les résultats bruts sont volumineux : une sortie de `bash` va jusqu'à
**131 072 octets** (`MAX_BASH_OUTPUT_BYTES`), une lecture de fichier jusqu'à 2 000 lignes de 2 000
caractères (SF-39-06). Trente itérations de ce calibre — le plafond de la boucle — sortent de la
fenêtre du modèle. L'usage réel mesuré au cadrage relève déjà un **contexte maximal de 900 519
tokens** sur une session Claude Code : la marge n'est pas théorique.

### Cas nominal

1. Chaque appel de la boucle demande au fournisseur d'**écarter les résultats d'outils anciens**
   au-delà d'un seuil, en conservant les plus récents.
2. Tant que le seuil n'est pas atteint, rien ne change : la requête part comme avant, cache compris.
3. Au-delà, le fournisseur retire les résultats les plus anciens et ne traite que le reste. Les
   `tool_use` correspondants restent en place : la **structure** de la conversation est intacte.
4. Le nombre de résultats écartés et les tokens gagnés sont journalisés en `debug` — un contexte
   édité ne lève aucune erreur, seuls ces compteurs le disent.
5. Le réglage `app.atelier.context-pruning` (défaut `true`) coupe le mécanisme sans livraison.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `app.atelier.context-pruning=false` | ni `context_management`, ni en-tête beta ; comportement d'avant | inchangé |
| Réponse sans champ `context_management` | rien à journaliser, traduction normale du tour | 200 |
| Réponse avec `applied_edits` vide | rien à journaliser, traduction normale | 200 |
| Réponse avec un type d'édition inconnu | journalisé tel quel, aucune interprétation | 200 |
| Le fournisseur refuse l'option (beta retirée) | `AIProviderException` comme tout autre `400` ; le coupe-circuit rétablit le service sans livraison | 502 |
| Aucune clé (ni BYOK ni plateforme) | `AIProviderUnavailableException`, aucun appel | 503 |

---

## Critères d'acceptation

- [ ] La requête d'agent porte `context_management.edits[0].type = "clear_tool_uses_20250919"` et
      l'en-tête `anthropic-beta: context-management-2025-06-27` quand la politique est active.
- [ ] Le déclencheur est exprimé en **tokens d'entrée** (`trigger`), la conservation en **nombre de
      résultats récents** (`keep`), et un plancher d'écartement (`clear_at_least`) est envoyé.
- [ ] `clear_tool_inputs` n'est **pas** demandé : les paramètres d'appel restent, seuls les
      **résultats** sont écartés.
- [ ] Politique inactive ⇒ **ni** `context_management` **ni** en-tête beta dans la requête, et le
      corps est identique à celui d'avant cette subfeature.
- [ ] Une réponse portant `context_management.applied_edits` est traduite normalement (texte, appels
      d'outils, `usage`) — l'édition n'altère aucun champ existant d'`AgentTurn`.
- [ ] Le domaine n'écrit **jamais** `clear_tool_uses_20250919` : le nom du mécanisme n'existe que
      dans `AnthropicAgentProvider` (Provider Independence).
- [ ] `app.atelier.context-pruning=false` désactive le mécanisme de bout en bout.
- [ ] Le comptage du quota (SF-39-01) est inchangé : les trois champs de `usage` restent additionnés.
- [ ] Aucun test existant de cache de prompt (SF-39-01) ni de raisonnement (SF-39-10) ne régresse.

---

## Périmètre

### Hors scope (explicite)

- La **compaction** serveur (`compact_20260112`) — voir « Notes et décisions », D-L6-7.
- L'écartement des blocs de **raisonnement** (`clear_thinking`) : ils ne sont déjà pas persistés et
  ne vivent que le temps d'un tour (SF-39-10, D-L5-3). Il n'y a rien à y gagner.
- Le chemin **Managed Agents** et le fournisseur de **chat** (F-02).
- Toute remontée **à l'écran** d'une édition de contexte : aucune UI dans cette subfeature.
- Le rejeu **entre** messages (déjà borné par SF-39-03) : rien n'y change.

---

## Valeurs initiales

Aucune entité créée.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|-------|-------------|------------------|---------------|
| `app.atelier.context-pruning` | Non | booléen | `null` ⇒ `true` |

Constantes **nommées, non configurables** (même règle qu'en SF-39-04) :

| Constante | Valeur | Raison |
|-----------|--------|--------|
| `CONTEXT_TRIGGER_INPUT_TOKENS` | 200 000 | large devant un tour ordinaire, très en deçà de la fenêtre |
| `CONTEXT_KEEP_TOOL_RESULTS` | 3 | ce sur quoi l'agent travaille à l'instant |
| `CONTEXT_CLEAR_AT_LEAST_INPUT_TOKENS` | 20 000 | plancher d'écartement : sous ce gain, l'édition coûterait plus de cache qu'elle ne fait économiser |

---

## Technique

### Endpoint(s)

Aucun endpoint créé ni modifié.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun. Subfeature strictement backend.

### Classes impactées

| Classe | Opération |
|--------|-----------|
| `fr.claudegateway.agent.AgentContextPolicy` | **nouveau** — intention neutre : écarter les résultats périmés |
| `fr.claudegateway.agent.AgentTurnRequest` | + `contextPolicy` (défaut : aucune) |
| `fr.claudegateway.agent.AnthropicAgentProvider` | mapping `context_management` + en-tête beta + journal |
| `fr.claudegateway.atelier.AtelierProperties` | + `contextPruning` |
| `fr.claudegateway.atelier.AtelierChatService` | construit la politique et la passe à chaque tour |
| `backend/src/main/resources/application.yml` | 1 clé de configuration |

---

## Plan de test

### Tests unitaires

- [ ] `AgentContextPolicy` — `none()` est inactive ; une politique construite porte ses trois bornes.
- [ ] `AtelierPropertiesTest` — `context-pruning` : défaut `true`, `false` honoré.
- [ ] `AnthropicAgentProviderTest` — politique active : `type`, `trigger`, `keep`, `clear_at_least`
      présents et bien formés ; `clear_tool_inputs` absent ; en-tête beta présent.
- [ ] `AnthropicAgentProviderTest` — politique inactive : ni `context_management` ni en-tête beta.
- [ ] `AnthropicAgentProviderTest` — une réponse portant `applied_edits` est traduite normalement.

### Tests d'intégration

- [ ] `AtelierChatServiceContextTest` — la boucle transmet une politique **active** par défaut au
      fournisseur, et une politique **inactive** quand `context-pruning=false`.

### Isolation utilisateur

- [x] Non applicable — aucun accès aux données ; la boucle appelante conserve son
      `workspaceService.requireOwned(userId, workspaceId)` en toute première instruction, inchangé.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-01` — statut : `done` (le marqueur de cache et l'édition de contexte cohabitent).
- `SF-39-11` — statut : `done` (même classe fournisseur).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

**D-L6-7 — Édition de contexte, pas compaction.** Le cadrage laissait le choix (« compaction **ou**
édition de contexte »). Trois raisons de trancher pour l'édition :

1. *Elle est sans état.* L'édition est réappliquée par le fournisseur à chaque requête, à partir de
   ce qu'on lui envoie. La compaction, elle, rend des **blocs de compaction** qu'il faut renvoyer
   inchangés au tour suivant, sous peine de perdre l'état en silence — or notre boucle **reconstruit**
   `messages` depuis l'historique persisté à chaque message. Adopter la compaction imposerait de
   persister ces blocs, donc une colonne, une migration, et un format de plus à faire survivre.
2. *Le débordement est intra-tour, pas inter-tours.* La mémoire d'un message à l'autre est déjà
   bornée (SF-39-03). Ce qui déborde, c'est l'accumulation des `tool_result` **dans un même tour** —
   exactement ce que l'édition écarte.
3. *Elle ne réécrit rien.* Résumer, c'est décider à la place de l'agent ce qui comptait. Écarter un
   résultat de commande vieux de vingt itérations ne perd que ce que l'agent a déjà exploité.

Réversible : la compaction reste ouverte si un besoin **inter-tours** apparaît.

**D-L6-8 — Les paramètres d'appel restent, les résultats partent.** `clear_tool_inputs` n'est pas
demandé. Savoir *qu'on a lancé `mvn test` il y a vingt itérations* tient en quelques tokens et
empêche l'agent de le relancer ; c'est la **sortie** de la commande, elle, qui pèse jusqu'à 128 Ko.
Écarter les deux économiserait marginalement plus et ferait perdre la trace de ce qui a été tenté.

**D-L6-9 — Un plancher d'écartement pour ne pas casser le cache pour rien.** Une édition modifie le
préfixe, donc invalide le cache de prompt (SF-39-01) à partir du point édité. Sans plancher, le
fournisseur pourrait écarter quelques centaines de tokens et faire payer une réécriture complète du
cache : le remède coûterait plus que le mal. `clear_at_least` garantit qu'on ne paie cette
invalidation que pour un gain qui la justifie.

**D-L6-10 — L'intention est neutre, le mécanisme ne l'est pas.** `AgentContextPolicy` dit *« écarte
les résultats d'outils périmés au-delà de tant, garde les tant derniers »* — une intention qu'un
autre fournisseur pourrait servir autrement, ou ignorer. La chaîne `clear_tool_uses_20250919` et
l'en-tête beta n'existent que dans `AnthropicAgentProvider`, comme `thinking` et `output_config`
depuis SF-39-10.

**D-L6-11 — Un coupe-circuit, parce que c'est une capacité *beta*.** Si le fournisseur retirait
l'option, `context_management` deviendrait un champ invalide et **chaque** tour de l'Atelier
échouerait en `400`. `app.atelier.context-pruning=false` rétablit le service par variable
d'environnement, sans livraison. C'est le prix d'entrée d'une beta dans un chemin critique.
