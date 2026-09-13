# Mini-spec — F-91 / SF-91-02 — Le témoin au premier plan, et l'arrêt d'un clic

## Identifiant

`F-91 / SF-91-02`

## Feature parente

`F-91` — Le volet Teams : l'enregistrement local

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-91-02-temoin-au-premier-plan`

---

## Objectif

Rendre une capture **impossible à oublier** : un témoin **toujours au premier plan** pendant toute sa
durée, qui compte le temps et l'**arrête d'un clic** — et donner à l'agent les outils par lesquels
l'utilisateur démarre, arrête et interroge une capture.

---

## Pourquoi le témoin ne prévient que l'utilisateur, et pourquoi c'est son but

On ne peut **rien afficher dans la réunion des autres** — seul Teams le peut. Le témoin ne les
protège donc pas, et ce n'est pas ce qu'on lui demande. Ce qu'on lui demande est écrit noir sur blanc
dans le cadrage : **éviter la capture oubliée qui tourne trois heures.**

Un enregistrement qu'on croit arrêté et qui continue est le pire artefact du volet : il capte la
réunion suivante, la conversation d'après, l'écran qu'on n'avait pas l'intention de montrer. **Une
fenêtre qu'on ne peut pas perdre de vue est la seule chose qui l'empêche.**

---

## Comportement attendu

### Cas nominal

1. La capture démarre (SF-91-01). **Avant la première image utile**, le témoin apparaît : petite
   fenêtre sans décor, **toujours au-dessus** des autres, déplaçable.
2. Il porte : que ça enregistre, **l'usage** (mon écran / une réunion), la **durée qui court**, et un
   bouton **Arrêter**.
3. Le bouton arrête réellement la capture — le même chemin que l'outil d'arrêt, pas un second.
4. À l'arrêt, le témoin disparaît.

### L'arrêt de sécurité

Au-delà d'un **plafond de durée** (3 heures), la capture **s'arrête d'elle-même** et le dit. C'est la
« capture oubliée » traitée jusqu'au bout : un témoin caché par une session verrouillée ne préviendrait
plus personne.

### Les outils donnés à l'agent

| Outil | Ce qu'il fait |
|---|---|
| `teams_capture_start` | Démarre — **avec l'usage, et la confirmation quand c'est une réunion** |
| `teams_capture_stop` | Arrête, et rend le fichier, la durée, la taille |
| `teams_capture_status` | Où en est la capture en cours, et l'historique des précédentes |

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Aucun environnement graphique (poste sans écran, session SSH) | **Refus de capturer** : sans témoin, la capture oubliée n'a plus de garde-fou — et un poste sans écran n'a de toute façon rien à capturer |
| La fenêtre ne peut pas être créée (gestionnaire de fenêtres absent, exception graphique) | **Refus de capturer**, avec la raison |
| Le système ne sait pas mettre une fenêtre au premier plan | La fenêtre est quand même montrée, et le **manque est nommé** : un témoin visible vaut mieux que pas de témoin |
| L'utilisateur ferme le témoin | La croix est **absente** : le seul geste est « Arrêter » |
| Le module graphique n'est pas dans cette JVM | **Refus de capturer**, avec la raison |
| Clic sur « Arrêter » alors que la capture est déjà finie | Sans effet, et sans exception : personne ne lit une exception derrière un bouton |
| `teams_capture_start` sans usage / réunion sans confirmation | **Refus rendu comme un résultat d'outil**, porteur du remède (règle de forme n° 1 du volet) |
| Volet Teams désactivé (`--no-teams`) | L'outil le dit, il ne fait pas semblant |

---

## Critères d'acceptation

- [ ] Le témoin est montré **avant** que la capture soit déclarée démarrée.
- [ ] Il est **toujours au premier plan** quand le système le permet, et le dit quand il ne le
      permet pas.
- [ ] Il affiche la **durée qui court**, rafraîchie à la seconde.
- [ ] Il porte **un seul** geste : arrêter. Aucune croix de fermeture.
- [ ] Le bouton passe par **le même chemin** que `teams_capture_stop`.
- [ ] Un poste **sans environnement graphique** ne capture pas : refus nommé.
- [ ] Au-delà de **3 heures**, la capture s'arrête seule et le **dit**.
- [ ] `teams_capture_start` refuse **en rendant un résultat d'outil**, jamais une erreur d'outil.
- [ ] `teams_capture_status` sans identifiant rend **celle qui tourne**.
- [ ] Chaque résultat porte, à côté de son contenu, **ce qui n'a pas pu être fait**.
- [ ] Le catalogue côté runner et le catalogue côté gateway portent **exactement les mêmes noms**.

---

## Tables / endpoints / composants impactés

| Composant | Nature |
|---|---|
| `runner/…/teams/CaptureWitness.java` | **nouveau** — le témoin : fenêtre au premier plan, durée, arrêt |
| `runner/…/teams/CaptureWitnessException.java` | **nouveau** — l'impossibilité de montrer un témoin |
| `runner/…/teams/CaptureCeiling.java` | **nouveau** — l'arrêt de sécurité au bout de trois heures |
| `runner/…/teams/LocalCapture.java` | **modifié** — refus quand le témoin est impossible, plafond |
| `runner/…/teams/TeamsTools.java` | **modifié** — les trois outils de capture |
| `runner/…/ToolStack.java` | **modifié** — montage du moteur et du témoin |
| `backend/…/teams/TeamsToolCatalog.java` | **modifié** — les trois outils donnés à l'agent |
| `backend/…/atelier/AtelierChatService.java` | **modifié** — cible d'audit d'un appel de capture |

**Aucune table, aucune migration, aucun écran Angular.** Le témoin est une fenêtre **du poste**, pas
une page de l'application — et c'est exactement ce qu'il doit être : une page web ne peut pas rester
au-dessus des autres fenêtres.

---

## Ce qui est hors périmètre

- **La transcription sur la machine** et la mention en tête du compte rendu → SF-91-03.
- **Afficher quoi que ce soit dans la réunion des autres** : impossible.
- Un écran Angular de pilotage des captures : ce serait un bouton, et le cadrage les refuse.

---

## Plan de test

**Unitaires (runner)**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 1 | `CaptureWitnessTest` — sans environnement graphique | refus nommé, aucune fenêtre |
| 2 | `CaptureWitnessTest` — le texte du témoin | usage, durée, et un seul geste |
| 3 | `CaptureWitnessTest` — la durée court | `00:00:05` puis `00:01:05` |
| 4 | `CaptureWitnessTest` — premier plan impossible | montré quand même, **et le manque nommé** |
| 5 | `LocalCaptureTest` — témoin impossible | **refus de capturer**, aucun processus lancé |
| 6 | `CaptureCeilingTest` — sous le plafond | rien ne se passe |
| 7 | `CaptureCeilingTest` — au plafond | arrêt, et la raison est dite |
| 8 | `TeamsCaptureToolsTest` — `start` nominal | résultat portant l'identifiant, le filigrane, la mention |
| 9 | `TeamsCaptureToolsTest` — `start` sans usage | **succès d'outil** portant le refus et son remède |
| 10 | `TeamsCaptureToolsTest` — `start` réunion sans confirmation | idem, et la phrase du non-garanti |
| 11 | `TeamsCaptureToolsTest` — `stop` sans identifiant | arrête celle qui tourne |
| 12 | `TeamsCaptureToolsTest` — `status` sans identifiant | rend celle qui tourne |
| 13 | `TeamsCaptureToolsTest` — `status` sans capture | zéro **et** un manque nommé |
| 14 | `TeamsCaptureToolsTest` — volet désactivé | le dit, ne fait pas semblant |
| 15 | `TeamsToolsTest` — le catalogue | les trois noms y sont |

**Unitaires (backend)**

| # | Test | Ce qu'il prouve |
|---|---|---|
| 16 | `TeamsToolCatalogTest` — miroir des catalogues | gateway et runner portent les mêmes noms |
| 17 | `TeamsToolCatalogTest` — sans droit | aucun outil `teams_*`, capture comprise |
| 18 | `TeamsToolCatalogTest` — descriptions | la confirmation et le non-garanti y sont écrits |
| 19 | `AtelierChatServiceTeamsToolsTest` — cible d'audit | l'usage est tracé, jamais un contenu |

**Isolation utilisateur** — le droit Teams (F-89 / SF-89-01) est la garde : sans lui, **aucun** outil
`teams_*` n'est donné, capture comprise. Test 17. Rien ne remonte dans cette subfeature.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | **Non** | aucun endpoint nouveau |
| Contexte tenant | **Non** | rien ne remonte ; le droit est celui de F-89, inchangé |
| **Plans / limites** | **Oui, et rien ne change** | `TeamsToolCatalog.toolsFor` est le **seul** point de garde ; les trois outils y sont ajoutés, donc gardés par construction. Test 17 le verrouille sur le préfixe, pas sur une énumération |
| Navigation / routing | **Non** | aucun écran |
| **Environnement graphique** | **Oui, et c'est nouveau** | le runner ouvre une fenêtre. Traité ci-dessous |

### L'environnement graphique, traité explicitement

Le runner n'avait jusqu'ici **aucune** interface : c'est un client de ligne de commande. Ouvrir une
fenêtre ajoute une dépendance au module `java.desktop` et à un serveur graphique.

1. **Rien n'est chargé tant qu'aucune capture n'est demandée.** Le témoin est construit au
   démarrage d'une capture, pas au montage des outils : un runner qui n'enregistre jamais ne touche
   jamais au module graphique.
2. **Un runner sans écran refuse de capturer**, il ne se dégrade pas. Sans témoin, le garde-fou
   n° 3 n'existe plus — et un poste sans écran n'a rien à capturer.
3. **Aucun autre outil n'est affecté** : `bash`, les outils fichiers et les outils de lecture Teams
   ne passent par aucun code graphique.

---

## Notes et décisions

**A1 — Sans témoin, on ne capture pas.** On *pourrait* capturer en mode sans écran et le dire. On ne
le fait pas : le témoin est le garde-fou n° 3, et un garde-fou qui s'efface quand il gêne n'est pas
un garde-fou (même raisonnement qu'en SF-91-01 pour le filigrane). Et le cas réel est cohérent : un
poste sans environnement graphique n'a pas d'écran à capturer.
**Alternative écartée** : un témoin « console » (des lignes dans le terminal) — l'utilisateur ne
regarde pas son terminal pendant une réunion, c'est précisément le problème. **Réversible.**

**A2 — Le témoin n'a pas de croix.** Fermer la fenêtre ne doit pas être possible : ce serait
« masquer le témoin sans arrêter la capture », c'est-à-dire retirer le garde-fou en gardant le
risque. Le seul geste est **Arrêter**. **Réversible.**

**A3 — Un plafond de trois heures, et il s'arrête vraiment.** Le PO demande la solution la plus
complète : le témoin traite la capture oubliée quand l'écran est visible, le plafond la traite quand
il ne l'est pas (session verrouillée, écran éteint, poste laissé allumé). Trois heures est plus long
que toute réunion normale et beaucoup plus court qu'une nuit. **Le plafond est dit au démarrage**, pas
découvert à l'arrêt. **Alternative écartée** : un simple avertissement — un avertissement que personne
ne lit ne protège personne. **Réversible** (constante nommée).

**A4 — Le bouton du témoin passe par le moteur, pas par un second chemin.** `LocalCapture.stopQuietly`
est le même arrêt que l'outil, avec les exceptions absorbées : personne ne lit une exception derrière
un bouton. Deux chemins d'arrêt finiraient par diverger sur l'essentiel — écrire l'état, fermer le
conteneur proprement. **Réversible.**

**A5 — Les refus sont des SUCCÈS d'outil.** C'est la règle de forme n° 1 du volet (SF-88-01) : une
erreur d'outil ferait dire à l'agent « je n'ai pas réussi », là où il faut dire « cochez la
confirmation, et voici pourquoi ». **Réversible.**

**Limite, écrite et non maquillée** : le CI est **sans écran**. Le témoin réel n'y est donc jamais
construit — ce qui est éprouvé est **ce qu'il dit** (texte, durée, geste unique), son **refus** en
l'absence d'environnement graphique, et le fait que le moteur **refuse de capturer** quand il ne peut
pas le montrer. Ce qui n'est **pas** éprouvé : qu'une fenêtre Swing reste effectivement au-dessus
d'un partage d'écran Teams en plein écran, sur chacun des trois systèmes. C'est exactement le genre
de chose que le premier branchement tranchera, et c'est dit dans la PR.
