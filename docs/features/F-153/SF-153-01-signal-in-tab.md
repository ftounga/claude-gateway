# Mini-spec — F-153 / SF-153-01 — Signal in-tab (titre d'onglet + favicon dynamique)

## Identifiant

`F-153 / SF-153-01`

## Feature parente

`F-153` — Notifications (signal + Web Push)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-153-01-signal-in-tab`

---

## Objectif

Quand l'onglet est **caché** (`document.hidden`) et qu'un tour **se termine** ou **passe en
attente d'autorisation**, allumer un **signal dans l'onglet** — préfixe de titre + favicon
dynamique — et le rétablir au retour au premier plan. Première alerte, **sans PWA, quasi gratuite**.

---

## Comportement attendu

### Cas nominal

- Un tour **se termine** (`onDone`, hors « tour de suite »/`followUp`) **et** `document.hidden` :
  le titre de l'onglet devient `● Réponse prête — <titre d'origine>` et le favicon prend une
  pastille orange de marque.
- Un tour **passe en attente d'autorisation** (`showConfirmation`) **et** `document.hidden` : le
  titre devient `● Autorisation demandée — <titre d'origine>` et le favicon s'allume.
- **Retour au premier plan** (`visibilitychange` → visible) : titre **et** favicon **rétablis**.
- Si l'onglet est **déjà au premier plan** au moment de la transition : **rien** (le signal in-tab
  ne sert que lorsque l'utilisateur ne regarde pas ; il verra l'invite/la réponse à l'écran).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun `<link rel="icon">` dans le document | Le service ne pose pas de favicon (dégrade proprement) ; le titre s'allume quand même |
| `clear()` appelé sans signal levé | Aucun effet (idempotent) |
| Transition en erreur (`onError`) | **Aucun** signal « réponse prête » (une erreur n'est pas une réponse prête) |

---

## Critères d'acceptation

- [ ] `TabAlertService` : `signalTurnDone()` et `signalAwaitingAuthorization()` n'allument le signal **que** si `document.hidden`.
- [ ] Le titre d'origine est mémorisé puis **restauré** intégralement au `clear()`.
- [ ] Le favicon est remplacé par une pastille de marque puis **restauré** au `clear()` ; absence de `<link rel="icon">` → pas d'erreur.
- [ ] `visibilitychange` → visible déclenche `clear()`.
- [ ] Wiring atelier : `signalTurnDone()` appelé à la fin réelle d'un tour (pas sur `followUp`, pas sur `onError`) ; `signalAwaitingAuthorization()` appelé à l'arrivée d'une demande d'autorisation.
- [ ] Aucune couleur hors DESIGN_SYSTEM (orange `#E07B39` / navy `#0B1020`).
- [ ] Isolation : le service ne lit aucune donnée métier ; il ne reçoit qu'un signal d'état déjà présent côté client.

---

## Périmètre

### Hors scope (explicite)

- Le **Web Push** (backend, abonnements, VAPID, SW handlers) → SF-153-02 / SF-153-03.
- Tout historique/centre de notifications in-app.
- Toute notification quand l'onglet est **au premier plan** (inutile : l'écran montre déjà l'état).

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| déclenchement | uniquement si `document.hidden` |
| couleurs | orange `#E07B39` (pastille), jetons DESIGN_SYSTEM |
| restauration | titre + favicon rétablis à l'identique au retour au premier plan |

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun. Migration : **non applicable** (pur frontend).

### Composants / services Angular

- `TabAlertService` (nouveau, `core/services/`) — gère titre + favicon + `visibilitychange`.
- `AtelierComponent` — deux appels : `signalTurnDone()` (fin de tour `onDone`),
  `signalAwaitingAuthorization()` (arrivée d'une demande dans `showConfirmation`).

---

## Préoccupation transversale

Aucune : pas d'auth, tenant, plan, ni route/guard. Le service ne touche que `document.title` et le
`<link rel="icon">`.

---

## Plan de test

### Tests unitaires (`tab-alert.service.spec.ts`, DOCUMENT bouchonné)

- [ ] `signalTurnDone()` avec `hidden=true` : titre préfixé « Réponse prête », favicon changé.
- [ ] `signalAwaitingAuthorization()` avec `hidden=true` : titre « Autorisation demandée ».
- [ ] Avec `hidden=false` : **aucun** changement de titre ni de favicon.
- [ ] `clear()` restaure titre + favicon d'origine.
- [ ] `visibilitychange` → visible appelle `clear()` (signal levé puis rétabli).
- [ ] Document sans `<link rel="icon">` : pas d'erreur, titre géré quand même.

### Non-régression atelier

- [ ] `atelier.component.spec.ts` reste vert (les deux appels sont additifs ; le service est injecté et bouchonnable).

### Isolation workspace / user_id

- [x] Non applicable — aucun accès données (état déjà côté client).

---

## Dépendances

- Indépendante : **livrable sans F-152** (le signal in-tab ne requiert pas le service worker).
  SF-153-02/03 (Web Push) dépendront de F-152.

---

## Notes et décisions

- **D-hidden-only** : le signal ne se lève que si l'onglet est caché — au premier plan, l'écran
  montre déjà la réponse ou l'invite d'autorisation (F-33/F-47). Évite le bruit.
- **D-favicon-svg** : favicon dynamique via data-URI SVG (pastille orange de marque) — pas de
  fichier binaire à générer, restauration triviale de l'`href` d'origine.
- **D-branchement-F84** : on ne recalcule aucun état ; on se branche sur les transitions déjà
  détectées par le terminal (`onDone`, `showConfirmation`) — Gateway-First.
