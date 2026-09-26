# Mini-spec — [F-158 / SF-158-21] Dégager le rail « Vos questions » des boutons flottants (mobile)

> Correctif P0 (retour PO, capture 390 px). Suite directe de SF-158-19 / SF-158-20.

---

## Identifiant

`F-158 / SF-158-21`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-21-degager-rail-questions-fab`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous 819 px, réserver au bas du conteneur qui défile (`.terminal-scrollback`) une marge basse suffisante pour que la dernière portion du rail « Vos questions » (F-126) — son titre et le badge de la question 1 — puisse défiler **au-dessus** des deux boutons flottants ancrés à `bottom: calc(112px + env(safe-area-inset-bottom))` (la « Note poste » repliée, SF-158-20, à gauche ; l'aide « ? », à droite) et redevienne entièrement visible et cliquable.

---

## Comportement attendu

### Cas nominal

> Description précise du flux principal (entrée → traitement → sortie).

- L'élément qui défile réellement en mobile est `.terminal-scrollback` (`flex: 1; overflow-y: auto`, `atelier-terminal.component.scss`). Le rail `.terminal-qrail` et le fil `.terminal-thread` sont ses deux enfants de grille ; le composeur `.terminal-input` est un **frère en flux** (`position: sticky; bottom: 0`), donc le bas du fil s'arrête au-dessus de lui.
- Les deux FAB (`.notice--collapsed` de `workstation-notice`, `.help-widget` de `help-chat-widget`) sont `position: fixed`, remontés par SF-158-19 à `bottom: calc(112px + env(safe-area-inset-bottom))`, hauteur ~48 px → ils occupent une bande au-dessus du composeur, par-dessus le bas du fil.
- SF-158-19 avait posé une réserve `padding-bottom` / `scroll-padding-bottom` de 72 px (`calc(var(--cg-space-6) + var(--cg-space-4))`), calibrée sur l'hypothèse (périmée) « FAB ~56 px sur le bord bas du fil » et **sans `env(safe-area-inset-bottom)`** : insuffisante depuis que les FAB sont ancrés à 112 px + safe-area (constat PO 390 px : titre « Vos questions » + badge Q1 recouverts, non tappables).
- Correctif : la réserve passe à `calc(112px + 48px + var(--cg-space-3) + env(safe-area-inset-bottom))` (ancrage FAB + hauteur FAB + jour + encoche), appliquée en `padding-bottom` **et** `scroll-padding-bottom` du **même** `.terminal-scrollback`, sous le seul point de rupture `@media (max-width: 819px)` de la feuille mobile dédiée.
- Résultat : la dernière portion du contenu (fil ou rail) défile toujours au-dessus de la bande des FAB ; les cibles redeviennent visibles et cliquables. Les sauts d'ancre (`scroll-padding-bottom`) déposent aussi la cible au-dessus de la bande.

### Cas d'erreur

> Ici « erreur » = régression visuelle / d'usage à éviter.

| Situation | Comportement attendu |
|-----------|---------------------|
| Rendu desktop (≥ 820 px) | STRICTEMENT inchangé — tout le correctif est sous `@media (max-width: 819px)` |
| Encoche (safe-area) présente | Les FAB montent de `env(safe-area-inset-bottom)` ; la réserve monte du même terme → clairance conservée |
| Terminal en lecture seule / sans question (pas de rail) | Le fil défile au-dessus des FAB de la même façon (réserve appliquée au scrollback, pas au rail) — pas de régression |
| Feuille au plafond de build 12 ko | Correctif porté par la feuille mobile dédiée (comme SF-158-19), jamais par la feuille questions — budget respecté |

---

## Critères d'acceptation

> Chaque critère est vérifiable. Pas d'ambiguïté.

- [ ] Sous 819 px, `.terminal-scrollback` porte `padding-bottom` **et** `scroll-padding-bottom` = `calc(112px + 48px + var(--cg-space-3) + env(safe-area-inset-bottom))`.
- [ ] La réserve inclut `env(safe-area-inset-bottom)` (elle suit la remontée des FAB sur écran à encoche).
- [ ] La réserve (≥ 112 + 48 + 16 px) dégage la bande des FAB (ancrés à 112 px, ~48 px de haut) : le titre « Vos questions » et le badge Q1 défilent au-dessus et redeviennent cliquables.
- [ ] Le correctif est intégralement sous `@media (max-width: 819px)` de `atelier-terminal-mobile.component.scss` — desktop (≥ 820 px) inchangé.
- [ ] Aucune couleur ni police nouvelle : uniquement les jetons `--cg-*` existants et des primitives CSS (`px`, `env`).
- [ ] Aucune régression SF-158-16 (`minmax(0,1fr)`), SF-158-17 (repli/déploiement), SF-158-19 (FAB à 112 px), SF-158-20 (mini-bouton 48 px) : ces règles ne sont pas modifiées.
- [ ] `npm run build` vert ; tests Karma ciblés verts (garde-fou CSSOM nouveau + non-régression existants).

---

## Périmètre

### Hors scope (explicite)

- Toute modification du desktop (≥ 820 px).
- Le DOM/HTML, le TypeScript, les services, les bindings (style seul).
- Le repositionnement / redimensionnement des FAB eux-mêmes (SF-158-19 / SF-158-20, inchangés).
- L'ordre du rail dans la colonne (`order: -1`, `atelier-terminal-questions.component.scss`, inchangé).
- Backend, endpoints, migrations, entités (aucun).

---

## Technique

### Endpoint(s)

Aucun (correctif purement frontend / display-only).

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular impactés

- `AtelierTerminalComponent` — feuille de style dédiée `atelier-terminal-mobile.component.scss` (styleUrl, déclarée après la feuille questions → gagne sous 819 px). **Seule la règle `.terminal-scrollback` sous `@media (max-width: 819px)` est modifiée** (valeurs `padding-bottom` / `scroll-padding-bottom` + commentaire).

### Préoccupations transversales

- Auth / Principal : non concernée.
- Contexte tenant / `user_id` : non concernée (aucun accès données).
- Plans / limites : non concernée.
- Navigation / routing : non concernée (aucune route/guard).

---

## Plan de test

### Tests unitaires (Karma, garde-fou CSSOM indépendant du viewport — patron SF-158-09/19)

- [ ] `atelier-terminal.component.spec.ts` — sous 819 px, le CSS concaténé des règles `@media (max-width:819px)` contient `.terminal-scrollback` avec `padding-bottom: calc(112px + 48px + var(--cg-space-3) + env(safe-area-inset-bottom))`.
- [ ] Même règle : `scroll-padding-bottom` avec la même valeur.
- [ ] Non-régression : le rail « Vos questions » et `.terminal-scrollback--railed` restent rendus en terminal interactif avec questions (tests existants l.315/329 conservés verts).

### Tests d'intégration

- Sans objet (pas d'endpoint). Vérification manuelle possible à 390 px : dérouler le fil en bas, le titre « Vos questions » + badge Q1 restent au-dessus des FAB et tappables.

### Isolation utilisateur

- [ ] Non applicable — raison : correctif CSS display-only, aucun accès aux données, aucun `user_id`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-158-19` — done (FAB remontés à 112 px + réserve initiale).
- `SF-158-20` — done (mini-bouton icône 48 px).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- Valeur retenue exactement `calc(112px + 48px + var(--cg-space-3) + env(safe-area-inset-bottom))` (borne basse imposée par le cadrage). Comme le composeur est **en flux** et consomme déjà ~112 px sous le fil, cette borne **sur-couvre** volontairement la bande réellement recouverte : une réserve trop généreuse n'ajoute qu'un peu de vide défilable en bas (inoffensif, c'est l'intention), une réserve trop courte laisse le chevauchement — on privilégie donc la borne haute du cadrage.
- Observation consignée : dans le CSS actuel, `.terminal-qrail` est placé en **tête** de colonne (`order: -1`, feuille questions) et non en bas ; le correctif dégage néanmoins toute la bande basse du conteneur qui défile, ce qui couvre le chevauchement quel que soit le contenu en bas (fin de fil **ou** rail). L'ordre du rail n'est pas modifié (hors périmètre).
