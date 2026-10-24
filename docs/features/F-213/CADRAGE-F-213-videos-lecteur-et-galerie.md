# Cadrage F-213 — Les vidéos : lecteur, séries et galerie

**Demande PO du 2026-10-24** (conversation Claude Code, après l'étude de la vidéo « Comment faire du Motion Design PRO avec Claude Opus 5.5 » et l'essai `cours-eks-alb.mp4`, 2 min 34, jugé « époustouflant ») : « je veux pouvoir dire "fais-moi une vidéo de formation" […] je clique sur une vidéo, je vois […] un cours vidéo » ; puis : « quand je demande un vrai besoin consistant, que l'outil me propose de le faire sous forme de chapitres, d'une galerie vidéo […] une forme de tutoriel divisé en plusieurs vidéos […] groupées au même endroit, sous le même titre ».

**Ce que F-213 apporte** : une vidéo (ou un son) produit sur le poste **se regarde dans l'application** — dans le fil du terminal au moment où elle est livrée, puis dans une **galerie** où les vidéos d'un même tutoriel sont **rangées sous un titre de série**, dans l'ordre des épisodes. F-213 ne fabrique rien : la fabrication est F-215, les corrections à la seconde F-214.

## Existant réutilisable (vérifié sur `main` le 2026-10-24)
- **F-109 pages** : `page_publish` lit un fichier **sur le poste** (`path`) ou reçoit le contenu, le range en S3 (`PageStore`, préfixe `pages/{userId}/{pageId}/v{n}/`), versionne (`page_versions`), affiche un **bloc dans le fil** et liste les pages. C'est le patron à suivre (publication, versions, bloc, liste).
- **Lecture binaire par morceaux depuis le poste** : `FileTools` du runner renvoie un fichier par tranches (`offset`, base64, `remaining`) — permet de rapatrier un `.mp4` de plusieurs dizaines de Mo sans nouvel outil runner.
- **Lecteur média existant** : `meeting-detail-page.component` (Vigie, F-128) utilise déjà `<video>`/`<audio>` sur des médias servis par la passerelle (`TeamsMeetingMediaController`).
- **Stockage** : `S3_BUCKET` existant, types de média fermés déjà listés (`video/mp4`, `video/webm`, `audio/mpeg`…).

## Manques
(a) Le fil ne sait afficher ni vidéo ni audio produits par l'agent ; (b) aucun objet « vidéo » ni « série » côté serveur ; (c) aucune galerie ; (d) les pages plafonnent à 8 Mo — une vidéo de 20 min pèse ~100–150 Mo.

## Décisions proposées
1. **Outil agent `video_publier`** (cible poste, comme `page_publish` avec `path`) : `path` (`.mp4`/`.webm`), `titre`, `description`, `chapitres[]` (`{t, titre}`), facultatifs `serie` (titre ou id) + `episode` (rang), `remplace` (id d'une vidéo ⇒ **nouvelle version**, utilisée par F-214). Rapatriement par tranches (`FileTools`), **plafond 300 Mo** par fichier, types fermés, contrôle `ffprobe` côté passerelle impossible ⇒ durée/dimensions fournies par l'agent et vérifiées à la lecture (métadonnées du conteneur lues par le navigateur).
2. **Outil agent `audio_publier`** (même mécanique, `.mp3`/`.wav`/`.m4a`, ≤ 20 Mo, **sans galerie**) : sert aux **auditions de voix** (« voici 4 voix, choisissez ») et aux extraits sonores ; s'affiche seulement dans le fil.
3. **Modèle** : tables `video_series` (id, user_id, workspace_id, title, description, created_at, updated_at), `videos` (id, user_id, workspace_id, host_id, series_id null, episode null, title, description, current_version, duration_s, created_at, updated_at), `video_versions` (id, video_id, user_id, version, storage_key, size_bytes, mime, chapters_json, poster_key, created_at). S3 `videos/{userId}/{videoId}/v{n}/video.mp4` (+ `poster.jpg`, image de couverture fournie par l'agent ou extraite à 10 %). Migrations : numéros pris au dev (max + 1 ; 168 réservé F-212).
4. **Lecture** : `GET /api/videos/{id}/versions/{n}/media` **avec `Range`** (lecture progressive, avance rapide), isolation `user_id` (404 sinon) ; jamais de lien public dans V1.
5. **Dans le fil** : bloc riche `video` (constructeur dédié, rétro-compatible, comme le bloc `proof` de F-212) : couverture, lecture sur place, **chapitres cliquables** sous le lecteur, plein écran, vitesse 0,75–2×, « Ouvrir dans la galerie », « Télécharger ». Bloc `audio` : lecteurs compacts empilés, libellé par piste (« Voix 1 — grave, posée »).
6. **Galerie** : écran **Vidéos** du sujet (onglet à côté des pages) et vue de toutes les vidéos du poste. Une **série** = une carte « titre + N épisodes + durée totale » ; ouverte : liste ordonnée des épisodes, reprise là où on s'est arrêté (position par appareil, `localStorage`), épisode suivant enchaîné. Les vidéos hors série s'affichent seules.
7. **Effacement** : supprimer une vidéo / une série (et ses objets S3) ; effacées avec le sujet comme les pages.

## Préoccupations transversales
- **Contexte tenant** : toutes les routes filtrent `user_id` (`requireOwned`), tests 404 croisés (vidéo, version, média, série).
- **Navigation / routing** : nouvel onglet Vidéos du sujet + route de la vue poste ; vérifier les routes du terminal et des pages existantes (aucun guard modifié).
- **Plans / limites** : stockage des vidéos compté ? → **D1** ci-dessous.

## Questions à trancher par le PO
- **D1** — Plafond de stockage par compte (proposé : 5 Go, l'ADMIN exempté comme ailleurs) ou aucun en V1 ?
- **D2** — Partage par lien (comme `page_shares`) : hors V1 (proposé) ou dès V1 ?

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-213-01 | Backend : tables + S3, `video_publier` / `audio_publier` (rapatriement par tranches, plafonds, versions), route média `Range`, bloc `video`/`audio` du compte rendu ; tests (isolation, Range, plafonds) | 🟠 |
| SF-213-02 | Front : blocs vidéo et audio dans le fil (couverture, chapitres, vitesse, plein écran), relecture de l'historique | 🟢 |
| SF-213-03 | Séries et galerie : API séries/épisodes, écran Vidéos du sujet + vue poste, reprise de lecture, épisode suivant, suppression | 🟢 |

## Hors périmètre
Fabrication des vidéos (F-215) ; commentaires à la seconde (F-214) ; partage public ; transcodage côté serveur ; terminal hébergé (sandbox).
