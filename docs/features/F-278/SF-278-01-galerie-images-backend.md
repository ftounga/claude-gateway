# Mini-spec — [F-278 / SF-278-01] L'outil `galerie_images` (backend)

## Identifiant
`F-278 / SF-278-01`

## Feature parente
`F-278` — Galerie des captures d'écran du sujet, à faire défiler dans le fil (`CADRAGE-F-278-galerie-des-captures.md`)

## Statut
`in-progress`

## Date de création
2026-11-27

## Branche Git
`feat/SF-278-01-galerie-images-backend`

---

## Objectif
Donner à l'agent de l'Atelier un outil `galerie_images` qui lit sur le poste une sélection d'images, en range des
aperçus bornés et publie dans le fil un bloc riche `gallery` (nom, date, légende de chaque image), relu au rechargement.

## Comportement attendu

### Cas nominal
1. L'agent appelle `galerie_images { titre, images:[{chemin, legende?, date?}] }` (≤ 30) ou
   `{ titre, dossier, nombre? }` (glob `dossier/**` du runner, images seulement, plus récentes d'abord, ≤ 30).
2. Chaque chemin passe la politique de lecture (`read_file`) : refus ⇒ image écartée ; invite ⇒ une seule demande
   d'accord pour la galerie (sauf « tout autoriser pour ce message »).
3. Chaque image est lue par tranches (`read_file_bytes`, existant), tracée au journal du poste (`read_file`),
   décodée, réduite (≤ 1600 px, JPEG ≤ 400 Kio), rangée sous `galeries/{user}/{ws}/{id}.jpg`.
4. Le bloc `GalleryBlock(title, host, items[{id, name, path, date, caption, width, height}])` part au fil (événement
   SSE `gallery`) et dans la transcription du tour (`AtelierTurnReport.Block.gallery`).
5. Le modèle reçoit un texte borné : nombre d'images affichées, leurs noms, les écartées et leur raison. Jamais d'image.
6. `GET /api/workspaces/{id}/gallery/{imageId}` sert l'aperçu au propriétaire.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Terminal hébergé / sans poste | Outil : erreur « La galerie exige un poste connecté… » | — |
| Ni `images` ni `dossier` ; titre vide | Outil : erreur explicite | — |
| Plus de 30 images | Outil : erreur « 30 images au plus » | — |
| Chemin refusé par la politique | Image écartée (« lecture refusée »), les autres affichées | — |
| Accord refusé / délai | Outil : erreur, rien n'est lu | — |
| Format non décodable (WebP, SVG…), fichier > 10 Mo, illisible | Image écartée avec sa raison | — |
| Aucune image affichable | Outil : erreur listant les raisons ; aucun bloc | — |
| Poste hors ligne | Outil : erreur « poste injoignable » | — |
| Image d'un autre compte / autre terminal / id invalide | 404 | 404 |

## Critères d'acceptation
- [ ] `galerie_images` est déclaré sur un terminal de poste (hors Teams), jamais sur un terminal hébergé ou Teams ; définition fixe.
- [ ] Mode `images` : les images lisibles sont rangées en JPEG ≤ 1600 px et ≤ 400 Kio, dans l'ordre donné, avec la légende et la date données.
- [ ] Mode `dossier` : les images du dossier (extensions image), plus récentes d'abord, au plus `nombre` (≤ 30).
- [ ] Date : donnée par l'agent, sinon date du dépôt connu au même chemin, sinon date lue dans le nom, sinon `null`.
- [ ] Un chemin refusé, un format non pris en charge, un fichier trop lourd sont écartés avec leur raison sans faire échouer l'appel.
- [ ] Le bloc `gallery` est publié au fil de l'eau et persisté dans la transcription (relu au rechargement).
- [ ] La route d'image rend 404 pour un terminal ou une image d'un autre compte (isolation `user_id`).
- [ ] Les images sont effacées avec le terminal.
- [ ] La porte du parcours guidé classe l'outil en lecture ; le registre des scènes Jarvis le déclare (`S1`).

## Périmètre — hors scope
- Le rendu à l'écran (SF-278-02).
- Terminal hébergé, terminal Teams ; WebP/HEIC/SVG ; description automatique ; index des pièces Jarvis.
- Toute modification du runner (aucune).

## Valeurs initiales
Non applicable (aucune entité ; métadonnées dans le bloc).

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-------------|----------------------------|---------------|
| titre | Oui | 200 (coupé) | texte non vide | strip |
| images | Oui sauf `dossier` | 30 éléments | `chemin` non vide | strip |
| images[].legende | Non | 300 (coupée) | texte | strip |
| images[].date | Non | 40 (coupée) | texte libre | strip |
| dossier | Oui sauf `images` | — | chemin relatif au projet | strip, `/` final retiré |
| nombre | Non | — | 1..30, défaut 30 | borné |
| source | — | 10 Mo | PNG, JPEG, GIF, BMP | — |
| transfert total | — | 150 Mo / appel | — | images suivantes « non chargées » |

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/workspaces/{id}/gallery/{imageId}` | JWT | accès terminal (`requireTerminalAccess`) + propriétaire (`requireOwned`) |

### Tables impactées
Aucune (lecture de `atelier_deposited_files` pour la date, filtrée `user_id` + `workspace_id`).

### Migration Liquibase
- [x] Non applicable

### Composants
- `atelier/gallery/GalleryToolCatalog` — définition de l'outil.
- `atelier/gallery/GalleryBlock` — bloc riche.
- `atelier/gallery/GalleryImages` — réduction bornée (fonction pure).
- `atelier/gallery/GalleryDates` — date lue dans un nom de fichier (fonction pure).
- `atelier/gallery/GalleryService` — lecture runner, rangement, texte au modèle.
- `atelier/gallery/GalleryController` — route d'image.
- `AtelierChatService` (aiguillage, déclaration), `AtelierTurnReport.Block` (+ `gallery`), `AtelierProgressListener`/`TurnSignals`/`AtelierChatController` (`onGallery`, SSE `gallery`), `JourneyRiskClassifier`, `WorkspaceService.delete`, registre Jarvis.

## Plan de test

### Tests unitaires
- [ ] `GalleryImagesTest` — PNG 3000×2000 réduit à 1600 de large, JPEG ≤ 400 Kio ; transparence sur fond blanc ; octets illisibles ⇒ vide.
- [ ] `GalleryDatesTest` — `Capture d'écran 2026-11-27 093012.png`, `Screenshot_20261127_093012.png`, `IMG-20261127.jpg`, sans date ⇒ null.
- [ ] `GalleryServiceTest` — mode images (ordre, légende, date donnée / dépôt / nom), écartées (format, taille, lecture en échec), mode dossier (filtre extensions, `nombre`), texte au modèle borné, aucune image ⇒ erreur.
- [ ] `JourneyRiskClassifierTest` — `galerie_images` ⇒ LECTURE.

### Tests d'intégration
- [ ] `GalleryControllerTest` — 200 `image/jpeg` pour le propriétaire ; 404 autre compte ; 404 id invalide.
- [ ] `JarvisSceneRegistryGuardTest` (existant) — l'outil est au registre.

### Isolation utilisateur
- [x] Applicable — la route reconstruit la clé depuis l'utilisateur authentifié après `requireOwned` : un autre compte reçoit 404.

## Dépendances
- Aucune SF bloquante. Questions ouvertes : aucune impactée.

## Notes et décisions
Voir cadrage §4 (D1–D8).
