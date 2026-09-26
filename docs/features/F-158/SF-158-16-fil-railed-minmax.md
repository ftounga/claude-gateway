# Mini-spec — [F-158 / SF-158-16] Le fil du terminal railed ne déborde plus sur mobile (grille `minmax(0,1fr)`)

## Identifiant

`F-158 / SF-158-16`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-16-fil-railed-minmax`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Corriger (P0) le débordement à droite du fil du terminal **quand le rail « Vos questions » (F-126) est présent** sur téléphone : l'override mobile de la grille railed passe de `1fr` (= `minmax(auto,1fr)`, minimum = min-content → une longue ligne de code force la piste à ~504 px et la partie droite est clippée) à `minmax(0, 1fr)` (minimum 0 → la piste rétrécit à la largeur du conteneur, le contenu se replie / défile chez lui).

---

## Comportement attendu

### Cas nominal

Sur téléphone (≤ 819 px), dans un terminal **interactif** portant au moins une question de l'utilisateur (`.terminal-scrollback--railed`, F-126) :

- La grille mobile passe à **une seule colonne** `grid-template-columns: minmax(0, 1fr);` (au lieu de `1fr;`).
- Le minimum de la piste devient **0** : la colonne rétrécit à la largeur du conteneur, quelle que soit la largeur intrinsèque du contenu.
- Une **longue ligne de code insécable** (ex. sortie `terraform`/`tfstate`) ne force plus la piste : le fil `.terminal-thread` tient dans la largeur de l'écran ; les blocs `pre`/`table`/`code` défilent **chez eux** via leur `overflow-x: auto` existant (SF-158-14), jamais la page.
- Le rail « Vos questions » reste en tête (`order: -1`, `position: static`) — inchangé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Terminal **sans question** (pas de classe `--railed`) | Non concerné — la règle ne s'applique qu'à `.terminal-scrollback--railed`. Aucun changement. |
| Écran **desktop** (≥ 820 px) | **Rigoureusement inchangé** — le desktop garde `grid-template-columns: minmax(0, 1fr) 232px;` (hors media). La correction est entièrement sous `@media (max-width: 819px)`. |
| Contenu très large (ligne mono insécable) dans un message | Aucun débordement du **fil** (`.terminal-thread`) ni de la page à 390 px ; les blocs à défilement propre défilent chez eux. |
| Autre grille mobile du terminal avec le même piège (`1fr` sans `minmax(0,…)`) | Balayée : aucune autre occurrence trouvée dans les 14 feuilles du terminal (seule `atelier-terminal-questions.component.scss:28` est concernée). |

---

## Critères d'acceptation

- [ ] À 390 px, avec la classe `--railed` ET un contenu large (longue ligne de code insécable), `.terminal-thread` (le FIL) a `clientWidth <= 390` (et non ~504 px).
- [ ] À 390 px, aucun enfant direct du fil ne déborde au point de dépasser 390 px (les blocs à défilement propre exceptés, qui gèrent leur propre `overflow-x`).
- [ ] Le test est **rouge avant** (avec `1fr`) et **vert après** (avec `minmax(0,1fr)`) — il mesure bien `.terminal-thread`, pas seulement `.terminal-scrollback`.
- [ ] Desktop (≥ 820 px, Karma 1440 px) inchangé : la grille railed reste `minmax(0, 1fr) 232px` (test de non-régression desktop conservé).
- [ ] Aucune couleur/police nouvelle ; jetons `--cg-*` uniquement (aucun jeton touché ici, changement de layout seul).
- [ ] La feuille `atelier-terminal-questions.component.scss` reste sous le budget de build 12 ko.
- [ ] `npm run build` vert ; suite Karma ciblée verte.

---

## Périmètre

### Hors scope (explicite)

- Le **desktop** (≥ 820 px) : aucune modification.
- La **logique Angular / les bindings / le template** : style seul (aucun DOM/TS touché).
- Le terminal **Teams** et la **lecture seule** : non concernés (la classe `--railed` n'est posée qu'en interactif avec questions).
- Toute **nouvelle couleur / police** ; toute autre feuille du terminal (balayées, aucune autre corrigée).
- La coquille (F-151), la PWA (F-152), les notifications (F-153).

---

## Technique

### Endpoint(s)

Aucun — pur frontend, display-only.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular (feuilles SCSS)

- `atelier-terminal-questions.component.scss` — override mobile `@media (max-width: 819px)` de `.terminal-scrollback--railed` : `grid-template-columns: 1fr;` → `grid-template-columns: minmax(0, 1fr);`. **Unique ligne fonctionnelle changée.**
- `terminal-largeur-reelle.spec.ts` — test étendu (railed + contenu large + mesure de `.terminal-thread` à 390 px).

### Préoccupations transversales

| Préoccupation | Impact | Composants |
|--------------|--------|-----------|
| Auth / Principal | Aucun | — |
| Contexte tenant | Aucun | — |
| Plans / limites | Aucun | — |
| Navigation / routing | Aucun | — |

Aucune préoccupation transversale cochée : changement purement visuel/layout, mobile-only, sans DOM/TS.

---

## Plan de test

### Tests unitaires (Karma — ChromeHeadless, DOM réel)

Extension de `terminal-largeur-reelle.spec.ts` :

- [ ] À 390 px, terminal monté **avec `--railed`** (fil + rail) et un message contenant une **longue ligne de code insécable**, règles mobiles ré-appliquées sans la garde media : `.terminal-thread` a `clientWidth <= 390` (rouge-avant avec `1fr`, vert-après avec `minmax(0,1fr)`).
- [ ] À 390 px, aucun enfant direct du fil ne dépasse 390 px au point de déborder.

### Test de non-régression desktop (conservé + ajout ciblé)

- [ ] À 1440 px (sans la feuille mobile), la grille `.terminal-scrollback--railed` reste `minmax(0, 1fr) 232px` (deux colonnes, rail à droite) — desktop inchangé.

### Isolation utilisateur

- [ ] Non applicable — pur affichage, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-158-10` — Done (containment `:host`/`.terminal-view`, vrai test de largeur en DOM réel — patron réutilisé).
- `SF-158-14` — Done (boîtes de sortie qui défilent chez elles — c'est ce qui absorbe le contenu large une fois la piste rétrécie).
- `F-126 / SF-126-02` — Done (le rail « Vos questions » et la grille railed corrigée ici).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Cause racine confirmée par mesure réelle (Chrome, 390 px)** : `1fr` = `minmax(auto, 1fr)` ; le minimum `auto` d'une piste de grille se résout au **min-content** des items → une longue ligne de code insécable force la piste unique à ~504 px (voire 828 px), et `.terminal-scrollback { overflow-x: hidden }` (mobile) **clippe** la partie droite. `minmax(0, 1fr)` fixe le minimum à **0** → la piste suit la largeur du conteneur et le contenu se replie / défile dans ses propres boîtes.
- **Pourquoi les tests précédents ont raté** : ils mesuraient `.terminal-scrollback` (le conteneur clippant, dont `scrollWidth` reste borné par `overflow-x:hidden`), jamais `.terminal-thread` (le fil, qui, lui, était dilaté à 504 px sous la piste `1fr`). Le nouveau test mesure le FIL.
- **Balayage** : `grep 'grid-template-columns:\s*1fr\b'` sur les 14 feuilles SCSS du terminal → **une seule occurrence** (ligne 28 de cette feuille). Aucune autre grille mobile du terminal ne porte ce défaut.
- **Budget** : feuille `atelier-terminal-questions.component.scss` ~5,0 ko (bien sous 12 ko) ; la correction n'ajoute que quelques caractères.
