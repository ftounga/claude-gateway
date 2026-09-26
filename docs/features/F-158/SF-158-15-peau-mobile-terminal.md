# Mini-spec — [F-158 / SF-158-15] Peau mobile du terminal alignée sur la maquette (niveau « chat »)

## Identifiant

`F-158 / SF-158-15`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-15-peau-mobile-terminal`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Appliquer réellement la **peau sombre « pro » de la maquette validée** (`mobile-terminal-mockup.html`) au **fil du terminal Atelier sur mobile** (≤ 819 px), au même niveau de finition que le chat refondu (F-160-01) — là où SF-158-12/13/14 n'avaient posé que la structure en gardant l'ancienne peau (fond, surfaces, espacements).

---

## Comportement attendu

### Cas nominal

Sur téléphone (≤ 819 px), dans un terminal de projet **interactif** (ni lecture seule, ni Teams) :

- Le **fond du fil** passe au navy profond de la maquette (`--cg-navy`, `#0B1020` = `--bg`), comme le fond du chat mobile — au lieu du `--cg-primary` daté.
- Le **chrome** (barre d'en-tête, composeur) passe en **surface** `--cg-navy-2`, comme la barre d'outils et le composeur du chat.
- Les **cartes/blocs du fil** prennent la peau de la maquette :
  - la demande de l'utilisateur (`.terminal-prompt-line` / `.terminal-question`) = carte boxée `--cg-navy-2` arrondie (déjà en place, conservée) ;
  - les **sous-agents groupés** (`.terminal-subagents`, maquette `.subs`) = **carte bordée arrondie** (filet `--cg-divider` voilé, surface `--cg-navy-2`), au lieu du seul filet gauche ;
  - l'**essentiel** (`.terminal-essential`, maquette `.ess`) = filet accent + voile doré + rayon (déjà en place, rayon aligné) ;
  - la **sortie / le code** (`.terminal-output`, maquette `.out`) = boîte en creux `--cg-navy` qui **défile chez elle** (déjà en place, respiration ajoutée) ;
  - la **demande d'autorisation** (`.terminal-ask`, maquette `.ask`) = carte arrondie (rayon 14) avec ombre douce dérivée d'un jeton, commande en creux `--cg-navy` arrondie ; boutons empilés pleine largeur (déjà en place) ;
  - la **ligne vivante** (`.terminal-live`, maquette `.live`) = ligne mono atténuée, chrono/coût poussés à droite (déjà en place).
- Le **champ de saisie** devient un creux `--cg-navy` bordé sur le composeur `--cg-navy-2` (lisible comme un champ, pas un blend).
- Espacements plus généreux (gouttières ≥ 16 px déjà rendues par SF-158-13 ; respiration des cartes).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Terminal **Teams** (peau « Papier » claire) | **Non touché** — les règles de surface Teams (`.terminal-view.terminal-view--teams …`, spécificité (0,2,0)/(0,3,0)) l'emportent, et la nouvelle règle de fond est explicitement exclue via `:not(.terminal-view--teams)`. |
| Tuile en **lecture seule** (mosaïque) | **Non touché** — fond `--cg-navy-2` conservé (règle de fond exclue via `:not(.terminal-view--readonly)`). |
| Écran **desktop** (≥ 820 px) | **Rigoureusement inchangé** — tout est sous `@media (max-width: 819px)` ; aucune règle hors media ; test de non-régression desktop conservé/étendu. |
| Contenu très large (ligne mono insécable) | Aucun scroll horizontal de **page** (containment SF-158-10 conservé) ; la sortie défile dans sa boîte. |

---

## Critères d'acceptation

- [ ] À ≤ 819 px, `.terminal-view` (interactif, non-Teams, non-readonly) a pour fond `--cg-navy` (et non `--cg-primary`).
- [ ] À ≤ 819 px, `.terminal-bar` et `.terminal-input` sont sur surface `--cg-navy-2` (chrome, parité chat).
- [ ] À ≤ 819 px, `.terminal-subagents` est une carte bordée arrondie (fond non transparent, `border-radius > 0`), plus seulement un filet gauche.
- [ ] À ≤ 819 px, `.terminal-ask` a un `border-radius ≥ 12px` et `.terminal-ask-command` un fond `--cg-navy`.
- [ ] À ≤ 819 px, aucun débordement horizontal de page à 360/400 px (garde-fou SF-158-10 conservé) ; la sortie défile chez elle.
- [ ] Le terminal **Teams** garde sa peau « Papier » (fond `--cg-terminal-teams-bg`) à ≤ 819 px.
- [ ] Le desktop (≥ 820 px, Karma 1440 px) est inchangé : bandeau complet présent, `⋯` absent, fonds inchangés (test de non-régression vert).
- [ ] Aucune couleur nouvelle : jetons `--cg-*` uniquement (dérivés via `color-mix` autorisés, technique déjà employée).
- [ ] Chaque feuille SCSS reste sous le budget de build 12 ko ; feuille principale du terminal (11.97 ko) non touchée.

---

## Périmètre

### Hors scope (explicite)

- Le **desktop** (≥ 820 px) : aucune modification.
- La **logique Angular / les bindings / le template** : style seul (le template ne change pas hors du signal `isNarrow` déjà en place, qui n'est pas retouché ici).
- Le terminal **Teams** et la **lecture seule** : non retouchés (peaux dédiées conservées).
- Toute **nouvelle couleur / police**.
- La **coquille** (F-151), la PWA (F-152), les notifications (F-153).

---

## Technique

### Endpoint(s)

Aucun — pur frontend, display-only.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular (feuilles SCSS)

- `atelier-terminal-mobile.component.scss` — **feuille mobile dédiée** : ajout de la section « peau sombre pro » (fond du fil, chrome, champ, carte ask, sous-agents, respiration). C'est la seule feuille substantiellement modifiée.
- Restyle mobile de composants à feuille dédiée sans casser le desktop : `.terminal-subagents` (F-150), `.terminal-essential` (F-126), `.terminal-ask` — via règles `@media (max-width: 819px)` dans la feuille mobile (le composant partage l'`attr` d'encapsulation).

### Préoccupations transversales

| Préoccupation | Impact | Composants |
|--------------|--------|-----------|
| Auth / Principal | Aucun | — |
| Contexte tenant | Aucun | — |
| Plans / limites | Aucun | — |
| Navigation / routing | Aucun | — |

Aucune préoccupation transversale cochée : changement purement visuel, mobile-only, sans DOM/TS.

---

## Plan de test

### Tests unitaires (Karma — ChromeHeadless, DOM réel)

Extension de `terminal-largeur-reelle.spec.ts` (SF-158-15) :

- [ ] À 360/400 px, le fond du fil interactif est bien un navy sombre (fond non transparent, ≠ ancien primary) — mesure `getComputedStyle`.
- [ ] À 360 px, `.terminal-subagents` est une carte (fond non transparent + `border-radius > 0`) quand un lot d'explorations est rendu.
- [ ] À 360 px, `.terminal-ask` a un `border-radius ≥ 12px` (carte de la maquette) quand une décision est en attente.
- [ ] Conservé : à 360/400 px, la sortie défile dans sa boîte et la page ne défile pas horizontalement.

### Test de non-régression desktop (conservé)

- [ ] À largeur desktop (Karma 1440 px, `isNarrow=false`), le bandeau d'actions complet est présent et le menu `⋯` absent (test SF-158-12 existant, conservé).
- [ ] Ajout : à largeur desktop, le fond de `.terminal-view` **n'est pas** modifié par la feuille mobile (les règles vivent sous `@media` et ne s'appliquent qu'appliquées sans garde — vérification que la règle mobile est bien sous media).

### Isolation utilisateur

- [ ] Non applicable — pur affichage, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-158-12/13/14` — Done (structure : en-tête compact, composeur ancré, peau du fil conservatrice).
- `SF-160-01` — Done (barre de qualité « chat » de référence).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Mapping maquette → jetons** (aucune couleur nouvelle) : `--bg #0B1020` → `--cg-navy` ; `--surface #121a30` / `--surface-2` → `--cg-navy-2` ; `--out #080c17` (plus sombre) → jeton navy le plus proche `--cg-navy` ; `--text #E7ECF6` → `--cg-divider` ; `--muted` → `--cg-text-secondary` ; `--accent #F5A623` → `--cg-accent` / `--cg-orange-2` (gestes). Identique au mapping **livré** du chat mobile (F-160-01), d'où la parité.
- **Protection Teams / readonly** : la règle de fond `.terminal-view` est scopée `:not(.terminal-view--teams):not(.terminal-view--readonly)` ; les surfaces de chrome (`.terminal-bar`, `.terminal-input`) sont déjà dominées par la spécificité des règles Teams et n'existent pas en lecture seule (pas d'en-tête ni de composeur).
- **Budget** : tout va dans la feuille mobile dédiée (raw ~11 ko, minifiée bien en-dessous de 12 ko) ; la feuille principale du terminal (11.97 ko) n'est pas touchée.
