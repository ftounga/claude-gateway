# Mini-spec — F-148 / SF-148-01 — `stepEffort` low → medium (+ `maxDelegations` 3 → 5)

## Identifiant

`F-148 / SF-148-01`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-148-01-step-effort-medium-max-delegations`

---

## Objectif

> En une phrase : porter le défaut de l'effort des tours de **continuation** à `medium` (au lieu de
> `low`) et le défaut de `maxDelegations` à `5` (au lieu de `3`), les deux restant réglables par
> environnement, sans casser aucun appelant ni constructeur de compatibilité.

---

## Constat / existant

- `AtelierProperties.stepEffort` (défaut `low`, `DEFAULT_STEP_EFFORT`) alimente `stepReasoning`, utilisé
  par `AtelierChatService.reasoningForIteration` (`AtelierChatService.java:1074`) pour les tours
  `iteration > 0`. `low` → sous-raisonnement possible → auto-corrections → tours de rattrapage.
- `AtelierProperties.maxDelegations` (défaut `3`) : plafond d'explorations déléguées par message.
- Le défaut **effectif** vit dans `application.yml` via un repli d'env :
  `step-effort: ${APP_ATELIER_STEP_EFFORT:low}` et `max-delegations: ${APP_ATELIER_MAX_DELEGATIONS:3}`.
  Le défaut du **record** (`DEFAULT_STEP_EFFORT`, littéral `3`) ne s'applique qu'en construction directe
  (tests). Les deux doivent rester cohérents.
- **Constructeurs de compatibilité** (formes F-116/F-118/F-119/F-134/F-141) : à **PRÉSERVER**
  (ajout additif uniquement).

---

## Comportement attendu

### Cas nominal

- Propriété `step-effort` absente / vide / inconnue → défaut `medium`.
- Propriété `max-delegations` absente / négative → défaut `5`.
- `max-delegations = 0` → reste `0` (valeur légitime : retire l'outil de délégation).
- Env `APP_ATELIER_STEP_EFFORT=low|high|…` → valeur honorée (réglable dans les deux sens).
- Env `APP_ATELIER_MAX_DELEGATIONS=N` → valeur honorée.
- Aucun constructeur de compatibilité n'est modifié dans sa signature ; ils appliquent les **nouveaux**
  défauts (medium / 5) via la construction canonique.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `step-effort` = valeur inconnue (`turbo`, blanc) | Repli sur `medium` (le nouveau défaut) |
| `max-delegations` négatif | Repli sur `5` |
| `max-delegations` déraisonnable | Inchangé : pas de plafond dur sur ce champ (comportement existant) |

---

## Critères d'acceptation

- [ ] `DEFAULT_STEP_EFFORT` vaut `medium` ; `stepEffort()` par défaut = `medium` (record + compat).
- [ ] `maxDelegations()` par défaut = `5` (record + compat) ; `0` reste `0` ; négatif → `5`.
- [ ] `application.yml` : `step-effort` retombe sur `medium`, `max-delegations` sur `5`, tous deux
      toujours surchargeables par `APP_ATELIER_STEP_EFFORT` / `APP_ATELIER_MAX_DELEGATIONS`.
- [ ] Tous les **constructeurs de compatibilité** existent encore avec la même signature (compat).
- [ ] Une valeur d'env explicite (`low`, ou un N) est honorée (réglable dans les deux sens).
- [ ] `reasoningForIteration` inchangé structurellement : il consomme le nouveau défaut sans logique
      nouvelle.

---

## Périmètre

### Hors scope (explicite)

- La **mesure** d'impact (itérations/tour, `reusedPercent`) — opérationnelle, post-déploiement.
- `exploreParallelism` : reste `3` (le parallélisme ne rouvre pas le plafond par message).
- `effort` (premier tour) et `exploreEffort` : inchangés.
- Aucun changement d'infra, aucun composant cluster, aucune migration, pas de mise à jour runner.

---

## Contraintes de validation

| Champ | Défaut | Repli | Réglable |
|-------|--------|-------|----------|
| `step-effort` | `medium` | valeur inconnue/vide → `medium` | `APP_ATELIER_STEP_EFFORT` |
| `max-delegations` | `5` | négatif → `5` ; `0` reste `0` | `APP_ATELIER_MAX_DELEGATIONS` |

---

## Technique

### Composants impactés

| Composant | Opération |
|-----------|-----------|
| `AtelierProperties` | `DEFAULT_STEP_EFFORT` = `medium` ; `DEFAULT_MAX_DELEGATIONS` = `5` (nouvelle constante) ; javadoc |
| `application.yml` | replis d'env `step-effort` → medium, `max-delegations` → 5 |
| `AtelierPropertiesTest` | défauts medium / 5, bornes, compat |
| `AtelierChatServiceParallelExploreTest` | plafond par message aligné sur 5 |

### Endpoint(s) / Tables / Migration

Aucun endpoint, aucune table, aucune migration Liquibase.

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierPropertiesTest` — `stepEffort()` défaut = `medium` (record + compat legacy).
- [ ] `AtelierPropertiesTest` — `stepEffort` inconnu/blanc → `medium` ; `low`/`high` honorés.
- [ ] `AtelierPropertiesTest` — `maxDelegations()` défaut = `5` ; `0` reste `0` ; négatif → `5`.
- [ ] `AtelierChatServiceParallelExploreTest` — plafond par message = `5` (6ᵉ exploration limitée).

### Tests d'intégration

- Non applicable : réglages de configuration, aucun endpoint. Le contexte Spring valide déjà la liaison
  de `AtelierProperties` (test de démarrage existant).

### Isolation workspace / tenant

- [x] Non applicable : réglage global de la boucle, aucun accès données. `user_id`/`host_id` non touchés.

---

## Dépendances

### Subfeatures bloquantes

- Aucune.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Cache de prompt** : non impacté. L'effort voyage **dans** la conversation (F-134 / SF-134-05,
  `perMessageEffort` défaut vrai) — changer le niveau n'invalide pas le préfixe. F-118 baisse déjà
  l'effort dès la 2ᵉ étape ; `medium` au lieu de `low` emprunte le même canal.
- **Coût** : légère hausse de tokens assumée (cadrage), à mesurer post-déploiement, pas à supposer.
- Le défaut effectif étant porté par `application.yml`, il est modifié **en plus** des constantes du
  record pour que le comportement livré change réellement, tout en restant réglable par env.
