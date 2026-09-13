# Mini-spec — F-91 / SF-91-03 — La transcription sur la machine, et la mention qui voyage avec l'artefact

## Identifiant

`F-91 / SF-91-03`

## Feature parente

`F-91` — Le volet Teams : l'enregistrement local

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-91-03-transcription-sur-la-machine`

---

## Objectif

Donner à une capture locale la **transcription qu'elle n'a pas** — produite **sur la machine**, sans
que la vidéo ni l'audio n'en sortent — puis **rejoindre le chemin existant** (moments, carte, compte
rendu), en y portant la **mention** qui dit d'où l'artefact vient.

---

## Pourquoi transcrire sur la machine, et pas ailleurs

**Conséquence en cascade du cadrage** : une capture locale n'a **pas** de transcription — Teams n'en
produit que pour ses propres enregistrements. Il faut donc la produire nous-mêmes.

Et il faut la produire **ici**. Envoyer l'audio d'une réunion à un service **annulerait le bénéfice
de garder la vidéo en local** : ce qu'on protégeait en ne remontant pas les images, on le donnerait
en remontant les voix. Le modèle est donc **téléchargé une fois** (D3), et **rien ne sort — ni la
vidéo, ni l'audio, seulement le texte**.

---

## Comportement attendu

### Cas nominal

1. `teams_capture_stop` arrête la capture (SF-91-02) et **démarre la transcription** — traitement
   **lourd, donc asynchrone** : l'outil rend la main tout de suite.
2. L'audio est extrait de la capture par `ffmpeg` (mono, 16 kHz), **dans le dossier de la capture**.
3. Le modèle de transcription est résolu par le chemin **D3 déjà en place** : `PATH`, copie
   rapatriée, puis téléchargement — **annoncé avant, confirmé après**.
4. La transcription tourne **hors ligne**, et écrit ses répliques horodatées.
5. Elles deviennent des `TeamsTranscriptCue`, **datés en absolu** à partir de l'instant de démarrage
   de la capture — celui-là, on le connaît exactement : c'est nous qui l'avons écrit.
6. Le fichier de transcription est écrit sur la machine, **avec la mention en première ligne**.
7. `teams_capture_status` dit où en est le travail, puis rend les répliques.
8. **On rejoint le chemin existant** : `teams_meeting_moments` accepte un `capture_id` et y prend la
   vidéo, les répliques et l'origine du temps. Les moments, la carte et le compte rendu **ne sont pas
   refaits**.

### La mention voyage avec l'artefact — les quatre endroits

| Où | Quoi | Depuis |
|---|---|---|
| **Dans l'image** | le filigrane incrusté par `ffmpeg` | SF-91-01 |
| **Dans le journal d'audit** | l'usage et la confirmation déclarée | SF-91-02 |
| **Dans le fichier de transcription** | la mention, en **première ligne** | **cette subfeature** |
| **En tête du compte rendu** | `recordingNotice`, affiché **au-dessus du titre** du bloc | **cette subfeature** |

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Capture **sans son** | Aucune transcription, **et un manque nommé** — jamais un silence qui se lirait « rien n'a été dit » |
| Modèle introuvable **et** non rapatriable | **Échec nommé** avec le remède ; la vidéo, elle, reste intacte |
| Binaire de transcription absent sur ce système | **Échec nommé** avec la commande d'installation du système observé |
| Extraction audio en échec | Échec portant **les dernières lignes de `ffmpeg`** |
| Transcription en échec | Échec portant **les dernières lignes** du binaire |
| Sortie illisible (format inattendu) | **Échec nommé** — on ne rend pas la moitié des répliques |
| Une réplique isolée illisible | Écartée, **comptée et nommée** ; les autres sont rendues |
| **Zéro réplique reconnue** | Zéro **et** un manque nommé : « aucune parole reconnue » ≠ « personne n'a parlé » |
| `teams_meeting_moments` avec un `capture_id` inconnu | Refus nommé, sans rien inventer |
| `teams_meeting_moments` sur une capture **non terminée** | Refus nommé : on n'extrait pas d'images d'un fichier en cours d'écriture |
| Bloc de compte rendu **sans** `recordingNotice` alors qu'il vient d'une capture | L'agent en est **instruit** par les descriptions d'outils ; le champ reste facultatif côté bloc — voir A5, qui dit ce que le produit ne garantit pas |

---

## Critères d'acceptation

- [ ] **Rien ne sort de la machine** : aucun appel réseau ne porte l'audio ou la vidéo. Vérifié par
      un test qui inspecte tout ce qui est émis.
- [ ] Le modèle est **téléchargé au premier usage**, jamais embarqué ; le téléchargement se **dit**.
- [ ] Le modèle est cherché **avant** d'être téléchargé (copie déjà rapatriée).
- [ ] La transcription est **asynchrone** : `teams_capture_stop` rend la main tout de suite.
- [ ] Les répliques sont **datées en absolu**, à partir de l'instant de démarrage de la capture.
- [ ] Le fichier de transcription porte **la mention en première ligne**.
- [ ] Une capture sans son rend **zéro réplique et un manque nommé**.
- [ ] Zéro réplique reconnue rend **zéro et un manque nommé**, jamais un silence.
- [ ] Une sortie illisible **échoue**, elle ne rend pas la moitié des répliques.
- [ ] `teams_meeting_moments` accepte `capture_id` et **rejoint le chemin existant** sans le refaire.
- [ ] Une capture **non terminée** est refusée par `teams_meeting_moments`.
- [ ] Le bloc de compte rendu porte `recordingNotice`, **affiché au-dessus du titre**.
- [ ] Le repli textuel du bloc porte la mention **en première ligne** lui aussi.
- [ ] **Aucune couleur nouvelle** : la mention se lit par la typographie, jamais par un pictogramme
      d'alerte.

---

## Tables / endpoints / composants impactés

| Composant | Nature |
|---|---|
| `runner/…/teams/LocalTool.java` | **modifié** — le modèle et le binaire de transcription ; genre `DATA` |
| `runner/…/teams/LocalToolchain.java` | **modifié** — un fichier de **données** se rapatrie sans se lancer |
| `runner/…/teams/AudioTrack.java` | **nouveau** — l'extraction audio par `ffmpeg` |
| `runner/…/teams/LocalTranscription.java` | **nouveau** — la transcription hors ligne, et sa lecture |
| `runner/…/teams/TranscriptionJob.java` | **nouveau** — l'état du travail, écrit et relu |
| `runner/…/teams/TranscriptionWorker.java` | **nouveau** — le travail long, hors du fil de l'appel |
| `runner/…/teams/CaptureRecord.java` | **modifié** — porte sa transcription |
| `runner/…/teams/TeamsTools.java` | **modifié** — `capture_stop` démarre, `capture_status` rend, `meeting_moments` accepte `capture_id` |
| `runner/…/ToolStack.java` | **modifié** — montage |
| `backend/…/teams/TeamsToolCatalog.java` | **modifié** — descriptions : `capture_id`, `recordingNotice` |
| `backend/…/teams/block/TeamsBlockCard.java` | **modifié** — `recordingNotice` |
| `backend/…/teams/block/TeamsBlockCards.java` | **modifié** — lecture et borne du champ |
| `frontend/…/core/models/atelier.models.ts` | **modifié** — `recordingNotice` |
| `frontend/…/atelier/terminal/teams-block.ts` | **modifié** — la mention en tête du repli textuel |
| `frontend/…/atelier/terminal/atelier-terminal.component.html` | **modifié** — la mention au-dessus du titre |
| `frontend/…/atelier/terminal/atelier-terminal-teams.component.scss` | **modifié** — un style, **aucune couleur nouvelle** |

**Aucune table, aucune migration, aucun endpoint nouveau.**

---

## Ce qui est hors périmètre

- **Refaire le résumé, la carte ou les moments** : F-87 à F-90 sont livrées, on les **rejoint**.
- **Envoyer l'audio à un service de transcription** : cela annulerait le bénéfice de garder la vidéo
  en local. Écrit ici pour que la tentation soit nommée.
- **Diariser** (« qui parle ») : le modèle local ne le fait pas sans un second modèle, et une
  attribution fausse serait pire qu'une absence d'attribution. Les répliques sortent **sans
  locuteur**, et cela se **dit**.
- **Traduire.**

---

## Plan de test

**Unitaires (runner)**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 1 | `LocalToolchainTest` — un outil de genre `DATA` | rapatrié sans être lancé, ni cherché dans le `PATH` |
| 2 | `AudioTrackTest` — la ligne de commande | mono 16 kHz, sans shell, sortie dans le dossier de la capture |
| 3 | `AudioTrackTest` — `ffmpeg` en échec | échec portant ses dernières lignes |
| 4 | `LocalTranscriptionTest` — lecture de la sortie | répliques horodatées, sur un échantillon **fabriqué** |
| 5 | `LocalTranscriptionTest` — datées en absolu | décalage + instant de démarrage |
| 6 | `LocalTranscriptionTest` — sortie illisible | **échec**, pas la moitié des répliques |
| 7 | `LocalTranscriptionTest` — une ligne illisible | écartée, comptée, nommée |
| 8 | `LocalTranscriptionTest` — zéro réplique | zéro **et** un manque nommé |
| 9 | `LocalTranscriptionTest` — le fichier écrit | **la mention en première ligne** |
| 10 | `TranscriptionWorkerTest` — asynchrone | l'appel rend la main avant la fin |
| 11 | `TranscriptionWorkerTest` — sans son | zéro, un manque nommé, aucun modèle rapatrié |
| 12 | `TranscriptionWorkerTest` — reprise | redemander ne recommence pas |
| 13 | `RienNeSortDeLaMachineTest` — **le test de la subfeature** | aucun octet d'audio ni de vidéo dans ce qui est émis |
| 14 | `TeamsCaptureToolsTest` — `stop` démarre la transcription | et le dit |
| 15 | `TeamsCaptureToolsTest` — `status` rend les répliques | quand c'est fini |
| 16 | `TeamsMomentsToolsTest` — `capture_id` | rejoint le chemin existant : vidéo, répliques, origine |
| 17 | `TeamsMomentsToolsTest` — capture en cours | refus nommé |

**Unitaires (backend)**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 18 | `TeamsBlockCardsTest` — `recordingNotice` lu | rendu dans le bloc, borné |
| 19 | `TeamsBlockCardsTest` — absent | accepté : tous les blocs ne viennent pas d'une capture |
| 20 | `TeamsReadingCatalogTest` — descriptions | `capture_id` et `recordingNotice` y sont dits |

**Unitaires (frontend)**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 21 | `teams-block.spec.ts` — repli textuel | la mention en **première ligne** |
| 22 | `terminal-teams.spec.ts` — à l'écran | la mention **au-dessus du titre**, et pas repliée |
| 23 | `terminal-teams.spec.ts` — sans mention | rien d'affiché, aucun cadre vide |

**Isolation utilisateur** — le droit Teams (F-89 / SF-89-01) reste la garde : sans lui, aucun outil
`teams_*`. La remontée des images passe par la route de F-90 / SF-90-03, qui revérifie déjà que le
terminal appartient au compte du jeton présenté — **inchangée**.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | **Non** | aucun endpoint nouveau |
| Contexte tenant | **Non** | la remontée passe par la route existante, garde inchangée |
| Plans / limites | **Non** | le droit Teams est posé par F-89 ; les outils ne changent pas de nombre |
| **Navigation / routing** | **Non** | aucune route ; un champ de plus dans un bloc existant |
| **Forme d'un bloc riche (contrat écran)** | **Oui** | `TeamsBlockCard` gagne un champ. Tolérant aux inconnus des deux côtés : un bloc ancien se relit, un bloc neuf s'affiche sur un écran ancien sans casser |
| **Sortie de données hors de la machine** | **Oui, et c'est le cœur** | traité ci-dessous |

### La sortie de données, traitée explicitement

C'est la préoccupation qui **décide** de cette subfeature. Trois garanties :

1. **Le binaire de transcription tourne en local**, sans réseau. Le seul trafic est le
   **téléchargement du modèle**, une fois, depuis une adresse **en dur dans le code**.
2. **Ce qui remonte est le texte**, et rien d'autre : la vidéo, l'audio et le fichier audio extrait
   restent dans le dossier de la capture.
3. **Un test le vérifie** plutôt que de le promettre (test 13) : tout ce qui est émis est inspecté,
   et aucun octet d'audio ni de vidéo n'y figure.

---

## Notes et décisions

**A1 — La transcription démarre à l'arrêt de la capture, sans qu'on la demande.** On *pourrait*
attendre un outil dédié. On ne le fait pas : une capture sans transcription ne sert à rien, et faire
attendre un tour de plus ferait perdre des minutes à quelqu'un qui sort de réunion. Elle est
**asynchrone** et **dite**, donc elle ne bloque rien. **Alternative écartée** : un quatrième outil —
il aurait ajouté une étape que l'agent aurait parfois oubliée. **Réversible.**

**A2 — L'origine du temps est celle qu'on a écrite, pas celle qu'on devine.** F-90 refuse d'aligner
sans origine connue, et c'est la bonne règle. Ici, on la **connaît exactement** : c'est l'instant où
**nous** avons démarré la capture. C'est le seul cas du volet où l'alignement ne repose sur aucune
hypothèse — et c'est ce qui rend les moments d'une capture locale plus sûrs que ceux d'un
enregistrement Teams. **Réversible.**

**A3 — Les répliques sortent sans locuteur, et on le dit.** Le modèle local ne sépare pas les voix.
Attribuer les paroles à l'écran serait une invention, et « une attribution fausse est pire qu'une
absence d'attribution » est la règle de ce volet. Le manque est **nommé** dans le résultat.
**Alternative écartée** : un second modèle de diarisation — un modèle de plus à télécharger pour un
résultat que personne n'a demandé. **Réversible.**

**A4 — Une sortie illisible fait échouer, une ligne illisible est comptée.** La nuance est celle de
SF-90-01 : un format entier qu'on ne reconnaît plus veut dire qu'on ne sait plus lire, et rendre la
moitié serait « plausible et faux ». Une ligne isolée, elle, est un trou qu'on peut **nommer**.
**Réversible.**

**A5 — `recordingNotice` est un champ du bloc, et ce que cela garantit est dit exactement.** Ce que
le produit **garantit** : le filigrane dans l'image (structurel, SF-91-01), la ligne d'audit
(structurelle, SF-91-02), la mention en tête du fichier de transcription (structurelle, ici). Ce que
le produit **n'impose pas** : que l'agent recopie la mention dans le bloc — le champ est **facultatif**
parce que la plupart des blocs ne viennent pas d'une capture, et un champ obligatoire ferait refuser
tous les autres. Les descriptions d'outils l'**ordonnent**, et le résultat de capture le rend prêt à
coller. **C'est écrit ici plutôt que maquillé** : sur les quatre endroits où la trace voyage, trois
sont garantis par construction, le quatrième dépend du modèle. **Réversible** — le rendre obligatoire
pour les seuls blocs issus d'une capture demanderait que la gateway sache d'où vient le bloc, ce
qu'elle ne sait pas aujourd'hui.

**Limite, écrite et non maquillée** : le CI n'a ni `ffmpeg`, ni binaire de transcription, ni modèle,
ni compte Teams de test. Ce qui est prouvé est ce que le produit **décide** — assembler, lire une
sortie, dater, refuser, ne rien laisser sortir — sur des **ports injectés** et des sorties
**fabriquées à la main d'après le format documenté**. Ce qui n'est **pas** prouvé : qu'un binaire
réel produise ce format sur une vraie réunion, ni la qualité de la transcription obtenue. Les
adresses de téléchargement du binaire et du modèle viennent de leurs dépôts officiels ; elles n'ont
pas été exercées. La **sonde de santé (F-87 / SF-87-03)** reste responsable de la confrontation au
réel le jour du premier branchement.
