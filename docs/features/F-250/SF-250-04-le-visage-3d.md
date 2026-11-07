# Mini-spec — F-250 / SF-250-04 Le visage 3D

## Identifiant
`F-250 / SF-250-04`

## Feature parente
`F-250` — Le visage de Jarvis (cadrage §4, §6 ; style B choisi par le PO le 2026-11-07)

## Statut
`done` (2026-11-07) · **Estimation :** 2 j · **Branche :** `feat/SF-250-04-le-visage-3d` · **Dépend de :** SF-250-03

## Objectif
Un visage holographique **générique** généré par code (`engine/jarvis-face-3d.ts` + `face/jarvis-face-geometry.ts`) :
nuage de points et fil de fer partageant les sommets, traits lumineux, aura, ondes, cadran et socle, style B « cyan
holographique », déformation **dans le shader de sommets**, trois niveaux de détail, zéro allocation par image.

## Décision PO (2026-11-07) et exception de charte
Style **B — cyan holographique**. `DESIGN_SYSTEM.md` §23 amendé : le cyan `#5FD4FF` est la **teinte du visage** ; le
sens « attend votre réponse » reste porté par la pastille d'état et les pancartes ; le visage exprime l'attente par sa
posture, **jamais** par un changement de teinte (pourquoi : le cyan du visage n'est pas un état, deux sens pour une même
couleur sur le même objet seraient illisibles). Jetons : `FACE_COLORS` (`jarvis-motion.ts`, source unique).

## Comportement attendu

### Cas nominal
1. Géométrie (une fois) : ovale + reliefs gaussiens, grille au pas 0,019 (PC) / 0,022 (moyen) / 0,028 (bas) ⇒ ≈ 5 900 /
   ≈ 4 500 / ≈ 2 900 points ; éclairage factice précalculé ; fil de fer une maille sur deux (PC) / une sur une (bas),
   coupé sur la fente des lèvres.
2. Shader : bouche (4 uniformes), bords qui se dissolvent, balayage, scintillement, lueur de bouche, éclairs
   synaptiques violets (`--cg-holo-deep`), recomposition depuis la sphère du cœur (`aFrom`, T-F1).
3. Traits : lèvres (4 courbes, arc de Cupidon), lueur intérieure, paupières (+ pli), iris, pupilles, sourcils, nez —
   déplacés dans le shader par famille (paupière, regard, sourcils).
4. Les poses par état (`face/jarvis-face-motion.ts`, pur) sont posées ici parce que le rendu les lit ; leurs
   transitions et leurs preuves sont l'objet de SF-250-05. Ici le visage remplace le cœur dès qu'il est allumé.
5. Aura + ondes en **un** appel de dessin ; cadran + socle en **un** appel (rotation dans le shader).
6. Appels de dessin : PC 6 (points, fil, traits, lueurs des traits, aura+ondes, cadran+socle), téléphone 4 (sans
   lueurs des traits ni cadran, sans aura) ; le cœur F-236 (≈ 10 appels) n'est plus dessiné quand le visage est là.
7. Banc `?banc=visage-*` (repos, écoute, réflexion, parole, oui, coupure, veille, cerveau, pancarte).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| WebGL indisponible / repli 2D / mode sobre / mouvements réduits (T10) | aucun visage, orbe 2D (inchangé) | — |
| Perte de contexte | règle T11 inchangée (le moteur entier se libère) | — |
| Coupe-circuit `APP_JARVIS_FACE_ENABLED=false` | le cœur F-236, inchangé | — |

## Critères d'acceptation
- [x] Visage généré par code, aucune ressource importée ; même graine ⇒ même visage.
- [x] Style B, jetons dans `FACE_COLORS`, §23 amendé.
- [x] ≤ 6 appels PC / ≤ 4 téléphone ; aucune allocation par image (uniformes seulement).
- [x] Captures PNG du rendu produit (parole, écoute, réflexion, téléphone 390 px) dans `captures/`.

## Plan de test
- Unitaires : `jarvis-face-geometry.spec.ts` (nombres de points, graine, fente coupée, relief, bouche, traits).
- Visuel : `frontend/scripts/jarvis-3d-captures.sh` sur les scènes `visage-*` (Chrome sans tête, swiftshader).
- Intégration / isolation : sans objet (rendu).

## Impact
Frontend : `jarvis/face/jarvis-face-geometry.ts`, `jarvis/stage3d/engine/jarvis-face-3d.ts`, `jarvis-motion.ts`
(`FACE_COLORS`), `jarvis-engine.ts` / `jarvis-engine.contract.ts` (option `face`), banc. Docs : `DESIGN_SYSTEM.md` §23.
Préoccupations transversales : aucune.

## Hors périmètre
États et transitions (SF-05), lèvres branchées (SF-06), cohabitation (SF-07), bancs de mesure (SF-08).
