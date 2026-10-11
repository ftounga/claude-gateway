# Mini-spec — [F-190 / SF-190-04] L'agent sait qu'il peut regarder

## Identifiant

`F-190 / SF-190-04`

## Feature parente

`F-190` — Regarder une vidéo (cadrage `CADRAGE-F-190-regarder-une-video.md`)

## Statut

`in-progress`

## Date de création

2026-10-11

## Branche Git

`feat/SF-190-04-lagent-sait-regarder`

---

## Objectif

Quand une vidéo est déposée, l'agent sait qu'il peut la regarder et comment le faire. Il ne répond plus « je n'ai pas ffmpeg ». La chaîne est prouvée avec un vrai `ffmpeg`.

---

## Comportement attendu

### Cas nominal

1. **Notice de dépôt** (`DepositConsumptionService.note`) : pour chaque fichier vidéo déposé (`.mp4`, `.mov`, `.m4v`, `.webm`, `.mkv`, `.avi`), la ligne du fichier ajoute : « vidéo : regarde-la avec video_images puis video_images_status ; read_file ne la lit pas ».
   - Si une transcription (`.vtt`, `.srt`, `.docx`, `.txt`) est déposée dans le même lot, la notice ajoute : « passe-la en transcript à video_images pour avoir ce qui se disait à côté de chaque image ».
   - Les autres fichiers gardent la notice actuelle, mot pour mot.
2. **Pas de doctrine dans la consigne système** : la règle « préfixe stable » interdit d'y mettre ce qui dépend du dépôt. La notice (dans le message) et les descriptions d'outils (stables) suffisent.
3. **Preuve avec un vrai `ffmpeg`** : test d'intégration runner, exécuté seulement si `ffmpeg` est présent sur la machine de test (il est ignoré sinon, et le test le dit).
   - Il fabrique une vidéo de 12 s en trois plans de couleurs franches, plus une piste audio.
   - Il lance `video_images` (mode `plans`), puis le zoom (`intervalle`).
   - Il vérifie les horodatages réels (journal `showinfo` d'un vrai `ffmpeg`), la planche produite et les images lisibles.
4. **Vérification sur le poste réel** (après déploiement et mise à jour du runner) : sur la démo Monday d'EDENRED, l'agent doit retrouver « Alarm for Tech » dans le board. Cette vérification est faite par le PO sur son poste ; le résultat est consigné dans l'historique de PRODUCT_SPEC.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Dépôt sans vidéo | Notice inchangée |
| Vidéo déposée, runner sans capacité `video` | La notice parle de `video_images` ; l'outil absent, l'agent le dit. La mise à jour du runner (F-111) le rend disponible |
| `ffmpeg` absent de la machine de test | Test d'intégration ignoré, avec la raison |

---

## Critères d'acceptation

- [ ] Une vidéo déposée produit la mention `video_images` dans la notice ; une transcription déposée avec elle produit la mention `transcript`.
- [ ] Un dépôt sans vidéo produit exactement la notice d'avant.
- [ ] Avec un vrai `ffmpeg`, trois plans donnent trois images (**l'écran d'ouverture compris**) datées à une seconde près des changements réels, et une planche lisible.
- [ ] Le zoom produit une image toutes les N secondes, datée en temps absolu.

---

## Plan de test

- `DepositConsumptionServiceTest` : vidéo seule, vidéo avec transcription, sans vidéo (notice identique).
- `VideoRealFfmpegTest` (runner) : vidéo synthétique avec `ffmpeg`, trois plans, puis zoom ; ignoré si `ffmpeg` est absent.
- Isolation : sans objet (la notice est construite pour le seul dépôt de l'utilisateur, filtre `user_id` existant).

---

## Composants impactés

- Backend : `atelier/deposit/DepositConsumptionService` (notice).
- Runner :
  - test d'intégration ;
  - `SceneFrames.extractWithOpening`, utilisé par la vidéo : le premier écran est toujours retenu, ce que le seuil seul ne faisait jamais (défaut trouvé par le test réel) ;
  - le volet Teams est inchangé.

## Préoccupations transversales

Aucune.

---

## Périmètre

### Hors scope (explicite)

- Carte de progression à l'écran (SF-190-05).
- Doctrine dans la consigne système (écartée, règle du préfixe stable).
