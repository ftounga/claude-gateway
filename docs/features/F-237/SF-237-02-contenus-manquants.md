# Mini-spec — F-237 / SF-237-02 — Les contenus manquants

## Identifiant
`F-237 / SF-237-02` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-02-contenus-pieces`
Cadrage : §4.3, §12. Dépend de SF-237-01 (mergée).

## Objectif
> Tout ce que l'index désigne se lit par la passerelle, pour son seul propriétaire : vignettes réduites, corps texte
> d'un courriel, pièces d'un besoin, PDF d'une page.

## Comportement attendu
- `GET /api/jarvis/pieces/{pieceId}/thumb` : PNG ≤ 320 px de large (redimensionnement seul, aucune analyse) pour
  `proof:`, `img:`, `slides:` (diapo 1), `video:` (couverture). Cache mémoire 200 vignettes. Image illisible ⇒
  l'original. Dépôts : **aucune** vignette (l'original s'ouvre, cadrage §4.3).
- `GET /api/jarvis/pieces/mail:{id}/text` : `body_text` en `text/plain` — **jamais** `body_html`.
- `GET /api/jarvis/pieces/req:{id}/attachments/{n}` : la pièce copiée n° n d'un besoin, pour son **propriétaire**
  (jusqu'ici réservée à l'admin) ; types en ligne en liste blanche, le reste en téléchargement, `nosniff`.
- Index : `thumb` = `/jarvis/pieces/{id}/thumb` (preuve, image, présentation avec diapos, vidéo avec couverture) ;
  page : `meta.pdf = /pages/{id}/pdf` (PDF existant F-184) ; courriel : `open` = route texte ; besoin :
  `meta.attachments[{name, open}]`.

### Cas d'erreur
- Pièce d'un autre compte, inexistante, identifiant mal formé, type sans vignette ⇒ **404** (indiscernables).
- Présentation sans diapos rendues, vidéo sans couverture ⇒ 404 ; pièce de besoin non copiée ⇒ 404.
- Sans jeton ⇒ 401.

## Critères d'acceptation
- [ ] Vignette 320 px pour le propriétaire ; 404 pour un autre compte.
- [ ] Courriel : corps texte exact, HTML jamais servi.
- [ ] Identifiant mal formé ⇒ 404 (jamais un chemin).

## Technique
`PieceId` (lecture d'identifiant, liste fermée de préfixes), `PieceThumbnails` (ImageIO), `JarvisPieceContentService`
(lecture par (`id`, `user_id`) puis stockage existant), `JarvisPieceContentController`.
`ProofCaptureRepository.findByIdAndUserId`. Aucune migration.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Contexte tenant | **Oui** | `JarvisPieceContentService` (preuves, images, présentations, vidéos, courriels, besoins) : chaque lecture par (`id`, `user_id`) ; `AppRequestService.attachment` déjà filtré `user_id` ; tests A/B |
| Auth / Plans / Navigation | Non | — |

## Plan de test
Unitaires `PieceIdAndThumbnailsTest` (identifiants valides / invalides / chemin, réduction 1280→320, illisible).
Intégration `JarvisPieceContentApiIntegrationTest` (vignette réduite + index, texte du courriel, A/B 404, 401).

## Hors périmètre
Vignette des dépôts sur poste (jamais copiés, D9) ; PJ des courriels (effacées après envoi) ; schémas (SF-237-03).
