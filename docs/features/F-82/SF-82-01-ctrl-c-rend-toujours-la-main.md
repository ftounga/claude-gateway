# Mini-spec — F-82 / SF-82-01 — `Ctrl-C` rend toujours la main

## Identifiant

`F-82 / SF-82-01`

## Feature parente

`F-82` — Arrêter proprement, et savoir quand on n'y arrive pas

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-82-01-ctrl-c-rend-la-main`

---

## Objectif

Borner l'attente du crochet d'arrêt du runner : `Ctrl-C` rend **toujours** la main dans un délai
connu, et **dit** quand la fermeture propre n'a pas abouti.

---

## Le défaut, tel qu'il est dans le code

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
la main**. Si la session est bloquée dans une lecture réseau, le crochet attend sans plafond.
`Ctrl-C` a bien déclenché l'arrêt ; c'est l'arrêt qui attend. Un second `Ctrl-C` n'y change rien :
la JVM ne relance pas un crochet déjà en cours. L'utilisateur voit un terminal figé, **sans un mot**.

Les deux `stop()` appelés juste avant sont **coopératifs** :

- `RunnerConnection.stop()` demande une fermeture `1000` et compte le `closedLatch` — mais la boucle
  peut être ailleurs (dans le `Thread.sleep` du backoff, ou dans un `future.join()` de handshake) ;
- `PollingConnection.stop()` se contente de basculer `running` à `false` : le `poll` HTTP en cours
  ne rend la main qu'**au bout de son délai**, soit `POLL_WAIT_MS` (25 s) plus `READ_MARGIN` (20 s)
  — **45 s** dans le meilleur des cas, et jamais si le réseau ne répond plus du tout.

Autrement dit : même sans panne, le chemin nominal du repli long-polling peut faire attendre le
crochet trois quarts de minute en silence.

---

## Comportement attendu

### Cas nominal — la session rend la main

1. `Ctrl-C`. Le crochet marque l'arrêt, demande la fermeture au transport actif.
2. La session sort de sa boucle, le `finally` d'`execute()` libère `stopped`.
3. Le crochet rend la main. **Aucune ligne n'est ajoutée** : l'arrêt s'est passé comme prévu, et les
   messages de fermeture des transports (« Arrêt demandé — fermeture… », « Boucle … terminée. »)
   suffisent.

### Cas dégradé — la session est bloquée

1. `Ctrl-C`. Le crochet demande la fermeture.
2. Au bout de **5 s** (`RunnerShutdown.GRACE`), la session n'a toujours pas rendu la main.
3. Le runner **dit** ce qui se passe, en trois lignes :
   - la fermeture propre n'a pas abouti dans le délai, et le runner rend la main **quand même** ;
   - la cause probable : une lecture réseau en cours (long-poll, poignée de main, envoi de trame) —
     elle est abandonnée ;
   - ce que cela coûte : rien sur la machine, et la gateway refermera la liaison d'elle-même
     (balayage d'inactivité).
4. Le crochet sort. La JVM termine. **Le terminal est repris.**

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Le crochet est interrompu pendant son attente | Le drapeau d'interruption est reposé, on rend la main immédiatement, sans message d'échec |
| La session rend la main pile au moment du délai | `await` gagne : aucun message d'échec (`CountDownLatch.await` rend `true`) |
| `Ctrl-C` avant même l'ouverture d'un transport | Inchangé : le crochet trouve `stopped` libéré aussitôt |

---

## Critères d'acceptation

- [ ] Le crochet d'arrêt n'appelle **plus** `stopped.await()` sans borne.
- [ ] Le délai est une **constante nommée** (`RunnerShutdown.GRACE`), de 5 s, et la borne est
      réellement appliquée (test avec une session **volontairement bloquée**).
- [ ] Une session bloquée : le crochet rend la main en **moins de `GRACE` + marge**, et la console
      porte un message d'échec qui **nomme le délai**.
- [ ] Le message dit (a) que la fermeture propre a échoué, (b) la cause probable, (c) que rien n'est
      perdu côté machine et que la gateway referme d'elle-même.
- [ ] Une session qui se ferme normalement n'ajoute **aucun** message d'échec — vérifié par un test
      dédié qui relit toutes les lignes émises.
- [ ] Les ordres d'arrêt (`connection.stop()`, `polling.stop()`) sont envoyés **avant** l'attente, et
      un test le prouve.
- [ ] Le comportement d'`execute()` (codes de sortie, chemins d'erreur) est **inchangé**.
- [ ] Aucun `Runtime.halt()`, aucun `System.exit()` ajouté.

---

## Périmètre

### Hors scope (explicite)

- Rendre `PollingConnection.stop()` **interruptif** (annuler le `poll` HTTP en vol) : ce serait
  toucher au transport, pas au crochet. La borne du crochet suffit à rendre la main, et le défaut
  observé est le terminal figé.
- Arrêter le processus du runner depuis l'application (hors périmètre absolu F-82).
- Un service système (`systemd`, service Windows).
- Le repli de transport et son silence : `SF-82-03`.
- Le coupe-circuit et son atteignabilité : `SF-82-02`.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Composants impactés

| Fichier | Nature |
|---|---|
| `runner/src/main/java/fr/claudegateway/runner/RunnerShutdown.java` | **nouveau** — la logique du crochet, isolée et testable |
| `runner/src/main/java/fr/claudegateway/runner/RunnerMain.java` | le crochet délègue à `RunnerShutdown` |
| `runner/src/test/java/fr/claudegateway/runner/RunnerShutdownTest.java` | **nouveau** — la session bloquée, et la session normale |

### Décision de conception

La logique du crochet sort de la lambda et devient `RunnerShutdown.task(...)`, qui rend un
`Runnable`. C'est ce `Runnable` que la JVM exécute **et** que le test exécute : le test ne rejoue pas
une imitation du crochet, il exécute **le crochet lui-même**. C'est la leçon de `SF-79-02`, dont le
premier garde-fou passait alors que le défaut était toujours là — il testait à côté.

---

## Plan de test

### Tests unitaires (`runner`)

| # | Test | Vérifie |
|---|---|---|
| 1 | `une_session_bloquee_rend_la_main_dans_le_delai_borne` | Latch **jamais** libéré : le `Runnable` du crochet sort en moins de `GRACE` + marge |
| 2 | idem | La console porte le message d'échec, et il **nomme le délai** |
| 3 | `une_session_qui_se_ferme_rend_la_main_sans_message_d_echec` | Latch libéré d'avance : aucune ligne ne contient le mot d'échec |
| 4 | `les_ordres_d_arret_partent_avant_l_attente` | L'ordre d'arrêt des transports est exécuté avant que l'attente commence |
| 5 | `le_drapeau_d_arret_est_pose_en_premier` | `shuttingDown` est à `true` dès l'entrée, avant tout `stop()` |
| 6 | `le_delai_par_defaut_est_de_cinq_secondes` | `RunnerShutdown.GRACE` vaut 5 s |
| 7 | `une_interruption_du_crochet_ne_produit_aucun_message_d_echec` | Thread interrompu : sortie immédiate, drapeau reposé, console muette |

### Tests d'intégration

Aucun : le crochet n'a pas de dépendance réseau une fois isolé.

### Isolation utilisateur

Sans objet — code local au runner, aucun accès aux données.

---

## Préoccupations transversales

| Préoccupation | Touchée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

---

## Ce qui reste ouvert

Le `poll` HTTP en vol n'est toujours pas annulable : sur une session bloquée, le processus rend la
main mais la gateway ne reçoit **pas** de `/runner/disconnect`. Elle referme le canal au balayage
d'inactivité. C'est dit à l'écran, et c'est assumé ici.
