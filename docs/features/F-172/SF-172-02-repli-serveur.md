# Mini-spec — [F-172 / SF-172-02] Repli serveur

## Identifiant

`F-172 / SF-172-02`

## Feature parente

`F-172` — Opus 5.5 dans la Forge (D1 validée par le PO le 2026-10-02)

## Statut

`in-progress`

## Date de création

2026-10-02

## Branche Git

`feat/SF-172-02-repli-serveur`

---

## Objectif

Relayer le repli côté serveur du fournisseur (`fallbacks: "default"`) : un refus de filtre est rejoué par le fournisseur sur le modèle qu'il recommande, au lieu d'arrêter le tour ; la réponse d'un repli est lue correctement et le modèle servi remonte dans le tour.

---

## Comportement attendu

### Cas nominal

1. Pour un modèle de la liste `app.agent.api-features.fallback-models` (défaut : `claude-opus-5-5`, `claude-opus-5`, `claude-fable-5-1`, `claude-fable-5`) et si le coupe-circuit `APP_ATELIER_FALLBACKS` vaut `true` (défaut), la requête porte `"fallbacks": "default"` et l'en-tête bêta `server-side-fallback-2026-07-01`.
2. Réponse avec un ou plusieurs blocs `fallback` : on repère le **dernier**. Avant lui, les blocs `thinking`, `redacted_thinking`, `tool_use` (et tout type inconnu) sont **écartés** : ni rejoués, ni exécutés. Les blocs `text` sont conservés. Après lui, tout est lu normalement. Le marqueur `fallback` lui-même n'est pas rejoué (marqueur d'audit, « keep or drop » selon la doc fournisseur).
3. Le modèle **servi** (champ `model` de la réponse, `message_start.message.model` en flux) remonte dans `AgentTurn.servedModel()`.
4. Chaque repli est journalisé (`de → vers`) ; un tour servi par un modèle de repli (entrée `fallback_message` dans `usage.iterations`) aussi.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Toute la chaîne refuse (`stop_reason: refusal` final) | Refus visible (SF-172-01) |
| Refus `reasoning_extraction` (non rejoué par le fournisseur) | Refus visible (SF-172-01) |
| Modèle hors liste (Sonnet 5 de l'exploration, Haiku…) | Aucun `fallbacks`, aucun en-tête : requête strictement inchangée |
| Coupe-circuit `APP_ATELIER_FALLBACKS=false` | Aucun `fallbacks`, aucun en-tête |
| Réponse sans bloc `fallback` | Lecture inchangée |

---

## Critères d'acceptation

- [ ] Modèle de la liste : corps avec `fallbacks: "default"` et en-tête `server-side-fallback-2026-07-01` (cumulé aux autres bêtas).
- [ ] Modèle hors liste ou coupe-circuit à `false` : ni champ, ni en-tête.
- [ ] Réponse `[thinking, tool_use, fallback, thinking, text, tool_use]` : seul le `tool_use` d'après le repli est rendu, seul le `thinking` d'après le repli est rejoué, le texte est gardé.
- [ ] Même lecture sur le chemin streamé (le bloc `fallback` arrive comme un `content_block_start` ordinaire).
- [ ] `servedModel` porte le modèle de la réponse (non streamé et streamé).

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Forme | scalaire `"default"` + en-tête `server-side-fallback-2026-07-01` (la forme tableau, autre en-tête, n'est pas employée) |
| Liste de modèles | configuration (`APP_AGENT_FALLBACK_MODELS`), comparaison exacte de l'identifiant |

---

## Périmètre

### Hors scope (explicite)

- Le coût par tentative (`usage.iterations`) : SF-172-03.
- Le crédit de repli (`fallback_credit_token`), le middleware SDK : sans objet (API Claude directe, repli serveur).
- Aucune table, aucun endpoint, aucun écran.

---

## Technique

### Composants backend impactés

- `agent/AgentApiFeaturesProperties` (nouveau, `app.agent.api-features`) + `agent/AgentApiConfig`.
- `agent/AnthropicAgentProvider` : corps, en-têtes, lecture des blocs `fallback`, modèle servi.
- `agent/AgentTurn` : composant `servedModel`.
- `application.yml` : `app.agent.api-features.fallbacks` / `fallback-models`.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | (le coût par tentative est SF-172-03) |
| Navigation / routing | Non | — |

### Tables / endpoints / Angular

Aucun. Migration Liquibase : non applicable.

---

## Plan de test

### Tests unitaires (`AnthropicAgentProviderTest`)

- [ ] Corps + en-tête pour un modèle de la liste ; rien pour un modèle hors liste ; rien si coupe-circuit.
- [ ] Lecture d'un repli en cours de sortie (non streamé et streamé) : blocs antérieurs écartés, `tool_use` postérieur rendu.
- [ ] `servedModel` lu (non streamé, streamé).

### Tests d'intégration

Suite complète (`mvn test`) : démarrage du contexte avec le nouveau `@ConfigurationProperties`.

### Isolation utilisateur

- [x] Non applicable — aucune donnée lue ni écrite.

---

## Dépendances

SF-172-01 (refus visible) mergée.

## Notes et décisions

- **Arbitrage (réversible)** : le marqueur `fallback` est **retiré** du message rejoué au lieu d'être conservé à sa place (le cadrage prévoyait un bloc neutre `Fallback`). La doc fournisseur le décrit comme un marqueur d'audit ignoré, « keep or drop » ; le retirer évite d'ajouter un type au contrat neutre `AgentContentBlock` (et ses `switch` exhaustifs). Retour arrière : ajouter le bloc neutre si la mesure D5 montre des pertes liées à son retrait.
