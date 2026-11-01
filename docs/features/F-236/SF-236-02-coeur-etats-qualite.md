# Mini-spec — [F-236 / SF-236-02] Le cœur et ses états, qualité adaptative

## Identifiant
SF-236-02

## Feature parente
F-236 — Jarvis en 3D (cadrage `docs/features/F-236/CADRAGE-F-236-jarvis-en-3d.md`, itération 1 « Jarvis s'allume »)

## Statut
Livrée (PR à la suite de cette mini-spec)

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-02-coeur-etats-qualite`

---

## Objectif

Remplacer l'orbe 2D par le **cœur 3D** de Jarvis — particules, anneaux, ondes de voix, rayons, halo, onde de choc —
qui change de rendu à chaque état (repos, écoute, réflexion, parole, coupure, « oui » attendu, veille, réveil) en
transitions du catalogue §7.4, sous un bloom à seuil et une qualité qui s'adapte à l'appareil.

## Comportement attendu

### Cas nominal

1. En 3D, la scène dessine le **cœur** (rayon 1,4 ; PC 2 000 / téléphone 900 particules ; 3 anneaux) ; l'orbe 2D
   F-231 s'efface en fondu (T-F) et son **bouton reste** à sa place pour « parler » (aucun toucher perdu).
2. Le **directeur de scène** (pur) lit l'état de l'écran (H6), la coupure (`ReaderState.frozen`, H2), l'autorisation
   S2 épinglée (H2), la conversation (H9) et le mot « Jarvis » (H10) ; il produit un **plan** (`mood`, `wakes`) que
   le moteur interpole.
3. Humeurs (priorité : coupure > « oui » > veille > état) :

   | Humeur | Rendu | Entrée |
   |---|---|---|
   | repos | respiration 4 s, anneaux lents, lumière 80 % | T-C4 1,2 s `souffle` |
   | écoute (N4) | **ondes entrantes ∝ niveau du micro**, anneaux face à la caméra | T-C1 0,35 s `sortie` |
   | réflexion | nuage en tourbillon, rotation rapide, bloom 1,25 | T-C2 0,6 s `glisse` |
   | parole (N5) | **ondes sortantes ∝ niveau de la voix**, 64 rayons modulés | T-C3 0,25 s `sortie` |
   | coupure | **gel** sur l'instant, lumière 50 % | T-C6 0,4 s `entree` |
   | « oui » attendu | coquille **cyan** (couleur de l'attente, goût PO V1), ondes lentes | T-C5 0,5 s `glisse` |
   | veille (N3) | lumière 30 %, bloom réduit, tout ralentit | T-V1 2,5 s `souffle` |
   | réveil (N2) | **onde de choc** sphérique depuis le cœur | T-V2 0,6 s `sortie` |

4. **Veille** : 60 s sans conversation active, Jarvis au repos ⇒ veille ; une conversation, un tour ou le mot
   « Jarvis » réveille (onde de choc). La minuterie n'existe que si la 3D est montée.
5. **Lumière** : bloom (`UnrealBloomPass`) à seuil 0,32 — seul l'émissif brille ; **aucun texte n'est dessiné dans le
   canevas** (étiquettes et panneaux sont du DOM), donc aucun texte flouté. Haut : pleine résolution ; Moyen :
   demi-résolution ; Bas : coupé (halos additifs). La lueur du fond suit la lumière du cœur.
6. **Qualité adaptative** (`jarvis-quality.ts`, pur) : moyenne glissante 2 s ; descend après 2 s > 20 ms ; remonte
   après 10 s < 13 ms (jamais au-dessus du niveau de départ) ; ≤ 1 changement / 5 s ; changement en fondu (T-Q
   0,6 s : densité des particules, poussière) ; niveau Bas < 30 i/s pendant 5 s ⇒ **repli 2D** (`low_perf`).
7. Toute propriété visible passe par `Tween` / `ColorTween` (`jarvis-tween.ts`, pur) : interruptible depuis la valeur
   courante, durées en ms sur l'horloge du moteur (même rendu à 30 et 60 i/s), zéro allocation par image.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Niveau de voix absent / `NaN` / > 1 | Ondes bornées 0-1 (0 si invalide). |
| Onglet revenu (intervalle > 250 ms) | Pas une mesure : la fenêtre de qualité repart de zéro, aucun changement. |
| Niveau Bas trop lent 5 s | Repli 2D en fondu, bouton « Vue » retiré pour l'ouverture. |
| Changement d'état pendant une transition | La nouvelle transition part de la valeur affichée (aucun saut). |
| Écran en 2D (toute cause SF-236-01) | Rien de nouveau : aucune minuterie, aucun calcul, orbe 2D visible. |
| Banc (`?banc=`) | Qualité figée (captures reproductibles) ; transitions rejouées depuis t = 0. |

---

## Critères d'acceptation

- [x] CA1 — Chaque humeur a un rendu distinct (test des signatures) ; captures + séquences au banc
  (`docs/features/F-236/captures/coeur/`).
- [x] CA2 — Ondes ∝ niveau (test), nulles hors écoute / parole.
- [x] CA3 — Qualité : descente après 2 s > 20 ms, remontée après 10 s < 13 ms, ≤ 1 changement / 5 s, repli 2D Bas
  < 30 i/s 5 s (tests à horloge simulée).
- [x] CA4 — 0 allocation par image (tampons et uniformes préalloués ; tampon circulaire de qualité testé).
- [x] CA5 — Aucun saut : pas maximal borné par courbe, transitions interruptibles depuis la valeur courante (tests).
- [x] CA6 — Veille après 60 s, réveil par « Jarvis » ou reprise (tests), onde de choc comptée.
- [x] CA7 — En 3D l'orbe 2D s'efface et le bouton « parler » reste (test de l'écran) ; en 2D, écran inchangé.
- [ ] CA8 — ≥ 50 i/s p50 sur le téléphone du PO : **mesure sur appareil réel à faire** (le banc tourne en rendu
  logiciel) ; la qualité adaptative et le repli 2D garantissent le plancher en attendant.

## Périmètre

### Hors scope (explicite)

- Les deux cerveaux (SF-236-03), l'univers et les liaisons (SF-236-04), la caméra et les gestes (SF-236-05), le HUD et
  la mesure `RENDER` (SF-236-06).
- Aucun appel IA, aucune consigne système, description d'outil ou consigne orale modifiée.
- Aucun endpoint, aucune table, aucune migration.

## Contraintes de validation

- Niveau de voix : borné [0, 1], `NaN` ⇒ 0. Intervalle d'image > 250 ms ⇒ ignoré. Pas d'image borné à 50 ms.

---

## Technique

### Endpoint(s) / Tables / Migration
Aucun.

### Composants Angular

| Fichier | Rôle |
|---|---|
| `stage3d/jarvis-tween.ts` | Pur — `Tween`, `ColorTween`, `maxStep`. |
| `stage3d/jarvis-heart.ts` | Pur — humeurs, rendus, transitions, `waveAmplitude`, `SleepWatch`. |
| `stage3d/jarvis-quality.ts` | Pur — `QualityGovernor`. |
| `stage3d/jarvis-stage-director.ts` | Pur — `directStage` (signaux ⇒ plan). |
| `stage3d/engine/jarvis-heart-3d.ts` | Moteur — le cœur (particules, anneaux, ondes, rayons, halo, onde de choc). |
| `stage3d/engine/jarvis-post.ts` | Moteur — bloom à seuil par niveau. |
| `stage3d/engine/jarvis-engine.ts` | Moteur — plan, qualité adaptative, lueur du fond. |
| `stage3d/jarvis-stage-3d.component.ts` | Veille, plan relayé hors zone, repli `low_perf`. |
| `jarvis.component.{ts,html,scss}` | `stageSignals` (lus, jamais écrits), `wakeCalls`, orbe effacée en 3D. |
| `stage3d/jarvis-banc*.ts` | Scènes `ecoute`, `reflexion`, `parole`, `oui`, `coupure`, `veille`, `reveil`. |

## Plan de test

### Tests unitaires (Karma, sans WebGL)
- `jarvis-tween.spec.ts`, `jarvis-heart.spec.ts`, `jarvis-quality.spec.ts`, `jarvis-stage-director.spec.ts`.
- `jarvis-stage-3d.component.spec.ts` : plan suivi (écoute, « oui », coupure), veille / réveil, aucune minuterie en
  2D, qualité suivie et repli `low_perf`, banc sans qualité adaptative.
- `jarvis.component.spec.ts` : cœur 3D demandé, orbe effacée à la première image, bouton « parler » gardé.

### Tests d'intégration
- Visuel : `scripts/jarvis-3d-captures.sh` — chaque humeur, téléphone + PC, et séquence de 6 images du réveil.
- Build : `npm run build` (garde du morceau 3D en `postbuild`).

### Isolation workspace
Sans objet : aucune donnée lue ni écrite, aucun appel réseau ajouté.

## Dépendances
SF-236-01 (livrée, #1459). Questions ouvertes : aucune.

## Préoccupations transversales

| Préoccupation | Cochée ? | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucune route ni paramètre ajouté. |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Bloom **à seuil** sur tout le canevas plutôt qu'un bloom par calque. | Aucun texte n'est dans le canevas (T5/T6) : le seuil suffit à ne faire briller que l'émissif ; une passe de moins, coût moindre sur téléphone. | Bloom sélectif par calque (2 rendus de scène). | Oui. |
| A2 | La veille et les réveils sont tenus par la scène 3D, pas par l'écran. | Aucune minuterie n'existe tant que la 3D n'est pas montée ; l'écran ne fait que compter les « Jarvis ». | Minuterie dans `JarvisComponent` (tourne aussi en 2D pour rien). | Oui. |
| A3 | `ColorManagement` désactivé dans le moteur, sortie linéaire. | Les couleurs §23 sont écrites telles quelles, au canevas comme à travers les passes : même teinte avec et sans bloom. | Conversion sRGB (teintes différentes entre Haut et Bas). | Oui. |
| A4 | « Oui » attendu en **cyan**. | Goût PO V1 : l'orange est la teinte normale, le cyan devient la couleur de l'attente (DESIGN_SYSTEM §23). | Orange (grammaire d'origine). | Oui. |
| A5 | La mesure ≥ 50 i/s sur le téléphone du PO reste à faire sur l'appareil. | Le banc headless tourne en rendu logiciel ; seul l'appareil réel donne un chiffre honnête. Plancher garanti par la qualité adaptative. | Chiffre estimé. | — |
