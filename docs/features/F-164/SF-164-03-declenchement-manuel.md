# Mini-spec — F-164 / SF-164-03 Déclenchement manuel des questions structurées (dont mode unitaire)

## Identifiant

`F-164 / SF-164-03`

## Feature parente

`F-164` — Questions structurées à l'utilisateur (parité Claude Code `AskUserQuestion`)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-164-03-declenchement-manuel`

---

> **Note de re-scope (non silencieuse) et d'existant.** Le cadrage F-164 §5 réservait le numéro
> SF-164-03 à « décider-par-défaut + flag + push ». Le PO a **re-scopé** SF-164-03 sur le
> **déclenchement manuel** (retour PO explicite). La politique décider-par-défaut reste un besoin
> distinct, renumérotée si ouverte — signalé ici et à l'étape 6 (PRODUCT_SPEC), jamais remplacé en
> silence (CLAUDE.md § « Quand tu proposes une modification »).
>
> **Existant vérifié (mémoire « vérifier l'existant avant de cadrer »).** Le **signal de déclenchement
> manuel** est **déjà** porté par `ASK_QUESTION_DOCTRINE` (livré SF-164-01, PR #999, bullet 2 :
> « pose-moi les questions… ⇒ utiliser demander »). SF-164-03 ne le réinvente pas : elle **complète** le
> seul manque réel — l'**honneur d'une demande UNITAIRE explicite** (poser les questions **une par
> une**) — et pose le **témoin de non-régression** qui manquait (aucun test ne gardait cette doctrine).

## Objectif

> En une phrase : quand l'utilisateur demande explicitement à l'assistant de lui poser des questions —
> **y compris une par une (mode unitaire)** — l'assistant reconnaît ce signal et passe par l'outil
> structuré `demander` (F-164), en respectant à la lettre l'unitaire quand il est demandé.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur écrit, en substance, « pose-moi les questions que tu veux pour bien comprendre tel
   sujet ». L'assistant **reconnaît le signal** (consigne système, pas de bouton séparé) et utilise
   l'outil `demander` — au besoin **plusieurs fois** dans le tour (demander → répondre → raisonner →
   redemander), comme déjà porté par SF-164-01.
2. **Nouveau (mode unitaire).** Si l'utilisateur demande de les poser **une par une** (unitaire),
   l'assistant **respecte l'unitaire à la lettre** : **une seule question par appel** `demander`, il
   attend la réponse, raisonne dessus, puis pose la suivante — il n'empile pas un lot quand l'unitaire
   est explicitement demandé.
3. Hors demande unitaire, l'assistant garde sa latitude (une question ou un petit lot de 1 à 4, comme
   SF-164-01) — l'unitaire n'est **imposé** que lorsqu'il est **demandé**.

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|----------------------|
| Aucun signal manuel dans le message | Comportement inchangé : l'assistant n'appelle `demander` que s'il est vraiment bloqué (discipline anti-spam SF-164-01). |
| Demande manuelle mais aucun choix proposable (question vraiment ouverte) | Prose autorisée (règle par défaut F-164 : la prose reste réservée aux questions ouvertes). |
| Terminal hébergé / Teams / projet ordinaire | Doctrine `ASK_QUESTION_DOCTRINE` injectée sur les **deux cibles** (inchangé SF-164-01) : le signal manuel vaut partout où l'outil `demander` est offert. |

---

## Critères d'acceptation

- [ ] **CA1** — La consigne système porte, **textuellement**, le signal de déclenchement manuel
  (« pose-moi les questions… » ⇒ utiliser `demander`) — vérifié sur cible SANDBOX **et** RUNNER.
- [ ] **CA2 (nouveau)** — La consigne impose de **respecter une demande unitaire explicite** : une
  seule question par appel `demander`, attendre la réponse, raisonner, poser la suivante.
- [ ] **CA3** — La règle par défaut obligatoire (liste/réponses proposables → `demander`, jamais la
  prose) et la discipline anti-spam **restent présentes** (non-régression de `ASK_QUESTION_DOCTRINE`).
- [ ] **CA4 (témoin)** — Un test garde désormais la présence de cette doctrine sur les deux cibles
  (aucun test ne la gardait avant SF-164-03).
- [ ] **CA5 (isolation)** — Aucun nouvel accès aux données ; la consigne est construite sur le
  workspace possédé (`requireOwned`, `user_id`). Aucun chemin cross-tenant introduit.

---

## Périmètre

### Hors scope (explicite)

- **Politique décider-par-défaut + flag + push** (cadrage F-164 §5, ex-SF-164-03) : **hors périmètre**,
  besoin distinct, renumérotée si ouverte.
- **Unification des prompts ad-hoc** (F-164 §5) : hors périmètre.
- Le **schéma de l'outil**, la **pause/reprise** (F-84), le **rendu terminal** (SF-164-02) :
  **réutilisés**, pas re-spécifiés.
- Aucun bouton d'UI « poser une question » : le déclenchement manuel passe par le langage naturel + la
  consigne (cadrage F-164 §8).

---

## Technique

### Endpoint(s)

Aucun endpoint HTTP nouveau. `demander` réutilise `POST /workspaces/{id}/chat/answer` (SF-164-01) inchangé.

### Tables impactées

Aucune. **Aucune** migration Liquibase.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

Aucun. Le rendu de la carte de question est déjà livré (SF-164-02).

### Classes backend (modification)

- `AtelierChatService` — **évolution de `ASK_QUESTION_DOCTRINE`** : ajout du bullet « mode unitaire »
  (respecter une demande unitaire explicite). Prompt/doctrine, littéral **stable** (cache F-134
  préservé). Aucune nouvelle branche d'exécution d'outil.

### Tests backend

- `AtelierChatServiceSystemPromptTest` — nouveaux tests de présence de la doctrine (SANDBOX + RUNNER).

---

## Plan de test

### Tests unitaires (prompt/doctrine)

- [ ] `AtelierChatServiceSystemPromptTest` — SANDBOX : la doctrine `demander` est présente (signal
  manuel « pose-moi les questions », mode unitaire « une par une » / « une seule question par appel »,
  règle par défaut « JAMAIS par de la prose », anti-spam).
- [ ] `AtelierChatServiceSystemPromptTest` — RUNNER : mêmes garanties (doctrine sur les deux cibles).

### Tests d'intégration

- [ ] Réutilisés/cités, non redupliqués : `demander` + `/chat/answer` (SF-164-01), pauses répétées.
  SF-164-03 n'ajoute **aucun** endpoint ni outil → pas de nouveau test d'intégration HTTP.

### Isolation workspace / user

- [x] Applicable — vérifiée par réutilisation : la consigne est bâtie sur `requireOwned`
  (`user_id` + workspace possédé) ; `demander`/`/chat/answer` isolent déjà `user_id` + propriété
  workspace (SF-164-01). Aucun accès cross-tenant introduit (doctrine seule).

---

## Dépendances

### Subfeatures bloquantes

- **SF-164-01** (outil `demander` + `ASK_QUESTION_DOCTRINE`) — **livrée** (PR #999). Point d'accroche.
- **SF-164-02** (rendu terminal) — **livrée** (PR #1002). Réutilisée.

### Questions ouvertes impactées

- [ ] Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Préoccupations transversales — analyse d'impact

| Préoccupation | Touchée ? | Composants impactés + vérification |
|--------------|-----------|-------------------------------------|
| **Auth / Principal** | Non | Aucun endpoint touché ; consigne prompt uniquement. |
| **Contexte tenant** | Non (réutilisé) | Composant qui résout le tenant ici : `buildSystemPrompt` (via `requireOwned`, `user_id`), **inchangé**. Aucun nouveau chemin de résolution. |
| **Plans / limites** | Non | `demander` ne consomme aucun quota/gate nouveau. |
| **Navigation / routing** | Non | Aucune route front (rendu déjà livré SF-164-02). |
| **Prompt système partagé** | **Oui** | Composant impacté : `AtelierChatService.ASK_QUESTION_DOCTRINE` (injecté par `buildSystemPrompt` sur les DEUX cibles). Ajout **additif** d'un bullet ; aucune doctrine existante retirée ni réordonnée ; littéral stable (cache F-134). Non-régression gardée par les tests de présence des autres doctrines. |

---

## Cohérence architecture

- **`ARCHITECTURE_CANONIQUE.md`** : aucune incohérence. Prompt/doctrine uniquement ; aucune table,
  endpoint ou migration.
- **Gateway-First / Provider-First** : `demander` reste un mécanisme d'**interaction** ; SF-164-03
  n'ajoute aucune logique de « moteur IA ». La décision de poser appartient au modèle.

## Notes et décisions

- **Ré-scope de SF-164-03** (manuel, pas décider-par-défaut) : décision PO explicite, tracée ici et à
  l'étape 6. La décider-par-défaut reste ouverte sous un autre numéro si besoin.
- **Increment réel** : le signal manuel existait déjà (SF-164-01) ; SF-164-03 ajoute le respect de
  l'**unitaire explicite** + le **témoin de non-régression** manquant.
