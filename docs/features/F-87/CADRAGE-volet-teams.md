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

## 5. L'expérience : la conversation est l'interface

### 5.1 Ce qu'on ajoute à l'écran, et ce qu'on n'ajoute pas

**On n'ajoute aucun écran de commande.** Pas de page « Teams » avec des boutons.

**On ajoute deux choses, et deux seulement :**

**Un indicateur de liaison**, dans la barre du terminal, à côté du signe de vie du runner (§11 de la
charte). Il ne porte aucune action : il dit un **état**.

| État | Ce qu'il dit |
|---|---|
| **Relié** | pastille verte, « Teams relié » |
| **Navigateur non détecté** | pastille neutre, « Chrome non joignable » — et **comment le lancer** |
| **Teams a changé** | pastille ambre (§12, « ce qui attend une décision »), « lecture partielle » |

**Un volet latéral**, ouvert à la demande, qui montre **ce que la liaison sait** : le compte
Microsoft relié, la santé de l'adaptateur, et la dernière lecture faite. **C'est une fenêtre sur un
état, pas un panneau de contrôle.**

### 5.2 Ce que l'agent rend : des cartes vérifiables

Une réponse en texte brut gâcherait le travail. Trois formes de réponse, dans le fil de la
conversation, au même endroit que le reste :

**La carte de réunion**

> **Comité MFA — 11 septembre, 14 h 00 → 15 h 05** · 6 participants
>
> **Décisions** — le déploiement passe au T3 · l'authentification par SMS est abandonnée
> **On attend de vous** — la note de cadrage, avant vendredi *(Paul, 14 h 32)*
> **Vous vous êtes engagé à** — fournir la matrice des rôles *(vous, 14 h 51)*
>
> *[capture]* *[capture]* *[capture]*
> Transcription lue : 1 h 05, 412 répliques · **3 répliques non reconnues**

**La liste d'engagements**

Une ligne, un engagement, **un lien vers le message source**. Un clic ouvre le fil dans Teams, à la
bonne position. Chaque ligne porte **l'auteur** et **l'heure** — parce qu'un engagement sans auteur
n'est pas un engagement.

Et un **niveau de certitude, dit en toutes lettres** : « engagement explicite » (« je te l'envoie
demain ») ou « **à confirmer** » (« je vais regarder »). **Jamais de score, jamais de pourcentage** :
un chiffre donne une apparence de mesure à une interprétation.

**La liste de mentions**

Groupée par conversation, avec la phrase et son contexte immédiat. Chaque entrée mène au message.

### 5.3 Ce qui est joli, et pourquoi

**Les cartes reprennent la charte, sans rien y ajouter.** Surface blanche, ombre, accent orange pour
ce qui est actionnable, navy pour les en-têtes. **Aucun registre de couleur nouveau** — c'est une
règle du produit, et quatre registres cohabitent déjà.

**La densité est celle d'un compte rendu, pas d'un tableau de bord.** Un compte rendu de réunion se
lit en diagonale : titres courts, listes serrées, pas de fioriture. Ce qu'on cherche — *qu'est-ce
qu'on attend de moi* — doit sauter aux yeux en trois secondes, sans faire défiler.

**Les captures sont petites, alignées sur le texte, et s'agrandissent d'un clic.** Comme les tuiles
de la mosaïque (§13) : le geste existe déjà, on ne l'invente pas deux fois.

**Ce qui est incertain se lit comme incertain.** Un engagement « à confirmer » ne porte pas la même
graisse qu'un engagement explicite. C'est le travail de la typographie, pas d'un pictogramme
d'avertissement.

### 5.4 Le travail long se voit travailler

Télécharger un enregistrement, en extraire les images, transcrire un audio : ce sont des minutes, pas
des secondes. **La conversation ne se fige pas.** L'agent dit ce qu'il fait, étape par étape, comme
il le fait déjà pour une commande — c'est le même fil vivant, et depuis F-84 il survit à un
changement d'écran.

---

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
| **F-89** | **Ce que l'agent rend** | Les cartes vérifiables : réunion, engagements, mentions. Le lien vers la source sur **chaque** ligne. |
| **F-90** | **Les captures alignées** | Extraction aux changements de plan, alignement par horodatage. Asynchrone. |
| **F-91** | **L'enregistrement local** | Capture, filigrane, deux usages, transcription sur la machine. |

**Dans cet ordre, et pas un autre.** F-87 est la fondation ; F-89 n'a rien à rendre sans F-88 ; F-90
et F-91 sont les deux morceaux lourds, et ils viennent quand le reste fonctionne.

---

## 9. Les décisions qui restent au PO

1. **Ce qu'on dit à l'utilisateur** avant de traiter les paroles de tiers, et en quels termes.
   *Recommandation* : une fois par conversation ou réunion, avant le premier traitement, en nommant ce
   qui sera lu et où cela ira — la doctrine de la déclaration de portée du runner (F-57).
2. **La rétention** des textes et images remontés : avec le compte rendu, supprimés avec lui ?
   *Recommandation* : oui. Un cache de conversations de clients est un entrepôt que personne n'a
   demandé.
3. **Le poids du paquet runner.** Playwright et `ffmpeg` sont lourds, le modèle de transcription
   aussi. *Recommandation* : téléchargés **à la demande**, au premier usage, jamais embarqués — le
   paquet autonome fait déjà 40 Mo.
4. **Jusqu'où remonter.** « La dernière semaine » est facile à dire et coûteux à obtenir : c'est le
   défilement qui décide du temps et du risque de casse. *Recommandation* : un plafond dit, et
   négociable dans la demande.

---

## 10. Hors périmètre

- **Graph et le mode application** : ils demanderaient à une DSI des droits sur tout le tenant.
- **Écrire dans Teams** — répondre, publier, réagir. On lit.
- **Lire le DOM** comme source de vérité. Il sert au défilement, jamais à la donnée.
- Zoom, Google Meet, Webex. Le catalogue est fait pour qu'ils s'ajoutent un jour sans rien casser.
- **Un écran Teams à boutons.** C'est la décision fondatrice de ce cadrage.
