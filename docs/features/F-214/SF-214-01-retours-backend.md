# Mini-spec — F-214 / SF-214-01 Backend : les retours à la seconde

## Identifiant
`F-214 / SF-214-01` — Feature parente : F-214 « Les retours à la seconde » — Statut : `in-review` — 2026-10-25
Branche : `feat/SF-214-01-retours-backend`

## Objectif
Ranger, pour son propriétaire, des retours attachés à un instant (ou une plage) d'une version de vidéo, les faire passer par les statuts `OUVERT → ENVOYE → TRAITE | REJETE`, les donner à lire à l'agent (`video_retours`) et les clore quand l'agent republie (`video_publier(remplace, retours_traites[])`).

## Comportement attendu
### Cas nominal
- **Table `video_comments`** (migration 171 — 168 réservé F-212, 169/170 pris) : `id`, `user_id` (FK users, cascade), `video_id` (FK videos, cascade), `version`, `t_start_ms`, `t_end_ms` (null = instant), `frame`, `text` (≤ 1000), `status` (`OUVERT|ENVOYE|TRAITE|REJETE`), `resolution` (motif ≤ 300, null), `resolved_version` (null), `sent_at`, `created_at`, `updated_at`. Index `(user_id, video_id)`.
- **Image** : `frame = round(t_start_ms × 30 / 1000)` — 30 i/s (aucune cadence n'est rangée par F-213 ; arbitrage tracé).
- **Routes** (toutes `requireId()` + vidéo possédée, sinon 404) :
  - `GET /videos/{id}/comments?version=n` — les retours (toutes versions si absent), par version puis instant.
  - `POST /videos/{id}/comments` `{version?, tStartMs, tEndMs?, text}` → 201 ; version absente = courante ; version inconnue = 404.
  - `PATCH /videos/{id}/comments/{cid}` `{text?, status?}` — texte modifiable seulement `OUVERT` ; `status` accepté : `OUVERT` (rouvrir, depuis tout statut). Toute autre transition par l'écran ⇒ 400.
  - `DELETE /videos/{id}/comments/{cid}` → 204.
  - `POST /videos/{id}/comments/send` `{version?}` → compose le message structuré (« Retours sur « titre » vN (id) : » puis une ligne `00:10 (image 304) — texte` ou `01:20–01:35 (images 2400–2850) — texte` par retour **OUVERT** de la version) et les passe `ENVOYE` (`sent_at`). Rien à envoyer ⇒ 400. Le message est rendu à l'écran, qui le place dans la saisie (SF-214-02) ; « Copier » passe par la même route.
- **Outil `video_retours`** (même garde que `video_publier` : droit de l'espace + cible poste ; lecture seule, sans accord) : `video` (id), `version?` (toutes sinon) ⇒ texte : titre, version courante, une ligne par retour `[id] v1 00:10 (image 304) [ENVOYE] — texte` (+ motif). Rappel fixe : regarder l'instant avec `video_images`, republier avec `remplace` + `retours_traites`. Ajouté à la liste blanche des modes réponse/plan.
- **`video_publier.retours_traites[]`** `{id, statut: traite|rejete, motif?}` : exige `remplace` (sinon refus **avant** transfert) ; format validé avant transfert ; après l'inscription de la nouvelle version, chaque retour de **cette** vidéo et du compte, `OUVERT` ou `ENVOYE`, passe `TRAITE`/`REJETE` avec `resolved_version` = nouvelle version et `resolution` = motif ; les identifiants inconnus, d'une autre vidéo/compte ou déjà clos sont **ignorés** et cités dans le résultat. Le résultat d'outil indique « Retours : n traités, m rejetés ».
- **Série** : un retour appartient à une vidéo = un épisode ; rien de partagé entre épisodes.
- **Effacement** : cascade sur la vidéo (suppression galerie, sujet) et sur le compte.

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| vidéo / retour d'un autre compte, ou retour d'une autre vidéo | introuvable | 404 |
| version inexistante | introuvable | 404 |
| texte vide / > 1000 car. | motif | 400 |
| `tStartMs` < 0 ou > 24 h ; `tEndMs` ≤ `tStartMs` | motif | 400 |
| plus de 300 retours sur la vidéo | motif | 400 |
| modifier le texte d'un retour non `OUVERT` | motif | 400 |
| statut demandé par l'écran autre que `OUVERT` | motif | 400 |
| envoyer sans retour `OUVERT` | motif | 400 |
| `retours_traites` sans `remplace` / mal formé | résultat d'outil en erreur, rien transféré | — |
| `video_retours` sur une vidéo inconnue / d'un autre compte | résultat d'outil « vidéo inconnue » | — |

## Critères d'acceptation
- [x] CRUD des retours d'une vidéo du compte ; image calculée à 30 i/s ; plage acceptée.
- [x] `send` compose le message attendu et passe les `OUVERT` de la version en `ENVOYE` ; les autres versions intactes.
- [x] Transitions : écran → `OUVERT` seulement ; agent → `TRAITE`/`REJETE` depuis `OUVERT`/`ENVOYE` ; déjà clos ignoré.
- [x] `video_retours` liste les retours (filtre version), refuse une vidéo d'un autre compte.
- [x] `video_publier(remplace, retours_traites)` met à jour les statuts + motif + version ; sans `remplace` refus avant tout transfert.
- [x] Isolation : 404 croisés sur toutes les routes ; retour d'un autre compte ignoré par `retours_traites`.

## Plan de test
- Unitaires : `VideoCommentServiceTest`-like via intégration (format du message, frame, transitions).
- Intégration : `VideoCommentApiIntegrationTest` (CRUD, send, transitions, 404 croisés), `VideoRetoursToolIntegrationTest` (outil `video_retours`, `retours_traites` sur `video_publier` avec runner simulé, refus sans `remplace`, retour étranger ignoré).
- `AtelierChatServiceVideoToolsTest` inchangé ; suite complète backend avant merge.

## Contraintes de validation
Texte 1–1000 car. ; motif ≤ 300 ; instants 0–86 400 000 ms ; fin > début ; 300 retours max par vidéo ; `retours_traites` ≤ 300 éléments ; statuts fermés. Tout est tranché.

## Préoccupations transversales
- **Contexte tenant** : composants impactés — `VideoCommentService` (toute lecture par `(id, user_id)` + vidéo possédée via `VideoService.require`), `VideoCommentController`, `VideoToolExecutor` (retours_traites filtrés `user_id` + `video_id`), outil `video_retours` (via `VideoService.require`). Tests 404 croisés.
- Auth / plans / navigation : aucun changement (routes sous `/videos/**` déjà authentifiées).

## Impacts
Migration `171-video-comments.xml` ; paquet `fr.claudegateway.videos` (entité, dépôt, service, contrôleur, statut) ; `VideoToolCatalog` (définition `video_retours`, champ `retours_traites`) ; `VideoToolExecutor` ; `AtelierChatService` (routage, liste blanche, garde du chemin machine, cible d'audit).

## Hors périmètre
Écran (SF-214-02) ; annotation graphique ; retours à plusieurs ; envoi automatique sans geste ; cadence d'image lue dans le conteneur.
