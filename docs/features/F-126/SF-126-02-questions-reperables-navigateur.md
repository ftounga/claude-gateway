# Mini-spec — [F-126 / SF-126-02] Questions repérables + navigateur

---

## Identifiant

`F-126 / SF-126-02`

## Feature parente

`F-126` — Terminal : la réponse essentielle mise en avant + questions repérables

## Statut

`ready`

## Date de création

2026-09-17

## Branche Git

`feat/SF-126-02-questions-reperables-navigateur`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire **ressortir** les questions de l'utilisateur dans le fil (bloc distinct, badge « Q1/Q2… », libellé « Votre question ») et offrir un **rail « Vos questions »** qui les liste et permet d'y **sauter** d'un clic (ancre + scroll), replié en tête sur mobile.

---

## Comportement attendu

### Cas nominal

1. Chaque message `USER` du fil est rendu comme une **question repérable** : bloc à filet, **badge « Q<n> »** (numérotation par ordre d'apparition), libellé **« Votre question »**, et un `id` d'ancre stable `terminal-q-<n>`.
2. En **terminal interactif** (projet, poste, Teams — pas en lecture seule), un **rail « Vos questions »** liste les questions numérotées ; un clic **fait défiler** le fil jusqu'à la question (ancre + `scrollIntoView`, borné au conteneur de CE terminal).
3. Sur **large écran** (≥ 820 px), le rail est une colonne de droite **sticky** ; sous **820 px**, une seule colonne et le rail passe **en tête**.
4. S'applique à **tous les terminaux** via le composant unique `AtelierTerminalComponent`. Le terminal **Teams** garde sa peau « Papier » (§15) : la question et le rail reprennent l'**accent chaud** de la peau ; le terminal de la **Forge** (sombre) porte un filet/puce clairs.

### Cas d'erreur / dégradés

| Situation | Comportement attendu |
|-----------|---------------------|
| **Aucune question** posée (fil sans message `USER`) | Aucun rail ; le fil reste en une colonne (`--railed` non appliqué) — aucune régression. |
| Terminal **en lecture seule** (tuile de mosaïque) | **Pas de rail** (une tuile montre le flux et rien d'autre, §13) ; les questions restent **stylées** (badge, filet). |
| Fil **très long** (nombreuses questions) | Le libellé de chaque entrée du rail est **tronqué à 2 lignes** ; le rail sticky reste lisible. |
| Deux terminaux **coexistants** (mosaïque) | Le saut cherche l'ancre **dans le conteneur de son propre terminal**, jamais dans le document entier. |

---

## Critères d'acceptation

- [ ] Un message `USER` rend un bloc `.terminal-question` avec badge « Q<n> », libellé « Votre question » et `id="terminal-q-<n>"`.
- [ ] Les questions successives sont numérotées Q1, Q2… et leurs ancres suivent (`terminal-q-1`, `terminal-q-2`…).
- [ ] En terminal interactif avec ≥ 1 question : un rail « Vos questions » liste chaque question numérotée.
- [ ] Un clic sur une entrée du rail fait défiler le fil jusqu'à la question (`scrollIntoView` appelé sur la bonne ancre).
- [ ] En lecture seule : **aucun** rail ; la question reste stylée.
- [ ] Sans question : **aucun** rail, aucune bascule de mise en page.
- [ ] Charte : aucune couleur nouvelle ; le terminal Teams garde sa peau (accent chaud), la Forge un filet/puce clairs ; contraste AA vert (balayage Teams).
- [ ] Responsive : sous 820 px, une colonne, rail en tête (CSS `@media`).

---

## Périmètre

### Hors scope (explicite)

- La mise en avant de la réponse essentielle (livrée en SF-126-01).
- Un **scroll-spy** surlignant la question courante au défilement (maquette `.cur`) : non retenu (complexité/risque) ; le rail numérote et fait sauter, cela suffit à l'objectif « repérable/navigable ».
- Toute route, garde ou redirection Angular : la navigation du rail est un **défilement interne**, pas du routing.
- Le rail dans les tuiles de mosaïque (lecture seule) : volontairement absent (budget de chrome, §13).

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration.

### Composants Angular

- `atelier-terminal.component.ts` — `questionNumber()`, `questionAnchorId()`, `userQuestions` (getter), `scrollToQuestion()` ; cache de numérotation par tableau de messages (WeakMap).
- `atelier-terminal.component.html` — bloc question (badge + « Votre question » + ancre), wrapper `.terminal-thread`, rail `.terminal-qrail`, classe conditionnelle `--railed`.
- `atelier-terminal-questions.component.scss` (nouvelle feuille, budget 12 ko) — grille 2 colonnes, question, rail, responsive, + reprise Teams.
- Reprise Teams de l'essentiel déplacée dans `atelier-terminal-essential.component.scss` (budget de la feuille Teams).

---

## Plan de test

### Tests unitaires (frontend)

- [ ] Rendu : question `USER` → badge « Q1 », « Votre question », `id="terminal-q-1"`.
- [ ] Numérotation : deux questions → Q1/Q2, ancres `terminal-q-1`/`terminal-q-2`.
- [ ] Rail interactif : liste les questions, classe `--railed` posée.
- [ ] Lecture seule : pas de rail, pas de `--railed`, question stylée.
- [ ] Sans question : pas de rail.
- [ ] Saut : clic sur une entrée → `scrollIntoView` appelé sur la bonne ancre.

### Tests d'intégration

- [ ] N/A (composant de présentation, aucun endpoint) ; non-régression des specs terminaux existantes (2225 verts).

### Isolation workspace

- [ ] Non applicable — composant de présentation, aucun accès données.

---

## Préoccupations transversales

| Préoccupation | Impactée ? | Composants vérifiés |
|--------------|-----------|---------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| **Navigation / routing** | **Oui (analysée)** | Le rail ajoute une **navigation intra-terminal** (scroll vers ancre), **pas** de route. Composants rendant un terminal : **un seul**, `AtelierTerminalComponent`. Instanciations vérifiées : `atelier.component.html` (interactif — projet/poste/Teams — rail affiché, skin selon Teams/Forge) ; `mosaique.component.html` (`[readOnly]="true"` — rail **supprimé**, question stylée). Aucune route, garde ou redirection ajoutée ; le fil d'Ariane (routerLink) et les chemins existants sont **inchangés**. Le saut est borné au conteneur `#scrollback` du terminal courant (jamais `document`), donc sûr en mosaïque. |

---

## Dépendances

### Subfeatures bloquantes

- SF-126-01 — **Done** (PR #664) : mise en avant de l'essentiel ; SF-126-02 partage les feuilles de style dédiées.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Le rail est un grid conditionnel** : `.terminal-scrollback` ne devient une grille à deux colonnes que sous la classe `--railed` (posée quand un rail est présent) — un terminal sans question, ou en lecture seule, garde exactement la mise en page d'avant F-126.
- **Couleurs par surface** : le « liseré navy » de la maquette (dessinée sur clair) ne tient pas sur la Forge sombre ; la question y porte un filet/puce **clairs** (l'accent chaud reste réservé à l'essentiel), tandis que le terminal Teams reprend l'**accent chaud** de sa peau (§15).
</content>
