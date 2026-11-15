# Cadrage court — F-264 — La galerie au centre, lumineuse

**Demande PO du 2026-11-15, après un essai réel** sur `/jarvis` (3D, qualité basse) : quand Jarvis montre une
galerie (vidéos, photos, schémas, pages), elle apparaît « tout en bas, sous les transcriptions, très fade ».
Attendu, dans ses mots : « 1, ça doit être au milieu, 2, ça doit être visible, 3, ça doit être dynamique, 4, ça doit
être lumineux » — holographique, 3D, lumineux. L'écran se pilote à la voix, clic minimal.

## Cause prouvée (banc de la vraie page, `frontend/scripts/banc-galerie-centre/`)
1. **En bas** : la galerie est un calque `position: absolute; inset: 0` **dans** la colonne `.jv` (720 px en 2D,
   hauteur = contenu) ; l'éventail est collé au bas (`margin-top: auto`, `bottom: 24px`). Mesuré : centre des cartes
   à 766 px sur 768 (1366×768), 968 sur 1080 (1920×1080) ; en 2D la grille est en haut de la colonne.
2. **Sous les transcriptions et le cockpit** : la colonne `.jv` est un contexte d'empilement (z-index 1) ; le cockpit
   (z 3) et les étiquettes 3D (z 2) sont des frères posés **après** : 5 cartes sur 7 et le panneau de la pièce
   choisie passent dessous (test `elementFromPoint`).
3. **Fade** : sans sélection les cartes sont à 0,75, avec une pièce choisie toutes les autres tombent à **0,18** ;
   liseré 1 px, halo 12 px à 18 % ; aucune apparition animée.
4. **La pièce choisie** s'affiche en haut (`top: 64px`), pas au centre.

Le cadrage F-237 §8.1 / §8.4 voulait une galerie qui jaillit (T-G1), en arc de 110° face à la caméra, pièce choisie
au centre (T-G3) ; SF-237-05 avait arbitré « provisoirement » pour le calque DOM, sans le centrer.

## Décision
- **Technique : DOM en perspective CSS 3D, pas d'objets three.js** (arbitrage SF-237-05 reconduit, justifié) :
  vignettes, vidéos et pages restent des éléments DOM (lecteur vidéo, iframe bac à sable, SVG) — en three.js il
  faudrait des textures vidéo/iframe, des titres CSS2D et un lancer de rayons pour le toucher ; le calque DOM coûte
  zéro image GPU de plus, garde la même lecture (arc, héros, latent) et sert aussi le repli 2D et le téléphone.
  L'ancrage au nœud (filaments `L-piece`) reste possible plus tard.
- **Au centre, au-dessus de tout** : la galerie sort de la colonne et devient un calque **plein écran fixe** posé
  après le cockpit (z-index au-dessus du cockpit et des étiquettes, sous le plein écran d'une pancarte).
- **Visible** : cartes plus grandes (220 px, 260 px en très grand écran), arc centré verticalement ; pièce choisie
  en panneau au centre, les autres en arc réduit sous elle au palier **0,75** (§23), jamais 0,18.
- **Dynamique** : les cartes **jaillissent** du centre (T-G1 : `rebond`, 60 ms d'écart) ; le panneau de la pièce
  choisie avance et grandit (T-G3, `sortie`) ; l'arc glisse (T-G5) — inchangé.
- **Lumineux** : verre holographique §23 — reflet du ton en haut de carte, liseré du ton, halo 24 px à 45 % (héros
  40 px), socle de lumière elliptique sous l'arc, voile radial qui assombrit le fond derrière la galerie. Aucune
  couleur nouvelle.
- **Qualité basse** : même disposition, mêmes lueurs (ombres statiques, peu coûteuses) ; seuls l'éclat qui balaie
  les cartes et la pulsation du socle sont coupés. **Sobre / mouvement réduit** : grille centrée, sans animation.
- **2D** : grille centrée dans tout l'écran (verticalement et horizontalement), cartes plus grandes ; pièce choisie
  au centre, bande des autres pièces au-dessus.
- **Voix inchangée** : réducteur, ordres (« la deuxième », « suivante », « zoome », « ferme », chapitres) intacts.
  Ni écoute, ni voix, ni cerveau touchés ⇒ pas de banc à vraie voix.

## Découpage
| SF | Contenu | Estimation |
|---|---|---|
| SF-264-01 | Galerie au centre, lumineuse, animée (3D basse/normale, 2D) + banc de la vraie page | 1 j |
