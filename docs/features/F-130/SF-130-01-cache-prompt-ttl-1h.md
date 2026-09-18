# Mini-spec — F-130 / SF-130-01 — Cache de prompt en TTL 1 heure

## Identifiant

`F-130 / SF-130-01`

## Feature parente

`F-130` — Maîtrise du coût jetons (parité Claude Code)

## Statut

`ready`

## Date de création

2026-09-18

## Branche Git

`feat/SF-130-01-cache-prompt-ttl-1h`

---

## Objectif

Faire porter au cache de prompt de la boucle d'agent un **TTL de 1 heure** au lieu de 5 minutes,
pour qu'un usage étalé dans la journée relise le préfixe stable (au tarif cache-read) au lieu de le
ré-écrire (cache-creation) — sans changer d'un octet ce que le modèle voit.

---

## Comportement attendu

### Cas nominal

- Chaque marqueur `cache_control` posé par `AnthropicAgentProvider` (bloc système — qui couvre aussi
  les outils rendus avant lui — et dernier bloc du dernier message d'historique) porte désormais
  `{"type":"ephemeral","ttl":"1h"}` au lieu de `{"type":"ephemeral"}`.
- Le corps de requête est **par ailleurs identique** : mêmes points de marquage, même nombre de
  marqueurs (≤ 4, 2 par construction), même contenu, même ordre. Le préfixe caché ne bouge pas.
- Les deux entrées de cache portent le **même** TTL (1 h) : la règle « une entrée de TTL long doit
  précéder une entrée de TTL court » est trivialement respectée (aucun TTL mixte).
- Décompte de **volume** : `usage.cache_creation_input_tokens` (plat) et `usage.cache_read_input_tokens`
  restent lus tels quels dans `toTurn` — le volume traité et le décompte cache/lecture sont
  **inchangés et corrects** quel que soit le TTL (le fournisseur remplit toujours le champ plat ;
  `usage.cache_creation` le ventile par TTL mais la boucle n'en dépend pas).
- Tarif d'écriture de cache (F-63) : **laissé à `6.25` volontairement.** La clé
  `app.atelier.agent.cost.cache-write-cost-per-million-tokens` est **partagée** par le décompte de la
  boucle maison (`QuotaService`) **et** celui des Managed Agents (`AtelierSessionService`), via le
  même `BilledTokensCalculator`. La monter à 2× (10.00) altérerait le décompte des Managed Agents
  (hors périmètre SF-130-01, « ne pas toucher au provider Managed Agents »). Le TTL 1 h rend les
  **créations** de cache rares (l'entrée est relue au tarif lecture) : le résidu de sous-compte est
  négligeable et **au bénéfice du client** (jamais de sur-facturation), la marge reste protégée.
  Commentaire yml mis à jour pour tracer la décision.

### En-tête beta

- Vérifié via la skill `claude-api` (`shared/prompt-caching.md`, API reference) : le TTL 1 h est
  **GA** — `cache_control: {"type":"ephemeral","ttl":"1h"}` est un simple champ, **aucun en-tête
  beta requis** (l'ancien `extended-cache-ttl-2025-04-11` n'est plus mentionné, feature sortie de
  beta). `anthropic-version: 2023-06-01` (config actuelle) suffit. L'en-tête
  `context-management-2025-06-27` déjà posé quand l'écartement est actif reste inchangé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Le fournisseur ignore le champ `ttl` (compat descendante) | Comportement dégradé vers TTL 5 min par défaut fournisseur — aucun échec, aucune perte fonctionnelle |
| Préfixe trop court pour être caché | `cache_creation_input_tokens: 0`, aucun échec (comportement existant, inchangé) |
| Retry 429/529 | Corps calculé une fois, rejoué à l'identique — le marqueur (TTL compris) ne glisse pas (SF-39-11, inchangé) |

---

## Critères d'acceptation

- [ ] Le corps de requête porte `"ttl":"1h"` sur le `cache_control` du bloc système.
- [ ] Le corps de requête porte `"ttl":"1h"` sur le `cache_control` du dernier bloc du dernier message.
- [ ] Le nombre de marqueurs `cache_control` reste ≤ 4 et vaut 2 par construction (inchangé).
- [ ] Aucun en-tête beta supplémentaire n'est requis ni ajouté pour le TTL cache (documenté).
- [ ] Le décompte d'usage (`cache_creation_input_tokens`, `cache_read_input_tokens`, volume total)
      est inchangé — tests provider existants verts.
- [ ] `cache-write-cost-per-million-tokens` reste `6.25` (rate partagé Managed Agents, hors scope) ;
      le commentaire yml trace la décision et l'impact 1 h.
- [ ] Le retry 429/529 et le streaming SSE sont inchangés (tests existants verts).

---

## Préoccupations transversales

- **Auth / Principal** : non concerné (aucune modification d'auth, de Principal ou de session).
- **Contexte tenant** : non concerné (aucune résolution de tenant modifiée ; la clé BYOK/plateforme
  est résolue comme avant).
- **Plans / limites** : le décompte au coût réel (F-63) est **inchangé**. Le tarif d'écriture de
  cache (`app.atelier.agent.cost.cache-write-cost-per-million-tokens`, `TokenPricingProperties`) est
  **laissé à 6.25** : il est partagé par `QuotaService` (boucle maison) **et** `AtelierSessionService`
  (Managed Agents) via `BilledTokensCalculator`. Le modifier toucherait le décompte des Managed
  Agents (hors périmètre). Aucun test de coût (`TokenPricingPropertiesTest`, `BilledTokensCalculatorTest`)
  n'est donc modifié. Seul le **commentaire** yml change (trace la décision).
- **Navigation / routing** : non concerné (aucune route, aucun écran).

---

## Technique

### Endpoint(s)

Aucun. Relais interne `POST /v1/messages` (comportement fournisseur, non exposé).

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `AnthropicAgentProvider.CACHE_CONTROL` | Modif. constante | Ajout de `"ttl":"1h"` ; Javadoc mise à jour |
| `application.yml` | Modif. commentaire seul | Trace que la boucle pose désormais un cache 1 h et pourquoi le tarif d'écriture reste 6.25 (partagé Managed Agents) |

### Composants Angular

Aucun (pas d'UI).

---

## Plan de test

### Tests unitaires (provider)

- [ ] `AnthropicAgentProviderTest` — le `cache_control` du bloc système porte `ttl == "1h"`.
- [ ] `AnthropicAgentProviderTest` — le `cache_control` du dernier bloc du dernier message porte `ttl == "1h"`.
- [ ] `AnthropicAgentProviderTest` — le nombre de marqueurs reste 2 (≤ 4) — test existant, régression.
- [ ] `AnthropicAgentProviderTest` — décompte cache (`cache_creation`/`cache_read`) + volume inchangés
      (tests existants verts).
- [ ] `AnthropicAgentProviderTest` — retry 429/529 : le corps rejoué reste identique, `ttl:"1h"`
      compris (tests existants verts, plus assertion de forme si utile).

### Tests d'intégration

- [ ] Non applicable (aucun endpoint, aucune donnée persistée) — la boucle et le streaming sont
      couverts par les tests provider/agent existants qui doivent rester verts.

### Isolation utilisateur

- [ ] Non applicable — raison : aucun accès aux données, aucune requête filtrée par `user_id` n'est
      ajoutée ni modifiée ; la résolution de clé (BYOK/plateforme) est inchangée.

---

## Périmètre

### Hors scope (explicite)

- Baisser le plafond par message (reste 4 M) — écarté (contrainte « certitude du meilleur résultat »).
- Rendre l'écartement de contexte plus agressif / baisser `triggerInputTokens` (reste 200 000) — écarté.
- Introduire ou durcir un plafond de troncature des sorties d'outils — écarté.
- Le routage intelligent des modèles (ex-lot B) — abandonné.
- Toute modification du modèle, du raisonnement, du streaming, du retry, ou de la structure du cache.
