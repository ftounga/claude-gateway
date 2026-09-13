# Mini-spec — F-91 / SF-91-01 — Le filigrane, et les deux gestes

## Identifiant

`F-91 / SF-91-01`

## Feature parente

`F-91` — Le volet Teams : l'enregistrement local

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-91-01-filigrane-et-deux-gestes`

---

## Objectif

Donner au poste de quoi **démarrer et arrêter une capture d'écran locale** — avec, **incrusté dans
l'image par `ffmpeg` à la volée**, un filigrane qui empêche l'enregistrement d'être anonyme, et
**deux gestes distincts** selon qu'on capture son propre écran ou une réunion à plusieurs.

---

## Pourquoi ce morceau n'est pas de la même nature que les autres

Tout le reste du volet Teams **relit ce qui existait déjà** : des messages que Teams a servis, une
transcription que Teams a produite, un enregistrement que Teams a fait. **Celui-ci crée.** Et les
participants ne le sauront pas, là où Teams affiche un bandeau quand c'est lui qui enregistre.

**Article 226-1 du code pénal**, politiques internes des clients, et — c'est le point qui décide de
la conception — **la personne exposée est le consultant**, pas la gateway. **Le PO a maintenu la
demande après exposition du risque.** Le code doit donc *dire* cette différence de nature, pas la
noyer dans le reste du catalogue.

---

## Comportement attendu

### Cas nominal — capturer son propre écran (aucune friction)

Entrée : `purpose = "self"`. Une démo, un débogage : **cela ne concerne personne d'autre**.

1. `ffmpeg` est résolu par le chemin de D3 déjà en place (`LocalToolchain`) : `PATH`, puis copie
   rapatriée, puis téléchargement — annoncé avant, confirmé après.
2. **Le filigrane est préparé** : une ligne de texte qui nomme **qui** enregistre, **depuis quand**,
   et **avec quoi**. Elle est incrustée dans chaque trame par le filtre `drawtext`.
3. La commande `ffmpeg` est assemblée **argument par argument** (jamais une ligne de shell), avec les
   entrées écran et son du système observé.
4. Le processus est lancé ; la capture écrit dans le **dossier de travail du volet**, jamais dans un
   chemin venu d'un appel d'outil.
5. L'arrêt ferme proprement le conteneur (`q` sur l'entrée standard, puis interruption si besoin) et
   rend le chemin du fichier, sa durée et sa taille.

### Cas nominal — enregistrer une réunion à plusieurs (confirmation explicite)

Entrée : `purpose = "meeting"` **et** `participants_informed = true`.

Sans le second, **le démarrage est refusé** : la case n'est jamais pré-cochée, et le refus **nomme ce
que le produit ne peut pas garantir** — que les participants soient informés. Cela ne peut venir que
de l'utilisateur, **de vive voix**. La confirmation sert à le lui rappeler, **pas à le protéger**.

Le filigrane porte alors une mention supplémentaire : la capture est une capture de **réunion**.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| `purpose = "meeting"` sans `participants_informed` | **Refus** nommé, portant la phrase de rappel et ce que le produit ne garantit pas |
| `purpose` absent ou inconnu | **Refus** : le geste n'est pas deviné — deviner reviendrait à donner la friction la plus faible |
| `ffmpeg` introuvable et non rapatriable | Refus nommé, **avec le remède** (chemin D3 existant) |
| **Aucune police utilisable pour le filigrane** | **Refus de capturer.** Un enregistrement sans filigrane serait anonyme : c'est précisément ce que le garde-fou interdit |
| `ffmpeg` sans filtre `drawtext` | **Refus de capturer**, avec le remède (installer une build complète) |
| Système sans mode de capture connu (`OTHER`) | Refus nommé : on ne devine pas un périphérique de capture |
| `ffmpeg` meurt dans la seconde (périphérique refusé, X non accessible, permission macOS) | Refus portant **les dernières lignes de `ffmpeg`**, jamais un « échec » nu |
| Une capture est déjà en cours | Refus nommé rendant **celle qui tourne** : deux captures simultanées écriraient deux fichiers dont l'un serait oublié |
| Arrêt d'une capture inconnue | Refus nommé, sans rien inventer |
| Fichier produit vide ou absent à l'arrêt | **Échec nommé** : on ne rend pas un chemin vers un fichier qui ne porte rien |

---

## Critères d'acceptation

- [ ] Une capture **ne démarre jamais** sans filigrane : ni police, ni `drawtext` ⇒ refus.
- [ ] Le filigrane est **dans le filtre passé à `ffmpeg`**, donc **incrusté dans l'image** — pas un
      fichier de métadonnées à côté, qu'une réencodage effacerait.
- [ ] Le filigrane nomme **qui**, **quand**, et **que c'est un enregistrement local**.
- [ ] `purpose = "meeting"` sans `participants_informed = true` ⇒ **refus**, et le message dit ce que
      le produit **ne peut pas** garantir.
- [ ] `purpose = "self"` démarre **sans aucune friction** supplémentaire.
- [ ] La confirmation n'est **jamais** déduite d'un défaut : absente ⇒ refus.
- [ ] La destination du fichier est **fixe** (dossier de travail du volet), jamais un paramètre
      d'appel.
- [ ] La commande est construite **argument par argument**, jamais concaténée dans un shell.
- [ ] Deux captures simultanées sont refusées, en rendant celle qui tourne.
- [ ] L'état d'une capture **survit au redémarrage du runner** (écrit sur le disque, relu).
- [ ] Un fichier vide à l'arrêt est un **échec nommé**, pas un succès.

---

## Tables / endpoints / composants impactés

| Composant | Nature |
|---|---|
| `runner/…/teams/CapturePurpose.java` | **nouveau** — les deux usages, et lequel demande une confirmation |
| `runner/…/teams/CaptureConsent.java` | **nouveau** — le second geste, et son refus quand il manque |
| `runner/…/teams/CaptureRefusedException.java` | **nouveau** — un refus qui porte son remède |
| `runner/…/teams/Watermark.java` | **nouveau** — la ligne incrustée, et le filtre `drawtext` |
| `runner/…/teams/WatermarkFont.java` | **nouveau** — la police du poste ; sans elle, on ne capture pas |
| `runner/…/teams/ScreenCaptureCommand.java` | **nouveau** — la ligne `ffmpeg` par système |
| `runner/…/teams/ProcessSession.java` | **nouveau** — le port d'un processus **qui dure** (F-90 n'avait que des processus qui finissent) |
| `runner/…/teams/CaptureRecord.java` | **nouveau** — l'état d'une capture, écrit et relu |
| `runner/…/teams/CaptureStore.java` | **nouveau** — la persistance de cet état |
| `runner/…/teams/LocalCapture.java` | **nouveau** — le moteur : refuser, lancer, arrêter |
| `runner/…/teams/TeamsWorkFolder.java` | **modifié** — un dossier `captures/` |
| `runner/…/teams/LocalTool.java` | **modifié** — `ffmpeg` sert aussi à capturer : la phrase de D3 le dit |

**Aucune table, aucun endpoint, aucun écran.** Tout est local à la machine.

---

## Ce qui est hors périmètre

- **Le témoin au premier plan** (fenêtre toujours visible, durée, arrêt d'un clic) → SF-91-02.
- **Les outils donnés à l'agent** (`teams_capture_start` / `_stop` / `_status`) → SF-91-02.
- **La transcription sur la machine** et la mention en tête du compte rendu → SF-91-03.
- **Capturer à l'insu de l'utilisateur du poste** : hors périmètre de la feature, par écrit.
- **Toute capture déclenchée autrement que par un geste explicite** : hors périmètre.
- **Afficher quoi que ce soit dans la réunion des autres** : impossible — seul Teams le peut.

---

## Plan de test

**Unitaires (runner)**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 1 | `CaptureConsentTest` — réunion sans confirmation | refus, et le message dit ce qui n'est pas garanti |
| 2 | `CaptureConsentTest` — réunion avec confirmation | accepté |
| 3 | `CaptureConsentTest` — écran propre | accepté **sans** confirmation |
| 4 | `CaptureConsentTest` — usage absent / inconnu | refus : le geste n'est pas deviné |
| 5 | `WatermarkTest` — la ligne nomme qui, quand, quoi | l'enregistrement n'est jamais anonyme |
| 6 | `WatermarkTest` — échappement `drawtext` | un `:` ou une `'` dans le nom ne casse pas le filtre |
| 7 | `WatermarkTest` — mention de réunion | le filigrane distingue les deux usages |
| 8 | `WatermarkFontTest` — aucune police | rend vide, ce qui **fera refuser** |
| 9 | `ScreenCaptureCommandTest` — Linux / Windows / macOS | entrées attendues, **et le filtre de filigrane présent** |
| 10 | `ScreenCaptureCommandTest` — sans shell | chaque argument est un élément, aucun `;` interprété |
| 11 | `ScreenCaptureCommandTest` — système inconnu | refus nommé |
| 12 | `LocalCaptureTest` — sans police | **refus de capturer**, aucun processus lancé |
| 13 | `LocalCaptureTest` — sans `drawtext` | refus, aucun processus lancé |
| 14 | `LocalCaptureTest` — réunion sans confirmation | refus, aucun processus lancé |
| 15 | `LocalCaptureTest` — nominal | processus lancé, état écrit, chemin dans le dossier du volet |
| 16 | `LocalCaptureTest` — mort immédiate de `ffmpeg` | refus portant les dernières lignes |
| 17 | `LocalCaptureTest` — seconde capture | refus rendant celle qui tourne |
| 18 | `LocalCaptureTest` — arrêt | processus arrêté, durée et taille rendues |
| 19 | `LocalCaptureTest` — fichier vide à l'arrêt | échec nommé |
| 20 | `CaptureStoreTest` — écriture / relecture | l'état survit au redémarrage |

**Isolation utilisateur** — sans objet ici : rien ne quitte la machine dans cette subfeature, et le
runner d'un poste ne connaît qu'un compte. La garde d'isolation de ce qui remonte est déjà celle de
F-90 / SF-90-03, réutilisée telle quelle en SF-91-03.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | **Non** | aucun endpoint dans cette subfeature |
| Contexte tenant | **Non** | rien ne remonte ici |
| Plans / limites | **Non** | le droit Teams est posé par F-89 au niveau du catalogue d'outils |
| Navigation / routing | **Non** | aucun écran |
| Liste blanche CDP (sécurité) | **Non, et elle ne bouge pas** | `CdpCommands` n'est pas touché : capturer ne parle pas au navigateur |
| **Exécution d'un binaire tiers** | **Oui** | `ffmpeg` est lancé, désormais **en processus long**. Traité ci-dessous |
| **Création d'un artefact sur le poste** | **Oui, et c'est nouveau** | premier morceau du volet qui **écrit** un fichier lourd. Traité ci-dessous |

### L'exécution d'un binaire tiers — inchangée, et vérifiée

Les trois garde-fous de SF-90-01 tiennent tels quels : adresse de téléchargement **en dur** dans
`LocalTool`, destination **fixe** (`.claude-runner/teams/tools/`), téléchargement **annoncé**. Ce qui
est neuf, c'est la **durée** du processus : `ProcessRunner` attend une fin, une capture n'en a pas.
D'où `ProcessSession`, un second port — qui ne change rien aux garde-fous, et permet de tout
éprouver sans `ffmpeg`.

### La création d'un artefact — la trace voyage avec lui

Le fichier produit est **le plus indiscret de tous** : tout ce qui est passé à l'écran, y compris ce
qu'on n'avait pas l'intention de montrer. D'où la règle qui structure cette subfeature :
**pas de filigrane, pas de capture.** Et le filigrane est **incrusté dans l'image**, pas posé à côté
— un champ de métadonnées se perd au premier réencodage, la trace doit **voyager avec l'artefact**.

---

## Notes et décisions

**A1 — Sans filigrane, on ne capture pas. Refus, jamais dégradation.** On *pourrait* capturer sans
filigrane quand la police manque, et le dire. On ne le fait pas : le garde-fou n° 1 du cadrage est
qu'un enregistrement **ne soit jamais anonyme**, et une capture sans filigrane est exactement un
enregistrement anonyme. Un garde-fou qui s'efface quand il gêne n'est pas un garde-fou. Le refus
porte son remède (installer une police, ou une build `ffmpeg` avec `drawtext`).
**Alternative écartée** : incruster un bandeau dessiné sans police (`drawbox` + image) — cela dirait
« quelque chose a été enregistré » sans dire **par qui**, ce qui rate l'objectif.
**Réversible** (le refus est à un seul endroit).

**A2 — La confirmation est un paramètre d'appel, jamais un défaut.** `participants_informed` n'a pas
de valeur par défaut : absent vaut **non**, et le démarrage est refusé. C'est la transposition
littérale de « case **jamais** pré-cochée ». Et le refus écrit noir sur blanc ce que le produit ne
peut pas garantir : *la confirmation sert à vous le rappeler, pas à vous protéger*.
**Alternative écartée** : une confirmation valable pour la session — elle deviendrait un réflexe, et
« si les deux avaient la même friction, elle ne protégerait plus rien » vaut aussi dans le temps.
**Réversible.**

**A3 — Deux usages, deux gestes, et l'usage n'est jamais deviné.** Un `purpose` absent est un refus,
pas un repli sur `self`. Se replier sur l'usage le moins friction reviendrait à supprimer la friction
pour qui oublie de la nommer — c'est-à-dire à la supprimer. **Réversible.**

**A4 — Le son est capturé par défaut, y compris pour une capture d'écran propre.** Le PO demande la
solution la plus complète, et toute la suite en dépend : **une capture locale n'a pas de
transcription**, il faudra la produire à partir de l'audio (SF-91-03). Une capture muette serait une
capture dont on ne pourrait rien tirer. `audio = false` reste possible et **se dit** dans l'état de
la capture. **Réversible.**

**A5 — Une seule capture à la fois.** Deux captures simultanées écriraient deux fichiers lourds dont
l'un serait oublié — exactement la « capture oubliée » que le garde-fou n° 3 cherche à éviter. La
seconde demande est refusée **en rendant la première** (son identifiant, depuis quand elle tourne),
pour que l'utilisateur puisse l'arrêter. **Réversible.**

**A6 — L'état est écrit sur le disque, comme un travail de moments.** Un runner redémarré pendant une
capture doit pouvoir **dire qu'une capture a été lancée**, même s'il ne peut plus l'arrêter
proprement. Un état seulement en mémoire produirait un fichier orphelin dont plus rien ne parlerait.
**Réversible.**

**Limite, écrite et non maquillée** : nous n'avons **aucun compte Teams de test**, et le CI n'a ni
`ffmpeg`, ni écran, ni périphérique audio. Ce qui est prouvé ici est ce que le produit **décide** —
refuser sans filigrane, refuser sans confirmation, assembler une ligne de commande, écrire et relire
un état, refuser une seconde capture, refuser un fichier vide — sur un **port de processus injecté**.
Ce qui n'est **pas** prouvé : qu'un `ffmpeg` réel capture effectivement l'écran et le son de chaque
système avec ces arguments. Les entrées par système (`x11grab`/`pulse`, `gdigrab`/`dshow`,
`avfoundation`) sont **fabriquées d'après la documentation d'`ffmpeg`**, et non observées sur un
poste de test. La **sonde de santé (F-87 / SF-87-03)** reste responsable de la confrontation au réel
le jour du premier branchement.
