# Mini-spec — F-118 / SF-118-01 — L'effort s'adapte à l'étape

> Dérivée du cadrage validé `docs/features/F-118/CADRAGE-F-118-effort-adaptatif-et-infra.md`.
> Ce document est produit AVANT tout code (séquence obligatoire CLAUDE.md, étape 1).

---

## Identifiant

`F-118 / SF-118-01`

## Feature parente

`F-118` — Effort adaptatif et réglages d'infrastructure

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-118-01-effort-adaptatif`

---

## Objectif

> En une phrase : dans la boucle maison, ne payer l'effort de raisonnement **normal** qu'au **premier tour** d'une demande et **réduire** l'effort sur les **étapes de continuation** (enchaîner un outil, relire), sans jamais dégrader une vraie tâche de raisonnement.

---

## Comportement attendu

### Cas nominal

La boucle d'agent (`AtelierChatService.runLoop`) appelle le modèle une fois par itération. Aujourd'hui,
chaque itération part avec le **même** réglage `AgentReasoning(true, effort)` — effort `high` par défaut,
posé même pour un `read_file` ou un `ls` trivial (`AtelierChatService:719`).

Après cette subfeature :

1. **Premier tour d'une demande** (`iteration == 0`) : le modèle reçoit l'effort **normal**
   (`app.atelier.effort`, `APP_ATELIER_EFFORT`, défaut `high`). C'est le tour où la réflexion sert :
   cadrer le travail, choisir la trajectoire.
2. **Étapes de continuation** (`iteration > 0`) : le modèle reçoit l'effort **réduit**
   (`app.atelier.step-effort`, `APP_ATELIER_STEP_EFFORT`, défaut `low`). La trajectoire est déjà tracée ;
   enchaîner un outil ou relire un fichier ne demande pas de « réfléchir fort ».
3. Le **raisonnement adaptatif** (`adaptive = true`) reste actif à chaque tour : seul le **niveau
   d'effort** change. Le modèle décide toujours quand et combien raisonner.

### Drapeau de repli

`app.atelier.adaptive-effort` (`APP_ATELIER_ADAPTIVE_EFFORT`, défaut `true`). Passé à `false`, la boucle
rétablit **l'effort normal à chaque étape** (comportement d'avant cette subfeature), sans livraison.
C'est le coupe-circuit : si l'effort réduit dégradait la qualité observée, on revient au comportement
plat par variable d'environnement.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `app.atelier.step-effort` absent / vide / valeur inconnue (`turbo`…) | retombe sur le défaut `low` (même règle que `effort` : une faute de config ne casse pas les tours, ni ne les rend muets) |
| `app.atelier.adaptive-effort` absent | retombe sur `true` (le comportement livré) |
| `app.atelier.step-effort` = même valeur que `effort` | légal : effort constant, sans effet — c'est un réglage, pas une erreur |

Aucun code HTTP : la subfeature est interne à la boucle, sans endpoint ni entrée utilisateur.

---

## Critères d'acceptation

- [ ] Une **demande neuve** (première itération) part avec l'effort **normal** (`effort`, défaut `high`).
- [ ] Une **étape de continuation** (itération ≥ 2, après un appel d'outil) part avec l'effort **réduit**
      (`step-effort`, défaut `low`) quand `adaptive-effort = true`.
- [ ] Avec `adaptive-effort = false`, **toutes** les étapes partent avec l'effort **normal** (repli).
- [ ] `step-effort` inconnu ou vide → défaut `low` ; `adaptive-effort` absent → `true`.
- [ ] Le **raisonnement adaptatif** (`adaptive = true`) reste actif sur tous les tours.
- [ ] Invariants **inchangés** : cache de prompt (l'effort vit dans `output_config`, pas dans les blocs
      `cache_control`), retry 429/529, décompte d'usage, reprise F-84 / précisions SF-84-06, isolation
      `user_id`/`host_id`, transport runner.

---

## Périmètre

### Hors scope (explicite)

- SF-118-02 (réglages d'infra : Hikari, `MaxRAMPercentage`, HPA, ingress) — **appliquée par le PO/orchestrateur**, aucun fichier k8s touché ici.
- Le streaming (F-116), la gestion du contexte (F-117), le chemin Managed Agents (bloc `app.atelier.agent`, effort réglé à part).
- Toute heuristique plus fine que « premier tour vs continuation » (ex. détecter qu'une étape de continuation demande du raisonnement lourd) : la garantie « ne pas dégrader une vraie tâche de raisonnement » repose sur le fait que le **premier tour**, celui qui cadre et planifie, garde l'effort normal.
- Aucune migration, aucune table, aucun endpoint, aucun frontend.

---

## Technique

### Composants impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `AtelierProperties` (record de config) | ajout de 2 réglages | `stepEffort` (String) + `adaptiveEffort` (Boolean), défauts `low` / `true`, validés comme `effort` |
| `AtelierChatService` | logique de sélection | 2 `AgentReasoning` (normal / réduit) + `reasoningForIteration(int)` ; l'appel `AgentTurnRequest` prend l'effort de l'étape |
| `application.yml` | binding env | `step-effort: ${APP_ATELIER_STEP_EFFORT:low}`, `adaptive-effort: ${APP_ATELIER_ADAPTIVE_EFFORT:true}` |
| `AnthropicAgentProvider` | **aucun changement** | mappe déjà `effort` → `output_config` ; varier l'effort par tour n'y touche pas |
| `StubAiAgentProvider` (test) | ajout append-only | capture le `reasoning()` de **chaque** appel (`reasoningSnapshots`) pour prouver l'effort par étape |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — réglages de configuration uniquement, aucun schéma.

### Préoccupations transversales (déclencheurs CLAUDE.md)

- **Auth / Principal** : non touché.
- **Contexte tenant** : non touché — `user_id`/`host_id` inchangés.
- **Plans / limites / coût** : impact **favorable** — l'effort réduit **baisse** le coût des étapes
  simples ; aucun gate ni quota modifié (le décompte d'usage additionne les tokens réellement traités,
  inchangé). Composants « coût » vérifiés : `QuotaService` (non touché), plafond de message
  `maxTurnTokens` (non touché), compteur `recordUsage` (non touché).
- **Navigation / routing** : non touché.

---

## Plan de test

### Tests unitaires — `AtelierPropertiesTest`

- [ ] `step-effort` par défaut = `low` ; `adaptive-effort` par défaut = `true`.
- [ ] `step-effort` inconnu (`turbo`) ou vide → `low`.
- [ ] `step-effort`/`adaptive-effort` configurés sont honorés.

### Tests unitaires — `AtelierChatServiceReasoningTest` (ou dédié)

- [ ] `reasoningForIteration(0)` → effort normal (`high`) ; `reasoningForIteration(1)` → effort réduit (`low`).
- [ ] Avec `adaptive-effort = false`, `reasoningForIteration(0)` **et** `reasoningForIteration(1)` → effort normal.
- [ ] Bout en bout : une demande à **une seule** étape (tour final direct) part en effort **normal**.
- [ ] Bout en bout : une demande à **deux** étapes (un appel d'outil puis le final) → 1er appel effort
      **normal**, 2e appel effort **réduit** (via `StubAiAgentProvider.reasoningSnapshots`).
- [ ] Le raisonnement reste `adaptive = true` sur les deux tours.

### Isolation utilisateur

- [x] Non applicable — raison : aucun accès données ajouté ni modifié ; la boucle reste appelée sous
  `user_id`/`workspace_id` vérifiés par `requireOwned` (inchangé).

---

## Dépendances

### Subfeatures bloquantes

- Aucune. SF-118-02 (infra) est indépendante et livrée à part par le PO.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non concerné).

---

## Notes et décisions

- **D-118-1 — effort réduit par défaut = `low`.** Le cadrage vise « enchaîner un `read_file` ou un `ls` »
  sur les continuations : `low` est le plancher du vocabulaire d'effort partagé avec le chemin Managed
  Agents. Réglable via `APP_ATELIER_STEP_EFFORT` sans livraison si `medium` s'avère plus sûr.
- **D-118-2 — granularité « premier tour vs continuation ».** La garantie « ne jamais dégrader une vraie
  tâche de raisonnement » tient parce que le tour **0**, celui où le modèle cadre et planifie, garde
  l'effort normal ; les tours suivants exécutent une trajectoire déjà décidée. Pas d'heuristique plus fine
  (hors scope) pour rester simple et prévisible.
- **D-118-3 — cache de prompt préservé.** L'effort est envoyé dans `output_config`
  (`AnthropicAgentProvider.applyReasoning`), pas dans les blocs porteurs de `cache_control` : le faire
  varier d'un tour à l'autre ne change pas le préfixe caché.
- **D-118-4 — compatibilité des constructeurs.** `AtelierProperties` a un constructeur canonique
  `@ConstructorBinding` ; les 2 nouveaux champs sont ajoutés en fin de composants, et les constructeurs de
  compatibilité (13 et 14 arguments, utilisés par les tests) délèguent avec les défauts — aucun site
  d'appel existant n'est cassé.
