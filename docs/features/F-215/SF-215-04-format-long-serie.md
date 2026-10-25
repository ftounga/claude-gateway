# Mini-spec — F-215 / SF-215-04 Format long : une série d'épisodes, fiche des sources, voix de série

## Identifiant
`F-215 / SF-215-04` — Feature parente : F-215 « Le cours vidéo libre (court et long) » — Statut : `done` — 2026-10-25 (PR #1280, #1281)
Branches : `feat/SF-215-04-format-long-serie` (backend), `feat/SF-215-04-fiche-sources-ecran` (frontend)

## Objectif
Au-delà de 3 minutes estimées ou de 4 notions majeures (D3), l'agent **propose une série** d'épisodes dont le plan est
validé avant toute fabrication ; chaque épisode est publié avec sa **fiche des sources** (affichée sous le lecteur) et
la **voix est fixée pour toute la série** (amendement PO, point 3).

## Comportement attendu
### Cas nominal
1. **Méthode « format long »** dans le guide `.claude/guides/cours-video.md` (savoir-faire chargé à la demande, SF-215-03 —
   **jamais** dans la consigne système) — nouvelle section « 7. Format long : une série » :
   - seuil D3 : > 3 min estimées **ou** > 4 notions majeures ⇒ proposer une série (l'utilisateur peut imposer l'un ou
     l'autre) ;
   - **plan d'abord** : 5 à 15 épisodes de 1 à 3 min, pour chacun objectif + sources ; durée totale et coût estimés
     (repère mesuré : ~2–4 $ de fabrication + ~0,06 $ de voix pour 2 à 3 min) ; validé par une **question structurée**
     (`demander`, F-164) **avant** toute fabrication ;
   - **charte commune** fixée une fois (un `theme.css` partagé à la racine de la série, D1) ; **voix commune** : auditions
     proposées à la **première vidéo d'une série** (D2), voix choisie fixée par `video_publier(voix)` au premier épisode,
     puis `voix_off(serie)` la réutilise ;
   - **un épisode = une composition** (`<série>/ep01-…/`), fabriquée, relue et rendue séparément ; délégation possible à
     un sous-agent `task` (worktree F-150, agent nommé F-182), **un épisode par sous-agent**, la synthèse seule remonte ;
     publié avec `serie` + `episode` ;
   - **avancement suivi comme un chantier** : `set_plan` avec une étape par épisode (plan F-208), mise à jour à chaque
     épisode publié ;
   - **fiche des sources** : chaque affirmation technique de la narration est sourcée (fait de carte, page, fichier du
     dépôt `chemin:ligne`, compte rendu) ; le non-vérifié est retiré ou listé « à confirmer » ; publiée par
     `video_publier(sources)` ;
   - corrections par épisode (F-214) sans refaire les autres ; changer la voix de toute la série = refaire chaque épisode
     (`remplace`) et republier avec la nouvelle `voix`.
2. **`video_publier`** gagne (texte d'outil fixe, préfixe stable) :
   - `sources` : `[{affirmation, source?, a_confirmer?}]` — rangée **par version** (`video_versions.sources_json`) ;
   - `voix` / `consignes_voix` (vidéo en série) : fixent (ou changent) la voix **de la série** (`video_series.voice`,
     `voice_instructions`).
   Le résultat d'outil dit le nombre de sources et d'« à confirmer », ou rappelle qu'aucune fiche n'a été jointe.
3. **`voix_off`** gagne `serie` (titre ou identifiant) : sans `voix` explicite, la voix (et les consignes, si aucune n'est
   donnée) de la série s'appliquent ; priorité `voix` explicite > voix de la série > préférence du compte > onyx.
   Série inconnue ou sans voix : la préférence s'applique et le résultat le dit.
4. **Lecture** : `GET /videos/{id}` rend `sources` (version servie) et `seriesVoice` ; le lecteur affiche la **fiche des
   sources** sous les chapitres (repliable, « à confirmer » surlignés) — PR frontend.

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| `sources` pas une liste, > 80 entrées | erreur d'outil explicite, rien n'est transféré | — |
| entrée sans `affirmation`, ou sans `source` ni `a_confirmer: true` | erreur d'outil explicite | — |
| affirmation / source > 300 caractères | erreur d'outil explicite | — |
| `voix` / `consignes_voix` pour une vidéo hors série | erreur d'outil explicite | — |
| `voix` mal formée (hors `[a-z0-9_-]{1,32}`), consignes > 1 000 | erreur d'outil explicite | — |
| `voix_off(serie)` série inconnue / d'un autre compte | préférence appliquée, résultat « série inconnue » | — |
| `voix_off(serie)` voix de série plus permise | préférence appliquée, résultat le dit | — |
| `GET /videos/{id}` d'un autre compte | introuvable | 404 |
| version sans fiche | `sources: []`, rien affiché | 200 |

## Critères d'acceptation
- [x] CA1 — Le guide porte la section « Format long » : seuil D3, plan 5–15 × 1–3 min validé par `demander` avant
  fabrication, charte et voix communes, auditions à la 1re vidéo de série, un épisode = une composition (sous-agent
  `task` possible), `set_plan` une étape par épisode, fiche des sources ; la consigne système n'en contient rien.
- [x] CA2 — `video_publier(sources)` range la fiche par version ; validation (bornes, source ou à confirmer).
- [x] CA3 — `video_publier(serie, voix, consignes_voix)` fixe/modifie la voix de la série ; refus hors série.
- [x] CA4 — `voix_off(serie)` applique la voix de la série sans voix explicite ; explicite prime ; série inconnue ⇒ préférence.
- [x] CA5 — `GET /videos/{id}` rend `sources` et `seriesVoice` ; isolation 404 croisée.
- [x] CA6 — Le lecteur affiche la fiche des sources (repliable, « à confirmer » surligné), rien sans fiche.

## Contraintes de validation
| Champ | Obligatoire | Format |
|---|---|---|
| `sources` | Non | liste, 80 entrées au plus |
| `sources[].affirmation` | Oui | 1 à 300 caractères |
| `sources[].source` | Oui sauf `a_confirmer: true` | 300 caractères au plus |
| `sources[].a_confirmer` | Non | booléen (défaut faux) |
| `voix` | Non (vidéo en série) | `[a-z0-9_-]{1,32}` |
| `consignes_voix` | Non | 1 000 caractères au plus |
| `voix_off.serie` | Non | titre (120) ou identifiant de série du sujet |

## Technique
- Migration **172** `172-video-sources-series-voice.xml` : `video_versions.sources_json varchar(16000)`,
  `video_series.voice varchar(32)`, `video_series.voice_instructions varchar(1000)` ; rollback : colonnes retirées.
- Backend : `VideoSource` (parse / JSON), `VideoService.PublishRequest` (+ `sources`, `voice`, `voiceInstructions`,
  constructeur historique conservé — record, pas un bean), `VideoService.findSeries`, `VideoToolCatalog`,
  `VideoToolExecutor`, `VideoResponse`, `SpeechToolCatalog` (`serie`), `SpeechToolExecutor`, guide `cours-video.md`.
- Frontend : `videos.models.ts` (`VideoSource`, `sources`, `seriesVoice`), `video-player.component.ts` (fiche des sources).

## Plan de test
- Unitaires : `VideoSourceTest` (parse nominal, à confirmer sans source, bornes, entrée invalide, JSON aller-retour) ;
  `SpeechToolExecutorTest` (voix de série appliquée, explicite prime, série inconnue).
- Intégration : `VideoSeriesVoiceSourcesIntegrationTest` (publication avec sources ⇒ `GET /videos/{id}` les rend par
  version ; voix de série fixée puis modifiée ; refus hors série ; **isolation** : `GET` d'un autre compte ⇒ 404,
  `findSeries` d'un autre compte ⇒ vide) ; `GovernancePackageSeederTest` (section format long du guide) ;
  `AtelierChatServiceVideoKitToolsTest` existant (rien du guide dans la consigne).
- Front : spec du lecteur (fiche affichée, à confirmer surligné, rien sans fiche).
- Suite complète backend + `npm test`.

## Préoccupations transversales
- **Contexte tenant** : `VideoService.findSeries` filtre `user_id` + sujet du tour ; `voix_off(serie)` ne lit que les
  séries du compte. Composants : `VideoService`, `SpeechToolExecutor`, `VideoToolExecutor`, `VideoController`.
- **Plans / limites** : aucun nouvel usage payant (la voix reste comptée par `voix_off`).
- Auth / navigation : non.
- **Préfixe stable** : définitions d'outils fixes ; la méthode longue est dans le guide lu à la demande.

## Hors périmètre
Orchestration serveur des épisodes (l'agent orchestre avec ses outils existants) ; garde dure exigeant une fiche ;
réordonner les épisodes à la main ; Plume vidéo (SF-215-05).

## Notes et décisions (arbitrages réversibles)
- **A1** — Fiche des sources **par version** (une correction peut changer les faits) ; colonne JSON comme les chapitres.
- **A2** — Voix de série portée par `video_publier` (la série naît à la première publication) et relue par `voix_off(serie)`
  plutôt qu'une série créée dès la voix off (série vide visible dans la galerie).
- **A3** — Fiche **facultative** côté outil (pas de garde dure, doctrine F-168) mais rappel dans le résultat d'outil.
- **A4** — Suivi en chantier par `set_plan` (outil existant, F-208) plutôt qu'un nouvel état serveur de série.
