# Mini-spec — F-161 / SF-161-03 — Le journal des ruptures

## Identifiant
`F-161 / SF-161-03` — feature parente `F-161`

## Objectif
Enregistrer **pourquoi** et **quand** le runner disparaît, pour pouvoir un jour le réparer sur des
faits plutôt que sur une hypothèse.

## La demande, et sa retenue
Le cadrage F-161 §6 écarte explicitement « corriger la cause des déconnexions » :

> Le runner bat toutes les **30 s**, la gateway tolère **90 s** : trois battements de marge, ce
> n'est **pas** un problème de réglage. Le runner a réellement disparu — veille du poste, processus
> sorti, coupure réseau. **On ne le sait pas**, et le deviner produirait un correctif qui ne corrige
> rien.

Cette subfeature **ne corrige rien**. Elle **mesure**. C'est sa valeur et sa limite.

## Ce que le code sait déjà, et jette
| Cause | Où elle est connue | Ce qu'on en fait aujourd'hui |
|---|---|---|
| **Arrêt propre** | `RunnerPollingSessions.close(identity)` — `POST /runner/disconnect` | rien |
| **Inactivité (long-polling)** | `sweepIdleChannels()` — plus interrogé depuis `idleTimeoutMs` | un `log.info` |
| **Canal remplacé** | `open()` remplace un canal existant — reconnexion | rien |
| **Socket fermée** | `RunnerWebSocketHandler.afterConnectionClosed(session, status)` | un `log.debug` |
| **Socket muette** | balayage `stale-after` → `SESSION_NOT_RELIABLE` | un log |

**Le `CloseStatus` du WebSocket est le renseignement le plus précieux du lot** — il distingue une
fermeture réseau d'un arrêt applicatif — et il finit aujourd'hui dans un log `debug`, c'est-à-dire
nulle part en production.

## L'arbitrage, tranché ici
**Une table, pas des logs.** CloudWatch garde les logs quelques jours et ne se joint à rien. Pour
répondre à « ce poste décroche-t-il plus que les autres, et à quelle heure ? », il faut une table
qu'on interroge — et qu'on puisse croiser avec `runner_audit` et `usage_turns`.

**On enregistre la rupture, pas le battement.** Un enregistrement par battement (toutes les 30 s,
par poste) noierait le signal et coûterait en écritures. Une rupture est rare : c'est ce qui la rend
intéressante.

## Comportement attendu
1. Toute fermeture de canal — long-polling ou WebSocket — écrit **une ligne** : poste, utilisateur,
   **cause**, durée de vie du canal, temps écoulé depuis le dernier signe, et le `CloseStatus`
   quand il y en a un.
2. La ligne dit si des **appels étaient en vol** au moment de la rupture : c'est le cas qui tue un
   tour, et le seul qui coûte de l'argent.
3. L'écriture est **best-effort** : elle ne doit jamais empêcher une fermeture de canal ni faire
   échouer une requête. Un journal perdu vaut mieux qu'un canal bloqué.
4. **Isolation** : la ligne porte `user_id` ; toute lecture filtre dessus.
5. Une vue d'administration rend, sur une période : le nombre de ruptures **par cause**, par poste,
   et l'heure de la journée où elles se concentrent.

| Cas | Ligne écrite |
|---|---|
| `POST /runner/disconnect` | cause `ARRET_PROPRE` |
| Long-polling plus interrogé | cause `INACTIVITE` |
| Reconnexion remplaçant un canal | cause `REMPLACE` |
| Socket fermée par le pair | cause `SOCKET_FERMEE` + `CloseStatus` |
| Socket muette au-delà de `stale-after` | cause `SOCKET_MUETTE` |
| L'écriture du journal échoue | **rien** — le canal se ferme quand même |

## Critères d'acceptation
- [ ] Chacune des **cinq** causes écrit une ligne portant la bonne cause.
- [ ] La ligne porte la **durée de vie** du canal et le **temps depuis le dernier signe**.
- [ ] Le `CloseStatus` du WebSocket est **conservé**, au lieu de finir dans un log `debug`.
- [ ] Une rupture **avec appels en vol** est distinguée d'une rupture à vide.
- [ ] Une erreur d'écriture du journal **ne fait pas échouer** la fermeture (test de non-régression).
- [ ] **ISOLATION** : la vue d'administration filtre sur `user_id` ; un poste d'autrui est invisible.
- [ ] Débranchable : sans le réglage, aucune écriture, comportement d'avant.

## Plan de test minimal
**Unitaires** — une cause par test, sur `RunnerPollingSessions` et `RunnerWebSocketHandler` · le
`CloseStatus` est transcrit · un dépôt qui lève n'empêche pas la fermeture · la durée de vie et
l'ancienneté sont calculées depuis les instants du canal.
**Intégration** — la vue d'administration agrège par cause et par poste sur une période.
**Isolation** — un poste appartenant à un autre compte n'apparaît pas.

## Technique
| Élément | Changement |
|---|---|
| migration `134-runner-disconnects.xml` | table `runner_disconnects` + index `(user_id, host_id, created_at)` |
| `RunnerDisconnect` / `RunnerDisconnectCause` / dépôt | l'entité et ses cinq causes |
| `RunnerDisconnectJournal` | l'écriture, **best-effort**, un seul point d'entrée |
| `RunnerPollingSessions` | trois causes : `close`, `sweepIdleChannels`, `open` remplaçant |
| `RunnerWebSocketHandler` | deux causes, dont le `CloseStatus` |
| `RunnerDisconnectController` | `/admin/runner-disconnects` — agrégat par cause, poste, heure |
| réglage `app.runner.journal-disconnects` | défaut `true`, débranchable |

## Préoccupations transversales
**Contexte tenant** — la nouvelle table porte `user_id` et toute lecture le filtre. Composants
impactés, vérifiés un par un : `RunnerPollingSessions` (l'identité du canal porte déjà `userId`),
`RunnerWebSocketHandler` (idem via `RunnerIdentity`), `RunnerDisconnectController` (garde
d'administration + filtre). Aucun autre chemin n'écrit ni ne lit cette table.

## Hors périmètre
**Corriger la cause** des déconnexions — c'est précisément ce qu'on refuse de deviner · le **ping
conditionnel** (**SF-161-04**) · toute alerte ou notification sur rupture · la reprise de tour.
