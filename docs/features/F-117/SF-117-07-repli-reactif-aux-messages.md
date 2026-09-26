# Mini-spec — F-117 / SF-117-07 : « Voir l'historique » s'affiche même quand `foldedTurns` arrive avant les messages

## Identifiant

`F-117 / SF-117-07`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-117-07-repli-reactif-aux-messages`

---

## Objectif

Corriger un bug d'affichage confirmé en prod : l'affordance « Voir l'historique » (SF-117-06) ne
s'affiche jamais parce que le calcul du repli n'est pas réactif au chargement asynchrone des
messages ; rendre `foldedCount` (et tout calcul dérivé de la longueur du fil) réactif au changement
de l'entrée `messages`.

---

## Comportement attendu

### Cas nominal

1. `GET .../chat/resume` renvoie `foldedTurns > 0` **avant** que le fil (`GET .../chat`) ne soit
   chargé — ordre réel observé en prod.
2. L'entrée `[foldedTurns]` est posée alors que `messages` est encore vide, puis les messages
   arrivent ensuite via l'entrée `[messages]`.
3. Dès que les messages sont posés, `foldedCount` se **recalcule** : `min(foldedTurns, messages.length)`.
4. `foldedCount > 0` ⇒ l'affordance « Voir l'historique (N messages) » est rendue et les N premiers
   messages sont repliés — quel que soit l'ordre d'arrivée des deux entrées.
5. Tout le comportement SF-117-06 (toggle révéler/replier, bornage, rail aligné, absorption
   d'auto-scroll, copie honnête) reste identique.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `foldedTurns` posé avant `messages` (ordre prod) | Recalcul au chargement des messages ⇒ repli affiché |
| `messages` posé avant `foldedTurns` (ordre des tests SF-117-06) | Inchangé ⇒ repli affiché |
| `messages` remplacé (nouveau fil) après repli | `foldedCount` se recalcule sur la nouvelle longueur |
| `foldedTurns` > longueur du fil | Bornage inchangé, pas de crash |
| `messages` posé à `null`/`undefined` | Traité comme `[]` (défaut préservé) |

---

## Critères d'acceptation

- [ ] `foldedTurns` posé **avant** `messages` ⇒ après chargement des messages, `hasFoldedHistory()`
      est vrai, `foldedCount()` = `min(foldedTurns, messages.length)`, et l'affordance est dans le DOM.
- [ ] `messages` posé avant `foldedTurns` ⇒ comportement identique (non-régression SF-117-06).
- [ ] Toutes les lectures existantes de `this.messages` dans le composant et le gabarit continuent de
      fonctionner (aucune API cassée) : `lastOutcome`, `questionNumbers`/`questionNumberCache`,
      `isLastUserMessage`, `displayedMessages`, rail « Vos questions ».
- [ ] `displayedMessages`, auto-scroll (SF-158-23), rail (SF-158-22), bornage : inchangés.
- [ ] Un test de non-régression reproduit l'ordre asynchrone réel (foldedTurns puis messages) ;
      il **échoue** contre le code actuel et **passe** après correctif.
- [ ] `--cg-*` uniquement, aucune couleur/police nouvelle ; `npm run build` vert (budgets respectés).
- [ ] Karma ciblé (`atelier-terminal.component.spec.ts`) : nouveaux tests verts ; les 5 échecs
      préexistants (en-tête responsive headless) restent identiques (non comptés en régression).

---

## Périmètre

### Hors scope (explicite)

- Backend, migration, endpoint : **rien** (frontend display-only ; `foldedTurns` déjà fourni par
  SF-117-05).
- Refonte du toggle, de l'affordance, de la copie : inchangés (SF-117-06).
- Numérotation globale des questions : conservée.

---

## Technique

### Endpoint(s)

Aucun. Réutilise l'état de reprise existant (SF-117-05).

### Composants Angular

- `AtelierTerminalComponent` (`frontend/src/app/atelier/terminal/atelier-terminal.component.ts`) :
  - Convertir l'entrée `messages` en signal de sauvegarde : `@Input() set messages(...)` qui alimente
    un `signal<AtelierThreadItem[]>` + `get messages()` qui lit ce signal — l'API `this.messages`
    reste identique pour toutes les lectures existantes et le gabarit.
  - `foldedCount` (computed) lit désormais la longueur via ce signal ⇒ réactif au changement de
    `messages`.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable (frontend)

---

## Plan de test

### Tests unitaires (`atelier-terminal.component.spec`)

- [ ] **Repro bug (ordre prod)** : poser `foldedTurns = 26` PUIS `detectChanges`, PUIS `messages`
      (28 éléments) PUIS `detectChanges` ⇒ `hasFoldedHistory()` vrai, `foldedCount() === 26`,
      `displayedMessages.length === 2`, affordance `.terminal-history-fold` présente dans le DOM.
      (Ce test échoue sur le code actuel — contrôle d'utilité effectué.)
- [ ] **Ordre inverse (non-régression)** : `messages` puis `foldedTurns` ⇒ même résultat.
- [ ] Conserver les 5 tests SF-117-06 existants verts (replie N, révèle/replie, foldedTurns=0,
      bornage, rail aligné).

### Isolation workspace

- [x] Non applicable — composant de présentation, aucun accès données (isolation portée par le
      backend).

---

## Préoccupations transversales

- **Auth / Principal** : non touché.
- **Contexte tenant** : non touché.
- **Plans / limites** : non touché.
- **Navigation / routing** : non touché.
- Analyse d'impact des usages de `this.messages` (composant présentation, un seul fichier) :
  `lastOutcome()`, `questionNumbers()`/`questionNumberCache` (WeakMap clé = référence du tableau,
  préservée car le getter renvoie la même référence stockée dans le signal), `isLastUserMessage()`,
  `displayedMessages`, `userQuestions`, gabarit `atelier-terminal.component.html`. Tous relus,
  aucun ne casse : le getter renvoie exactement la valeur posée par le setter.

---

## Dépendances

### Subfeatures bloquantes

- `SF-117-05` (backend `foldedTurns`) et `SF-117-06` (repli) — mergées.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Cause racine** : `foldedCount = computed(() => Math.min(this.foldedTurnsValue(), this.messages.length))`
  où `messages` est un `@Input()` tableau non réactif. Le computed ne s'invalide qu'au changement de
  `foldedTurnsValue`. En prod, `resume` arrive vite ⇒ `foldedTurns` passe à 26 pendant que `messages`
  est vide ⇒ `foldedCount = min(26,0) = 0` mémoïsé ⇒ les messages arrivant ensuite (entrée tableau,
  aucune invalidation) laissent `foldedCount` à 0 pour toujours.
- **Correctif choisi** : signal de sauvegarde derrière l'entrée `messages` (setter/getter), voie
  minimale qui ne casse aucun usage de `this.messages` ni le gabarit. Le computed devient réactif
  sans autre changement de logique.
