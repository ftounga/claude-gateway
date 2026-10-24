# Mini-spec — F-213 / SF-213-03 Séries et galerie

## Identifiant
`F-213 / SF-213-03` — Feature parente : F-213 « Les vidéos : lecteur, séries et galerie » — Statut : `in-review` — 2026-10-24
Branche : `feat/SF-213-03-series-galerie`

## Objectif
Les vidéos d'un sujet — et de tout un poste — se retrouvent dans une **galerie** où les épisodes d'un tutoriel sont **rangés sous le titre de leur série**, dans l'ordre, avec reprise de lecture, épisode suivant enchaîné et suppression.

## Comportement attendu
### Cas nominal
- **API** :
  - `GET /videos?workspaceId=` (galerie du sujet) ou `?hostId=` (vue du poste) → `{ series: [{id, title, description, episodeCount, totalDurationS, episodes: [...]}], videos: [...] }` — vidéos seulement (jamais les sons), épisodes triés par rang, séries et vidéos seules de la plus récente à la plus ancienne ; chaque vidéo porte une adresse de couverture par ticket si elle en a une.
  - `DELETE /videos/{id}` → objets S3 puis ligne (versions en cascade) ; une série devenue vide part avec.
  - `DELETE /video-series/{id}` → la série **et ses épisodes** (objets compris).
  - `PUT /videos/{id}/duration` `{durationS}` → la durée lue par le navigateur corrige celle annoncée (écart > 1 s).
- **Front** :
  - Composant `app-video-gallery` (sujet ou poste) : cartes **série** (« titre · N épisodes · durée totale ») et cartes **vidéo seule** ; une série ouverte = liste ordonnée des épisodes + lecteur (`app-video-player`) ; **reprise** là où on s'est arrêté (position par appareil, `localStorage`, effacée en fin de lecture) ; **épisode suivant** enchaîné automatiquement à la fin ; **Supprimer** (vidéo, série) avec confirmation `MatDialog`, retour `MatSnackBar`.
  - Écran **Vidéos du sujet** : route `/atelier/:id/videos` (`?video=` ouvre une vidéo), ouverte dans un nouvel onglet depuis le bouton **Vidéos** de la barre du terminal (à côté de *Fichiers*) et depuis **Ouvrir dans la galerie** du bloc vidéo — le tour vit dans le flux du terminal, le quitter le tuerait.
  - **Vue du poste** : onglet **Vidéos** du poste (Forge), à côté de *Pages*.

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| ni `workspaceId` ni `hostId` | refus explicite | 400 |
| sujet / poste d'un autre compte | galerie vide (filtre `user_id`) | 200 |
| vidéo / série d'un autre compte (suppression, durée) | introuvable | 404 |
| durée ≤ 0 ou > 24 h | refus | 400 |
| `localStorage` indisponible | lecture depuis le début, sans erreur | — |
| galerie vide | « Aucune vidéo publiée ici pour l'instant. » | — |

## Critères d'acceptation
- [x] Galerie du sujet et du poste : séries (ordre des épisodes, nombre, durée totale) + vidéos seules ; sons exclus.
- [x] Suppression vidéo (série vidée supprimée) et série (épisodes compris), objets S3 effacés.
- [x] Isolation : 404 croisés sur suppression et durée ; galerie d'un autre compte vide.
- [x] Reprise de lecture par appareil ; épisode suivant enchaîné ; suppression confirmée par dialogue.
- [x] Route `/atelier/:id/videos`, bouton *Vidéos* du terminal, *Ouvrir dans la galerie*, onglet *Vidéos* du poste.

## Plan de test
- Backend : `VideoGalleryApiIntegrationTest` (galerie sujet/poste, ordre, sons exclus, suppression vidéo/série + objets, durée, isolation 404 / vide, 400).
- Front : `video-gallery.component.spec.ts` (cartes, série ouverte, reprise, épisode suivant, suppression confirmée), `video-resume.spec.ts` (stockage local tolérant), `forge-tabs.spec.ts` (onglet). Suite complète backend + `npm test`.

## Contraintes de validation
Durée 1 s – 24 h. Position sauvegardée au plus toutes les 5 s, ignorée sous 5 s ou à moins de 5 s de la fin.

## Préoccupations transversales
- **Contexte tenant** : `VideoService.gallery/delete*/setDuration` filtrent `user_id` ; composants : `VideoGalleryController`, `VideoService`.
- **Navigation / routing** : nouvelle route `/atelier/:id/videos` (même parent authentifié que `/atelier/:id/fichiers`, aucun guard modifié) ; nouvel onglet `videos` du poste (`forge-tabs.ts` : `effectiveTab` retombe sur *Projets* pour un onglet inconnu, rien d'autre ne change) ; chemins vérifiés : `/atelier/:id`, `/atelier/:id/fichiers`, `/pages/:id`, `/forge/...?onglet=`.

## Impacts
Backend : `VideoGalleryController` (nouveau), `VideoService` (galerie, suppressions, durée), `VideoRepository`. Front : `shared/videos/video-gallery.component.ts`, `video-resume.ts`, `videos/video-gallery-page.component.ts`, `videos.service.ts`, `videos.models.ts`, `app.routes.ts`, `postes` (onglet), `atelier-terminal` (bouton), `video-block.component.ts` (Ouvrir dans la galerie).

## Hors périmètre
Partage (D2), retours à la seconde (F-214), renommer / réordonner les épisodes à la main, galerie Vigie.
