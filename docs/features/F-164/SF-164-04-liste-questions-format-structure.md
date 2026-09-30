# Mini-spec — F-164 / SF-164-04 Règle impérative : une liste de questions à réponses proposables passe par le format structuré

## Identifiant

`F-164 / SF-164-04`

## Feature parente

`F-164` — Questions structurées à l'utilisateur (parité Claude Code `AskUserQuestion`)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-164-04-liste-passe-par-structure`

---

> **Note de re-scope (non silencieuse) et d'existant.** Le cadrage F-164 §5 réservait le numéro
> SF-164-04 à « unifier les prompts ad-hoc (Nouveau départ, reprise) ». Le PO a **re-scopé** SF-164-04
> sur la **règle impérative** du format structuré (retour PO explicite). L'unification des prompts
> ad-hoc reste un besoin distinct, renumérotée si ouverte — signalé ici et à l'étape 6, jamais
> remplacé en silence.
>
> **Existant vérifié.** La règle « toute question à réponses proposables, a fortiori une **liste**,
> passe par `demander`, jamais la prose » est **déjà** présente **textuellement** dans
> `ASK_QUESTION_DOCTRINE` (livré SF-164-01, bullet 1). SF-164-04 **durcit** la formulation (cadrage
> F-164 §3 : « une liste de questions rendue en prose est un **défaut**, pas un style ») et pose le
> **témoin de non-régression** dédié à cette règle.

## Objectif

> En une phrase : rendre **impérative et gardée par un test** la doctrine selon laquelle, dès que
> l'assistant a une **liste de questions** (ou des questions à **réponses proposables**), il **DOIT**
> passer par le format structuré `demander` — jamais du texte libre.

---

## Comportement attendu

### Cas nominal

1. L'assistant a plusieurs questions à poser (ou une question dont il peut proposer les réponses).
2. La consigne système lui impose, **sans exception**, de les poser via l'outil `demander` (format
   structuré) : une liste de questions rendue en **prose** est un **défaut**, pas un style.
3. La **prose reste réservée** aux questions **vraiment ouvertes**, sans réponse proposable.

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|----------------------|
| Question unique **vraiment ouverte** (aucune réponse proposable) | Prose autorisée (exception explicite de la règle). |
| Liste de questions rendue en prose | **Défaut** de doctrine : à proscrire — la consigne le dit désormais explicitement. |
| Terminal hébergé / Teams / projet ordinaire | `ASK_QUESTION_DOCTRINE` injectée sur les **deux cibles** (inchangé SF-164-01) : la règle vaut partout où `demander` est offert. |

---

## Critères d'acceptation

- [ ] **CA1** — La consigne porte **textuellement** la règle : toute question à réponses proposables,
  a fortiori une **liste**, passe par `demander`, **JAMAIS** par de la prose — vérifié SANDBOX + RUNNER.
- [ ] **CA2 (durcissement)** — La consigne qualifie explicitement de **défaut** (« pas un style ») une
  liste de questions rendue en prose.
- [ ] **CA3** — L'exception « prose réservée aux questions vraiment ouvertes » **reste présente**.
- [ ] **CA4 (témoin)** — Un test dédié garde cette règle sur les deux cibles.
- [ ] **CA5 (non-régression)** — Le signal manuel + mode unitaire (SF-164-03) et la discipline
  anti-spam restent présents dans la même doctrine.
- [ ] **CA6 (isolation)** — Aucun accès données nouveau ; consigne bâtie sur `requireOwned`
  (`user_id`). Aucun chemin cross-tenant.

---

## Périmètre

### Hors scope (explicite)

- **Unification des prompts ad-hoc** (Nouveau départ F-117, reprise F-39, la porte) — cadrage F-164 §5,
  ex-SF-164-04 : **hors périmètre**, renumérotée si ouverte.
- **Décider-par-défaut + flag + push** : hors périmètre (besoin distinct).
- Schéma de l'outil, pause/reprise (F-84), rendu (SF-164-02) : réutilisés, pas re-spécifiés.

---

## Technique

### Endpoint(s)

Aucun endpoint HTTP nouveau.

### Tables impactées

Aucune. **Aucune** migration Liquibase.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

Aucun (rendu déjà livré SF-164-02).

### Classes backend (modification)

- `AtelierChatService` — **durcissement de `ASK_QUESTION_DOCTRINE`** (bullet règle par défaut) :
  ajout « une liste rendue en prose est un défaut, pas un style ». Prompt/doctrine, littéral **stable**
  (cache F-134). Aucune nouvelle branche d'exécution.

### Tests backend

- `AtelierChatServiceSystemPromptTest` — test dédié de la règle impérative (SANDBOX + RUNNER).

---

## Plan de test

### Tests unitaires (prompt/doctrine)

- [ ] `AtelierChatServiceSystemPromptTest` — SANDBOX : règle impérative présente (« Toute question à
  réponses PROPOSABLES », « JAMAIS par de la prose », « une liste … est un DÉFAUT », exception « prose …
  vraiment ouvertes »).
- [ ] `AtelierChatServiceSystemPromptTest` — RUNNER : mêmes garanties (doctrine sur les deux cibles).

### Tests d'intégration

- [ ] Réutilisés/cités, non redupliqués : `demander` + `/chat/answer` (SF-164-01). Aucun endpoint/outil
  nouveau → pas de test d'intégration HTTP.

### Isolation workspace / user

- [x] Applicable — vérifiée par réutilisation : consigne bâtie sur `requireOwned` (`user_id`) ;
  `demander`/`/chat/answer` isolent déjà `user_id` + propriété workspace. Aucun accès cross-tenant.

---

## Dépendances

### Subfeatures bloquantes

- **SF-164-01** (`ASK_QUESTION_DOCTRINE`) — livrée. Point d'accroche.
- **SF-164-03** (mode unitaire dans la même doctrine) — livrée (PR #1014). SF-164-04 s'y enchaîne (même
  constante), sans conflit (bullets distincts).

### Questions ouvertes impactées

- [ ] Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Préoccupations transversales — analyse d'impact

| Préoccupation | Touchée ? | Composants impactés + vérification |
|--------------|-----------|-------------------------------------|
| **Auth / Principal** | Non | Consigne prompt uniquement. |
| **Contexte tenant** | Non (réutilisé) | Composant qui résout le tenant : `buildSystemPrompt` (via `requireOwned`, `user_id`), **inchangé**. |
| **Plans / limites** | Non | Aucun quota/gate nouveau. |
| **Navigation / routing** | Non | Aucune route front. |
| **Prompt système partagé** | **Oui** | Composant impacté : `AtelierChatService.ASK_QUESTION_DOCTRINE` (injecté sur les DEUX cibles). Durcissement **additif** d'un bullet existant ; aucune doctrine retirée ni réordonnée ; littéral stable (cache F-134). Non-régression gardée par les tests des autres doctrines + SF-164-03. |

---

## Cohérence architecture

- **`ARCHITECTURE_CANONIQUE.md`** : aucune incohérence (prompt/doctrine ; aucune table/endpoint/migration).
- **Gateway-First / Provider-First** : `demander` reste un mécanisme d'interaction ; règle de doctrine,
  aucun « moteur IA ».

## Notes et décisions

- **Re-scope** SF-164-04 (règle impérative, pas unification) : décision PO, tracée ici + étape 6.
- **Increment réel** : la règle existait (SF-164-01) ; SF-164-04 la durcit (« défaut, pas un style »,
  cadrage §3) et pose le témoin de non-régression dédié.
