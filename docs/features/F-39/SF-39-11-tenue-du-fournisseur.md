# Mini-spec — F-39 / SF-39-11 · Le tour tient quand le fournisseur flanche

## Identifiant

`F-39 / SF-39-11`

## Feature parente

`F-39` — L'Atelier comme harnais (lot 6 · Tenue longue)

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-11-tenue-fournisseur`

---

## Objectif

Câbler un **délai HTTP** sur l'appel fournisseur de la boucle maison et **réessayer** les deux
refus temporaires du fournisseur (`429` trop de requêtes, `529` surchargé), pour qu'un incident
d'une poignée de secondes cesse de tuer un tour de dix minutes.

---

## Comportement attendu

### Contexte : ce qui existe aujourd'hui

`AnthropicAgentProvider` construit son `RestClient` avec `restClientBuilder.baseUrl(...)` **et rien
d'autre**. Deux conséquences, toutes deux constatées dans le code :

1. **Aucun délai n'est posé.** `AnthropicProperties.timeout()` (120 s) existe, est appliqué par le
   fournisseur de **chat** (`AnthropicProvider`), et n'est **jamais lu** par le fournisseur d'agent.
   Un appel qui ne répond jamais bloque le thread de la boucle indéfiniment : le budget de tour
   (`TURN_BUDGET_MS`, 10 min) n'est vérifié **qu'entre deux itérations**, jamais pendant un appel en
   vol. Le flux SSE expire, l'écran se fige, et le thread reste pris.
2. **Aucun réessai.** Une `RestClientException` — quelle qu'en soit la cause — est traduite en
   `AIProviderException` et remonte. Un `429` d'une seconde ou un `529` de deux secondes détruit le
   tour entier, y compris quand vingt itérations de travail viennent d'être payées.

### Cas nominal

1. La boucle appelle `nextTurn`. L'appel HTTP porte un délai de connexion **et** de lecture égal à
   `app.ai.anthropic.agent-timeout` (défaut `PT5M`).
2. Réponse `200` → traduction en `AgentTurn`, comportement strictement inchangé.
3. Réponse `429` ou `529` → l'appel est **rejoué à l'identique** après une attente, jusqu'à
   `app.ai.anthropic.agent-max-attempts` tentatives (défaut `3`, soit 2 réessais).
4. L'attente vaut l'en-tête `Retry-After` de la réponse quand il est présent et lisible en secondes,
   sinon un **repli exponentiel** (1 s, 2 s, 4 s…) **avec gigue** dans `[0,5 × d ; d]`.
5. Chaque attente est bornée à `30 s`, et l'attente **cumulée** d'un appel à `60 s`. Au-delà, on
   n'attend plus : l'échec remonte.
6. Un réessai qui aboutit rend le tour normalement — l'appelant ne voit rien, la boucle continue.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `429` / `529`, tentatives restantes | attente puis réessai du même appel | — (transparent) |
| `429` / `529`, tentatives épuisées | `AIProviderException` (message neutre), tour interrompu | 502 côté API (mapping existant) |
| `429` / `529`, budget d'attente cumulé (60 s) atteint | plus d'attente, `AIProviderException` immédiate | 502 |
| `Retry-After` absent, illisible, ou date HTTP | ignoré, repli exponentiel utilisé | — |
| `Retry-After` supérieur à 30 s | ramené à 30 s | — |
| Délai de lecture dépassé (`ResourceAccessException`) | **aucun réessai**, `AIProviderException` | 502 |
| `400` / `401` / `403` / `404` / `500` | **aucun réessai**, comportement inchangé | inchangé |
| Interruption du thread pendant l'attente | le flag d'interruption est reposé, `AIProviderException` | 502 |
| Aucune clé (ni BYOK ni plateforme) | `AIProviderUnavailableException`, **aucun appel HTTP** | 503 |

---

## Critères d'acceptation

- [ ] Le `RestClient` de `AnthropicAgentProvider` porte un délai de connexion et de lecture issu de
      `app.ai.anthropic.agent-timeout` (défaut 5 min), distinct de `timeout` (chat, 120 s).
- [ ] Un `429` suivi d'un `200` rend un `AgentTurn` normal ; le fournisseur a été appelé 2 fois.
- [ ] Un `529` suivi d'un `200` rend un `AgentTurn` normal.
- [ ] Trois `429` d'affilée (défaut 3 tentatives) lèvent `AIProviderException` ; le fournisseur a été
      appelé exactement 3 fois.
- [ ] Un `400` n'est **jamais** rejoué : le fournisseur est appelé exactement 1 fois.
- [ ] Un délai de lecture dépassé n'est **jamais** rejoué : 1 appel, `AIProviderException`.
- [ ] `Retry-After: 2` est respecté (attente de 2 000 ms) ; `Retry-After: 999` est ramené à 30 000 ms ;
      `Retry-After: bientôt` est ignoré au profit du repli exponentiel.
- [ ] Le repli exponentiel reste dans `[0,5 × 2^(n-1) s ; 2^(n-1) s]` pour la n-ième tentative.
- [ ] L'attente cumulée d'un appel ne dépasse jamais 60 s.
- [ ] Aucune clé API — plateforme ou BYOK — n'apparaît dans un log, y compris dans les journaux de
      réessai.
- [ ] `agent-max-attempts` hors bornes (`< 1`, `> 5`) retombe sur une valeur saine sans empêcher le
      démarrage.
- [ ] Le corps de la requête rejouée est **identique** à celui de la tentative initiale (mêmes
      marqueurs de cache, même raisonnement) : un réessai n'est pas un nouvel appel.

---

## Périmètre

### Hors scope (explicite)

- Le fournisseur de **chat** (`AnthropicProvider`, F-02) : streamé, user-facing, arbitrages
  différents. Reste sans réessai — tracé en risque résiduel.
- Le chemin **Managed Agents** (`AnthropicManagedAgentProvider`) : sessions serveur, pas d'appel
  `/v1/messages` en boucle.
- Le passage de la boucle en **streamé** : c'est ce qui permettrait de monter `effort` à `xhigh` et
  de dépasser 16 384 tokens de sortie. Hors lot 6.
- Toute remontée **à l'écran** d'un réessai en cours (ligne vivante) : aucune UI dans cette
  subfeature.
- Le réessai des `500`/`502`/`503` : le cadrage dit `429/529`, et un `500` sur une création de
  message est ambigu (l'appel a-t-il été traité ?). Voir « Notes et décisions ».

---

## Valeurs initiales

Aucune entité créée.

---

## Contraintes de validation

| Champ | Obligatoire | Bornes | Format / Valeurs autorisées | Normalisation |
|-------|-------------|--------|-----------------------------|---------------|
| `app.ai.anthropic.agent-timeout` | Non | > 0 | `Duration` ISO-8601 | `null`/`0`/négatif ⇒ `PT5M` |
| `app.ai.anthropic.agent-max-attempts` | Non | 1 à 5 | entier | `null` ou hors bornes ⇒ `3` |

Constantes **nommées, non configurables** (même règle qu'en SF-39-04 : une constante nommée vaut
mieux qu'un énième réglage) :

| Constante | Valeur | Raison |
|-----------|--------|--------|
| `INITIAL_DELAY_MS` | 1 000 | premier repli |
| `MAX_DELAY_MS` | 30 000 | plafond d'une attente unitaire, `Retry-After` compris |
| `MAX_TOTAL_WAIT_MS` | 60 000 | plafond de l'attente cumulée d'un appel |

---

## Technique

### Endpoint(s)

Aucun endpoint créé ni modifié.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun. Subfeature strictement backend, sans effet visible à l'écran hors disparition d'échecs.

### Classes impactées

| Classe | Opération |
|--------|-----------|
| `fr.claudegateway.ai.AnthropicProperties` | + `agentTimeout`, + `agentMaxAttempts` (+ défauts) |
| `fr.claudegateway.agent.AgentRetryPolicy` | **nouveau** — logique pure : statut rejouable, délai |
| `fr.claudegateway.agent.AnthropicAgentProvider` | fabrique HTTP avec délais + boucle de réessai |
| `backend/src/main/resources/application.yml` | 2 clés de configuration |

---

## Plan de test

### Tests unitaires

- [ ] `AgentRetryPolicy` — `429` et `529` rejouables ; `400`, `401`, `500`, `503` non rejouables.
- [ ] `AgentRetryPolicy` — `Retry-After: 2` ⇒ 2 000 ms ; `Retry-After: 999` ⇒ 30 000 ms ;
      `Retry-After` illisible ⇒ repli exponentiel.
- [ ] `AgentRetryPolicy` — le repli de la n-ième tentative reste dans `[0,5 × 2^(n-1) s ; 2^(n-1) s]`.
- [ ] `AgentRetryPolicy` — au-delà de 60 s d'attente cumulée, plus aucun délai n'est accordé.
- [ ] `AnthropicPropertiesTest` — `agent-timeout` / `agent-max-attempts` : défauts et bornes.

### Tests d'intégration (serveur HTTP simulé)

- [ ] `429` puis `200` ⇒ `AgentTurn` rendu, 2 appels.
- [ ] `529` puis `200` ⇒ `AgentTurn` rendu, 2 appels.
- [ ] `429` × 3 ⇒ `AIProviderException`, 3 appels.
- [ ] `400` ⇒ `AIProviderException`, 1 appel.
- [ ] Le corps de la 2ᵉ tentative est identique à celui de la 1ʳᵉ.

### Isolation utilisateur

- [x] Non applicable — aucun accès aux données. La clé BYOK reste passée par appel et n'est ni
      mémorisée ni journalisée ; le réessai réutilise l'appel d'origine, donc la même clé, sans la
      stocker.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-10` — statut : `done` (le raisonnement doit être rejoué identique par le réessai).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

**D-L6-1 — Un délai propre à l'agent, pas celui du chat.** `timeout` (120 s) est calibré pour un
appel de chat streamé. La boucle appelle en **non-streamé**, sur `claude-opus-5`, en raisonnement
adaptatif à effort `high`, avec jusqu'à 16 384 tokens de sortie : 120 s couperait des appels
parfaitement légitimes, et un appel coupé côté client est facturé côté fournisseur sans qu'on en
retire rien. `PT5M` laisse la place à un appel long tout en garantissant que deux appels bloqués
n'épuisent pas le budget de tour de 10 min à eux seuls.

**D-L6-2 — Réessai sur `429`/`529`, et sur eux seuls.** Ce sont les deux statuts que le fournisseur
déclare temporaires. Un `500` est ambigu : la création de message a peut-être été traitée, et la
rejouer pourrait faire exécuter deux fois la même série d'outils sur la machine de l'utilisateur.
Le coût d'un faux positif ici n'est pas un token perdu, c'est un `rm` joué deux fois. On s'abstient.

**D-L6-3 — Le dépassement de délai n'est pas rejoué.** Un appel coupé à 5 min a déjà consommé la
moitié du budget de tour ; le rejouer échangerait un échec lisible contre un tour qui meurt au
budget, ce que l'utilisateur lit comme une panne. L'échec remonte tout de suite.

**D-L6-4 — Attente cumulée bornée à 60 s.** Le budget de tour est vérifié **entre** les itérations,
jamais pendant un appel : rien ne rattraperait une attente de plusieurs minutes décidée par un
`Retry-After` généreux. La borne rend l'attente lisible et laisse la boucle rendre la main.

**D-L6-5 — Gigue sur le repli exponentiel.** Plusieurs utilisateurs plafonnés en même temps
repartiraient à la même seconde sans elle, et se re-plafonneraient ensemble. La gigue est bornée à
`[0,5 × d ; d]` pour rester testable.

**D-L6-6 — Le réessai rejoue l'appel, il ne le reconstruit pas.** Le corps est calculé une fois,
avant la première tentative : marqueurs `cache_control` et blocs de raisonnement signés sont donc
strictement identiques d'une tentative à l'autre. Reconstruire ferait glisser le marqueur de cache
et transformerait un réessai en écriture de cache payée deux fois.
