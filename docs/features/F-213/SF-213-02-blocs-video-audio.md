# Mini-spec — F-213 / SF-213-02 Front : blocs vidéo et audio dans le fil

## Identifiant
`F-213 / SF-213-02` — Feature parente : F-213 « Les vidéos : lecteur, séries et galerie » — Statut : `in-review` — 2026-10-24
Branche : `feat/SF-213-02-blocs-video-audio`

## Objectif
Une vidéo ou des sons publiés par l'agent (SF-213-01) **se regardent dans le fil du terminal**, au fil de l'eau comme à la relecture de l'historique.

## Comportement attendu
### Cas nominal
- Événements SSE `video` / `audio` → bloc rangé comme une carte (survit au recalcul des blocs vivants) ; à la relecture, les champs `video` / `audio` du compte rendu donnent les mêmes blocs.
- **Bloc vidéo** (`app-video-block`) : « Épisode N — titre » (série) ou « Vidéo — titre », méta « Série X · Version n · durée · N chapitres · privée », description ; relit `GET /videos/{id}?version=n` (la version **de l'appel**) puis **lecteur sur place** (`app-video-player`, réutilisé par la galerie en SF-213-03).
- **Lecteur** : `<video controls preload="metadata">` sur l'adresse par ticket (lecture progressive `Range`) ; **couverture** si fournie, sinon image à 10 % de la durée (fragment `#t=`) ; **chapitres cliquables** (saut + lecture, chapitre courant surligné) ; **vitesse** 0,75 / 1 / 1,25 / 1,5 / 2× ; **plein écran** (le cadre du lecteur, chapitres compris) ; **Télécharger**.
- **Bloc audio** (`app-audio-block`) : titre du lot, une ligne par piste (libellé + lecteur compact `preload="none"`).
- Lecture seule (tuile « Voir travailler ») : titre et méta seuls, aucun appel, aucun lecteur (charte §13).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| vidéo supprimée / 404 | « Vidéo indisponible (supprimée depuis, ou illisible). » — le bloc reste |
| piste audio 404 | « Son indisponible » sur la ligne, les autres pistes jouent |
| événement SSE sans identifiant / sans piste | ignoré (pas de bloc creux) |

## Critères d'acceptation
- [x] SSE `video`/`audio` relayés, blocs creux ignorés.
- [x] Bloc vidéo : titre, méta, lecteur de la version de l'appel, couverture ou aperçu à 10 %.
- [x] Chapitre cliqué ⇒ `currentTime` = début du chapitre + lecture ; vitesse appliquée (`playbackRate`).
- [x] Plein écran et Télécharger présents ; aucune couleur ni police hors charte (jetons `--cg-*`).
- [x] Bloc audio : un lecteur par piste, piste en erreur signalée.
- [x] Lecture seule : ni lecteur ni appel.

## Plan de test
`video-block.component.spec.ts` (fonctions pures, bloc, chapitres, vitesse, erreur, lecture seule, audio) ; `atelier.service.spec.ts` (+1 : SSE video/audio). `npm run build` + `npm test`. Isolation `user_id` : portée par l'API (SF-213-01, 404 croisés) ; le front n'envoie aucun identifiant de compte.

## Préoccupations transversales
Aucune (Auth, tenant, plans, navigation : non — aucune route ni garde modifiée).

## Impacts
Nouveaux : `core/models/videos.models.ts`, `core/services/videos.service.ts`, `shared/videos/video-player.component.ts`, `atelier/terminal/video-block(.ts|.component.ts)`, `audio-block.component.ts`. Modifiés : `atelier.models.ts` (types + champs `video`/`audio`), `atelier.service.ts` (SSE), `atelier.component.ts` (2 abonnements), `atelier-terminal.component.(ts|html)` (2 rendus).

## Hors périmètre
Galerie, séries, reprise de lecture, épisode suivant, « Ouvrir dans la galerie », suppression (SF-213-03) ; retours à la seconde (F-214).
