# Cadrage — F-60 · Cycle de vie des postes, visible d'un coup d'œil

> Dérivé de la ligne **F-60** de `docs/PRODUCT_SPEC.md` (cadrée le 2026-09-10). Ce document
> n'ouvre aucun périmètre nouveau : il tranche les pièges et il découpe.

---

## 1. Ce qui manque

Un consultant ne gère pas des machines, il gère des **missions**. Certaines sont en cours,
d'autres attendent un feu vert, d'autres sont terminées. La vue d'ensemble livrée par F-49 ne
connaît qu'un état **technique** — connecté ou non — qui ne dit rien de l'état **métier** :

- un poste dont le runner est éteint peut être une mission **active** en pause ;
- un poste connecté peut être une mission **close** que personne n'a rangée.

F-60 ajoute au poste un **état de mission** — `en cours`, `en attente`, `clôturé` — **choisi par
l'utilisateur**, et le fait remonter partout où le poste apparaît.

## 2. Le piège, et comment on l'évite

Les postes ont **déjà** une couleur : l'identité visuelle de SF-49-03, dérivée du nom, qui répond à
*« chez quel client suis-je »*. La couleur de mission répond à une **autre** question : *« où en
est-on »*. Deux systèmes de couleur qui se disputent la même surface deviennent illisibles tous les
deux.

**Trois règles, non négociables, opposables en review :**

| # | Règle | Conséquence concrète |
|---|---|---|
| R1 | **Deux registres séparés** | L'identité prend la palette **§9** (dix tons dérivés du nom). La mission prend la palette de **statut §5** (vert / ambre / gris) — la même que « Connecté », « Actif ». Aucun ton de §9 ne qualifie jamais un état, aucune couleur de statut n'identifie jamais une machine. |
| R2 | **Une pastille à côté du nom, jamais un second aplat** | La mission entre par une **pastille** (`.badge` de la charte). Elle n'ajoute ni fond de carte, ni deuxième filet : le filet gauche reste **celui de l'identité**, et il reste seul. |
| R3 | **La couleur ne porte jamais seule l'information** | La pastille de mission **écrit toujours son libellé** (`En cours`, `En attente`, `Clôturé`). Une pastille muette, même colorée, est interdite. |

R3 est déjà la loi de `DESIGN_SYSTEM.md` §9 pour l'identité ; F-60 l'étend à la mission.

## 3. Ce que « ranger » veut dire

Un poste clôturé se **range**, il ne disparaît pas :

- il **quitte la vue principale** de `/postes` ;
- il **reste consultable** — un repli dépliable le rend visible en un geste ;
- son **historique n'est pas touché** : aucun journal effacé, aucun projet détaché ;
- **son runner n'est pas coupé** par ce seul changement d'état. Clôturer une mission n'est pas un
  coupe-circuit. Couper une machine reste un geste explicite et distinct (`POST /kill`).

## 4. Découpage

| SF | Titre | Portée |
|---|---|---|
| **SF-60-01** | L'état de mission, côté gateway | La colonne, l'énumération, l'endpoint de changement d'état, le champ rendu par la vue d'ensemble et par le détail du poste. Isolation `user_id`, et **rien d'autre n'est touché** — ni jeton, ni liaison, ni journal. |
| **SF-60-02** | L'état de mission se voit et se choisit | La pastille d'état, son choix depuis `/postes`, le rangement des clôturés, et le report de l'état dans la Forge (liste des projets, en-tête du terminal). |

Le backend d'abord : le contrat décide de ce que l'écran peut montrer et proposer.

## 5. Ce que F-60 ne fait pas

- **Des dates** — début, fin, échéance. Hors périmètre, dit par `PRODUCT_SPEC.md`.
- **Des jalons.** Un état à trois valeurs n'est pas un plan de charge.
- **De la facturation à la mission.** La consommation par client est **F-61**, et elle s'agrège
  déjà par poste sans avoir besoin d'un état.
- **Un état automatique.** Rien ne clôture une mission tout seul : ni l'inactivité, ni la
  déconnexion du runner. L'état est **déclaré**, jamais deviné — c'est précisément ce qui le
  distingue de l'état technique que F-49 montre déjà.
- **Un état sur le projet.** L'état vit sur le **poste** — c'est la mission chez un client, pas le
  dossier de travail.
