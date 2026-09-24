# Mini-spec — F-157 / SF-157-02 — Lire le dépôt de l'application

## Identifiant
`F-157 / SF-157-02` — feature parente `F-157` — dépend de **SF-157-01**

## Objectif
Lire les fichiers déclarés par la carte **dans le dépôt de l'application** — et **refuser de lire**
quand le projet désigné n'est pas ce dépôt.

## La condition, et pourquoi elle est la moitié du travail
Le backend tourne en conteneur, **sans ses sources**. Le seul chemin honnête est un **terminal dont
le projet est le dépôt de l'application**, désigné par l'administrateur.

**Un projet désigné par erreur ferait lire un code sans rapport et conclure n'importe quoi.** La
reconnaissance n'est donc pas un confort : c'est ce qui empêche le diagnostic de raconter des
bêtises avec assurance.

## Comment on reconnaît le dépôt
Par **la carte elle-même** : les chemins qu'elle déclare doivent se trouver dans le projet. Pas un
fichier marqueur qu'on pourrait poser n'importe où — **les fichiers que le diagnostic va justement
lire**. Si la majorité manque, ce n'est pas le dépôt.

Le seuil est **la majorité des chemins distincts**, pas la totalité : une branche en cours peut
avoir déplacé un fichier, et refuser pour autant rendrait la feature inutilisable le jour où elle
sert le plus.

## Comportement attendu
1. On lit **là où vit le projet** — poste ou hébergé — par le chemin existant (`ProjectFileRead`),
   sans nouveau protocole runner.
2. **La reconnaissance d'abord** : les chemins distincts de la carte sont sondés ; si moins de la
   moitié répond, on **refuse** en le disant, et **rien d'autre n'est lu**.
3. La lecture est **bornée** : seuls les chemins **déclarés par la carte**, un plafond d'octets par
   fichier, un plafond de fichiers. *Un diagnostic qui lit 4 000 fichiers coûterait plus cher que ce
   qu'il ferait économiser — le défaut même qu'il traque.*
4. Un fichier illisible n'interrompt rien : il est **noté absent**, et la lecture continue.
5. Le résultat dit, pour chaque chemin : **lu** (avec son contenu) ou **absent** (avec la raison).

| Cas d'erreur | Comportement |
|---|---|
| Projet non possédé | **404** (`requireOwned` d'abord) |
| Projet qui n'est pas le dépôt | refus **nommé**, aucune autre lecture |
| Fichier trop lourd | noté absent, borne dite ; la lecture continue |
| Runner injoignable | noté absent ; le diagnostic reste possible sans ce fichier |

## Critères d'acceptation
- [ ] La lecture passe par `ProjectFileRead` — **aucun nouveau chemin d'accès au poste**.
- [ ] La reconnaissance échoue → **refus nommé**, et **aucun fichier** n'est lu ensuite.
- [ ] La reconnaissance réussit avec la **majorité** des chemins, pas la totalité.
- [ ] Seuls les chemins **de la carte** sont lus ; jamais un chemin venu d'ailleurs.
- [ ] Bornes respectées : octets par fichier, nombre de fichiers.
- [ ] Un fichier illisible **n'interrompt pas** la lecture.
- [ ] **ISOLATION** : `requireOwned` en premier ; la lecture porte le `userId` du tour.

## Hors scope
Le **verdict débranchée** (**SF-157-03**) · la **lecture raisonnée** (**SF-157-04**) · l'**écran**
(**SF-157-05**) · toute écriture · la lecture d'un chemin non déclaré.

## Technique
| Élément | Changement |
|---|---|
| `SourceReader` | reconnaître le dépôt, lire les chemins de la carte, borner |
| `SourceRead` (record) | par chemin : lu / absent, contenu, raison |
| `RepositoryNotRecognizedException` | le refus nommé |

**Aucune migration, aucune route, aucun appel fournisseur.**

## Plan de test
- [ ] Reconnaissance : majorité présente → accepté ; minorité → **refus**, aucune lecture ensuite.
- [ ] Seuls les chemins de la carte sont demandés (vérifié par les appels).
- [ ] Fichier trop lourd / illisible → noté absent, lecture poursuivie.
- [ ] Borne du nombre de fichiers respectée.
- [ ] **ISOLATION** : `requireOwned` appelé en premier ; projet d'autrui → 404, aucune lecture.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint ici — la garde admin arrive en SF-157-05 |
| **Contexte tenant** | **oui** | `SourceReader` appelle `WorkspaceService.requireOwned` **en premier**, puis `ProjectFileRead.read(userId, workspace, …)` — le chemin de lecture **existant**, déjà isolé, utilisé par les outils de dépôt d'images et de présentations. Aucun nouveau chemin d'accès au poste. |
| Plans / limites | non | aucun appel fournisseur ; la lecture est locale au projet |
| Navigation / routing | non | aucune route |
