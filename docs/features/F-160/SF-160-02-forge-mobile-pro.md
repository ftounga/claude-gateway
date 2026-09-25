# Mini-spec — F-160 / SF-160-02 — Forge mobile pro

## Identifiant

`F-160 / SF-160-02`

## Feature parente

`F-160` — Refonte mobile pro : Chat, Forge, Vigie

## Statut

`in-progress`

## Date de création

2026-09-26

## Branche Git

`feat/SF-160-02-forge-mobile-pro`

---

## Objectif

Porter l'écran **Forge (postes/projets)** à un rendu mobile de niveau produit conforme à la maquette
validée — barre de recherche pleine largeur, **cartes de postes pleine largeur empilées** (nom,
statut, nombre de projets, dernière activité), en-tête « Forge » compact — **sous 819 px uniquement**,
le desktop (≥ 820 px) restant rigoureusement identique.

---

## Comportement attendu

### Cas nominal

Sous 819 px (`@media (max-width: 819px)`), l'écran `/forge` (liste) présente :

1. Un **en-tête « Forge » compact** (`.forge-fleet`) : titre réduit, KPIs lisibles qui enroulent,
   boutons d'action atteignables au pouce (≥ 44 px). Aucun bouton/fonction retiré.
2. Une **barre de recherche pleine largeur** (`.forge-rail__search`) : cible ≥ 44 px, filet
   `--cg-divider`, focus `--cg-orange`.
3. Une **pile de cartes de postes pleine largeur** (`.forge-rail__host`) : surface `--cg-surface`,
   filet `--cg-divider`, rayon, retrait confortable ; **nom** du poste (ellipsé), **statut daté**
   (« En ligne · vu il y a … » / « Hors ligne · vu il y a … », avec la pastille de vie §5/§11),
   **nombre de projets** ; **tap** sur la carte ouvre le poste (binding `(click)` existant inchangé).

Les données et bindings Angular ne changent pas (display-only, restyle SCSS mobile).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Nom de poste très long / ligne insécable | La carte **clippe/enroule**, jamais de scroll horizontal de page (assert `scrollWidth <= clientWidth` à 360/400 px) |
| Viewport desktop (≥ 820 px) | **Aucune** règle F-160 ne s'applique — rendu strictement identique à avant (test de non-régression) |
| Aucun poste ne correspond au filtre | Message vide existant (`.forge-rail__empty`) restylé lisible, inchangé sur le fond |

---

## Critères d'acceptation

- [ ] Sous 819 px, la colonne des postes est une **pile de cartes pleine largeur** (nom + statut daté
      + nombre de projets), la barre de recherche est **pleine largeur** et l'en-tête « Forge » est
      **compact**.
- [ ] Toutes les cibles tactiles (carte, recherche, boutons d'en-tête, connecter, repli) font
      **≥ 44 px** de haut sous 819 px.
- [ ] **Aucun scroll horizontal** : `host.scrollWidth <= host.clientWidth` à **360 px et 400 px** avec
      un contenu volontairement large (mesure en DOM réel, ChromeHeadless).
- [ ] **Desktop ≥ 820 px inchangé** : toute règle nouvelle vit sous `max-width: 819px` (test CSSOM),
      et le rendu calculé de `.forge-rail` / `.forge-rail__host` à ≥ 1024 px est celui d'avant F-160.
- [ ] **Charte stricte** : jetons `--cg-*` uniquement, **aucune couleur hex nouvelle** ; polices
      Inter / JetBrains Mono ; gouttières ≥ 16 px (réutilise `.page` / `_breakpoints`).
- [ ] Budget **< 12 ko** par feuille de style touchée (mesuré).
- [ ] Les specs Angular existants de la Forge (`postes.component.spec.ts`, `forge-rail.component.spec.ts`)
      restent **verts sans modification de leur logique**.
- [ ] `npm run build` **vert**.

---

## Périmètre

### Hors scope (explicite)

- Toute modification du **desktop** (≥ 820 px reste identique — règle absolue).
- Toute **couleur / police nouvelle**. La surface **sombre** de la maquette n'est **pas** reprise sur
  la Forge : `--cg-navy-2` est la surface **de terminal** (DESIGN_SYSTEM §13) et la Forge est définie
  sur surface claire (§16). On reproduit la **FORME** de la maquette (silhouette des cartes, recherche,
  en-tête compact, méta en mono) dans la **palette charte de la Forge** — divergence chromatique avec
  la maquette **assumée et notée** (voir Notes/décisions D-A).
- Toute **logique métier**, tout **binding** Angular (`*ngIf` / `*ngFor` / `(click)` / `[attr]`), toute
  route, tout endpoint, toute migration.
- L'onglet **Chat** (SF-160-01) et l'onglet **Vigie** (SF-160-03).
- Les fichiers **partagés** avec la Vigie (`_forge-layout-shell.scss`, `_forge-layout.scss`) : non
  touchés (parallélisme SF-160-03).

---

## Technique

### Endpoint(s)

Aucun (pur frontend, display-only).

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular (restyle mobile uniquement)

- `postes/postes.component.scss` — en-tête `.forge-fleet` compact + fond de la liste (mobile).
- `postes/forge-rail/forge-rail.component.scss` — barre de recherche + cartes de postes (mobile).

> `postes.component.{ts,html}` : **non modifiés** (aucun changement de logique/binding).
> `forge-project-tile` : hors de la liste des postes (tuiles de projets du détail) — non requis pour
> cet écran de liste ; non touché.

---

## Préoccupations transversales

- **Navigation / routing** : **non touchée**. Aucune route, guard ni redirection ajoutée/retirée ;
  même URL aux deux tailles. Le `(click)` d'ouverture de poste et `routerLink` existants sont intacts.
  Composants concernés vérifiés : `postes.component.html` (aucune modification), `forge-rail.component.html`
  (aucune modification).
- **Auth / Principal / tenant / plans / limites** : **non touchés** (pur affichage, aucun accès données).
- **Design system (transverse)** : composants impactés listés ci-dessus ; mapping charte respecté
  (jetons `--cg-*`), aucune couleur nouvelle.

---

## Plan de test

### Tests (frontend, ChromeHeadless)

Nouveau spec `postes/forge-rail/forge-rail-mobile.spec.ts` :

- [ ] **Anti-scroll-x réel (D4)** — monte `app-forge-rail` avec un poste au **nom très long** + une
      sonde mono **insécable** ; applique au layout réel les règles `max-width: 819px` de la feuille
      forge-rail ; à **360 px** puis **400 px** : `host.scrollWidth <= host.clientWidth`.
- [ ] **Sanity (mesure réelle)** — **sans** les règles mobile, la même sonde insécable **fait**
      déborder (prouve que l'assertion mesure un vrai layout, pas une tautologie).
- [ ] **Non-régression desktop — CSSOM (D3)** — chaque règle F-160 (fond de liste, carte bordée,
      recherche pleine hauteur) vit **sous `max-width: 819px`** ; aucune ne fuit hors media.
- [ ] **Non-régression desktop — calculé (D3)** — à la largeur Karma (≥ 1024 px), `.forge-rail` garde
      son fond clair d'origine et `.forge-rail__host` n'a **ni le filet ni le rayon** de la carte
      mobile (structure desktop inchangée).

### Isolation utilisateur

Non applicable — aucune donnée n'est lue ni écrite (restyle CSS pur, display-only).

---

## Dépendances

### Subfeatures bloquantes

- Aucune. SF-160-01 (Chat) est prioritaire mais **SF-160-02 et SF-160-03 sont indépendantes et
  démo-ables isolément** (CADRAGE F-160 §4). Réutilise les fondations F-159 (`_breakpoints.scss`,
  `.page`, `.table-scroll`) déjà mergées.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D-A — Palette charte de la Forge, pas la surface de terminal.** La maquette est sombre
  (`#0B1020` / `#121a30`). Le CADRAGE §2 mappe ces hex sur `--cg-navy` / `--cg-navy-2`, mais
  `--cg-navy-2` est **normativement la surface d'un terminal en lecture seule** (DESIGN_SYSTEM §13) et
  la Forge est définie sur **surface claire** (§16). Reprendre le navy sur la Forge créerait une
  **incohérence §13 ↔ §16** et exigerait de re-thématiser en sombre les contrôles Angular Material de
  l'en-tête (fragile, risque de régression, contraire à D8 « ne pas casser Angular »). **Décision** :
  on reproduit la **FORME** validée par le PO (cartes pleine largeur empilées, recherche, en-tête
  compact, méta en mono) dans la **palette charte de la Forge** (surface claire, filets `--cg-divider`,
  accent `--cg-orange`) — 100 % jetons `--cg-*`, zéro couleur nouvelle. La **divergence chromatique**
  avec la maquette (claire vs sombre) est **assumée** et signalée ici et en PR, conformément à la règle
  « Refuser/​signaler explicitement plutôt que laisser passer silencieusement ».
- **D8 — Restyle, pas refonte logique.** Uniquement du SCSS sous 819 px ; aucun changement HTML,
  aucun binding modifié.
- **Budget / feuille dédiée** : les deux feuilles touchées restent bien sous 12 ko après ajout — pas
  de feuille dédiée nécessaire (CADRAGE D5).
