# Mini-spec — F-248 / SF-248-10 Bancs et planche

## Identifiant
`F-248 / SF-248-10`

## Feature parente
`F-248` — Les pancartes (cadrage §6, §8, §11 ; portes K2, K4, K6, K7)

## Statut
`done` (2026-11-05) · **Estimation :** 1,5 j (réestimé 1 j) · **Branche :** `feat/SF-248-10-bancs-et-planche` · **Dépend de :** SF-248-06, 08, 09 (mergées)

## Objectif
Prouver les portes sans modèle : banc PANCARTES déterministe (40 scénarios côté passerelle, 40 côté écran), banc de mise
en page (1366 × 768, 360 × 740), bancs CONVERSATION et PIECES rejoués, et planche de captures pour le visa de goût du PO.

## Comportement attendu

### Cas nominal
1. **Passerelle** — `JarvisCardsBenchTest` : 40 scénarios sur `POST /api/jarvis/converse` (fournisseur simulé, 40 comptes
   + un second compte témoin) : lecture d'office (attentes, bloc), lecture ciblée (outil `etat`, 12 scénarios),
   présentation (5), secrets semés (mot de passe dans une attente, jeton dans un titre de ticket, texte d'un autre
   compte). Portes : **0 pancarte hors catalogue**, **0 jeu avant le premier mot** (`text` avant `cards`, `cards` avant
   `done`), **0 secret**, **présentation vide**, ciblée ⇔ `etat` lu.
2. **Écran** — `jarvis-card-banc.spec.ts` : 40 scénarios (`CardShowing`, tailles 1-12, lecture ciblée un sur trois, alias
   ambigu un sur cinq) : **0 avant la première phrase**, **0 hors catalogue**, **100 % des citées montrées**, l'alias
   ambigu n'allume rien, présentation refusée.
3. **Mise en page (K4)** — la galerie avec 1, 4 et 9 pancartes dans un cadre de 1366 × 768 (3D) et 360 × 740 (pile 2D) :
   aucune barre de défilement horizontale. L'i/s (K4) se mesure en production (`fps_*`, SF-248-01).
4. **Non-régression** — bancs `JarvisConversationBench*`, `JarvisPiecesBench*` et toute la suite rejoués verts.
5. **Planche** — scènes de banc `pancartes-un|eventail|mur|plein-ecran|comparer|eteint|plat` (`/jarvis-banc`,
   développement seulement, faits fictifs) ; 12 captures (PC 1920×1080, téléphone 390×844) par
   `scripts/jarvis-3d-captures.sh` ; `docs/features/F-248/captures/index.html` par `jarvis-planche.mjs`.
6. Corrections vues sur la planche : éventail élargi (facteur 0,6 → 0,75 : les pancartes ne se masquent plus et le jeu
   tient dans 1366 px) ; plein écran opaque sur un voile, titre et libellés en crème.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Une porte franchie | le banc échoue en nommant le scénario | — |
| Scène de banc inconnue | `coeur` (liste fermée `BANC_SCENES`) | — |

## Contraintes de validation
40 scénarios par banc ; cadres 1366 × 768 et 360 × 740.

## Critères d'acceptation
- [x] Banc passerelle : 35 jeux, 0 hors catalogue, 0 avant le premier mot, 0 secret, 0 en présentation.
- [x] Banc écran : 40/40, 100 % des citées montrées.
- [x] Aucun débordement horizontal aux deux tailles.
- [x] Planche publiée dans le dépôt (12 captures).

## Plan de test
- Intégration : `JarvisCardsBenchTest`.
- Unitaires : `jarvis-card-banc.spec.ts` (49).
- Non-régression : suites complètes (back et front).

## Impact
- Frontend : `cards/jarvis-card-banc.ts` (faits du banc), `stage3d/jarvis-banc.ts` (+7 scènes), `stage3d/jarvis-banc.component.ts` ; `cards/jarvis-card-deck.ts` (écart de l'éventail), `cards/jarvis-card-detail.component.ts` (opacité, couleurs).
- Docs : `docs/features/F-248/captures/` (planche).
- Préoccupations transversales : aucune (la route `/jarvis-banc` n'existe qu'en développement).

## Hors périmètre
Le visa de goût (taille du bloc unique, densité du mur) : soumis au PO par la planche, non bloquant.
