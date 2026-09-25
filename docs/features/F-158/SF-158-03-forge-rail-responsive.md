# Mini-spec — F-158 / SF-158-03 forge-rail responsive

## Identifiant

`F-158 / SF-158-03`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-03-forge-rail-responsive`

---

## Objectif

Rendre la colonne des postes (`app-forge-rail`) confortable et tactile sur téléphone (~400 px) :
sous 819 px, chaque ligne de poste (grille `32px minmax(0,1fr) auto`) reste lisible, les cibles
tactiles (ligne de poste, recherche, repli « Fermées », bouton « Connecter ») font ≥ 44 px, et rien
ne provoque de scroll horizontal. **Le desktop (≥ 820 px) reste strictement inchangé.**

---

## Comportement attendu

### Cas nominal

- **≥ 820 px (desktop, inchangé)** : la colonne s'affiche exactement comme aujourd'hui (grille de ligne
  `32px minmax(0,1fr) auto`, densité `--cg-space-1`/`--cg-space-2`, bordure droite).
- **≤ 819 px (téléphone)** : la colonne occupe déjà toute la largeur (coquille F-151,
  `_forge-layout-shell.scss:148` → 1 colonne, `border-right` → `border-bottom`). En plus :
  - Chaque ligne de poste (`.forge-rail__host`) a une hauteur minimale de 44 px et un espacement
    inter-lignes suffisant pour être atteignable au doigt sans se tromper de voisine.
  - La barre de recherche (`.forge-rail__search`), le repli « Fermées » (`.forge-rail__group--toggle`)
    et le bouton « Connecter un poste » (`.forge-rail__connect`) font tous ≥ 44 px de haut.
  - La grille de ligne reste `32px minmax(0,1fr) auto` (elle tient largement sur 400 px) ; le libellé
    et le méta s'ellipsent déjà (`min-width:0`), la 3ᵉ colonne (`auto` : pastille « En attente » ou
    compteur) ne provoque aucun débordement.
  - **Aucun scroll horizontal de page** à 400 px.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Aucun poste (liste vide) | L'état vide existant (`.forge-rail__empty`) s'affiche ; la recherche et « Connecter » restent des cibles ≥ 44 px. |
| Redimensionnement ≥ 820 px | Les règles desktop reprennent (densité d'origine) ; aucun état incohérent — tout est en CSS sous `@media (max-width: 819px)`. |

Aucun cas d'erreur réseau/HTTP nouveau : SF purement d'affichage, aucune logique métier touchée.

---

## Critères d'acceptation

- [ ] À ≥ 820 px, le rendu est **identique** à aujourd'hui (aucune règle hors `@media (max-width: 819px)`).
- [ ] À ≤ 819 px, chaque ligne de poste (`.forge-rail__host`) fait ≥ 44 px de haut.
- [ ] À ≤ 819 px, recherche, repli « Fermées » et bouton « Connecter » font ≥ 44 px de haut.
- [ ] Aucun scroll horizontal de page à 400 px (la grille de ligne tient, le texte s'ellipse).
- [ ] Charte respectée : jetons `--cg-*` uniquement, aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] Budget SCSS F-117 respecté : `forge-rail.component.scss` reste sous le budget d'erreur (12 kB) ;
      règles mobile dans la feuille du composant (pas de dépassement du budget dur → pas de feuille
      dédiée nécessaire, cf. D5).
- [ ] `npm run build` vert ; tests de composant verts.

---

## Plan de test minimal

- **Unitaire / composant (`forge-rail.component.spec.ts`)** :
  - Non-régression : les tests existants (groupes, statut daté, compteur, mise à jour runner, TJM,
    cumul, pastille d'attente, sélection, émissions) restent verts.
  - Ajout : la ligne de poste, la recherche et le bouton « Connecter » sont bien des éléments
    interactifs présents (cibles rendues) — garde-fou structurel des cibles tactiles.
  - Note : les `@media` ne sont pas évaluables en jsdom ; la couverture mobile repose sur le build et
    la revue visuelle du bloc `@media (max-width: 819px)`.
- **Intégration / build** : `npm run build` vert au premier plan.
- **Isolation utilisateur** : sans objet — SF purement d'affichage, aucun accès aux données, aucun
  endpoint touché ; l'isolation `user_id` en vigueur est inchangée.

---

## Tables / endpoints / composants impactés

- **Tables** : aucune.
- **Endpoints** : aucun.
- **Composants** :
  - `frontend/src/app/postes/forge-rail/forge-rail.component.scss` (bloc `@media (max-width: 819px)`)
  - `frontend/src/app/postes/forge-rail/forge-rail.component.spec.ts` (garde-fou des cibles)
  - `forge-rail.component.html` : **non modifié** — les règles ciblent les classes existantes ; pas de
    changement de gabarit nécessaire.

---

## Décision technique (documentée)

- **Feuille du composant plutôt que feuille dédiée (D5 / F-117)** : `forge-rail.component.scss` est
  aujourd'hui **sous** le budget d'avertissement (non listé au build) ; l'ajout du bloc mobile le garde
  très en deçà du budget d'**erreur** (12 kB), le seul budget dur du dépôt (p. ex. `postes.component.scss`
  vit à 9,87 kB dans sa propre feuille). Aucun **dépassement** du budget dur → **pas de feuille dédiée**,
  conformément à D5. Cela colle aussi à l'impact déclaré au cadrage (`{scss,html}`, sans `.ts`).

---

## Préoccupations transversales

- **Navigation / routing** : **non touchée** — aucune route, aucun guard, aucune redirection, aucun
  repli d'affichage nouveau (la mise en pleine largeur de la colonne sous 819 px est déjà assurée par la
  coquille F-151 ; cette SF n'ajuste que densité et cibles tactiles).
- **Auth / Principal**, **Contexte tenant**, **Plans / limites** : non touchées (SF purement d'affichage).

---

## Périmètre — Hors scope (explicite)

- Le desktop (≥ 820 px) : strictement inchangé.
- La coquille (`_forge-layout-shell.scss`) déjà rendue responsive par F-151 : non retouchée.
- Les autres écrans de F-158 (chat, postes, gouvernance, etc.) : non touchés (agents parallèles).
- Toute logique métier, tout endpoint, toute migration, tout DTO, tout protocole runner.
- Mise à jour de `docs/PRODUCT_SPEC.md` (étape 6, groupée séparément).
