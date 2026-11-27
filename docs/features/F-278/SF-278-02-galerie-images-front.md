# Mini-spec — [F-278 / SF-278-02] La galerie à l'écran (frontend)

## Identifiant
`F-278 / SF-278-02`

## Feature parente
`F-278` — Galerie des captures d'écran du sujet, à faire défiler dans le fil (`CADRAGE-F-278-galerie-des-captures.md`)

## Statut
`in-progress`

## Date de création
2026-11-27

## Branche Git
`feat/SF-278-02-galerie-images-front`

---

## Objectif
Afficher dans le fil le bloc `gallery` produit par `galerie_images` (SF-278-01) sous forme d'un carrousel qu'on fait
défiler d'un coup d'œil, au fil de l'eau comme au rechargement.

## Comportement attendu

### Cas nominal
- Événement SSE `gallery` ⇒ carte « Galerie » rangée dans le tour (écran principal et volet de chantier), comme les
  preuves ; au rechargement, le bloc `gallery` de la transcription est relu tel quel (`ATELIER_RICH_BLOCK_FIELDS`).
- Composant `app-gallery-block` : titre « Galerie · <titre> · N images » ; grande image (aperçu de
  `GET /api/workspaces/{id}/gallery/{imageId}`) ; sous l'image : nom, date (« 27/11/2026 09:30 » pour une date ISO,
  texte de l'agent tel quel, « date inconnue »), compteur « k / N », légende ; précédent / suivant (boutons désactivés
  aux bouts) ; flèches ←/→, Début/Fin au clavier quand le bloc a le focus ; bande de vignettes cliquables (chargement
  paresseux, vignette courante surlignée et ramenée au centre) ; Agrandir / Réduire (plein écran, Échap) ; Copier
  l'image (PNG au presse-papiers, repli téléchargement) ; Télécharger ; « N images écartées » repliable avec raisons.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aperçu introuvable (terminal supprimé, autre compte) | « Image indisponible. » à la place, la galerie reste utilisable | 404 |
| Événement `gallery` sans image | Aucun bloc (pas de bloc creux) | — |
| Presse-papiers image non pris en charge / refusé | Message + téléchargement | — |
| Une seule image | Pas de bande de vignettes | — |

## Critères d'acceptation
- [ ] Le bloc reçu au fil de l'eau s'affiche dans le tour (écran principal et volet) et survit au rechargement.
- [ ] Nom, date lisible, légende et compteur sous l'image courante.
- [ ] Défilement par boutons, flèches du clavier et vignettes ; bornes respectées.
- [ ] Agrandir / Échap ; Copier l'image ; Télécharger par la route isolée.
- [ ] Image indisponible signalée sans casser la galerie ; écartées listées.
- [ ] Charte : tokens `--cg-*` uniquement (bordure orange comme les preuves), espacements multiples de 4 px, Material (`mat-button`, `mat-icon-button`, `MatSnackBar`), téléphone (images et vignettes réduites, bande défilante).

## Périmètre — hors scope
- Tout changement backend (SF-278-01). Écran Jarvis (scène déjà déclarée `S1`). Galerie hors du fil.

## Valeurs initiales
Image courante = la première ; galerie non agrandie.

## Contraintes de validation
Non applicable (affichage seul).

## Technique

### Endpoint(s)
Consommé : `GET /api/workspaces/{id}/gallery/{imageId}` (SF-278-01).

### Tables impactées
Aucune. Migration : non applicable.

### Composants Angular
- `GalleryBlockComponent` (`atelier/terminal/gallery-block.component.ts`) — le carrousel.
- `atelier.models.ts` (types, champ `gallery`, `ATELIER_RICH_BLOCK_FIELDS`), `atelier.service.ts` (événement `gallery`),
  `atelier.component.ts`, `terminal-lane-pane.component.ts`, `atelier-terminal.component.{ts,html}` (rendu).

## Plan de test

### Tests unitaires
- [ ] `gallery-block.component.spec.ts` — rendu (URL isolée, nom, date, légende, compteur, écartées), défilement
  (boutons, clavier, vignettes, bornes), agrandir/Échap, une seule image, image indisponible, téléchargement, format de
  date, bloc au fil de l'eau et relecture (`toThreadItem`).

### Tests d'intégration
- [ ] `npm run build` vert ; specs de la zone `atelier` vertes.

### Isolation utilisateur
- [x] Applicable côté serveur (SF-278-01, route 404 pour autrui) ; le composant ne charge que la route du terminal.

## Dépendances
- SF-278-01 — done (mergée).

## Notes et décisions
- Une seule taille d'aperçu (cadrage D4) : la bande réutilise l'aperçu en `object-fit: cover`, `loading="lazy"`.
- Le JPEG est converti en PNG au moment de copier (le presse-papiers n'accepte que le PNG).
