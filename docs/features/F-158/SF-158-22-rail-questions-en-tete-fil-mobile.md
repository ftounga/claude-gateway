# Mini-spec — [F-158 / SF-158-22] Le rail « Vos questions » en tête du fil sur mobile, à la racine (ordre DOM)

> Correctif de robustesse P1 (retour PO, capture 390 px). Suite directe de SF-158-19 / SF-158-20 / SF-158-21.

---

## Identifiant

`F-158 / SF-158-22`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-22-rail-questions-en-tete-mobile`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire du placement « rail en tête » sur téléphone (≤ 819 px) une **garantie de structure** — le rail « Vos questions » (F-126) est rendu **avant** le fil dans le DOM — au lieu d'un artifice CSS `order: -1` posé sur un rail resté dernier dans le DOM, tout en gardant le desktop (≥ 820 px : fil à gauche, rail à droite en 232 px) **visuellement identique**.

---

## Constat de diagnostic (établi par mesure de layout réelle, non par lecture de règle)

- La consigne rapportait que `order: -1` (posé dès F-126 / SF-126-02) « ne prenait pas » et que le rail tombait en bas, sous les FAB (titre « Vos questions » à ~y=711 dans 844 px).
- **Mesure décisive** (ChromeHeadless, iframe natif à **390 px**, cascade et `@media` réels) : `order: -1` **prend bien** — `display: grid`, `order: -1`, `position: static`, et **`rail.top (156) < thread.top (211)`** : le rail est déjà **visuellement en tête** dans le code d'`origin/main`. La grille `.terminal-scrollback--railed { display: grid }` (base) n'est écrasée par aucune règle ; aucune feuille ne repasse le fil en `flow`/`flex`.
- **Vraie cause du constat PO** : l'écart CODE ↔ PROD. Le placement « en tête » repose sur un **artifice de peinture** (`order: -1`) alors que le rail reste **dernier dans le DOM**. Conséquences réelles, non couvertes jusqu'ici :
  1. **Ordre de lecture / de focus faux** : un aide-navigation (« sauter à la question N ») rendu *dernier* au clavier et au lecteur d'écran — l'inverse de sa fonction, surtout sur mobile.
  2. **Fragilité** : la mise en tête n'est vraie que tant que la grille reste établie ; toute régression de `display` (feuille future, `@layer`, refonte) fait retomber le rail en bas *sans qu'aucun test de layout ne le voie* — c'est exactement le piège relevé par SF-158-10 (« les specs CSSOM passaient sans corriger »).
  3. **Contradiction SF-158-21** : SF-158-21 a empilé une grande réserve basse (`112+48+16 px + safe-area`) sur le *même* conteneur en supposant que « la dernière portion (fil OU rail) » pouvait être le rail. Le rail étant en tête, cette réserve ne sert **que le fil**.
- **Correctif à la racine** : rendre le rail **avant** le fil dans le DOM ⇒ « en tête sur mobile » devient l'ordre **naturel** (flux), plus un effet `order`. Le desktop garde le rail à droite via un `order: 1` explicite (rendu **pixel-identique**, prouvé par garde-fou). Un **garde-fou de layout réel** (pas CSSOM) verrouille les deux orientations.

---

## Comportement attendu

### Cas nominal

- **DOM** : dans `.terminal-scrollback` (le seul `overflow-y:auto` en mobile), l'ordre source devient `[ <aside.terminal-qrail>, <div.terminal-thread> ]` — le rail **d'abord** (interactif, avec au moins une question ; jamais en lecture seule).
- **Mobile (≤ 819 px)** : grille à une colonne (`grid-template-columns: minmax(0,1fr)`, SF-158-16 inchangée). Le rail, premier dans le DOM et `order: 0`, occupe la **première rangée** (en tête) ; le fil suit. `position: static` (conservé). Le titre « Vos questions » et le badge Q1 sont en haut, jamais dans la bande basse des FAB.
- **Desktop (≥ 820 px)** : grille deux colonnes `minmax(0,1fr) 232px` (inchangée). Le rail porte `order: 1` (base) → il est placé dans la **seconde piste** (à droite), le fil dans la première (à gauche) — **rendu visuel identique** au comportement actuel, malgré l'ordre DOM inversé. `position: sticky; top: 0` conservé.
- **FAB** : le rail étant en tête, les deux boutons flottants (Note poste repliée à gauche, aide « ? » à droite, ancrés `bottom: calc(112px + safe-area)`) ne recouvrent plus que le **bas du fil** (texte) — comportement FAB normal, accepté par le PO.
- **Réserve basse SF-158-21** : `padding-bottom` / `scroll-padding-bottom` du scrollback **conservés tels quels** (documentés : ils dégagent désormais la dernière portion du **fil** des FAB, plus le rail). Non réduits pour ne pas toucher les critères testés de SF-158-21.

### Cas d'erreur

> Ici « erreur » = régression visuelle / d'usage / d'accessibilité à éviter.

| Situation | Comportement attendu |
|-----------|---------------------|
| Desktop (≥ 820 px) | Rendu **visuellement identique** : fil à gauche (1fr), rail à droite (232 px), rail `sticky`. Prouvé par garde-fou (rail.left > thread.left, 2 pistes dont 232px). |
| Terminal en lecture seule | Pas de rail (`--railed` absent, `@if !readOnly`) : aucun changement, aucune classe grille. |
| Terminal interactif sans question | Pas de rail : aucun changement. |
| Régression future du `display` de la grille | Sans effet sur la mise en tête mobile : l'ordre DOM la garantit (le rail reste premier même en flux normal). |
| Encoche (safe-area) / réserve basse | Inchangées (SF-158-19/20/21 non modifiées). |
| Ordre de lecture / focus | Le rail (nav) est désormais **premier** au clavier et au lecteur d'écran, mobile ET desktop — amélioration d'accessibilité assumée et documentée. |

---

## Critères d'acceptation

> Chaque critère est vérifiable. Pas d'ambiguïté.

- [ ] Dans le gabarit, l'`<aside class="terminal-qrail">` est rendu **avant** le `<div class="terminal-thread">` à l'intérieur de `.terminal-scrollback`.
- [ ] **Mobile (390 px, layout réel)** : avec le rail actif, `rail.getBoundingClientRect().top < thread.getBoundingClientRect().top` (le rail est visuellement en tête).
- [ ] **Mobile** : le rail retombe en tête **même si `display: grid` est retiré** de la grille (garantie par l'ordre DOM, pas par `order`) — vérifié par un garde-fou dédié.
- [ ] **Desktop (1440 px, layout réel)** : `rail.getBoundingClientRect().left > thread.getBoundingClientRect().left` (rail à droite) et la grille garde deux pistes dont la seconde `232px`.
- [ ] `.terminal-qrail` conserve `position: static` (mobile) / `position: sticky; top:0` (desktop) et son `order` : `1` en base, `0` sous ≤ 819 px.
- [ ] Aucune couleur ni police nouvelle : uniquement les jetons `--cg-*` existants et des primitives CSS.
- [ ] Aucune régression SF-158-16 (`minmax(0,1fr)`), SF-158-17, SF-158-19, SF-158-20, SF-158-21 : leurs règles ne sont pas modifiées (le `padding-bottom`/`scroll-padding-bottom` de SF-158-21 est conservé à l'identique).
- [ ] Les tests existants du rail (`atelier-terminal.component.spec.ts` : liste des questions, numérotation, saut à la question) restent verts.
- [ ] `npm run build` vert (budget ≤ 12 ko par feuille respecté) ; tests Karma ciblés verts, affichés.

---

## Périmètre

### Hors scope (explicite)

- Toute modification du **rendu visuel** desktop (≥ 820 px) — seul l'ordre DOM/focus change, à rendu pixel constant.
- Le repositionnement / redimensionnement des FAB eux-mêmes (SF-158-19 / SF-158-20).
- La **réduction** de la réserve basse SF-158-21 (envisagée par la consigne, écartée ici pour ne pas rouvrir les critères testés de SF-158-21 ; conservée et documentée).
- Le TypeScript, les services, les bindings, le backend, les endpoints, les migrations (aucun).

---

## Contraintes de validation

Sans objet — aucun champ de données saisi/validé (correctif de structure/CSS front, display-only).

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `AtelierTerminalComponent`
  - `atelier-terminal.component.html` — déplacer l'`<aside class="terminal-qrail">` **avant** `<div class="terminal-thread">` dans `.terminal-scrollback` (commentaire mis à jour).
  - `atelier-terminal-questions.component.scss` — 8ᵉ feuille (là où vit toute la logique de grille/rail ; budget respecté) :
    - base : ajouter `order: 1` sur `.terminal-scrollback--railed .terminal-qrail` (garde le rail dans la 2ᵉ piste à droite en desktop malgré l'ordre DOM inversé) ;
    - `@media (max-width: 819px)` : `order: -1` → `order: 0` (le rail, premier dans le DOM, est en tête par le flux ; `position: static` conservé).

### Préoccupations transversales

- **Auth / Principal** : non concernée (aucun changement d'auth/session/Principal).
- **Contexte tenant / `user_id`** : non concernée (aucun accès données ; display-only).
- **Plans / limites** : non concernée.
- **Navigation / routing** : non concernée (aucune route/guard/redirection ; l'« ordre de navigation » ici est l'ordre de focus/lecture DOM au sein d'un même écran, traité comme amélioration d'accessibilité, pas une route).

---

## Plan de test

### Tests unitaires (Karma — layout RÉEL en ChromeHeadless, pas CSSOM)

Nouveau fichier `terminal-rail-en-tete.spec.ts` (patron iframe natif 390 px + mesure desktop 1440 px) :

- [ ] **Mobile (iframe 390 px, `@media` natif)** : rail actif ⇒ `rail.top < thread.top` (rail en tête).
- [ ] **Garantie de structure** : en forçant `display: block` sur `.terminal-scrollback--railed` (annulation de la grille), le rail reste **avant** le fil (`rail.top < thread.top`) — prouve que la mise en tête vient du DOM, pas de `order`.
- [ ] **Desktop (1440 px)** : rail actif ⇒ `rail.left > thread.left` (rail à droite) et `gridTemplateColumns` = 2 pistes, seconde = `232px`.
- [ ] **DOM** : dans `.terminal-scrollback`, `firstElementChild` est `.terminal-qrail` (ou le rail précède le fil).

### Tests d'intégration

- [ ] Non applicable (aucun endpoint) — la vérification d'intégration est le rendu réel mesuré ci-dessus + `npm run build`.

### Isolation utilisateur / workspace

- [x] Non applicable — raison : aucun accès aux données, aucun `user_id` (correctif d'affichage/structure côté client uniquement).

### Non-régression

- [ ] `atelier-terminal.component.spec.ts` (rail : liste, numéro Q1, saut à la question) verts.
- [ ] `terminal-largeur-reelle.spec.ts` (SF-158-16 fil railed ≤ 390 px ; desktop 2 pistes) verts.

---

## Dépendances

### Subfeatures bloquantes

- `SF-126-02` (rail « Vos questions ») — Done.
- `SF-158-16` (grille `minmax(0,1fr)`), `SF-158-19/20/21` (FAB + réserve basse) — Done (non modifiées).

### Questions ouvertes impactées

- [ ] Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Décision** : correction *à la racine* par l'ordre DOM plutôt que par `order: -1`. Le placement « en tête sur mobile » devient une propriété de structure (robuste aux régressions de `display`) et corrige l'ordre de lecture/focus du rail (aide-navigation) sur mobile **et** desktop.
- **Desktop pixel-identique** : l'inversion DOM est compensée par `order: 1` (base) ; le rendu visuel desktop est prouvé inchangé par garde-fou (rail à droite, 2 pistes dont 232px). Le seul effet desktop assumé est l'ordre de focus/lecture (rail avant fil), documenté comme amélioration d'accessibilité.
- **SF-158-21 conservée** : la réserve basse (`padding-bottom`/`scroll-padding-bottom`) reste à l'identique ; elle dégage désormais la dernière portion du **fil** des FAB (le rail n'est plus en bas). Réduction volontairement hors scope pour ne pas rouvrir ses critères testés.
- **Feuille choisie** : `atelier-terminal-questions.component.scss` (où vit toute la grille/rail), budget 12 ko respecté ; la feuille mobile dédiée n'accueille aucune règle `qrail`/`order` (inchangée).
