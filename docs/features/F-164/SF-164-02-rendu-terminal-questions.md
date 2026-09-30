# Mini-spec — F-164 / SF-164-02 — Rendu terminal des questions structurées (desktop + mobile)

## Identifiant

`F-164 / SF-164-02`

## Feature parente

`F-164` — Questions structurées à l'utilisateur (parité Claude Code `AskUserQuestion`)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-164-02-rendu-questions`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre à l'écran, dans le fil du terminal (desktop **et** mobile), une **carte de question soignée**
quand l'agent appelle l'outil `demander` (SF-164-01) : afficher chaque question du lot (1 à 4) avec ses
options en choix **simple** (radio) ou **multiple** (cases), l'option **recommandée** repérée, un champ
**« Autre / tape ta réponse »** toujours présent, puis poster la réponse sur l'endpoint isolé existant
`POST /workspaces/{id}/chat/answer` et faire passer la carte à l'état **« répondu »**.

---

## Comportement attendu

### Cas nominal

1. Un tour tourne dans le terminal. Le modèle appelle `demander` : le backend (SF-164-01) suspend le
   tour et émet l'événement de flux **`question_request`** (et l'aparté **`question_state`** pour un
   spectateur qui se branche après coup), portant `callId`, `questions[]` (`header`, `question`,
   `multiSelect`, `options[]` = `label` + `description` + `recommended`) et `timeoutMs`.
2. `AtelierService.dispatchSseEvent` (flux **chat**) parse `question_request` **et** `question_state`
   vers le **même** callback `onQuestionRequest` (comme `confirm_request`/`confirm_state` : « pour
   l'écran, une attente est une attente »), et `question_resolved` vers `onQuestionResolved`.
3. `AtelierComponent` pose l'état du tour `pendingQuestion` (signal), démarre un **compte à rebours**
   (mirroir de la porte, F-47/SF-47-02 : `deadline = now + timeoutMs`, minuteur 1 s, `nudgeRender`
   car le flux se tait pendant l'attente), et transmet la carte au terminal.
4. La **carte** (`app-atelier-terminal-demande`) rend, par question : l'intitulé, les options en
   **radio** (choix simple) ou **cases à cocher** (choix multiple), l'option **recommandée** portant un
   repère discret « Recommandé », et **toujours** un champ libre « Autre / tape ta réponse ». Un seul
   bouton **Envoyer** compose la réponse.
5. À l'envoi, `AtelierComponent.submitQuestionAnswer` poste
   `POST /workspaces/{id}/chat/answer` `{ callId, answers[] }` via `AtelierService.answerQuestion`
   (chemin **isolé** existant). La carte est **verrouillée** (`answering`) le temps de l'aller-retour.
6. Le backend tranche la porte, le tour reprend, l'événement **`question_resolved`** retire l'invite :
   `onQuestionResolved` fait passer la carte à **« répondu »** (verrouillée, montre le choix fait si la
   réponse vient d'**ici**, ou « Répondu sur un autre appareil » sinon). La carte est retirée quand une
   **nouvelle** question arrive (pauses répétées, nouveau `callId`) ou à la fin du tour.
7. **Cross-device (F-84)** : un autre appareil qui se branche pendant l'attente reçoit l'aparté
   `question_state` au rejeu (déjà émis par SF-164-01) → **la même carte s'affiche** avec le temps
   restant recalculé par la gateway. Répondre depuis cet appareil reprend le tour ; le premier appareil
   reçoit `question_resolved` et passe sa carte à « répondu ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Envoi sans aucun choix ni texte libre sur une question | Bouton Envoyer **désactivé** tant qu'aucune question n'a de réponse ; jamais de POST vide (le backend renvoie 400, mais l'écran l'empêche en amont) | — (bloqué à l'écran) / 400 |
| `POST /answer` échoue en 409 (la question n'attend plus : double réponse, réponse tardive, tour fini) | La carte est retirée et un message lisible s'affiche (« La question n'attend plus de réponse. ») ; aucun clic dans le vide | 409 |
| `POST /answer` renvoie 404 (projet non possédé) | Message « Projet introuvable. » ; carte retirée | 404 |
| Délai dépassé sans réponse (`question_resolved` status `timeout`) | La carte passe à un état terminal « Délai écoulé » puis est retirée ; message lisible | — |
| Réponse tranchée ailleurs (autre appareil) | `question_resolved` (status `answered`) → carte « Répondu sur un autre appareil », verrouillée | — |
| En **lecture seule** (mosaïque / F-83) | La question est **signalée** (libellé « Une question vous attend », sans boutons) : on regarde, on ne tranche pas | — |

---

## Critères d'acceptation

> Chaque critère est vérifiable et reviewé dans la PR.

- [ ] **CA1 (parse)** — `AtelierService` parse `question_request`, `question_state` (→ `onQuestionRequest`)
  et `question_resolved` (→ `onQuestionResolved`) sur le flux **chat**, avec les types de charge utile
  (`AtelierQuestionRequest` : `callId`, `questions[]`, `timeoutMs?` ; `AtelierQuestionResolved` :
  `callId`, `status`).
- [ ] **CA2 (rendu simple)** — Une question `multiSelect:false` rend ses options en **radios** (un seul
  choix). Une question `multiSelect:true` rend des **cases à cocher** (plusieurs choix).
- [ ] **CA3 (recommandé)** — L'option `recommended:true` porte un repère textuel discret « Recommandé »
  (aucune couleur hors charte).
- [ ] **CA4 (option libre)** — Un champ « Autre / tape ta réponse » est **toujours** présent, quelle que
  soit la question ; une réponse ne portant qu'un texte libre est valide et envoyée.
- [ ] **CA5 (envoi)** — Le bouton Envoyer compose `{ callId, answers[] }` (une entrée par question, avec
  `header`, `selected[]`, `other`) et appelle `POST /workspaces/{id}/chat/answer` via le service ; la
  carte se verrouille pendant l'aller-retour.
- [ ] **CA6 (répondu)** — À réception de `question_resolved`, la carte passe à l'état **verrouillé**
  « répondu » (montrant le choix fait si la réponse vient d'ici) puis est retirée à la question suivante
  ou en fin de tour.
- [ ] **CA7 (compte à rebours)** — Un `timeoutMs` positif affiche un compte à rebours ; sans délai, aucun
  chiffre n'est inventé. Le temps restant est recalculé à l'attache via `question_state` (SF-47-02).
- [ ] **CA8 (cross-device / attache)** — Un écran qui se branche pendant l'attente reçoit
  `question_state` et **affiche la carte** avec le temps restant ; répondre depuis cet écran reprend le
  tour et l'autre écran passe à « répondu ». *(Vérifié via test unitaire de routage + parcours manuel.)*
- [ ] **CA9 (mobile / SF-158)** — Cibles tactiles **≥ 44 px**, pas de débordement horizontal à 390 px,
  `min-width:0` sur les enfants flex, rangée d'actions qui passe à la ligne ; le rail SF-158-23
  (auto-scroll) amène la carte dans le champ de vision ; **pas de régression SF-158**.
- [ ] **CA10 (accessibilité)** — Radios/cases **natifs** (ou rôles ARIA), `fieldset`/`legend` par
  question, focus clavier visible, `aria-label` sur le champ libre et le bouton Envoyer.
- [ ] **CA11 (design system)** — Uniquement des jetons `--cg-*` ; aucune couleur/police nouvelle ;
  cohérence visuelle avec la carte d'autorisation (`.terminal-ask`) et l'essentiel.
- [ ] **CA12 (non-régression porte)** — La **porte d'autorisation** (Autoriser / Tout autoriser /
  Refuser), qui partage le mécanisme généralisé (SF-164-01), rend et fonctionne toujours ; boucle,
  recall, compaction, SF-117-08 (assainissement du replay), SF-158 mobile intacts. `npm run build` vert,
  tests existants du terminal et du service verts.

---

## Périmètre

### Hors scope (explicite)

- **Politique décider-par-défaut + flag + notification push** (option recommandée = défaut au timeout /
  en vague autonome ; push F-153) → **SF-164-03**. Le champ `recommended` est **rendu** ici, mais aucune
  décision automatique n'est prise.
- **Unification des prompts ad-hoc** (Nouveau départ F-117, reprise F-39, la porte) → **SF-164-04**.
- **Enrichissement du snapshot JSON `/turn` (`pendingQuestion`) et du `RemoteTurnSource`** :
  **non nécessaire** (décision, voir « Notes »). La visibilité cross-device est **déjà** portée par
  l'aparté SSE `question_state` (SF-164-01) que le rejeu d'attache relaie, y compris cross-pod — le
  champ `pending` du snapshot n'est d'ailleurs **consommé nulle part** dans le frontend aujourd'hui.
  Aucun changement backend dans cette SF.
- **Rendu des questions dans la mosaïque / vue 360** (`live-turn-view`) au-delà du signalement en lecture
  seule : la mosaïque n'initie aucun tour et ne tranche rien (limites de la vue 360, F-84).
- **Rail « Vos questions »** (SF-126-02, `terminal-q*` / `--railed`) : distinct, non touché ; le nouveau
  rendu emploie un préfixe `terminal-demande-*` pour éviter toute collision.

---

## Valeurs initiales

> Aucune entité persistée. L'état vit **en mémoire** dans le composant (view-model), le temps du tour,
> comme la demande d'autorisation.

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `AtelierPendingQuestion.status` | `awaiting` | posé à la réception de `question_request`/`question_state` |
| `AtelierPendingQuestion.answering` | `false` | passe `true` le temps de l'aller-retour du POST |
| `deadline` | `now + timeoutMs` | `null` si `timeoutMs` absent → aucun compte à rebours |
| sélection par question (radio) | vide | un seul label retenu |
| sélection par question (multi) | vide | ensemble de labels |
| texte libre par question | `''` | trim à l'envoi |

---

## Contraintes de validation (côté écran)

| Champ | Obligatoire | Longueur / bornes | Format | Normalisation |
|-------|-------------|-------------------|--------|---------------|
| réponse d'une question | au moins 1 sur le lot | — | au moins un choix **ou** un texte libre non vide sur ≥ 1 question pour activer Envoyer | trim du texte libre |
| texte libre | Non | ≤ 2000 (aligné backend) | texte | trim |
| `selected` (multi) | Non | ≤ 8 labels (aligné backend) | labels des options | — |

Notes :
- L'écran **empêche** un envoi vide (bouton désactivé) ; le backend reste l'autorité (400 si contourné).
- Les labels envoyés sont ceux des options cochées, verbatim (le backend accepte tels quels, SF-164-01).

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Isolation |
|---------|-----|------|-----------|
| POST | `/api/workspaces/{id}/chat/answer` | Oui (JWT, intercepteur) | **existant** (SF-164-01) : `requireTerminalAccess` + `requireOwned` (404 cross-user). Le frontend n'ajoute aucun contournement — il appelle le chemin isolé avec l'`id` du workspace actif. |

Aucun **nouvel** endpoint. Aucune modification backend.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| — | — | **Aucune** : rendu pur, état en mémoire (view-model). |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucun schéma modifié.

### Composants Angular

- **NOUVEAU** `AtelierTerminalDemandeComponent` (standalone, `atelier-terminal-demande.component.ts` +
  `.html` + `.scss`) — la carte de question : `@Input() pending`, `@Input() countdown`,
  `@Input() readOnly` ; `@Output() answer`. État local des sélections/texte libre. Feuille scss dédiée
  (budget 12 ko propre au composant).
- **MODIF** `AtelierTerminalComponent` — `@Input() pendingQuestion`, `@Input() questionCountdown` ;
  `@Output() questionAnswer` ; embarque `<app-atelier-terminal-demande>` dans le fil (après la carte
  d'autorisation) et relaie l'output.
- **MODIF** `AtelierComponent` — signal `pendingQuestion`, minuteur `questionRemainingMs` +
  `questionCountdown` (computed), `showQuestion`/`clearQuestion`/`submitQuestionAnswer` ; câblage dans
  les jeux de handlers **chat** (envoi initial + réattache) ; retrait aux points de vie du tour
  (fin/erreur/interruption/coupe-circuit), mirroir de `clearPendingConfirmation`.
- **MODIF** `AtelierService` — parse `question_request`/`question_state`/`question_resolved` (flux chat) ;
  méthode `answerQuestion(id, body): Observable<void>`.
- **MODIF** modèles : `atelier.models.ts` (`AtelierQuestionRequest`, `AtelierQuestion`,
  `AtelierQuestionOption`, `AtelierQuestionResolved`, `AtelierAnswerRequest`, `AtelierQuestionAnswerEntry`,
  handlers `onQuestionRequest`/`onQuestionResolved`) ; `atelier.types.ts` (`AtelierPendingQuestion`).

### Classes backend

- **Aucune.** (Voir « Hors scope » : pas d'enrichissement `/turn`.)

---

## Plan de test

### Tests unitaires (Karma)

- [ ] `atelier-terminal-demande.component.spec.ts` (**nouveau**) :
  - rend N questions (1 à 4) ; `multiSelect:false` → radios ; `multiSelect:true` → cases ;
  - option `recommended` porte le repère « Recommandé » ;
  - champ libre « Autre » toujours présent ;
  - Envoyer désactivé tant qu'aucune réponse ; activé dès un choix **ou** un texte libre ;
  - émet `answer` avec `{ callId, answers[] }` correct (choix multiples, texte libre seul, mixte) ;
  - état `answering`/« répondu » verrouille les contrôles ;
  - lecture seule → signalement sans boutons ;
  - cibles ≥ 44 px, rôles ARIA/`fieldset`/`legend` présents.
- [ ] `atelier.service.spec.ts` (**complété**) : `question_request`, `question_state`, `question_resolved`
  routent vers les bons handlers ; `answerQuestion` poste sur `/chat/answer` le bon corps.

### Tests d'intégration / non-régression

- [ ] `atelier-terminal.component.spec.ts` reste vert (rendu de la carte d'autorisation inchangé ;
  la carte de question s'affiche quand `pendingQuestion` est fourni).
- [ ] `boutons-terminaux-lisibles.spec.ts`, `acquis-f30.spec.ts` restent verts.
- [ ] `npm run build` **vert** (budgets respectés).

### Isolation workspace / user

- [x] **Applicable (héritée)** — l'envoi passe par l'endpoint **isolé existant** (SF-164-01 :
  `requireTerminalAccess` + `requireOwned`, 404 cross-user). Le frontend n'introduit **aucun** nouvel
  accès données ni contournement ; il fournit l'`id` du workspace actif du contexte. Vérifié par revue
  du chemin d'appel + test de service (URL isolée appelée).

---

## Dépendances

### Subfeatures bloquantes

- **SF-164-01** (backend `demander` : événements `question_request`/`question_state`/`question_resolved`,
  endpoint `/chat/answer`) — **livrée** sur `main`.
- **F-84** (pause/reprise cross-device, aparté rejoué à l'attache) — **livrée**.
- **SF-158** (responsive mobile) — respectée (conventions ≥ 44 px / no-overflow / SF-158-23).

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` n'est tranchée par cette SF.

---

## Préoccupations transversales — analyse d'impact

| Préoccupation | Touchée ? | Composants impactés + vérification |
|--------------|-----------|-------------------------------------|
| **Auth / Principal** | Non | L'appel `POST /chat/answer` passe par l'intercepteur JWT existant ; aucun changement du Principal ni de session. Endpoint et contrôle d'accès inchangés (SF-164-01). |
| **Contexte tenant** | Non | Aucune nouvelle résolution de tenant. L'`id` du workspace actif vient du contexte existant (`activeWorkspaceId`) ; l'isolation reste côté endpoint (`requireOwned`). |
| **Plans / limites** | Non | Aucun quota ni gate. Rendu + un POST vers un endpoint existant. |
| **Navigation / routing** | Non | **Aucune route Angular ajoutée** : la carte vit **dans le fil** du terminal existant. Aucun guard ni redirection touchés. Chemins de navigation existants inchangés. |

---

## Notes et décisions

- **Décision — pas d'enrichissement du snapshot `/turn`.** La visibilité cross-device de la question à
  l'attache est **déjà** assurée par l'aparté SSE `question_state` (SF-164-01), relayé par le rejeu du
  tour vivant (F-84), y compris cross-pod (`RemoteTurnSource` relaie le flux du pair). Le champ `pending`
  du snapshot JSON `AtelierTurnState` n'est **consommé nulle part** dans le frontend actuel (seul
  `state.live` est lu, dans le filet de rejeu SF-162). Ajouter `pendingQuestion` au snapshot serait donc
  du code mort et imposerait de câbler `RemoteTurnSource` pour rien. On consomme le même mécanisme que la
  porte d'autorisation (SSE `*_state`), ce qui garantit la parité de comportement. → **frontend-only.**
- **Décision — composant dédié plutôt qu'inline.** La carte porte un **état de formulaire** non trivial
  (sélections par question, texte libre) ; un composant standalone dédié l'isole, le rend testable en
  Karma, et lui donne son **propre** budget scss de 12 ko (aucune pression sur les 8 feuilles du
  terminal). L'état **du tour** (`pendingQuestion`, minuteur, HTTP) reste dans `AtelierComponent`,
  conformément au patron de la porte (état dans le parent, rendu + émetteurs dans la vue).
- **`nudgeRender` (F-47/SF-47-01)** : le flux se tait pendant qu'une question attend ; comme pour la
  porte, `showQuestion`/`clearQuestion` et chaque tick du minuteur forcent un cycle de rendu, sinon la
  carte ne serait pas peinte.
- **Anti-collision SF-126-02** : préfixe de classes `terminal-demande-*` ; on ne touche ni `terminal-q*`
  ni la grille `--railed` du rail « Vos questions ».
- **Gateway-First / Provider-First** : rendu pur d'un mécanisme d'interaction ; aucun « moteur IA ».
