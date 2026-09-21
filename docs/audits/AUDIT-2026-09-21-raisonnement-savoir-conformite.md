# Audit — Le raisonnement, le savoir et la conformité

> Demandé par le PO le 2026-09-21 : *« Est-ce qu'il est possible d'augmenter encore la capacité de
> l'application si on se borne à mon domaine métier — l'infrastructure, l'architecture, la sécurité ?
> […] Je voudrais que plus je fasse des requêtes sur l'application, plus l'application aille vite à
> la résolution des problèmes, parce qu'elle connaîtrait déjà un peu plus l'architecture. »*
>
> **Périmètre** : le raisonnement de l'agent dans l'infrastructure d'un client. Le graphisme est
> explicitement hors sujet. La conformité est auditée, mais ses chantiers sont présentés à part.
>
> **Méthode** : lecture du code (références `fichier:ligne`) et **mesures sur la base de
> production**, 30 jours glissants au 2026-09-20.

---

## 0. Verdict

**Le mécanisme d'apprentissage existe, il fonctionne, et il ne sert presque pas.**

L'application sait déjà accumuler du savoir sur l'infrastructure d'un client : la carte du poste
(F-92) porte **2 593 faits** chez CAGIP, contre 516 six jours plus tôt — elle a été **multipliée par
cinq en six jours**. Ce n'est pas un prototype : c'est une base de connaissance vivante, datée,
sourcée, organisée exactement selon le métier du PO (accès, réseau, plateformes, données,
exploitation).

Mais trois choses l'empêchent de produire l'effet attendu :

| # | Constat mesuré | Effet |
|---|---|---|
| **1** | Le savoir est lu **~1 400 fois par la gateway pour être jugé**, et **une vingtaine de fois par l'agent pour s'en servir**. | L'application vérifie qu'on remplit le carnet ; elle ne le lit pas pour réfléchir. |
| **2** | La carte n'entre **jamais** dans la consigne système. `buildSystemPrompt` injecte `CLAUDE.md`, les règles de gouvernance et les skills — **pas un octet de carte**. | À chaque tour, l'agent repart de zéro sur l'infrastructure du client. |
| **3** | **Un poste sur quatre** capitalise. CAGIP oui ; EDENRED a les règles sans les fichiers ; FREE et Richemont n'ont rien. | Les trois quarts des missions n'apprennent rien. |

**Réponse à la question posée** : oui, il est possible d'augmenter nettement la capacité, et le
levier n'est pas un nouveau moteur de raisonnement — c'est de **faire entrer dans le contexte le
savoir qu'on accumule déjà**. C'est peu de code, et c'est devenu quasiment gratuit depuis F-134 : un
bloc stable placé en tête du prompt est **relu depuis le cache à 0,50 $/M** au lieu d'être
redécouvert par des tours d'exploration facturés plein tarif.

---

## 1. Ce qui est déjà bon — et qu'il ne faut pas toucher

Dit franchement, parce que la question « est-ce que c'est déjà bon ? » mérite une réponse honnête.

| Domaine | État |
|---|---|
| **Boucle de raisonnement** | Raisonnement signé réémis entre itérations, ordre `reasoning → text → tool_use → results` respecté, `effort` et `thinking:{adaptive}` correctement mappés, appariement `tool_use`/`tool_result` strict. L'audit de parité de F-121 conclut : *« le raisonnement de base est bon »*. |
| **Coût** | F-134 a porté la part relue de 16-23 % à **99 %** et divisé le coût d'un tour par ~16. Le préfixe est stable, donc **l'ajout de contexte durable y est désormais bon marché** — ce qui change l'arbitrage de tout cet audit. |
| **Discipline** | Cinq doctrines cumulées en tête du prompt : investigation (F-119), retenue (F-120), style (F-121), carte silencieuse (F-125), réponse essentielle (F-126). |
| **Permissions** | `allow / ask / deny` par outil et par préfixe de commande, **persistées** (F-121 / SF-121-02) — modèle de Claude Code, déjà livré. |
| **Contrôles de fin de tour** | Juge indépendant (F-94), intégrité du poste (F-95), commit sans trace de modèle (F-52) : trois verrous, dont un sémantique. |
| **Traçabilité** | Chaque appel d'outil journalisé : qui, quel poste, quel projet, quel outil, quelle cible, quel résultat, durée, octets. **Jamais de contenu.** |

Le fil « parité Claude Code » est déjà cadré (`docs/features/F-121/`, 20 écarts référencés). **Rien
dans cet audit ne le remplace** : les deux avancent en parallèle. F-121 rend l'agent meilleur *en
général* ; cet audit le rend meilleur *chez vos clients*.

---

## 2. Les mesures

### 2.1 Ce que l'agent fait vraiment (30 jours, production)

| Outil | Appels | Lecture |
|---|---|---|
| `governance_map_read` | **1 416** | **Lectures de carte faites par la gateway** (juge de fin de tour, contrôle d'intégrité, écran « ce que la machine sait ») — **pas** par l'agent |
| `bash` | 1 043 | Le vrai outil de travail, conforme à l'usage mesuré (95 % bash) |
| `screen_list_files` / `screen_list_folders` | 621 | Exploration d'arborescence |
| `bootstrap` | 225 | Amorçage de tour |
| `read_file` | 219 | Lectures ciblées |
| `edit_file` / `write_file` | 172 | Mutations |
| `grep` | 41 | Recherche (livré par F-121 / SF-121-01) |

Sur la même période : **271 messages utilisateur**, 243 réponses.

### 2.2 Le savoir : beaucoup écrit, presque jamais lu pour réfléchir

Ce que l'agent a réellement ouvert de la mémoire, en 30 jours :

| Fichier ouvert par l'agent | Appels | Portée |
|---|---|---|
| `PLAN-ACTION.md` | 47 | **Projet** — la mémoire du sujet en cours |
| `STATE.md` | 22 | **Projet** — le brouillon jetable |
| `acces.md` | 8 | **Poste** — le savoir transversal du client |
| `README.md` | 4 | Poste |
| `exploitation.md` | 4 | Poste |
| `reseau.md`, `plateformes.md`, `donnees.md` | ~0 | Poste |

**C'est le constat central.** L'agent lit la mémoire du *sujet* (69 fois) et ignore la mémoire du
*client* (~16 fois). Or c'est exactement l'inverse de ce que demande le PO : la connaissance qui
fait gagner du temps d'une mission à l'autre, c'est celle du **poste**.

Et ce n'est pas un défaut de zèle du modèle : **rien ne la lui met sous les yeux**. Le prompt système
ne la contient pas, la liste des fichiers de la racine ne lui est pas donnée, et les règles de
gouvernance lui disent d'**écrire** la carte bien plus qu'elles ne lui disent de la **lire**.

### 2.3 La carte, en volume

Poste CAGIP, faits comptés par `GovernanceMapDigest` (une ligne porteuse = un fait) :

| Fichier | Faits le 14/09 | Faits le 20/09 | Croissance |
|---|---|---|---|
| `plateformes.md` | 69 | **733** | ×10,6 |
| `acces.md` | 186 | **641** | ×3,4 |
| `exploitation.md` | 74 | **454** | ×6,1 |
| `README.md` | 81 | **299** | ×3,7 |
| `reseau.md` | 47 | **249** | ×5,3 |
| `donnees.md` | 59 | **217** | ×3,7 |
| **Total** | **516** | **2 593** | **×5,0** |

2 593 faits représentent de l'ordre de **200 à 300 Ko** : dix fois la place disponible dans la
consigne système (`SYSTEM_MAX_CHARS = 40 000`, `AtelierChatService.java:176`). **On ne peut donc pas
« injecter la carte ».** C'est le vrai problème d'ingénierie à résoudre, et il a une solution déjà à
moitié écrite : `GovernanceMapDigest` produit déjà, pour l'écran, un **sommaire par sections avec le
nombre de faits** — quelques kilo-octets, stables, donc cachés.

### 2.4 La couverture : un poste sur quatre

| Poste | Règles injectées | Fichiers déposés | Faits |
|---|---|---|---|
| **CAGIP** | oui | 16 | 2 593 |
| **EDENRED** | oui | **0** — activation jamais appliquée | 0 |
| **FREE** | non | 0 | 0 |
| **Richemont** | non | 0 | 0 |

EDENRED est le cas le plus gênant : l'agent y reçoit une doctrine qui lui parle d'une carte **qui
n'existe pas sur la machine**. `activeOn` ne filtre pas sur le statut de l'activation
(`GovernanceActivationService.java:216`).

---

## 3. Le raisonnement métier

### 3.1 Le rôle déclaré ne correspond pas au métier exercé

> *« Tu es un assistant de développement qui travaille sur le projet de l'utilisateur, sur sa
> machine. »* — `AtelierChatService.java:3644`

C'est la **première phrase** que lit le modèle, à chaque tour, sur les deux cibles. Or le travail
réel est de l'architecture, du réseau, de la sécurité et de l'exploitation chez des grands comptes.
Les réflexes d'un développeur (lire le code, modifier, résumer le diff) ne sont pas ceux d'un
architecte d'infrastructure :

| Un développeur | Un architecte / ingénieur infra |
|---|---|
| lit le fichier avant d'affirmer | **constate l'état réel** avant d'affirmer : version installée, certificat, droit effectif, route, quota |
| prouve par un test qui passe | prouve par une **commande de constat** datée et rejouable, et distingue *ce qu'il a vu* de *ce qu'on lui a dit* |
| livre un diff | livre une **note d'état** opposable : ce qui est, depuis quand, d'où ça vient |
| modifie et résume | ne modifie **rien** sans constat préalable, parce que la machine est celle d'un client |

Rien de tout cela n'est dit au modèle aujourd'hui. Le paragraphe de discipline (F-119) dit *comment*
vérifier ; il ne dit pas **ce qui vaut preuve dans ce métier**.

### 3.2 Le véhicule existe déjà, et il est vide

Le catalogue de gouvernance (F-51) sait déjà : porter des **règles injectées** dans la consigne
système, **déposer des fichiers** sur la machine, et s'**activer par poste**. Il ne contient
aujourd'hui **qu'un seul paquet** — « Le savoir durable ».

Un profil métier n'est donc **pas un mécanisme à construire** : c'est un paquet de plus. Le coût est
celui d'écrire une doctrine, pas celui d'un moteur.

---

## 4. Conformité — ce qui tient, ce qui manque

Rappel du cadre : **le confinement a été retiré volontairement** (F-73 / ADR-019, décision PO du
2026-09-12 : *« c'est à chaque client de laisser l'autorisation des commandes et de les valider »*).
Rien de ce qui suit ne propose de le rétablir.

### Ce qui tient

- **Un registre complet** : chaque appel d'outil est horodaté, attribué à un utilisateur, à un poste
  et à un projet, avec sa cible, son issue, sa durée. **Aucun contenu n'y entre** — c'est une
  garantie testée (F-61), renforcée depuis F-133 (liste blanche de champs bornés).
- **Isolation** : `user_id` sur tout accès, sans exception connue.
- **Permissions** `allow / ask / deny` persistées, réarmées par défaut à la création d'un poste.
- **Arrêt d'urgence** (`RunnerKillSwitchService`) et **intégrité du poste** vérifiée en fin de tour.
- **Purge** : à la suppression du compte et à la suppression d'un projet.

### Ce qui manque, pour vendre à une DSI

| # | Manque | Pourquoi ça compte |
|---|---|---|
| **C1** | **Aucun export du registre d'intervention.** L'audit se consulte par projet, avec une limite ; il n'existe pas de « tout ce qui a été exécuté chez vous entre le 1er et le 30 », exportable et remis au client. | C'est **la** pièce qu'une DSI demande en fin de mission. Les données sont déjà en base : il manque la sortie. |
| **C2** | **Rien ne signale qu'un secret est sorti du poste.** Un `cat` d'un fichier de clés part en clair vers le fournisseur. Le détecteur de secrets existe (`DocumentSecretScanner`, `ClientMailSecrets`) mais **ne sert qu'aux pièces jointes de courriel**. | Ne rien bloquer (décision PO), mais **savoir** et **pouvoir le dire** : c'est ce que demande un RSSI. |
| **C3** | **Pas de durée de conservation.** Purge à la suppression du compte, rien en fin de mission ni de rétention paramétrable. | Une DSI demande une durée, pas un « on supprime si vous partez ». |
| **C4** | **Le sous-traitant n'est écrit nulle part dans le produit.** La fiche « Pour votre DSI » (F-45) ne traite que le proxy réseau. | Le contenu des fichiers du client transite par un tiers. Cela s'assume et se documente ; ne pas le dire est le seul vrai risque. |

---

## 5. Ce que je propose

Sept features, **toutes tournées vers le raisonnement dans l'infrastructure**. La conformité est
regroupée à la fin, séparée, parce que le PO a demandé une liste centrée sur le raisonnement.

### Ordre recommandé

| # | Feature | Effort | Pourquoi là |
|---|---|---|---|
| 1 | **F-135 — Aucun poste n'apprend à vide** | XS | Multiplie par 4 la surface d'apprentissage. Le moins cher de la liste. |
| 2 | **F-136 — La carte du client entre dans le contexte** | M | **Le levier principal.** Rend vrai « il connaît déjà l'architecture ». |
| 3 | **F-137 — Le fait juste, au bon moment** | M | Ce qui rend F-136 tenable à 2 593 faits et au-delà. |
| 4 | **F-138 — Les profils métier** | S | Aligne le raisonnement sur le métier réel. |
| 5 | **F-139 — Le savoir qui vieillit se signale** | S | Protège la confiance dans la carte, sans quoi tout le reste se dégrade. |
| 6 | **F-140 — Ce que l'agent a appris, écrit noir sur blanc** | S | Rend l'apprentissage mesurable, donc pilotable. |
| 7 | *(conformité)* **F-141 / F-142** | S / S | À décider séparément. |

---

### F-135 — Aucun poste n'apprend à vide

**Le défaut.** Trois postes sur quatre n'ont aucune mémoire, et **rien à l'écran ne le dit**.
EDENRED reçoit même une doctrine qui décrit une carte absente de la machine.

**Ce qu'on livre.** La Forge dit, par poste, si la mémoire est active — et l'active en un geste. Une
activation dont le dépôt n'a pas abouti se voit et se rejoue. Les règles ne sont injectées que
lorsque les fichiers sont réellement là (`activeOn` filtre sur le statut).

**Comment on saura que c'est gagné.** Les quatre postes portent une carte vivante ; aucun agent ne
reçoit une doctrine pointant vers des fichiers absents.

---

### F-136 — La carte du client entre dans le contexte

**Le défaut.** 2 593 faits sur le client, et **pas un octet** dans la consigne système. L'agent ne
sait même pas quels fichiers de carte existent.

**Ce qu'on livre.** Un **sommaire de carte** en tête du préfixe stable : pour chacun des fichiers de
la racine, son titre, ses sections, le nombre de faits de chaque section et la date du dernier
apport. Quelques kilo-octets — `GovernanceMapDigest` les calcule **déjà** pour l'écran. Plus une
consigne explicite : *avant d'explorer la machine, regarde ce que tu sais déjà de ce client.*

**Pourquoi c'est bon marché.** Le sommaire est stable d'un tour à l'autre : il est **relu depuis le
cache** (0,50 $/M) au lieu d'être redécouvert par des tours d'exploration facturés plein tarif. F-134
a rendu cet arbitrage favorable ; il ne l'était pas il y a une semaine.

**Comment on saura que c'est gagné.** L'agent cite un fait de la carte **sans être allé le chercher**,
et le nombre d'appels d'exploration avant la première action utile baisse. Les deux se lisent dans
`runner_audit` sans instrumentation nouvelle.

---

### F-137 — Le fait juste, au bon moment

**Le défaut.** Un sommaire dit *où* chercher, pas *ce que* l'on sait. Et 2 593 faits ne tiendront
jamais dans un prompt — le volume croît de 5× par semaine.

**Ce qu'on livre.** Un **index des faits par entité**, construit côté gateway à partir des fichiers
que le juge de fin de tour **rapatrie déjà à chaque tour** (le coût de lecture est donc déjà payé) :
les noms propres de l'infrastructure — un bastion, un proxy, un coffre, une plateforme, un domaine —
et les faits qui les mentionnent. À l'ouverture d'un tour, les entités citées dans la demande
déclenchent l'injection des faits correspondants, bornée.

C'est ce qui produit littéralement l'effet demandé : poser une question sur un composant, et
l'application **répond déjà avec ce qu'elle en sait**, au lieu d'aller le redécouvrir.

**Comment on saura que c'est gagné.** Sur une question portant sur une entité connue, l'agent
répond sans aucun appel d'outil.

---

### F-138 — Les profils métier

**Le défaut.** *« Tu es un assistant de développement »* — pour un travail d'architecture et
d'exploitation.

**Ce qu'on livre.** Des **paquets de profil** dans le catalogue existant, activables par poste :
Architecte, Infrastructure / Production, Sécurité, Données. Chacun porte le rôle, **ce qui vaut
preuve dans ce métier** (un constat daté et rejouable, pas une supposition), les réflexes
d'investigation propres au domaine, et la forme du livrable attendu. Aucun mécanisme nouveau : F-51
sait déjà faire tout cela, et le catalogue ne contient qu'un seul paquet.

**Un garde-fou.** Un profil **ajoute** une doctrine métier ; il ne peut ni desserrer une règle de
plateforme, ni remplacer la discipline d'investigation.

---

### F-139 — Le savoir qui vieillit se signale

**Le défaut.** Un fait d'infrastructure de six mois n'est plus un fait. Les règles imposent déjà la
date de constat ; **rien ne s'en sert**. Une carte qui vieillit sans le dire finit par faire
répondre faux avec assurance — le pire mode d'échec possible sur une infra client.

**Ce qu'on livre.** Chaque fait porte son âge ; au-delà d'un seuil il est présenté comme **à
re-vérifier**, et l'agent le dit dans sa réponse au lieu de l'affirmer. La re-vérification met la
date à jour sans dupliquer le fait.

---

### F-140 — Ce que l'agent a appris, écrit noir sur blanc

**Le défaut.** `governance_map_growth` mesure déjà la croissance (516 → 2 593 faits), et **personne
ne la regarde**. Sans lecture, aucune régression d'apprentissage ne se verra.

**Ce qu'on livre.** Par client : les faits acquis cette semaine, les sections qui progressent, celles
qui stagnent, les faits périmés. Et la mesure qui compte pour le PO : **le nombre d'appels
d'exploration par tour** — s'il baisse à mesure que la carte grossit, l'application apprend
vraiment ; s'il ne baisse pas, la promesse n'est pas tenue et on le sait.

---

### Conformité — à décider séparément

**F-141 — Le registre d'intervention remis au client.** Export par poste et par période de tout ce
qui a été exécuté : date, outil, cible, issue, durée. Sans contenu, comme le registre lui-même. Les
données existent déjà ; il manque la sortie et l'écran. *(Répond à C1.)*

**F-142 — Le secret qui sort se signale.** Réutiliser le détecteur existant sur ce qui **remonte du
poste** vers le fournisseur. **Ne bloque rien** — le confinement a été retiré volontairement — mais
marque la ligne d'audit et l'affiche, pour qu'on puisse le dire à un RSSI qui le demande.
*(Répond à C2.)*

C3 (rétention) et C4 (mention du sous-traitant) sont des **décisions**, pas des développements : à
trancher, puis à écrire.

---

## 6. Ce que je ne propose pas, et pourquoi

| Piste | Pourquoi non |
|---|---|
| Une base vectorielle / un RAG sur la carte | 2 593 faits tiennent dans un index par entité. Un moteur sémantique ajouterait une dépendance, une latence et une source d'erreur pour un problème que la structure du fichier résout déjà. À reconsidérer au-delà de ~20 000 faits. |
| Un moteur de raisonnement maison | Contraire à Provider-First. Le raisonnement de base est bon (audit F-121) ; ce qui manque est le **contexte**, pas le moteur. |
| Rétablir le confinement | Décision PO du 2026-09-12, assumée. Non rediscuté ici. |
| Refondre la boucle sur le Claude Agent SDK | Décision PO du 2026-09-15 : on garde la boucle maison. F-121 poursuit la parité par réimplémentation. |
| Du graphisme | Explicitement écarté par le PO pour ce tour. |

---

## 7. Réponse courte à la question posée

**Oui.** Et le gain ne vient pas d'un raisonnement plus puissant : il vient de ce que l'application
**cesse d'oublier ce qu'elle a déjà appris**. Le savoir est là — 2 593 faits sur un seul client, en
six jours. Il est écrit, daté, sourcé, jugé. Il ne manque qu'une chose : **qu'il entre dans la
conversation**.

F-135 et F-136 suffisent à rendre la promesse vraie. F-137 la rend tenable dans la durée. F-138
l'accorde au métier. Le reste sert à ne pas la perdre.
