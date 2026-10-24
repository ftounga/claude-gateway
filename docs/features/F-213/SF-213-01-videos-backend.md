# Mini-spec — F-213 / SF-213-01 Backend : vidéos et sons publiés par l'agent

## Identifiant
`F-213 / SF-213-01` — Feature parente : F-213 « Les vidéos : lecteur, séries et galerie » — Statut : `in-review` — 2026-10-24
Branche : `feat/SF-213-01-videos-backend`

## Objectif
Une vidéo (`.mp4`/`.webm`) ou un son (`.mp3`/`.wav`/`.m4a`) produit **sur le poste** est rapatrié par tranches, rangé en S3 et versionné pour son propriétaire, puis annoncé dans le fil par un bloc `video` / `audio` du compte rendu de tour, et lu par une route média avec `Range`.

## Comportement attendu
### Cas nominal
- **Outil `video_publier`** (cible poste, même garde d'espace que `page_publish`, accord d'un clic comme une page) : `path`, `titre`, `description?`, `chapitres[]` (`{t, titre}`, `t` en secondes, croissants), `serie?` (identifiant ou titre ; créée si absente dans ce sujet), `episode?` (rang ; par défaut le suivant), `remplace?` (identifiant d'une vidéo du compte ⇒ **nouvelle version**), `couverture?` (image `png/jpg/jpeg/webp` du poste, ≤ 2 Mo), `duree_s?`.
- **Outil `audio_publier`** : `pistes[]` (1 à 6 : `{path, libelle}`), `titre?` ; chaque piste ≤ 20 Mo ; pas de galerie, seulement le fil.
- **Rapatriement** : `read_file_bytes` par tranches de 384 Kio vers un **envoi S3 multipart** (jamais le fichier entier en mémoire ni sur disque) ; taille lue à la 1re tranche ⇒ plafond et quota refusés **avant** tout transfert ; fichier qui change pendant la lecture ⇒ refus. Type contrôlé par l'extension **et** la signature (ftyp, EBML, ID3/MPEG, RIFF/WAVE).
- **Runner** : `read_file_bytes` accepte jusqu'à 300 Mio pour les seules extensions média (`mp4, webm, mp3, wav, m4a`), 10 Mio pour tout autre fichier (inchangé).
- **Stockage** : `videos/{userId}/{videoId}/v{n}/video.mp4|video.webm|audio.mp3|…` (+ `poster.png|jpg|webp`). 5 versions conservées par vidéo (la plus ancienne purgée au-delà).
- **Plafond de compte (D1)** : 5 Go vidéo + audio par compte (somme des versions conservées, purge comptée), **ADMIN exempté** ; dépassement ⇒ refus clair « X Go utilisés sur 5 Go ».
- **Tables** (migration 169, 168 réservé F-212) : `video_series`, `videos` (+ `kind` VIDEO/AUDIO), `video_versions`.
- **Lecture** : `GET /videos/{id}` (métadonnées, chapitres, version courante ou `?version=n`, adresses `mediaUrl`/`posterUrl`/`downloadUrl` par **ticket**) ; `GET /videos/{id}/versions` ; `GET /videos/{id}/versions/{n}/media` (JWT, `Range` → 206/416) ; `GET /video-media/{ticket}` et `/video-media/{ticket}/poster` (sans JWT : un `<video>` ne porte pas d'en-tête ; ticket HMAC à clé propre, lié à vidéo + version + compte, 12 h).
- **Fil** : blocs riches `video` et `audio` dans le compte rendu (`AtelierTurnReport.Block`, constructeurs rétro-compatibles), événements SSE `video` / `audio` au fil de l'eau ; ils survivent au bornage.
- **Effacement** : les vidéos d'un sujet (lignes + objets) partent avec le sujet ; les objets du compte partent avec le compte (lignes en cascade).

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| extension/signature hors liste | résultat d'outil en erreur, rien rangé | — |
| > 300 Mo (vidéo), > 20 Mo (piste), > 2 Mo (couverture) | refus avant transfert | — |
| quota 5 Go dépassé (non ADMIN) | refus « X Go utilisés sur 5 Go » | — |
| `remplace` inconnu ou d'un autre compte / autre type | refus « identifiant inconnu » | — |
| chapitres mal formés / non croissants / > 60 | refus explicite | — |
| projet hébergé (sandbox) | refus « depuis un poste » | — |
| runner trop ancien (`too_large`/`unsupported_tool`) | « mets à jour le runner » | — |
| fichier modifié pendant la lecture | refus, envoi S3 annulé | — |
| vidéo d'un autre compte (métadonnées, versions, média) | introuvable | 404 |
| ticket falsifié / expiré | introuvable | 404 |
| `Range` hors fichier | `bytes */taille` | 416 |

## Critères d'acceptation
- [x] `video_publier` range une vidéo (rapatriée par tranches) et rend `video_id` + version ; `remplace` crée v2, les chapitres sont relus.
- [x] `serie` crée la série puis la retrouve par titre ; `episode` par défaut = suivant.
- [x] Plafonds 300 Mo / 20 Mo / 2 Mo et quota 5 Go refusés avant transfert ; ADMIN exempté du quota.
- [x] `audio_publier` range 1 à 6 pistes et pose un bloc `audio`.
- [x] Route média : 200 complet, 206 avec `Content-Range`, 416 hors borne ; ticket sans JWT.
- [x] Isolation : 404 croisés (métadonnées, versions, média, ticket d'un autre compte impossible à forger).
- [x] Blocs `video`/`audio` dans la transcription et en SSE ; anciens relevés relus sans erreur.
- [x] Runner : 300 Mio pour les médias, 10 Mio sinon.

## Plan de test
- Unitaires : `VideoMediaTypesTest` (signatures, extensions), `VideoViewTicketServiceTest` (falsifié, expiré, format), `VideoChaptersTest`, runner `FileToolsTest` (plafond média).
- Intégration : `VideoToolPublishIntegrationTest` (runner simulé : nominal multi-tranches, remplace, série, plafonds, quota + ADMIN, signature, fichier modifié, audio), `VideoApiIntegrationTest` (métadonnées, Range 206/416, ticket, isolation 404 croisés), `AtelierTurnReportVideoTest` (sérialisation + rétro-compatibilité).
- Suite complète backend avant merge.

## Contraintes de validation
Titre ≤ 120 car., description ≤ 300, libellé de piste ≤ 80, chapitres ≤ 60 (titre ≤ 120, `t` ≥ 0 croissant), épisode 1–999, pistes 1–6, tailles ci-dessus. Tout est tranché (cadrage + D1).

## Préoccupations transversales
- **Contexte tenant** : toutes les lectures par `(id, user_id)` ; série cherchée dans `(user_id, workspace_id)` ; ticket lié au compte. Composants : `VideoService`, `VideoController`, `VideoMediaController`, `VideoToolExecutor`.
- **Plans / limites** : nouveau plafond de stockage vidéo (D1) ; seul appelant `VideoService.publish` ; ADMIN via `AdministratorEntitlement`.
- **Auth** : une route publique GET `/video-media/**` (ticket), ajoutée à `SecurityConfig` ; aucune autre route modifiée.
- Navigation : non (backend).

## Impacts
Nouveau paquet `fr.claudegateway.videos` ; `WorkspaceStorage` (méthodes par défaut : envoi multipart, lecture par plage) + `S3WorkspaceStorage` ; `AtelierChatService` (mutateur, outils, routage, bloc), `AtelierTurnReport.Block`, `AtelierProgressListener`, `TurnSignals`, `AtelierChatController` (SSE) ; `WorkspaceService.delete`, `AccountService` (purge) ; `SecurityConfig` ; migration `169-videos.xml` ; runner `FileTools`.

## Hors périmètre
Affichage (SF-213-02), galerie/séries/suppression par l'API (SF-213-03), fabrication (F-215), retours à la seconde (F-214), partage public (D2), transcodage, projet hébergé.
