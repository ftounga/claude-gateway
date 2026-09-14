# Mini-spec — [F-118 / SF-118-03] Le budget de temps par tour est configurable

---

## Identifiant

`F-118 / SF-118-03`

## Feature parente

`F-118` — Effort adaptatif et réglages d'infrastructure

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-118-03-budget-de-temps-configurable`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre configurable, sans livraison, le budget de temps d'un message de la boucle maison
(`AtelierChatService`), aujourd'hui figé à 10 min par la constante `TURN_BUDGET_MS`, pour le PO
puisse le porter à 60 min par variable d'environnement.

---

## Comportement attendu

### Cas nominal

> Description précise du flux principal (entrée → traitement → sortie).

1. Une propriété `turnBudget` (type `Duration`) est ajoutée à `AtelierProperties`, sur le modèle exact
   de `maxTurnTokens` : liée à la clé `app.atelier.turn-budget`, placeholder d'environnement
   `APP_ATELIER_TURN_BUDGET`, **défaut `PT10M`** (10 min, identique au comportement livré).
2. Le constructeur compact normalise la valeur comme les autres réglages : une valeur absente, nulle,
   nulle-durée ou négative retombe sur le défaut `PT10M` ; une valeur au-delà d'un **plafond dur
   raisonnable (`PT2H`, 2 h)** est ramenée au plafond — même règle que `maxTurnTokens`.
3. `AtelierChatService` lit `atelierProperties.turnBudget().toMillis()` dans son constructeur (comme
   il lit déjà `maxTurnTokens`) et l'utilise pour calculer `deadline = startedAt + turnBudgetMs`. La
   constante `TURN_BUDGET_MS` reste comme **valeur de repli documentaire** (10 min) mais la valeur
   effective vient de la configuration.
4. Comportement inchangé au-delà : quand `System.currentTimeMillis() >= deadline`, la boucle rend la
   main et répond `BUDGET_REACHED_REPLY` (« Le temps imparti à ce message est écoulé… »).
5. **Mise à 60 min en production** : `APP_ATELIER_TURN_BUDGET=PT60M` côté déploiement (configmap prod,
   **hors périmètre de cette subfeature** — géré par le PO/orchestrateur). Aucune valeur de code ne
   change : le défaut du code reste `PT10M`.

### Cas d'erreur

> Lister tous les cas d'erreur identifiés.

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `app.atelier.turn-budget` absent | Défaut `PT10M` appliqué (comportement livré) | n/a (démarrage) |
| Valeur nulle / durée zéro / négative | Retombe sur le défaut `PT10M` (ne coupe pas les tours à 0, ne les rend pas infinis) | n/a |
| Valeur supérieure au plafond `PT2H` | Ramenée à `PT2H` (borne lisible, comme `maxTurnTokens`) | n/a |
| Budget effectif écoulé pendant un tour | La boucle rend la main, réponse `BUDGET_REACHED_REPLY` | 200 (réponse normale) |

---

## Critères d'acceptation

> Chaque critère est vérifiable. Pas d'ambiguïté.

- [ ] `AtelierProperties.turnBudget()` vaut `Duration.ofMinutes(10)` quand la clé est absente/nulle
- [ ] Une valeur configurée valide (ex. `PT60M`) est lue telle quelle par `turnBudget()`
- [ ] Une valeur nulle-durée ou négative retombe sur `PT10M`
- [ ] Une valeur au-delà du plafond `PT2H` est ramenée à `PT2H`
- [ ] `AtelierChatService` calcule sa deadline à partir de la propriété : un budget très court fait
      sortir la boucle sur le budget de temps (`BUDGET_REACHED_REPLY`, `budgetReached() == true`)
- [ ] Le défaut du **code** reste 10 min (aucun changement de comportement sans configuration)
- [ ] `application.yml` porte `turn-budget: ${APP_ATELIER_TURN_BUDGET:PT10M}` (convention ISO-8601
      identique aux autres `Duration` du yml, ex. `progress-interval: PT5S`)
- [ ] Les classes backend touchées et le contexte Spring compilent et sont verts

---

## Périmètre

### Hors scope (explicite)

- Changer la valeur par défaut du code (reste `PT10M`) — la mise à 60 min est faite côté déploiement.
- Toute modification de manifeste k8s / configmap / infra (relève du PO/orchestrateur, SF-118-02).
- Rendre le budget configurable par utilisateur ou par plan (c'est un réglage global d'exploitation).
- Modifier la sémantique de la deadline (frontière SSE, propagation aux outils/runner) : inchangée.
- Frontend : aucun (réglage d'exploitation, aucun écran).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `app.atelier.turn-budget` | `PT10M` | Défaut du code ; identique au comportement livré |
| Plafond dur | `PT2H` | Borne lisible ; au-delà, `maxIterations` et le budget auraient tranché |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `turnBudget` | Non | `Duration` ISO-8601 (ex. `PT10M`, `PT60M`) | absent/null/≤0 → `PT10M` ; > `PT2H` → `PT2H` |

Notes :
- Même stratégie de repli que `maxTurnTokens` : une faute de configuration ne doit ni couper les
  tours à zéro, ni les rendre illimités.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — réglage de configuration uniquement, aucun schéma touché.

### Composants Angular (si applicable)

Aucun — réglage d'exploitation sans écran.

### Fichiers touchés

- `backend/.../atelier/AtelierProperties.java` — nouveau champ `Duration turnBudget` + constantes
  `DEFAULT_TURN_BUDGET` / `TURN_BUDGET_CEILING` + normalisation + constructeur de compatibilité.
- `backend/.../atelier/AtelierChatService.java` — champ `turnBudgetMs` lu depuis les propriétés,
  utilisé pour la deadline ; `TURN_BUDGET_MS` conservée comme repli documentaire.
- `backend/src/main/resources/application.yml` — clé `app.atelier.turn-budget`.
- `backend/.../atelier/AtelierPropertiesTest.java` — défaut / surcharge / plafond / valeur invalide.
- `backend/.../atelier/AtelierChatServiceBudgetTest.java` — la deadline vient bien de la propriété.

---

## Préoccupations transversales

Aucun déclencheur coché : ni Auth/Principal, ni contexte tenant, ni Plans/limites, ni
Navigation/routing. L'isolation `user_id`/`workspace_id` de la boucle est inchangée (aucun accès
donnée nouveau). Le réglage est global et ne touche ni quota ni gate.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierPropertiesTest` — défaut : `turnBudget()` == `PT10M` quand absent/null
- [ ] `AtelierPropertiesTest` — surcharge : `PT60M` lu tel quel
- [ ] `AtelierPropertiesTest` — plafond : `PT10H` ramené à `PT2H`
- [ ] `AtelierPropertiesTest` — valeur nulle-durée / négative → `PT10M`
- [ ] `AtelierChatServiceBudgetTest` — un budget très court fait sortir la boucle sur le budget de
      temps (`BUDGET_REACHED_REPLY`), prouvant que la deadline vient de la propriété

### Tests d'intégration

- [ ] Contexte Spring vert (liaison de la propriété, chargement `application.yml`) via la suite
      d'intégration atelier existante.

### Isolation workspace

- [x] Non applicable — raison : aucune donnée nouvelle lue/écrite ; réglage global d'exploitation.

---

## Dépendances

### Subfeatures bloquantes

- `SF-118-01` — statut : Done (a introduit le motif `stepEffort`/`adaptiveEffort` réutilisé ici).

### Questions ouvertes impactées

- [ ] Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **D-118-3-1** : le budget reste exprimé en `Duration` côté configuration (cohérent avec les autres
  délais du yml : `progress-interval PT5S`, `session-timeout PT10M`…) et converti en millisecondes une
  seule fois, dans le constructeur du service, pour ne pas toucher l'arithmétique de la deadline.
- **D-118-3-2** : plafond dur `PT2H` — au-delà, `maxIterations` (≤ 100) et la durée de vie du flux SSE
  auraient tranché de toute façon ; même raisonnement que `MAX_TURN_TOKENS_CEILING`.
- **D-118-3-3** : le défaut du code reste 10 min ; la mise à 60 min est portée par
  `APP_ATELIER_TURN_BUDGET=PT60M` dans le configmap prod (hors périmètre de cet agent).
