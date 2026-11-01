# Mini-spec — [F-236 / SF-236-01] Socle du moteur 3D, repli 2D, banc, palette

## Identifiant
SF-236-01

## Feature parente
F-236 — Jarvis en 3D (cadrage `docs/features/F-236/CADRAGE-F-236-jarvis-en-3d.md`, itération 1 « Jarvis s'allume »)

## Statut
Livrée (PR à la suite de cette mini-spec)

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-01-socle-moteur-3d`

---

## Objectif

Poser le socle du rendu 3D de `/jarvis` — moteur Three.js dans un second morceau paresseux, dessiné hors zone
Angular, avec repli 2D automatique, banc de captures et palette §23 — sans rien changer pour un appareil qui reste
en 2D.

## Goût du PO (réponses du 2026-11-02 aux questions §17 du cadrage)

| # | Question | Réponse du PO | Effet dans cette SF |
|---|---|---|---|
| V1 | Teinte dominante | **Orange de la marque** (l'alternative) | Normal = `--cg-orange` `#E07B39` (émissif) / `--cg-orange-2` `#F0954F` (texte) ; **l'attente passe en cyan** `#5FD4FF` ; violet (Opus), vert, rouge, gris inchangés. |
| V2 | Police du HUD | **Space Grotesk** (le défaut) | Aucune police nouvelle. |
| V3 | Densité du décor | **Riche** (le défaut) | Lueur d'abysse, nébuleuse, étoiles sur trois profondeurs, poussière, sol polaire dès le socle. |

## Comportement attendu

### Cas nominal

1. L'écran `/jarvis` s'affiche **tout de suite en 2D** (F-234, inchangé).
2. Le relevé de capacité (T10) décide 3D ou 2D. En 3D, le morceau `jarvis-engine` (seul porteur de `three@0.186.1`)
   est chargé par `import()`, le moteur dessine **hors zone Angular** dans un canevas plein écran posé **derrière**
   l'écran F-234 (z-index 0), et apparaît en **fondu T-F** (400 ms, `glisse`) à sa première image.
3. Au socle, la 3D de `/jarvis` dessine le **décor riche** (L0/L1) ; l'orbe 2D F-231 reste le cœur et la
   constellation F-234 reste devant (le cœur 3D arrive en SF-236-02, l'univers 3D en SF-236-04).
4. Bouton **« Vue 2D / Vue 3D »** à côté de « Mode sobre », proposé seulement si la 3D est possible sur l'appareil ;
   choix retenu par appareil (`localStorage` `cg.jarvis.view`, sous try/catch).
5. Onglet caché ⇒ la boucle s'arrête (zéro image) ; reprise au retour.
6. Fermeture de l'écran ⇒ `dispose()` : géométries, matières, rendu, contexte (`forceContextLoss`), écouteurs,
   observateur de taille, canevas retiré.
7. **Banc** `/jarvis-banc?banc=coeur&t=<ms>[&niveau=haut|moyen|bas]` (développement seulement) : horloge figée,
   hasard graine, rendu logiciel admis, cœur provisoire dessiné ; `frontend/scripts/jarvis-3d-captures.sh` produit
   les captures téléphone (390 × 844, Moyen) et PC (1920 × 1080, Haut) et vérifie « ≥ 2 % hors fond, aucune erreur
   console ».

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Pas de WebGL2 / rendu logiciel (`failIfMajorPerformanceCaveat`) / `prefers-reduced-motion` / `saveData` / mode sobre / « Vue 2D » / contexte perdu deux fois | 2D, **identique à aujourd'hui** ; le morceau 3D n'est **jamais demandé** ; aucun canevas. |
| Échec du chargement du morceau (réseau, CSP) | 2D, sans message. |
| Création du moteur refusée | 2D, sans message, rien de résiduel. |
| Contexte WebGL perdu (1re fois) | Bascule **immédiate** en 2D (fondu), restauration tentée ; restauré ⇒ retour en 3D. |
| Contexte perdu une 2e fois | 2D pour la session, moteur libéré, bouton « Vue » retiré. |
| Bascule vers 2D puis retour en 3D pendant le fondu de sortie | La libération est annulée, aucune double instance. |
| Écran fermé avant la fin du chargement | Aucun moteur créé. |
| `?banc=` en production | Ignoré (`isDevMode()` faux) ; la route `/jarvis-banc` n'est pas appariée. |
| `?niveau=` inconnu (y compris `constructor`) | Niveau de l'appareil. |

---

## Critères d'acceptation

- [x] CA1 — Table de vérité de capacité testée : chaque cause seule ⇒ 2D avec sa raison ; aucune ⇒ 3D au bon niveau.
- [x] CA2 — En repli, le chargeur du morceau 3D n'est **jamais appelé** (test par cause + test de l'écran Jarvis).
- [x] CA3 — Bundle initial : `three` absent (garde `check-jarvis-3d-budget.mjs` en `postbuild`) ; morceau 3D
  133 ko gzip, garde d'accident à 800 ko.
- [x] CA4 — Onglet caché ⇒ 0 image (test de la boucle à hôte simulé).
- [x] CA5 — 10 ouvertures / fermetures ⇒ 0 moteur vivant (test).
- [x] CA6 — `?banc=` inopérant en production (test) ; route du banc `canMatch: isDevMode()`.
- [x] CA7 — Captures non vides (≥ 2 % hors fond), sans erreur console : 6 captures `docs/features/F-236/captures/socle/`.
- [x] CA8 — DESIGN_SYSTEM §23 écrit (V1 orange), contrastes mesurés et testés (tout texte ≥ 5,8:1), une seule source
  (`jarvis-motion.ts` → propriétés CSS sur l'hôte).
- [x] CA9 — Fondu T-F 400 ms `glisse` à l'apparition et à la disparition ; jamais de noir (le 2D est dessous).
- [x] CA10 — Perte de contexte (T11) testée : 1re ⇒ 2D puis 3D à la restauration ; 2e ⇒ 2D pour la session.

## Périmètre

### Hors scope (explicite)

- Le **cœur 3D** et ses états, le bloom sélectif, la qualité adaptative (SF-236-02) ; les deux cerveaux (SF-236-03) ;
  l'univers et les liaisons (SF-236-04) ; caméra et gestes (SF-236-05) ; HUD et mesure `RENDER` (SF-236-06).
- Aucun appel IA, aucune consigne système, description d'outil ou consigne orale modifiée (préfixe intact).
- Aucun endpoint, aucune table, aucune migration.

## Valeurs initiales

- Niveau de départ (T10) : Haut si `pointer: fine` et largeur ≥ 1024 px ; Bas si `deviceMemory ≤ 3` ou
  `hardwareConcurrency ≤ 4` ; Moyen sinon. Ratio de pixels plafonné 2 / 1,5 / 1.
- Décor par niveau : Haut 3 × 900 étoiles, 600 poussières, nébuleuse, 7 cercles ; Moyen 2 × 500, 250, nébuleuse,
  5 cercles ; Bas 1 × 300, 0, sans nébuleuse, 3 cercles.

## Contraintes de validation

- `banc` : `[a-z0-9-]{1,40}` parmi les scènes connues, sinon `coeur` ; `t` ∈ [0, 3 600 000] ms, sinon 0 ;
  `niveau` ∈ {`haut`, `moyen`, `bas`}, sinon niveau de l'appareil.

---

## Technique

### Endpoint(s)
Aucun.

### Tables impactées
Aucune.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)

| Fichier | Rôle |
|---|---|
| `jarvis/stage3d/jarvis-motion.ts` | Pur — palette §23, paliers de transparence, courbes, durées, `holoCssVars()`. |
| `jarvis/stage3d/jarvis-render-capability.ts` | Pur — `decideRender`, `startLevel`, `canOffer3d`, `ContextLossPolicy`, préférence « Vue 2D », relevé navigateur injectable. |
| `jarvis/stage3d/jarvis-banc.ts` | Pur — lecture du banc (dev), horloge figée, PRNG graine. |
| `jarvis/stage3d/jarvis-frame-loop.ts` | Pur — boucle d'images, arrêt onglet caché, pause / arrêt définitif. |
| `jarvis/stage3d/jarvis-engine.contract.ts` | Contrat du moteur sans `three` + jeton de chargement paresseux. |
| `jarvis/stage3d/engine/jarvis-engine.ts` | Moteur Three.js (seul importeur de `three`). |
| `jarvis/stage3d/jarvis-stage-3d.component.ts` | Calque 3D : décision, chargement, hors zone, fondu, perte de contexte, `dispose`. |
| `jarvis/stage3d/jarvis-banc.component.ts` + route `/jarvis-banc` | Page du banc (dev seulement). |
| `jarvis/jarvis.component.{ts,html,scss}` | Calque 3D derrière l'écran, bouton « Vue », jetons CSS sur l'hôte. |
| `frontend/scripts/check-jarvis-3d-budget.mjs` (`postbuild`), `jarvis-3d-captures.sh`, `png-coverage.mjs` | Garde d'accident, captures, critère « non vide ». |

## Plan de test

### Tests unitaires (Karma, sans WebGL)
- `jarvis-motion.spec.ts` : V1 orange / attente cyan, couleurs d'état distinctes, contrastes ≥ 5,8:1, 5 paliers,
  courbes (bornes, monotonie), propriétés CSS.
- `jarvis-render-capability.spec.ts` : table de vérité, niveaux, offre du bouton, T11, préférence + stockage bloqué.
- `jarvis-banc.spec.ts` : production ⇒ rien, lecture, bornes, niveau imposé (et `constructor` refusé), déterminisme.
- `jarvis-frame-loop.spec.ts` : images, onglet caché ⇒ 0, pause/reprise, arrêt sans écouteur résiduel.
- `jarvis-stage-3d.component.spec.ts` : chaque cause ⇒ chargeur jamais appelé ; chargement unique + fondu à la
  première image ; « Vue 2D » (fondu 400 ms puis libération) et retour ; annulation pendant le fondu ; 10 ouvertures
  sans fuite ; fermeture pendant le chargement ; échec du chargement ; T11 ; banc.
- `jarvis.component.spec.ts` : écran 2D inchangé sans WebGL (aucun canevas 3D, aucun bouton, jetons posés) ; bouton
  « Vue 2D » proposé si la 3D est possible.

### Tests d'intégration
- Visuel : `scripts/jarvis-3d-captures.sh` (Chrome headless swiftshader) — 6 captures, critère automatique.
- Build : `npm run build` ⇒ `postbuild` vérifie `three` hors du bundle initial et le poids du morceau.

### Isolation workspace
Sans objet : aucune donnée lue ni écrite, aucun appel réseau ajouté (le moteur ne connaît ni le bus ni l'API).

## Dépendances

### Subfeatures bloquantes
Aucune (première SF du projet F-236).

### Questions ouvertes impactées
Aucune (`docs/OPEN_QUESTIONS.md` non concerné).

## Préoccupations transversales

| Préoccupation | Cochée ? | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — (la page du banc n'a pas de garde mais n'existe qu'en développement et n'appelle aucune API) |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | **Oui** | `app.routes.ts` : route `/jarvis-banc` ajoutée **avant** `/jarvis`, `canMatch: isDevMode()` ⇒ en production elle n'est jamais appariée et l'adresse retombe sur le joker existant ; `/jarvis` (garde `authGuard`), `?sujet=`, `?alerte=`, `?source=compagnon` inchangés (tests `jarvis.component.spec.ts` verts). |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Au socle, la 3D de `/jarvis` est un **calque de décor derrière** l'écran F-234 (orbe et constellation devant, inchangés) ; le cœur provisoire n'est dessiné qu'au banc. | Livrable seul sans régression : rien ne disparaît avant que SF-02/04/05 n'apportent cœur, univers et touchers 3D ; pas de cœur en double. | Remplacer dès maintenant le 2D par la 3D (perte des touchers et de la constellation jusqu'à SF-05). | Oui (`withCore`, gabarit). |
| A2 | Page `/jarvis-banc` (dev seulement) pour le banc, en plus de `?banc=` lu par le calque. | `/jarvis` exige une session : un Chrome headless sans compte ne peut pas l'ouvrir. | Jeton de test injecté dans le script (secret hors du code impossible à garantir). | Oui. |
| A3 | `niveau=` imposable au banc. | La machine de capture (4 cœurs) tombe en niveau Bas ; les captures de référence doivent montrer Moyen (téléphone) et Haut (PC). | Captures au niveau de la machine (non représentatives). | Oui. |
| A4 | La garde de poids tourne en `postbuild` (donc dans l'image Docker). | Un accident (`three` dans le bundle initial) doit casser le build, pas passer en production. | Script manuel (oublié). | Oui. |
| A5 | Bundle initial : +≈ 0,2 ko non gzip (l'entrée de route du banc, présente mais jamais appariée en production) ; `three` : 0. | Une route `canMatch` est déclarée statiquement. | Route ajoutée conditionnellement (impossible sans environnement de build dédié). | Oui. |
