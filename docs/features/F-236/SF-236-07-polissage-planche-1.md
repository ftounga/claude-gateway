# Mini-spec — [F-236 / SF-236-07] Polissage visuel 1 et planche de l'itération 1

## Identifiant
SF-236-07

## Feature parente
F-236 — Jarvis en 3D (cadrage §7.3, §15 ; itération 1 « Jarvis s'allume », SF de polissage)

## Statut
Livrée — **visa du PO attendu** sur la planche (seule sollicitation de l'itération)

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-07-polissage-planche`

---

## Objectif

Corriger ce que les captures des SF-236-02 à 06 ont montré (lumière, taches, étiquettes) et produire la planche de
référence de l'itération 1 que le PO vise avant l'itération 2.

## Comportement attendu

### Cas nominal

1. **Réglages de lumière** (constats sur les captures) :
   - nébuleuse écartée du cœur (elle passait le seuil du bloom sous le cœur : tache claire en bas à droite, traînées
     roses sur le sol) ;
   - poussière et étoiles qui frôlent la caméra s'effacent (taches floues au premier plan) ;
   - halo du cœur à 60 % (centre saturé en blanc, surtout au téléphone) ; cœurs incandescents (centre blanc) resserrés
     au cœur rapide et au grand cerveau.
2. **Étiquettes** posées sous leur nœud et son halo (clients et autres nœuds), jamais dessus.
3. **Planche** `docs/features/F-236/captures/iteration-1/index.html` : les 7 humeurs du cœur, le grand cerveau
   (déployé, annulé), l'univers (ensemble, cité, « Montrer »), et 6 séquences de transitions (T-V2, T-B1, T-B3, T-E1,
   T-Z1, T-Z2) — téléphone + PC ; toutes les captures refaites après polissage (critère « non vide, sans erreur
   console » vert).
4. **Règles §7.3 vérifiées** sur les séquences :

   | Règle | Vérification |
   |---|---|
   | 1 Jamais de saut | Tests de pas maximal (tween, cerveaux) ; séquences continues. |
   | 2 Interruptible | Tests `Tween`, `BrainsClock` (dans les deux sens), `CameraDirector`. |
   | 3 Indépendant de la cadence | Tests `Tween`, `Spring` (30 / 60 i/s). |
   | 4 ≤ 3 transitions majeures | Caméra (≤ 1 / 2,5 s), cerveaux, cœur : jamais plus de trois à la fois par construction. |
   | 5 Couleurs d'état d'une seule source | `jarvis-motion.ts` (§23) ; aucune autre valeur. |
   | 6 Un seul héros | ≤ 3 nœuds allumés, reste latent (tests du graphe). |
   | 7 Rien ne bouge pendant que le PO parle | Gel caméra à l'écoute (test), dérive arrêtée. |
   | 8 Anti mal des transports | Aucun roulis, focale fixe, ressort critique, ≤ 1 déplacement / 2,5 s. |
   | 9 Lisibilité | Texte en DOM (jamais flouté ni incliné), Space Grotesk, contraste ≥ 5,8:1 (§23). |
   | 10 Le noir n'existe pas | 2D dessous, fondu T-F, captures refaites si la première image tarde. |

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Le PO demande des changements de goût | Traités dans cette SF (nouvelle PR de polissage) avant l'itération 2. |
| Capture headless vide | Refaite (≤ 3) par le script ; sinon échec signalé. |

---

## Critères d'acceptation

- [x] CA1 — Planche produite (96 captures référencées) ; captures non vides, sans erreur console.
- [x] CA2 — Règles §7.3 vérifiées une à une (tableau ci-dessus, tests cités).
- [ ] CA3 — **Visa du PO** : en attente (goût visuel — non bloquant pour la fusion, bloquant pour démarrer
  l'itération 2).
- [ ] CA4 — Mesures i/s sur le téléphone et le PC du PO : à relever sur les appareils (le banc headless est en rendu
  logiciel) ; la mesure `RENDER` (SF-236-06) les remontera en production.

## Périmètre

### Hors scope (explicite)
- Toute nouvelle scène (itération 2), tout nouvel écran.
- Aucun appel IA, aucune consigne touchée ; aucun endpoint, aucune table.

---

## Technique

| Fichier | Rôle |
|---|---|
| `stage3d/engine/jarvis-engine.ts` | Nébuleuse écartée du cœur ; points proches de la caméra effacés. |
| `stage3d/engine/jarvis-heart-3d.ts` | Halo à 60 %, centre incandescent resserré. |
| `stage3d/engine/jarvis-brain-3d.ts` | Centre du champ resserré. |
| `stage3d/engine/jarvis-universe-3d.ts` | Étiquettes sous leur nœud. |
| `docs/features/F-236/captures/iteration-1/index.html` + captures refaites | La planche. |

## Plan de test
- Suites Jarvis (aucune logique modifiée : réglages de shaders et de positions) + suite frontend complète + build.
- Visuel : captures refaites, critère automatique vert.

## Dépendances
SF-236-02 → 06 (livrées).

## Préoccupations transversales
Aucune cochée.

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Fusion sans attendre le visa ; l'itération 2 ne démarre pas avant. | Le visa porte sur le goût (réversible) ; l'itération 1 est déployable seule. | Bloquer la PR sur le visa. | Oui. |
| A2 | Planche dans le dépôt, non publiée en page partagée. | Ses 96 images sont déjà dans le dépôt ; l'orchestrateur la remet au PO avec le déploiement unique. | Publication séparée. | Oui. |
