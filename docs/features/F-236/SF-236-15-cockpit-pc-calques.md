# Mini-spec — [F-236 / SF-236-15] Mise en page PC multi-calques (le cockpit)

## Identifiant
SF-236-15

## Feature parente
F-236 — Jarvis en 3D (cadrage §5, §6.1, §12.1, §15 ; itération 3 « Le cockpit PC »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-15-cockpit-pc`

---

## Objectif

Sur PC large, faire de `/jarvis` en 3D un **cockpit** : calques L0-L5 complets (profondeur de champ, SMAA, passe de
finition) et **panneaux latéraux en verre** repliables, dont chaque ligne tend un **filament** vers son nœud 3D.

## Comportement attendu

### Cas nominal

1. **Cockpit** = 3D affichée, pas en mode sobre, pas téléphone, largeur ≥ 1280 px. Sinon rien ne change (écran de
   l'itération 2).
2. **Panneaux latéraux** (calque L5, DOM en verre holographique §7.2, navy 72 %, liseré orange, coins chanfreinés,
   flou d'arrière-plan) : gauche 300 px « Univers » (postes puis sujets, état écrit : connecté / déconnecté /
   hébergé / au travail ; sujet courant marqué) ; droite 320 px « Tour en cours » (état, action en cours, appels
   d'outil, délégations). Les SF suivantes y ajoutent leurs vues.
3. **T-P4** : chaque panneau se replie / se déplie (320 ms, `glisse`), poignée de 28 px toujours visible ; repli
   **retenu par appareil** (`jarvis.cockpit.replis`). Panneaux repliés ⇒ univers **pleine largeur** ; un seul
   ouvert ⇒ l'univers se **recentre** dans la zone libre (fenêtre de caméra décalée, même focale, glissement sans
   saut) ; aucun hologramme ne se pose sous un panneau ouvert.
4. **Filaments** : survol ou focus clavier d'une ligne ⇒ ligne lumineuse (orange, pointillée, halo) du bord de la
   ligne jusqu'au nœud projeté à l'écran, suivie image par image hors zone ; nœud absent ou derrière la caméra ⇒
   pas de filament. Toucher un sujet le choisit (`touchSubject`) ; toucher un poste ne navigue pas.
5. **Calques** (`jarvis-post-chain.ts`, pur) : profondeur de champ du calque L0 (PC, Haut) — étoiles et poussière
   hors du plan de netteté s'élargissent en disques doux, la netteté suit le point visé ; **SMAA** (PC Haut/Moyen) ;
   **finition** (vignette 22 %, grain 1,8 %, aberration chromatique sur les bords ; PC Haut/Moyen). Téléphone :
   aucun des trois. Le texte n'est jamais touché (DOM).
6. Sous-titres et commandes restent dans la zone libre entre les panneaux.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Stockage local indisponible | Panneaux ouverts ; le repli vaut pour l'ouverture. |
| Nœud d'une ligne non posé (univers tronqué, derrière la caméra) | Pas de filament, aucune erreur. |
| Univers non lu | Liste vide avec « Aucun poste lu pour l'instant. » |
| Fenêtre réduite sous 1280 px | Le cockpit disparaît, l'univers reprend toute la largeur. |
| Perte de contexte / repli 2D | `shown3d` faux ⇒ cockpit retiré (2D F-234 inchangé). |

---

## Critères d'acceptation

- [x] CA1 — Table de vérité du mode (cockpit / téléphone / rien) testée.
- [x] CA2 — Panneaux repliés ⇒ insets nuls (univers pleine largeur) ; un seul ouvert ⇒ décalage = moitié de
  l'écart ; repli retenu par appareil (tests).
- [x] CA3 — Filament : coordonnées = bord de la ligne → projection du nœud ; nœud absent ⇒ éteint (test).
- [x] CA4 — Chaîne de rendu par niveau et appareil testée (bokeh PC Haut ; SMAA/finition PC Haut/Moyen ; rien au
  téléphone).
- [x] CA5 — Aucun hologramme sous un panneau ouvert (zones réservées, test).
- [x] CA6 — Bundle initial inchangé (485,20 ko) ; `three` hors bundle initial (garde).
- [x] CA7 — Captures PC `cockpit`, `cockpit-replie`, `cockpit-filament` non vides, sans erreur console.
- [ ] CA8 — ≥ 60 i/s p50 sur le PC du PO en 1920 × 1080 (et rapport 2560 × 1440) : à relever en usage
  (événement `RENDER`, histogramme d'i/s) — le banc tourne en rendu logiciel.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Largeur minimale du cockpit | 1280 px |
| Panneaux | gauche 300 px, droite 320 px, marge 16 px, poignée 28 px |
| T-P4 | 320 ms, `glisse` |
| Bokeh | élargissement ≤ × 2,6 (1 + 1,6), énergie conservée |
| Finition | vignette 0,22 ; grain 0,018 ; aberration 0,006 |

## Plan de test

- Unitaires : `hud/jarvis-cockpit-layout.spec.ts` (mode, insets, décalage, repli, chaîne de rendu, zones réservées).
- Composant : `hud/jarvis-cockpit.component.spec.ts` (panneaux, liste, repli, filament, choix de sujet, vide) ;
  `jarvis-stage-3d.component.spec.ts` (insets relayés au moteur, projection).
- Non-régression : suites Jarvis complètes (`jarvis.component.spec.ts` : `?sujet=`, `?alerte=`, `?source=compagnon`).
- Visuel : banc `cockpit`, `cockpit-replie`, `cockpit-filament` (PC 1920 × 1080).
- Isolation utilisateur : sans objet (aucune donnée nouvelle, aucun appel réseau).

## Périmètre

### Impacté
`stage3d/hud/jarvis-cockpit-layout.ts`, `stage3d/hud/jarvis-cockpit.component.{ts,html,scss}`,
`stage3d/jarvis-post-chain.ts`, `engine/jarvis-post.ts` (SMAA, finition), `engine/jarvis-engine.ts` (bokeh,
insets, `project` public), `jarvis-engine.contract.ts` (`setInsets`, `project`), `jarvis-panels.ts` (zones
latérales), `jarvis-stage-3d.component.ts` (`insets`, `project`), `jarvis-motion.ts` (`sidePanel`,
`sideFilament`), banc (`cockpit*`), `jarvis.component.{html,ts,scss}`, DESIGN_SYSTEM §23.

### Hors scope (explicite)
- Contenu des vues (journée, météo, chronologies, personnes, inspecteur, recherche) : SF-236-16 → 19.
- Téléphone : SF-236-20. Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Navigation / routing | Non | Aucune route ni paramètre ; toucher un sujet passe par `touchSubject` existant. |
| Auth / tenant / plans | Non | — |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Profondeur de champ **dans le shader des particules** L0 (disques qui s'élargissent hors du plan de netteté). | Les particules additives n'écrivent pas la profondeur : une `BokehPass` floutait le cœur et les filaments. | `BokehPass` sur tampon de profondeur. | Oui. |
| A2 | Sol polaire (L1) non flouté. | Lignes fines : le flou les ferait disparaître ; la profondeur se lit déjà par le brouillard. | Flou moyen du sol. | Oui. |
| A3 | Finition placée avant SMAA ; SMAA aussi en Moyen. | Le MSAA du canevas ne vaut plus dès que l'image passe par les tampons du bloom. | SMAA en Haut seulement. | Oui. |
| A4 | Décalage du centre par **fenêtre de caméra** (`setViewOffset`). | Même focale, aucune déformation, picking et projection cohérents. | Déplacer la caméra (parallaxe parasite). | Oui. |
| A5 | Seuil du cockpit 1280 px. | En dessous, deux panneaux de 300 px laissent < 600 px à l'univers. | 1440 px. | Oui. |
| A6 | Morceau 3D 166 → 208 ko gzip (SMAA embarque ses textures). | Le poids n'est pas un frein (D3) ; garde d'accident à 800 ko. | FXAA (moins net). | Oui. |
