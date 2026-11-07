# Mini-spec — F-250 / SF-250-05 Le visage vit

## Identifiant
`F-250 / SF-250-05`

## Feature parente
`F-250` — Le visage de Jarvis (cadrage §4.1, §5)

## Statut
`done` (2026-11-07) · **Estimation :** 2 j · **Branche :** `feat/SF-250-05-le-visage-vit` · **Dépend de :** SF-250-04

## Objectif
Le visage suit l'état de Jarvis (repos, écoute, réflexion, parole, coupure, « oui » attendu, veille) avec clignements,
regard, sourcils, ondes et éclairs, et **prend la place du cœur** (T-F1, 900 ms) quand Jarvis dialogue.

## Comportement attendu

### Cas nominal
1. `face/jarvis-face-motion.ts` (pur, posé avec le rendu en SF-250-04 qui en a besoin, **éprouvé ici**) : cibles par état (`FACE_MOODS`, §4.1), ressorts, clignements et saccades
   **déterministes** (graine par fenêtre : 2,4-6 s entre deux clignements, jamais > 3 Hz), coupure = bouche fermée en
   ≈ 120 ms et regard vers le PO ; mouvements réduits ⇒ ni clignement, ni balayage, ni balancement.
2. `face/jarvis-face-stage.ts` (pur) : `facePresenceTarget` — visage dès que Jarvis est ouvert et hors veille ; retour
   au cœur en **veille** (repos > 60 s, `SLEEP_AFTER_MS`) ; **dissolution** vers la scène des deux cerveaux quand le grand
   cerveau est déployé (Opus travaille longtemps), **retour pour parler** (parole, écoute, coupure).
3. Moteur : présence interpolée **linéairement en 900 ms** ; les points partent de la sphère du cœur (`aFrom`) et s'y
   recomposent ; le cœur s'éteint à mesure (`lightGain × (1 − présence)`) et n'est plus dessiné à présence ≈ 1 ; lumière
   et bloom mélangés.
4. Taille : `faceScaleFor` — le visage tient ≈ 36 % de la hauteur de l'écran (figure principale), ≤ 62 % de la largeur
   au téléphone, jamais plus petit que le cœur.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Visage éteint pendant l'ouverture (réglage, serveur) | présence → 0 en 900 ms, cœur de retour | — |
| Plan inconnu | humeur `repos` (inchangé) | — |

## Critères d'acceptation
- [x] Chaque état a sa pose (tests) ; clignements déterministes et bornés.
- [x] Coupure : bouche fermée en ≈ 120 ms.
- [x] T-F1 900 ms dans les deux sens ; veille ⇒ cœur ; grand cerveau ⇒ dissolution, parole ⇒ retour.

## Plan de test
Unitaires `jarvis-face-motion.spec.ts` (6), `jarvis-face-stage.spec.ts` (présence, échelle). Banc visuel
`visage-*`. Intégration / isolation : sans objet.

## Impact
Frontend : `jarvis/face/jarvis-face-stage.ts` (+ spec), `jarvis/face/jarvis-face-motion.spec.ts`,
`engine/jarvis-engine.ts` (présence interpolée, échelle).
Préoccupations transversales : aucune.

## Hors périmètre
Lèvres branchées sur la voix (SF-06), pancartes et réglage (SF-07).
