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

## 6. Découpage

| | |
|---|---|
| **SF-82-01** | `Ctrl-C` rend toujours la main : attente bornée, et un mot quand la fermeture propre a échoué |
| **SF-82-02** | Le coupe-circuit vit sur la carte du poste, avec une confirmation qui **dit tout** ce qu'il fait |
| **SF-82-03** | Le repli de transport se nomme : transport essayé, motif de l'échec, transport retenu |

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
