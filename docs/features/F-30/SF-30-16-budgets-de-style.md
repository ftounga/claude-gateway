# Mini-spec — F-30 / SF-30-16 Les budgets de style

## Identifiant

`F-30 / SF-30-16`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-30-16-budgets-de-style`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Repasser sous le budget de style Angular (`anyComponentStyle`, avertissement à 4 kB) les feuilles
`vigie.component.scss` et `atelier-files.component.scss`, en **découpant chaque feuille par ce
qu'elle porte** (patron F-83/F-89), **sans aucun changement visuel**.

---

## Comportement attendu

### Cas nominal

- **Constat** : `ng build` émet un avertissement de budget sur deux feuilles :
  - `src/app/vigie/vigie.component.scss` : total 6.55 kB (dépassement 2.55 kB). La feuille propre
    est petite (~1.15 kB) ; l'essentiel du poids vient de `@use '../postes/forge-layout'`, la forme
    maître–détail partagée (5.24 kB compilés).
  - `src/app/atelier/files/atelier-files.component.scss` : total 5.22 kB (dépassement 1.22 kB).
    Feuille autonome, sans `@use`.
- **Traitement** :
  - `_forge-layout.scss` est **découpé par concern** en deux partiels — `_forge-layout-shell.scss`
    (l'ossature : `.postes`, `.forge-fleet`, `.forge-split`) et `_forge-layout-detail.scss` (le poste
    ouvert : `.poste`, `.badge`). `_forge-layout.scss` **devient un baril** (`@forward` des deux
    partiels) : les consommateurs actuels (`postes.component.scss`) restent **inchangés** et produisent
    un CSS **strictement identique** (vérifié octet à octet).
  - `vigie.component.ts` passe de `styleUrl` à `styleUrls` : sa feuille propre + deux feuilles minces
    (`vigie-forge-shell.scss`, `vigie-forge-detail.scss`) qui font chacune `@use` d'un partiel. Chaque
    fichier repasse sous 4 kB.
  - `atelier-files.component.scss` est scindé par concern : la feuille garde l'ossature (barre haute,
    barre d'outils, arbre) et une nouvelle feuille `atelier-files-viewer.component.scss` porte l'aperçu
    / l'édition et les éléments Git. `atelier-files.component.ts` liste les deux via `styleUrls`.
- **Sortie** : `ng build` **ne produit plus d'avertissement de budget** sur ces deux feuilles ni sur
  aucun des nouveaux fichiers. Le rendu à l'écran est **identique** (même sélecteurs, mêmes déclarations,
  ordre de cascade préservé).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Une des feuilles issues du découpage dépasse encore 4 kB | Le découpage est affiné (concern plus fin) jusqu'à repasser sous le budget — vérifié avant push. |
| Le CSS compilé de `postes.component.scss` change après la mise en baril | BLOQUANT — le baril `@forward` doit produire un CSS octet-à-octet identique ; sinon régression visuelle sur la Forge. |
| L'ordre de cascade des feuilles multiples change le rendu | Les `styleUrls` listent les feuilles dans l'ordre d'origine (forge d'abord, propre ensuite) pour préserver la cascade. |

---

## Critères d'acceptation

- [ ] `ng build` ne produit **aucun** avertissement de budget sur `vigie.component.scss`,
      `atelier-files.component.scss` ni sur les nouvelles feuilles issues du découpage.
- [ ] Chaque feuille de composant issue du découpage compile sous **4 kB** (`anyComponentStyle`).
- [ ] Le CSS compilé de `postes.component.scss` est **inchangé** (baril `@forward` vérifié identique).
- [ ] **Aucun changement visuel** : les mêmes sélecteurs et déclarations sont émis, dans le même ordre
      de cascade, pour la Vigie et l'explorateur de fichiers.
- [ ] Aucune couleur hors charte introduite (déplacement de règles existantes uniquement).
- [ ] `npm test -- --watch=false` et `npm run build` verts.

---

## Périmètre

### Hors scope (explicite)

- Les autres feuilles qui dépassent le budget (`postes.component.scss`, `atelier-terminal*.scss`,
  `radar-subject-page.component.scss`, `landing`, `chat`, `billing`, dialogues runner…) : **hors
  périmètre** de SF-30-16, elles resteront avertissables et seront traitées ailleurs.
- Modifier la valeur du budget dans `angular.json` : non — on repasse **sous** le budget existant.
- Toute modification de comportement, de template HTML ou de logique TypeScript autre que le passage
  `styleUrl` → `styleUrls`.

---

## Technique

### Endpoint(s)

Aucun — subfeature purement frontend (feuilles de style).

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `VigieComponent` — `styleUrl` → `styleUrls` (feuille propre + deux feuilles minces `@use` des
  partiels forge-layout).
- `AtelierFilesComponent` — `styleUrl` → `styleUrls` (ossature + aperçu/édition).
- Partiels SCSS : `_forge-layout.scss` (devient baril `@forward`), `_forge-layout-shell.scss`
  (nouveau), `_forge-layout-detail.scss` (nouveau).
- Feuilles : `vigie-forge-shell.scss`, `vigie-forge-detail.scss`, `atelier-files-viewer.component.scss`
  (nouvelles).

---

## Plan de test

### Tests unitaires / composant

- [ ] `vigie.component.spec.ts` — le composant se crée et se rend (feuilles multiples chargées) : vert.
- [ ] `atelier-files.component.spec.ts` — le composant se crée et se rend : vert.
- [ ] Suite frontend complète `npm test -- --watch=false` : verte.

### Test de non-régression de budget / build

- [ ] `npm run build` : aucun avertissement de budget sur les feuilles concernées ni sur les nouvelles.
- [ ] Vérification octet-à-octet : le CSS compilé de `forge-layout` (baril) == celui d'avant découpage.

### Isolation workspace / user_id

- [x] Non applicable — subfeature purement stylistique (CSS), aucun accès aux données.

---

## Préoccupations transversales

- Auth / Principal : non concerné.
- Contexte tenant / `user_id` : non concerné (CSS uniquement).
- Plans / limites : non concerné.
- Navigation / routing : non concerné.
- **Consommateurs partagés de `_forge-layout.scss`** : `postes.component.scss` et `vigie.component.scss`
  sont les seuls consommateurs (vérifié par `grep forge-layout`). Le baril `@forward` garantit que
  `postes` reste inchangé ; `vigie` est traité par cette subfeature.

---

## Notes et décisions

- **Découpe « par ce qu'elle porte »** (patron F-83/F-89) : forge-layout se scinde selon deux concerns
  naturels déjà présents dans le fichier — l'ossature maître–détail vs le poste ouvert.
- **Baril `@forward`** retenu pour ne pas toucher `postes.component.scss` : CSS identique octet à octet,
  zéro risque de régression sur la Forge (mesuré : 5364 octets avant == après).
- Tailles compilées mesurées (compressed) avant push : shell 2.24 kB, detail 3.00 kB, vigie propre
  1.15 kB, atelier ossature 3.34 kB, atelier aperçu 1.75 kB — toutes sous 4 kB.
