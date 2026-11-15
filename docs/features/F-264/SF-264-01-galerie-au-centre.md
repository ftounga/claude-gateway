# Mini-spec — F-264 / SF-264-01 — La galerie au centre, lumineuse

## Identifiant
`F-264 / SF-264-01` · Feature parente `F-264` · Statut `in-progress` · 2026-11-15 · Branche
`feat/SF-264-01-galerie-au-centre` · Cadrage `docs/features/F-264/CADRAGE-F-264-galerie-au-centre.md`

## Objectif
> Quand Jarvis montre une galerie, elle apparaît au milieu de l'écran, au-dessus de tout, grande, lumineuse et animée
> — en 3D (qualité basse comme normale) et en 2D.

## Comportement attendu
- **Calque** : `<app-jarvis-piece-gallery>` déplacé hors de `<main class="jv">`, après le cockpit ; hôte
  `position: fixed; inset: 0; z-index: 7` (au-dessus du cockpit z 3, des étiquettes z 2, sous le plein écran de
  pancarte z 8). Pointeurs : seuls la barre, les cartes et le panneau captent le toucher en éventail.
- **Éventail (3D rendue, ni sobre ni mouvement réduit)** : arc de 110° centré verticalement (perspective 1400 px) ;
  cartes 220 px (260 px dès 1700 px de large ; écart de l'arc ×0,8 sous 1200 px, ×1,25 dès 1700 px) ; sans
  sélection toutes au palier 1 ; voile radial derrière ; socle lumineux elliptique sous l'arc.
- **Pièce choisie** : panneau centré (horizontalement et verticalement dans la zone au-dessus de l'arc), apparition
  T-G3 ; l'arc descend sous le panneau, réduit (×0,62), les autres cartes au palier 0,75, la choisie au palier 1,
  avancée (×1,25, plus ×1,6 qui la faisait chevaucher le panneau).
- **Apparition** (T-G1) : chaque carte jaillit du centre (translation vers sa place, échelle 0,2 → 1, éclat), courbe
  `rebond`, 700 ms, 60 ms d'écart ; ne s'applique qu'à la création (nouvelle galerie), pas à la navigation.
- **Lueur** : reflet du ton (dégradé 22 % → 0), liseré 1 px à 70 %, halo 24 px à 45 % + lueur intérieure ; héros
  2 px, halo 40 px à 60 %. Éclat balayant (4,5 s) et pulsation du socle : **coupés en qualité basse**, sobre et
  mouvement réduit.
- **Grille (2D, sobre, mouvement réduit)** : fond navy 82 % plein écran ; grille centrée (largeur ≤ 1100 px, cartes
  ≥ 200 px) ; pièce choisie : bande défilante des cartes en haut, panneau centré, large (≤ 1000 px).
- **Téléphone** : inchangé (grille 2 colonnes, une pièce plein écran), seulement plein écran fixe.
- **Pancartes (F-248)** : le jeu est centré verticalement au lieu d'être collé en bas ; rien d'autre.
- Réducteur, ordres vocaux, visionneuses : inchangés.

### Cas d'erreur
- Présentation ⇒ rien n'est rendu (inchangé, test existant).
- Galerie de 1 seule pièce ⇒ une carte au centre, sans arc (inchangé dans `fanLayout`).
- Plus de 12 pièces ⇒ « +n » visible au bas de l'arc (dans l'écran).
- Vignette injoignable ⇒ pictogramme du type (inchangé).

## Critères d'acceptation (banc de la vraie page, 1366×768 et 1920×1080, 3D basse, 3D normale, 2D)
- [ ] Centre des cartes entre 30 % et 70 % de la hauteur ; toutes les cartes dans l'écran.
- [ ] Au centre de chaque carte, l'élément au-dessus appartient à la galerie (ni transcriptions ni cockpit).
- [ ] Opacité de chaque carte ≥ 0,7 sans sélection ; après « la deuxième », panneau centré (écart horizontal < 40 px,
      centre vertical entre 25 % et 65 %), autres cartes ≥ 0,45 et dans l'écran.
- [ ] Pas de défilement horizontal, aucune erreur de page.
- [ ] Qualité basse : pas d'animation continue (éclat, socle) — classe `pg--low` testée.
- [ ] Suite front complète verte.

## Contraintes de validation
Aucun champ saisi. Paliers de transparence §23 (1 / 0,75 / 0,45 / 0,18 / 0,04) respectés.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | aucune route ; calque interne à `/jarvis` |

## Impact
Frontend seul : `jarvis-piece-gallery.component.ts`, `jarvis-piece-fan.ts`, `jarvis.component.html` (déplacement
d'un élément), `jarvis-banc.component.ts` (qualité du banc). Aucune table, aucun endpoint.

## Plan de test
- Unitaires : `jarvis-piece-fan` (hero ×1,25, autres 0,75 avec héros, 1 sans) ; `jarvis-piece-gallery.spec`
  (classe `pg--low` en qualité basse, délai d'apparition par carte, décalage de jaillissement = −x).
- Intégration réelle : `frontend/scripts/banc-galerie-centre/scenario.mjs` (6 passages, captures avant/après dans
  `~/.local/share/claude-gateway/captures-galerie/`).
- Isolation utilisateur : sans objet (aucune donnée nouvelle lue).
- Non-régression : suite front complète.

## Hors périmètre
Ancrage dans la scène three.js et filaments `L-piece` ; diaporama ; visionneuses ; écoute, voix, cerveau.
