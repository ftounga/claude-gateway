# Ce qu'on attend de moi — la Forge lit vos réunions et vos conversations Teams

> Cadrage du 2026-09-12. **Périmètre complet demandé et confirmé par le PO**, capture vidéo locale
> comprise. J'ai proposé deux fois de séquencer ; le PO a maintenu l'ensemble. C'est sa décision,
> elle est tracée, et ce cadrage la sert entièrement.

## 1. Ce qu'on veut obtenir

> « Résume la réunion d'hier avec Dupont. »
> « Regarde la discussion MFA avec Paul, sur la dernière semaine : ce qu'il dit, ce qu'il attend de
> moi, ce sur quoi je me suis engagé. »
> « Où m'a-t-on mentionné cette semaine ? »
> « Qu'est-ce qu'on attend de moi en ce moment ? »

Un consultant en régie se pose ces questions tous les lundis matin. **Rien n'y répond aujourd'hui**,
et personne ne les instruit à la main : c'est trop long.

## 2. Le choix qui structure tout : le navigateur, pas l'API

**Par le navigateur déjà authentifié.** Le runner tourne sur la machine du client, Chrome y est
ouvert avec la session Microsoft active — SSO franchi, MFA franchie, accès conditionnel satisfait. Le
runner s'y rattache (`--remote-debugging-port`), ouvre ce que l'utilisateur pourrait ouvrir,
télécharge ce qu'il pourrait télécharger.

**Pourquoi pas Graph.** L'API officielle exige un enregistrement d'application Azure **et** un
consentement administrateur du tenant client — pour les transcriptions, une permission de niveau
application, donc des droits sur **tout** le tenant. C'est un dossier qui prend des mois quand il
aboutit. Le navigateur ne demande **rien à personne** : il ne fait que ce que l'utilisateur fait déjà.

**Ce que ce choix coûte, et qui est assumé** :
- **la fragilité** — Teams web est une application vivante ; chaque refonte casse quelque chose. On
  vise les **URL de téléchargement** plutôt que des boutons partout où c'est possible ;
- **la politique d'entreprise** — automatiser une session authentifiée reste de l'automatisation,
  même quand chaque geste pris isolément est permis. **À dire au client, pas à cacher.**

**Ce que ce choix ne change pas** : le texte extrait part chez le fournisseur d'IA. Aucune
authentification n'y change rien, et c'est la seule question qui reste entière.

## 3. Découpage

### F-87 — Résumer une réunion enregistrée

Teams dépose ses enregistrements dans **OneDrive** (réunion privée) ou **SharePoint** (réunion de
canal) ; le lien de la conversation y pointe. La **transcription** se télécharge depuis le même
lecteur, en `.vtt`. **C'est le morceau facile et stable** : deux fichiers, deux URL.

| | |
|---|---|
| **SF-87-01** | Se rattacher au navigateur ouvert. Fondation de tout le reste : détecter Chrome, s'y connecter, échouer **clairement** s'il n'est pas lancé avec le port de débogage. |
| **SF-87-02** | Depuis un lien de réunion : retrouver l'enregistrement et sa transcription, les télécharger. **Les fichiers restent sur la machine.** |
| **SF-87-03** | Transcription → résumé structuré : décisions, points en suspens, engagements **attribués à leur auteur**. |

### F-88 — Le résumé montre ce qui était à l'écran

| | |
|---|---|
| **SF-88-01** | Extraire les images **aux changements de plan** (`ffmpeg`), dédoublonner. Une heure de réunion = 20 à 60 images utiles, contre 360 à intervalle fixe. **La détection de scènes n'est pas une optimisation : c'est ce qui rend la chose finançable.** |
| **SF-88-02** | Aligner transcription et images **par horodatage**, et rendre le résumé illustré. |

**Traitement lourd, donc asynchrone** — règle de `CLAUDE.md`, sur le modèle d'`OcrPollingWorker`.

### F-89 — Lire les conversations

**Le morceau difficile.** Teams web est une liste virtualisée : seuls les messages visibles existent
dans la page. Remonter une semaine, c'est faire défiler par programme, attendre les chargements, et
recoller sans doublon ni trou.

| | |
|---|---|
| **SF-89-01** | Lire **une** conversation sur une fenêtre de temps. |
| **SF-89-02** | **Les mentions** — par le **flux d'activité**, que Teams calcule déjà. Dix à trente fils à ouvrir au lieu de toutes les conversations : le problème tombe d'un ordre de grandeur. |
| **SF-89-03** | **Les engagements sans mention** : le nom écrit en clair (recherche Teams, qui indexe le contenu) et **ses propres promesses** — « je te l'envoie demain » —, qu'aucune recherche par mot-clé ne trouve : il faut ouvrir les conversations récemment actives. |

### F-90 — Capturer une réunion non enregistrée

La plupart des réunions **ne sont pas enregistrées**. Sans cela, F-87 ne sert qu'une minorité de cas.
`ffmpeg` capture écran et audio nativement sur les trois systèmes.

**Ce morceau n'est pas de la même nature que les autres, et le cadrage doit le dire.** Tout le reste
**relit ce qui existait déjà** — un enregistrement que Teams a produit, des messages déjà écrits, et
les participants savaient : Teams affiche un bandeau. **Une capture locale n'affiche rien.**

En France, enregistrer les paroles d'une personne sans son consentement relève de l'**article 226-1
du code pénal**. Le cadre professionnel n'y change pas grand-chose, et la plupart des politiques
internes l'interdisent. **La personne exposée est le consultant**, pas l'éditeur.

**Conséquences de conception, non négociables** :

| | |
|---|---|
| **SF-90-01** | La capture **s'annonce** : indicateur visible pendant toute sa durée, et mention dans le compte rendu produit — « réunion enregistrée localement le … ». **Un enregistrement silencieux transforme un outil de productivité en pièce à conviction.** |
| **SF-90-02** | **Deux usages distincts** dans le produit : capturer **son propre écran** (une démo, un travail en solo) ne pose aucun problème et sera le cas le plus fréquent ; enregistrer **une réunion à plusieurs** demande une confirmation explicite d'avoir prévenu. |

## 4. Ce qui est garanti, et ce qui ne l'est pas

**Ce qui est lu est exact** : ce sont vos fichiers et vos messages.

**Ce qui est déduit ne l'est pas.** « Je vais regarder » est-il un engagement ? « On en reparle
lundi » — pour qui ? Un humain hésite, un modèle aussi. Il y aura des engagements manqués, et des
phrases prises pour des engagements.

**D'où une exigence de forme, qui décide de l'utilité du produit** : le résultat est une **liste à
valider**, jamais une affirmation. Chaque ligne porte **son message source et son lien** — un clic,
on voit la phrase dans son fil.

> Sans cela, l'outil devient dangereux : il dira qu'on s'est engagé sur quelque chose, on le croira,
> et on découvrira trois semaines plus tard que la phrase disait autre chose.

C'est la leçon du 2026-09-12, transposée : un runner qui prétend confiner sans confiner, un poste
affiché « connu » dont le canal n'existe pas, un résumé qui prête un engagement non pris — **c'est la
même faute**. Ce que le produit affirme doit être vérifiable d'un clic.

## 5. Hors périmètre

- **Graph et le mode application** : écartés, ils demandent à une DSI des droits sur tout le tenant.
- **Écrire dans Teams** — répondre, publier un compte rendu dans la conversation.
- **Les réunions en cours** pour F-87 : on lit ce qui est terminé. (F-90 capture le direct, c'est autre chose.)
- Zoom, Google Meet, Webex.
- Transcrire nous-mêmes l'audio d'un enregistrement Teams non transcrit — **à trancher** (§6).

## 6. Les décisions que je ne prends pas seul

1. **Ce qu'on dit à l'utilisateur** avant de traiter les paroles de tiers, et en quels termes.
   *Recommandation* : une fois par réunion, avant le premier traitement, en nommant ce qui sera lu et
   où cela ira — la doctrine de la déclaration de portée du runner (F-57).
2. **Les réunions sans transcription** : refuser proprement, ou transcrire l'audio nous-mêmes ?
   *Recommandation* : refuser **et le dire**. Transcrire est une feature de plus, pas une variante.
3. **La rétention** des vidéos, images et textes extraits : où, et combien de temps ?
   *Recommandation* : avec le résumé, supprimés avec lui. Un cache d'enregistrements de réunions est
   un entrepôt de données sensibles que personne n'a demandé.

## 7. Impact transversal

| Préoccupation | Composants |
|---|---|
| **Traitement lourd** | F-88 et F-90 sont asynchrones **par obligation** : worker, état, reprise |
| **Isolation `user_id`** | une réunion, une conversation, une capture appartiennent à qui les a demandées — **jamais** de lecture croisée |
| **Secrets** | rien à stocker côté gateway : la session vit dans le navigateur du poste. **C'est un avantage de l'approche navigateur, et il faut le préserver** — ne jamais rapatrier de cookie ni de jeton Microsoft. |
| **Coût** | les images se paient ; la détection de scènes est ce qui rend F-88 finançable |
| **Poids du paquet runner** | Playwright et `ffmpeg` sont lourds. À télécharger à la demande, pas à embarquer — le paquet autonome fait déjà 40 Mo |
