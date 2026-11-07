# Mini-spec — F-250 / SF-250-07 Cohabitation

## Identifiant
`F-250 / SF-250-07`

## Feature parente
`F-250` — Le visage de Jarvis (cadrage §5)

## Statut
`done` (2026-11-07) · **Estimation :** 1,5 j · **Branche :** `feat/SF-250-07-cohabitation` · **Dépend de :** SF-250-05, 06

## Objectif
Le visage partage la scène sans rien casser : il s'écarte et regarde les panneaux, laisse sa place au grand cerveau,
respecte le téléphone et le compagnon Android, et se règle par appareil (« Jarvis : visage / cœur »).

## Comportement attendu

### Cas nominal
1. **Panneaux / pancartes 3D** (F-236 / F-248) : un panneau affiché (PC ≥ 768 px) ⇒ le visage glisse sur le côté
   (échelle × 0,6, ≈ 240 ms) et tourne la tête vers eux ; les panneaux se posent autour du **rayon du visage**
   (`coreScreen`), aucun n'est masqué. Au téléphone, panneaux ancrés inchangés, le visage reste au centre.
2. **Grand cerveau (F-235)** : dissolution / retour (SF-250-05), inchangé ici, vérifié au banc `visage-cerveau`.
3. **Univers (F-237)** : nœuds, liaisons, étiquettes inchangés (le visage remplace le cœur à l'origine).
4. **Téléphone / compagnon WebView (F-232)** : niveau « Bas » ⇒ ≈ 2 900 points, fil de fer une maille sur une au
   pas large, **ni aura, ni cadran, ni lueurs des traits** ; 4 appels de dessin.
5. **Réglage** « Jarvis : visage / cœur » dans le volet Réglages (par appareil, `localStorage`, défaut visage),
   visible seulement si le serveur allume le visage ; éteindre le visage coupe aussi la demande d'alignement.
6. **Repli 2D, mode sobre, `prefers-reduced-motion`** : règle T10 inchangée (pas de 3D ⇒ pas de visage).
7. Sous-titres, HUD, pastille d'état : inchangés ; visage décoratif (calque `aria-hidden`).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `GET /api/jarvis/face` en échec | visage éteint (cœur F-236), aucune erreur affichée | — |
| Stockage local indisponible | réglage le temps de la page | — |

## Critères d'acceptation
- [x] Panneaux autour du visage ; visage écarté et tourné vers eux (banc `visage-pancarte`).
- [x] Budget téléphone : 4 appels, ≈ 2 900 points.
- [x] Réglage par appareil ; coupe-circuit serveur respecté.
- [x] Non-régression des scènes F-234 / F-236 (suite `stage3d` verte, visage absent par défaut du moteur).

## Plan de test
Unitaires : `jarvis-face-stage.spec.ts` (réglage de l'appareil), `jarvis.component.spec.ts` (réglage, statut).
Visuel : banc `visage-pancarte`, `visage-cerveau`, téléphone. Intégration / isolation : sans objet.

## Impact
Frontend : `engine/jarvis-engine.ts` (écart, `coreScreen`), `jarvis.component.ts/html` (réglage), `face/jarvis-face-stage.ts`.
Préoccupations transversales : aucune (pas de route, d'auth ni de tenant ; un réglage local de plus).

## Hors périmètre
Mesures et planche (SF-08).
