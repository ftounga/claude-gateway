# Mini-spec — [F-190 / SF-190-02] La planche contact

## Identifiant

`F-190 / SF-190-02`

## Feature parente

`F-190` — Regarder une vidéo (cadrage `CADRAGE-F-190-regarder-une-video.md`, décision D4)

## Statut

`in-progress`

## Date de création

2026-10-11

## Branche Git

`feat/SF-190-02-planche-contact`

---

## Objectif

L'agent survole une vidéo comme un humain : d'abord des planches de 16 vignettes horodatées, puis il n'ouvre en pleine taille que les images qui comptent.

---

## Comportement attendu

### Cas nominal

1. Quand une extraction se termine avec au moins une image (dans les deux modes), le runner compose des **planches** dans le dossier du travail : `planche-01.jpg`, `planche-02.jpg`, et ainsi de suite.
   - Chaque planche est une grille de **4 × 4**, soit 16 cases au plus, de 384 × 216 pixels chacune, ce qui donne une planche de 1536 × 864 (sous la limite de redimensionnement du fournisseur, environ 1 800 tokens).
   - Les images sont prises **dans l'ordre du temps**, de gauche à droite puis de haut en bas.
   - Chaque vignette garde ses proportions sur fond sombre.
2. **Repère** dans chaque case, en haut à gauche : son numéro (1 à 16) et son instant `HH:MM:SS`, sur un bandeau lisible.
   - Si la machine ne sait pas dessiner de texte (JVM sans polices), la planche est quand même produite sans repère, et un manque est nommé. L'index textuel suffit alors : case n ↔ instant ↔ chemin.
3. `video_images_status` d'un travail terminé rend **d'abord les planches** : chemin de chaque planche, puis pour chaque case son numéro, son instant et le chemin de l'image en pleine taille. Il donne aussi la consigne : « ouvre d'abord les planches avec read_file, puis seulement les images en pleine taille dont tu as besoin ».
4. Les planches sont persistées dans `job.json`. Un travail relu après redémarrage les rend.
5. Une planche ratée (image illisible, erreur d'écriture) n'empêche pas les autres : son manque est nommé, et les images en pleine taille restent listées.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune image retenue | Aucune planche ; rendu inchangé (suggestion du mode intervalle) |
| Polices indisponibles (JVM headless) | Planche sans repère, plus le manque « repères non dessinés » |
| Une image illisible | Case laissée vide et barrée d'un fond neutre ; manque nommé ; les autres cases restent |
| Écriture de la planche impossible | Manque nommé ; la liste des images en pleine taille reste rendue |

---

## Critères d'acceptation

- [ ] 1 à 16 images donnent 1 planche ; 17 à 32 en donnent 2 ; 60 en donnent 4.
- [ ] Chaque planche fait 1536 × 864 et place les images dans l'ordre du temps.
- [ ] Le statut d'un travail terminé liste les planches avant les images, avec pour chaque case son numéro, son instant et son chemin en pleine taille.
- [ ] La description de `video_images_status`, côté gateway, dit de commencer par les planches.
- [ ] Les planches survivent à un redémarrage du runner (relues depuis `job.json`).
- [ ] Un échec de dessin du texte ou d'une image ne fait pas échouer le travail.

---

## Plan de test

### Runner (unitaires)
- `ContactSheetsTest` :
  - découpage 1, 16, 17 et 60 images en planches ;
  - dimensions 1536 × 864 ;
  - pixel d'une case non noir (image posée) ;
  - image illisible donnant une case vide et un manque ;
  - repère dessiné quand les polices existent.
- `VideoToolsTest` :
  - le statut terminé rend les planches, puis les cases, puis les images ;
  - relecture après redémarrage avec planches.

### Backend
- `AtelierChatServiceVideoToolsTest` : la description de `video_images_status` mentionne les planches.

### Isolation utilisateur
Sans objet : aucun accès de données côté gateway ; tout reste sur le poste, dans le dossier du travail.

---

## Composants impactés

- **Runner** :
  - `video/ContactSheets` (nouveau, Java2D/ImageIO, sans dépendance) ;
  - `video/VideoJob` (planches dans l'état et `job.json`) ;
  - `video/VideoFramesWorker` (étape « Planches ») ;
  - `video/VideoTools.render`.
- **Backend** : `atelier/video/VideoToolCatalog` (descriptions).
- Aucune table, aucun endpoint.

## Préoccupations transversales

Aucune.

## Contraintes de validation

16 cases au plus par planche ; une case fait 384 × 216 ; JPEG de qualité par défaut d'ImageIO.

---

## Périmètre

### Hors scope (explicite)

- Son aligné (SF-190-03) ; notice de dépôt (SF-190-04) ; carte à l'écran (SF-190-05).
- Choix d'une grille variable.
