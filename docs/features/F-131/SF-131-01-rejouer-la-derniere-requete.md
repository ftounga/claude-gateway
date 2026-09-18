# Mini-spec — F-131 / SF-131-01 — Rejouer la dernière requête (spinner honnête + bouton)

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage source de vérité : `docs/features/F-131/CADRAGE-F-131-rejouer-la-derniere-requete.md`.

---

## Identifiant

`F-131 / SF-131-01`

## Feature parente

`F-131` — Rejouer la dernière requête

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-131-01-rejouer-derniere-requete`

---

## Objectif

Donner un **filet côté client** dans le terminal de l'Atelier : quand le flux SSE d'un tour se
détache sans réponse rendue, le spinner ne tourne plus à l'infini — l'écran bascule sur un état
**« Réponse non reçue — Rejouer ? »**, et un clic **re-soumet la dernière requête utilisateur** comme
un **nouveau tour** via l'envoi de chat existant.

---

## Comportement attendu

### Cas nominal

1. Un tour est lancé (`startTurn`). La dernière requête utilisateur est mémorisée.
2. **Chemin honnête A — flux fermé sans final** : le flux d'émission (`streamChat`) se referme sans
   avoir émis `done` (non-suite) ni `error`. Le service appelle un nouveau hook `onClosed`. Le
   composant tente d'abord un **rattrapage par fenêtres** (F-84, le serveur a peut-être fini) ; si le
   rattrapage aboutit (`done`), le fil se recharge normalement ; s'il revient `idle` (rien ne tourne
   côté serveur) ou en erreur, l'écran bascule sur l'état **« réponse non reçue »**.
3. **Chemin honnête B — tour sans rendu** : la sonde de flux retenu (F-84 / SF-84-04) bascule déjà
   sur un suivi par fenêtres quand rien n'est entendu ; si ces fenêtres abandonnent (`idle` après
   plusieurs essais), l'écran bascule sur l'état **« réponse non reçue »** au lieu de rester en
   spinner (aujourd'hui : rien ne se passe, spinner infini).
4. **Bouton « Rejouer la dernière requête »** : présent dans l'état « réponse non reçue » ET,
   manuellement, sur le **dernier message utilisateur** quand aucun tour n'est en cours.
5. **Le clic rejoue** : anti-doublon d'abord (voir cas d'erreur), puis re-soumission du dernier
   message utilisateur comme **nouveau tour** (réutilise `startTurn` → `streamChat`). Un message clair
   dit que **cela produit une nouvelle réponse** (ne restaure pas le tour perdu à l'identique).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Rejouer alors qu'un tour est visiblement en cours (`submitting`) | Refusé + avertissement « un tour est encore en cours » ; jamais deux tours en parallèle |
| Rejouer depuis l'état « réponse non reçue » alors que le serveur signale un tour encore vivant (`GET /chat/turn` → `live:true`) | On ne lance PAS un second tour : on se **rebranche** sur le tour vivant + message ; anti-doublon serveur |
| Pas de dernière requête (aucun message utilisateur) | Bouton absent / inactif ; `replayLastRequest` ne fait rien |
| Vérification serveur `GET /chat/turn` en échec | Filet dégradé : on rejoue quand même (le filet doit marcher même si `/turn` est indisponible) |

---

## Critères d'acceptation

- [ ] Un flux d'émission fermé **sans** `done`/`error`, sans tour serveur vivant, aboutit à l'état
      « réponse non reçue — Rejouer ? » (plus de spinner infini).
- [ ] Un tour sans rendu dont les fenêtres de suivi abandonnent (`idle`) aboutit au même état honnête.
- [ ] Un flux fermé alors que le serveur a réellement fini → le rattrapage par fenêtres recharge le
      fil (réponse affichée), **sans** état d'erreur.
- [ ] Le clic « Rejouer » re-soumet le **dernier message utilisateur** comme nouveau tour (rendu
      normalement) et efface l'état « réponse non reçue ».
- [ ] Rejouer est **empêché/averti** si un tour est réellement actif (local `submitting`, ou serveur
      `live:true`) — pas de double tour.
- [ ] Un message dit clairement que rejouer **produit une nouvelle réponse**.
- [ ] Non-régression : envoi normal, streaming, `<<essentiel>>` (F-126), question repérable
      (F-126-02), précisions/tour de suite (F-84), rebranchement (SF-84-02) inchangés.

---

## Périmètre

### Hors scope (explicite)

- **Reprise serveur exacte** du tour interrompu (recomposer la réponse perdue) — cadrage §8.
- Supprimer la cause racine du détachement (réseau/onglet quitté) — on gère le symptôme.
- Tout changement backend : **aucun nouvel endpoint** (réutilise `POST /chat/stream` et
  `GET /chat/turn` existants). Aucune table, aucune migration.

---

## Technique

### Endpoint(s)

Aucun nouveau. Réutilise :
- `POST /api/workspaces/{id}/chat/stream` (envoi/rejeu) — existant.
- `GET /api/workspaces/{id}/chat/attach` (suivi par fenêtres / rattrapage) — existant (F-84).
- `GET /api/workspaces/{id}/chat/turn` (anti-doublon serveur) — existant (F-84).

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular

- `AtelierService` — `streamChat` appelle `onClosed` quand le flux se ferme sans événement final ;
  ajout du hook `onClosed?` à `AtelierStreamHandlers`.
- `AtelierComponent` — signaux `unanswered` / `replaying`, mémorisation `lastRequest`, handler
  `onClosed` → rattrapage/`onTurnStreamClosed`, `showUnanswered`, `replayLastRequest`, `onIdle` sur
  les handlers d'envoi (filet du chemin B).
- `AtelierTerminalComponent` — entrées `unanswered` / `replaying`, sortie `replay`, bannière honnête
  « réponse non reçue » + bouton « Rejouer », bouton « Rejouer » sur le dernier message utilisateur.

### Préoccupations transversales (cadrage §9)

- **Navigation** : aucun ajout de route ni de guard — bouton intra-terminal. Aucun chemin de
  navigation existant modifié.
- **Plans / limites** : un rejeu consomme un tour (quota) via `startTurn` — geste explicite, aucun
  nouveau gate ; le refus de plafond existant (`liveTerminals.limitReached`) s'applique tel quel.
- **Auth / tenant** : aucune modification du Principal ni de la résolution `user_id` ; réutilise les
  endpoints authentifiés existants.
- **Composants impactés** listés ci-dessus (`AtelierService`, `AtelierComponent`,
  `AtelierTerminalComponent`).

---

## Plan de test

### Tests unitaires / composant (`atelier.component.spec.ts`)

- [ ] `onClosed` sans tour serveur vivant (fenêtres → `idle`) → `unanswered() === true`, `submitting()
      === false`.
- [ ] `onClosed` avec tour serveur qui rend `done` via fenêtres → fil rechargé, `unanswered() ===
      false`.
- [ ] `replayLastRequest()` sans tour actif et `/turn` non vivant → `startTurn` rappelé avec la
      dernière requête, `unanswered()` remis à `false`.
- [ ] `replayLastRequest()` avec `submitting() === true` → refus + avertissement, pas de nouveau tour.
- [ ] `replayLastRequest()` en état « réponse non reçue » mais `/turn` `live:true` → rebranchement, pas
      de second tour.

### Tests service (`atelier.service.spec.ts`)

- [ ] `streamChat` : flux fermé sans `done`/`error` → `onClosed` appelé une fois.
- [ ] `streamChat` : flux avec `done` (non-suite) → `onClosed` **non** appelé.
- [ ] `streamChat` : flux avec `error` → `onClosed` **non** appelé.

### Tests terminal (`atelier-terminal.component.spec.ts`)

- [ ] `unanswered` → bannière « réponse non reçue » + bouton « Rejouer » rendus ; clic émet `replay`.
- [ ] Bouton « Rejouer » présent sur le dernier message utilisateur quand `!submitting`.

### Isolation workspace

Non applicable directement (frontend). Les endpoints réutilisés portent déjà l'isolation `user_id`
(F-84) ; aucune nouvelle route.

---

## Dépendances

### Subfeatures bloquantes

- F-84 (rebranchement, fenêtres de suivi, `GET /chat/turn`) — Terminée. Réutilisée.
- SF-125-07/08 (garantie serveur de la réponse) — complémentaire, non bloquante.

### Questions ouvertes impactées

Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Rejouer = re-soumettre la dernière requête utilisateur** (nouveau tour), pas de reprise serveur
  (cadrage §4).
- **Aucun endpoint backend ajouté** : l'anti-doublon serveur réutilise `GET /chat/turn` (F-84). Pas
  de drapeau « nouvel endpoint ».
- Réutilisation maximale de la machinerie F-84 (sonde + suivi par fenêtres) : les deux chemins
  honnêtes convergent vers `showUnanswered` sans nouveau transport.
