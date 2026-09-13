# F-106 — La Vigie : l'espace du pilotage, à côté de la Forge

> Cadrage du 2026-09-13, sur proposition du PO. **Cadrage seul : la livraison attend le go du PO.**
> **Le nom « Vigie » est une proposition, à confirmer par le PO.**
> Liée à F-98 (Forge refondue), F-99 → F-105 (le Radar) et F-107 (l'offre par espace).

## 1. La proposition, dans les mots du PO

> « On pourrait avoir la Forge d'un côté, mais aussi un autre onglet où on a tout ça. On pourrait y
> importer des postes/clients qui existent déjà. Et la partie Radar y vit, le terminal conversation
> client y vit… En plus, ça permet d'avoir deux types de Gold : un Gold pour la Forge et un autre Gold
> pour lui. »

## 2. Pourquoi c'est la bonne structure

Le produit sert désormais **deux métiers** qui partagent des clients mais pas un geste :

| | La Forge | La Vigie |
|---|---|---|
| Le métier | **faire** : livrer sur l'infrastructure du client | **piloter** : suivre ce que l'organisation du client attend |
| La question | « qu'est-ce que je construis, et où en est la commande ? » | « qu'est-ce qu'on attend de moi, et où en est ce sujet ? » |
| Ce qui y vit | projets, terminaux de projet, terminal du poste, carte du poste (infra), gouvernance, Voir travailler | **Radar** (résumé, sujets, engagements), **terminal Teams** (conversation client), réunions et enregistrements, annuaire, Outlook |
| Le savoir accumulé | la **carte** : l'infrastructure | le **registre** : l'organisation |
| L'acheteur type | consultant infra, DevOps, sécurité | consultant en pilotage, chef de projet, PMO, manager |

Mettre le Radar dans un onglet de la Forge (ce que F-102 prévoyait) mélangeait les deux : un
consultant qui n'a que de l'organisationnel traverserait des projets vides et un terminal de code
pour lire ses relances, et un consultant infra verrait un onglet qu'il ne paie pas. **Deux espaces,
chacun complet pour son métier**, règlent les deux.

## 3. La décision structurante : un client, une machine, deux espaces

**On n'importe pas un poste dans la Vigie, on l'y active.** Le client (le poste) reste **une seule
entité** : une machine, un runner, un appairage, une place facturable (F-65). Les deux espaces sont
deux **regards** sur ce même client.

Pourquoi pas une copie :
- **Le runner est indispensable aux deux.** La Vigie lit Teams par le navigateur **de cette
  machine** (F-87) : une copie du poste voudrait dire un second appairage, ou deux entités qui
  divergent sur leur état de connexion.
- **Un client peut être dans les deux**, et c'est le cas le plus riche : un sujet du Radar
  (« migration DNS validée par Paul ») renvoie au projet de la Forge qui le porte, et inversement.
- **La clôture de mission (F-60) reste un geste unique** : elle ferme le client dans les deux espaces
  et purge son Radar (F-99).

| Geste | Effet |
|---|---|
| **Activer dans la Vigie** un client de la Forge | aucun appairage ; le Radar et le terminal Teams deviennent disponibles pour ce poste |
| **Connecter un client depuis la Vigie** (client sans aucun projet technique) | même parcours d'appairage que la Forge (F-72) ; le poste n'apparaît pas dans la Forge tant qu'on ne l'y active pas |
| **Activer dans la Forge** un client de la Vigie | aucun appairage ; projets et terminaux deviennent disponibles |
| **Retirer d'un espace** | le client disparaît de cet espace, **rien n'est supprimé** ailleurs ; retirer de la Vigie propose de purger son Radar |

Techniquement : une table `host_spaces (host_id, space, activated_at)`, `space ∈ {FORGE, VIGIE}`.
Migration : **tous les postes existants sont activés dans la Forge**, aucun dans la Vigie. Aucun
changement d'appairage, de jeton ni de runner.

## 4. La navigation

- La barre du haut porte **Forge** et **Vigie** côte à côte. Un espace non souscrit reste visible et
  ouvre une page de présentation avec l'essai (F-107), jamais une erreur.
- **La Vigie reprend la forme validée de la Forge refondue** (F-98) : colonne des clients à gauche,
  client ouvert à droite. Mêmes pastilles d'identité (§9), même statut daté (F-97). Onglets du client
  dans la Vigie : **Radar · Conversations · Réunions · Personnes**.
- **Bandeau de flotte** propre à la Vigie : « 3 relances dues · 2 sujets bloqués · synchro d'hier
  soir complète ».
- **Passerelles entre espaces**, seulement quand elles portent quelque chose : sur un sujet lié à un
  projet, « Voir le projet dans la Forge » ; sur un projet cité par un sujet, « 2 sujets dans la
  Vigie ». Le client ouvert reste le même en changeant d'espace.
- Routes : `/vigie`, `/vigie/:hostRef`, `?onglet=radar|conversations|reunions|personnes`,
  `/vigie/:hostRef/sujets/:id`.

## 5. Ce qui déménage

| Élément | Aujourd'hui | Demain |
|---|---|---|
| Terminal Teams (F-89) | ouvert depuis la carte du poste dans la Forge | onglet **Conversations** de la Vigie ; la Forge garde un lien « Ouvrir dans la Vigie » si le client y est activé |
| Liaison Teams (F-87) : état et réparation | carte du poste | en-tête du client dans la Vigie |
| Réunions, captures, enregistrements (F-90, F-91) | via le terminal Teams | onglet **Réunions** de la Vigie |
| Radar (F-102, F-103) | onglet prévu dans la Forge | onglets **Radar** et page sujet de la Vigie |

**Rien ne change pour le terminal de poste, les projets, la carte du poste ni la gouvernance** : ils
restent dans la Forge.

## 6. Le découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-106-01 | Les espaces d'un client | `host_spaces`, migration (tous les postes en FORGE), activer / retirer, clôture de mission commune, API filtrée par espace, isolation `user_id` |
| SF-106-02 | La Vigie, l'écran | Route `/vigie`, colonne et client ouvert sur le modèle de F-98, bandeau de flotte, activer un client de la Forge, connecter un client depuis la Vigie |
| SF-106-03 | Le déménagement de Teams | Terminal Teams, liaison et réunions déplacés dans la Vigie, lien depuis la Forge, redirection des anciens chemins |
| SF-106-04 | Les passerelles | Liens sujet ↔ projet, conservation du client ouvert en changeant d'espace |
| SF-106-05 | La page d'un espace non souscrit | Présentation, essai, sans erreur ni écran vide |

**Ordre** : après F-97 et F-98 (la forme maître–détail est posée par F-98 et réemployée ici). Le Radar
(F-102, F-103) se livre **directement dans la Vigie**.

## 7. Préoccupations transversales

- **Navigation / routing : oui.** Composants impactés : `app.routes.ts` (nouvelles routes `vigie`),
  barre de navigation, chemins d'ouverture du terminal Teams, fil d'Ariane F-68, redirections F-98.
- **Plans / limites : oui.** Chaque espace a son droit (F-107) ; `TeamsEntitlementService` et
  `AtelierEntitlementService` deviennent des droits **d'espace**. Composants listés en F-107.
- **Contexte tenant : oui.** `host_spaces` filtré par `user_id` du poste possédé ; un poste retiré
  d'un espace n'est plus lisible par les API de cet espace.
- **Auth / Principal : non.**

## 8. Hors périmètre

- Un troisième espace.
- Des espaces partagés entre plusieurs utilisateurs (V3, F-17).
- Déplacer la gouvernance « infra » dans la Vigie : elle reste le savoir de la Forge.
