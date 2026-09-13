# F-97 — Le statut du poste dit vrai

> Cadrage du 2026-09-13, sur constat du PO en production. **Cadrage seul : la livraison attend le go
> du PO.**

## 1. Le constat, dans ses mots

> « Ça met beaucoup de temps avant de se mettre à jour. Tu peux en voir un connecté, avant d'entrer
> dans un terminal et lancer une commande, qu'il te dit que le runner est éteint, et c'est seulement
> là, en revenant sur l'écran Forge, qu'il te montre le poste hors ligne. »

**Ce n'est pas l'écran qui est en retard.** La Forge relit l'état toutes les 15 s
(`POSTES_REFRESH_MS`, `postes.component.ts:86`) et dès qu'un onglet revient au premier plan. Elle
affiche fidèlement ce que la gateway lui répond, et **la gateway répond « connecté »**.

## 2. La cause, vérifiée dans le code

`RunnerStatusService.statusOf` (`RunnerStatusService.java:95`) :

```java
boolean connected = registry.isConnected(host.getId()) || heartbeatFresh;
```

Deux témoins de vie, reliés par un **ou** :

| Témoin | Ce qu'il prouve vraiment | Quand il s'éteint |
|---|---|---|
| `heartbeatFresh` — `runner_tokens.last_seen_at` < 90 s | le runner **a parlé** récemment (battement toutes les 30 s) | 90 s après la mort du runner ✅ |
| `registry.isConnected` — une socket est enregistrée | le serveur **n'a pas encore été prévenu** d'une fermeture | à `afterConnectionClosed` seulement ❌ |

Un runner qui meurt **sans fermer** sa connexion — ordinateur en veille, Wi-Fi coupé, WSL suspendu,
VPN qui tombe, proxy d'entreprise qui lâche la connexion — ne prévient personne. La socket reste
**à moitié ouverte** côté gateway :

- `RunnerWebSocketHandler.afterConnectionClosed` (seul appel à `registry.unregister`) n'est jamais
  appelé ;
- **aucun balayage** ne retire une socket muette : le transport long-polling en a un
  (`RunnerPollingSessions`, 90 s), **le WebSocket n'en a pas** ;
- pire, `PgNotifyRunnerRegistry` **ré-annonce toutes les 15 s** chaque connexion locale aux autres
  pods : la socket morte entretient sa propre présence ;
- l'ingress referme une connexion inactive au bout de `proxy-read-timeout: 900` s.

Le battement, lui, s'arrête bien. Mais le **ou** l'ignore, puisque la socket est encore enregistrée.
**Résultat : un poste éteint peut rester « connecté » jusqu'à 15 minutes.** C'est le premier envoi
réel (la commande du PO) qui fait tomber la socket morte, d'où l'enchaînement observé.

## 3. Le principe retenu

**Le battement fait foi.** Une socket enregistrée sert à **acheminer**. Elle ne prouve pas que le
runner est vivant.

- `connected` ⇔ un battement de moins de `stale-after` (90 s). Le registre ne suffit plus à lui seul.
- Une socket dont le battement est périmé est **fermée par la gateway**, et retirée du registre,
  exactement comme le long-polling le fait déjà.
- Une commande vers un poste dont le battement est périmé est refusée **tout de suite** avec « poste
  hors ligne », sans attendre un délai d'appel.

**Délai maximal affiché : ≈ 1 min 45** (90 s + un sondage de 15 s), contre 15 min aujourd'hui.

## 4. Le découpage

### SF-97-01 — La gateway ne croit plus une socket muette (backend)

- `RunnerStatusService` : `connected = heartbeatFresh`. Le registre reste lu pour distinguer
  « sur ce pod / sur un autre » dans le routage, jamais pour le statut.
- **Balayage des sockets WebSocket** (`@Scheduled`, même cadence que `RunnerPollingSessions`) : toute
  connexion locale dont le dernier battement est plus vieux que `stale-after` est fermée
  (`CloseStatus.SESSION_NOT_RELIABLE`), puis retirée du registre. Le retrait passe par le **même
  chemin** que `afterConnectionClosed`, garde anti-course par jeton comprise : la fermeture tardive
  d'une vieille socket n'efface pas une reconnexion plus récente.
- La ré-annonce de présence (`PgNotifyRunnerRegistry`) **ne ré-annonce plus** une connexion dont le
  battement est périmé. Sinon un pod pair continue de croire le poste présent.
- `RunnerCallDispatcher.call` : battement périmé → `RUNNER_UNAVAILABLE` immédiat.
- **Tests** : socket enregistrée + battement vieux de 91 s → `connected=false` ; balayage → socket
  fermée et registre vide ; reconnexion sous un nouveau jeton pendant le balayage → **non effacée** ;
  ré-annonce muette pour une connexion périmée ; appel refusé sans attendre le délai.

### SF-97-02 — Un refus reçu n'importe où met le poste à jour partout (frontend)

Le PO a vu l'information **dans le terminal**, pas dans la Forge. Un « poste hors ligne » reçu par
une commande, un aperçu de supervision ou une tuile de mosaïque doit **invalider l'état de ce poste**
dans tout l'écran, sans attendre le sondage suivant.

- État des postes tenu **à un seul endroit** (service partagé), écrit par le sondage **et** par tout
  refus `RUNNER_UNAVAILABLE`, lu par la Forge, le terminal et l'en-tête du projet.
- **L'écran date au lieu d'affirmer** : « en ligne · vu il y a 12 s », « hors ligne · vu il y a
  18 min », « jamais connecté ». La date se rafraîchit à la seconde côté client, **sans appel**. Un
  statut qui date se lit juste même quand il est en retard ; un statut qui affirme ment dès que le
  runner meurt en silence.
- **Tests** : un refus dans le terminal fait passer la Forge hors ligne sans nouvel appel ; le
  libellé relatif avance sans requête ; « jamais connecté » quand `lastSeenAt` est nul.

## 5. Ce qui ne change pas

- Le battement à 30 s et `stale-after` à 90 s : réduire ces valeurs raccourcirait le délai, mais
  ferait tomber à tort des postes derrière un proxy d'entreprise lent. **Décision : on ne touche pas
  aux durées**, on retire le mensonge.
- Aucune migration. Aucun changement du runner : **les runners déjà déployés en profitent sans
  mise à jour**.
- Le `proxy-read-timeout` de l'ingress (900 s) : il protège les tours longs, il n'a plus d'effet sur
  le statut une fois SF-97-01 livrée.

## 6. Préoccupations transversales

- **Auth / Principal** : non. **Contexte tenant** : non (le statut reste lu via `requireOwned`).
- **Plans / limites** : non.
- **Navigation** : non.
- **Inter-pods** : oui. Composants impactés : `PgNotifyRunnerRegistry` (ré-annonce),
  `RunnerCallRouter` (relais : même règle de battement), `RunnerCallDispatcher`.

## 7. Critère d'acceptation de la feature

Couper le Wi-Fi d'un poste connecté : **en moins de 2 minutes**, sans rien toucher, la Forge le montre
hors ligne. Lancer une commande pendant ces 2 minutes : refus immédiat, et la Forge suit **sans
recharger**.
