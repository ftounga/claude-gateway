# Mini-spec — F-149 / SF-149-03 — Politique de modèle (explore→Sonnet)

## Identifiant

`F-149 / SF-149-03`

## Feature parente

`F-149` — Déléguer l'audit lourd, politique de modèle, réparer l'activation

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-149-03-politique-modele-explore`

---

## Objectif

Faire tourner la **sous-boucle `explore`** (exploration/revue de routine) sur **Sonnet**
(`claude-sonnet-5`) tandis que la **boucle principale** reste sur **Opus** — c'est là que se concentre
la lecture lourde déléguée (SF-149-02), donc le gain de coût — via un réglage
`app.atelier.explore-model`, **repli sûr sur le modèle principal** si non configuré/indisponible, sans
jamais coupler le code métier à un modèle en dur (Provider Independence).

## Décision produit

Déclencheur retenu = **« `explore` → Sonnet, principal → Opus »** (pas un routage par difficulté).
C'est simple, sûr, et mesurable ensuite : la lecture lourde déléguée est le poste de coût, et Sonnet y
coûte ~5× moins pour de la lecture/synthèse. Un routage par phase/difficulté plus fin reste possible
plus tard, une fois l'effet coût/qualité mesuré.

---

## Comportement attendu

### Cas nominal

À chaque délégation `explore`, la sous-boucle est exécutée avec le modèle configuré par
`app.atelier.explore-model` (défaut de production **`claude-sonnet-5`**). La boucle principale continue
d'utiliser `app.atelier.model` (défaut `claude-opus-5`). Le modèle voyage comme une **chaîne** via
`AtelierExploration.run` → `AgentTurnRequest` → `AiAgentProvider` (aucun couplage direct à un modèle).

### Cas d'erreur / de bord

| Situation | Comportement attendu |
|-----------|----------------------|
| `explore-model` **non configuré** (null/blank) | **Repli** : la sous-boucle utilise le modèle principal (comportement d'avant SF-149-03, à l'octet près) |
| `explore-model` configuré | la sous-boucle utilise ce modèle ; la boucle principale reste sur le modèle principal |
| Boucle principale | **jamais** affectée : elle garde `app.atelier.model` |

---

## Critères d'acceptation

- [ ] Quand `explore-model` est configuré, la **sous-boucle** `explore` appelle le fournisseur avec ce
      modèle (vérifié sur `AgentTurnRequest.model()` de l'appel sous-boucle).
- [ ] Quand `explore-model` est **null/blank**, la sous-boucle **replie** sur le modèle principal.
- [ ] La **boucle principale** utilise toujours `app.atelier.model` (non-régression), dans les deux
      cas.
- [ ] Le routage passe par `AiAgentProvider` (chaîne `model`), **aucun couplage direct** à un modèle
      (Provider Independence).
- [ ] Le défaut de **production** est `claude-sonnet-5` (`application.yml`), avec repli sûr.

---

## Périmètre

### Hors scope (explicite)

- Routage par **difficulté**/par phase plus fin (mesuré plus tard).
- Choix de modèle **par poste**/par utilisateur.
- Aucune UI, aucune table, aucun endpoint, aucune migration.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|-------|-------------|------------------|---------------|
| `app.atelier.explore-model` | Non | chaîne libre (id de modèle) | null/blank → repli sur le modèle principal ; **non** validé contre `ModelCatalog` (comme `app.atelier.model` : le catalogue dit ce que le chat propose, pas ce que le harnais exécute) |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

Aucun.

### Fichiers touchés

- `AtelierProperties` — nouveau composant `exploreModel` (24ᵉ), défaut porté par `application.yml`
  (repli null = suivre le modèle principal), constante `DEFAULT_EXPLORE_MODEL`.
- `AtelierChatService` — champ `exploreModel` ; `explore()` passe `exploreModel` (repli sur `model`
  principal si null/blank) à `AtelierExploration.run`.
- `application.yml` — `explore-model: ${APP_ATELIER_EXPLORE_MODEL:claude-sonnet-5}`.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceExploreModelTest` — configuré : `explore-model = claude-sonnet-5` → l'appel
      **sous-boucle** porte `claude-sonnet-5`, l'appel **principal** porte `claude-opus-5`.
- [ ] `AtelierChatServiceExploreModelTest` — repli : `explore-model` null → l'appel sous-boucle porte
      le modèle principal (`claude-opus-5`).
- [ ] `AtelierPropertiesTest` — `exploreModel` : valeur portée telle quelle ; null/blank reste null
      (repli côté service).
- [ ] Non-régression : `AtelierChatServiceParallelExploreTest`, `AtelierChatServiceReasoningTest`
      (la sous-boucle et la boucle restent fonctionnelles).

### Tests d'intégration

- [ ] `ClaudeGatewayBackendApplicationTests` (contexte Spring + binding de config) reste vert.

### Isolation

- [ ] Non applicable — aucune donnée accédée (réglage de modèle).

---

## Préoccupations transversales

- **Provider Independence** (déclencheur du cadrage) : le modèle est une **chaîne** transportée par
  `AtelierExploration.run(provider, model, …)` → `AgentTurnRequest(model, …)` → `AiAgentProvider`. Aucun
  type/appel Anthropic n'apparaît dans le code métier. Composant impacté unique : `AtelierChatService`
  (champ + passage du modèle) ; le contrat `AiAgentProvider` est inchangé.
- **Plans/limites** : **non touché** — aucun nouveau gate, aucun quota modifié. La consommation de la
  sous-boucle reste comptée dans le tour (F-39, D4), inchangée.

---

## Dépendances

### Subfeatures bloquantes

`SF-149-01` (done), `SF-149-02` (done).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Défaut en `application.yml` (et non dans le record)** : le record laisse `exploreModel` à `null`
  (= suivre le modèle principal) pour que **tous les tests existants** (qui construisent
  `AtelierProperties` via les constructeurs de compatibilité) restent **byte-identiques** ; le défaut
  produit `claude-sonnet-5` vit dans `application.yml`, exactement comme `explore-effort`. « Défaut
  Sonnet » (config) + « repli sûr sur le principal si non configuré » (null) sont ainsi tous deux
  vrais et **testables**.
- **Cache F-134 préservé** : le modèle de la sous-boucle n'est pas dans le préfixe de la boucle
  principale ; changer le modèle d'`explore` ne touche pas le cache du tour principal.
- **F-119 intacte** : la sous-boucle garde son raisonnement adaptatif (`explore-effort`) ; seul le
  modèle change.
