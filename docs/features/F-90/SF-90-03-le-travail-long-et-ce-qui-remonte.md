# Mini-spec — F-90 / SF-90-03 — Le travail long qui se dit, et ce qui remonte

## Identifiant

`F-90 / SF-90-03`

## Feature parente

`F-90` — Le volet Teams : les captures alignées

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-90-03-le-travail-long-et-ce-qui-remonte`

---

## Objectif

Faire du pipeline de SF-90-01 et SF-90-02 un **travail asynchrone qui dit où il en est**, qui
**reprend** là où il s'était arrêté, et qui fait remonter **les 20 à 60 images retenues — et elles
seules** — jusqu'au compte rendu.

---

## Traitement lourd, donc asynchrone — c'est la règle de `CLAUDE.md`

Télécharger `ffmpeg`, décoder une heure de vidéo, dédoublonner des centaines d'images : des
**minutes**. Un outil qui rendrait la main au bout de sept minutes ferait tomber le délai d'appel
bien avant.

**Le modèle est celui d'`OcrPollingWorker`** : le travail ne tourne jamais dans le fil qui a reçu la
demande. `teams_meeting_moments` **démarre** et rend la main **tout de suite** avec un identifiant de
travail ; `teams_moments_status` dit où il en est ; un dernier appel rend les moments.

**Et le travail se dit dans le fil.** C'est le §5.5 du cadrage : *« la conversation ne se fige pas —
l'agent dit ce qu'il fait, étape par étape, comme pour une commande »*. Le runner diffuse ses étapes
par `ToolContext.stream`, exactement comme `bash` diffuse ses lignes. **Et depuis F-84, ce fil
survit à un changement d'écran** : l'utilisateur peut partir et revenir.

### La reprise

L'état d'un travail est écrit sur le disque de la machine, dans le dossier de travail du volet
(`.claude-runner/teams/jobs/<id>.json`). Redemander le **même** enregistrement **ne recommence pas** :

- travail **terminé** → ses moments sont rendus immédiatement, et on le **dit** ;
- travail **en cours** → on rend son avancement, on n'en démarre pas un second ;
- travail **échoué** → on rend l'échec **et sa raison**, et un `restart` explicite le relance.

L'identifiant d'un travail est **dérivé du fichier vidéo** (chemin + taille + date de modification) :
c'est ce qui fait qu'une reprise retrouve son travail sans que personne n'ait à noter un numéro.

---

## Ce qui reste, ce qui remonte

| Reste sur la machine | Remonte |
|---|---|
| l'enregistrement vidéo (centaines de Mo) | les **20 à 60 images retenues** |
| l'audio | le compte rendu, écrit par l'agent |
| les fichiers bruts, les images écartées | |
| les cookies et jetons Microsoft — **jamais rapatriés** | |

**La gateway orchestre, elle ne devient pas un entrepôt de vidéos de réunions.**

Les images remontent **une par une**, en HTTPS, par un endpoint du **canal runner**
(`POST /runner/teams/moments`), authentifié par le jeton runner. Elles sont stockées par le service
de F-89 (`TeamsMomentImageService`), qui **existait déjà sans producteur** : *« le dépôt est une
méthode de service, que F-90 appellera »*. C'est cet appel.

**Elles ne passent pas par le modèle** : le résultat d'outil ne porte que des **identifiants
d'image**, jamais des octets. Soixante images en base64 dans une réponse d'outil coûteraient plus
cher que tout le reste de la feature réunie, et n'apprendraient rien au modèle.

**D2 — la rétention** : les images vivent avec le compte rendu et **sont supprimées avec lui**. Rien
à faire de plus ici : `TeamsMomentImageService.deleteAll` est déjà branché sur la suppression du
terminal.

---

## D1 — ce qu'on annonce avant de traiter les paroles et les écrans de tiers

Une annonce **une fois par réunion**, avant le premier traitement, qui **nomme ce qui sera lu et où
cela ira**. Elle est plus longue ici que pour une lecture de messages, et pour une raison qu'il faut
écrire :

> **Une capture est plus indiscrète qu'une phrase.** La transcription dit ce qui a été **dit** ; les
> captures montrent ce qui était **visible** — y compris un tableau de bord avec des noms de clients,
> une messagerie ouverte à côté, une notification qui passe.

L'annonce le dit en toutes lettres, et dit ce qui reste sur la machine.

---

## Comportement attendu

### `teams_meeting_moments`

Entrée : `video` (chemin local, **obligatoire**), `meeting_id` (facultatif — donne l'origine du temps
et le sujet), `video_started_at` (facultatif), `offset_seconds` (facultatif), `scene_threshold`
(facultatif), `restart` (facultatif).

Sortie **immédiate** : l'identifiant du travail, son **étape**, son avancement, l'enveloppe commune
(`text`, `window`, `gaps`, `health`), et l'annonce D1 si c'est la première fois pour cette réunion.

Étapes, dans l'ordre, toutes **dites** : `OUTILLAGE` → `EXTRACTION` → `TRI` → `ALIGNEMENT` →
`REMONTÉE` → `TERMINÉ` (ou `ÉCHOUÉ`).

### `teams_moments_status`

Entrée : `job_id` (ou `video`, qui donne le même identifiant).

Sortie : l'étape, l'avancement chiffré (images extraites / retenues / remontées), et — **quand c'est
terminé** — les **moments** : instant, décalage vidéo, citation, locuteur, **identifiant d'image
remontée**. Plus, toujours, ce qui n'a pas pu être fait.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| `video` absent | manque nommé `MISSING_FIELD` ; aucun travail démarré |
| Fichier vidéo introuvable | refus nommé (SF-90-01), aucun travail démarré |
| `ffmpeg` ni présent ni rapatriable | le travail **échoue** en portant le **remède** |
| Origine du temps inconnue | le travail **échoue** en nommant les **deux** façons de la donner (SF-90-02) |
| `job_id` inconnu | **zéro moment et un manque nommé**, jamais un travail vide qui se lirait « rien trouvé » |
| Remontée d'une image refusée par la gateway | l'image est **écartée et comptée** ; le travail continue, et le compte l'accompagne |
| **Toutes** les remontées échouent | le travail **échoue** — un compte rendu de moments sans images n'est pas un compte rendu de moments |
| Navigateur non relié / `--no-teams` | succès d'outil portant l'état et le remède, comme les sept autres outils |

### L'endpoint de remontée — `POST /runner/teams/moments`

| | |
|---|---|
| Authentification | jeton runner (`X-Runner-Token`), vérifié **par le contrôleur** (D9) ; **jamais** d'`AuthenticatedUser` posé dans le `SecurityContext` |
| Isolation | `userId` vient **du jeton**, jamais du corps. `workspaceId` est un paramètre, mais il est **revérifié possédé par ce `userId`** — inconnu et « à quelqu'un d'autre » sont **indiscernables** (404) |
| Droit Teams | **exigé** : produire des moments demande l'option (D5). Relire n'en demande pas (F-89), produire si |
| Terminal Teams | le workspace doit en être un — on ne dépose pas de captures de réunion dans un projet de code |
| Type | liste **close** : `image/png`, `image/jpeg`, `image/webp` |
| Taille | 8 Mio par image — une capture d'écran 1280 px n'en pèse jamais le dixième |
| Nombre | 600 images par terminal, refus **nommé** au-delà : sans borne, une machine pourrait remplir le stockage d'un compte |
| Réponse | l'identifiant d'image, à poser dans un moment |

---

## Critères d'acceptation

- [ ] `teams_meeting_moments` **rend la main immédiatement** ; le travail tourne ailleurs.
- [ ] Les étapes sont **diffusées dans le fil** au fur et à mesure (`ToolContext.stream`).
- [ ] Redemander le même enregistrement **ne relance pas** un travail déjà en cours ou terminé.
- [ ] `restart` relance explicitement un travail échoué.
- [ ] L'identifiant de travail est **dérivé du fichier**, donc retrouvable sans être noté.
- [ ] D1 : l'annonce paraît **une fois par réunion**, et dit qu'**une capture est plus indiscrète
      qu'une phrase**.
- [ ] Le résultat d'outil ne porte **jamais** d'octets d'image, seulement des identifiants.
- [ ] La vidéo, l'audio et les images écartées **ne remontent jamais**.
- [ ] L'endpoint refuse sans jeton runner valide (401), sans droit Teams (403), sur un workspace
      d'un autre compte (404), sur un workspace qui n'est pas un terminal Teams (400).
- [ ] L'endpoint refuse un type hors liste close, une image trop grosse, et au-delà du plafond — **en
      nommant** la raison.
- [ ] Une remontée refusée est **comptée** ; toutes refusées font **échouer** le travail.
- [ ] `job_id` inconnu rend **zéro moment et un manque nommé**.
- [ ] Les deux catalogues d'outils (gateway et runner) portent **exactement** les mêmes noms.

---

## Tables / endpoints / composants impactés

### Runner

| Composant | Nature |
|---|---|
| `MomentsJob.java` | **nouveau** — l'état d'un travail : étape, compteurs, moments, manques |
| `MomentsJobStore.java` | **nouveau** — l'écriture et la relecture sur disque (**la reprise**) |
| `MomentsWorker.java` | **nouveau** — le travail lui-même, hors du fil de l'appel |
| `MomentUploader.java` | **nouveau** — la remontée d'une image, une par une, jeton runner |
| `TeamsTools.java` | **modifié** — deux outils de plus, et le catalogue |
| `TeamsViews.java` | **modifié** — le rendu d'un moment et d'un travail |
| `ToolStack.java` | **modifié** — le câblage (dossier de travail, outillage, remontée) |

### Gateway

| Composant | Nature |
|---|---|
| `RunnerTeamsMomentController.java` | **nouveau** — `POST /runner/teams/moments` |
| `RunnerSecurityConfig.java` | **modifié** — une entrée `permitAll` de plus, déclarée une par une |
| `TeamsToolCatalog.java` | **modifié** — les deux outils déclarés à l'agent |
| `RunnerToolGateway.java` | **modifié** — le délai des deux nouveaux outils |

**Aucune table, aucune migration** : le stockage des images existe depuis F-89.
**Aucun écran** : le bloc moment est livré (F-89 / SF-89-02 et SF-89-03).

---

## Ce qui est hors périmètre

- L'extraction (**SF-90-01**) et l'alignement (**SF-90-02**), livrés.
- L'affichage (**F-89**), livré.
- **L'acquisition de la vidéo** : F-90 part d'un fichier déjà sur la machine. La capture locale et sa
  transcription sont **F-91**.
- Le **montant** de l'option (D5) — **À CONFIRMER PAR LE PO**.

---

## Plan de test

**Runner**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 1 | `teams_meeting_moments` rend la main avant la fin du travail | l'asynchrone |
| 2 | Les étapes sont diffusées dans le fil | le travail se voit travailler |
| 3 | Un second appel ne relance rien, et le dit | la reprise |
| 4 | Un travail terminé rend ses moments immédiatement | la reprise |
| 5 | `restart` relance un travail échoué | |
| 6 | L'identifiant est dérivé du fichier | retrouvable sans être noté |
| 7 | D1 paraît une fois, et dit l'indiscrétion d'une capture | |
| 8 | `video` absent → manque nommé, aucun travail | |
| 9 | `job_id` inconnu → zéro moment + manque nommé | jamais « rien trouvé » |
| 10 | `ffmpeg` absent → travail échoué **portant le remède** | |
| 11 | Origine inconnue → travail échoué portant les deux remèdes | |
| 12 | Une remontée refusée est comptée, le travail continue | |
| 13 | Toutes les remontées refusées → travail échoué | |
| 14 | Le résultat ne porte aucun octet d'image | |
| 15 | Les deux catalogues portent les mêmes noms | verrou des deux côtés |
| 16 | Liste blanche CDP inchangée | sécurité |

**Gateway (intégration, `RunnerTeamsMomentApiIntegrationTest`)**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 17 | Dépôt nominal → identifiant, image relisible par son propriétaire | |
| 18 | Sans jeton / jeton invalide → 401 générique | |
| 19 | **Workspace d'un autre compte → 404** | **isolation utilisateur** |
| 20 | Sans droit Teams → 403 | D5 : produire demande l'option |
| 21 | Workspace qui n'est pas un terminal Teams → 400 | |
| 22 | Type hors liste close → 400 nommé | |
| 23 | Image trop grosse → 413 nommé | |
| 24 | Au-delà du plafond → 409 nommé | |
| 25 | Aucun `AuthenticatedUser` posé dans le `SecurityContext` | D9 |

**Isolation utilisateur** — test 19, **bloquant** : le `userId` vient du jeton, le `workspaceId` est
revérifié possédé, et la clé de stockage est **reconstruite** (jamais un chemin reçu).

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| **Auth / Principal** | **Oui** | `RunnerSecurityConfig` (une entrée `permitAll` de plus, déclarée **une par une**, jamais par joker), `RunnerTeamsMomentController` (authentifie lui-même, **ne pose rien** dans le `SecurityContext`). Les trois entrées existantes (`/runner/poll`, `/send`, `/disconnect`) sont **inchangées** ; le `denyAll` final couvre toujours le reste. Test 25 en non-régression |
| **Contexte tenant** | **Oui** | `userId` **du jeton** (`RunnerIdentity`), `workspaceId` **revérifié possédé** par `WorkspaceService.requireOwned`, clé de stockage **reconstruite** par `TeamsMomentImageService.prefixOf`. Aucun autre composant ne résout le tenant autrement. Test 19 |
| **Plans / limites** | **Oui** | `TeamsEntitlementService.isEntitled(userId)` — le **seul** service de droit Teams, déjà appelé par `TeamsAccessService`. Aucun gate nouveau : le même droit, posé à un endroit de plus. Test 20 |
| Navigation / routing | **Non** | aucun écran, aucune route frontend |
| **Liste blanche CDP** | **Non, et elle ne bouge pas** | `CdpCommands` intact — `ffmpeg` ne parle pas au navigateur. Test 16 |

---

## Notes et décisions

**A9 — La remontée passe par le canal runner, pas par le résultat d'outil.** Soixante images en
base64 dans une réponse d'outil traverseraient le modèle : des millions de jetons pour des octets
qu'il n'a aucune raison de lire. L'endpoint dédié coûte une route de plus et **rien** au tour.
**Alternative écartée** : les octets dans le résultat. Réversible.

**A10 — L'identifiant d'un travail est dérivé du fichier vidéo.** Un identifiant tiré au hasard
obligerait l'agent à le retenir d'un tour sur l'autre — et un agent qui a changé de tour ne l'a plus.
Le dériver du chemin, de la taille et de la date de modification fait qu'une reprise **retrouve son
travail toute seule**, et qu'un fichier modifié en démarre un autre. **Alternative écartée** : un
UUID. Réversible.

**A11 — Produire demande le droit, relire non.** F-89 l'a écrit pour l'image : *« un compte qui a
résilié l'option doit continuer de voir les comptes rendus qu'il a payés »*. L'endpoint de dépôt
applique donc le droit ; l'endpoint de lecture (F-89) ne l'applique pas. **C'est volontairement
asymétrique.** Réversible.

**A12 — Une remontée refusée n'arrête pas le travail, toutes l'arrêtent.** Perdre une image sur
soixante et le **dire** vaut mieux que perdre le compte rendu. Mais un compte rendu de moments sans
aucune image n'est pas un compte rendu de moments : il se lirait comme une transcription découpée,
et personne ne saurait qu'il manque tout. Le seuil est donc « aucune image » et non « quelques
images ». Réversible.

**A13 — Le plafond de 600 images par terminal.** Sans borne, une machine compromise — ou un agent en
boucle — remplirait le stockage d'un compte. Soixante images par compte rendu, dix comptes rendus par
terminal : 600 est large pour l'usage et net pour la borne. Le refus est **nommé**, jamais silencieux.
Réversible.

**Limite, écrite et non maquillée** : toujours **aucun compte Teams de test**. Ce qui est prouvé ici
est l'**asynchrone**, la **reprise**, la **diffusion des étapes**, **tous les refus** et — côté
gateway — l'**isolation** et le **droit**, sur un `ffmpeg` de papier, des images fabriquées et une
gateway de test. Ce qui ne l'est pas : qu'un enregistrement Teams réel se traite de bout en bout. La
**sonde de santé (F-87 / SF-87-03)** reste responsable de la confrontation au réel le jour du premier
branchement.
