# Cadrage F-190 — Regarder une vidéo

**Origine** : demande du PO le 2026-10-11. Sur le terminal du poste EDENRED, l'enregistrement d'une démo Monday (`Demo Monday-…-Meeting Recording.mp4`, 101 Mo, une réunion Teams) a été déposé avec sa transcription. L'agent a répondu : « je n'ai vu ni la vidéo ni le site ». Le PO pensait que l'application savait le faire.

## Constat : la capacité existe, morcelée et fermée

| Maillon | Existe ? | Où | Pourquoi il n'a pas servi |
|---|---|---|---|
| Déposer la vidéo sur le poste | ✅ | dépôt du terminal (`atelier_deposited_files`) | — (la vidéo est bien arrivée dans `.atelier/entrees/`) |
| `ffmpeg` sur le poste | ✅ rapatrié au premier usage | runner, `Toolchain` / `LocalTool.ffmpeg()` (F-90 / F-91) | n'est demandé que par les outils du volet Teams ; l'agent a vérifié `command -v ffmpeg` : **absent** |
| Images aux changements de plan (20 à 60 par heure) | ✅ | runner, `SceneFrames` (F-90 / SF-90-01) | outils `teams_meeting_moments` et `teams_moments_status` proposés **seulement** si le terminal est un terminal Teams **et** la Vigie est active (`TeamsToolCatalog.toolsFor`) ; ce n'est pas le cas du terminal du poste |
| Le modèle **voit** une image | ✅ | `read_file` multimodal (F-121 / SF-121-15) | — |
| Les images de F-90 passent par le modèle | ❌ **par décision** | SF-90-03 : « Elles ne passent pas par le modèle » | F-90 illustre un compte rendu pour l'humain ; il ne fait pas **comprendre** la vidéo à l'agent |
| Transcription locale si on n'en a pas | ✅ | F-91 / SF-91-03 (sur la machine) | même fermeture Teams |

**Conclusion** : il manque **un branchement**, pas un moteur.
- L'API Claude ne prend pas de vidéo, mais elle voit des images.
- Le poste sait déjà extraire les bonnes images.
- L'agent sait déjà regarder une image.

Il reste à relier les trois, pour **n'importe quelle vidéo, sur n'importe quel poste**.

Le cas EDENRED est typique : une démo d'outil en partage d'écran. **C'est l'image qui porte le sens** : colonnes d'un board, statuts, formulaires. La transcription seule dit « là tu cliques ici ».

## Hors du sujet, mais à noter

Le PO voulait aussi que l'agent parcoure **le site Monday**. C'est un autre manque :
- il n'y a pas d'URL ;
- le site exige une connexion SSO ;
- il n'y a pas de navigateur piloté hors Teams.

C'est une feature distincte, « naviguer dans une application web du client avec sa session ». Elle n'est pas couverte ici. La vidéo répond déjà à une bonne partie du besoin : elle montre le site.

## Décisions à prendre (PO)

- **D1 — Les images d'une vidéo passent par le modèle** (recommandé). Cela **amende** la règle de SF-90-03, qui reste vraie pour le compte rendu Teams.
  - Le modèle voit les images que l'agent choisit d'ouvrir, pas toute la vidéo d'office.
  - Confidentialité : ce sont les mêmes règles que pour une capture d'écran déposée aujourd'hui (elle part chez le fournisseur). La vidéo elle-même ne quitte jamais le poste.
- **D2 — Disponible sur tout poste**, sans la Vigie ni le volet Teams (recommandé). L'extraction est générique ; seule l'origine Teams des outils actuels la ferme.
- **D3 — Densité : changements de plan d'abord** (le seuil de F-90), puis un **zoom à la demande** sur un intervalle (« de 12:30 à 14:00, une image toutes les 5 s »). Une démo d'outil a souvent peu de changements de plan franc (défilement dans un même board) : le zoom rattrape ce que le seuil manque.
  - La justesse passe avant le coût (règle absolue).
  - Ordre de grandeur du coût : environ 1 200 tokens par image 1280×720, soit environ 0,40 $ pour 60 images avec Opus 5.5.
- **D4 — Planche contact** (recommandé). L'agent voit d'abord une mosaïque de 16 vignettes horodatées par image, soit environ 4 lectures pour une heure, puis il ouvre en pleine taille ce qui compte. C'est la façon dont un humain survole une vidéo ; le coût est divisé par 5 sans rien cacher.

## Découpage proposé

| SF | Titre | Contenu | Estimation |
|---|---|---|---|
| SF-190-01 | **Extraire les images de n'importe quelle vidéo** | Outil `video_images` (démarrer, puis suivre), générique et proposé sur tout terminal de poste. Il réutilise côté runner `Toolchain` (rapatriement de `ffmpeg`) et `SceneFrames`, avec un mode intervalle (D3). Les images sont écrites **sur le poste** (`.atelier/video/<nom>/`), en JPEG réduits et horodatés. Travail long et asynchrone, avec reprise, comme F-90. | 1,5 j |
| SF-190-02 | **La planche contact** | Le runner compose des mosaïques de 16 vignettes avec leur horodatage incrusté. L'agent les ouvre avec `read_file` (vision existante), puis ouvre une image en pleine taille. | 1 j |
| SF-190-03 | **Le son avec l'image** | Si aucune transcription n'est fournie, transcription locale (SF-91-03 réutilisée). L'index rendu à l'agent aligne chaque image sur ce qui se disait à cet instant (réutilisation de SF-90-02). Avec une transcription fournie (le cas EDENRED), l'index s'aligne dessus. | 1 j |
| SF-190-04 | **L'agent sait qu'il peut regarder** | Quand une vidéo est déposée, la notice de dépôt et la description de l'outil disent quoi faire : planche, puis zoom, et dire ce qui n'a pas été vu. Il ne doit plus répondre « je n'ai pas ffmpeg ». Vérification de bout en bout sur la démo Monday d'EDENRED : l'agent doit retrouver « Alarm for Tech » dans le board, ce que le PO a donné comme preuve de compréhension. | 0,5 j |
| SF-190-05 | **Le suivi à l'écran** | Une carte de progression dans le terminal (extraction de 4 min, environ 40 images, puis analyse). Les planches sont visibles par l'utilisateur, pour qu'il voie ce que l'agent a vu. | 1 j |

## Préoccupations transversales

- Auth, tenant, limites, navigation : **aucune**. Les images restent sur le poste de l'utilisateur ; la lecture passe par le canal runner existant, déjà isolé par poste et par `user_id`.
- **Provider-First** : on ne réimplémente pas la compréhension de vidéo ; on prépare des images que Claude **voit** lui-même.
- **Provider Independence** : les images passent par `AgentContentBlock.Image`, qui est neutre.
- **Traitement lourd asynchrone** : l'extraction tourne sur le poste, en travail suivi, comme F-90.

## Hors périmètre

- Navigation dans le site du client (voir plus haut).
- Envoi de la vidéo au serveur ou au fournisseur : la vidéo ne quitte jamais le poste.
- Terminal hébergé, sans poste : pas de `ffmpeg` côté serveur dans un premier temps. La question est à poser si le besoin apparaît.
- Vidéo en direct ou en flux.
