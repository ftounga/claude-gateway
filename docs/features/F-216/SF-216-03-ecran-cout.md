# Mini-spec — F-216 / SF-216-03 Le coût d'une vidéo à l'écran

## Identifiant
`F-216 / SF-216-03` — Feature parente : F-216 « Le coût d'une vidéo, annoncé avant et mesuré après » — Statut : `in-progress` — 2026-10-26
Branche : `feat/SF-216-03-ecran-cout`

## Objectif
L'utilisateur voit, dans la galerie et sous le lecteur, ce qu'une vidéo ou une série a coûté à côté de ce qui avait été
estimé, et ce qu'il a dépensé en vidéos ce mois-ci.

## Comportement attendu
### Cas nominal
1. **Carte** (vidéo seule ou série) : la ligne de méta se termine par « 5,63 $ » quand le coût est mesuré.
2. **Sous le lecteur** : « Coût : 5,63 $ (estimé 4,80 à 7,20 $) » ; estimation absente ⇒ « Coût : 5,63 $ » ; coût
   absent ⇒ « Coût non mesuré (publiée sans devis) ».
3. **Série ouverte** : sous son titre, « Coût de la série : 12,40 $ (estimé 10,00 à 15,00 $) » ; chaque épisode de la
   liste porte son coût dans sa méta.
4. **En-tête de la galerie** (liste) : « Ce mois-ci : 12,40 $ en vidéos » (`GET /videos/costs`), affiché seulement
   s'il y a eu au moins un devis ce mois-ci ; rafraîchi avec la galerie.
5. Montants au format français (virgule, 2 décimales, « $ » après un espace insécable) ; une fourchette dont les bornes
   sont égales s'écrit en un seul montant.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `GET /videos/costs` en échec | l'en-tête du mois est masqué, la galerie s'affiche normalement |
| Coût `null` (vidéo publiée sans devis, ou avant F-216) | pas de montant sur la carte ; « Coût non mesuré » sous le lecteur |
| Série dont aucun épisode n'est mesuré | pas de ligne de coût de série |

## Critères d'acceptation
- [ ] CA1 — Carte : le coût mesuré apparaît dans la méta ; rien sinon.
- [ ] CA2 — Sous le lecteur : « Coût : X $ (estimé A à B $) », ou « Coût non mesuré ».
- [ ] CA3 — Série ouverte : total + estimation de la série ; coût par épisode dans la liste.
- [ ] CA4 — En-tête : total du mois, masqué si aucun devis ce mois-ci ou en cas d'erreur.
- [ ] CA5 — Conforme à `DESIGN_SYSTEM.md` (jetons `--cg-*`, espacements multiples de 4 px, aucune couleur en dur).

## Technique
- `videos.models.ts` : `costUsd`, `estimateLowUsd`, `estimateHighUsd` (facultatifs) sur `VideoGalleryItem`,
  `VideoGallerySeries`, `VideoSummary` ; `VideoMonthCost`.
- `videos.service.ts` : `monthCost()` → `GET /api/videos/costs`.
- `shared/videos/video-cost.ts` : `formatUsd`, `costLine` (fonctions pures).
- `video-gallery.component.ts` : méta des cartes et des épisodes, ligne sous le lecteur, ligne de série, en-tête du mois.
- Aucune route nouvelle, aucun backend.

## Plan de test
- Unitaires : `video-cost.spec.ts` (format, fourchette égale, non mesuré).
- Composant : `video-gallery.component.spec.ts` (coût sur la carte, sous le lecteur, série, en-tête du mois affiché /
  masqué si 0 devis / masqué en erreur).
- Suite Karma complète + `npm run build`.

## Préoccupations transversales
- Contexte tenant : aucun changement (le JWT seul ; la gateway filtre par compte).
- Auth / navigation / plans : non.

## Hors périmètre
Paramètres › Vidéos (le total est dans l'en-tête de la galerie) ; détail par poste de dépense (voix / agent) du coût
réel ; graphiques.
