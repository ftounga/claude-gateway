# Mini-spec — F-90 / SF-90-01 — ffmpeg au premier usage, et les images aux changements de plan

## Identifiant

`F-90 / SF-90-01`

## Feature parente

`F-90` — Le volet Teams : les captures alignées

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-90-01-ffmpeg-et-changements-de-plan`

---

## Objectif

Donner au poste de quoi **tirer d'un enregistrement de réunion les seules images qui portent quelque
chose** — extraction aux **changements de plan** puis **dédoublonnage** — avec un `ffmpeg`
**téléchargé au premier usage, jamais embarqué** (D3).

---

## Pourquoi le tri est la fonctionnalité, pas son confort

Une heure de réunion à intervalle fixe (1 image/s) donne **3 600 images**. Aux changements de plan
puis dédoublonnées, elle en donne **20 à 60**.

**Chaque image analysée se paie.** Ce n'est donc pas une optimisation de confort : c'est ce qui rend
la fonctionnalité **finançable**. Une version « une image toutes les dix secondes » ne serait pas une
version dégradée de F-90 — elle serait une version qu'on ne pourrait pas vendre.

Et le seuil dit une chose simple : **un curseur qui bouge n'est pas un nouveau plan.** Le filtre de
scène d'`ffmpeg` compare deux trames successives ; un seuil trop bas retient le clignotement du
curseur et le défilement du texte, un seuil trop haut rate le passage d'une diapositive à la
suivante.

---

## Comportement attendu

### Cas nominal

Entrée : un **chemin de fichier vidéo sur la machine**, résolu par le `PathResolver` existant
(relatif au projet, absolu, `~` étendu — comme tout chemin du runner depuis F-73).

1. **L'outillage.** `ffmpeg` est cherché dans cet ordre : le `PATH` du poste, puis la copie déjà
   téléchargée sous `.claude-runner/tools/`, puis — et seulement alors — **téléchargé**. Le
   téléchargement **se voit et se dit** : une phrase avant (ce qui va être pris, d'où, pour quoi
   faire) et une phrase après (où il a été posé, combien il pèse).
2. **L'extraction.** Un seul passage `ffmpeg`, filtre `select='gt(scene,SEUIL)'` + `showinfo`,
   sortie JPEG dans un dossier de travail. Le journal `showinfo` donne le `pts_time` **exact** de
   chaque image retenue : c'est lui, et pas le numéro de fichier, qui porte l'horodatage.
3. **Le dédoublonnage.** Une empreinte perceptuelle (dHash 8×8, 64 bits) par image ; deux images
   dont la distance de Hamming est sous le seuil sont **la même image**, on garde la première.
   C'est ce qui retire les faux positifs du filtre de scène (une transition animée produit trois
   trames quasi identiques).
4. **Le plafond.** Au-delà de **60** images retenues, on garde les **60 les plus espacées dans le
   temps** — et on **dit** combien ont été écartées. Jamais un plafond silencieux.
5. **Le rendu.** La liste des images retenues (fichier local + décalage en secondes depuis le début
   de la vidéo + empreinte), et, **à côté**, ce qui n'a pas pu être fait.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| `ffmpeg` introuvable **et** téléchargement impossible (poste hors ligne, proxy) | **Refus nommé**, avec le remède exact : installer `ffmpeg`, et la commande du gestionnaire de paquets du système observé |
| Téléchargement interrompu / archive illisible | Refus nommé ; la copie partielle est effacée — jamais un binaire à moitié écrit laissé en place |
| Fichier vidéo absent | Refus nommé `not_found`, avec le chemin tel qu'il a été résolu |
| Fichier présent mais qu'`ffmpeg` n'ouvre pas | Refus nommé portant **les dernières lignes de `ffmpeg`**, pas un « échec » nu |
| `ffmpeg` sort en erreur au milieu | Les images déjà écrites sont **jetées** : une extraction partielle donnerait un compte rendu plausible et faux |
| Zéro changement de plan détecté | **Zéro image ET un manque nommé** — « la vidéo n'a pas changé de plan au seuil demandé » ; jamais une liste vide muette |
| Une image extraite illisible (JPEG tronqué) | Elle est écartée, **comptée** et nommée dans le rapport |

---

## Critères d'acceptation

- [ ] `ffmpeg` est cherché dans le `PATH` **avant** tout téléchargement.
- [ ] Aucun binaire `ffmpeg` n'est embarqué dans le paquet runner.
- [ ] Le téléchargement est **annoncé avant** et **confirmé après**, sur la console du runner.
- [ ] Un téléchargement interrompu ne laisse **aucune** copie partielle en place.
- [ ] L'horodatage de chaque image vient du `pts_time` de `showinfo`, jamais du numéro de fichier.
- [ ] Deux images perceptuellement identiques ne donnent qu'**une** image retenue.
- [ ] Le plafond de 60 dit **combien** ont été écartées, et garde les plus espacées.
- [ ] Zéro changement de plan rend **zéro image et un manque nommé**, jamais une liste vide muette.
- [ ] Une sortie `ffmpeg` en échec **jette** les images déjà écrites.
- [ ] Le rapport porte toujours ce qui n'a pas pu être fait, même quand tout s'est bien passé
      (liste vide explicite).

---

## Tables / endpoints / composants impactés

| Composant | Nature |
|---|---|
| `runner/…/teams/LocalToolchain.java` | **nouveau** — l'outillage téléchargé au premier usage (D3), **partagé avec F-91** |
| `runner/…/teams/LocalTool.java` | **nouveau** — la description d'un outil : noms, adresses, vérification |
| `runner/…/teams/ProcessRunner.java` | **nouveau** — le port d'exécution d'un processus, pour que tout s'éprouve sans `ffmpeg` |
| `runner/…/teams/SceneFrames.java` | **nouveau** — l'extraction aux changements de plan et la lecture de `showinfo` |
| `runner/…/teams/FrameFingerprint.java` | **nouveau** — dHash 64 bits et distance de Hamming |
| `runner/…/teams/FrameSelection.java` | **nouveau** — dédoublonnage, plafond, et le compte de ce qui est écarté |
| `runner/…/teams/SceneFrame.java` | **nouveau** — une image retenue : décalage, fichier, empreinte |
| `runner/…/teams/FramesHarvest.java` | **nouveau** — les images **et** ce qui n'a pas pu être fait |
| `runner/…/teams/TeamsWorkFolder.java` | **nouveau** — le dossier de travail du volet, sous `.claude-runner/teams/` |

**Aucune table, aucun endpoint, aucun écran.** Tout est local à la machine.

---

## Ce qui est hors périmètre

- **L'alignement** sur la transcription → SF-90-02.
- **Le travail long** (worker, progression, reprise) et la **remontée** des images → SF-90-03.
- **L'acquisition de la vidéo** : F-90 part d'un fichier **déjà sur la machine**. Le téléchargement
  depuis Teams reste refusé par construction (A5 de SF-88-02) ; la capture locale est F-91.
- **Le filigrane** (`ffmpeg` le pose aussi) → F-91.
- La **transcription audio** → F-91.

---

## Plan de test

**Unitaires (runner)**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 1 | `LocalToolchainTest` — `PATH` d'abord | aucun téléchargement quand l'outil est déjà là |
| 2 | `LocalToolchainTest` — copie locale ensuite | on ne retélécharge pas à chaque appel |
| 3 | `LocalToolchainTest` — le téléchargement est dit | deux phrases sur la console, avant et après |
| 4 | `LocalToolchainTest` — téléchargement rompu | refus nommé **et** aucune copie partielle |
| 5 | `LocalToolchainTest` — hors ligne | refus nommé portant le remède du système observé |
| 6 | `SceneFramesTest` — lecture de `showinfo` | `pts_time` → décalage exact, sur un journal fabriqué |
| 7 | `SceneFramesTest` — `ffmpeg` en échec | images jetées, dernières lignes rendues |
| 8 | `SceneFramesTest` — zéro plan | zéro image **et** manque nommé |
| 9 | `SceneFramesTest` — vidéo absente | refus `not_found` |
| 10 | `FrameFingerprintTest` — identiques / différentes | distance 0 vs distance élevée |
| 11 | `FrameSelectionTest` — dédoublonnage | trois quasi-identiques → une |
| 12 | `FrameSelectionTest` — plafond | 200 → 60, les plus espacées, et le compte écarté |
| 13 | `FrameSelectionTest` — image illisible | écartée, comptée, nommée |
| 14 | `TeamsWorkFolderTest` — repli | dossier du poste, sinon `~` |

**Isolation utilisateur** — sans objet : rien ne quitte la machine dans cette subfeature, et le
runner d'un poste ne connaît qu'un compte. La garde d'isolation de la remontée est en SF-90-03.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | **Non** | aucun endpoint |
| Contexte tenant | **Non** | rien ne remonte ici |
| Plans / limites | **Non** | le droit Teams est posé par F-89 au niveau du catalogue |
| Navigation / routing | **Non** | aucun écran |
| **Liste blanche CDP (sécurité)** | **Non, et elle ne bouge pas** | `CdpCommands` n'est pas touché : `ffmpeg` ne parle pas au navigateur |
| **Exécution d'un binaire tiers** | **Oui** | nouveau : le runner lance un processus qu'il a téléchargé. Traité ci-dessous |

### L'exécution d'un binaire tiers, traitée explicitement

Le runner exécute déjà des commandes arbitraires (`bash`, F-38). Ce qui est **nouveau** ici, c'est
qu'il exécute un binaire qu'il a **lui-même rapatrié**. Trois garde-fous :

1. **L'adresse de téléchargement est en dur dans le code**, jamais reçue d'un appel d'outil — un
   paramètre d'outil ne peut pas faire télécharger n'importe quoi.
2. **La destination est fixe** (`.claude-runner/tools/`), jamais un chemin venu d'un appel.
3. **Le téléchargement se voit** : il est annoncé sur la console du poste, avant et après.

---

## Notes et décisions

**A1 — L'outillage est une brique générique, pas un détail d'`ffmpeg`.** F-91 aura besoin du
**même mécanisme** pour son modèle de transcription (D3 les nomme ensemble : navigateur, `ffmpeg`,
modèle). `LocalToolchain` ne connaît donc pas `ffmpeg` : elle connaît « un outil qu'on résout, qu'on
télécharge une fois, et dont on dit le téléchargement ». `LocalTool.ffmpeg()` est sa première
description. **Alternative écartée** : un téléchargeur sur mesure pour `ffmpeg` — il aurait fallu
l'écrire deux fois, et les deux auraient divergé sur ce qui compte (ce qu'on dit à l'utilisateur).
Réversible.

**A2 — Le PO demande la solution la plus complète : on prend le dédoublonnage perceptuel, pas la
seule détection de scène.** Le filtre de scène d'`ffmpeg` suffirait à « livrer quelque chose ». Mais
il produit des grappes : une transition animée donne trois trames au-dessus du seuil. Sans
dédoublonnage, une heure de réunion rend 150 images au lieu de 40 — et **chaque image se paie**. Le
dHash est vingt lignes et aucune dépendance (`ImageIO` est dans le JDK). **Alternative écartée** :
monter le seuil de scène jusqu'à ne plus avoir de grappes — cela rate les vraies diapositives.
Réversible (les deux seuils sont des constantes nommées).

**A3 — Une extraction partielle est jetée.** Quand `ffmpeg` s'arrête au milieu, on **pourrait**
garder les images déjà écrites. On ne le fait pas : un compte rendu couvrant la première moitié
d'une réunion sans le dire est exactement le « plausible et faux » que ce volet interdit. Le refus
nomme ce qui s'est passé et rend les dernières lignes d'`ffmpeg`. Réversible.

**A4 — Le plafond garde les images les plus espacées, pas les premières.** Tronquer à la 60ᵉ image
rendrait un compte rendu qui s'arrête au tiers de la réunion **en ayant l'air complet**. Espacer
couvre toute la durée. Et le nombre écarté est **dit**. Réversible.

**Limite, écrite et non maquillée** : nous n'avons **aucun compte Teams de test**, et le CI n'a pas
`ffmpeg`. Ce qui est prouvé ici est la **résolution de l'outillage**, la **lecture du journal
`showinfo`**, le **dédoublonnage**, le **plafond** et **tous les refus** — sur un port de processus
injecté et des journaux fabriqués (provenance : écrits à la main d'après le format documenté du
filtre `showinfo` d'`ffmpeg`). Ce qui n'est **pas** prouvé : qu'un `ffmpeg` réel, sur une vraie
vidéo Teams, produise ce journal et ces images. La **sonde de santé (F-87 / SF-87-03)** reste
responsable de la confrontation au réel le jour du premier branchement.
