# Mini-spec — [F-236 / SF-236-05] Caméra, gestes, transitions d'écran

## Identifiant
SF-236-05

## Feature parente
F-236 — Jarvis en 3D (cadrage §7.4, §9.3, §9.4 ; itération 1 « Jarvis s'allume »)

## Statut
Livrée (PR à la suite de cette mini-spec)

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-05-camera-gestes`

---

## Objectif

Faire bouger la caméra comme un opérateur calme — vers ce dont on parle, jamais pendant que le PO parle, sans roulis
ni à-coups — laisser le PO tourner, pincer et toucher l'univers, et donner à l'écran une entrée et une sortie.

## Comportement attendu

### Cas nominal

1. **Directeur de caméra** (`jarvis-camera-rules.ts`, pur) :
   - repos ⇒ vue d'ensemble (sphère englobante ajustée au plus petit champ, portrait compris) ;
   - nœud cité (le premier allumé) ⇒ cadré : client 7 · sujet 5 · chantier 3,5 · étape / personne 2,5 (× 1,5 en
     portrait), la caméra au-delà du nœud côté extérieur (le cœur n'est jamais traversé) ;
   - changement de sujet ⇒ dézoom immédiat ; 10 s sans citation ⇒ vue d'ensemble ; autorisation S2 ⇒ recul 20 % ;
     grand cerveau déployé ⇒ recul 15 % (SF-236-03) ;
   - **écoute ⇒ aucun déplacement ne démarre** ; **≤ 1 déplacement / 2,5 s**.
2. **Ressort amorti critique** (ω = 5,5 rad/s, solution exacte) sur lacet, tangage, distance et point visé : aucun
   dépassement, même trajet à 30 et 60 i/s ; le lacet prend le chemin court (arcs autour du cœur) ; **aucun roulis**
   (axe vertical fixe), focale fixe 55°.
3. **Gestes** (`engine/jarvis-gestures.ts`) : un doigt / la souris tourne (lacet libre, tangage ±35°) ; pincer /
   molette zoome (2,5-60) ; double toucher ou double clic dans le vide, ou `Échap` ⇒ vue d'ensemble ; un toucher bref
   ⇒ **sélection en espace écran** (nœud le plus proche à ≤ 28 px : cible ≥ 44 px à toute profondeur) :
   - sujet ⇒ « passe sur X » (`touchSubject`, comme les étiquettes) ;
   - client, chantier, étape, personne ⇒ la caméra le **regarde**, aucune navigation (D9) ;
   - le cœur ⇒ `talk()` (comme l'orbe).
   Après le dernier geste, la caméra revient d'elle-même au plan du directeur au bout de 8 s (T-Z4, ressort) ; pendant
   ce temps les ordres attendent, sauf S2.
4. **Entrée** : T-E1 (2,6 s) à la première ouverture du jour — fond qui s'éclaire, cœur qui s'allume (`rebond`),
   caméra qui recule depuis l'intérieur du cœur, orbites (sol) qui se tracent, clients allumés en cascade (70 ms),
   HUD qui glisse depuis les bords (1,9-2,6 s) ; T-E2 (0,9 s) aux ouvertures suivantes ; **sautée au premier
   toucher** (fondu de 300 ms vers l'état final). Le jour est retenu par appareil (`cg.jarvis.entry`, try/catch).
5. **Sortie T-S** (0,5 s) : le HUD s'efface (200 ms), l'univers se replie vers le cœur (350 ms), le cœur se réduit
   à un point (150 ms), puis navigation vers `/atelier`. En 2D : navigation immédiate.
6. En 3D, le vide de l'écran laisse passer les gestes vers l'univers ; tout ce qui se touche dans l'écran le reste.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Toucher dans le vide (ni nœud, ni cœur) | Rien. |
| Toucher un nœud invisible (entrée en cours, derrière la caméra) | Ignoré. |
| Plusieurs citations rapprochées | Au plus un déplacement / 2,5 s ; la dernière citation l'emporte au déplacement suivant. |
| Stockage bloqué (jour de l'entrée) | T-E2. |
| « Quitter » cliqué deux fois pendant la sortie | Une seule navigation. |
| Banc (`?banc=`) | Pas d'entrée sauf scènes `entree` / `entree-courte` ; zoom / dézoom rejoués depuis t = 0 (séquences). |

---

## Critères d'acceptation

- [x] CA1 — Aucun déplacement ne démarre en `ecoute` (test).
- [x] CA2 — ≤ 1 déplacement / 2,5 s (test) ; aucun roulis (construction : `lookAt` à axe vertical, test du ressort).
- [x] CA3 — Changement de sujet ⇒ dézoom ; 10 s sans citation ⇒ vue d'ensemble (tests).
- [x] CA4 — Toucher client **ne navigue pas** ; toucher sujet ⇒ `touchSubject` ; cœur ⇒ « parler » (tests).
- [x] CA5 — Cible ≥ 44 px à toute profondeur (rayon de sélection 28 px, test ; étiquettes-boutons ≥ 44 px).
- [x] CA6 — Entrée sautée au premier toucher (test du calque) ; T-E1 la première fois du jour, T-E2 ensuite (test).
- [x] CA7 — Séquences T-E1 (6), T-Z1 zoom (6), T-Z2 dézoom (6), téléphone + PC (`docs/features/F-236/captures/camera/`).
- [ ] CA8 — Séquence T-K (changement de client) : non capturée au banc (elle combine T-Z2, T-Z1 et la relecture
  SF-236-04, chacune capturée) ; fil d'Ariane réécrit lettre à lettre reporté (le fil reste celui de F-234).

## Périmètre

### Hors scope (explicite)
- Fiche holographique de l'inspecteur au toucher d'un nœud (SF-236-19) ; volets latéraux PC (`[` / `]`), plein écran
  `F`, recherche `/` (itération 3).
- Profondeur de champ réglée sur le nœud visé (PC, itération 3, SF-236-15).
- Aucun appel réseau, aucune consigne touchée ; aucun endpoint, aucune table.

## Contraintes de validation
- Zoom borné 2,5-60 ; tangage ±35° ; rayon de sélection 28 px ; retour automatique 8 s ; entrée sautée ⇒ 300 ms.

---

## Technique

| Fichier | Rôle |
|---|---|
| `stage3d/jarvis-camera-rules.ts` | Pur — `CameraDirector`, `Spring`, `nodeShot` / `overviewShot`, `entryFrame`, `exitFrame`, `entryKindToday`, `pickNearest`. |
| `stage3d/engine/jarvis-gestures.ts` | Gestes du canevas (glisser, pincer, molette, double toucher, `Échap`, toucher). |
| `stage3d/engine/jarvis-engine.ts` | Rig de caméra à ressorts, entrée, sortie, toucher (cœur / nœud), banc rejoué. |
| `stage3d/engine/jarvis-universe-3d.ts` | Cascade d'entrée par client, `pickables` (projection au toucher). |
| `stage3d/jarvis-stage-director.ts` | `UniverseView.focus` / `subjectId` ; plan `bancCamera` (banc). |
| `stage3d/jarvis-stage-3d.component.ts` | Sortie `talk`, `entry`, `skipEntry()`, `leave(then)` ; gestes reçus en 3D. |
| `jarvis.component.{ts,html,scss}` | Lien « Quitter » ⇒ `leave` (T-S), premier toucher ⇒ entrée sautée, HUD qui glisse (T-E1), vide transparent aux gestes en 3D. |
| `stage3d/jarvis-banc*.ts` | Scènes `entree`, `entree-courte`, `zoom`, `dezoom`, `focus-chantier`. |

## Plan de test
- `jarvis-camera-rules.spec.ts` : directeur (repos, nœud, portrait, écoute, 2,5 s, sujet, 10 s, S2, côté extérieur),
  ressort (pas de dépassement, cadence), lacet court, T-E1 / T-E2 / T-S, jour de l'entrée, sélection ≤ 28 px.
- `jarvis-stage-3d.component.spec.ts` : cœur ⇒ `talk` ; `leave` en 3D (sortie puis 500 ms) et avant affichage
  (immédiat) ; entrée hors banc / au banc ; `skipEntry`.
- `jarvis.component.spec.ts` : « Quitter » en 2D ⇒ navigation immédiate ; toucher sujet / client (SF-236-04).
- Visuel : 36 captures (3 séquences × 6 instants × téléphone / PC).
- Isolation : sans objet.

## Dépendances
SF-236-04 (livrée, #1462).

## Préoccupations transversales

| Préoccupation | Cochée ? | Composants impactés |
|---|---|---|
| Navigation / routing | **Oui** | Lien « Quitter » : `routerLink` remplacé par `href="/atelier"` + `leave()` (`router.navigateByUrl('/atelier')`, après T-S en 3D, immédiat en 2D) — test ; toucher sujet ⇒ `touchSubject` inchangé ; aucun autre toucher ne navigue ; `?sujet=`, `?alerte=`, `?source=compagnon` non touchés. |
| Auth / tenant / limites | Non | — |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Caméra paramétrée en lacet / tangage / distance autour du point visé, chaque paramètre à ressort. | Les trajectoires sont des arcs par construction (jamais à travers le cœur), aucun roulis possible. | Interpoler la position cartésienne (traverse le cœur). | Oui. |
| A2 | Le directeur décide à chaque plan et toutes les 250 ms (pas à chaque image). | Le retour « 10 s sans citation » a besoin du temps ; une décision par image allouerait. | Décision à chaque image. | Oui. |
| A3 | Le lien « Quitter » devient `href` + `leave()`. | `RouterLink` navigue sans attendre ; la sortie T-S doit se jouer avant. | Garder `RouterLink` (pas de T-S). | Oui. |
| A4 | Le premier `pointerdown` sur l'écran saute l'entrée. | « Sautée au premier toucher » quel que soit l'endroit touché. | Seulement sur le canevas. | Oui. |
| A5 | T-K non capturé, fil d'Ariane non réécrit lettre à lettre. | T-K est la composition de transitions capturées ; le fil d'Ariane est celui de F-234 (gelé). | Banc à deux plans successifs. | Oui. |
