# Mini-spec — F-150 / SF-150-04 — Modèle `task` (`AiAgentProvider`) + doctrine `task` vs `explore` vs `bash`

## Identifiant

`F-150 / SF-150-04`

## Feature parente

`F-150` — Sous-agent `task` capable d'agir, isolé par git-worktree sur le poste

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-150-04-task-model-doctrine`

---

## Objectif

Rendre le **modèle de la sous-boucle `task`** configurable (`app.atelier.task-model`, repli sur le modèle principal) via `AiAgentProvider` (Provider Independence), et ajouter une **doctrine littérale stable** « quand `task` vs `explore` vs `bash` » dans la description d'outil (cache F-134 préservé). **Prompt/config uniquement.**

---

## Comportement attendu

### Cas nominal

1. `app.atelier.task-model` configuré → la sous-boucle `task` tourne sur ce modèle ; la boucle principale reste sur `app.atelier.model`.
2. `app.atelier.task-model` absent/vide → **repli** : `task` suit le modèle principal.
3. Le modèle voyage comme **chaîne** via `AiAgentProvider` (Provider Independence) — aucun couplage direct à un fournisseur.
4. La description de l'outil `task` porte la **doctrine littérale stable** : `task` (écrire/exécuter une sous-tâche isolée) vs `explore` (lire) vs `bash`/édition directe (agir dans la boucle principale). Le littéral est **identique à chaque tour** (aucune volatilité de préfixe → cache F-134 intact).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `task-model` non valide au catalogue | non validé contre le `ModelCatalog` (comme `model`/`explore-model`) : c'est le harnais qui exécute, pas le chat |
| `task-model` vide | repli sur le modèle principal (aucune erreur) |

---

## Critères d'acceptation

- [ ] `task-model` configuré → sous-boucle `task` sur ce modèle, boucle principale sur le modèle principal.
- [ ] `task-model` absent → sous-boucle `task` sur le modèle principal (repli sûr).
- [ ] Le modèle voyage comme chaîne via `AiAgentProvider` (aucun modèle en dur).
- [ ] Doctrine `task` vs `explore` vs `bash` présente dans la description d'outil, **littérale et stable** (cache F-134 préservé, boucle principale inchangée).
- [ ] Rétro-compatibilité : les appelants d'`AtelierProperties` sans `task-model` compilent (constructeur de compatibilité).

---

## Périmètre

### Hors scope (explicite)

- Restitution branche/diff + nettoyage + plafonds (SF-150-05).
- Rendu terminal (SF-150-06).

---

## Technique

### Composants impactés

- `AtelierProperties` : 25ᵉ composant `taskModel` + constructeur de compatibilité (sans `taskModel` ⇒ `null`).
- `application.yml` : `app.atelier.task-model` (`${APP_ATELIER_TASK_MODEL:}`, vide ⇒ modèle principal).
- `AtelierChatService` : champ `taskModel`, résolu dans `task()` (`subModel = taskModel blank ? model : taskModel`) ; doctrine dans la description de l'outil `task`.

Aucune table. Migration Liquibase : **non applicable**.

### Préoccupation transversale — Plans / limites

Nouveau réglage `task-model` (config, pas de quota). Pas de nouveau gate ni changement de plafond. Composant : `AtelierProperties`, `application.yml`, `AtelierChatService`.

---

## Plan de test

### Tests unitaires / intégration (backend)

- [ ] `task-model` configuré → la sous-boucle `task` reçoit ce modèle (provider enregistreur), la boucle principale garde le modèle principal.
- [ ] `task-model` absent → repli : la sous-boucle `task` reçoit le modèle principal.
- [ ] `AtelierPropertiesTest` : `taskModel` lu, constructeur de compatibilité (sans `taskModel`) rend `null`.

### Isolation workspace

- [ ] Non applicable (config/prompt ; aucun accès données nouveau).

---

## Dépendances

### Subfeatures bloquantes

- `SF-150-02` — **Done** (la sous-boucle `task` et sa résolution de modèle).

---

## Notes et décisions

- **Provider Independence** : `task-model` est une **chaîne** remise à `AiAgentProvider`, jamais un modèle en dur.
- **Cache F-134** : la doctrine est un **littéral stable** de la description d'outil (patron SF-149-02) ; la consigne système de la boucle principale n'est pas modifiée.
