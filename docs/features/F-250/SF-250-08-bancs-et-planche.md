# Mini-spec — F-250 / SF-250-08 Bancs et planche

## Identifiant
`F-250 / SF-250-08`

## Feature parente
`F-250` — Le visage de Jarvis (cadrage §6, §8 portes P0-P5)

## Statut
`done` (2026-11-07) · **Estimation :** 1 j · **Branche :** `feat/SF-250-08-bancs-et-planche` · **Dépend de :** SF-250-01 → 07

## Objectif
Les bancs du visage (captures déterministes, images par seconde, synchronisation) et la planche PO, avec le bilan
chiffré des portes.

## Comportement attendu
1. `frontend/scripts/jarvis-face-banc.mjs` (SF-04) : sert un build de **développement** (page `/jarvis-banc`), Chrome
   sans tête swiftshader, `?banc=visage-*&t=<ms>&niveau=<n>` ⇒ PNG ; erreurs console relevées.
   `node scripts/jarvis-face-banc.mjs <dist/browser> <sortie> visage-parole visage-ecoute …` (puppeteer-core requis).
2. `frontend/scripts/jarvis-face-fps.mjs` : images par seconde (rAF, 5 s) d'une scène, cœur vs visage, PC / téléphone ;
   `GPU=1` pour le rendu matériel quand le poste en a un.
3. Banc P2 hors ligne : `docs/features/F-250/bancs/p2/` (SF-06). Bancs P0 / P1 : `docs/features/F-250/bancs/` (SF-01).
4. Planche : `captures/10-…13-` (parole, écoute, réflexion PC ; téléphone 390 px) et `14-planche-produit.png`.

## Bilan des portes
| Porte | Résultat | Méthode |
|---|---|---|
| P0 coût | **PASS** — 924 = 924 caractères | 50 phrases, compte + en-tête `character-cost` |
| P1 premier son | **PASS** — −4 ms p95 | 50 phrases entrelacées, 1ᵉʳ octet mp3 vs 1ᵉʳ objet aligné ; suivi `jarvis_turn_timings` après déploiement |
| P2 lèvres ↔ son | **PASS** — p90 du retard 7 ms (fermetures), 38 ms (ouvertures) | chaîne du produit rejouée sur 6 réponses réelles |
| P3 i/s | **Raisonnée, à confirmer sur appareils** (voir ci-dessous) | — |
| P4 repli | **PASS** — tests (ligne illisible, alignement incohérent, voix sans alignement) | unitaires |
| P5 coupe-circuits | **PASS** — `APP_JARVIS_FACE_ENABLED`, `APP_JARVIS_FACE_ALIGN_ENABLED` | tests `JarvisFaceAlignTest` |

### P3 — méthode et limite
Le poste de livraison n'a **pas de GPU** (Chrome = SwiftShader, rendu logiciel sur 4 cœurs) : les i/s mesurés y
mesurent le processeur, pas l'appareil. Mesures relatives (même poste, même scène, `jarvis-face-fps.mjs`) :
téléphone niveau Bas 390 px — cœur 84 i/s, visage 255 i/s (le visage ne dessine plus le cœur) ; PC 960×540 haut —
cœur 1,4 i/s (le bloom plein écran sature le rendu logiciel, identique avec le visage). Raisonnement : le visage
**remplace** le cœur (≈ 10 appels de dessin) par 6 (PC) / 4 (téléphone), ≈ 5 900 / 2 900 points, toute la
déformation dans le shader ; coût processeur mesuré **2,7 µs par image** (lèvres + visage vivant, Node) pour un budget
de 0,5 ms ; zéro allocation par image. La chaîne de post-traitement (seul coût plein écran) est celle de F-236.
**Reste** : relever p50 pendant la parole sur le PC et le téléphone du PO (≥ 60 / ≥ 50), qualité adaptative F-236 en
garde (niveau Bas puis repli 2D sous 30 i/s).

## Critères d'acceptation
- [x] Scripts de banc versionnés, captures et planche dans `captures/`.
- [x] Bilan des portes chiffré, méthode écrite, limite P3 dite.

## Plan de test
Bancs (scripts) ; aucune ligne de code de l'application ne change dans cette SF.

## Impact
`frontend/scripts/jarvis-face-fps.mjs`, `docs/features/F-250/captures/1[0-4]-*.png`, cette mini-spec.
Préoccupations transversales : aucune.

## Hors périmètre
Déploiement (consigne), mesures sur appareils réels du PO.
