# F-82 — Arrêter proprement, et savoir quand on n'y arrive pas

> Cadrage du 2026-09-12, écrit pendant la séance de test du PO. Tout ce qui suit a été **observé**
> ou **lu dans le code**, rien n'est supposé.

## 1. Ce que le PO a vécu

Poste connecté, tout fonctionne. Il veut arrêter le runner. **Il n'y arrive pas.**

Trois défauts se cachent derrière cette phrase, et ils ne se recouvrent pas.

## 2. D1 — `Ctrl-C` peut bloquer indéfiniment

`RunnerMain:162` :

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    shuttingDown.set(true);
    connection.stop();
    PollingConnection active = polling.get();
    if (active != null) { active.stop(); }
    stopped.await();            // ← aucune limite de temps
}, "runner-shutdown"));
```

`stopped` n'est libéré que dans le `finally` d'`execute()`, c'est-à-dire **quand la session a rendu
la main**. Si elle est bloquée dans une lecture réseau — un long-polling en attente, une socket qui
ne rend rien —, le crochet attend **sans plafond**. `Ctrl-C` a bien déclenché l'arrêt ; c'est
l'arrêt qui attend. Et un second `Ctrl-C` n'y change rien : la JVM ne relance pas un crochet déjà en
cours.

L'utilisateur voit un terminal figé, sans un mot.

**Ce qu'il faut** : borner l'attente (quelques secondes), **dire** qu'on n'a pas pu fermer
proprement, et rendre la main quand même. Une fermeture propre est souhaitable ; **un terminal qu'on
ne peut plus reprendre ne l'est pas.**

## 3. D2 — Le coupe-circuit existe, mais il est introuvable quand on en a besoin

`RunnerKillSwitchService` (F-38 / SF-38-08) fait exactement ce qu'il promet : révoquer **tous** les
jetons du poste, couper la liaison sur-le-champ, et ramener **tous** les projets en cible `SANDBOX`.

Mais son bouton ne vit **que** dans l'en-tête d'un terminal
(`atelier-terminal.component.html:229`). Vérifié : aucune trace de `killRunner` dans les écrans de
postes.

Conséquence, et c'est exactement la situation du PO : **un poste connecté sans aucun projet n'a pas
de terminal, donc pas de bouton.** On voit la machine branchée, et on ne peut rien en faire depuis
l'application. Sur le poste d'un client, à distance, c'est plus qu'un inconfort.

**Ce qu'il faut** : le geste doit exister là où vivent les postes — sur la carte de la machine, dans
`/forge`.

## 4. D3 — « Couper la liaison » ne coupe pas le processus, et ne le dit pas

Même déclenché, le coupe-circuit agit **côté gateway** : il tue la liaison, pas le programme. Le
processus Java continue de tourner sur la machine, tente de se reconnecter, et se fait refuser
(jeton mort). Vu du terminal du client : **rien ne s'arrête**.

Et le libellé n'aide pas. « Couper la liaison avec la machine » se comprend comme « arrête ça », et
**son effet réel dépasse largement la coupure** : tous les projets du poste repassent en bac à
sable. C'est la bonne conduite pour un **coupe-circuit** — on ne coupe pas un dossier, on coupe une
machine —, mais ce n'est pas ce qu'on croit déclencher quand on veut simplement débrancher.

**Ce qu'il faut** : que l'écran dise **ce qui va se passer** avant de le faire — les projets
ramenés au bac à sable, le processus qui continuera de tourner sur la machine et **comment
l'arrêter**.

## 5. D4 — Le repli de transport ne s'est pas déclenché

Constat du matin, non couvert par F-80 : après un appairage réussi, **ni le WebSocket ni le
long-polling** n'ont abouti, et le runner s'est arrêté sans rien dire d'exploitable. SF-38-09 prévoit
pourtant le repli — c'est précisément ce cas qu'il devait couvrir.

**À instruire avant de corriger** : le repli a-t-il été tenté et a-t-il échoué en silence, ou n'a-t-il
jamais été tenté ? Le runner doit **nommer** le transport essayé et le motif de son échec. Tant
qu'on ne sait pas lequel des deux, aucun correctif ne peut être écrit.

## 5 bis. D5 — Reprendre un poste connu coûte un code d'appairage, pour rien

Ajouté le 2026-09-12 à la demande du PO, après l'avoir constaté en testant.

Depuis le terminal d'un projet, le bouton de mise en service rouvre le parcours en mode « projet ».
Il lit le poste rattaché, puis se place selon son état — `runner-pairing-dialog.component.ts:572` :

```java
this.step.set(this.hostAlreadyLive() ? null : 'code');
```

Un poste **connecté** saute à la conclusion : rien à appairer, c'est le gain de F-48. Un poste
**non connecté** ouvre l'étape **« code d'appairage »**.

**Or « non connecté » recouvre deux situations que l'écran ne distingue pas :**

| | Ce qu'il faut réellement |
|---|---|
| Jamais appairé | un code d'appairage |
| **Appairé, mais le runner ne tourne pas** | `java -jar claude-runner.jar`, **sans argument** — le jeton est déjà sur le disque |

Le second est le cas **courant** : un poste qu'on rallume le matin, une machine qu'on redémarre, un
`Ctrl-C` de la veille. L'écran y propose pourtant un code neuf — qui expire en cinq minutes, qu'il
faut aller chercher, et qui ne sert à rien.

La commande de reprise **existe** à l'écran (`runner-pairing-dialog.component.html:903`), mais dans
la **conclusion**, sous la commande complète. Ce dont on a besoin dans le cas le plus fréquent est
donc ce qu'on voit en dernier.

**Ce qu'il faut** : la gateway sait si un poste porte un jeton **valide et non révoqué**. Quand c'est
le cas, l'écran propose **la reprise d'abord** — une ligne à copier, rien à générer — et n'offre le
réappairage qu'en **repli**, nommé comme tel (« la machine a changé, ou le jeton a été révoqué »).
Rien n'est retiré : le parcours complet reste accessible d'un clic.

## 5 ter. D6 — le mode projet demande une racine de **projet**, et refait un poste par projet

Ajouté le 2026-09-12 à la demande du PO, dans le même mouvement que D5 : c'est le même écran, et la
même confusion entre « la machine » et « le dossier où je travaille ».

`runner-pairing-dialog.component.html:870` :

```html
{{ hostMode ? 'Racine du poste sur la machine' : 'Racine du projet sur la machine' }}
```

Cette valeur (`workspacePath` → `commandPath()`, `ts:1043`) part **telle quelle** dans `--root` de la
commande de lancement (`ts:997`) **et** dans la commande de reprise (`ts:1011`). Or `--root` **est la
racine du poste** — le dossier sous lequel vivent les projets ; le commentaire de `ts:995` le dit
lui-même, en citant F-48 / SF-48-02.

**Conséquence en mode projet** (dialogue ouvert depuis l'en-tête d'un terminal, `hostMode = false`) :
l'écran demande la « racine du **projet** », et le runner déclare donc la machine comme si elle
commençait à ce dossier. La racine du poste devient `~/dev/mon-projet`, les sous-dossiers offerts à
« Ajouter un projet » sont ceux du projet et non ceux de la machine, et l'on retombe sur **un poste
par projet** — exactement le modèle que F-48 a supprimé.

Les chemins d'exemple disaient la même chose : `C:\Users\moi\projets\mon-projet` et
`/Users/moi/projets/mon-projet` décrivent un **projet**, pas une racine de machine.

**Ce qui est retenu** — « la connexion doit se faire **par poste uniquement**, pas par projet » :

1. Les **deux** modes demandent, affichent et emploient **la même chose** : la racine de la machine.
   Le libellé conditionnel disparaît ; l'aide dit que c'est le dossier **sous lequel** vivent les
   projets.
2. Les chemins d'exemple deviennent des racines de poste (`C:\Users\moi\projets`,
   `/Users/moi/projets`).
3. Quand la machine a **déjà déclaré** sa racine, l'écran le **rappelle** au lieu de laisser croire
   qu'un chemin neuf est attendu. Il ne **pré-remplit pas** : `runner_hosts.root_name` ne stocke
   volontairement que le **dernier segment** — l'arborescence d'une machine cliente n'a rien à faire
   dans la base — et deviner le reste produirait une commande faussement prête.
4. Le mode projet **reste** : il sert à rattacher un projet à un poste. Ce qui disparaît, c'est
   l'idée qu'on appaire un dossier de projet.

Livré avec D5, dans **SF-82-04** : même parcours, même fichier, même décision.

## 5 quater. D7 — Deux endroits pour connecter, un seul modèle

Ajouté le 2026-09-12, après que le PO a demandé : *« on est d'accord que la connexion se fait par
poste uniquement, pas par projet ? »*. Vérification faite, les deux gestes existent **aux deux
endroits** :

| Geste | Terminal d'un projet | Carte du poste |
|---|---|---|
| Connecter | `atelier.component.ts:1765` | oui |
| Couper la liaison | `atelier-terminal.component.html:229` | ajouté par SF-82-02 |

SF-82-04 a supprimé l'**ambiguïté** (les deux modes demandent la racine du poste), pas le **chemin**.
D7 tranche le chemin — et il le tranche **différemment pour chaque geste**, parce qu'ils ne servent
pas le même moment.

### Connecter depuis le terminal d'un projet → **retiré**

Le cas réel qu'il couvre est : *« je suis dans mon projet, le poste est éteint, je veux le
rallumer »*. Ce n'est pas une **connexion**, c'est une **reprise** — et SF-82-04 vient de mettre la
commande de reprise en premier. La moitié utile est déjà couverte.

Ce qui reste après retrait : une machine **jamais appairée** se connecte depuis `/forge`. Un clic de
plus, **une fois par machine**, contre un parcours qui cesse définitivement de laisser croire qu'on
appaire un dossier.

À la place, dans le terminal : **un état, pas un bouton d'action**. Le nom du poste, son état, la
commande de reprise quand elle a un sens, et un lien vers la carte du poste.

### Couper la liaison depuis le terminal → **gardé**

C'est le geste d'**urgence**. Quand ça se passe mal, l'utilisateur est **dans le terminal en train de
le regarder** : l'obliger à naviguer pour arrêter une machine qui déraille ajoute une étape au pire
moment. SF-38-08 le dit déjà — « le bouton qu'on cherche quand ça se passe mal ».

**Mais une seule formulation.** Aujourd'hui le terminal promet **moins** que ce qu'il fait : ni les
projets ramenés au bac à sable, ni le processus qui continue de tourner sur la machine. Les deux
boutons doivent partager **la confirmation de SF-82-02** — celle qui nomme les projets un par un.

## 6. Découpage

| | |
|---|---|
| **SF-82-01** | `Ctrl-C` rend toujours la main : attente bornée, et un mot quand la fermeture propre a échoué |
| **SF-82-02** | Le coupe-circuit vit sur la carte du poste, avec une confirmation qui **dit tout** ce qu'il fait |
| **SF-82-03** | Le repli de transport se nomme : transport essayé, motif de l'échec, transport retenu |
| **SF-82-04** | Reprendre un poste connu ne coûte plus un code : la reprise d'abord, le réappairage en repli — **et** la racine demandée est celle du **poste**, dans les deux modes (D6) |

## 7. Hors périmètre

- **Arrêter le processus du runner depuis l'application.** Ce serait une commande d'extinction à
  distance sur la machine d'un client : le produit ne s'arroge pas ce pouvoir. L'écran doit **dire**
  comment l'arrêter, pas le faire.
- Un service système (`systemd`, service Windows) pour piloter le runner.
- Changer ce que fait le coupe-circuit — F-82 le rend **atteignable** et **compréhensible**, elle ne
  le redessine pas.

## 8. Impact transversal

| Préoccupation | Composants |
|---|---|
| Aucune (ni auth, ni tenant, ni plans) | — |
| Navigation | un geste destructif de plus dans `/forge` — il rejoint « Supprimer le poste » (F-69) sous le même menu, jamais en accès direct |
| Runner | `RunnerMain` (crochet d'arrêt), `TransportFallbackPolicy`, `RunnerConnection`, `PollingConnection` |
| Frontend | carte de poste (`postes.component`), en-tête de terminal **inchangé** |

## 9. Plan de test minimal

- Crochet d'arrêt avec une session **volontairement bloquée** : le processus rend la main dans le
  délai borné, et **le dit**. C'est le test qui prouve la subfeature — sans lui, rien ne distingue le
  correctif de l'état actuel.
- Session qui se ferme normalement : **aucun message d'échec** ajouté.
- Coupe-circuit depuis la carte d'un poste **sans aucun projet** — le cas vécu.
- Coupe-circuit sur le poste d'autrui : refusé (isolation `user_id`), inchangé.
- Repli de transport : WebSocket refusé → le long-polling est tenté, et **les deux** sont nommés.
