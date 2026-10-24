# Mini-spec — F-214 / SF-214-02 Écran : commenter la vidéo à la seconde

## Identifiant
`F-214 / SF-214-02` — Feature parente : F-214 « Les retours à la seconde » — Statut : `in-review` — 2026-10-25
Branche : `feat/SF-214-02-retours-ecran` — dépend de SF-214-01 (mergée, PR #1276).

## Objectif
Dans le lecteur du fil et de la galerie, poser un retour sur un instant ou une plage (pause + `00:10 · image 304`), le retrouver sur la barre et dans une liste, l'envoyer à l'agent en **remplissant la saisie du terminal sans envoyer**, ou le copier, et revoir le même instant d'une version à l'autre.

## Comportement attendu
### Cas nominal
- **Composant partagé `app-video-review`** (remplace `app-video-player` dans le bloc du fil et dans la galerie ; le lecteur reste tel quel dedans) :
  - **« Commenter cet instant »** (bouton, ou touche `C` quand le lecteur a le focus hors champ de texte) : la vidéo se met en **pause**, un champ (`mat-form-field` outline) affiche `00:10 · image 304` ; **Entrée** enregistre, **Échap** annule. **« Fin de plage ici »** fixe la fin à l'instant courant (on peut avancer la vidéo pendant la saisie) ⇒ `01:20 → 01:35 · images 2400–2850`.
  - **Barre des retours** sous la vidéo : un marqueur par retour de la version affichée (plage = segment), couleur selon le statut ; clic ⇒ saut à l'instant (pause).
  - **Liste** des retours de la version affichée, par instant : instant cliquable (saut), texte, statut (`Ouvert`, `Envoyé`, `Traité en v2`, `Rejeté en v2 — motif`), actions **Rouvrir** (envoyé/clos) et **Supprimer** (confirmation `MatDialog`).
  - **« Envoyer à l'agent »** (si au moins un retour ouvert sur la version) : `POST …/comments/send` ⇒ le message rendu est **placé dans la saisie** du terminal du sujet, **rien n'est envoyé** (patron Cap F-192 / Plume F-193) ; un brouillon déjà tapé est conservé sous le message. **« Copier »** : même route, message dans le presse-papiers, snackbar.
  - **Sélecteur de version** (`v1 · v2 · v3`, si plus d'une version conservée) : recharge la version choisie **au même instant** ; marqueurs et liste suivent la version.
  - **Série** : les retours sont ceux de l'épisode affiché ; « Envoyer » ne concerne que lui.
- **Fil** : `app-video-block` émet le message ; `atelier-terminal` le place dans la saisie (`draftChange`) et donne le focus.
- **Galerie** (onglet séparé) : le message part vers l'onglet du terminal du sujet par `BroadcastChannel` (`cg-terminal-draft`, identifié par le sujet) ; l'écran du terminal ouvert sur ce sujet l'ajoute à sa saisie et accuse réception. Sans accusé sous 800 ms (aucun terminal ouvert), la galerie ouvre le terminal du sujet dans l'onglet courant avec le message dans l'état de navigation (patron F-103), jamais dans l'URL. Galerie d'un poste : le sujet est celui de la vidéo (`workspaceId` ajouté à `GET /videos/{id}`).

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| texte vide | Entrée sans effet, bouton désactivé | — |
| enregistrement refusé (contrainte, 300 retours) | snackbar avec le motif rendu | 400 |
| vidéo supprimée entre-temps | snackbar « vidéo introuvable » | 404 |
| envoi sans retour ouvert | bouton désactivé ; erreur serveur ⇒ snackbar | 400 |
| presse-papiers refusé par le navigateur | snackbar « copie impossible », message laissé visible | — |
| terminal en lecture seule (tuile) | aucun lecteur, aucun retour | — |

## Critères d'acceptation
- [x] « Commenter cet instant » met en pause et affiche `mm:ss · image n` ; Entrée crée le retour (version affichée) ; plage possible.
- [x] Marqueurs positionnés à `t / durée`, clic = saut ; liste ordonnée, statuts par version lisibles.
- [x] « Envoyer à l'agent » remplit la saisie du terminal sans l'envoyer (fil) ; depuis la galerie, via l'onglet du terminal ou, à défaut, en ouvrant le terminal avec le brouillon.
- [x] « Copier » met le message dans le presse-papiers.
- [x] Sélecteur de version : même instant revu dans l'autre version.
- [x] Rouvrir / supprimer (avec confirmation) ; aucun `window.confirm/alert/prompt`.
- [x] Design system : jetons `--cg-*`, polices de la charte, Material outline, espacements multiples de 4 px.

## Plan de test
- Unitaires : `video-review.ts` (étiquettes d'instant, position des marqueurs, libellés de statut, ajout au brouillon), `terminal-draft-bridge` (accusé / repli).
- Composant : `video-review.component.spec.ts` (pause + étiquette, création avec version, envoi ⇒ sortie émise, copier, sélecteur de version au même instant, rouvrir, marqueur ⇒ saut), `video-block.component.spec.ts` (sortie relayée), terminal (le message rejoint la saisie sans `send`).
- Backend : `VideoApiIntegrationTest` vérifie `workspaceId`. Suite complète backend + `npm run build` + `npm test` (comparés à `main`).
- Isolation : portée par le backend (SF-214-01, 404 croisés) ; l'écran n'envoie aucun identifiant de compte.

## Contraintes de validation
Texte 1–1000 car. (compteur), instants issus du lecteur (0 – durée), fin > début. Tranché en SF-214-01.

## Préoccupations transversales
- **Navigation / routing** : aucune route nouvelle ; la galerie peut ouvrir `/atelier/:id` avec un état de navigation (patron F-103 existant) — composants vérifiés : `AtelierComponent.takeRadarDraft`, `VideoGalleryPageComponent`, onglet Vidéos du poste.
- **Contexte tenant** : inchangé (routes SF-214-01 filtrées `user_id`) ; `GET /videos/{id}` rend en plus le `workspaceId` de la vidéo du compte (déjà filtrée).
- Auth / plans : non.

## Impacts
Front : `shared/videos/video-review.component.ts` (+ `video-review.ts`), `shared/videos/terminal-draft-bridge.ts`, `core/services/videos.service.ts`, `core/models/videos.models.ts`, `video-player.component.ts` (accès lecture : pause, instant, durée), `video-block.component.ts`, `atelier-terminal` (html + ts), `atelier.component.ts` (écoute du pont), `video-gallery.component.ts`. Back : `VideoResponse.workspaceId`.

## Hors périmètre
Édition du texte d'un retour (l'API le permet ; l'écran supprime et repose — arbitrage), dessin sur l'image, retours à plusieurs, envoi automatique.
