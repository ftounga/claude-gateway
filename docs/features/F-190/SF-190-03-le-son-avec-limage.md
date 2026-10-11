# Mini-spec — [F-190 / SF-190-03] Le son avec l'image

## Identifiant

`F-190 / SF-190-03`

## Feature parente

`F-190` — Regarder une vidéo (cadrage `CADRAGE-F-190-regarder-une-video.md`)

## Statut

`in-progress`

## Date de création

2026-10-11

## Branche Git

`feat/SF-190-03-le-son-avec-limage`

---

## Objectif

À côté de chaque image, l'agent lit ce qui se disait pendant qu'elle était à l'écran, à partir d'une transcription fournie ou produite sur le poste.

---

## Comportement attendu

### Cas nominal

1. `video_images` accepte deux paramètres facultatifs :
   - `transcript` : chemin d'une transcription sur le poste (`.vtt`, `.srt`, `.docx`, `.txt`) ;
   - `transcribe: true` : faire transcrire la vidéo sur le poste quand on n'a pas de transcription.
2. **Lecture d'une transcription fournie**, en phase « Lecture du son » après les planches :
   - `.vtt` : par le lecteur existant (`VttTranscript`) ;
   - `.srt` : virgules des horodatages converties, puis le même lecteur ;
   - `.docx` (export Teams) et `.txt` : texte extrait (`DocxText` pour le docx). Une ligne qui porte un horodatage (`H:MM:SS`, `M:SS`, ou `début --> fin`) ouvre une réplique ; le texte qui précède l'horodatage sur la même ligne est le locuteur, et les lignes suivantes sont le texte.
3. **Transcription sur le poste** (`transcribe: true`, sans `transcript`) : extraction de l'audio (`AudioTrack`), puis whisper.cpp (`LocalTranscription`, F-91), avec moteur et modèle rapatriés comme dans F-91. Le texte complet est écrit dans `transcript.txt` du dossier du travail, et l'audio intermédiaire est effacé.
4. **Alignement** : une image « règne » de son instant jusqu'à l'image suivante, la dernière jusqu'à la fin. Chaque case (ou image) reçoit le texte des répliques **commencées** pendant son règne, avec le locuteur s'il est connu, borné à 300 caractères (« … » au-delà).
5. **Rendu** : sous chaque case du statut, `dit : « … »` ; aucune ligne si rien n'a été dit. Le statut donne aussi le chemin de la transcription complète.
6. Persisté dans `job.json` (relu après redémarrage).
7. **La reprise tient compte du son** : changer `transcript` ou `transcribe` change l'identifiant du travail, qui est refait.

### Cas d'erreur

Aucun de ces cas ne fait échouer le travail : les images restent, le son manque et le manque est dit.

| Situation | Comportement attendu |
|-----------|---------------------|
| `transcript` introuvable | Refus avant le démarrage (`not_found`), avec le chemin |
| Format illisible ou sans aucun horodatage | Travail terminé sans alignement. Manque nommé : « transcription sans horodatage lisible : lis-la toi-même avec read_file » |
| Moteur ou modèle de transcription non rapatriable (hors Windows) | Travail terminé sans alignement. Manque nommé, avec la commande d'installation (remède de `LocalToolchain`) |
| Échec du moteur ou de l'extraction audio | Travail terminé sans alignement, avec un manque nommé et les dernières lignes du moteur |
| Répliques avant la première image | Comptées dans un manque nommé ; elles ne sont rattachées à aucune image |

---

## Critères d'acceptation

- [ ] Avec une transcription VTT, SRT, DOCX ou TXT horodatée, chaque case porte le texte dit pendant son règne.
- [ ] Avec `transcribe: true`, l'audio est extrait, whisper produit les répliques, `transcript.txt` est écrit et l'audio intermédiaire est effacé.
- [ ] Les échecs du son ne font jamais échouer le travail, et sont nommés.
- [ ] Changer la source du son refait le travail ; la même demande le reprend.
- [ ] Les textes alignés survivent à un redémarrage du runner.
- [ ] Les descriptions côté gateway exposent `transcript` et `transcribe`.

---

## Plan de test

### Runner (unitaires)
- `TimedTranscriptTest` :
  - VTT, SRT, format Teams docx/txt (« Nom   0:03 » puis texte), format « début --> fin » ;
  - texte sans horodatage donnant 0 réplique ;
  - heures longues (`1:02:03`).
- `VideoToolsTest` :
  - alignement avec une transcription VTT déposée (deux images, trois répliques, règnes respectés) ;
  - transcription illisible donnant un manque sans échec ;
  - transcription locale par un faux transcripteur (`transcript.txt` écrit) ;
  - transcripteur indisponible donnant un manque avec remède ;
  - changement de source qui refait le travail ;
  - relecture après redémarrage.

### Backend
- `AtelierChatServiceVideoToolsTest` : schéma de `video_images` avec `transcript` et `transcribe`.

### Isolation utilisateur
Sans objet : tout reste sur le poste.

---

## Composants impactés

- **Runner** :
  - `video/TimedTranscript` (nouveau, lecture des formats) ;
  - `video/VideoTranscriber` (nouveau : interface, et implémentation locale `AudioTrack` + `LocalTranscription`) ;
  - `VideoJob` (texte par case et par image, chemin de la transcription) ;
  - `VideoFramesWorker` (phase « Lecture du son » et alignement) ;
  - `VideoTools` (paramètres, identifiant, rendu) ;
  - `ToolStack` (montage) ;
  - `teams/DocxText` (rendu public pour être relu).
- **Backend** : `VideoToolCatalog` (schéma et descriptions).

## Préoccupations transversales

Aucune.

## Contraintes de validation

300 caractères au plus par case ; transcription fournie de 5 Mo au plus (borne de `VttTranscript`).

---

## Périmètre

### Hors scope (explicite)

- Identification des locuteurs par transcription locale (whisper ne la fait pas : le manque est déjà nommé par F-91).
- Notice de dépôt et doctrine (SF-190-04) ; carte à l'écran (SF-190-05).
