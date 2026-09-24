# Cadrage — F-154 — Les actions à faire, visibles dans le terminal

> Demande du PO, 2026-09-24 :
> *« Je voudrais que lorsque je rentre dans un terminal — celui d'un sujet ou la racine — j'aie une
> option à l'écran : je clique, et j'ai la liste des actions qu'il faut que je fasse. Par exemple :
> contacter telle personne, envoyer tel message. Et ensuite, quand je reçois les réponses, j'ai juste à
> les rentrer dans le terminal et la liste se met à jour. »*
>
> *« Sur pas mal de clients, je commence un sujet, bim, je suis coincé : je veux faire une merge
> request et je n'ai pas les accès réseau, il faut contacter telle personne. Je voudrais que ces
> actions se mettent dans un menu du terminal — quitte à ce que je les annule moi-même après. »*

## 1. Ce qui existe déjà — et qui règle la moitié du problème

**Le modèle est déjà écrit, et il est bon.** Le Radar (F-100 → F-104) tient exactement cette notion :

| Brique existante | Ce qu'elle fait |
|---|---|
| `radar_commitments` | des **engagements** par sujet : « À faire par moi », « J'attends des autres », « mise en relation » |
| `radar_add_engagement` / `radar_mark_engagement` | l'agent en **crée** et en **ferme** (fait / abandonné) |
| La **preuve** | *la parole de l'utilisateur* : ce qu'il dit dans le terminal **est** la justification de l'écriture |
| La **chronologie** | tout est **annulable** depuis l'écran |
| Les **relances** | ce qui traîne est rappelé (F-104 / SF-104-05) |

**Ce qui manque n'est donc pas le modèle, c'est l'endroit.** Ces outils ne sont ouverts que dans le
**terminal Teams d'un client suivi par la Vigie** (`RadarToolCatalog.isOpenFor` exige
`workspace.isTeamsTerminal()`). Dans le terminal d'un **projet** — AGENOR, ou la racine — ils n'existent
pas, et l'écran n'a aucun menu d'actions.

**Conséquence exacte de ce que décrit le PO** : quand l'agent bute sur « il faut demander l'accès réseau
à X », il l'écrit dans sa réponse… et ça se perd au tour suivant.

## 2. Ce qu'on construit

1. **Les actions du terminal** : la même notion d'engagement, disponible **dans un terminal de projet**,
   rattachée au **sujet** quand il y en a un, au **projet** sinon.
2. **L'agent les inscrit quand il est bloqué** — c'est le cœur : un blocage nommé (« accès réseau
   manquant → contacter X ») devient une **action**, au lieu d'une phrase qui s'efface.
3. **Un menu dans le terminal** : la liste, d'un clic, sans quitter l'écran.
4. **L'utilisateur annule** ce qu'il juge inutile : l'action sort du périmètre, sans discussion.
5. **La réponse ferme l'action** : « X m'a répondu, il a ouvert le flux » → l'action se clôt, avec le
   message comme preuve. Même doctrine que le Radar : *la parole de l'utilisateur est souveraine*.

## 3. Ce qu'on ne fait pas
- **Deux listes.** Une seule notion d'action, quel que soit le terminal — sinon le PO aura un registre
  Vigie et un registre Forge qui divergent.
- **Deviner.** L'agent n'invente pas des actions « utiles » : il inscrit ce qui **bloque réellement**
  le travail en cours, ou ce que l'utilisateur lui dit.
- **Harceler.** Pas de rappel intempestif : la liste est là quand on l'ouvre.

## 4. Découpage proposé
| SF | Objet |
|---|---|
| **SF-154-01** | Les actions dans un terminal de projet : modèle, rattachement (sujet ou projet), outils d'écriture sous la preuve de l'utilisateur |
| **SF-154-02** | L'agent inscrit un **blocage** comme action, nommément, quand il ne peut pas avancer |
| **SF-154-03** | Le **menu du terminal** : la liste, l'annulation d'un clic |
| **SF-154-04** | La **fermeture par la réponse** : ce que l'utilisateur rapporte clôt l'action correspondante |

## 5. Ce qui reste à trancher
- **Rattachement** : une action née dans un terminal de projet appartient-elle au **projet** (elle suit
  le dépôt) ou au **client/poste** (elle rejoint le Radar de la Vigie) ? *Recommandation : au projet,
  avec remontée dans le Radar quand le terminal a un sujet Vigie — une seule notion, deux vues.*
