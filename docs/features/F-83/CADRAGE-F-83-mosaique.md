# F-83 — La mosaïque : quatre terminaux en même temps

> Cadrage du 2026-09-12. **Cette feature répare une réduction que j'ai introduite**, pas un défaut
> de livraison : F-76 a fait exactement ce que son cadrage demandait.

## 1. Ce que le PO a demandé, et ce qu'il a reçu

Ses mots, au moment du cadrage de F-76 :

> « Au moins je vois le travail dans les terminaux… **en lecture seule sur les 4 terminaux**. »

Ce que la consigne de F-76 a retenu :

> « FORME RETENUE : l'aperçu vivant, **PAS quatre flux complets rejoués** — illisibles dans une
> tuile. »

**Cette phrase n'est pas du PO.** « Voir les quatre terminaux » a été traduit en « voir six lignes de
chacun », et le cadrage validé portait déjà la réduction. Le PO a donc reçu des **vignettes**, pas
des terminaux — et, le plafond de places étant lié aux onglets ouverts, il n'en voyait qu'une seule.

Son besoin, reformulé le 2026-09-12 : **« je veux voir les 4 terminaux en même temps, pas un seul.
Ça a toujours été ça mon besoin sur la vue 360. »**

## 2. Pourquoi l'objection ne tient pas

« Illisible dans une tuile » était une **opinion de mise en page**, présentée comme une contrainte.
Les trois obstacles supposés, vérifiés :

| Obstacle supposé | Réalité |
|---|---|
| Quatre flux dans une page | Tenable : l'ingress de production est en **HTTP/2 multiplexé** ; ce n'est contraint qu'en développement (proxy `ng serve` en HTTP/1.1, ~6 connexions par origine). **Écrit dans F-70.** |
| Quatre flux = quatre tours payés | **Faux pour la lecture.** Regarder un terminal **qui tourne déjà** ne coûte rien : le tour est en cours, le flux existe. C'est **ouvrir** un cinquième flux qui coûte. |
| Le plafond de quatre de F-70 | Ce n'est pas un obstacle, c'est **exactement** le bon nombre de tuiles. |

Reste une vraie question, qui ne se tranche qu'en essayant : **sur un écran de portable, quatre flux
complets, ça donne quoi ?** D'où l'agrandissement d'une tuile d'un clic, prévu dès le départ.

## 3. Ce que F-83 livre

**Une mosaïque de quatre vrais terminaux**, vivants, en lecture seule, dans une même page.

Le **contenu réel** du terminal — le flux, tel qu'on le voit dans un terminal ouvert — et non un
résumé de six lignes. C'est toute la différence avec F-76.

### Exigences du PO, littérales

1. **Les terminaux prennent une très grosse partie de l'écran.** Tout ce qui n'est pas du terminal —
   en-têtes, marges, chrome — se réduit à ce qui reste indispensable. Une mosaïque dont la moitié de
   la surface sert à encadrer rate son objet.
2. **Le fond de chaque terminal en lecture seule est celui du terminal actuel** : `--cg-navy-2`
   (`#141D33`), comme `atelier-terminal.component.scss`. On doit reconnaître un terminal, pas
   découvrir un nouveau composant. **Aucune couleur nouvelle**, aucun registre supplémentaire.

### Le reste

- **La couleur du client sur chaque tuile** (SF-49-03) : quatre terminaux, c'est souvent quatre
  clients, et c'est le seul repère qui permette de ne pas se tromper de fenêtre.
- **Un clic agrandit une tuile** — et la rend à la mosaïque. C'est la réponse à l'écran de portable.
- **Une tuile en attente d'autorisation se signale franchement.** Exigence non négociable reprise de
  F-76, et elle vaut ici plus encore : c'est la vue où l'on regarde quatre choses à la fois. Palette
  de statut §5, comme F-76 — **pas de quatrième registre de couleur**.
- **Lecture seule, strictement.** Écrire reste un geste pris dans le terminal, devant son flux
  entier. Un clic pour y entrer.

## 4. La question qui décide de l'architecture

**D'où viennent les flux ?** Deux voies, et elles n'ont pas le même prix.

| | |
|---|---|
| **A — La page ouvre ses propres flux** | Quatre connexions depuis la mosaïque, comme un terminal ordinaire. Simple, fidèle. Mais une place du registre F-70 par flux : **la mosaïque consommerait les quatre places** et l'on ne pourrait plus rien ouvrir ailleurs. Sauf à distinguer une place « lectrice » d'une place « émettrice » — F-76 avait justement vérifié que la supervision ne prend **aucune** place. |
| **B — Rejouer le fil depuis la gateway** | La mosaïque lit le fil de chaque projet (`atelier_messages`) et le rafraîchit. Aucune place prise, aucun flux nouveau. Mais **ce n'est pas le direct** : la latence est celle du rafraîchissement, et un tour en cours n'apparaît qu'à mesure qu'il est persisté. |

**Recommandation** : **A, avec des places lectrices**. Le PO demande de *voir travailler*, pas de
*relire* — B livrerait une seconde fois un aperçu décalé, c'est-à-dire l'erreur de F-76 sous une
autre forme. Le registre de F-70 compte des flux **payants** : une lecture qui n'ouvre aucun tour
n'a aucune raison d'y prendre une place, et la distinction est de toute façon nécessaire le jour où
l'on regarde le travail d'un agent lancé ailleurs.
**TRANCHÉ PAR DÉFAUT le 2026-09-12, le PO ayant demandé une livraison autonome** : voie **A**, avec
des places **lectrices** distinctes des places émettrices. Le choix est **réversible** — il tient dans
la façon de compter les places, pas dans la forme de l'écran. Il est signalé ici pour que le PO
puisse l'infirmer sans rien refaire d'autre que le comptage.

## 5. Hors périmètre

- **Écrire depuis une tuile.** Tranché par le PO dès F-76, inchangé.
- Voir un terminal dont **aucun onglet n'est ouvert**. C'est le sujet distinct de la source de
  vérité, déjà identifié ; F-83 ne le rouvre pas.
- Retirer les aperçus de F-76 : ils gardent leur sens sur la page d'accueil, là où l'on ne veut
  précisément **pas** de flux.
- Changer le plafond de quatre.

## 6. Impact transversal

| Préoccupation | Composants |
|---|---|
| **Plafond / places** | `LiveTerminalService`, `live_terminals`, le 409 `terminal_limit_reached` — **à lister et vérifier un par un** si la voie A est retenue |
| Navigation | une route de plus sous `/forge` ; aucune entrée nouvelle dans la barre |
| Frontend | la vue de supervision de F-76, le flux du terminal (à réutiliser, **pas à recopier**) |

## 7. Plan de test minimal

- Quatre terminaux vivants → **quatre tuiles**, chacune avec le **contenu réel** de son flux.
- Le fond d'une tuile est **exactement** celui du terminal (`--cg-navy-2`) — vérifié par test.
- Une tuile en attente d'autorisation se signale, et passe en tête.
- Agrandir puis réduire une tuile : le flux **n'est pas rouvert** au passage.
- Voie A : ouvrir la mosaïque **ne consomme aucune place émettrice** — on peut toujours ouvrir un
  quatrième terminal ailleurs. C'est le test qui protège le portefeuille du PO.
- Isolation `user_id` sur chaque flux lu.
