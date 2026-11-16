# Mini-spec — [F-238 / SF-238-15] Zoom borné vers le point visé, cadrage plein

## Identifiant
SF-238-15

## Feature parente
F-238 — Architecture en 3D (visionneuse three.js dans Jarvis) — correctif après relecture des captures
`retours-po-2026-11-16` (SF-238-10 navigation, SF-238-11 gestes)

## Statut
in-progress

## Date de création
2026-11-16

## Branche Git
`feat/SF-238-15-zoom-borne-cadrage`

---

## Objectif

Pincer, tourner la molette ou appuyer sur « + » rapproche la caméra **vers le point visé sans jamais perdre le
schéma**, et le schéma au repos **remplit le cadre** de la visionneuse (PC comme téléphone en portrait).

## Comportement attendu

### Cas nominal
1. **Zoom vers le point visé** : la molette zoome vers le point survolé, le pincement vers le milieu des deux doigts,
   les boutons « + / − » vers le centre. Le point visé (rayon caméra → plan à la hauteur de la cible) devient
   l'ancre : la cible de la caméra glisse vers lui à proportion du zoom (`t' = a + (t − a) × ancien/nouveau`).
2. **Sensibilité raisonnable** : zoom continu (plus de cran ×2 au geste). Pincement : facteur = (écart des doigts)^0,75
   — un pincement normal (écart ×2) ≈ ×1,7 ; molette : ×1,15 par cran (10 crans plafonnent) ; boutons : ×1,5.
   La voix « zoome » garde son cran ×2.
3. **Bornes** (relatives à la boîte englobante de la scène) :
   - zoom architecture ×1 à ×4 (`ARCH_MAX_ZOOM`) ;
   - distance caméra ≥ sortie du premier plateau rencontré le long du rayon de visée (volume de chaque plateau, du sol
     à son altitude) + une icône de marge : **la caméra ne traverse jamais un plateau** ;
   - recul minimal devant chaque icône (sphère, sur l'axe comme en bord de cadre) : une icône n'occupe jamais plus de
     40 % de la plus petite dimension du cadre (`ICON_MAX_SHARE`) — pas d'icône géante ;
   - le zoom effectif est plafonné d'autant (le zoom rendu = celui atteint, un dézoom répond tout de suite) ;
   - cible (pan) bornée à l'emprise des services ; si aucun service n'est au cadre (zoom dans un vide), la cible se
     pose sur le service le plus proche : au moins un service reste dans le cadre.
   - dézoomer ramène le décalage vers 0 à proportion (×1 = vue d'ensemble exacte).
4. **Cadrage plein** : la vue d'ensemble cadre les **vrais volumes** (coins des plateaux à leur altitude, services
   et icônes) et non plus la boîte « emprise × hauteur max » (vide au-dessus des plateaux bas) ; marge 5 %.
   Marge latérale ≥ 24 px (étiquette d'un service au bord entière). La cible de repos est reposée sur la ligne de
   visée à l'altitude moyenne des services (même image ; le recentrage pouvait l'enfouir sous le sol, ce qui
   bloquait le zoom). Résultat attendu ≈ 85–90 % de la dimension limitante, PC 1920×1080 comme téléphone 390×844.
7. **Étiquettes** : le dé-chevauchement des étiquettes est relancé à la fin de l'entrée et à la fin de chaque
   déplacement de caméra (zoom, orbite), et toutes les 15 images pendant un déplacement (sinon des libellés se
   superposaient après un zoom).
5. **« Recentrer »** remet zoom ×1, décalage nul, orbite nulle, vue d'ensemble (inchangé, vérifié au banc).
6. Le décompte d'usage `n_tch` compte **un** ordre par pincement (pas un par mouvement) et une rafale de molette
   (< 400 ms) une seule fois.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Pincement très fort / molette répétée au maximum | zoom plafonné (×4 ou moins selon la distance minimale), un service au moins reste dans le cadre | — |
| Rayon visé parallèle au plan ou derrière la caméra (vue de face, ciel) | ancre = cible courante (zoom au centre) | — |
| Point visé hors du schéma (vide, coin) | ancre ramenée dans l'emprise des services | — |
| Zoom demandé par valeur > ×4 sur une architecture | plafonné à ×4 (pas de refus) | — |
| Dézoom sous ×1 | borné à ×1, décalage remis à 0 | — |

## Critères d'acceptation
- [x] Après pincement fort (écart ×7) et après 10 crans de molette, au moins un service est projeté dans le cadre
      (test unitaire sur la caméra pure + banc : services visibles ≥ 1).
- [x] À tout zoom, la caméra est hors de la boîte englobante de la scène (test unitaire).
- [x] Un pincement normal (écart ×2) donne un zoom entre ×1,5 et ×2 (test unitaire).
- [x] « Recentrer » remet zoom ×1 et le cadrage de repos (banc : même nombre de services qu'au repos).
- [x] Vue d'ensemble : l'emprise projetée des volumes occupe ≥ 80 % de la dimension limitante du cadre, PC et
      portrait téléphone (test unitaire) ; captures avant / après.
- [x] Luminosité, voix et consigne Jarvis inchangées.

## Périmètre — hors scope
Luminosité / bloom (validés) ; voix, consigne et outil `afficher` de Jarvis ; visionneuse SVG 2D (F-237) ;
glisser à deux doigts pour déplacer (pan direct) ; inertie.

## Valeurs initiales
`ARCH_MAX_ZOOM = 4` ; pincement exposant 0,75 ; molette ×1,15/cran ; boutons ×1,5 ; marge de cadrage 5 % (≥ 24 px
de côté) ; `ICON_MAX_SHARE = 0,4`. Mesuré au banc (fixture 3-tiers) : zoom atteint PC ×1,9–2,0, téléphone ×2,2–4.

## Contraintes de validation
| Champ | Règle |
|---|---|
| zoom (architecture) | 1 ≤ zoom ≤ 4, et distance ≥ distance minimale de la boîte |
| décalage (pan) | cible dans l'emprise des services |

## Technique
`architecture-stage-director.ts` (`cameraFor` : cadrage sur les volumes, `applyZoomPan`, `zoomToward`,
`ArchState.pan`, plan `base`), `engine/architecture-scene-3d.ts` (`zoomAt`, dé-chevauchement en fin de déplacement), `architecture-stage.component.ts`
(`zoomAt` en coordonnées fenêtre), `pieces/jarvis-piece-gestures.ts` (facteurs continus),
`pieces/jarvis-piece-architecture.component.ts` (gestes → `zoom {value, pan}`), `pieces/jarvis-piece-nav.ts`
(`zoom.pan`, plafond architecture), `pieces/jarvis-piece-screen.ts` (zoom arrondi dans la ligne d'écran),
`scripts/banc-architecture-3d/scenario.mjs` (`BANC_ZOOM=1`).
Aucune table, aucun endpoint.

## Plan de test
### Tests unitaires
`architecture-navigation.spec.ts` : caméra hors boîte à ×4 et après pan extrême ; services projetés dans le cadre
après zoom vers un coin ; cadrage ≥ 80 % (PC, portrait) ; `zoomToward` (ancre, plafond, dézoom → pan 0).
`jarvis-piece-gestures.spec.ts` : pincement ×2 ⇒ ×1,5–2, molette par cran, point du geste.
`jarvis-piece-nav` (spec existant) : zoom architecture plafonné, `pan` posé par le zoom.
### Tests d'intégration
Banc `BANC_ZOOM=1 node scripts/banc-architecture-3d/scenario.mjs` (vraie page /jarvis, API simulée) : repos,
pincement fort, molette ×10, Recentrer — PC 1920×1080 et téléphone 390×844, avant (origin/main) / après.
### Isolation utilisateur
Non applicable (rendu local, aucune donnée nouvelle ; graphe lu par le chemin existant filtré `user_id`).

## Préoccupations transversales
Aucune (pas d'auth, pas de tenant, pas de quota, pas de route). Pas de banc à vraie voix : seule la visionneuse 3D
(frontend) change.
