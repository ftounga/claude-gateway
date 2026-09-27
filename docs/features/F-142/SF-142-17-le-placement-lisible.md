# Mini-spec — F-142 / SF-142-17 — Le placement lisible

## Identifiant
`F-142 / SF-142-17` — feature parente `F-142`

## Objectif
Qu'un schéma d'architecture se lise sans suivre une flèche à travers un demi-mètre de vide.

## Le constat, sur le rendu réel du 2026-09-27
Vue réseau : **3979 × 3131 pixels** pour une quinzaine de composants, un immense vide au centre, et
des arêtes horizontales interminables dont les étiquettes — « objets », « images », « files »,
« Snowflake », « webserver PRIVATE_ONLY » — flottent au milieu de nulle part, sans qu'on voie leur
origine ni leur destination.

**Dépend de SF-142-15** : une partie de l'étalement vient de l'absence d'imbrication. Cette
subfeature traite ce qui restera **après** cette correction, et doit donc être mesurée après elle.

## L'arbitrage, tranché ici
**Régler graphviz, ne pas placer à sa place.** Écrire notre propre moteur de placement pour
`cloud.py` serait refaire ce que graphviz fait bien, et introduire un second moteur à maintenir à
côté de celui de `drawio`. On agit sur les **attributs** — rangs, séparations, contrainte d'arête,
longueur minimale — pas sur les coordonnées.

**Une borne de taille, pas un recadrage.** Au-delà d'une dimension, on **dit** que le schéma est
trop dense plutôt que de produire une image qu'aucun écran n'affiche.

## Comportement attendu
1. Les séparations de rang et de nœud sont réglées pour un rendu **compact**, pas étalé.
2. Une arête dont les extrémités sont éloignées ne **traverse plus le schéma** sans point d'appui.
3. Les étiquettes d'arête restent **près de leur arête**.
4. Au-delà d'une borne de largeur ou de hauteur, l'avertissement le **dit** à l'agent.
5. Un schéma simple rend **au moins aussi bien** qu'avant — non-régression visuelle.

## Critères d'acceptation
- [ ] La vue réseau réelle, avec l'imbrication de SF-142-15, rend **au plus la moitié** de la surface
      actuelle (3979 × 3131) — mesuré sur les pixels.
- [ ] Aucune arête ne traverse le schéma de bord à bord sans point d'appui.
- [ ] Un schéma de trois nœuds rend une image de taille comparable à aujourd'hui.
- [ ] Un schéma trop dense produit un avertissement nommé.

## Plan de test minimal
**Unitaires (renderer)** — les dimensions du rendu de la vue réseau réelle, avant/après · un schéma
minimal n'enfle pas · l'avertissement de densité se déclenche à la borne.
**Éprouvé sur pièce** — le rendu réel est regardé, pas seulement mesuré : une image peut être petite
et illisible.

## Technique
| Élément | Changement |
|---|---|
| `cloud.py` | `graph_attr` / `edge_attr` : `ranksep`, `nodesep`, `concentrate`, `minlen`, `splines` ; borne de dimension |

**Aucune migration.**

## Hors périmètre
Un moteur de placement maison · l'imbrication (**SF-142-15**, prérequis) · le vocabulaire
(**SF-142-16**) · la mise en page de `drawio`, qui est déjà déterministe et maison.
