# Mini-spec — [F-172 / SF-172-05] Garde-fous de requête

## Identifiant

`F-172 / SF-172-05`

## Feature parente

`F-172` — Opus 5.5 dans la Forge (D5 validée par le PO le 2026-10-02)

## Statut

`in-progress`

## Date de création

2026-10-02

## Branche Git

`feat/SF-172-05-garde-fous-requete`

---

## Objectif

Préparer la bascule sans rien retirer au raisonnement : relever le plafond de sortie sur les appels en flux, garantir un effort explicite sur tous les appels, et poser le contrôle de liaison du raisonnement en `drop_block` avec un journal compté des blocs perdus (D5).

---

## Comportement attendu

### Cas nominal

1. **Plafond de sortie.** Appel **en flux** : `max_tokens` = `app.agent.api-features.streamed-max-tokens` (défaut 32 768), ou `escalated-max-tokens` (défaut 65 536) quand l'effort effectif de l'appel est `xhigh` ou `max` (effort de la requête, ou dernière consigne d'effort glissée dans la conversation — F-134). Appel **non streamé** (sous-boucles, synthèse, compaction, Radar, repli après échec du flux) : `agent-max-tokens` inchangé (16 384) — un appel non streamé plus long dépasserait le délai HTTP de 5 min.
2. **Effort explicite.** Pour un modèle de `explicit-effort-models` (défaut : `claude-opus-5-5`, dont le défaut fournisseur est `medium` au lieu de `high`), un appel qui ne précise aucun effort part avec `output_config.effort = default-effort` (défaut `high`, le défaut d'Opus 5) et `thinking: adaptive`. Inventaire des appels : boucle et étapes (effort posé), escalade F-119 (posé), exploration (Sonnet 5, hors liste — D4), sous-tâche `task` (posé si configuré, sinon garde), synthèse forcée (garde), compaction (garde), Radar (garde).
3. **Liaison du raisonnement (D5).** Pour un modèle de `binding-control-models` (défaut : `claude-opus-5-5`, `claude-fable-5-1`), la requête porte `thinking.block_binding.prefix_mismatch_behavior = "drop_block"` et l'en-tête `thinking-binding-controls-2026-08-01`. Le tableau `input_transformations` de la réponse (premier niveau, ou `message_start`/`message_delta` en flux) est lu : chaque bloc perdu est **compté** et journalisé (`thinking_dropped`, raison, chemin) — jamais de 400 « bound to a different conversation ».

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Modèle hors listes (Opus 5 actuel, Sonnet 5, Haiku) | Corps strictement inchangé (hors plafond en flux relevé) |
| Repli non streamé après échec du flux | Plafond non streamé (16 384) |
| `input_transformations` absent ou vide | Rien n'est journalisé |
| Entrée de type ou raison inconnus | Comptée et journalisée telle quelle, sans erreur |

---

## Critères d'acceptation

- [ ] Flux, effort `high` : `max_tokens` = 32 768 ; flux, effort `xhigh` (racine ou consigne par message) : 65 536 ; non streamé : 16 384.
- [ ] Modèle `claude-opus-5-5` sans effort demandé : `output_config.effort == "high"` et `thinking.type == "adaptive"` ; avec effort demandé : l'effort demandé.
- [ ] Modèle hors liste sans effort : corps inchangé (ni `thinking`, ni `output_config`).
- [ ] `claude-opus-5-5` : `thinking.block_binding.prefix_mismatch_behavior == "drop_block"` + en-tête.
- [ ] Réponse avec deux `thinking_dropped` : compteur du tour = 2, journal émis (non streamé et streamé).

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Plafonds | entiers > 0 ; un réglage absent retombe sur son défaut |
| Effort par défaut | `low`/`medium`/`high`/`xhigh`/`max` ; défaut `high` |

---

## Périmètre

### Hors scope (explicite)

- Historique « append-only » (compaction serveur, messages système en cours de conversation) : seulement si la mesure D5 le justifie (feature séparée).
- La bascule de `APP_ATELIER_MODEL` : SF-172-06.
- Tables, endpoints, écrans : aucun.

---

## Technique

### Composants backend impactés (préoccupation « Plans / limites »)

- `agent/AgentApiFeaturesProperties` (plafonds, effort, listes), `agent/AnthropicAgentProvider` (corps en flux / non streamé, effort, `block_binding`, `input_transformations`), `agent/AgentTurn` (`droppedThinkingBlocks`).
- Budget de tour `AtelierTurnBudget` : inchangé — il borne des tokens, dont la sortie relevée fait partie (le plafond de consommation du message continue de s'appliquer avant chaque appel).
- `application.yml`.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Plans / limites | Oui | plafond de sortie relevé ⇒ consomme le même quota ; `AtelierTurnBudget` vérifié inchangé |
| Auth, tenant, routing | Non | — |

---

## Plan de test

- [ ] `AnthropicAgentProviderTest` : plafonds (flux / flux escaladé par racine et par consigne / non streamé / repli) ; effort explicite (liste / hors liste / effort fourni) ; `block_binding` + en-tête ; comptage `input_transformations` (non streamé, streamé).
- [ ] Suite complète verte (démarrage du contexte).
- [ ] Isolation : non applicable.

## Dépendances

SF-172-04 mergée.

## Notes et décisions

- **Arbitrage (réversible)** : le plafond relevé ne vaut que pour les appels en flux. Les appels non streamés restent à 16 384 : relever leur plafond transformerait des coupures lisibles (`truncated`) en dépassements du délai HTTP de 5 min, illisibles et facturés.
- **Arbitrage (réversible)** : l'effort par défaut forcé est `high` (défaut d'Opus 5) : la bascule ne baisse pas silencieusement le raisonnement des appels qui n'en précisaient pas (règle « justesse avant coût »).
- Le compte des blocs perdus est journalisé (CloudWatch, ligne `thinking_dropped=`) : c'est la mesure D5 de la SF-172-06.
