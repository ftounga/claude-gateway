# Mini-spec — F-160 / SF-160-03 — Vigie mobile pro

## Identifiant

`F-160 / SF-160-03`

## Feature parente

`F-160` — Refonte mobile pro : Chat, Forge, Vigie

## Statut

`in-progress`

## Date de création

2026-09-26

## Branche Git

`feat/SF-160-03-vigie-mobile-pro`

---

## Objectif

Porter l'onglet **Radar de la Vigie** (résumé du matin + engagements) à un rendu mobile de niveau
produit conforme à la maquette validée — **carte « résumé du matin »** en tête (filet gauche
`--cg-orange`), puis sections **« À faire par moi »** et **« J'attends des autres »** en **cartes
d'engagements** empilées (sujet + pastille §5, ligne d'engagement + méta mono « depuis 1 j » /
« relancé 2× ») — **sous 819 px uniquement**, le desktop (≥ 820 px) restant rigoureusement identique.

---

## Comportement attendu

### Cas nominal

Sous 819 px (`@media (max-width: 819px)`), l'onglet Radar d'un client (`app-radar-board`) présente :

1. Une **carte « résumé du matin »** (`radar-brief`) : surface `--cg-surface`, **filet gauche
   `--cg-orange`** (silhouette « digest » de la maquette, jamais un aplat §8), rayon confortable ;
   le titre du jour et les phrases « ce qui a bougé » lisibles ; les compteurs (`__tally`) enroulent.
   Empilée en une colonne (résumé d'abord, couverture ensuite).
2. Des **sections d'engagements** (`radar-columns`) empilées en **une seule colonne** sous 819 px :
   « À faire par moi » puis « Sujets en cours » puis « J'attends des autres », chacune en **carte**
   (surface `--cg-surface`, filet `--cg-divider`, rayon), en-tête de section compact.
3. Chaque **engagement** (`__item`) : le titre du sujet, la **pastille de statut §5** (`radar-state`)
   inchangée, la ligne d'engagement, la **méta en mono** (source, « dans « sujet » », moment) qui
   **enroule** ; les gestes (`__acts`) atteignables au pouce (boutons ≥ 44 px de haut).
4. Les cibles tactiles (boutons de gestes, ordre des sujets, synchroniser) font **≥ 44 px** sous 819 px.

Les données et bindings Angular ne changent pas (display-only, restyle SCSS mobile).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Sujet / engagement au libellé très long, ligne insécable | La carte **clippe/enroule**, jamais de scroll horizontal de page (assert `scrollWidth <= clientWidth` à 360/400 px) |
| Viewport desktop (≥ 820 px) | **Aucune** règle F-160 ne s'applique — rendu strictement identique à avant (test de non-régression CSSOM + calculé) |
| Aucun engagement dans une section | Message vide existant (`__empty`) restylé lisible, inchangé sur le fond |

---

## Critères d'acceptation

- [ ] Sous 819 px, le résumé du matin est une **carte à filet gauche `--cg-orange`** en tête, et les
      colonnes du Radar sont empilées en **une seule colonne** de cartes d'engagements (sujet +
      pastille §5 + ligne + méta mono).
- [ ] Toutes les cibles tactiles (gestes d'engagement, ordre des sujets, synchroniser) font **≥ 44 px**
      de haut sous 819 px.
- [ ] **Aucun scroll horizontal** : `host.scrollWidth <= host.clientWidth` à **360 px et 400 px** avec
      un contenu volontairement large (mesure en DOM réel, ChromeHeadless), sur `radar-brief` **et**
      `radar-columns`.
- [ ] **Desktop ≥ 820 px inchangé** : toute règle nouvelle vit sous `max-width: 819px` (test CSSOM),
      et le rendu calculé de `.radar-columns` (grille 3 colonnes) et `.radar-brief` à ≥ 1024 px est
      celui d'avant F-160.
- [ ] **Charte stricte** : jetons `--cg-*` uniquement, **aucune couleur hex nouvelle** dans les règles
      F-160 ; polices Inter / JetBrains Mono ; gouttières ≥ 16 px (fondations F-159).
- [ ] Budget **< 12 ko** par feuille de style touchée (mesuré).
- [ ] Les specs Angular existants de la Vigie/Radar (`radar-brief.component.spec.ts`,
      `radar-columns.component.spec.ts`, `vigie.component.spec.ts`) restent **verts sans modification
      de leur logique**.
- [ ] `npm run build` **vert**.

---

## Périmètre

### Hors scope (explicite)

- Toute modification du **desktop** (≥ 820 px reste identique — règle absolue).
- Toute **couleur / police nouvelle**. La surface **sombre** de la maquette (`#0B1020` / `#121a30`)
  n'est **pas** reprise sur la Vigie : `--cg-navy-2` est la surface **de terminal** (DESIGN_SYSTEM
  §13) et la Vigie emprunte la forme maître–détail de la **Forge sur surface claire** (§16). On
  reproduit la **FORME** de la maquette (carte digest à filet gauche, sections d'engagements en
  cartes, méta mono, pastilles) dans la **palette charte claire de la Vigie** — divergence chromatique
  avec la maquette **assumée et notée** (voir Notes/décisions D-A), **cohérente avec SF-160-02**.
- Toute **logique métier**, tout **binding** Angular (`*ngIf` / `*ngFor` / `(click)` / `[routerLink]`),
  toute route, tout endpoint, toute migration.
- L'onglet **Chat** (SF-160-01) et l'onglet **Forge** (SF-160-02).
- Les fichiers **partagés** avec la Forge (`vigie-forge-shell.scss`, `vigie-forge-detail.scss`,
  `postes/_forge-layout*.scss`, `forge-rail`) : **non touchés** (parallélisme SF-160-02, et
  l'en-tête `.forge-fleet` de la Vigie est hors de cette SF de contenu Radar).
- Les autres onglets du client (Conversations, Réunions, Personnes, Pages, Présentations) et les
  autres composants Radar (`radar-news`, `radar-subject-page`, `radar-directory`, dialogs).

---

## Technique

### Endpoint(s)

Aucun (pur frontend, display-only).

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular (restyle mobile uniquement)

- `vigie/radar/radar-brief.component.scss` — carte « résumé du matin » à filet gauche `--cg-orange`,
  compteurs qui enroulent, cibles ≥ 44 px (mobile < 819 px).
- `vigie/radar/radar-columns.component.scss` — colonnes empilées en une colonne, cartes d'engagements,
  méta mono qui enroule, gestes ≥ 44 px (mobile < 819 px).

> `radar-brief.component.{ts,html}` et `radar-columns.component.{ts,html}` : **non modifiés** (aucun
> changement de logique/binding/structure). `radar-board`, `vigie.component`, feuilles partagées : non
> touchés.

---

## Préoccupations transversales

- **Navigation / routing** : **non touchée**. Aucune route, guard ni redirection ajoutée/retirée ;
  même URL aux deux tailles. Les `routerLink` d'engagement/sujet et `(click)` de gestes existants sont
  intacts. Composants concernés vérifiés : `radar-brief.component.html` (aucune modification),
  `radar-columns.component.html` (aucune modification).
- **Auth / Principal / tenant / plans / limites** : **non touchés** (pur affichage, aucun accès
  données ; isolation `user_id` hors sujet par construction).
- **Design system (transverse)** : composants impactés listés ci-dessus ; mapping charte respecté
  (jetons `--cg-*`), aucune couleur nouvelle, pastilles §5 inchangées.

---

## Plan de test

### Tests (frontend, ChromeHeadless)

Nouveaux specs `vigie/radar/radar-brief-mobile.spec.ts` et `vigie/radar/radar-columns-mobile.spec.ts`
(modèle imposé `terminal-largeur-reelle.spec.ts` + `fondations-responsive.spec.ts`) :

- [ ] **Anti-scroll-x réel (D4)** — monte le composant avec un engagement/sujet au **libellé très
      long** + une sonde mono **insécable** ; ré-applique au layout réel les règles `max-width: 819px`
      de la feuille du composant (sans la garde media) ; à **360 px** puis **400 px** :
      `host.scrollWidth <= host.clientWidth`.
- [ ] **Sanity (mesure réelle)** — **sans** les règles mobile, la même sonde insécable **fait**
      déborder (prouve que l'assertion mesure un vrai layout, pas une tautologie).
- [ ] **Non-régression desktop — CSSOM (D3)** — chaque règle F-160 (carte à filet gauche, colonne
      unique, carte d'engagement) vit **sous `max-width: 819px`** ; aucune ne fuit hors media ; aucune
      n'introduit de couleur hex/rgb en dur (jetons `--cg-*`).
- [ ] **Non-régression desktop — calculé (D3)** — à la largeur Karma (≥ 1024 px, media inactive),
      `.radar-columns` garde sa **grille 3 colonnes** d'origine et `.radar-brief` son rendu d'avant
      F-160 (structure desktop inchangée).

### Isolation utilisateur

Non applicable — aucune donnée n'est lue ni écrite (restyle CSS pur, display-only).

---

## Dépendances

### Subfeatures bloquantes

- Aucune. SF-160-02 et SF-160-03 sont **indépendantes et démo-ables isolément** (CADRAGE F-160 §4).
  Réutilise les fondations F-159 (`_breakpoints.scss`, `.page`, `.table-scroll`) déjà mergées.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D-A — Palette charte claire de la Vigie, pas la surface de terminal** (cohérent avec SF-160-02
  D-A). La maquette est sombre (`#0B1020` / `#121a30`). Le CADRAGE §2 mappe ces hex sur
  `--cg-navy` / `--cg-navy-2`, mais `--cg-navy-2` est **normativement la surface d'un terminal en
  lecture seule** (DESIGN_SYSTEM §13) et la Vigie emprunte la forme maître–détail de la **Forge, sur
  surface claire** (§16). Reprendre le navy sur la Vigie créerait une **incohérence §13 ↔ §16** et
  exigerait de re-thématiser en sombre des contrôles Angular Material (fragile, contraire à D8). La
  Forge (SF-160-02) a tranché de même. **Décision** : on reproduit la **FORME** validée par le PO
  (carte digest à filet gauche, sections d'engagements en cartes, méta mono, pastilles) dans la
  **palette charte claire de la Vigie** (surface `--cg-surface`, filets `--cg-divider`, accent
  `--cg-orange`) — 100 % jetons `--cg-*`, zéro couleur nouvelle. La **divergence chromatique** avec la
  maquette (claire vs sombre) est **assumée** et signalée ici et en PR.
- **D8 — Restyle, pas refonte logique.** Uniquement du SCSS sous 819 px ; aucun changement HTML,
  aucun binding modifié. Les pastilles d'état §5 (`radar-state`) restent inchangées.
- **Breakpoints existants conservés.** `radar-brief` (`max-width: 859px`) et `radar-columns`
  (`max-width: 1019px` / `639px`) portent des paliers antérieurs à F-159 : **non touchés** (hors
  périmètre). F-160 ajoute **un seul** bloc `max-width: 819px` par feuille, qui prend le relais du
  rendu mobile sans supprimer l'existant.
- **Budget / feuille dédiée** : les deux feuilles touchées restent bien sous 12 ko après ajout — pas
  de feuille dédiée nécessaire (CADRAGE D5), même choix que SF-160-02.
