# Mini-spec — [F-238 / SF-238-09] Scène 3D : plateaux, services, filaments, flux

## Identifiant
SF-238-09

## Feature parente
F-238 — Architecture en 3D, itération 1 « L'architecture en 3D » (cadrage §5.1, §6, §8.1, §9, §10, §14, §17)

## Statut
in-progress

## Date de création
2026-11-15

## Branche Git
`feat/SF-238-09-scene-3d`

---

## Objectif

Poser un `ArchitectureGraph` v1 en 3D holographique — plateaux translucides en terrasses, services sur socles
lumineux, filaments orthogonaux parcourus de particules dans le sens du flux, étiquettes — avec l'entrée T-A1, la
sortie T-A2 et la vue de dessus qui coïncide avec le schéma 2D cadré, jouable au banc `?banc=architecture*`.

## Comportement attendu

### Cas nominal
1. **Directeur pur** `architecture-stage-director.ts` (aucun import de `three`) : `(graphe, état {vue, éclaté,
   focus, allumés}, phase {entrée|repos|sortie, t})` ⇒ `ArchitecturePlan` déclaratif : caméra (cible, azimut,
   élévation, distance, champ), et pour chaque zone / nœud / liaison : palier de transparence F-236 (0,04 · 0,18 ·
   0,45 · 0,75 · 1), rôle de couleur §10 (`normal`, `core`…), échelle, progression du tracé ; flux animés.
2. **Mise en page 3D** `architecture-layout-3d.ts` (pur) : `(x, y)` du graphe ⇒ `(x, z)` (1 px = 0,01 u, centré) ;
   plateau d'une zone à `h = (depth + 1) × Δ` (compact Δ = 0,25, éclaté Δ = 2,2, téléphone éclaté 1,4) ; nœud posé
   sur le plateau de sa zone (sol = 0 hors zone), icône à 0,35 au-dessus (centre de l'icône 64 px du SVG) ; liaison =
   polyligne du graphe à l'altitude du plateau commun le plus profond, raccordée aux extrémités par des **segments
   verticaux** (jamais de diagonale) ; particules le long du chemin de `from` vers `to` (deux sens si
   bidirectionnel), 0,8 u/s, espacement 0,6, écrites dans un tampon fixe (0 allocation).
3. **Vue de dessus (T-A9)** : zénith, champ fixe, plateaux **couchés** (altitudes × 0) ⇒ chaque point du graphe tombe
   à l'écran là où le SVG cadré (contenu `contain`, marge 5 %, centré) le dessine.
4. **Rendu** `engine/architecture-scene-3d.ts` (seul fichier `three` de la SF, chargé paresseusement) : plateaux =
   1 `InstancedMesh` ; liserés = 2 `LineSegments2` (continus ; pointillés pour `region`/`az`/`asg`) ; socles,
   halos, ombres = 1 `InstancedMesh` chacun ; icônes = 1 `InstancedMesh` de quads + **atlas** (`CanvasTexture`) ;
   filaments = 2 `LineSegments2` (noyau clair + halo orange) ; particules = 1 `Points` (pool fixe) ; décor
   (fond nébuleuse, poussière, sol polaire) ; bloom sélectif F-236 (`JarvisPost`) ; étiquettes CSS2D en
   `textContent` (Space Grotesk capitales pour les zones, Inter pour les services). Couleurs **uniquement** de
   `HOLO_COLORS` / `HOLO_ALPHA` / `MOTION_CURVES` (`jarvis-motion.ts`) ; zones toutes en orange (Q1), nature lue par
   trait, libellé, altitude.
5. **Icônes** : tant que l'atlas de SF-238-08 n'est pas livré, une **boîte neutre** par icône (jamais une icône
   approchante) via une interface `ArchitectureIconSource` que SF-238-08 remplira.
6. **T-A1 (2,2 s)** et **T-A2 (0,6 s)** selon le tableau §10.1 (durées ajoutées à `MOTION_DURATIONS`), courbes
   `glisse`/`sortie`/`rebond`/`entree` ; le directeur calcule chaque instant (déterministe, banc `&t=`).
7. **Banc** : scènes `architecture` (3-tiers, entrée T-A1 à l'instant `t`), `architecture-dessus` (vue de dessus +
   SVG cadré superposé à 50 %), `architecture-eclate`, `architecture-sortie` (T-A2), `architecture-tout` (17 nœuds,
   19 zones), `architecture-60` (60 nœuds, budget d'appels) ; compteur d'appels de dessin et i/s exposés au banc.
   Captures headless 1920 × 1080 et 390 × 844.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Graphe de version ≠ 1 ou vide (0 nœud) | `layoutArchitecture` renvoie `null` ; le banc affiche le message 2D « schéma indisponible », aucune scène | — |
| Nœud dont la zone est inconnue | posé au sol (altitude 0), pas d'erreur | — |
| Liaison dont `from`/`to` est inconnu ou < 2 points | ignorée (ni filament ni particule) | — |
| Segment oblique dans le graphe (graphe corrompu) | coupé en deux segments orthogonaux (coude en L), compté dans `plan.repaired` | — |
| Plus de particules que le pool | bornées (PC 2 000, téléphone 400), les dernières liaisons sans particule | — |
| WebGL indisponible au banc | message de repli, aucune exception | — |

## Critères d'acceptation
- [ ] Vue de dessus : écart ≤ 2 px à 1920 × 1080 entre la projection de chaque coin de zone, centre d'icône et coude
      de liaison et sa position dans le SVG cadré (test Karma sur le plan, projection pure).
- [ ] 0 segment non orthogonal dans le plan, en compact **et** en éclaté (chaque segment 3D ne varie que sur un axe).
- [ ] Particules dans le sens `from → to` : l'abscisse curviligne croît avec le temps ; bidirectionnel ⇒ les deux sens.
- [ ] ≤ 20 appels de dessin pour 60 nœuds (hors post-traitement) : compté sur la scène construite (objets dessinables
      visibles) et lu au banc (`renderer.info.render.calls`).
- [ ] 0 allocation par image dans le chemin chaud (particules, instances : tampons fixes réutilisés — test).
- [ ] T-A1 : à t = 0 tout est couché et éteint ; à t = 2 200 ms état final ; plateaux du plus large au plus profond
      (60 ms d'écart), services dans l'ordre du flux (25 ms) ; T-A2 inverse en 600 ms (tests sur le directeur).
- [ ] Couleurs et paliers exclusivement issus de `jarvis-motion.ts` ; étiquettes en `textContent`.
- [ ] Captures 1920 × 1080 et 390 × 844 dans `~/.local/share/claude-gateway/captures-f238/` ; i/s relevés au banc.
- [ ] `three` reste hors du bundle initial (`check-jarvis-3d-budget.mjs`).

## Périmètre — hors scope
Visionneuse dans `PIECE_VIEWERS`, gestes, `PieceNav.arch`, replier/déplier, éclater animé T-A3 (SF-238-10 — l'état
éclaté est seulement **posé** ici) ; atlas réel et proxy d'icônes (SF-238-08) ; téléphone/qualité adaptative/repli 2D
complet/mode présentation (SF-238-11) ; narration (SF-238-12) ; aucune lecture réseau (fixtures du renderer réel).

## Valeurs initiales
Sans objet (aucune entité).

## Contraintes de validation
| Champ | Règle |
|---|---|
| `graph.version` | = 1, sinon `null` |
| nœuds / zones / liaisons | bornes F-142 (≤ 60 / ≤ 24 / ≤ 120) : au-delà, tronqué (les premiers) |
| libellés | `textContent`, jamais `innerHTML` |

## Technique
- Endpoints : aucun. Tables / migration : aucune.
- Fichiers (nouveaux, `frontend/src/app/jarvis/stage3d/architecture/`) : `architecture-graph.ts` (type v1 +
  validation), `architecture-graph.fixture.ts` (graphes produits par `diagram-renderer/cadre.js` sur les fixtures
  `trois-tiers`, `tout`, + générateur 60 nœuds), `architecture-layout-3d.ts`, `architecture-stage-director.ts`,
  `engine/architecture-scene-3d.ts`, `architecture-stage.component.ts` (hôte du banc) et leurs `.spec.ts`.
- Modifiés : `jarvis-motion.ts` (durées T-A1/T-A2), `jarvis-banc.ts` (scènes), `jarvis-banc.component.ts`
  (pose la scène d'architecture à la place de l'univers pour `architecture*`).
- Composants Angular : `ArchitectureStageComponent` (banc ; SF-238-10 l'inscrira dans `PIECE_VIEWERS`).

## Plan de test
### Tests unitaires (Karma, sans WebGL)
- layout : coordonnées plan = graphe ; altitudes compact/éclaté ; segments orthogonaux (3 graphes, compact/éclaté) ;
  extrémités verticales ; segment oblique réparé ; zone inconnue au sol ; graphe invalide ⇒ `null`.
- directeur : projection vue de dessus ≤ 2 px (1920 × 1080 et 390 × 844) ; T-A1 jalons (0, 400, 1 100, 1 900,
  2 200 ms), ordres (plateaux larges d'abord, services selon le flux) ; T-A2 ; paliers de transparence ⊂ `HOLO_ALPHA` ;
  déterminisme (même entrée ⇒ même plan).
- particules : sens `from → to`, bidirectionnel, bornes du pool, 0 allocation (même tableau réutilisé).
- scène (`three` sans rendu, Karma) : nombre d'objets dessinables ≤ 20 sur 60 nœuds ; étiquettes en `textContent`.
### Tests d'intégration
- Banc headless : 6 scènes × 2 tailles, couverture ≥ 2 %, appels de dessin et i/s lus sur la page.
### Isolation utilisateur
- Non applicable — aucune donnée lue ni écrite (fixtures, rendu 100 % navigateur).

## Préoccupations transversales
- Navigation / routing : **non** — aucune route ; scènes de banc ajoutées à la liste fermée (dev seulement).
- Contexte tenant, Auth, Plans : non.

## Dépendances
- SF-238-05 (graphe exporté) : done. SF-238-08 (atlas) : en cours en parallèle — branchement par interface, boîte
  neutre en attendant. Aucune dépendance F-237 (cadrage §17).
- Questions ouvertes : aucune ; Q1/Q2 appliquées par défaut (PO 2026-11-15).

## Notes et décisions
- **D-09-1** Vue de dessus « couchée » : en T-A9 les altitudes passent à 0 pour que la perspective (focale fixe) ne
  décale pas les plateaux hauts (parallaxe > 2 px sinon) — c'est la forme exacte du 2D cadré.
- **D-09-2** Moteur de scène **séparé** de l'univers F-236 (même contrat de matières, post-traitement, courbes,
  couleurs, boucle d'images) : l'univers et l'architecture ne coexistent pas à l'écran ; SF-238-10 l'inscrira
  comme visionneuse.
