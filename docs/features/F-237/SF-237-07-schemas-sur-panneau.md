# Mini-spec — F-237 / SF-237-07 — Les schémas sur panneau

## Identifiant
`F-237 / SF-237-07` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-07-schemas-panneau`
Cadrage : §10 (type ARCHITECTURE, points d'extension), §8.2. Contrat : SF-237-03 (`/jarvis/pieces/diag:<id>/image`).
`diagram-renderer` **non touché**.

## Objectif
> Un schéma d'architecture se regarde comme une pièce : SVG net à tout zoom, déplacement, et cibles nommées
> (« zoome sur le VPC de prod ») lues dans le schéma lui-même.

## Comportement attendu
- `jarvis-piece-svg.ts` (pur hors DOMParser) : `svgTargets(svg)` lit le cadre (`viewBox`) et les groupes portant un
  libellé et un cadre (`<g id="zone-…">` / `node-…` du rendu maison, grappes Graphviz en polygone avec le `translate`
  des ancêtres) ; `findTarget` (libellé exact, sinon unique approché, article ignoré) ; `viewOf` (centre la cible,
  ~80 % du cadre, zoom ×1-×8).
- Visionneuse `schema-svg` : le SVG reste affiché en `<img>` (**jamais inséré** : aucun script possible) ; son texte
  est analysé à part. La cible de l'état (`nav.target`) est cadrée ; introuvable ⇒ « Je ne trouve pas « X » sur ce
  schéma. » ; les zones (≤ 8) sont proposées au toucher, plus « Tout le schéma ».
- Points d'extension (§10) : `registerViewer('ARCHITECTURE', …)` remplace la visionneuse sans toucher au reste ; le
  réducteur accepte toute cible nommée (testé).

### Cas d'erreur
- PNG seul (pas de SVG) ⇒ image zoomable sans cibles ; SVG illisible ⇒ aucune cible, l'image reste.

## Critères d'acceptation
- [ ] Cibles lues sur un rendu maison et sur un rendu Graphviz.
- [ ] « la base » ⇒ zoom ×4 centré ; cible inconnue ⇒ phrase dite à l'écran.
- [ ] Remplacement de la visionneuse ARCHITECTURE sans effet sur DIAGRAM.

## Préoccupations transversales
Aucune cochée (lecture par la route isolée de SF-237-03).

## Plan de test
`jarvis-piece-svg.spec.ts` : cibles (maison, Graphviz, illisible), recherche, cadrage, panneau (zones, cadrage,
introuvable), point d'extension.

## Hors périmètre
Rendu 3D de l'architecture (F-238) ; ordre vocal « zoome sur … » (passe par l'outil `afficher`, SF-237-08).
