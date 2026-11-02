# CADRAGE F-237 — Jarvis montre les pièces (galerie holographique)

**Statut :** cadrage du 2026-11-02. Famille « Jarvis » : F-230 → F-236, **F-237**.
**Nature :** un projet en **4 itérations** livrables, **14 SF** de 2 jours au plus (≈ 24 j). Toutes les décisions
techniques ont un défaut tranché (§17). Le PO n'est sollicité que pour **trois décisions produit ou de goût** (§18).
**Points d'extension réservés** pour le rendu 3D des architectures (feature distincte, cadrée par une autre session,
**non traitée ici**) : §10.

---

## 1. Demande PO (2026-11-02)

| # | Consigne (en substance) | Où |
|---|---|---|
| P1 | « Si je demande de me montrer les entrées d'un chantier, il m'explique **en affichant** les entrées : photos, vidéos, audio… de manière dynamique, avec des effets de **zoom avant / zoom arrière**. » | §8, §9 |
| P2 | « Je veux être sûr qu'on a mis **tout ce qu'il fallait**. Pour la description, sers-toi de **ce qui existe déjà**. » | §3 (inventaire), §11 |
| P3 | « Y a-t-il **d'autres fonctionnalités de ce style** à intégrer ? Par exemple les **preuves** qu'un chantier est terminé : on génère des captures, tu peux aussi me les afficher ? **Autant de choses.** » | §3, §14 |
| P4 | **L'IA décide SEULE de montrer** une pièce quand ça appuie sa réponse, sans « montre-moi ». « Où en est-on ? » ⇒ le point **et** le point de départ (le besoin initial, ce que le PO avait fourni), **affichés de lui-même**. « Tu es sûr que c'est terminé ? » ⇒ la preuve existe : il la retrouve et l'affiche ; elle n'existe pas : il **propose** de la générer, et sur un « oui » déclenche la génération de preuves **existante**, puis l'affiche. Par un **outil** que l'IA sait utiliser d'elle-même. | §6, §7 |
| P5 | **Naviguer** parmi les pièces **au toucher ET à la voix**, avec un **état de navigation que l'IA connaît** : « la galerie de vidéos de tel sujet » ⇒ affichée ; puis « la deuxième », « celle sur le déploiement », « le cours 3 », précédente / suivante, retour à la galerie, ouvrir le PDF, page suivante, zoomer, pause, remonter au chantier. | §5, §8 |
| P6 | Les **schémas d'architecture** de F-142 (diagram-as-code, icônes cloud officielles, SVG) sont un **type de pièce à part entière** : panneau holographique, zoom et déplacement, navigables, commentables, affichés **d'initiative** (« comment c'est déployé chez ce client ? »). Le **rendu 3D** de l'architecture est **hors de ce cadrage** (autre feature, autre session) ; ici on prévoit seulement les **points d'extension**. | §10 |

**Ce qui ne change pas :** le moteur 3D F-236 (directeur pur, `StagePlan`, registre `SCENES_3D`, catalogue des
transitions, DESIGN_SYSTEM §23), le bus de scènes F-234, la voix F-233, la route rapide / Opus F-235. F-237 **ajoute**
un type de contenu (la pièce), un état (la navigation), des lectures, deux outils au cerveau rapide et une forme de
plus à la ligne `🖥 montrer:`.

### 1.1 Règles qui bornent ce projet (non négociables)

| Règle | Conséquence |
|---|---|
| **Contenu réel** | On affiche le **binaire réel** (image, vidéo, audio, PDF, page, SVG), jamais une mise en scène symbolique. Une pièce indisponible (poste déconnecté) s'affiche comme telle, jamais remplacée par une autre. |
| **Descriptions existantes** | Le commentaire oral réutilise ce qui est déjà en base (titre, description, chapitres, texte du message qui accompagne la pièce, étape prouvée, commandes, reformulation du besoin…). Aucune description automatique d'image. §11. |
| **Gateway-First / Provider-First** | La passerelle **indexe et relaie** ; elle ne produit aucune analyse d'image. Décrire ce qu'on voit sur une image sans légende = demande explicite, **déléguée à Opus** (vision du fournisseur), comptée. |
| **Isolation `user_id`** | Toute lecture nouvelle filtre `user_id` **et** le sujet possédé (`requireOwned`) ; test d'isolation A/B à chaque SF backend. Aucun lien S3 direct envoyé au navigateur (§12). |
| **Préfixe de cache stable** | La consigne système et les définitions d'outils sont **fixes** (texte versionné). L'état de navigation, l'inventaire des pièces, les descriptions vont **dans le message** ou **dans les résultats d'outil**, jamais dans la consigne. |
| **Justesse avant coût** | Montrer d'initiative ne doit jamais dégrader la réponse : la pièce **appuie** une phrase vraie ; en cas de doute sur l'attribution d'une pièce à un chantier, Jarvis le dit (« d'après la date, c'est probablement… ») ou délègue. |
| **Coûts en €** | Toute estimation est donnée en euros. |
| **Une action qui engage passe par Opus + « oui »** (F-235 D8) | Générer une preuve, commenter une vidéo, partager une pièce = délégation, confirmation, outil existant. Montrer et naviguer **n'engagent rien** : le cerveau rapide les fait seul. |
| **Aucun effet sonore** (F-234 D-PO-5) | Reconduit. Le son d'une vidéo ou d'un audio montré n'est pas un effet : c'est la pièce. |

### 1.2 Décisions antérieures amendées (signalées explicitement)

| Décision | Aujourd'hui | Amendement F-237 | Pourquoi |
|---|---|---|---|
| **F-235 D8** « le cerveau rapide n'a aucun outil d'action **ni de lecture** » | un seul outil, `deleguer` | **Deux outils de plus, sans effet de bord** : `pieces` (lecture en base passerelle, jamais le poste) et `afficher` (pilote l'écran). Toujours **aucun outil d'action**. | P4/P5 : montrer et naviguer doivent être **instantanés et sans coût Opus**. |
| **F-234 D1** « aucun appel réseau pendant un tour » | univers chargé à l'ouverture | **Lecture des vignettes et des contenus de pièces autorisée pendant un tour**, uniquement quand une pièce est montrée (paresseuse, annulable). Le lecteur de scène, lui, reste sans réseau. | Le contenu réel ne peut pas être préchargé. |
| **F-234 SF-234-13** « `🖥 montrer:` = 6 filtres fermés sur l'univers » | nœuds seulement | **Grammaire étendue** (§6.3) : `pieces`, `preuves`, `entrees`, `galerie`, identifiants de pièces. Les 6 filtres restent. | P1/P4 côté Opus, sans toucher au préfixe (la consigne orale vit dans le **message**). |
| **F-236 SF-236-23** mode présentation : « Montrer non rendu » | rien de détaillé | **Reconduit par défaut** : aucune pièce n'est rendue en présentation ; exception pièce par pièce si le PO le décide (§18 Q1). | Pas de fuite devant un tiers. |

---

## 2. Vérifications faites (2026-11-02, `origin/main` `c26d73a4`)

- **Numéro :** F-237 libre dans `docs/PRODUCT_SPEC.md` et `docs/features/` (dernier pris : F-236).
- **Aucun binaire en base** : ni `bytea` ni `@Lob`. Tout passe par `WorkspaceStorage` (S3 en cluster, mémoire en dev)
  **ou reste sur le poste** (fichiers du projet, lus à la volée par le runner).
- **Le chantier n'est une colonne d'aucun média.** Le seul lien est `atelier_messages.chantier_number` (migration
  178), atteint par `message_id` (dépôts, besoins, envois) ou par le `call_id` / bloc riche rangé dans
  `atelier_messages.tool_trace` / `terminal_json` (preuves, pages, vidéos, moments Teams).
- **Mesuré en prod (lecture seule, comptages seulement)** : 21 chantiers ; **122 messages sur 3 524** portent un
  `chantier_number` ; **263 dépôts** (13 sujets) dont **190 rattachés à un message** mais **6 seulement à un message
  de chantier** ⇒ le rattachement par message **ne suffit pas** : il faut le repli par **fenêtre de temps du
  chantier** (§4.2).
- **Jarvis ne sait afficher aucun média aujourd'hui** : `🖥 montrer:` ne désigne que des nœuds (client, chantier,
  personne) ; la scène S7 (`page_publish`, `render_diagram`, `video_*`…) ne dessine qu'une forme + un titre ; aucun
  `<img>`, `<video>`, `<iframe>` dans `frontend/src/app/jarvis`. `JarvisUniverse` ne contient aucune référence média.
- **Cerveau rapide F-235** (`jarvis/fastbrain/JarvisFastBrainPrompt.java`, version `2`) : **un seul outil**
  `deleguer` ; **actif en prod sur `claude-sonnet-5-5`**, budget 50 €/mois. **Écart constaté** : le cadrage F-235 D7
  prévoyait qu'il émette la ligne `🖥 montrer:` ; sa consigne **ne la mentionne pas** — seul Opus l'émet (consigne
  orale `JarvisOralNote`). F-237 corrige l'écart par l'outil `afficher` (§6).
- **Banc existant** : `POST /api/admin/jarvis/fast-brain/bench` (`JarvisFastBrainBenchService`, catégories CASUAL,
  ALERT, ACTION, SENSITIVE ; juge Opus 5.5). Réutilisé (§16).
- **Génération de preuves existante** (F-212) : outil Opus `capturer_preuves` (`ProofToolCatalog`, exécuté par le
  runner `proof_run`, capacité `proof`, ≤ 8 commandes, même autorisation que bash) ; captures en S3
  `preuves/{u}/{ws}/…png`, table `proof_captures`, événement SSE `proof` publié pendant le tour
  (`AtelierChatController`), lecture `GET /workspaces/{id}/proofs/{captureId}`. **Pas de liste.**
- **Galerie vidéo existante** (F-213/214/215) : `GET /videos?workspaceId|hostId` (`VideoGalleryResponse` : séries,
  épisodes, chapitres, couverture), média par **ticket signé** avec `Range` (`VideoMediaController`).
- **Se laisser couper** (F-233 SF-233-08, `jarvis-barge-in.ts`) : détection de voix locale, `duck()` / `cut()` —
  réutilisé pour le son des vidéos (§8.5).
- **Ordres reconnus sans IA** : précédents `isStopCommand`, `classifyConfirmation`, `subjectSwitch`, « c'est bon »
  (SF-234-13) ⇒ la grammaire locale de navigation (§5.3) suit le même modèle.

---

## 3. Inventaire exhaustif de ce qui peut se montrer

Volumes mesurés en prod le 2026-11-02 (comptages, aucun contenu). « Desc. » = description réutilisable.

| # | Type (feature) | Stockage | Rattachement chantier / sujet / client | Lecture existante · `user_id` | Format | Desc. réutilisable | Prod | Retenu |
|---|---|---|---|---|---|---|---|---|
| E1 | **Médias collés / déposés** dans le terminal (F-201, F-115, aperçu F-228) | `atelier_deposited_files` (`path`, `size_bytes`, `message_id`, `consumed_at`) ; binaire **sur le poste** (`.atelier/entrees/`) ou S3 `atelier/{u}/{ws}/entrees/` (hébergé) | sujet ; chantier via `message_id → chantier_number`, sinon fenêtre de temps | `GET /workspaces/{id}/deposits/{depositId}/content` (≤ 20 Mo) et `/download` · **oui** · **poste connecté requis** | type par extension : png 217, zip 9, pdf 8, xlsx 7, docx 6, **m4a 4** (≈ 7 Mo), **mp4 2** (≈ 99 Mo) | **texte du message** qui l'accompagne, avec marqueur `[Image #n]` / `[Vidéo #n]` ; nom horodaté | 263 (329 Mo, 13 sujets) | **Oui — cœur « Entrées »** |
| E2 | **Besoin initial du chantier** | `atelier_messages` : premier message `role=user` de `chantier_number=N` | chantier | `GET /workspaces/{id}/chat` · oui | texte | c'est la description elle-même | 122 messages de chantier | **Oui** (carte texte) |
| E3 | **Besoins** (F-205) et leurs pièces | `app_requests` (`title`, `description`, `reformulation`, `observed_context`, `status`), `app_request_attachments` → S3 `besoins/{requestId}/…` | sujet, `message_id` | pièces : **admin seulement** (`/admin/app-requests/{id}/attachments/{n}`) | texte + binaire | `reformulation`, `description` | 5 besoins (5 LIVRE), 0 pièce | Oui (texte) ; pièces : **manque** une lecture propriétaire (SF-237-02) |
| P1 | **Captures de preuve** (F-212) | `proof_captures` (`title`, `step`, `position`, `commands_json`, `storage_key`) ; S3 `preuves/…png` | sujet ; chantier via `call_id → tool_trace → chantier_number` | `GET /workspaces/{id}/proofs/{captureId}` · oui · **pas de liste** | PNG (terminal rendu) | `title`, `step` (étape prouvée), commandes + statut (sans la sortie) | 8 (2 sujets, 6 sessions) | **Oui — cœur « Preuves »** |
| P2 | **« Marquer envoyé »** (F-211) | `message_block_sends` (`subject`, `recipient`, `excerpt`, `sent_at`) | `message_id` | `GET /workspaces/{id}/messages/sends` · oui | texte | `subject`, `excerpt` | 30 | **Oui** (preuve d'envoi, carte texte) |
| P3 | **Courriels au client** (`ClientMailOutbox`) | `client_emails` (`subject`, `body_text`, `body_html`, `attachment_count`, `status`) ; PJ S3 `client-emails/…` **effacées après envoi** | sujet, client | `GET /client-emails/{id}` · oui · **sans le corps** | HTML / texte | `subject`, `body_text` | 53 (14 avec PJ) | **Oui** (corps en lecture : SF-237-02) ; PJ envoyées : non (effacées) |
| P4 | **Moments Teams** (F-89/F-90) | pas de table ; ids dans `tool_trace` ; S3 `teams-moments/{u}/{ws}/…` | sujet, message | `GET /workspaces/{id}/teams/moments/{imageId}` · oui | PNG/JPEG | `quote`, `speaker`, `at` | 3 messages | **Oui** (preuve « il l'a dit ») |
| R1 | **Pages HTML** (F-109, F-142) | `pages`, `page_versions` ; S3 `pages/{u}/{id}/v{n}/index.html` + `files/` (images et schémas joints, inlinés par `PageImageInliner`) | sujet, client ; chantier via le message qui a publié | `GET /pages/{id}/content` (ticket → `/p/{token}/`) · oui | HTML (+ PNG/SVG) | `title`, `description` (« ce que montre la page »), titres `h2` = sections | 39 pages / 79 versions (9 sujets, 16 Mo) | **Oui** |
| R2 | **PDF d'une page** (F-184) | **non conservé** (cache mémoire 10 min) ; rendu `diagram-renderer /pdf` | celui de la page | `GET /pages/{id}/pdf` · oui | PDF | celle de la page | à la demande | **Oui** (« ouvre le PDF ») |
| R3 | **Schémas / architecture** (F-142, `render_diagram` : mermaid, `cloud` + icônes officielles, `drawio`) | **non conservés côté passerelle** : déposés dans le projet ; la `spec` (groupes, nœuds, liens) n'existe que dans l'entrée d'outil (`tool_trace`) ; conservés **seulement s'ils sont joints à une page** | sujet, message | aucune lecture binaire (`/workspaces/{id}/file` = texte) | SVG / PNG / `.drawio` | `spec.title`, libellés des groupes et nœuds | 25 messages avec `render_diagram` | **Oui — type « architecture »** ; **manque** la conservation (SF-237-03) |
| R4 | **Images IA** (`generate_image`) | `generated_images` (`prompt`, `image_key`) ; S3 `generated-images/…` | sujet, client | `GET /generated-images/{id}/image` · oui · **aucun appel front (dormant)** | PNG | `prompt` | 0 | Oui (gratuit, prêt pour l'usage) |
| R5 | **Présentations** (F-129, `presentation_publish`) | `presentations` (`slide_count`) ; S3 `…/slides/0001.png` | sujet, client | `GET /presentations/{id}/slides/{n}` · oui | PNG par diapo | `title`, `description` | 0 | Oui (diaporama gratuit) |
| R6 | **Vidéos et cours HyperFrames** (F-213/214/215) | `videos` (`kind`, `series_id`, `episode`, `duration_s`), `video_versions` (`chapters_json`, `poster_key`, `sources_json`), `video_series`, `video_comments` ; S3 `videos/…` | sujet, client ; **série / épisode** (= la galerie « cours ») | galerie `GET /videos?workspaceId`, média par ticket (`Range`) · oui | MP4 + couverture | `title`, `description`, **chapitres**, titre de série, sources | 15 vidéos (≈ 114 s) + 4 audio, 2 séries, 127 Mo | **Oui — galerie prioritaire** |
| R7 | **Audio publié** (`audio_publier`) | `videos` `kind=AUDIO` | idem | idem | audio | `title`, pistes | 4 | **Oui** |
| R8 | **Bilans de session** (F-155) | `session_bilans` (JSON) | sujet | `/admin/bilans/{id}` (admin) | JSON → panneau | suggestions | 8 | Non (déjà rendus par F-236 ; pas une « pièce ») |
| R9 | **Carte du poste** (F-173/F-174) | `host_map_*` (graphe) | client | `/governance/hosts/{h}/map/graph` · oui | graphe | titres, faits | 3 949 entités | Non en F-237 (point d'extension §10) |
| X1 | **Réunions Vigie** : audio, images du partage d'écran, transcription (F-128) | `meetings` (`audio_key`, `image_count`, `transcript`) ; S3 `teams-meetings/…` | `host_id`, `subject_id` **radar** (pas le sujet terminal) | `/vigie/hosts/{h}/meetings/{m}/audio|images|transcript` · oui | audio, images | `title`, transcription | 13 réunions, 0 média non purgé, 1 transcription | **Plus tard** (pas de lien au chantier ; §14 idée 9) |
| X2 | **Vidéo regardée par l'agent** (F-190) : images extraites, transcription | **poste seulement** (`.atelier/video/`) | — | aucune | images | texte du résultat d'outil (`tool_trace`) | — | **Non** (rien n'est conservé ; la vidéo d'origine, elle, est un dépôt E1) |
| X3 | **Audio Jarvis / dictée** (F-231/F-233) | **jamais conservé** (par conception) | — | — | — | la transcription devient le message | — | Non (le message texte est E2) |
| X4 | **Documents OCR** (F-04/F-05) et **PJ du chat** (`/upload`) | `documents` (texte extrait ; original non conservé), `uploaded_files` (chez le fournisseur) | ni sujet ni chantier | texte seulement | — | `extracted_text` | 5 / 6 | Non (chat historique, hors Atelier) |
| X5 | **Fichiers binaires du dépôt** (images, PDF du projet) | poste / hébergé | sujet | **texte seulement** | — | chemin | — | Non en F-237 (seuls les dépôts E1 ont un aperçu binaire) |
| X6 | **Preuves Radar** (`radar_evidence`) | texte | client, sujet radar | routes Radar | texte | `quote` | 0 | Non (texte, pas une pièce) |

### 3.1 Catégories retenues pour la galerie

| Catégorie (dite) | Contenu | Ordre |
|---|---|---|
| **Entrées** (« ce que vous m'aviez fourni ») | E2 besoin initial (toujours en tête), E1 dépôts, E3 besoin et sa reformulation | chronologique |
| **Preuves** (« ce qui prouve que c'est fait ») | P1 captures de preuve, P2 envois marqués, P3 courriels envoyés, P4 moments Teams | par étape prouvée, puis date |
| **Productions** (« ce que j'ai produit ») | R1 pages (+ R2 PDF), R3 schémas / architecture, R4 images, R5 présentations, R6/R7 vidéos et audio | par type, puis date |
| **Galeries nommées** | une **série** de cours (R6), les **versions** d'une page ou d'une vidéo, les **diapos** d'une présentation | ordre natif (épisode, version, diapo) |

### 3.2 Dormant (existe, non relié) — F-237 le **branche** plutôt que de développer

- `GET /generated-images*` complet, **aucun appelant** front.
- Diapos PNG des présentations : aucune vue.
- `video_versions.chapters_json` et `poster_key` : ignorés par Jarvis.
- `client_emails.body_text` / `body_html` stockés, **aucune lecture** ne les rend.
- `proof_captures` : lecture à l'unité, **pas de liste** ; aucun lien direct au chantier.
- `video_comments` (commentaires horodatés) : table prête, **0 ligne** — support naturel du « commentaire vocal » (§14).
- Les blocs riches (`page`, `video`, `proof`, `card`) de `tool_trace` / `terminal_json` portent l'unique lien au
  chantier, **indexé nulle part**.

### 3.3 Manques

- **Lien média → chantier** : aucune colonne ; 6 dépôts seulement atteignent un chantier par leur message (§4.2).
- **Schémas non conservés** côté passerelle (sauf joints à une page) ; la `spec` d'architecture se perd dans
  `tool_trace` ⇒ **SF-237-03**.
- **Corps des courriels** et **pièces de besoin** non lisibles par leur propriétaire ⇒ SF-237-02.
- **Dépôts sur le poste** : illisibles poste déconnecté (pas de copie serveur, par choix F-228) ⇒ affichés
  « sur le poste — hors ligne », jamais copiés en douce (§17 D9).
- **Aucune légende** pour une image seule (dépôt, capture, moment) : le texte du message est la seule source (§11).
- **Rien de conservé** pour F-190 (images extraites), l'audio Jarvis, les PJ de courriel envoyées, les PDF.

---

## 4. L'index des pièces (passerelle, lecture seule)

### 4.1 Lecture

`GET /api/jarvis/pieces?subjectId=&chantier=&category=&kind=&since=&until=&series=&limit=` (`JarvisPieceController`,
`requireOwned` sur le sujet, filtre `user_id` partout ; sans `subjectId` : tous les sujets du compte, borné à 200).
Aucune table : un **agrégat** sur les sources §3, calculé à la demande, mis en cache 60 s par (utilisateur, sujet).

```
Piece {
  id            "dep:<uuid>" | "proof:<uuid>" | "page:<uuid>@v3" | "video:<uuid>@v2" | "img:<uuid>" |
                "slides:<uuid>" | "send:<uuid>" | "mail:<uuid>" | "moment:<ws>/<imageId>" | "need:<msgId>" | "diag:<uuid>"
  category      ENTREE | PREUVE | PRODUCTION
  kind          IMAGE | VIDEO | AUDIO | PDF | PAGE | SLIDES | ARCHITECTURE | DIAGRAM | TEXT | FILE
  title         ≤ 120 car.
  caption       description réutilisée, ≤ 600 car. (§11) — null si aucune
  captionSource TITLE | DESCRIPTION | MESSAGE | STEP | CHAPTERS | REFORMULATION | SUBJECT | PROMPT | SPEC
  at            date de création
  subjectId, chantier (nullable), chantierLink  MESSAGE | WINDOW | NONE
  thumb         chemin passerelle de la vignette (nullable)
  open          chemin passerelle du contenu (ticket pour vidéo / page)
  meta          { durationS, chapters[], pages, slides, step, series, episode, width, height, sizeBytes }
  available     ONLINE | HOST_OFFLINE | PURGED
}
```

### 4.2 Rattachement au chantier (déterministe, documenté dans la réponse)

1. **Par message** (`chantierLink=MESSAGE`) : la pièce est liée à un message (`message_id`, `call_id` dans
   `tool_trace`, identifiant dans un bloc riche) qui porte `chantier_number`.
2. **Par fenêtre** (`WINDOW`) : sinon, pièce du **même sujet** créée entre `opened_at` et `closed_at` (ou maintenant)
   d'un **seul** chantier ouvert à ce moment-là.
3. **Aucun** (`NONE`) : plusieurs chantiers ouverts en même temps, ou hors fenêtre ⇒ la pièce reste au niveau du
   **sujet**. Jarvis ne l'attribue jamais à un chantier d'autorité ; l'outil rend ce champ pour qu'il le dise
   (« d'après la date »).

### 4.3 Contenus manquants servis par la passerelle (SF-237-02)

- liste des preuves (dans l'index) ; corps texte d'un courriel (`GET /api/jarvis/pieces/mail:<id>/text`, `body_text`
  seulement, jamais le HTML brut) ; pièces de besoin pour leur propriétaire ; vignettes : PNG réduit à 320 px par la
  passerelle pour les images S3 (cache mémoire), couverture pour les vidéos, diapo 1 pour les présentations, **aucune**
  pour un dépôt sur poste (chargement de l'original, ≤ 20 Mo, à l'ouverture seulement).

---

## 5. L'état de navigation (une seule source de vérité)

### 5.1 Modèle (`frontend/src/app/jarvis/pieces/jarvis-piece-nav.ts`, **pur**)

```
PieceNav {
  level     UNIVERS | CHANTIER | GALERIE | PIECE | PAGE | INSTANT
  scope     { subjectId, chantier?, category?, kind?, series?, label }      // « Entrées du chantier 3 — Edenred »
  items     [{ index (1..n), id, kind, title, at }]                          // ≤ 24 chargées, « +n » au-delà
  selected  index | null
  page      n (PDF, diapo, section de page) | null
  time      secondes (vidéo, audio) | null ; playing: boolean ; chapter: index | null
  zoom      1..8 ; pan {x, y}
  origin    ASKED | PROACTIVE                                               // demandé ou d'initiative
  history   pile des niveaux précédents (≤ 8) pour « retour »
}
```

**Actions** (réducteur pur, mêmes actions pour le toucher, la grammaire locale et l'outil `afficher`) :
`openGallery(scope)`, `select(index | id | titre approché)`, `next`, `prev`, `back`, `up` (remonter au chantier),
`close`, `play`, `pause`, `seek(t | chapitre)`, `page(n | +1 | -1)`, `zoom(+ | - | n | reset)`, `pan(dir)`,
`fullscreen`. Toute action invalide (index hors bornes, `page` sur une vidéo) ⇒ état inchangé + raison
(`OUT_OF_RANGE`, `NOT_APPLICABLE`) rendue à l'appelant pour qu'il le dise.

### 5.2 L'IA connaît l'état — dans le message, jamais dans la consigne

À chaque parole envoyée au cerveau rapide (et à chaque tour Opus né à la voix), l'écran joint un bloc
**`## Écran`** au contexte du **dernier message** (même endroit que le contexte D5 de F-235) :

```
## Écran
Galerie : Entrées du chantier 3 — Edenred (6 pièces) ; sélection : 2
1. image — « Capture de l'erreur 502 » — mardi 10:42
2. vidéo — « Démo du déploiement » — mardi 11:05 — en lecture 0:42 / 1:54 — chapitre 2 « Déploiement »
3. PDF — « Contrat de service » — 12 pages — page 1
…
```

Plafond **1 500 caractères** (titres coupés à 60). Sans galerie ouverte : une ligne « Écran : univers ». Le bloc
« Pièces » (§6.2) suit la même règle.

### 5.3 Ordres triviaux reconnus **sur l'appareil, sans IA** (recommandé)

`jarvis-piece-voice.ts` (pur), appelé **avant** le cerveau rapide **quand une galerie ou une pièce est ouverte** :
liste fermée, normalisée (accents, casse) comme `isStopCommand`.

| Dit | Action |
|---|---|
| « suivante », « suivant », « la suivante », « après » | `next` |
| « précédente », « avant », « la précédente » | `prev` |
| « la première » … « la douzième », « la 3 », « numéro 3 » | `select(n)` |
| « pause », « stop la vidéo », « arrête la vidéo » | `pause` |
| « lecture », « reprends », « continue » | `play` |
| « retour », « reviens à la galerie » | `back` |
| « remonte au chantier », « vue d'ensemble » | `up` |
| « page suivante », « page 4 » | `page` |
| « zoome », « plus près », « dézoome », « recule » | `zoom` |
| « ferme », « c'est bon », « merci » | `close` (comme SF-234-13) |

**Pourquoi :** < 100 ms, 0 €, 0 jeton, et une coupure de la voix de Jarvis immédiate. **Tout le reste** (« celle sur
le déploiement », « le cours 3 », « va au moment où il parle de Terraform », « montre la galerie des vidéos
d'Edenred ») part au **cerveau rapide**, qui répond par `afficher` (§6). Une phrase ambiguë ne passe **jamais** par la
grammaire locale (correspondance **exacte** de la phrase entière après normalisation, sinon cerveau rapide).

---

## 6. Les outils : qui montre quoi

### 6.1 Répartition recommandée

| Qui | Quoi | Comment | Coût |
|---|---|---|---|
| **Appareil** (sans IA) | navigation triviale | grammaire locale §5.3 | 0 € |
| **Cerveau rapide** (Sonnet 5.5 en prod) | **lire** l'index, **montrer**, **naviguer**, **montrer d'initiative**, **proposer** une preuve | outils `pieces` + `afficher` | ≈ un échange rapide (même tarif F-235, budget 50 €/mois) |
| **Opus** (tour du terminal) | **produire** : générer une preuve (`capturer_preuves`), décrire une image sans légende, commenter une vidéo, partager | délégation `deleguer` + confirmation + outils existants ; à la fin, `🖥 montrer:` étendu (§6.3) | tour Opus normal |

### 6.2 Les deux outils du cerveau rapide (définitions **fixes**, version de consigne `3`)

**`pieces`** — *lecture, aucun effet* ; exécuté **par la passerelle** dans la boucle du cerveau rapide (une seule
lecture de plus par réponse, plafonnée) ; résultat = la liste `Piece` compacte (id, kind, title, caption ≤ 200,
at, chantier, chantierLink, available), ≤ 12 éléments.

```
pieces { sujet?, chantier?, categorie? (entrees|preuves|productions), type? (image|video|audio|pdf|page|
         architecture|diaporama|texte), depuis?, jusqua?, serie?, recherche? }
```

**`afficher`** — *pilote l'écran, aucun effet en base* ; la passerelle le relaie au navigateur par un événement SSE
`show` sur `POST /api/jarvis/converse` ; le navigateur l'applique au réducteur §5.1 et rend le résultat (`ok` ou
raison) au cerveau rapide dans le même tour.

```
afficher { action (galerie|piece|suivante|precedente|selection|retour|remonter|fermer|lecture|pause|aller|
           page|zoom|comparer), pieces? [ids ≤ 8], portee? {sujet, chantier, categorie, type, serie},
           index?, temps?, chapitre?, page?, zoom?, raison (demande|appui) }
```

**Description des outils** (texte fixe, extrait — c'est elle qui apprend **quand** montrer) :

> Montre une pièce quand elle **appuie** ce que tu dis, sans attendre qu'on te le demande : (1) **point
> d'avancement** (« où en est-on ? ») : montre le **besoin initial** et ce que l'utilisateur avait fourni, puis la
> dernière preuve ; (2) **doute sur une fin** (« tu es sûr que c'est fini ? », « tu as vérifié ? ») : montre les
> **preuves** du chantier ; s'il n'y en a aucune, **propose** de les générer (« Voulez-vous que je génère la preuve,
> Monsieur ? ») et, sur un oui, délègue ; (3) **origine** (« pourquoi on fait ça ? », « qu'est-ce que je t'avais
> donné ? ») : les **entrées** ; (4) **vérification** d'un envoi (« tu l'as envoyé à Zahi ? ») : l'envoi marqué ou le
> courriel ; (5) **avant / après** : la capture d'entrée et la preuve côte à côte ; (6) **architecture** (« comment
> c'est déployé chez ce client ? ») : le schéma ; (7) **livrable** (« montre ce que tu as fait ») : la page, la
> vidéo, la présentation. Au plus **trois** pièces d'initiative par réponse, toujours du **chantier ou du sujet dont
> on parle** ; jamais une pièce déjà montrée dans les dix dernières minutes ; jamais en mode présentation. Pour
> naviguer, sers-toi du bloc « Écran » : « la deuxième » = l'index 2 de la galerie affichée. Dis toujours la pièce
> que tu montres en une phrase, avec sa description si elle existe ; si elle n'en a pas, dis seulement son titre et
> sa date, n'invente jamais ce qu'elle contient.

**Contexte joint au message** (volatil, donc dans le message) : bloc `## Écran` (§5.2) + bloc **`## Pièces`** :
un compteur par chantier du sujet courant (« chantier 3 : besoin initial, 4 entrées, 0 preuve, 1 page, 2 vidéos »,
≤ 600 car.). **Effet :** dans la plupart des cas le cerveau rapide appelle **directement** `afficher` avec une portée
(« preuves du chantier 3 ») sans `pieces` — un aller-retour de moins. Il n'appelle `pieces` que pour choisir une
pièce précise (« celle sur le déploiement ») ou lire une description.

**Latence :** `afficher` seul = la réponse habituelle F-235 (le geste part au **premier** appel d'outil, avant la
phrase). `pieces` puis `afficher` = + une lecture passerelle (< 150 ms, cache 60 s) + un appel modèle **en cache** ;
cible : pièce à l'écran < 2,5 s après la fin de la parole (mesuré, §19).

### 6.3 Côté Opus : la ligne `🖥 montrer:` étendue (aucun outil ajouté au tour Opus)

La consigne orale `JarvisOralNote` (dans le **message** d'un tour né à la voix, jamais la consigne système) gagne
une puce : en plus des 6 filtres, `🖥 montrer: preuves [chantier N]`, `🖥 montrer: entrees [chantier N]`,
`🖥 montrer: pieces <id>,<id>` (≤ 8, ids que l'agent a dans son fil : identifiants de captures rendus par
`capturer_preuves`, ids de page, de vidéo). L'écran résout par l'index §4. Même masquage que SF-234-13 (ni dite,
ni affichée, ni résumée). **Pourquoi pas un outil Opus :** ajouter une définition à la liste d'outils du terminal
alourdit **tous** les tours (même tapés) pour un usage vocal ; la ligne marqueur vit dans le message, **préfixe
intact** (D18 F-234 reconduit).

### 6.4 Chaîne « preuve absente → proposer → générer → montrer »

1. PO : « Tu es sûr que le chantier 3 est terminé ? » ⇒ cerveau rapide : bloc `## Pièces` = « 0 preuve ».
2. Il répond (sans action) : « Le chantier 3 est marqué terminé, mais je n'ai aucune preuve en image. Voulez-vous
   que je la génère, Monsieur ? » ⇒ l'écran **arme une confirmation** (mécanisme `classifyConfirmation` F-231).
3. « Oui » ⇒ `deleguer { demande: "Génère les captures de preuve du chantier 3 avec capturer_preuves (commandes de
   vérification de son plan), puis montre-les", label: "preuve du chantier 3" }`. **Aucun « oui » ⇒ rien.**
4. Opus exécute `capturer_preuves` (poste requis, capacité `proof`, autorisation bash habituelle). Chaque capture
   publiée par l'événement SSE `proof` **apparaît dans la galerie au fil de l'eau** (T-G7) ; à la fin, sa ligne
   `🖥 montrer: preuves chantier 3` fige la galerie.
5. Poste hors ligne / runner sans capacité `proof` ⇒ les messages existants (`HOSTED_MESSAGE`,
   `UPDATE_RUNNER_MESSAGE`) sont dits tels quels ; rien n'est inventé.

Preuve existante ⇒ étape 2 devient : `afficher { action: galerie, portee: {chantier: 3, categorie: preuves},
raison: appui }` + « Oui, Monsieur : voici la preuve de l'étape 4, la commande de santé répond 200. » (description =
`step` + commandes).

---

## 7. Montrer d'initiative : situations couvertes

| # | Ce que dit le PO | Ce que Jarvis montre d'initiative | Source de la phrase |
|---|---|---|---|
| I1 | « Où en est-on ? » / « Fais-moi le point sur le chantier 3 » | besoin initial + entrées (≤ 3), puis la dernière preuve | E2, `caption` des dépôts |
| I2 | « Tu es sûr que c'est terminé ? », « Tu as vérifié ? » | preuves, ou proposition de les générer (§6.4) | `step`, commandes |
| I3 | « Qu'est-ce que je t'avais donné au départ ? », « Pourquoi on fait ça ? » | entrées | texte du message |
| I4 | « Tu l'as envoyé à Zahi ? » | envoi marqué / courriel (objet + extrait) | `subject`, `excerpt` |
| I5 | « Ça marche maintenant ? » / « Qu'est-ce qui a changé ? » | **avant / après** : capture d'erreur collée ↔ preuve | §14 idée 1 |
| I6 | « Comment c'est déployé chez Edenred ? » | le schéma d'architecture le plus récent du client | `spec.title`, groupes |
| I7 | « Qu'est-ce que tu as produit aujourd'hui ? » | planche contact du jour (productions) | titres |
| I8 | « Rappelle-moi le cours sur Terraform » | l'épisode + chapitre correspondant | chapitres |
| I9 | « Qu'est-ce que Zahi avait dit en réunion ? » (Teams) | le moment Teams (citation + image) | `quote` |
| I10 | Annonce de fin d'une délégation qui a **produit** une pièce (page, vidéo, preuve) | la pièce produite | titre / description |

**Garde-fous** (déterministes, côté écran **et** dans la description d'outil) : ≤ 3 pièces `raison=appui` par
réponse (au-delà : tronqué, compté) ; pièce hors du sujet courant ou du chantier cité ⇒ refusée (`NOT_IN_SCOPE`) ;
même pièce < 10 min ⇒ ignorée ; mode présentation ⇒ refusé ; mode sobre ⇒ **vignette seule** dans le bandeau, pas de
zoom ; pendant que le PO parle ⇒ différé à la pause suivante ; fermeture < 2 s par le PO = signal « mal montré »
(compté, §19).

---

## 8. Mise en scène 3D

### 8.1 Galerie holographique en éventail

- **Ancrage** : la galerie naît du **nœud du chantier** (ou du sujet) dans l'univers F-236 ; un filament (liaison
  `L-piece`, nouvelle) relie le nœud à chaque carte.
- **Éventail** : cartes en verre holographique (DESIGN_SYSTEM §23) sur un **arc de 110°** devant la caméra, rayon
  fixe, légère profondeur alternée ; ≤ 12 cartes visibles (PC), au-delà « +n » et défilement de l'arc. Chaque carte :
  vignette (texture), pictogramme du type, titre (CSS2D), index visible (« 2 ») — **l'index est ce que le PO dit**.
- **Catégories** : Entrées en teinte normale, Preuves en **vert** de réussite, Productions en orange de la marque
  (palette §23 reconduite ; aucune couleur nouvelle).
- **Sélection** : la carte avance, grandit, devient le **héros** (un seul, règle F-236 §7.3) ; les autres passent
  en latent (palier de transparence F-236).

### 8.2 Visionneuses (une par type, dans le panneau héros)

| Type | Visionneuse | Gestes / voix |
|---|---|---|
| IMAGE | texture plein panneau, netteté native (pas de flou au zoom : chargement de l'original au-delà de ×2) | pincer / molette = zoom (×1-×8), glisser = déplacer, double-toucher = ajuster ; « zoome sur le coin en haut à droite » ⇒ zoom ciblé par quadrant |
| VIDEO / AUDIO | élément `<video>`/`<audio>` du DOM, en **texture vidéo** sur PC (panneau 3D), en panneau DOM sur téléphone ; chapitres en jalons sur la barre ; forme d'onde pour l'audio | lecture / pause / aller à / chapitre, à la voix et au toucher |
| PDF | `pdfjs-dist` (version figée ≥ 2 semaines), une page = une texture, **pages qui défilent** en pile (la courante devant, les suivantes en retrait) | page suivante / n, zoom |
| PAGE (HTML) | iframe **bac à sable** (`/p/{token}/`, CSP existante) dans un panneau DOM ; sections `h2` = « pages » | défilement par section, « ouvre le PDF » ⇒ bascule R2 |
| SLIDES | diapos PNG en pile, comme le PDF | diapo suivante / n |
| ARCHITECTURE / DIAGRAM | SVG vectoriel plein panneau, zoom sans perte, déplacement ; groupes de la `spec` = **cibles nommées** (« zoome sur le VPC de prod ») | zoom, déplacer, sélection d'un groupe ou nœud par son libellé (§10) |
| TEXT | carte de verre : besoin initial, courriel (objet, extrait), envoi marqué | défilement |
| FILE (zip, xlsx, docx) | fiche : nom, taille, date, message associé — **pas d'aperçu** | — |

### 8.3 Diaporama narré (« raconte-moi le chantier 3 »)

La galerie passe en lecture automatique : une pièce = une phrase de Jarvis (description réutilisée §11) ; la
pièce suivante arrive **quand la phrase se termine** (événement de fin de phrase de la voix F-233), jamais sur une
minuterie ; une vidéo se lit sur son extrait (chapitre ou 15 s) puis la narration reprend ; « pause » fige ;
« suivante » saute. Ordre du récit : besoin → entrées → étapes prouvées → productions → envois.

### 8.4 Transitions (catalogue F-236 §7.4 prolongé, mêmes courbes nommées)

| # | Transition | Durée | Courbe | Déroulé |
|---|---|---|---|---|
| **T-G1** | Ouverture d'une galerie | 1,4 s | `ressort` puis `rebond` | caméra vers le nœud (T-Z1), puis les cartes **jaillissent** du nœud le long de leur filament (`rebond`, 60 ms d'écart) |
| T-G2 | Fermeture | 0,7 s | `entree` | cartes rappelées vers le nœud, filaments qui se rétractent (T-L2) |
| **T-G3** | Zoom avant vers une pièce | 0,9 s | `ressort` | la carte avance au centre et grandit ; profondeur de champ sur elle (PC) ; le reste en latent (T-L3) |
| T-G4 | Zoom arrière vers la galerie | 0,8 s | `ressort` | inverse de T-G3 |
| T-G5 | Suivante / précédente | 0,5 s | `glisse` | l'arc tourne d'un cran ; la nouvelle carte devient héros |
| T-G6 | Page ou diapo suivante | 0,35 s | `sortie` | la page courante glisse en retrait, la suivante monte |
| T-G7 | Pièce produite en direct (preuve) | 0,6 s | `rebond` | une carte naît du cerveau Opus déployé et rejoint l'arc |
| T-G8 | Remonter au chantier | 1,4 s | séquence | T-G2 puis T-Z2 |

Mouvement : ≤ 1 déplacement de caméra / 2,5 s (règle F-236) ; `prefers-reduced-motion` ⇒ fondus seulement.

### 8.5 Son des pièces et voix de Jarvis

- Jarvis parle ⇒ la vidéo / l'audio **baisse à 15 %** (ducking, rampe 200 ms), remonte 300 ms après sa phrase.
- Le PO parle (détection locale F-233) ⇒ **pause automatique** de la pièce ; reprise si aucun ordre n'arrive en 1,5 s
  (parole non confirmée), sinon l'ordre s'applique.
- Le son de la pièce n'est jamais pris pour la voix du PO : micro avec annulation d'écho (déjà exigée par
  F-233) ; si la détection se déclenche sur le son de la pièce (mesuré au banc), repli : son de la pièce **coupé**
  pendant l'écoute.

### 8.6 PC et téléphone

- **PC** : galerie en éventail dans l'univers, panneau héros au centre, la liste des pièces dans le panneau latéral
  gauche (F-236 SF-236-15), l'état de navigation en fil d'Ariane (« Edenred › Chantier 3 › Entrées › 2/6 »).
  « Épingler au cockpit » ⇒ la pièce passe sur le 2ᵉ écran (`BroadcastChannel` F-236 SF-236-24).
- **Téléphone** : **une pièce à la fois en plein écran** ; balayage gauche / droite = suivante / précédente,
  balayage vers le bas = retour à la galerie (grille 2 colonnes), pincer = zoom ; pas de texture vidéo (DOM natif).
- **Repli 2D** (WebGL absent, mode sobre, mouvement réduit) : grille DOM + visionneuse DOM, **même réducteur**,
  mêmes ordres vocaux. La galerie fonctionne entièrement sans 3D.

### 8.7 Performance

Vignettes 320 px seulement dans l'éventail ; original chargé **à la sélection** ; ≤ 3 originaux en mémoire GPU
(LRU, `dispose()` des textures) ; texture vidéo à 30 i/s max ; PDF : 3 pages rendues autour de la courante ;
chargements annulables (`AbortController`) à chaque changement de sélection ; budget F-236 inchangé (PC ≥ 60 i/s,
téléphone ≥ 50 i/s p50) avec galerie ouverte, mesuré au banc. Mémoire GPU cible : + 150 Mo max galerie ouverte (PC).

---

## 9. Exemples de parcours (servent de banc)

| Pas | Dit | État attendu (`PieceNav`) | Par |
|---|---|---|---|
| 1 | « Montre-moi la galerie de vidéos du sujet Formation » | `GALERIE`, scope {sujet Formation, type video}, items 1..n par épisode | cerveau rapide `afficher` |
| 2 | « La deuxième » | `PIECE`, selected 2, playing true | **local** |
| 3 | « Pause » | playing false | **local** |
| 4 | « Va au chapitre sur le déploiement » | `INSTANT`, chapter = celui dont le titre contient « déploiement » | cerveau rapide |
| 5 | « La suivante » | selected 3, time 0 | **local** |
| 6 | « Retour » | `GALERIE`, selected null | **local** |
| 7 | « Remonte au chantier » | `CHANTIER` | **local** |
| — | « Où en est le chantier 3 ? » | `GALERIE` entrées (besoin initial en 1), origin PROACTIVE | cerveau rapide |
| — | « Tu es sûr que c'est fini ? » (0 preuve) | aucune galerie ; confirmation armée | cerveau rapide |
| — | « Oui » | délégation ; cartes T-G7 au fil des événements `proof` ; `GALERIE` preuves à la fin | Opus |

---

## 10. Architecture : type de pièce et points d'extension (rendu 3D hors périmètre)

- **Type `ARCHITECTURE`** : schéma rendu par `render_diagram` avec `engine=cloud` ou `drawio` ; **`DIAGRAM`** pour
  mermaid. Conservé par **SF-237-03** : table `diagram_renders` (`id`, `user_id`, `workspace_id`, `message_id`,
  `chantier_number`, `engine`, `title`, `spec_json` (groupes, nœuds, liens tels que reçus), `svg_key`, `png_key`,
  `drawio_key`, `created_at`) + S3 `diagrams/{u}/{ws}/{id}.{svg|png|drawio}` ; écrite **après** un rendu réussi,
  sans changer l'outil ni sa description (préfixe intact). **Nouvelle table : à reporter dans
  `ARCHITECTURE_CANONIQUE.md`.**
- **Panneau 2D holographique** (F-237) : SVG zoomable, déplacement, **cibles nommées** = groupes et nœuds de la
  `spec` (libellé → boîte englobante lue dans le SVG par l'identifiant d'élément) ; « zoome sur le VPC de prod » ⇒
  `afficher { action: zoom, cible: "VPC de prod" }` ; commentaire oral = `spec.title` + groupes de premier niveau.
- **Points d'extension réservés** (pour la feature de rendu 3D de l'architecture, cadrée ailleurs) :
  1. `Piece.kind = ARCHITECTURE` + `spec_json` servi par l'index (`GET /api/jarvis/pieces/diag:<id>/spec`) ;
  2. un **registre de visionneuses** `PIECE_VIEWERS: Record<kind, Viewer>` (même principe que `SCENES_3D`) : une
     visionneuse 3D pourra **remplacer** la visionneuse SVG de `ARCHITECTURE` sans toucher au reste ;
  3. le réducteur `PieceNav` accepte une **cible nommée** générique (`target: string`) et un niveau `INSTANT`
     réutilisables pour « entrer dans un sous-réseau » ;
  4. l'outil `afficher` porte déjà `cible` : aucune nouvelle définition d'outil ne sera nécessaire.

---

## 11. Descriptions : ce que Jarvis dit de chaque pièce

**Ordre de priorité** (première source non vide, déterministe, `captionSource` rendu) :

| Type | 1 | 2 | 3 |
|---|---|---|---|
| Dépôt (E1) | texte du message autour du marqueur `[Image #n]` (phrase qui le contient, ≤ 300 car.) | texte du message entier (≤ 300) | nom + date |
| Besoin initial (E2) | le message lui-même (≤ 600) | — | — |
| Besoin (E3) | `reformulation` | `description` | `title` |
| Preuve (P1) | `title` + étape prouvée (`step`) + « n commandes, toutes réussies / k en échec » | — | — |
| Envoi / courriel (P2, P3) | objet + destinataire + extrait | — | — |
| Moment Teams (P4) | « `speaker` : `quote` » | — | — |
| Page (R1) | `description` | `title` | — |
| Schéma (R3) | `spec.title` + groupes de 1ᵉʳ niveau | nom de fichier | — |
| Image IA (R4) | `prompt` (≤ 200) | — | — |
| Présentation (R5) | `description` | `title` + nombre de diapos | — |
| Vidéo / audio (R6, R7) | `description` + titre du chapitre courant | série + épisode + `title` | — |

**Description absente** — options étudiées : (a) ne rien dire de plus que le titre et la date ; (b) décrire à la
demande par Opus (vision du fournisseur), ≈ 2 à 5 centimes d'euro par image (image ≈ 1,5 k jetons + consigne en
cache), plafonné ; (c) décrire automatiquement toute pièce à l'indexation. **Recommandé : (a) par défaut + (b) sur
demande explicite** (« qu'est-ce qu'on voit ? », « décris-la ») = délégation Opus normale, tracée dans le fil,
comptée dans le quota existant ; la description obtenue n'est **pas** stockée comme légende (pas de nouvelle table,
elle reste dans le fil et le recall F-162 la retrouve). (c) rejeté : coût sans demande, contraire à « justesse avant
coût » et à Provider-First (indexation d'image = moteur IA maison).

---

## 12. Sécurité

- **Aucune URL S3 au navigateur** : tout contenu passe par la passerelle (JWT) ou par les **tickets existants**
  (vidéo `/api/video-media/{ticket}`, page `/p/{token}/`), émis par compte, courte durée. Images : `fetch` avec JWT →
  `URL.createObjectURL`, révoquée à la fermeture.
- **Isolation** : l'index filtre `user_id` à chaque source et `requireOwned` sur le sujet ; un identifiant de pièce
  d'un autre compte ⇒ **404** (jamais 403, pas de fuite d'existence) ; tests A/B par source (§16).
- **Le cerveau rapide ne reçoit jamais de binaire** : `pieces` rend du texte borné ; `afficher` ne rend que `ok` /
  raison.
- **Mode présentation** (SF-236-23) : aucune pièce rendue, galerie refusée (`PRESENTATION`), bloc `## Écran` vide ;
  la décision d'une exception relève du PO (§18 Q1).
- **Secrets** : les captures de preuve montrent la sortie réelle du terminal ; F-237 **n'en crée pas** de nouvelles
  (il affiche celles déjà produites et visibles dans le fil). La SF-237-01 vérifie le masquage existant appliqué à
  `capturer_preuves` ; s'il n'existe pas, elle le signale (sujet distinct, hors F-237) sans bloquer l'affichage
  d'une pièce **déjà visible** dans le terminal.
- **HTML** : page en iframe bac à sable (CSP existante) ; corps de courriel en **texte**, jamais `body_html` injecté.
- **Dépôts sur poste** : lus par le chemin existant (`DepositContentService`, ≤ 20 Mo, liste blanche
  `atelier_deposited_files`), jamais un chemin libre.

---

## 13. Coût IA (en euros)

| Poste | Coût |
|---|---|
| Navigation triviale (local) | **0 €** |
| `afficher` / `pieces` par le cerveau rapide | compris dans l'échange rapide F-235 ; consigne + 3 outils en cache (version `3` = **une** réécriture du cache puis stable) ; + ≈ 1 k jetons de contexte hors cache (`## Écran` + `## Pièces`) ⇒ ordre de grandeur **< 0,5 centime par échange** |
| Description absente sur demande | délégation Opus, ≈ 2-5 centimes par image, compté au quota |
| Génération de preuve | tour Opus + runner, comme aujourd'hui |
| Indexation, vignettes, rendu | **0 €** (aucun appel modèle) |

Budget du cerveau rapide inchangé (50 €/mois, coupe-circuit existant).

---

## 14. Autres fonctionnalités « de ce style » proposées

| # | Idée | Intérêt | Où |
|---|---|---|---|
| 1 | **Avant / après** côte à côte (capture d'erreur collée ↔ preuve ; page v1 ↔ v3 ; vidéo v1 ↔ v2) avec rideau glissant | répond à « ça marche maintenant ? » d'un coup d'œil | SF-237-11 |
| 2 | **Raconter un chantier** en diaporama narré (besoin → entrées → preuves → livrable → envoi) | le « point » visuel complet en 1 min | SF-237-10 |
| 3 | **Commentaire vocal horodaté** sur une vidéo (« note ici : le logo est trop petit ») → `video_comments` (table prête, 0 ligne) | retour sur un cours sans clavier ; action ⇒ Opus + oui | SF-237-12 |
| 4 | **Épingler une pièce au cockpit** (2ᵉ écran) | montrer sur grand écran en parlant | SF-237-12 |
| 5 | **Planche contact du jour / de la semaine** (« qu'est-ce qu'on a produit ? ») | bilan visuel sans lire le fil | SF-237-11 |
| 6 | **Preuve manquante signalée** : à la clôture d'un chantier sans preuve, la carte « preuve » vide clignote en attente et Jarvis propose de la générer | justesse des fins | SF-237-09 |
| 7 | **Visite guidée d'une page** : défilement section par section, chaque `h2` commenté | présenter un livrable | SF-237-06 |
| 8 | **Chapitres à la voix** (« va au moment où il parle de Terraform ») | navigation fine dans les cours | SF-237-08 |
| 9 | **Réunions Vigie** : images du partage d'écran alignées sur la transcription | revoir une réunion | **plus tard** (lien sujet radar ↔ chantier absent) |
| 10 | **Partager une pièce** (lien de partage de page existant `page_shares`) à la voix | action ⇒ Opus + oui | **plus tard**, sur usage observé |
| 11 | **Architecture en 3D** | — | **feature distincte**, points d'extension §10 |

---

## 15. Itérations et découpage — SF-237-01 → SF-237-14 (≤ 2 j chacune, ≈ 24 j)

### Itération A — « Jarvis sait ce qu'il a » (passerelle, lecture seule) — 5 j

| SF | Contenu | Jours |
|---|---|---|
| **SF-237-01** | **Index des pièces** : `GET /api/jarvis/pieces` (§4), agrégat E1, E2, E3, P1-P4, R1, R4-R7 ; rattachement §4.2 (message, fenêtre, aucun) ; descriptions §11 + `captionSource` ; disponibilité poste ; cache 60 s ; tests d'isolation A/B **par source**, rattachement (3 cas), priorité des descriptions. | 2 |
| **SF-237-02** | **Contenus manquants** : vignettes (320 px, cache), corps texte d'un courriel, pièces de besoin pour leur propriétaire, PDF de page via l'index, tickets réutilisés ; tests A/B. | 1,5 |
| **SF-237-03** | **Conserver les schémas** : `diagram_renders` + S3, écrit après un rendu réussi (outil et description inchangés), type `ARCHITECTURE` / `DIAGRAM`, `spec` servie ; migration additive ; `ARCHITECTURE_CANONIQUE.md`. | 1,5 |

### Itération B — « Jarvis montre » (écran, toucher + voix locale) — 7,5 j

| SF | Contenu | Jours |
|---|---|---|
| **SF-237-04** | **État de navigation** `jarvis-piece-nav.ts` (pur) + **grammaire locale** `jarvis-piece-voice.ts` + sérialisation `## Écran` (≤ 1 500 car.) ; registre `PIECE_VIEWERS` + garde (toute `kind` a une visionneuse ou la fiche FILE) ; tests : réducteur exhaustif, grammaire (phrases exactes, ambiguës ⇒ IA). | 2 |
| **SF-237-05** | **Galerie holographique 3D** : éventail depuis le nœud, filaments `L-piece`, cartes, index, catégories, T-G1-T-G5 + T-G8, toucher, téléphone plein écran + balayages, **repli 2D** (même réducteur), filtre mode présentation. | 2 |
| **SF-237-06** | **Visionneuses** image (zoom/déplacement), vidéo/audio (texture PC, chapitres, ducking §8.5), PDF (`pdfjs-dist`), page (iframe, sections), diapos, texte, fiche ; T-G6 ; LRU GPU. | 2 |
| **SF-237-07** | **Schémas / architecture** sur panneau : SVG zoomable, cibles nommées depuis la `spec`, commentaire ; points d'extension §10 testés (remplacement de visionneuse). | 1,5 |

### Itération C — « Jarvis décide de montrer » (IA) — 5,5 j

| SF | Contenu | Jours |
|---|---|---|
| **SF-237-08** | **Outils du cerveau rapide** `pieces` + `afficher` (définitions fixes, consigne version `3`, amendement F-235 D8), boucle d'outil passerelle (1 lecture max), événement SSE `show`, blocs `## Écran` et `## Pièces` dans le message ; garde-fous §7 ; test : consigne + outils identiques octet pour octet d'un appel à l'autre. | 2 |
| **SF-237-09** | **Côté Opus et preuves** : ligne `🖥 montrer:` étendue (`JarvisOralNote`, `jarvis-show.ts`), masquage ; cartes au fil des événements `proof` (T-G7) ; chaîne §6.4 (proposition → oui → délégation `capturer_preuves` → galerie) ; preuve manquante à la clôture (§14 idée 6). | 1,5 |
| **SF-237-10** | **Diaporama narré** : file de pièces calée sur la fin des phrases, extraits vidéo, « raconte le chantier ». | 2 |

### Itération D — « Au-delà » et mesure — 6 j

| SF | Contenu | Jours |
|---|---|---|
| **SF-237-11** | **Avant / après** (rideau), comparaison de versions, planche contact du jour. | 1,5 |
| **SF-237-12** | **Commentaire vocal horodaté** d'une vidéo (`video_comments`, Opus + oui) ; **épingler au cockpit**. | 1,5 |
| **SF-237-13** | **Bancs** : catégories `SHOW`, `NAVIGATE`, `PROOF` dans le banc du cerveau rapide (≥ 30 cas, contexte fixe) ; **banc de séquences** (§9) rejoué sur le réducteur + fournisseur simulé ; banc perf galerie ouverte. | 1,5 |
| **SF-237-14** | **Mesure** (§19) + **polissage** + planche de captures visée par le PO. | 1,5 |

**Total ≈ 24 j.** Chaque itération est déployable seule : A sans écran n'a pas d'effet visible (déployée avec B).

---

## 16. Critères d'acceptation (feature) et plan de test

### Critères

1. « Montre-moi les entrées du chantier 3 » ⇒ galerie avec le **besoin initial en 1**, puis les dépôts réels du
   chantier ; une image s'affiche en vrai, un m4a se lit, une vidéo se lit.
2. « Montre-moi les preuves » ⇒ les captures `proof_captures` du chantier, avec étape et commandes dites.
3. **Proactif** — banc (contexte fixe, ≥ 10 variantes par phrase) : « où en est-on ? », « fais-moi le point »,
   « tu es sûr que c'est fini ? », « tu as vérifié ? », « qu'est-ce que je t'avais donné au départ ? », « comment
   c'est déployé chez X ? » ⇒ `afficher` appelé avec `raison=appui` et la bonne portée dans **≥ 90 %** des cas ;
   phrases neutres (« merci », « quelle heure est-il ? ») ⇒ **0 %** d'affichage ; jamais > 3 pièces d'initiative.
4. Preuve absente ⇒ proposition dite, **aucune** délégation sans « oui » (0 au banc) ; « oui » ⇒ délégation
   `capturer_preuves`, captures affichées au fil de l'eau.
5. **Navigation** — banc de séquences §9 : état attendu à **chaque** pas (100 %) ; ordres de la grammaire locale
   traités **sans** appel au cerveau rapide (compté) ; < 100 ms.
6. Toucher et voix produisent **le même état** (mêmes actions sur le même réducteur, test).
7. Le son d'une vidéo baisse quand Jarvis parle, se met en pause quand le PO parle.
8. Mode présentation ⇒ aucune pièce rendue, `afficher` refusé.
9. Identifiant de pièce d'un autre compte ⇒ 404, à chaque source.
10. Consigne système et définitions d'outils **identiques** octet pour octet d'un appel à l'autre (cerveau rapide)
    et liste d'outils Opus **inchangée** (test passerelle).
11. Galerie ouverte : PC ≥ 60 i/s p50, téléphone ≥ 50 i/s p50 ; ≤ 3 originaux en mémoire GPU.
12. Poste déconnecté ⇒ dépôt affiché « sur le poste — hors ligne », jamais une autre pièce.

### Plan de test

- **Unitaires back** : rattachement (message / fenêtre / aucun / chantiers concurrents), priorité des descriptions,
  `captionSource`, bornes, cache ; `diagram_renders` écrit après rendu réussi seulement.
- **Isolation A/B** (une par source : dépôt, preuve, page, vidéo, image, présentation, courriel, envoi, moment,
  schéma) : B ne liste ni ne lit rien de A ; 404.
- **Front pur** : réducteur `PieceNav` (toutes actions × tous niveaux), grammaire locale (exactes, ambiguës, accents),
  sérialisation `## Écran` (plafond), garde `PIECE_VIEWERS`, garde-fous proactifs (≤ 3, 10 min, présentation, sobre).
- **Composant** : galerie 3D et repli 2D (mêmes états), visionneuses, ducking (horloge injectée).
- **Banc IA** : catégories `SHOW` / `NAVIGATE` / `PROOF` du banc existant, jugées comme aujourd'hui.
- **Non-régression** : suites jarvis + stage3d + shared, `npm run build`, suite backend **complète** (règle des
  deux constructeurs), test « préfixe identique ».

---

## 17. Décisions prises par défaut (tranchées, modifiables)

| # | Décision |
|---|---|
| D1 | Index des pièces **sans table** (agrégat à la demande) ; seule table nouvelle : `diagram_renders` (les schémas ne sont conservés nulle part). |
| D2 | Rattachement au chantier : message, sinon fenêtre de temps à chantier unique, sinon sujet ; le lien est dit quand il est déduit. |
| D3 | Cerveau rapide : `pieces` (lecture) + `afficher` (écran), aucun outil d'action (amende F-235 D8). |
| D4 | Opus : ligne `🖥 montrer:` étendue, **pas** d'outil ajouté à la liste d'outils du terminal. |
| D5 | Navigation triviale reconnue **sur l'appareil** (liste fermée, phrase entière) ; le reste au cerveau rapide. |
| D6 | État de navigation : un réducteur pur, une seule source pour toucher, voix locale et outil ; transmis dans le message. |
| D7 | Descriptions : réutilisées seulement ; description d'image absente = délégation Opus sur demande explicite. |
| D8 | ≤ 3 pièces d'initiative par réponse ; ≤ 8 sur demande ; ≤ 12 cartes visibles. |
| D9 | Dépôts sur poste jamais copiés côté serveur pour la galerie (F-228 reconduit) ; hors ligne affiché comme tel. |
| D10 | Vidéo : baisse à 15 % quand Jarvis parle, pause quand le PO parle (modifiable : §18 Q3). |
| D11 | Mode présentation : aucune pièce (modifiable : §18 Q1). |
| D12 | `pdfjs-dist` figé (version ≥ 2 semaines) chargé avec le chunk 3D paresseux ; aucune autre dépendance. |

---

## 18. Questions produit pour le PO (défaut appliqué sans réponse)

| # | Question | Défaut | Alternative |
|---|---|---|---|
| **Q1** | En **mode présentation** (devant un client ou un manager), Jarvis peut-il montrer des pièces ? | **Non**, jamais | Oui, mais seulement la pièce que vous demandez explicitement (« montre-la au client »), jamais d'initiative |
| **Q2** | **Combien** Jarvis montre-t-il de lui-même ? | **Sobre** : seulement dans les 10 situations §7, au plus 3 pièces | **Généreux** : dès qu'une pièce existe pour le chantier dont on parle |
| **Q3** | Le **son d'une vidéo** quand Jarvis parle | **Baissé** à 15 % (on entend encore la vidéo) | **Coupé** puis repris |

---

## 19. Mesure d'intérêt (J+7, J+14 après chaque itération)

Compteurs par jour (clés ajoutées à `RenderCounts.KEYS` / `jarvis_usage_days`, aucun contenu) : galeries ouvertes
(par catégorie), pièces montrées (demandées / d'initiative, par type), ordres de navigation (local / IA / toucher),
vidéos lues et secondes, pages de PDF tournées, diaporamas narrés, propositions de preuve (dites / acceptées /
générées), descriptions demandées à Opus, fermetures < 2 s d'une pièce d'initiative, délai parole → pièce (p50, p90).

| Signal | Lecture |
|---|---|
| Pièces d'initiative fermées < 2 s > 30 % | Jarvis montre à tort ⇒ resserrer la description d'outil (Q2) |
| Navigation locale ≥ 60 % des ordres | la grammaire locale paie (latence) |
| Propositions de preuve acceptées ≥ 50 % | la chaîne §6.4 sert ; sinon la rendre plus discrète |
| Délai parole → pièce p50 ≤ 2,5 s | cible tenue |
| Types jamais ouverts à J+14 | ne plus les enrichir (« proposer depuis l'usage observé ») |
| Coût du cerveau rapide / jour avant ↔ après | doit rester dans le budget 50 €/mois |

---

## 20. Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | lectures nouvelles sous JWT existant ; tickets vidéo / page réutilisés |
| Contexte tenant | **Oui** | `JarvisPieceController` / `JarvisPieceService` (toutes sources §3), `DepositContentService`, `ProofCaptureService`, `PageService`, `VideoService`, `GeneratedImageService`, `PresentationService`, `ClientEmailService`, `MessageBlockSendService`, `TeamsMomentController`, `diagram_renders` : filtre `user_id` + `requireOwned` ; tests A/B par source |
| Plans / limites | **Oui** | `JarvisFastBrainService` (quota, `recordAnnex`, plafond 50 €/mois) inchangé mais sollicité davantage ; délégation de description = quota existant |
| Navigation / routing | Non | aucune route ; état interne à `/jarvis` |
| **Préfixe de cache** | **Oui** | `JarvisFastBrainPrompt` (version 3, une réécriture), `JarvisOralNote` (dans le message) ; test « identique octet pour octet » |

---

## 21. Hors périmètre

- Le **rendu 3D des architectures** (plateaux, icônes flottantes, flux) : feature distincte ; F-237 en fournit le
  type de pièce, la `spec` et les points d'extension (§10).
- Description automatique des images, OCR des captures, indexation sémantique des pièces (Provider-First).
- Copie serveur des dépôts du poste ; conservation des images F-190, de l'audio Jarvis, des PJ de courriel envoyées.
- Réunions Vigie dans la galerie (§14 idée 9) ; partage de pièce à la voix (idée 10).
- Toute modification de la consigne système ou des outils du **tour Opus**.

## 22. Risques

| Risque | Parade |
|---|---|
| **Rattachement faible** (6 dépôts sur 263 liés à un chantier par message) | repli par fenêtre ; lien déduit **dit** ; niveau sujet sinon ; mesuré (part MESSAGE / WINDOW / NONE) |
| **Montrer à tort** (bruit, distraction) | ≤ 3, liste de situations, 10 min, fermeture < 2 s mesurée, Q2 |
| **Description inventée** par le modèle | la description vient du résultat d'outil ; contrôle d'ancrage F-235 D9 appliqué (un nom absent du contexte ⇒ délégation) ; « n'invente jamais ce qu'elle contient » dans la description d'outil |
| **Latence** (`pieces` + `afficher`) | bloc `## Pièces` pour appeler `afficher` directement ; grammaire locale ; cache 60 s |
| **Poste déconnecté** | disponibilité dans l'index ; carte « hors ligne » |
| **Mémoire GPU / batterie** (vidéo en texture) | LRU 3, 30 i/s, DOM natif sur téléphone, arrêt onglet caché (F-236) |
| **Son de la vidéo pris pour la voix du PO** | annulation d'écho, mesure au banc, repli : son coupé pendant l'écoute |
| **Fuite en présentation** | refus par défaut, filtre avant le dessin (SF-236-23) |
| **Secrets dans une capture de preuve** | n'affiche que des captures déjà visibles dans le fil ; vérification du masquage en SF-237-01 |
