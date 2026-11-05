# Mini-spec — F-248 / SF-248-04 Le jeu de pancartes

## Identifiant
`F-248 / SF-248-04`

## Feature parente
`F-248` — Les pancartes (cadrage §4.4, §4.9 ; D1, D5, D7, D11, D12, D13)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j (réestimé 1 j sur le code) · **Branche :** `feat/SF-248-04-le-jeu` · **Dépend de :** SF-248-03

## Objectif
Les pancartes sont un **mode `cards` de la galerie F-237** (type de pièce `FAIT`, visionneuse `pancarte`) : un jeu
disposé selon son nombre (1 au centre, 2-6 en éventail, ≥ 7 en mur paginé), groupé, à plat au téléphone et en repli,
effacé proprement, réglable par appareil.

## Comportement attendu

### Cas nominal
1. `PieceKind` + `FAIT` ; `PIECE_VIEWERS.FAIT = pancarte` (garde `kindsWithoutViewer` vide) ; `GalleryMode` + `cards`.
2. `JarvisPieceStore.showCards(responseId, cards, {flat})` : fiches → pièces `FAIT` dans le **même réducteur**
   `PieceNav` (voix locale, toucher, `afficher` inchangés) ; même `responseId` = mise à jour sans recompter, nouveau =
   remplacement ; compteurs `c_set`, `c_n1|c_n2_6|c_n7p`, `c_old`, `c_2d` (jamais `p_gal`).
3. Disposition pure (`jarvis-card-deck.ts`) : `deckLayout` (center / fan / wall), `deckArc` (2 → 40°, 6 → 110°),
   `deckSlots` (héros 1,6), `wallPage` (6 cases, dernière « et N autres », `c_more`), `orderCards` (client s'il y en a
   plusieurs, sinon alerte → attend → reste), `groupLabel` (en-tête écrit).
4. Galerie : le jeu au-dessus de la scène (cartes DOM en CSS 3D), entrée « sortie » 320 ms décalée de 60 ms (6 au plus),
   pancarte choisie en grand dans le panneau ; téléphone / 2D / sobre / mouvement réduit / qualité Basse ⇒ **pile
   plate** sans perspective ni flou (D7, D13), balayage vers le bas ⇒ fermer.
5. Coexistence : une galerie **demandée** ouverte n'est jamais remplacée — les pancartes attendent derrière une
   pastille « N pancartes » (raison `BUSY`, silencieuse) ; une galerie d'initiative est remplacée.
6. Effacement (D11) : « ferme » (voix, toucher, `afficher fermer`) efface le jeu (`c_close2s` si < 2 s) ;
   `lingerCards()` le laisse 60 s puis l'efface ; `closeCards()` au changement de sujet.
7. Réglage « Pancartes : oui / non » par appareil (volets Réglages 2D et 3D, `localStorage` protégé, défaut oui).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Présentation | refus `PRESENTATION`, rien n'est rendu | — |
| Jeu vide | refus `NOTHING` | — |
| Galerie demandée ouverte | refus `BUSY`, pastille | — |
| Stockage indisponible | réglage = oui, rien ne casse | — |

## Contraintes de validation
≤ 24 pancartes (borne du réducteur) ; mur de 6 cases ; ≤ 6 animées ; arc 40°-110°.

## Critères d'acceptation
- [x] 1 / 2-6 / ≥ 7 ⇒ centre / éventail / mur « et N autres ».
- [x] Groupes : client puis statut, en-tête écrit.
- [x] Une galerie demandée n'est jamais remplacée (pastille).
- [x] « ferme » et 60 s effacent ; présentation ⇒ rien.
- [x] À plat en 2D / téléphone / qualité Basse ; réglage par appareil.

## Plan de test
- Unitaires : `jarvis-card-deck.spec.ts` (disposition, mur, groupes, plat, D1, D12 — 6 ; store et galerie — 5).
- Non-régression : suites `pieces/*.spec.ts` (galerie, réducteur, voix), suite complète.
- Isolation : sans objet (écran ; les fiches arrivent filtrées par la passerelle).

## Impact
- Frontend : `cards/jarvis-card-deck.ts` ; `pieces/jarvis-piece.models.ts`, `jarvis-piece-viewers.ts`, `jarvis-piece-store.ts`, `jarvis-piece-gallery.component.ts`, `jarvis-piece-show.ts`, `jarvis-piece-measure.ts`, `jarvis-piece-screen.ts`, `jarvis-piece-story.ts` (une entrée `FAIT` chacun) ; `jarvis.component.ts/html` (réglage, entrées de la galerie).
- Préoccupations transversales : aucune (pas de route Angular nouvelle : un mode de la galerie de `/jarvis`).

## Arbitrages (réversibles)
- La dégradation D13 suit la **qualité Basse** du moteur 3D (le régulateur F-236 l'abaisse déjà quand les i/s tombent) ; pas de second chronomètre d'i/s.
- « à l'instant » n'est permis qu'aux fiches lues en **direct** (`source = direct`) : c'est la seule source dont la passerelle sait qu'elle est vivante.

## Hors périmètre
La citation (SF-05) et le branchement de l'événement `cards` ; le plein écran détaillé et la comparaison (SF-06).
