# Mini-spec — F-142 / SF-142-07 — Les icônes cloud officielles, sans rien installer

## Identifiant
`F-142 / SF-142-07` — feature parente `F-142`

## Objectif
Qu'un schéma d'architecture porte les **icônes officielles** AWS / Azure / GCP / on-prem, rendu **par la
gateway**, sans que le poste du client installe `diagrams`, `graphviz` ou quoi que ce soit.

## Le défaut
SF-142-03 a livré les icônes officielles… **sur le poste** : `pip install diagrams` +
`apt-get install graphviz`, avec un repli vers Mermaid quand l'installation est bloquée. Sur un poste
de banque, l'installation **est** bloquée : le repli devient le cas nominal, et les icônes officielles
n'apparaissent jamais. SF-142-06 a déplacé Mermaid côté gateway ; il reste à y déplacer `diagrams`.

## La décision qui structure cette subfeature
**Le service n'exécute aucun code fourni.**

`diagrams` se pilote en écrivant du Python. Accepter ce Python, c'est **exécuter du code arbitraire
écrit par le modèle sur notre infrastructure** — un pod qui voit le réseau interne. Tant que ce code
tournait sur le poste du client, le risque restait dans son périmètre ; chez nous, il change de nature.

Le service accepte donc une **description déclarative** (JSON) et **génère lui-même** le Python. Le
modèle fournit des **données**, jamais du code. Gain secondaire : le même schéma se rend toujours
pareil.

**Ce que cela coûte, et qu'on assume** : on dessine ce que la description permet — groupes, nœuds
typés, liens orientés, libellés. Les exotismes de `diagrams` restent hors de portée ; les rouvrir
demanderait un bac à sable réseau dédié, et serait alors un choix explicite.

## Comportement attendu
1. L'outil `render_diagram` accepte un second moteur : **`cloud`** — une description, pas du Mermaid.
2. La description nomme des **nœuds typés** (`aws.rds`, `azure.aks`, `gcp.gke`, `onprem.postgresql`…),
   des **groupes** et des **liens** ; le service les rend avec les icônes officielles.
3. Un **type inconnu** est refusé **nommément**, avec les types proches — jamais une icône au hasard.
4. Le résultat suit le **même chemin** que Mermaid : déposé dans le projet, chemin rendu à l'agent.
5. Tout le reste est **inchangé** : bornes, échecs nommés, repli par la page.

| Cas d'erreur | Comportement |
|---|---|
| Type de nœud inconnu | refus nommé, avec des suggestions ; **rien n'est dessiné** |
| Lien vers un nœud non déclaré | refus nommé — un schéma faux est pire qu'un schéma absent |
| Description démesurée (nœuds, profondeur) | refus borné avant tout rendu |
| `graphviz` absent de l'image | le service le dit ; l'agent replie sur Mermaid (SF-142-06) |

## Critères d'acceptation
- [x] Une description déclarative rend un PNG avec les **icônes officielles**.
- [x] Les quatre familles sont servies : **aws**, **azure**, **gcp**, **onprem**.
- [x] **Aucun code fourni n'est exécuté** : le service ne lit que des données, et le prouve par test.
- [x] Un type inconnu, un lien pendant, une description démesurée sont **refusés nommément**.
- [x] Le dépôt et les messages sont **ceux de SF-142-06** — aucun chemin parallèle.
- [x] Le pod de rendu **ne peut pas sortir** du cluster ni joindre autre chose que ce qu'il doit.

## Hors scope
La bascule des skills (**SF-142-08**) · l'exécution de Python libre (**écartée**, voir ci-dessus) ·
les icônes hors des quatre familles.

## Technique
| Élément | Changement |
|---|---|
| `diagram-renderer/Dockerfile` | + `python3`, `graphviz`, `diagrams` (pip) |
| `diagram-renderer/cloud.py` *(nouveau)* | lit la description **validée**, construit le diagramme, rend le PNG |
| `diagram-renderer/server.js` | `engine: "cloud"` → passe la description à `cloud.py`, jamais un `exec` de code reçu |
| `DiagramRenderer` / `HttpDiagramRenderer` | le moteur voyage avec la demande (interface inchangée par ailleurs) |
| `DiagramToolCatalog` | l'outil décrit le second moteur et **ses types** |
| `k8s/base/diagram-renderer/networkpolicy.yaml` *(nouveau)* | **aucune sortie** hors DNS ; entrée depuis la gateway seulement |

**Bornes** : 60 nœuds · 12 groupes · 120 liens · profondeur de groupe 2.

## Plan de test
### Service de rendu — éprouvé sur l'image réelle
- [x] Une description AWS rend un PNG **1673×890** : CloudFront, ALB, ECS Fargate, RDS, S3, le VPC en
      cluster et les libellés de liens — les **vraies** icônes.
- [x] **Le catalogue est vérifié AU BUILD** : 74 types, tous importables. Une entrée qui ne désignerait
      aucune classe réelle fait échouer la construction de l'image, pas le premier schéma d'un client.
- [x] Type inconnu → **422** avec les **types proches** ; lien pendant → **422** nommé.
- [x] **Aucune exécution** : `__import__(1)` dans un libellé est **dessiné comme du texte** (PNG rendu),
      rien n'est exécuté.

### Gateway — `DiagramToolExecutorTest` (10) + `HttpDiagramRendererTest` (6)
- [x] `engine=cloud` appelle le bon moteur (et **pas** Mermaid), avec la description telle quelle.
- [x] Sans description, le refus **enseigne la forme attendue** ; aucun rendu tenté.
- [x] Un type inconnu remonte la raison **et** les types proches ; rien n'est déposé.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | Le pod de rendu reçoit désormais des descriptions qui viennent du modèle : une **NetworkPolicy** lui interdit toute sortie (hors DNS) et n'autorise l'entrée que depuis la gateway. Il ne porte aucun secret, aucun montage, aucun compte de service privilégié. |
| Contexte tenant | non | le service ne connaît ni compte ni poste ; le dépôt reste celui du tour |
| Plans / limites | non | aucun appel fournisseur, aucun jeton |
| Navigation / routing | non | aucun écran |
