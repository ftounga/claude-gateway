# Mini-spec — [F-158 / SF-158-04] Gouvernance responsive

## Identifiant

`F-158 / SF-158-04`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-04-gouvernance-responsive`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous ~819 px, l'écran Gouvernance passe sa grille de paquets (catalogue) en une seule colonne
pleine largeur, sans aucun scroll horizontal, avec des cibles tactiles ≥ 44 px sur tous les
gestes de l'écran ; le desktop (≥ 820 px) reste strictement inchangé.

---

## Comportement attendu

### Cas nominal

- Desktop (≥ 820 px) : la grille du catalogue garde
  `repeat(auto-fill, minmax(min(320px, 100%), 1fr))` — aucun changement.
- Téléphone (< 819 px) : la grille `.gouvernance__catalog` bascule en `minmax(0, 1fr)` (une
  colonne). Les cartes de paquet (`.gouvernance__package`) prennent toute la largeur disponible ;
  aucun débordement horizontal de la page.
- Les gestes de l'écran (retenir/ne plus retenir, appliquer, désactiver, activer un paquet retenu,
  les deux liens du refus d'accès, le dépliant « fichiers déposés ») atteignent ≥ 44 px de haut
  sous 819 px pour rester atteignables au pouce.
- Les gestes d'une activation (`.gouvernance__activation-actions`) passent à la ligne au lieu de
  se serrer, jamais de débordement horizontal.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Refus d'accès (403) | La carte d'avis et ses deux liens restent lisibles pleine largeur, cibles ≥ 44 px, aucun scroll-x |
| Panne réseau | La carte d'avis et le bouton « Réessayer » restent pleine largeur et tactiles |
| Catalogue vide | Le message « Aucun paquet publié » reste lisible pleine largeur, aucun scroll-x |
| Chemin de fichier long | Contraint par `minmax(0, 1fr)` + `word-break` existant sur `code` ; pas de débordement de la grille |

---

## Critères d'acceptation

- [ ] Sous 819 px, `.gouvernance__catalog` s'affiche en une seule colonne (cartes empilées pleine largeur).
- [ ] Aucune carte n'est plus large que le viewport à ~400 px ; aucun scroll horizontal de la page.
- [ ] Les gestes de l'écran (liens d'avis, boutons appliquer/désactiver/activer/retenir, dépliant
      fichiers) ont une hauteur ≥ 44 px sous 819 px.
- [ ] À ≥ 820 px, le rendu est identique à l'existant (aucune régression desktop).
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; jetons `--cg-*` uniquement (aucune couleur ajoutée).
- [ ] Le build frontend passe (`npm run build`) — vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) : inchangé.
- Les autres écrans de F-158 (chat, postes, forge-rail, onboarding, rapports, admin/usage,
  space-pitch, terminal Atelier) — chacun est une SF distincte.
- Les boîtes de dialogue enfants (`deactivate-dialog`, `deposit-preview-dialog`, `file-viewer`) —
  hors périmètre de cette SF (feuilles propres, non citées par le CADRAGE).
- Toute logique métier, tout endpoint, toute migration, tout DTO. Pur frontend, display-only.
- Toute modification de `docs/PRODUCT_SPEC.md` (étape 6 groupée séparément).

---

## Technique

### Endpoint(s)

Aucun. Pur frontend.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- `GovernanceComponent` (`governance/governance.component.{scss,html}`) — ajout d'un bloc
  `@media (max-width: 819px)` dans la feuille du composant ; HTML inchangé (la structure porte déjà
  les cartes pleine largeur et les listes en flex-wrap). Aucune nouvelle feuille SCSS (règles
  minimes ; voir note budget F-117).

---

## Plan de test

### Tests unitaires (composant)

- [ ] `governance.component.spec.ts` — le catalogue rend une carte par paquet (test existant conservé,
      non-régression du markup `.gouvernance__catalog`).
- [ ] Ajout : la classe `.gouvernance__catalog` est présente et porte les cartes `.gouvernance__package`
      (garde-fou markup pour le patron responsive).

### Tests d'intégration

- Non applicable (pur affichage CSS ; la responsivité est vérifiée par le build + revue du `@media`).

### Isolation workspace

- [x] Non applicable — raison : aucun accès données, pur affichage CSS. Le test d'isolation JWT
      existant du composant reste vert (non touché).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF indépendante et démo-able isolément, cf. CADRAGE F-158 §4).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Navigation / routing** : non touchée. Aucun nouveau chemin, aucun guard, aucune redirection ;
  la même URL `/gouvernance` sert les deux tailles (display-only).
- **Auth / tenant / plans** : non touchés (pur affichage). Le test d'isolation JWT du composant est
  conservé.

---

## Notes et décisions

- **D1 (CADRAGE)** : réutilisation du point de rupture 819 px et de la forme « colonnes fixes →
  1 colonne » du DESIGN_SYSTEM (`_forge-layout-shell.scss:148`, `DESIGN_SYSTEM.md:686`). Aucune
  nouvelle route.
- **D4** : jamais de scroll-x → `minmax(0, 1fr)` (et non `1fr` seul) pour empêcher un item de grille
  de déborder à cause de son contenu (`min-width:auto` par défaut).
- **Cibles ≥ 44 px** : `min-height: 44px` sous 819 px sur les boutons/liens d'action et le
  `summary` du dépliant de fichiers (qui est cliquable).
- **Budget SCSS (F-117 / D5)** : `governance.component.scss` est **déjà** au-dessus du budget de 4 kB
  sur `origin/main` (4852 octets — comme d'autres composants du dépôt) : WARNING non bloquant, build
  vert. Les règles mobile ajoutées sont minimes (~0,7 kB) et ne **causent pas** le dépassement ; une
  feuille dédiée ne ramènerait pas la feuille principale sous le budget (dépassement pré-existant et
  indépendant de F-158). Décision : règles conservées dans la feuille du composant, dépassement
  pré-existant tracé ici comme item non bloquant (revue), cohérent avec le précédent SF-158-02.
