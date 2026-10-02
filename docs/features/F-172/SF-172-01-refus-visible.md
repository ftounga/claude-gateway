# Mini-spec — [F-172 / SF-172-01] Refus visible

## Identifiant

`F-172 / SF-172-01`

## Feature parente

`F-172` — Opus 5.5 dans la Forge (`CADRAGE-F-172-opus-5-5-dans-la-forge.md`, décisions D1→D5 validées par le PO le 2026-10-02)

## Statut

`in-progress`

## Date de création

2026-10-02

## Branche Git

`feat/SF-172-01-refus-visible`

---

## Objectif

Reconnaître un refus du fournisseur (`stop_reason: "refusal"`) au lieu de le prendre pour un tour fini : la sortie partielle est jetée et le terminal affiche un message clair, avec la catégorie du refus.

---

## Comportement attendu

### Cas nominal

1. `AnthropicAgentProvider.toTurn` lit `stop_reason`. S'il vaut `refusal`, le tour est marqué **refusé** (`AgentTurn.refused() == true`), la catégorie est lue dans `stop_details.category` (informative, peut être absente).
2. La sortie partielle (texte, appels d'outils) d'un tour refusé est **jetée** : `text` vide, `toolCalls` vide. Les tokens restent comptés (le fournisseur facture la partie déjà produite).
3. Le flux SSE reporte `stop_details` depuis `message_delta` : le chemin streamé rend le même tour que le non streamé.
4. La boucle de la Forge (`AtelierChatService`) s'arrête sur un tour refusé, sans exécuter d'outil, et rend `refusalReply(catégorie)` : un message en clair qui nomme la catégorie (cybersécurité, biologie, extraction du raisonnement, ou « non précisée ») et dit quoi faire.
5. Chaque refus est journalisé (`stop_reason`, catégorie, modèle) ; le résumé de fin de tour porte l'arrêt « refus du fournisseur ».

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `stop_details` absent ou `null` | Refus reconnu quand même (on branche sur `stop_reason`, jamais sur `stop_details`), catégorie « non précisée » |
| Refus en cours de flux, après du texte déjà affiché | Texte partiel jeté ; le message de refus devient la réponse persistée |
| Refus avec un `tool_use` partiel | L'outil n'est **pas** exécuté |
| Autres `stop_reason` (`end_turn`, `tool_use`, `max_tokens`) | Inchangés |

---

## Critères d'acceptation

- [ ] Une réponse `stop_reason: refusal` (non streamée) donne `refused=true`, la catégorie, `text` vide, aucun appel d'outil.
- [ ] Même résultat sur le chemin streamé (SSE), catégorie comprise.
- [ ] `stop_details` absent : `refused=true`, catégorie `null`.
- [ ] La boucle de la Forge rend le message de refus (catégorie nommée) et n'exécute aucun outil du tour refusé.
- [ ] Les tours non refusés sont strictement inchangés (suite de tests existante verte).

---

## Périmètre

### Hors scope (explicite)

- Le repli serveur (`fallbacks`) : SF-172-02.
- La gestion fine des refus dans les sous-boucles (`task`, `explore`, synthèse, compaction, Radar) : elles reçoivent un tour fini au texte vide, qu'elles traitent déjà comme « pas de conclusion ».
- Aucune table, aucun endpoint, aucun composant Angular (le message s'affiche par le canal de réponse existant).

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Détection | `stop_reason == "refusal"` uniquement ; `stop_details` est informatif |
| Catégorie | chaîne ouverte (`cyber`, `bio`, `reasoning_extraction`, …) ; inconnue ⇒ affichée telle quelle ; absente ⇒ « non précisée » |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun : la réponse de refus passe par le flux `done` existant du terminal.

### Composants backend impactés

- `agent/AgentTurn` : composants `refused`, `refusalCategory` (contrat neutre ; constructeurs existants conservés).
- `agent/AnthropicAgentProvider` : `toTurn` (lecture `stop_reason`/`stop_details`), `ParseState` (report de `stop_details`).
- `atelier/AtelierChatService` : arrêt sur refus, `refusalReply`, `stopCause`.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | (les tokens d'un tour refusé restent comptés comme avant) |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `AnthropicAgentProviderTest` : refus non streamé (catégorie + sortie jetée) ; refus sans `stop_details` ; refus streamé (SSE).
- [ ] `AtelierChatServiceRefusalTest` (ou test existant de la boucle) : un tour refusé rend le message de refus, aucun outil exécuté.

### Tests d'intégration

Non applicable (pas d'endpoint) ; la suite complète `mvn test` garantit le démarrage du contexte.

### Isolation utilisateur

- [x] Non applicable — aucune lecture ni écriture de données ; le tour reste celui de l'utilisateur authentifié.

---

## Dépendances

Aucune. Corrige un défaut déjà latent sur Opus 5.

## Notes et décisions

- On branche sur `stop_reason`, jamais sur `stop_details` (doc fournisseur : `stop_details` peut être `null`).
