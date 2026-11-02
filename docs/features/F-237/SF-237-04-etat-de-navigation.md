# Mini-spec — F-237 / SF-237-04 — L'état de navigation, la voix locale, le bloc « Écran »

## Identifiant
`F-237 / SF-237-04` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-04-etat-navigation`
Cadrage : §5 (état, voix locale, `## Écran`), §10 point 2-3 (registre, cible nommée). Contrat importé de SF-237-01
(`Piece`, `GET /api/jarvis/pieces`).

## Objectif
> Une seule source de vérité pour « où en est l'écran » parmi les pièces : un réducteur pur que le toucher, la voix
> locale et (plus tard) l'outil `afficher` pilotent de la même façon, et que l'IA connaît par le bloc `## Écran`.

## Comportement attendu
- `jarvis-piece-nav.ts` (pur) : `PieceNav { level, scope, items ≤ 24 (+more), selected, page, time, playing, chapter,
  zoom 1..8, pan, target, fullscreen, origin, history ≤ 8 }` ; `reduceNav(state, action) → { state, ok, reason? }`.
  Actions : `openGallery`, `select(index | id | titre approché unique)`, `next`, `prev`, `back`, `up`, `close`, `play`,
  `pause`, `seek(t | chapitre n | titre)`, `page(n | delta)`, `zoom(+ | - | reset | n | cible)`, `pan`, `fullscreen`.
- `back` est **structurel** : INSTANT/PAGE → PIECE → GALERIE → CHANTIER (si la portée a un chantier) → UNIVERS.
- `jarvis-piece-voice.ts` (pur) : liste **fermée**, phrase **entière** normalisée (cadrage §5.3) ⇒ action ; sinon
  `null` (part au cerveau rapide). Inactif sans galerie ouverte.
- `jarvis-piece-screen.ts` : bloc `## Écran` (≤ 1 500 car., titres ≤ 60, dates « mardi 10:42 » heure de Paris) ;
  « Écran : univers » sans galerie ; en présentation, aucune pièce.
- `jarvis-piece-viewers.ts` : registre `PIECE_VIEWERS: Record<PieceKind, PieceViewer>` (feuilletable, temporel,
  zoomable) ; `viewerFor` rend la fiche pour un type inconnu ; `registerViewer` remplace une visionneuse (point
  d'extension architecture 3D).

### Cas d'erreur
- Index hors bornes, page au-delà, temps au-delà de la durée, zoom hors 1..8 ⇒ `OUT_OF_RANGE`, état inchangé.
- `page` sur une vidéo, `play` sur une image, `pan` non zoomé ⇒ `NOT_APPLICABLE`.
- Aucune galerie ⇒ `NO_GALLERY` ; titre ou chapitre introuvable / ambigu ⇒ `NOT_FOUND`.

## Critères d'acceptation
- [ ] Banc de séquences §9 : l'état attendu à chaque pas.
- [ ] Toucher et voix produisent **le même état** (test).
- [ ] Phrases ambiguës ou riches ⇒ `null` (IA) ; ordres exacts reconnus, accents/casse ignorés.
- [ ] `## Écran` ≤ 1 500 caractères ; garde : tout `PieceKind` a une visionneuse.

## Préoccupations transversales
Aucune cochée : code front pur, aucune route, aucun appel réseau.

## Plan de test
`jarvis-piece-nav.spec.ts` : réducteur (toutes actions, refus, pureté), grammaire (exactes, ambiguës, accents),
sérialisation (plafond, présentation), registre (garde, remplacement).

## Hors périmètre
Rendu (galerie 3D, visionneuses : SF-237-05/06/07) ; branchement au micro et au cerveau rapide (SF-237-08).
