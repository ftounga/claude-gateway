# Mini-spec — F-142 / SF-142-09 — Aucun schéma ne doit échouer faute d'une icône

## Identifiant
`F-142 / SF-142-09` — feature parente `F-142`

## Objectif
Qu'un schéma se dessine **toujours**, même quand un composant n'a pas d'icône officielle — et qu'on
sache **lesquels** n'en avaient pas.

## Le défaut — constaté en production, en deux temps
> PO, 2026-09-24, projet AGENOR : *« il dit que la NAT privée et la transit gateway n'ont pas d'icône
> officielle. Il faudrait une solution de repli dans ce cas. »*

Deux problèmes, et le premier n'est pas celui qu'on croit :

1. **Le catalogue était trop étroit.** `NATGateway`, `TransitGateway`, `PrivateSubnet`, `PublicSubnet`,
   `InternetGateway`, `DirectConnect`, `VpnGateway`… **existent** dans la bibliothèque. Les 74 types
   de SF-142-07 avaient été choisis à la main : ce qui manquait, c'est le choix, pas l'icône.
2. **Un type inconnu faisait échouer tout le schéma.** Refuser est juste quand on peut corriger ; ça ne
   l'est plus quand le composant n'a, réellement, aucune icône — le livrable se retrouve sans schéma.

Puis, quelques minutes plus tard, le vrai coup de semonce :

> *« Le groupe réseau entreprise est représenté par un poste client. Vraiment toutes ces choses doivent
> avoir des replis. C'est inadmissible. Ça ne devrait pas être si compliqué que ça. »*

Faute de trouver le bon type, l'agent avait pris **celui qui ressemblait** — un composant **faux** dans
un livrable client, exactement ce que F-142 existe pour empêcher. Et le PO a raison sur la cause : la
complexité venait de moi. Un catalogue de 74 types **écrits à la main** condamnait l'agent à
approximer, alors que la bibliothèque en expose plus de mille.

## Comportement attendu
1. **Plus de catalogue à tenir** : les icônes sont **résolues dans la bibliothèque** — `aws.natgateway`
   cherche la classe `NATGateway` parmi les modules `diagrams.aws`. **1382 icônes atteignables**.
2. Un type **vraiment** inconnu ne fait plus échouer le schéma : il devient un **nœud générique neutre**
   portant son libellé.
3. La réponse **dit lesquels** ont été rendus sans icône officielle, pour que l'agent puisse le signaler.
4. **Jamais une icône approchante.** Une boîte neutre est honnête ; mettre une icône « qui ressemble »
   mettrait un composant faux dans un livrable client — c'est le contraire de ce que F-142 protège.
5. Le refus **reste disponible** (`strict`) pour qui veut échouer plutôt que livrer un schéma partiel.

| Cas d'erreur | Comportement |
|---|---|
| Type inconnu, mode par défaut | nœud générique + **avertissement** nommant le type |
| Type inconnu, `strict: true` | refus nommé, comme aujourd'hui |
| Famille inconnue (`foo.bar`) | nœud générique + avertissement ; les familles connues sont rappelées |

## Critères d'acceptation
- [x] `aws.natgateway`, `aws.transitgateway`, `aws.privatesubnet`, `aws.publicsubnet`,
      `aws.internetgateway`, `aws.directconnect`, `aws.vpngateway` rendent leur **icône officielle**.
- [x] Un type inconnu rend un **nœud générique**, et le schéma est produit.
- [x] La réponse de l'outil **nomme** les types rendus sans icône officielle.
- [x] `strict: true` refuse comme avant.
- [x] Le catalogue reste **vérifié au build**.

## Hors scope
Les icônes d'autres fournisseurs que ceux des quatre familles · un catalogue exhaustif de toutes les
classes de la bibliothèque (on couvre ce qui sert aux schémas d'architecture).

## Technique
| Élément | Changement |
|---|---|
| `diagram-renderer/cloud.py` | **résolution automatique** (famille + nom normalisé) ; repli générique ; liste des types repliés |
| `DiagramToolCatalog` | le guide **interdit la substitution** d'icône, et rappelle qu'un groupe n'est pas un nœud |
| `diagram-renderer/server.js` | l'avertissement voyage en en-tête `X-Cg-Unknown-Types` |
| `HttpDiagramRenderer` | lit l'en-tête et le porte dans le résultat |
| `DiagramToolExecutor` | le dit dans la réponse à l'agent |

## Plan de test — éprouvé sur le cas réel du PO
- [x] **Le schéma qui échouait passe** : Direct Connect, Transit Gateway, Subnet privé, NAT Gateway,
      EKS — toutes leurs icônes officielles, et le **réseau entreprise** est un vrai **groupe** (les
      postes sont dedans, il n'est plus « représenté par » un poste).
- [x] Un type inexistant (`aws.machin-qui-nexiste-pas`) : **image produite**, boîte neutre, et
      l'en-tête `X-Cg-Unknown-Types` le nomme.
- [x] L'avertissement remonte jusqu'à la **réponse de l'outil** (`DiagramToolExecutorTest`, 11 verts).
- [x] La résolution est **vérifiée au build** : 30 types de contrôle, dont les sept qui manquaient.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | — |
| Plans / limites | non | — |
| Navigation / routing | non | — |
