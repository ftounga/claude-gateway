# Mini-spec — F-142 / SF-142-15 — Les groupes imbriqués

## Identifiant
`F-142 / SF-142-15` — feature parente `F-142`

## Objectif
Qu'un VPC **contienne** ses sous-réseaux, qui **contiennent** leurs ressources — au lieu de trois
cadres posés côte à côte.

## Le constat, sur le rendu réel du 2026-09-27
Page « data-ingestion — 4 vues », version 2, vue réseau rendue par `engine=cloud` : les icônes AWS
officielles **sont là** (Lambda, EC2, EKS, Route 53, Transit Gateway, PrivateLink). Le schéma est
pourtant illisible.

Le cadre **« Sous-réseaux privés »** est à gauche. Le cadre **« VPC … 10.180.165.0/24 »** est en bas
à droite, et ne contient que Route 53. **Ce sont deux cadres frères.** Or un VPC contient ses
sous-réseaux : la topologie affichée est **fausse**.

**La cause est dans notre code**, pas dans le modèle : la `spec` de `cloud.py` n'a qu'**un seul
niveau** — chaque nœud porte un `group`, et `by_group` est une carte plate rendue par un `Cluster`
par clé. L'imbrication est **impossible à exprimer**. L'agent a fait ce qu'il pouvait.

**C'est le défaut structurel du rendu.** Les arêtes qui traversent le vide en sont la conséquence :
sans imbrication, graphviz étale tout et relie de loin.

## L'arbitrage, tranché ici
**Un `parent` sur le groupe, pas un groupe sur le nœud.** Le nœud continue de déclarer **un seul**
`group` ; c'est le **groupe** qui déclare son parent. Faire porter la hiérarchie par le nœud
obligerait à répéter le chemin sur chaque ressource, et deux nœuds du même sous-réseau pourraient
le décrire différemment.

**Rétrocompatible par construction** : un `groups` sans `parent` rend exactement ce qu'il rend
aujourd'hui.

## Comportement attendu
1. Un groupe peut déclarer `parent` : l'identifiant d'un autre groupe.
2. Le rendu imbrique les `Cluster` en conséquence, à **trois niveaux au moins** (VPC → sous-réseau →
   ressource).
3. Un `parent` inconnu est **refusé** avec un message nommé — un cadre orphelin dessinerait une
   topologie fausse, et un schéma faux est pire qu'un schéma absent.
4. Un **cycle** entre groupes est refusé.
5. Un `groups` sans `parent` rend **exactement** comme avant.

| Cas | Comportement |
|---|---|
| `parent` valide | cadres imbriqués |
| Aucun `parent` | rendu d'avant, à l'identique |
| `parent` inconnu | refus nommé |
| Cycle (`a`→`b`→`a`) | refus nommé |
| Profondeur > borne | refus nommé |

## Critères d'acceptation
- [ ] La vue réseau réelle, décrite avec `parent`, rend le VPC **contenant** les sous-réseaux.
- [ ] Trois niveaux d'imbrication fonctionnent.
- [ ] `parent` inconnu → refus nommé, pas un cadre orphelin.
- [ ] Cycle → refus nommé, jamais une boucle infinie.
- [ ] Une `spec` sans `parent` rend **octet pour octet** comme avant (non-régression).
- [ ] Le catalogue d'outil décrit `parent` en une phrase.

## Plan de test minimal
**Unitaires (renderer)** — imbrication à 2 et 3 niveaux · `parent` inconnu · cycle · profondeur
excessive · absence de `parent` (non-régression) · l'ordre de déclaration des groupes n'importe pas.
**Non-régression** — une `spec` plate rend le même fichier qu'avant.

## Technique
| Élément | Changement |
|---|---|
| `cloud.py` | `groups[].parent` ; arbre de clusters au lieu d'une carte plate ; refus nommés |
| `DiagramToolCatalog` | `parent` décrit dans la `spec` |

**Aucune migration, aucun endpoint, aucun composant nouveau.**

## Hors périmètre
Le vocabulaire des types (**SF-142-16**) · le placement et les arêtes longues (**SF-142-17**) ·
l'imbrication côté `drawio`, qui a sa propre mise en page (à traiter si le défaut s'y voit).
