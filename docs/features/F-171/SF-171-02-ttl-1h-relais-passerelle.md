# Mini-spec — [F-171 / SF-171-02] TTL 1 h sur le cache système du relais passerelle

## Identifiant

`F-171 / SF-171-02`

## Feature parente

`F-171` — Préfixe stable (effondrer le cache_write)

## Statut

`in-progress`

## Date de création

2026-10-01

## Branche Git

`feat/SF-171-02-ttl-relais-passerelle`

---

## Objectif

> En une phrase : aligner le cache système du **relais chat-passerelle** (`AnthropicProvider.systemField`) sur un **TTL de 1 h** (`cache_control: {type: ephemeral, ttl: "1h"}`), comme le fait déjà l'Atelier, pour que le préfixe système du chemin F-101 reste cacheable au-delà des 5 min par défaut.

---

## Comportement attendu

### Cas nominal

1. Quand une requête de complétion demande le cache système (`cacheSystem() == true`, F-101 / SF-101-03), le champ `system` est rendu comme un bloc texte marqué `cache_control`.
2. Ce marqueur porte désormais `{"type": "ephemeral", "ttl": "1h"}` (au lieu de `{"type": "ephemeral"}` seul, qui vaut un TTL de 5 min par défaut).
3. Quand le cache n'est pas demandé (`cacheSystem() == false`), le champ `system` reste une **chaîne simple** — comportement inchangé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Cache non demandé | `system` rendu en chaîne simple, aucun marqueur (inchangé) |
| Clé API absente | `complete()` lève `AIProviderUnavailableException` (inchangé, hors périmètre de cette SF) |

---

## Critères d'acceptation

- [ ] Avec cache demandé, `systemField` rend un bloc portant `cache_control = {type: ephemeral, ttl: "1h"}`.
- [ ] Sans cache, `systemField` rend la chaîne simple (inchangé).
- [ ] Le `ttl` posé est identique à celui de l'Atelier (`AnthropicAgentProvider.CACHE_CONTROL` = `{type: ephemeral, ttl: 1h}`).
- [ ] Aucune autre modification de forme de requête (un seul champ touché).

---

## Périmètre

### Hors scope (explicite)

- Le déplacement STATE/PLAN/arborescence (c'est **SF-171-01**, livrée).
- Toute autre modification du relais passerelle (retry, raisonnement, upload, headers beta).
- Aucune table / endpoint / migration / composant Angular.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Marqueur de cache | `{"type": "ephemeral", "ttl": "1h"}` (GA, aucun en-tête beta requis — cf. `AnthropicAgentProvider` §93-97) |
| Chemin sans cache | chaîne simple, inchangé |

---

## Technique

### Endpoint(s)

Aucun. Chemin interne : `AnthropicProvider.systemField` (relais `AIProvider` → API Anthropic).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun (backend seul).

### Composants backend impactés (préoccupation transversale « assemblage du prompt + cache »)

- `ai/AnthropicProvider.systemField` (1 ligne).
- Test impacté : `ai/AnthropicProviderTest.systemIsAPlainStringUnlessCacheIsRequested`.

---

## Plan de test

### Tests unitaires

- [ ] `systemField(cached)` rend un bloc avec `cache_control = {type: ephemeral, ttl: "1h"}` (adapter `AnthropicProviderTest`).
- [ ] `systemField(plain)` rend la chaîne simple (inchangé).

### Tests d'intégration

Non applicable (pas d'endpoint ; comportement couvert par le test unitaire de forme de requête).

### Isolation workspace

- [x] Non applicable — raison : transformation pure de la forme du champ `system`, aucun accès aux données.

---

## Dépendances

### Subfeatures bloquantes

Aucune (indépendante de SF-171-01).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- Le `ttl` « 1h » est **GA** côté API (aucun en-tête beta `extended-cache-ttl-*` requis), conformément au commentaire déjà présent dans `AnthropicAgentProvider` (§93-97). On se contente d'aligner le relais passerelle sur l'Atelier.
