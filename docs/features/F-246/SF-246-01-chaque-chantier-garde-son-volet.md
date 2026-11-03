# Mini-spec — F-246 / SF-246-01 Chaque chantier garde son volet

## Identifiant
`F-246 / SF-246-01`

## Feature parente
`F-246` — Les volets restent où on les met (cadrage `CADRAGE-F-246-les-volets-restent-ou-on-les-met.md`)

## Statut
`in-review` · **Estimation :** 1,5 j · **Branche :** `feat/SF-246-01-volets-stables` · **Date :** 2026-11-03 · **Dépend de :** F-221 (volets), F-226 (onglets, états)

## Objectif
Un chantier reste dans le volet où l'utilisateur l'a mis — le focus du serveur ne le déplace plus —, tout chantier ouvert se rouvre d'un geste, et une carte toujours visible dit quel chantier occupe quel volet.

## Comportement attendu

### Cas nominal
1. **Disposition** `{ left, right }` (numéros de chantier) tenue par l'écran (`pane-layout.ts`, fonctions pures). Le terminal de gauche est **épinglé** sur `left` (`laneChantier` + `primaryPane`) : il ne suit plus le focus du serveur ; il garde ce qu'il avait (suggestion, carte de situation, chantiers relus).
2. **Arrivée sur le sujet** : la disposition gardée (`localStorage` `cg.atelier.panes.<sujet>`) si ses chantiers sont encore ouverts ; sinon le focus à gauche et le plus ancien autre chantier actif à droite (F-221 inchangé).
3. **Carte des volets** (`app-terminal-pane-map`), une ligne au-dessus des volets dès 2 chantiers ouverts (ou un split) : par chantier, « Gauche »/« Droite » ou rien, « Ch.N · titre », l'état (« tourne », « à vous »), boutons ◧ ◨ (`aria-pressed`, `aria-label` « Afficher le chantier N à gauche/droite »). Placer un chantier déjà dans l'autre volet **échange** les deux.
4. **Gestes** : [Fermer ce volet] droite → plus de split ; gauche → le chantier de droite reste seul. « Basculer sur N » / « Mettre au focus » / [Afficher ici] dans un volet → N dans **ce** volet. « Ouvrir le chantier » (acceptation d'un chantier de plus) ou lancement en parallèle depuis un volet → le chantier dans **l'autre** volet ; la reprise « ✓ Nouveau chantier ouvert — investigation lancée. » part dans **son** fil (volet de droite : `sendText`, brouillon gardé ; gauche : envoi direct, brouillon rendu).
5. **Focus serveur** : mettre un chantier à gauche par un geste le met aussi au focus (best-effort) ; un focus qui change côté serveur (chantier ouvert ailleurs, par l'agent, autre appareil) ne change rien à l'écran — le nouveau chantier apparaît dans la carte.
6. **Chantier clos** : il quitte son volet ; un volet de gauche vidé prend le focus (s'il n'est pas déjà à droite), sinon un autre chantier ouvert, sinon celui de droite. Plus aucun chantier : plus de volet.
7. **Fils jamais mélangés** : un volet qui change de chantier efface aussitôt l'ancien fil et l'ancien brouillon (volet de droite) et relit le parcours de **son** chantier (bande, barre, tableau) — y compris le volet de gauche.
8. En-têtes : « Gauche — Chantier 9 · titre », « Droite — Chantier 11 · titre ».

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Stockage local bloqué / illisible / trafiqué | disposition en mémoire seulement ; valeurs non entières ou ≤ 0 ignorées | — |
| Chantier gardé clos depuis | écarté à l'arrivée et à chaque relecture des chantiers | — |
| `POST …/focus` refusé | la disposition reste ; rien n'est affiché (best-effort) | 4xx/5xx |
| Lancement en parallèle refusé (409 « tourne déjà ») | message « il s'affiche à gauche/à droite », le chantier est quand même placé | 409 |
| Sujet d'un autre utilisateur | inchangé : toutes les lectures restent `requireOwned` → 404 | 404 |
| Volet de droite qui a changé de chantier avant l'envoi de la reprise | rien n'est envoyé (`sendText` vérifie le chantier) | — |

## Critères d'acceptation
- [x] 10 à gauche, 11 à droite ; ouvrir 9 à gauche : 11 reste à droite.
- [x] Le focus serveur qui change (chantier ouvert ailleurs) ne déplace aucun volet.
- [x] Fermer un volet puis rouvrir le même chantier depuis la carte, des deux côtés.
- [x] Recharger : même disposition, même chantier inactif, même focus déplacé.
- [x] « Ouvrir le chantier » depuis la gauche : il arrive à droite et sa reprise part dans son fil (rien dans le fil de gauche) ; depuis la droite : à gauche.
- [x] Lancer en parallèle depuis la droite : il arrive à gauche ; [Afficher ici] depuis la droite : à droite.
- [x] Échanger les volets pendant un tour : chaque volet rejoint le tour de son chantier.
- [x] La carte est visible dès 2 chantiers, dit le volet et l'état de chacun.
- [x] Aucun fil ni parcours d'un autre chantier dans un volet, à chaque étape (banc).
- [x] 1366×768 et 360 px : aucun défilement horizontal ; cibles ≥ 44 px sous 820 px.

## Plan de test
- **Unitaires** : `pane-layout.spec.ts` (arrivée, placer/échanger, à côté, fermer/rouvrir, clos, stockage) ; `terminal-pane-map.component.spec.ts` ; `terminal-volet-epingle-f246.spec.ts` (Basculer → `showChantier`, relecture du parcours au changement de chantier, chantiers relus seulement une fois le parcours lu, « Ouvrir le chantier » → `chantierOpened` sans envoi) ; `terminal-lane-pane.component.spec.ts` (+2 : effacement au changement, `sendText`).
- **Composant** : `atelier.component.spec.ts` (+13, chaque parcours du PO ; tests F-221 adaptés ; `split-for.spec.ts` retiré avec `splitFor`, remplacé par `arrivalLayout`).
- **Bout en bout headless** : banc puppeteer (Chrome sans `--hide-scrollbars`, API simulée) — arrivée, lancer les deux, ouvrir 9, fermer/rouvrir, recharger, chantier ouvert ailleurs, ouvrir un chantier, échanger pendant un tour, onglets, fuite de fil à chaque étape — à 1366×768 et 360×740. Avant : 6 défauts ; après : 0.
- **Isolation** : aucune lecture nouvelle ; la clé de stockage porte l'identifiant du sujet (jamais d'autre sujet relu) ; changement de sujet → disposition vide puis celle de CE sujet (test).
- Suite frontend complète + build.

## Impact
Frontend seulement : `atelier/pane-layout.ts` (nouveau), `atelier/terminal/terminal-pane-map.component.*` (nouveau), `atelier.component.{ts,html,scss}`, `terminal/atelier-terminal.component.ts` (`laneChantier` en accesseur, `primaryPane`, `showChantier`, `chantierOpened`), `terminal/terminal-lane-pane.component.ts`. Aucune table, aucun endpoint, aucune migration.

**Préoccupations transversales** : Navigation / routing — non (aucune route ni garde). Auth, tenant, plans — non. Composants qui lisaient le split : `AtelierComponent` (envoi, historique, rebranchement, interruption, autorisation, précision par `chatLane`), `TerminalLanePaneComponent`, `AtelierTerminalComponent` (suggestion, tableau, bande, situation) — vérifiés par les tests ci-dessus.

## Arbitrages (documentés dans la PR)
- **Simplification d'interaction** : la disposition ne bouge plus qu'aux gestes de l'utilisateur (carte, fermer, ouvrir/lancer à côté, basculer dans un volet). L'ancien « split automatique dès qu'un chantier devient actif » est retiré après l'arrivée : un chantier qui démarre apparaît « tourne » dans la carte, sans prendre de place.
- **Gardée sur l'appareil**, pas côté serveur : préférence d'affichage par appareil (téléphone ≠ PC), aucune table. Réversible.
- Le focus serveur suit la gauche sur geste (Cap, Jarvis) mais l'écran ne suit plus le focus.

## Hors périmètre
Passé commun replié dans le fil d'un chantier (SF-246-02) ; plus de deux volets ; redimensionnement ; disposition partagée entre appareils.
