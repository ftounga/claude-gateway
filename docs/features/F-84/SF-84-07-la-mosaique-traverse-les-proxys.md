# Mini-spec — F-84 / SF-84-07 — La mosaïque traverse les proxys, et suit les tours de suite

## Identifiant

`F-84 / SF-84-07`

## Feature parente

`F-84` — Le tour survit à son flux

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-84-07-mosaique-proxys`

---

## Objectif

La mosaïque (F-83) suit chaque tuile **derrière un proxy d'entreprise** qui retient le flux SSE —
par la même sonde et la même reprise par curseur que le terminal (SF-84-04) — et une tuile **ne se
croit plus au repos** au premier `done` d'un **tour de suite** (SF-84-06).

---

## Contexte

La mosaïque a été livrée (F-83 / SF-83-02) **avant** les correctifs SF-84-04 et SF-84-06. Chaque
tuile branche sa place lectrice par `LiveTurnView`, qui appelle `AtelierService.attachTurn` — un
**unique** flux `GET .../chat/attach` sans fenêtre. Deux régressions en découlent :

1. **Exposition au proxy identique au terminal d'avant SF-84-04.** Derrière Netskope/Zscaler
   (constat de production du 2026-09-13, SF-84-04), le corps d'une réponse `text/event-stream` est
   **retenu jusqu'à sa fin** : une tuile qui regarde un tour de huit minutes n'affiche rien avant la
   dernière seconde. C'est exactement le défaut que SF-84-04 a corrigé pour le terminal (sonde de
   4 s puis suivi par fenêtres), et que `LiveTurnView` n'a jamais reçu (« laissée en l'état pour ne
   pas élargir le lot », hors-scope explicite de SF-84-04).

2. **Un tour de suite éteint la tuile.** Depuis SF-84-06, une précision arrivée pendant la réponse
   finale ouvre un **tour de suite** dans le même tour vivant : `done` porte alors `followUp: true`
   et **n'est pas la fin**. `LiveTurnView.onDone` appelle pourtant `rest()` sans regarder `followUp` :
   la tuile passe « au repos », perd son direct, et ne se rebranche que 5 s plus tard — alors que le
   tour continue.

Rien à ajouter côté gateway : `attach?waitMs` (SF-84-04) et `done.followUp` (SF-84-06) existent déjà
et sont couverts. Le correctif est **entièrement dans `LiveTurnView`** (place lectrice), là où le
terminal l'a reçu dans `AtelierComponent`.

---

## Comportement attendu

### Cas nominal

1. **Sonde plutôt que fenêtres systématiques.** À l'ouverture, la tuile branche le flux d'attache
   **normal** (`attachTurn`, `cursor` courant, sans `waitMs`) — inchangé sur un réseau direct, où
   `attached` ou `idle` arrive en quelques millisecondes.
2. **Flux retenu ⇒ suivi par fenêtres.** Si la tuile n'a **rien entendu** du flux (ni `attached`, ni
   `idle`, ni prise en main) **4 s** après l'ouverture, elle abandonne le flux normal — qui, retenu,
   rejouerait tout d'un bloc, `attached` compris, et remettrait la tuile à zéro — et suit le tour par
   `followTurnInWindows` : la gateway livre ce qui est neuf depuis le curseur puis **clôt**, une
   réponse close étant relâchée par le proxy, et l'on repart du nouveau curseur.
3. **Ni doublon ni trou.** Les deux sources (flux normal, fenêtres) partagent le même numérotage
   `id:` ; un événement dont le numéro est ≤ curseur déjà vu est ignoré, et un événement d'une
   génération précédente n'est jamais appliqué.
4. **Un tour de suite reste vivant dans la tuile.** Un `done` porteur de `followUp: true` **ne met
   pas la tuile au repos** : elle reste « en cours », le tour de suite s'affiche dans la même tuile.
   Seul un `done` **sans** `followUp` (ou une `error`) la met au repos.
5. **Rien ne tourne n'est pas une panne (inchangé).** `idle`, un `done` final, une `error` ou un
   réseau coupé laissent la tuile au repos ; la lecture se rebranche `REATTACH_DELAY_MS` (5 s) plus
   tard. La densité, le tri, l'anneau ambre, l'identité du client (SF-83-02) ne changent pas.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Flux retenu par un proxy (aucun octet en 4 s) | la tuile bascule sur les fenêtres et avance | 200 (SSE) |
| Fenêtres `idle` répétées (rien ne tourne) | après `maxIdle` fenêtres, la tuile passe au repos et se rebranche 5 s plus tard | 200 (SSE) |
| `done(followUp=true)` | la tuile **reste en cours** (pas de repos) | 200 (SSE) |
| `done` final / `error` / réseau coupé | tuile au repos, rebranchement 5 s plus tard | 200 (SSE) / — |
| Tuile fermée (quitter la page) | sonde, fenêtres et flux normal abandonnés ; le tour n'est pas touché (SF-84-01) | — |
| Tour d'un autre utilisateur | introuvable ⇒ `idle` (clef `(userId, workspaceId)`, inchangée) | 200 (SSE) |

---

## Critères d'acceptation

- [x] CA1 — À l'ouverture, la tuile branche `attachTurn` **normal** : le premier `fetch` vise
      `/api/workspaces/{id}/chat/attach?cursor=0` **sans** `waitMs` (non-régression SF-83-02).
- [x] CA2 — **Reproduction du constat** : flux normal retenu (aucun événement), après la sonde de 4 s
      la tuile suit par fenêtres (`fetch` avec `&waitMs=`) et l'étape `bash` livrée par fenêtre
      apparaît dans la tuile — test rouge avant correctif, vert après.
- [x] CA3 — Un flux normal qui répond vite (`attached`/`idle`) **n'ouvre aucune fenêtre** : la sonde
      est désarmée, aucun `fetch` avec `waitMs` n'est émis.
- [x] CA4 — Un événement livré deux fois (fenêtre puis flux relâché) n'est appliqué qu'une fois
      (curseur partagé) ; un événement d'un tour précédent est ignoré (génération).
- [x] CA5 — `done(followUp=true)` **ne remet pas** `stream()` à `null` : la tuile reste en cours et
      le tour de suite s'y affiche.
- [x] CA6 — `done` **final** (sans `followUp`) met la tuile au repos, puis elle se rebranche 5 s plus
      tard (comportement SF-83-02 conservé).
- [x] CA7 — Fermer la tuile abandonne sonde, fenêtres et flux, et ne rouvre plus rien.
- [x] CA8 — Isolation : aucun identifiant d'utilisateur n'est envoyé ; la tuile n'attache que des
      projets nommés par le registre de l'utilisateur (SF-83-02 inchangé).
- [x] CA9 — Aucune couleur hors `DESIGN_SYSTEM.md` (aucun changement de gabarit ni de style).

---

## Périmètre

### Hors scope (explicite)

- **Toute ligne de backend** : `attach?waitMs` (SF-84-04) et `done.followUp` (SF-84-06) existent et
  sont couverts. Aucun endpoint créé ou modifié.
- **Écrire depuis une tuile** — tranché dès F-76 : la tuile reste en lecture seule, sans champ, sans
  bouton, sans `steer`. Elle ne fait qu'**afficher** un tour de suite, elle n'en ouvre aucun.
- Le texte jeton par jeton (SF-84-05).
- Le gabarit, le tri, la densité et l'identité client de la mosaïque (SF-83-02) : inchangés.
- Le parcours Managed Agents (`/agent/stream`) : autre moteur, non lu par la mosaïque.

---

## Valeurs initiales

Non applicable — aucune entité créée.

## Contraintes de validation

| Champ | Obligatoire | Bornes | Règle |
|---|---|---|---|
| `waitMs` (fenêtre) | — | [1 000 ; 25 000] ms | valeur par défaut `TURN_WINDOW_WAIT_MS` (20 000), bornée côté gateway (SF-84-04) |
| délai de sonde (tuile) | — | 4 000 ms | constante `TURN_STREAM_PROBE_MS`, la même que le terminal |
| délai de rebranchement | — | 5 000 ms | constante `REATTACH_DELAY_MS`, inchangée (SF-83-02) |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---|---|---|---|
| GET | `/api/workspaces/{id}/chat/attach?cursor=&waitMs=` | Oui (JWT, droit Atelier) | **aucun** — déjà servi (SF-84-02 / SF-84-04) |

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `LiveTurnView` (`mosaique/live-turn-view.ts`) — **seul composant touché** :
  - sonde de flux retenu (constante `TURN_STREAM_PROBE_MS` réutilisée depuis `AtelierService`),
    bascule vers `followTurnInWindows` si rien n'est entendu en 4 s ;
  - `heard`/génération + `acceptSeq` pour ne jamais appliquer deux fois un événement, ni un
    événement d'un tour précédent ;
  - `onDone` respecte `done.followUp` : un tour de suite ne met pas la tuile au repos ;
  - `close()`/`rest()` arrêtent aussi la sonde et les fenêtres.

### Préoccupations transversales

- **Auth / Principal** : **non** touché. La route `forge/mosaique` vit sous la coquille
  authentifiée (SF-83-02) ; l'attache lit le `CurrentUser` comme avant. Aucun endpoint n'est ajouté
  ni modifié.
- **Contexte tenant** : **non** — l'attache cherche toujours le tour par `(userId, workspaceId)` ;
  aucun nouveau moyen de résoudre le tenant. Composant vérifié : `LiveTurnView.open` (n'envoie que le
  `workspaceId` venu du registre de l'utilisateur, jamais un identifiant d'utilisateur).
- **Plans / limites** : **non** — la place lectrice passe par le pool `turnAttachExecutor`
  (SF-84-02), ne prend aucune place au registre F-70 et ne consomme aucun token. Les fenêtres
  réutilisent **le même** endpoint d'attache : aucune place émettrice. Composants vérifiés :
  `LiveTerminalService` (jamais injecté par la mosaïque, aucun `POST .../terminal/live`),
  `AtelierService.followTurnInWindows` (même endpoint lecteur que `attachTurn`).
- **Navigation / routing** : **non** — aucune route ajoutée ou modifiée.

---

## Plan de test

### Tests unitaires

- [x] `live-turn-view.spec` — CA1 : l'ouverture branche `attach?cursor=0` **sans** `waitMs`.
- [x] `live-turn-view.spec` — CA2 : flux retenu (aucun événement) ⇒ après 4 s, `fetch` avec
      `&waitMs=` ; une étape `bash` livrée par fenêtre apparaît dans la tuile.
- [x] `live-turn-view.spec` — CA3 : `attached`/`idle` rapide ⇒ aucune fenêtre ouverte (sonde
      désarmée).
- [x] `live-turn-view.spec` — CA4 : un événement de numéro déjà vu est ignoré.
- [x] `live-turn-view.spec` — CA5 : `done(followUp=true)` garde la tuile en cours.
- [x] `live-turn-view.spec` — CA6 : `done` final met au repos puis rebranche à 5 s (conservé).
- [x] `live-turn-view.spec` — CA7 : `close()` arrête sonde + fenêtres, plus aucun `fetch` ensuite.
- [x] Non-régression : les tests SF-83-02 existants de `live-turn-view.spec` et
      `mosaique.component.spec` restent verts.

### Tests d'intégration

Sans objet côté backend : **aucun endpoint créé ni modifié**. `attach` (avec et sans `waitMs`) est
déjà couvert (`AtelierChatControllerAttachTest`, SF-84-04), isolation `(userId, workspaceId)`
comprise.

### Isolation utilisateur

- [x] Applicable — portée entièrement par la gateway (SF-83-02 / SF-84-02) : la tuile n'envoie aucun
      identifiant d'utilisateur et n'attache que des projets nommés par **son propre** registre ; le
      tour d'autrui est *introuvable* (`idle`), pas « refusé ». Inchangé ici.

---

## Dépendances

### Subfeatures bloquantes

- SF-83-02 (la mosaïque) — `done`.
- SF-84-02 (attache par curseur), SF-84-04 (fenêtres / `waitMs`), SF-84-06 (`done.followUp`) —
  `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Même remède que le terminal, au même endroit conceptuel.** SF-84-04 a mis la sonde et les
  fenêtres dans `AtelierComponent` (l'émetteur / rebrancheur). La place lectrice de la mosaïque,
  `LiveTurnView`, reçoit ici le même dispositif : `attachTurn` normal d'abord, `followTurnInWindows`
  seulement si le flux est retenu. Aucun nouveau transport, aucune nouvelle constante propre.
- **D2 — Sonde plutôt que fenêtres systématiques.** Un réseau direct ne paie rien : `attached`/`idle`
  arrive avant 4 s, aucune fenêtre n'est ouverte, et l'assertion d'URL de SF-83-02 (`?cursor=0` sans
  `waitMs`) reste vraie. C'est la raison du choix « sonde » plutôt que « fenêtres partout ».
- **D3 — Le curseur repart de 0 à chaque (ré)ouverture.** `openGate()` remet `cursor = 0` : une tuile
  (ré)ouverte n'a rien gardé en mémoire, comme le rebranchement du terminal (`attachTurn(id, 0, …)`).
  Corrige au passage un report latent du curseur d'un tour fini vers le rebranchement suivant.
- **D4 — Lecture seule intacte.** La tuile n'ouvre aucun tour de suite ; elle en **affiche** un. Le
  `followUp` ne change que l'état d'affichage (rester en cours vs. repos), jamais un envoi.
