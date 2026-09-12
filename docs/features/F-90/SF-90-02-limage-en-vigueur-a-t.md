# Mini-spec — F-90 / SF-90-02 — L'image EN VIGUEUR à t

## Identifiant

`F-90 / SF-90-02`

## Feature parente

`F-90` — Le volet Teams : les captures alignées

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-90-02-limage-en-vigueur-a-t`

---

## Objectif

Rapprocher chaque phrase prononcée de **l'image qui était à l'écran pendant qu'elle se disait** —
celle **en vigueur** à cet instant, jamais la suivante — et nommer tout ce qui n'a pas pu l'être.

---

## C'est l'alignement qui fait la valeur, pas l'extraction

SF-90-01 sait sortir les bonnes images. Elles ne valent rien seules : une galerie d'images en bas
d'un compte rendu, personne ne la regarde. Ce qu'on vient chercher, c'est
*« à 14 h 32, il présente le planning de migration **[capture]** et annonce le décalage au T3 »* —
et **personne ne le fait à la main parce que c'est fastidieux**.

Ce qui le rend possible : la transcription est horodatée (F-87 / `TeamsTranscriptCue`), les images
aussi (SF-90-01 / `pts_time`). Il ne manque que la règle qui les rapproche. Elle tient en une
phrase, et elle est **la** subtilité de la feature :

> **Une phrase prononcée à `t` va avec l'image EN VIGUEUR à `t`** — la dernière affichée avant `t` —
> **et surtout pas la suivante.**

Prendre la suivante inverserait la causalité : on collerait à « je vous montre le planning » l'image
de ce qui a été affiché **après** — c'est-à-dire on ferait dire à un compte rendu le contraire de ce
qui s'est passé. **Une image mal alignée est pire qu'une image absente.**

---

## L'origine du temps, et le refus qui va avec

Les images portent un **décalage en secondes depuis le début de la vidéo**. La transcription porte
des **instants absolus**. Pour les rapprocher, il faut savoir **à quel instant commence la vidéo**.

**Sans cette origine, tout l'alignement est faux — et faux silencieusement.** On refuse donc, en
nommant le manque et en disant comment le lever (`video_started_at`, ou une réunion dont le registre
connaît le début). **On ne devine pas.** C'est l'application directe de « échouer bruyamment, jamais
à moitié faux ».

Trois façons de connaître l'origine, dans cet ordre :

1. `video_started_at` donné explicitement dans la demande ;
2. le **début de la réunion** observé au registre (`TeamsMeeting.startedAt`) ;
3. **rien** → refus nommé.

Et parce qu'un enregistrement Teams ne commence pas toujours à la même seconde que la réunion, un
**décalage** (`offset_seconds`) est acceptable et **dit dans le résultat** : le compte rendu porte
toujours l'hypothèse sur laquelle il a été bâti.

---

## Comportement attendu

### Cas nominal

Entrée : les images retenues (SF-90-01), les répliques de la transcription (F-88), l'origine du
temps.

Pour **chaque image retenue**, dans l'ordre :

1. son **règne** va de son instant à celui de l'image suivante (la dernière règne jusqu'à la fin de
   la transcription) ;
2. on prend la **première réplique lisible** dont le début tombe dans ce règne : c'est la phrase du
   moment ;
3. on compte les autres répliques du règne — elles sont **dites** (« 4 autres répliques pendant que
   cette image était affichée »), jamais concaténées : *une phrase tronquée peut dire le contraire de
   la phrase* ;
4. le moment porte : l'**instant absolu** de la phrase, son **décalage** dans la vidéo, la
   **citation**, le **locuteur**, et le **fichier local** de l'image.

Sortie : les moments dans l'ordre du temps, **et** le rapport de ce qui n'a pas pu être aligné.

### Les cas limites, tous traités

| Situation | Comportement |
|---|---|
| Une réplique prononcée **avant la première image** | Elle n'est **rattachée à rien**. Elle est **comptée et nommée** — la rattacher à la première image serait exactement l'erreur qu'on refuse, dans l'autre sens |
| Une image **sans aucune parole** pendant son règne | Elle **ne devient pas un moment** (un moment sans citation n'est pas un moment, et le bloc de F-89 le refuserait), et elle est **comptée** : « 12 images sans parole pendant leur affichage » |
| Une réplique exactement **à l'instant** d'une image | Elle appartient à **cette** image : l'image est déjà affichée quand la phrase commence |
| Transcription **vide** | **Zéro moment et un manque nommé** — jamais une galerie muette |
| Images **vides** | **Zéro moment et un manque nommé** |
| **Origine du temps inconnue** | **Refus nommé**, avec les deux façons de la donner |
| Une réplique **sans horodatage** ou **sans texte** | Écartée, comptée, nommée |
| Une réplique **après le dernier changement de plan** | Rattachée à la **dernière image** : c'est ce qui était encore affiché. La dernière image règne **jusqu'à la fin de la transcription** — on ne prétend pas connaître la durée de la vidéo, donc on ne l'invente pas |

---

## Critères d'acceptation

- [ ] Une phrase prononcée à `t` est rapprochée de l'image **en vigueur** à `t`, **jamais** de la
      suivante.
- [ ] Une phrase prononcée **exactement** à l'instant d'une image va avec **cette** image.
- [ ] Une phrase prononcée **avant** la première image n'est rattachée à **aucune** image, et le
      manque est nommé.
- [ ] Une image sans parole pendant son règne **ne devient pas un moment**, et elle est comptée.
- [ ] Sans origine du temps, l'alignement **refuse** en nommant les deux façons de la donner.
- [ ] Le décalage entre le début de la réunion et le début de la vidéo est **dit** dans le résultat.
- [ ] Chaque moment porte instant absolu, décalage vidéo, citation, locuteur et fichier d'image.
- [ ] Les répliques supplémentaires d'un règne sont **comptées et dites**, jamais concaténées.
- [ ] Une transcription vide rend **zéro moment et un manque nommé**.
- [ ] Le rapport est **toujours** présent, même quand tout s'est bien passé.
- [ ] Le nombre de moments ne dépasse jamais la borne du bloc moment de F-89 (60).

---

## Tables / endpoints / composants impactés

| Composant | Nature |
|---|---|
| `runner/…/teams/TeamsMoment.java` | **nouveau** — un moment : instant, décalage, citation, locuteur, image locale |
| `runner/…/teams/MomentTimeline.java` | **nouveau** — l'origine du temps et la conversion décalage ↔ instant |
| `runner/…/teams/MomentAlignment.java` | **nouveau** — **la règle** : l'image en vigueur à `t` |
| `runner/…/teams/MomentsAlignment.java` (résultat) | **nouveau** — les moments **et** ce qui n'a pas pu être aligné |
| `runner/…/teams/TeamsGapKind.java` | **modifié** — deux valeurs : parole avant la première image, image sans parole |

**Aucune table, aucun endpoint, aucun écran.** Tout est local à la machine.

---

## Ce qui est hors périmètre

- **L'extraction** des images → SF-90-01 (livrée).
- **Le travail long** (worker, progression, reprise), la **remontée** des images et la
  **déclaration d'outil** → SF-90-03.
- **L'affichage** du bloc moment → F-89 / SF-89-02 et SF-89-03 (livrées).
- **La transcription d'un audio** (une capture locale n'a pas de transcription Teams) → F-91.

---

## Plan de test

**Unitaires (runner, `MomentAlignmentTest`, `MomentTimelineTest`)**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 1 | La phrase va avec l'image **en vigueur**, pas la suivante | **la règle de la feature** |
| 2 | Une phrase **à l'instant exact** d'une image va avec cette image | la borne, du bon côté |
| 3 | Une phrase **avant la première image** n'est rattachée à rien, et c'est nommé | l'erreur symétrique |
| 4 | Une image **sans parole** ne devient pas un moment, et elle est comptée | pas de moment sans citation |
| 5 | Les répliques **supplémentaires** d'un règne sont comptées, jamais concaténées | une phrase n'est pas tronquée |
| 6 | Ce qui est dit **après le dernier changement de plan** va à la dernière image | son règne va jusqu'à la fin |
| 7 | Transcription **vide** → zéro moment + manque nommé | jamais une galerie muette |
| 8 | Images **vides** → zéro moment + manque nommé | |
| 9 | **Origine inconnue** → refus nommé portant les deux remèdes | « échouer bruyamment » |
| 10 | Origine = début de réunion, **décalage dit** | le compte rendu porte son hypothèse |
| 11 | Une réplique **sans horodatage / sans texte** est écartée et comptée | |
| 12 | Le nombre de moments **ne dépasse jamais 60** | le contrat du bloc de F-89 |
| 13 | `MomentTimeline` : décalage ↔ instant, aller et retour | |

**Isolation utilisateur** — sans objet : rien ne quitte la machine dans cette subfeature. La garde
d'isolation de la remontée est en SF-90-03.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | **Non** | aucun endpoint |
| Contexte tenant | **Non** | rien ne remonte ici |
| Plans / limites | **Non** | le droit Teams est posé par F-89 au niveau du catalogue |
| Navigation / routing | **Non** | aucun écran |
| **Liste blanche CDP (sécurité)** | **Non, et elle ne bouge pas** | `CdpCommands` n'est pas touché |
| **Contrat du bloc moment (F-89)** | **Oui** | `TeamsBlockCards.readMoments` exige `at` **et** `quote`, et refuse au-delà de 60 moments. L'alignement produit donc des moments qui passent cette validation **par construction** — un moment sans citation n'est jamais émis. Vérifié par le test 4 et le test 12 |

---

## Notes et décisions

**A5 — L'image en vigueur, et le refus de deviner l'origine.** C'est la décision centrale. La
variante « l'image la plus proche dans le temps » aurait été plus indulgente et **fausse une fois sur
deux** : elle collerait à une phrase l'image qui lui a succédé. **Alternative écartée** : prendre la
plus proche, en avant comme en arrière. Le coût de l'erreur n'est pas symétrique — une image
postérieure fait dire au compte rendu le contraire de ce qui s'est passé. Réversible (la règle tient
dans une méthode).

**A6 — Un moment par IMAGE, pas un moment par réplique.** Une réunion d'une heure porte des centaines
de répliques et 20 à 60 images. Un moment par réplique donnerait des centaines de moments — et le
bloc de F-89 en refuse plus de 60. Surtout, ce serait le mauvais objet : *« montre-moi ce qu'il y
avait à l'écran »* est une question sur les **écrans**, pas sur les phrases. **Alternative
écartée** : un moment par réplique, plafonné — cela aurait rendu 60 moments tous tirés des dix
premières minutes. Réversible.

**A7 — Les répliques supplémentaires sont comptées, jamais concaténées.** On aurait pu coller les
quatre phrases d'un règne en une citation. Deux raisons de ne pas le faire : la borne de 500
caractères du bloc ferait **tronquer**, et *une phrase tronquée peut dire le contraire de la phrase*
(c'est écrit dans `TeamsBlockCards`) ; et une citation recomposée n'est plus citable — on ne peut
plus l'ouvrir à la seconde. Le nombre est **dit**, pour que le lecteur sache qu'il y avait autre
chose. Réversible.

**A8 — Une réplique orpheline n'est pas rattachée de force.** Ce qui est dit **avant la première
image** n'a aucune image en vigueur — la vidéo n'avait encore rien montré, ou le premier changement
de plan est venu plus tard. La rattacher à la première image serait la même faute que prendre
l'image suivante, dans l'autre sens. Elle est **comptée et nommée**. Réversible.

**Limite, écrite et non maquillée** : toujours **aucun compte Teams de test**. Ce qui est prouvé ici
est **la règle d'alignement et tous ses cas limites**, sur des répliques et des images
**fabriquées**. Ce qui ne l'est pas : que les `pts_time` d'un enregistrement Teams réel soient
comptés depuis le début de la réunion, et que la transcription Teams porte bien les instants qu'on
suppose. **C'est précisément pour cela que l'origine du temps est un paramètre explicite et que le
décalage retenu est écrit dans le résultat** : le jour du premier branchement, l'écart se verra
au lieu de se cacher. La **sonde de santé (F-87 / SF-87-03)** reste responsable de la confrontation
au réel.
