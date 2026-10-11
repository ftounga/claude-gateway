# Mini-spec — [F-190 / SF-190-01] Extraire les images de n'importe quelle vidéo

## Identifiant

`F-190 / SF-190-01`

## Feature parente

`F-190` — Regarder une vidéo (cadrage `CADRAGE-F-190-regarder-une-video.md`, décisions D1→D4 validées par le PO le 2026-10-11)

## Statut

`in-progress`

## Date de création

2026-10-11

## Branche Git

`feat/SF-190-01-extraire-images-video`

---

## Objectif

Sur tout poste, l'agent peut extraire d'une vidéo présente sur la machine les images qui portent le sens (changements de plan, ou intervalle zoomé), puis les ouvrir avec `read_file` pour les **voir**.

---

## Comportement attendu

### Cas nominal

1. **Deux outils, sur tout terminal servi par un poste** dont le runner annonce la capacité `video`. Ils sont indépendants du volet Teams et de la Vigie (D2).
   - `video_images` **démarre** (ou reprend) l'extraction et rend la main tout de suite avec un `job_id` (travail long, asynchrone).
   - `video_images_status` dit où en est le travail. Quand il est terminé, il donne **la liste des images** : horodatage `HH:MM:SS` et chemin sur le poste.
2. **Mode `plans`** (défaut, D3) : extraction aux changements de plan, avec le seuil de F-90 (`SceneFrames`, 0,4), puis dédoublonnage et répartition sur toute la durée (`FrameSelection`, 60 images au plus). Les images écartées sont effacées du disque.
3. **Mode `intervalle`** (zoom, D3) : `from` et `to` (secondes ou `HH:MM:SS`), `every_seconds` (défaut 5). Une image toutes les N secondes sur l'intervalle, horodatée en temps absolu de la vidéo. 60 images au plus par demande : au-delà, l'outil refuse en proposant un pas plus large.
4. **Où vont les images** : sur le poste, sous `<projet>/.atelier/video/<nom-assaini>-<id8>/`, en JPEG de largeur 1280 au plus (`q:v 4`). Le mode `intervalle` écrit dans un sous-dossier `zoom-<from>-<to>-<pas>/`. La vidéo et les images **ne quittent jamais le poste** ; seul ce que l'agent ouvre par `read_file` part chez le fournisseur (D1).
5. **`ffmpeg`** : celui du PATH ou rapatrié au premier usage (`LocalToolchain`, cache partagé avec F-90). Le téléchargement est annoncé dans le flux de l'outil.
6. **Reprise** : redemander la même vidéo avec les mêmes paramètres rend le travail existant (en cours ou fini), sans le refaire. `restart: true` relance. L'état est persisté dans `job.json`, à côté des images : il survit à un redémarrage du runner.
7. **Rétrocompatibilité** : un runner antérieur n'annonce pas `video`, et la gateway ne déclare alors pas les outils (même règle que `bash_background`).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `path` absent | refus nommé : « donne le chemin de la vidéo » | `invalid_input` |
| Vidéo introuvable sur le poste | refus nommé avec le chemin résolu (message générique, sans mention de Teams) | `not_found` |
| `ffmpeg` ni présent ni rapatriable | travail en échec ; `failure` et `remedy` donnent la commande d'installation pour l'OS (`winget`, `brew`, `apt`) | phase `ECHOUE` |
| `ffmpeg` échoue (fichier pas une vidéo, codec) | échec avec les dernières lignes d'`ffmpeg` ; rien de partiel ne reste | phase `ECHOUE` |
| Intervalle invalide (`to <= from`, pas ≤ 0) ou plus de 60 images | refus avec la borne et une suggestion de pas | `invalid_input` |
| `job_id` inconnu dans `video_images_status` | refus nommé : « aucun travail de ce numéro, relance video_images » | `not_found` |
| Aucun changement de plan | travail terminé avec 0 image et le manque nommé (`NO_SCENE_CHANGE`), plus la suggestion du mode `intervalle` | `TERMINE` |
| Runner sans capacité `video` | outils non déclarés au modèle | — |

---

## Critères d'acceptation

- [ ] Sur un terminal servi par un poste dont le runner annonce `video`, le modèle reçoit `video_images` et `video_images_status`. Il ne les reçoit pas sans cette capacité, ni sur un terminal hébergé.
- [ ] Le runner annonce `video` dans sa trame `ready`, indépendamment de `--no-teams`.
- [ ] `video_images` rend la main en moins d'une seconde avec `job_id` et phase, et diffuse ses étapes dans le flux de l'outil.
- [ ] Mode `plans` : images JPEG horodatées par `pts_time` (jamais par rang), 60 au plus, écrites sous `.atelier/video/…`, les écartées effacées.
- [ ] Mode `intervalle` : horodatage absolu (`from` + `pts_time`), borne de 60 images respectée.
- [ ] `video_images_status` d'un travail terminé liste chaque image avec `HH:MM:SS` et son chemin, lisible par `read_file`.
- [ ] La même demande ne relance pas le travail ; `restart` le relance.
- [ ] Les erreurs du tableau sont rendues telles quelles, sans trace de pile.

---

## Plan de test

### Runner (unitaires, `FakeProcesses` en guise d'`ffmpeg`)
- Zoom (dans `VideoToolsTest`) : commande argument par argument (`-ss`, `-to`, `fps=1/N`, `showinfo`) ; horodatage absolu ; borne de 60 ; intervalle invalide.
- `VideoToolsTest` (worker compris) : mode plans de bout en bout (images écartées effacées, état `TERMINE`) ; reprise sans relance ; `restart` ; `ffmpeg` indisponible donne `ECHOUE` avec remède ; état relu après redémarrage (`job.json`).
- `VideoToolsTest` : `video_images` rend la main tout de suite ; `path` manquant ; vidéo introuvable ; `job_id` inconnu ; rendu de la liste au statut.
- `ToolRouterTest` : aiguillage `video_*`, et capacité `video` annoncée même sans Teams.

### Backend
- `AtelierChatServiceRunnerTargetTest` (ou un test dédié) : outils déclarés seulement si la capacité `video` est présente ; appel acheminé vers le runner avec son entrée.
- Relais `RunnerToolGateway.video` : couvert par le test d'acheminement (entrée recopiée).
- `RunnerCallDispatcher` : `video_*` exige la capacité `video`.

### Isolation utilisateur
Les outils ne passent que par le canal runner du poste du workspace, déjà lié à `user_id` et `host_id` : aucun accès de données nouveau côté gateway. Le test de déclaration couvre un terminal sans poste.

---

## Composants impactés

- **Runner** :
  - nouveau paquet `fr.claudegateway.runner.video` : `VideoTools`, `VideoFramesWorker`, `VideoJob` ;
  - `ToolRouter` (aiguillage et capacité) ;
  - `ProjectScopes` (montage par projet) ;
  - `ToolStack` (montage) ;
  - `teams/SceneFrames` : ajout de `extractEvery` (le zoom), qui partage le lancement d'`ffmpeg` et l'appariement horodaté des plans. La vidéo absente est vérifiée par `VideoTools` avec un message générique.
  - `release-notes.txt`.
- **Backend** :
  - `AtelierChatService` (déclaration et `callRunner`) ;
  - `RunnerToolGateway.video(...)` ;
  - `RunnerCallDispatcher.capabilityFor`.
- Aucune table, aucun endpoint REST.

## Préoccupations transversales

Aucune (auth, tenant, limites, navigation inchangés). Contrat runner ↔ gateway étendu de façon **additive** : nouvelle capacité et nouveaux outils, rétrocompatible dans les deux sens (F-81).

## Contraintes de validation

- `every_seconds` ∈ [1, 3600] ; 60 images au plus par demande ; vidéo lisible par `ffmpeg` ; `from`, `to` ≥ 0.

---

## Périmètre

### Hors scope (explicite)

- Planche contact (SF-190-02), son aligné (SF-190-03), notice de dépôt et doctrine (SF-190-04), carte à l'écran (SF-190-05).
- Envoi d'images à la gateway (pas d'upload : l'agent lit sur le poste).
- Terminal hébergé.
