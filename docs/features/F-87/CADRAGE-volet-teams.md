# Le volet Teams — étude et cadrage

> Cadrage du 2026-09-12, sur demande du PO : *« réfléchis à une solution globale et complète qui me
> permette de tout faire, en tout cas le maximum, sur mon Teams. »*
>
> **Une correction du PO structure tout ce document** : les exemples qu'il avait donnés — « récupère
> mes mentions », « résume cette réunion » — **ne sont pas des boutons**. C'étaient des illustrations
> pour jauger la complexité. *« Pour moi tout ça doit se faire sous forme de chat. »*

---

## 1. Le principe : Teams n'est pas un écran, c'est une capacité de l'agent

Un écran à boutons serait une erreur de conception, pour une raison simple : **on ne peut pas
prévoir les demandes**.

« Résume la réunion d'hier » est un bouton. « Résume la réunion d'hier **et dis-moi si ce qu'on m'y a
demandé recoupe ce que Paul m'écrivait la semaine dernière** » n'en est pas un — et c'est pourtant la
vraie question d'un consultant. Chaque bouton qu'on ajoute nomme un cas et exclut les autres.

**L'agent de la Forge a déjà des outils** — `bash`, `read_file`, `explore`, `search_files` — et il
les compose. Personne n'a jamais demandé un bouton « chercher dans les fichiers ». On lui parle, il
choisit.

**Teams s'ajoute exactement de la même façon : un catalogue d'outils.** L'agent décide lesquels
appeler, dans quel ordre, et combien de fois. Une demande composée se résout par composition
d'outils, pas par un écran de plus.

C'est aussi ce qui rend la fonctionnalité **extensible sans rien réécrire** : le jour où l'on ajoute
« lire un canal », toutes les demandes qui en avaient besoin se mettent à fonctionner, sans qu'aucun
écran ne bouge.

---

## 2. Comment on lit Teams : le réseau, pas le DOM

C'est **la** décision technique du volet, et c'est elle qui répond à la question de la fragilité.

### Ce qu'on ne fait pas

Lire le **DOM** de Teams web. C'est la voie évidente et la pire : liste virtualisée, classes CSS
générées, structure qui change à chaque refonte. Un scraper de DOM se répare tous les deux mois.

### Ce qu'on fait

**On lit le trafic que la page produit déjà.**

Quand Teams web affiche une conversation, il ne la fabrique pas : il appelle ses propres services et
reçoit du **JSON structuré** — messages, auteurs, horodatages, mentions. Le protocole de débogage de
Chrome permet d'**observer ces réponses** (`Network.responseReceived`, `Network.getResponseBody`)
pendant que la page fait son travail normal.

**Ce que ça change :**

| | DOM | Réseau |
|---|---|---|
| Ce qu'on reçoit | du texte à reconstituer | des objets déjà structurés |
| Les horodatages | à parser depuis « 14:32 » | exacts, au format ISO |
| Les auteurs | à deviner depuis un avatar | identifiés |
| Une refonte visuelle | **casse tout** | **ne change rien** |
| Le défilement | obligatoire pour faire exister les messages | obligatoire aussi — mais on récolte ce qui **arrive**, pas ce qui **s'affiche** |

**Une refonte de l'interface ne casse rien** : le JSON sous-jacent est un contrat entre le client
Teams et ses serveurs, qui bouge bien plus lentement que les pixels. Il n'est pas immuable — mais
entre « Microsoft redessine l'écran » (souvent) et « Microsoft change la forme de ses réponses »
(rare, et progressif), il y a un ordre de grandeur.

**Et c'est toujours la session de l'utilisateur** qui parle : on n'appelle rien nous-mêmes, on
**observe** ce que son navigateur reçoit déjà. C'est ce qui distingue cette approche d'un client API
déguisé.

### Ce qui reste à faire dans la page

Le défilement. Les messages n'arrivent que si la page les demande. Le pilotage reste donc nécessaire
— mais réduit à **un seul geste** (« fais défiler vers le haut, attends »), là où un scraper de DOM
dépend de dizaines de sélecteurs.

---

## 3. Tenir dans le temps : trois mécanismes

### 3.1 Un adaptateur unique

**Tout ce que le produit sait de Teams vit dans une seule couche** : quelles URL portent quoi, quels
champs lire, comment paginer. Le reste du produit — l'agent, les outils, l'écran — ne connaît que des
objets **à nous** : `Message`, `Conversation`, `Réunion`, `Mention`.

Le jour où Microsoft change quelque chose, **un seul fichier est à corriger**. C'est exactement le
principe `AIProvider` de votre architecture, appliqué à Teams.

### 3.2 Une sonde de santé

**Le produit doit découvrir qu'il ne sait plus lire Teams avant que l'utilisateur ne le découvre.**

Une sonde légère, jouée au rattachement : ouvrir une conversation connue, vérifier que les réponses
observées ont la forme attendue, compter les champs reconnus. Trois issues :

- **tout reconnu** → on travaille ;
- **partiellement reconnu** → on travaille, **et on le dit** : « Teams a changé : les fils de
  discussion ne sont plus lus » ;
- **rien reconnu** → on refuse, **en nommant** ce qui a changé, avec la version du client Teams
  observée.

### 3.3 Échouer bruyamment, jamais à moitié faux

**La règle qui prime sur toutes les autres.** Un adaptateur cassé qui rend la moitié des messages est
**pire** qu'un adaptateur qui refuse : il produit un compte rendu plausible et faux, sur lequel on
prend des décisions.

Tout outil de lecture rend donc, à côté de son résultat, **ce qu'il n'a pas pu lire** : « 47 messages
lus, 3 non reconnus, du 5 au 12 septembre ». Un trou se voit. Un trou silencieux ne se voit jamais.

C'est la leçon du 2026-09-12, écrite au cadrage : *ce que le produit affirme doit être vérifiable
d'un clic.*

---

## 4. Le catalogue d'outils

Ce que l'agent reçoit. **Aucun n'est un bouton** ; tous se composent.

| Outil | Ce qu'il fait |
|---|---|
| `teams_status` | La liaison est-elle établie ? quelle santé d'adaptateur ? |
| `teams_find_conversations` | Retrouver une conversation par personne, groupe ou sujet |
| `teams_read_conversation` | Lire une conversation sur une fenêtre de temps |
| `teams_mentions` | Là où l'on m'a mentionné — par le **flux d'activité**, que Teams calcule déjà |
| `teams_search` | Rechercher dans le contenu — réutilise l'**index de Teams**, pas le nôtre |
| `teams_find_meetings` | Retrouver une réunion, par date ou participant |
| `teams_meeting_transcript` | La transcription d'une réunion enregistrée |
| `teams_meeting_recording` | Télécharger l'enregistrement (**reste sur la machine**) |
| `teams_capture_start` / `_stop` | Capturer localement une réunion non enregistrée |

**Ce que l'agent en fait, personne ne l'écrit à l'avance.** « Qu'est-ce qu'on attend de moi ? »
appellera `teams_mentions`, puis `teams_search` sur les variantes du nom, puis
`teams_read_conversation` sur les fils récemment actifs — et il conclura. Une autre question
appellera autre chose.

**Deux outils suffisent à dire pourquoi le catalogue est le bon découpage** : `teams_mentions` et
`teams_search` réutilisent ce que Teams **calcule déjà** — le flux d'activité et l'index de recherche.
C'est votre principe **Provider-First**, appliqué à l'interface plutôt qu'à une API.

---

## 5. L'expérience : un terminal Teams

> **Révision du 2026-09-12, après une question du PO** : *« dans le terminal, on verra des captures
> d'écran ? »* — **Non, pas aujourd'hui.** `AtelierTerminalBlock` ne porte que `output: string`. Les
> cartes et les images montrées dans l'étude **n'existent pas** : ce sont un **genre de bloc nouveau**.
> Et le PO a tranché deux choses dans la foulée : **enrichir le fil**, et faire du passage à Teams
> **un vrai basculement visuel**.

### 5.1 Un terminal Teams, comme le terminal de poste

**La forme existe déjà dans le produit.** F-74 a créé le **terminal de poste** : même mécanique qu'un
terminal de projet, rattaché à autre chose, avec sa propre conversation. Le terminal Teams est la
même idée.

| | |
|---|---|
| **Même mécanique** | le fil, les tours, la reprise (F-84), la place au registre (F-70), l'usage compté par client (F-61) |
| **Son propre historique** | les échanges Teams ne se mélangent pas aux sessions de code |
| **Sa propre peau** | le basculement visuel — la mosaïque a déjà montré qu'un fil porte plusieurs apparences (charte §13) |
| **Ses propres blocs** | cartes, images, engagements — **et seulement là** |
| **Son propre droit** | il existe, ou il n'existe pas (§5.4) |

**La règle qui découle du reste, et qui n'est pas négociable** : un **terminal de projet reste
textuel pour toujours**. Une sortie de commande est exactement ce que la machine a répondu, jamais
une carte. Les blocs riches n'apparaissent **que** dans le terminal Teams.

### 5.2 Toujours pas de boutons

Le basculement est **visuel**, pas fonctionnel. On ouvre un terminal Teams comme on ouvre un terminal
de poste, puis **on parle** — c'est la décision fondatrice du §1, et elle ne bouge pas. Ce qui change,
c'est qu'on **voit** qu'on a changé d'outil.

Deux éléments d'état, sans action : l'**indicateur de liaison** dans la barre (relié / navigateur non
détecté / Teams a changé), et le compte Microsoft relié.

### 5.3 Ce que l'agent rend : des blocs vérifiables

**Le bloc carte de réunion** — décisions, ce qu'on attend de vous, ce à quoi vous vous êtes engagé.
Chaque ligne porte **son auteur, son heure, et un lien vers le message**.

**Le bloc moment** — c'est la réponse à *« montre-moi ce qu'il y avait à l'écran »*. Une image posée
**à côté de la phrase prononcée pendant qu'elle était affichée**, rapprochées par l'horodatage. Pas
une galerie en bas de page. L'heure ouvre la transcription à la seconde ; l'image s'agrandit d'un
clic — **le geste de la mosaïque, qu'on n'invente pas deux fois**.

**Le bloc liste** — engagements ou mentions. Un **niveau de certitude en toutes lettres**
(« explicite » / « à confirmer »), **jamais un score** : un chiffre donne une apparence de mesure à
une interprétation.

**Le joli, concrètement** : la charte sans rien y ajouter, **aucun registre de couleur nouveau** ; la
**densité d'un compte rendu, pas d'un tableau de bord** — *qu'est-ce qu'on attend de moi* doit sauter
aux yeux en trois secondes ; et **ce qui est incertain se lit comme incertain**, par la typographie,
pas par un pictogramme d'avertissement.

### 5.4 Le droit, et ce qu'il change techniquement

**Le motif existe** : F-40 — *« Option Atelier (droit découplé du plan) »* — ouvre un droit
indépendamment de l'offre, et les codes d'accès (F-62) passent par le même mécanisme.

**La garde est au niveau de l'outil, pas de l'écran.** `buildTools(workspace)` décide déjà quels
outils l'agent reçoit — c'est là que `bash` est donné ou non selon la cible. **Sans l'option, les
outils `teams_*` ne sont simplement pas donnés.**

La nuance compte : **l'agent ne refuse pas, il n'a pas la capacité.** Il ne dira jamais « je pourrais
mais vous n'avez pas payé » ; il dira qu'il ne sait pas lire Teams. On ne met pas l'utilisateur devant
une porte fermée à chaque phrase.

**Et la doctrine du produit s'applique telle quelle** : *« l'option ouvre l'accès, elle n'ajoute pas
de tokens »* (F-40, repris par F-62). La consommation d'un tour Teams tombe sur le **quota existant**
de l'utilisateur.

### 5.5 Le travail long se voit travailler

Télécharger, extraire, transcrire : des minutes. **La conversation ne se fige pas** — l'agent dit ce
qu'il fait, étape par étape, comme pour une commande. Et depuis F-84, ce fil survit à un changement
d'écran.

## 6. La capture locale

Elle sort du cadre des autres outils : les autres **relisent ce qui existait**, celle-ci **crée**.

**Trois garde-fous, non négociables :**

**La trace est indélébile.** On ne peut rien afficher dans la réunion des autres — seul Teams le peut,
et seulement quand c'est lui qui enregistre. Ce qu'on peut garantir, c'est qu'un enregistrement
**ne soit jamais anonyme** : filigrane incrusté dans la vidéo (`ffmpeg` le pose à la volée), mention
en tête du compte rendu, entrée au journal d'audit. **La trace voyage avec l'artefact.**

**Deux usages, deux gestes.** Capturer **son propre écran** — une démo, un débogage — ne concerne
personne d'autre : aucune friction. Enregistrer **une réunion à plusieurs** demande une confirmation
explicite d'avoir prévenu. *Si les deux avaient la même friction, elle deviendrait un réflexe et ne
protégerait plus rien.*

**Un indicateur pendant toute la capture**, toujours au premier plan. Il ne prévient que
l'utilisateur — et c'est son but : **éviter la capture oubliée qui tourne trois heures.**

**Ce que le produit ne peut pas garantir**, et qui est écrit : que les participants soient informés.
Cela ne peut venir que de l'utilisateur, de vive voix. La confirmation sert à le lui rappeler.

**Conséquence en cascade** : une capture locale n'a **pas** de transcription — Teams n'en produit que
pour ses propres enregistrements. Il faut donc transcrire l'audio, **sur la machine** : le modèle y
est téléchargé une fois, et **rien ne sort** — ni la vidéo, ni l'audio, seulement le texte. Envoyer
l'audio à un service annulerait le bénéfice de garder la vidéo en local.

---

## 7. Ce qui remonte, ce qui reste

| Reste sur la machine | Remonte dans l'application |
|---|---|
| l'enregistrement vidéo (centaines de Mo) | le compte rendu |
| l'audio | les 20 à 60 images retenues |
| les fichiers bruts téléchargés | la transcription, si l'utilisateur veut relire |
| les cookies et jetons Microsoft — **jamais rapatriés** | |

**La gateway orchestre, elle ne devient pas un entrepôt de vidéos de réunions.** Et la vidéo est
l'artefact le plus indiscret de tous : tout ce qui est passé à l'écran, y compris ce qu'on n'avait pas
l'intention de montrer.

---

## 8. Découpage

| | | |
|---|---|---|
| **F-87** | **La liaison Teams** | Se rattacher au navigateur, lire le réseau, l'adaptateur unique, la sonde de santé, l'indicateur. **La fondation** — rien ne marche sans elle, et tout le reste en dépend. |
| **F-88** | **Les outils de lecture** | Le catalogue donné à l'agent : conversations, mentions, recherche, réunions, transcriptions. |
| **F-89** | **Le terminal Teams** | Un terminal à part, sa peau, son historique, **et un genre de bloc nouveau** — carte, moment, liste. Plus le **droit** : sans l'option, les outils ne sont pas donnés à l'agent. **Ce n'est pas de la mise en forme : c'est étendre ce qu'un terminal sait afficher.** |
| **F-90** | **Les captures alignées** | Extraction aux changements de plan, alignement par horodatage. Asynchrone. |
| **F-91** | **L'enregistrement local** | Capture, filigrane, deux usages, transcription sur la machine. |

**Dans cet ordre, et pas un autre.** F-87 est la fondation ; F-89 n'a rien à rendre sans F-88 ; F-90
et F-91 sont les deux morceaux lourds, et ils viennent quand le reste fonctionne.

---

## 9. Les décisions, tranchées

**Tranchées le 2026-09-12 par le PO** : *« s'il y a d'autres cadrages, je suis ta reco. Et prends
toujours l'arbitrage qui donne la solution la plus complète. »*

**D1 — Ce qu'on dit avant de traiter les paroles de tiers.** Une annonce **une fois par réunion ou
par conversation**, avant le premier traitement, qui **nomme ce qui sera lu et où cela ira**. C'est la
doctrine de la déclaration de portée du runner (F-57), transposée. Pas à chaque tour : une annonce
répétée cesse d'être lue.

**D2 — La rétention.** Les textes et images remontés vivent **avec le compte rendu, et sont
supprimés avec lui**. Aucun cache de conversations de clients : ce serait un entrepôt de données
sensibles que personne n'a demandé, et qu'il faudrait un jour expliquer.

**D3 — Le poids du paquet runner.** Le pilotage du navigateur, `ffmpeg` et le modèle de transcription
sont **téléchargés au premier usage**, jamais embarqués. Le paquet autonome fait déjà 40 Mo, et la
plupart des utilisateurs n'ouvriront jamais Teams. Le téléchargement **se voit** et **se dit** — c'est
une minute d'attente la première fois, pas une panne.

**D4 — Jusqu'où remonter.** Un **plafond annoncé** (par défaut : une semaine, ou un nombre de
messages), **négociable dans la demande** — « remonte jusqu'au 1er septembre » doit marcher. Le
plafond existe parce que c'est le défilement qui décide du temps et du risque de casse ; il est
**dit**, jamais silencieux, et le résultat porte toujours la fenêtre réellement lue.

**D5 — Le droit.** Une **option mensuelle**, sur le modèle de F-40 : elle **ouvre l'accès, elle
n'ajoute pas de jetons** — la consommation tombe sur le quota existant. Un essai se donne par **code
d'accès** (F-62), le mécanisme existe déjà. **Le MONTANT reste À CONFIRMER PAR LE PO** : aucun agent
ne fixe un prix, et cette règle-là ne se délègue pas. Ordre de grandeur mesuré pour éclairer la
décision — le coût fournisseur d'un usage intensif est d'**une dizaine de dollars par mois** ; c'est
un plancher, pas un prix.

## 10. Hors périmètre

- **Graph et le mode application** : ils demanderaient à une DSI des droits sur tout le tenant.
- **Écrire dans Teams** — répondre, publier, réagir. On lit.
- **Lire le DOM** comme source de vérité. Il sert au défilement, jamais à la donnée.
- Zoom, Google Meet, Webex. Le catalogue est fait pour qu'ils s'ajoutent un jour sans rien casser.
- **Un écran Teams à boutons.** C'est la décision fondatrice de ce cadrage.
