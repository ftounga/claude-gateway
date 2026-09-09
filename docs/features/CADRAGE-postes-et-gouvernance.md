# Cadrage — Le poste comme unité, et la gouvernance au catalogue

> Demande du PO, 2026-09-10. Document de **cadrage** : il arrête le vocabulaire, le modèle et
> l'ordre. Aucun développement ne démarre avant son arbitrage.

---

## 1. Ce qui déclenche ce cadrage

Deux jours de mise en service chez un client ont montré la même chose sous trois angles :
l'appairage est lourd, et il est lourd **par projet**. Or un développeur n'a pas un projet, il a
**une machine** avec un dossier de travail dessous. Aujourd'hui, chaque dossier exige son code
d'appairage, son runner, sa connexion — pour la même machine, le même utilisateur, la même racine.

Le PO demande donc de déplacer l'unité : *« au lieu d'avoir un dossier, on aura le nom du client, et
c'est ça qu'on va configurer et synchroniser. Ce dossier peut correspondre au dossier `dev` chez le
client. Le runner sera lancé à cet endroit. »*

S'y ajoutent deux demandes qui en découlent : une **vue d'ensemble** de tous les terminaux
connectés, et une **gouvernance** que chacun compose depuis un catalogue.

---

## 2. Vocabulaire proposé

| Terme | Définition | Pourquoi ce mot |
|---|---|---|
| **Poste** | Une machine connectée, avec **une racine** (ex. `~/dev`) et **un runner**. Appairé **une seule fois**. | « Client » désigne déjà l'utilisateur qui paie ; « machine » est juste mais froid ; « poste » dit l'endroit où l'on travaille |
| **Projet** | Un dossier **sous la racine du poste**. N'a plus ni runner ni appairage propres. | Inchangé pour l'utilisateur : c'est ce qu'il ouvre dans l'Atelier |
| **Racine** | Le dossier déclaré par le runner au démarrage, jamais deviné par la gateway | Reprend la décision de SF-38-15 |

**Un poste appartient à un seul utilisateur.** Ce n'est **pas** F-17 (espaces d'équipe, V3) : rien
n'est partagé entre comptes, l'isolation `user_id` reste la règle sur tous les accès.

---

## 3. Ce que le changement supprime

C'est le gain, et il est net :

- **un seul code d'appairage** par machine, au lieu d'un par dossier ;
- **un seul runner** lancé, au lieu d'un par projet — donc une seule fenêtre de terminal à laisser
  ouverte, un seul proxy à configurer, un seul paquet à installer ;
- **ouvrir un projet devient gratuit** : un sous-dossier de la racine est immédiatement disponible,
  sans rien réinstaller. C'est exactement le geste qui manquait au client.

Et il prolonge F-46 (reprise sans argument) : la mémoire de session devient celle du **poste**, donc
un `claude-runner` relancé retrouve toute l'arborescence, pas un seul projet.

---

## 4. Le point dur : le confinement

Aujourd'hui, `PathGuard` confine le runner à la racine du projet, et c'est **cette** racine qui borne
tout : outils fichiers, `cwd` des commandes, exclusions de secrets. Si la racine devient le dossier
du poste, le confinement s'élargit d'autant — et un agent travaillant sur le projet A pourrait lire,
voire écrire, dans le projet B.

**Deux régimes possibles, à trancher :**

| | Régime | Conséquence |
|---|---|---|
| **A** | Le runner reste confiné à la **racine du poste** ; chaque tour est borné au **sous-dossier du projet** par la gateway | Simple côté runner, mais le confinement devient une garantie **de la gateway**, pas du processus local — un défaut de la gateway ouvre toute la racine |
| **B** | Le runner reçoit la racine du poste **et** le sous-dossier du tour, et `PathGuard` borne au sous-dossier à chaque appel | Le confinement reste **local**, comme aujourd'hui ; coût : le protocole porte une racine par appel |

**Recommandation : B.** Le confinement est la promesse centrale du mode runner ; la déplacer dans la
gateway, c'est la rendre dépendante d'un composant réseau. B conserve la propriété « un processus
local refuse lui-même de sortir », qui est ce qui rend le runner acceptable sur un poste
d'entreprise. Le surcoût est une valeur de plus dans une trame déjà existante.

**Cas explicitement autorisé** : un projet peut vouloir lire un autre projet (monorepo, dépendance
locale). Ce sera un réglage **par projet**, jamais le défaut.

---

## 5. Modèle de données

**Nouvelle table `runner_hosts`** — le poste : `id`, `user_id`, `nom`, `racine` (déclarée par le
runner), `os`, `shell`, `elevated`, `derniere_connexion`. Les colonnes `runner_*` de `workspaces`
(migrations 052, 053, 063) y **déménagent**.

**`workspaces`** gagne `host_id` (nullable) et `chemin_relatif` (le sous-dossier sous la racine).

**Les trois tables runner** (`runner_tokens`, `runner_pairing_codes`, `runner_audit`) passent de
`workspace_id` à `host_id`. `runner_audit` **conserve** en plus le projet concerné : le journal doit
continuer de dire *quel projet* a exécuté quoi.

**`RunnerIdentity`** devient `(tokenId, userId, hostId)`, et le projet voyage **par appel**.

**Migration des données existantes — écartée le 2026-09-10 par le PO** : *« pour les clients
existants on invalidera leur jeton. Fais comme si tu n'avais pas de contrainte. »* Les projets de
test (`cagip`, `cagip2`, `cagip3`) sont supprimés, les jetons runner invalidés, et le modèle est
écrit **sans compromis de compatibilité**. C'est un allègement considérable : pas de reprise de
données, pas de double lecture, pas de colonne de transition. Le seul coût est un ré-appairage —
assumé, puisqu'il ne concerne qu'un poste de test.

---

## 6. La vue d'ensemble (« vue 360 »)

Un écran qui montre **tous les postes** de l'utilisateur : connecté ou non, depuis quand, quel
système, quel interpréteur élu, sous quels droits, quels projets vivent dessous, et **ce qui tourne
en ce moment** sur chacun.

Ce que le produit sait déjà : présence (`RunnerRegistry`), interpréteur (`workspaces.runner_shell`,
SF-38-27), droits (`runner_elevated`, SF-38-18), journal (`runner_audit`), tours en cours.

**Décision à prendre** : cet écran affiche-t-il des **terminaux vivants** (plusieurs flux ouverts en
parallèle) ou une **vue d'état** rafraîchie ? Recommandation : **vue d'état** d'abord, avec accès en
un clic au terminal d'un projet. Plusieurs flux SSE simultanés multiplient les tours facturés et le
coût, pour un bénéfice de surveillance que l'état couvre déjà.

---

## 7. La gouvernance au catalogue

Reprise du prompt de gouvernance du PO, **élagué de tout ce qui relève de l'organisation de son
poste personnel** (`~/dev/repos` vs `~/dev/infra`, `~/poste/`, `~/methodo/`). Ce qui est retenu :

- **le principe** : le travail est jetable, le savoir est durable ;
- **les trois niveaux** : la convention (consigne système, déjà en place), le **verrou déterministe**
  (à créer), le **filet sémantique** (à créer) ;
- **la règle des livrables** : rien de ce qui sort ne doit suggérer un LLM — et une vérification
  **mécanique** sur les messages de commit, pas seulement une consigne ;
- **la promotion avec dette bloquante** : un élément durable découvert rejoint la carte du projet ;
  une case non cochée bloque la clôture ;
- **les gabarits** (`STATE.md`, `PLAN-ACTION.md`) et **les skills** (`/explique`,
  `/plan-dashboard`), simplement **déposés** par un paquet : le produit lit déjà `.claude/skills/`.

**Un catalogue, deux étages** : des paquets **publiés par l'admin** (`ntounga@gmail.com`), et un
**catalogue personnel** où chaque utilisateur compose sa sélection et l'active **par projet ou par
poste**. Rien n'est imposé à personne.

**Ce qui manque au produit pour que tout cela existe** : des **points de contrôle** dans la boucle —
un crochet après chaque écriture de fichier, un en fin de tour — dont le résultat peut **bloquer** et
revenir au modèle, comme le fait déjà la porte de confirmation. Sans eux, il ne reste que le niveau 1.

---

## 8. Ordre proposé

| Rang | Chantier | Pourquoi ce rang |
|---|---|---|
| 1 | **Le poste** — modèle, migration, appairage unique, confinement par projet (régime B) | Tout le reste s'y adosse. Le faire après coûterait une seconde migration |
| 2 | **La vue d'ensemble** | Devient utile dès qu'il y a plusieurs projets sous un poste |
| 3 | **Les points de contrôle** de la boucle | Prérequis de toute gouvernance active |
| 4 | **Le catalogue** — paquets admin, catalogue personnel, activation | Porte la restriction admin et la composition par l'utilisateur |
| 5 | **Le premier paquet** — livrables sans trace de LLM, promotion, juge de fin de tour | Se pose sur les quatre précédents |

---

## 9. Risques et points de vigilance

- **Ampleur** : 51 fichiers backend touchent `workspaceId`, `RunnerIdentity` change de forme, trois
  tables changent de clé. C'est le plus gros changement structurel depuis la création du mode runner.
- ~~Un client est déjà installé~~ — **levé** : le PO a tranché pour la table rase (jetons invalidés,
  projets de test supprimés). Le modèle s'écrit sans dette de compatibilité.
- **Le confinement est une promesse de sécurité** : le régime A l'affaiblit. Le trancher à la légère
  serait le seul vrai danger de ce chantier.
- **Multi-pods** : `RunnerRegistry`, le relais inter-pods et la porte de confirmation sont indexés
  par workspace (SF-38-12/13). Ils suivent le poste, sinon le routage casse sous HPA.
- **Facturation** : inchangée — elle porte sur l'utilisateur, pas sur le poste.
- **F-17 (V3)** reste hors périmètre : un poste appartient à un seul compte.

---

## 10. Arbitrages — tranchés le 2026-09-10

**Décision structurante du PO** : *« chaque dossier doit avoir sa propre conversation »*. C'est ce
qui impose l'entité, et donc le changement de modèle. La voie légère — un seul projet racine, les
dossiers réduits à des emplacements — est **écartée** : elle donnait l'appairage unique sans donner
les conversations séparées.

| # | Sujet | Décision | Motif |
|---|---|---|---|
| 1 | Le mot | **Poste** (l'entité), **librement nommé** par l'utilisateur | « Client » est juste pour un consultant qui installe chez des clients, mais ambigu dans le produit où le client est l'utilisateur qui paie. Le libellé reste libre : rien n'empêche de nommer un poste « CAGIP » |
| 2 | Confinement | **Régime B — local** | Le refus de sortir doit rester la propriété du processus qui exécute. Le déplacer dans la gateway ferait dépendre une garantie de sécurité d'un composant réseau |
| 3 | Vue d'ensemble | **Vue d'état** d'abord, terminal d'un projet à un clic | Plusieurs flux vivants multiplient les tours facturés pour un bénéfice que l'état couvre |
| 4 | Catalogue | **Deux étages** : paquets publiés par l'admin (`ntounga@gmail.com`), composés et activés par chaque utilisateur | Reprend la demande initiale (admin) et son amendement (catalogue personnel) sans créer de partage entre comptes — F-17 reste hors périmètre |
| 5 | Ordre | **Le poste d'abord** | Bâtir la gouvernance sur le modèle actuel imposerait de la refaire : elle s'active par projet, et le projet change de nature |

**Ces cinq décisions sont réversibles sauf la n° 2**, qui engage la sécurité : elle ne sera pas
rouverte sans un motif écrit.
