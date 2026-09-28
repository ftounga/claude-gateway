# Mini-spec — F-84 / SF-84-08 — Le déploiement n'abandonne plus le tour en cours

## Identifiant

`F-84 / SF-84-08`

## Feature parente

`F-84` — Le tour survit à son flux

## Statut

`ready`

## Date de création

2026-09-28

## Branche Git

`feat/SF-84-08-deploiement-nabandonne-plus-le-tour`

---

## Objectif

Qu'une bascule de pods (déploiement, redémarrage, éviction) **attende la fin des tours d'agent en
cours** au lieu de les tuer, dans une limite de temps bornée et explicite.

---

## Le défaut, tel qu'il s'est produit

Le 2026-09-16, un déploiement a interrompu un tour en cours dans le terminal d'un client :
l'activité outil s'arrête pile à l'heure de bascule des pods, le tour est mort côté serveur, et
l'écran affiche encore « en réflexion » — parce que le flux SSE s'est seulement **détaché**.

F-84 avait établi que le tour **vit dans le processus du pod**, pas dans le flux : fermer l'onglet
ne l'arrête plus (SF-84-01), et on peut s'y rebrancher (SF-84-02). Le corollaire n'avait pas été
tiré : **tuer le processus tue le tour**, et c'est exactement ce que fait un `rollout`. Il n'existe
pas de « pod épargné » — la production a deux replicas et le rollout les recrée tous les deux.

Mesuré dans le dépôt, avant cette subfeature :

| Réglage | Valeur en vigueur | Conséquence |
|---|---|---|
| `server.shutdown` | non posé → `immediate` | les requêtes en vol sont coupées net |
| `spring.lifecycle.timeout-per-shutdown-phase` | non posé → 30 s | chaque phase d'arrêt est bornée à 30 s |
| `chatStreamExecutor` (le pool où tourne la boucle d'agent) | `waitForTasksToCompleteOnShutdown` non posé → `false` | à la fermeture du contexte, le pool est arrêté **sans** attendre les tâches actives |
| `terminationGracePeriodSeconds` | non posé → 30 s | Kubernetes envoie `SIGKILL` 30 s après `SIGTERM` |
| `strategy` du Deployment | non posée → `RollingUpdate` 25 %/25 % | avec 2 replicas, les **deux** pods peuvent être remplacés en même temps |

Autrement dit : un tour est perdu au plus tard 30 secondes après le début d'un déploiement.

---

## Comportement attendu

### Cas nominal

1. Kubernetes envoie `SIGTERM` au pod et le retire du `Service` (sonde de disponibilité + arrêt du
   serveur web) : **aucun nouveau tour n'arrive** sur ce pod.
2. Le serveur web s'arrête **gracieusement** : les requêtes en vol, flux SSE compris, ont le temps
   de se terminer.
3. Le pool `chatStreamExecutor` — celui sur lequel `AtelierMcpTurnLauncher` lance `runLoop` et sur
   lequel les flux de tour s'exécutent — **attend la fin de ses tâches actives**, dans la limite du
   délai de drainage.
4. Le processus s'arrête une fois les tours terminés, avant l'échéance de la période de grâce
   Kubernetes.
5. Le journal dit, à la fermeture du contexte, **combien de tours vivants** restaient à drainer :
   sans cela le drainage est invisible, et un drainage invisible ne se diagnostique pas.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Un tour dépasse le délai de drainage | L'arrêt se poursuit : le tour est perdu, mais **le journal l'écrit** (nombre de tours encore vivants), au lieu d'une perte silencieuse. Un déploiement ne peut pas être suspendu indéfiniment par un tour qui ne finit jamais. |
| Aucun tour en cours | L'arrêt est immédiat : le drainage n'ajoute **aucun** délai quand il n'y a rien à drainer (le pool attend des tâches actives, pas un temps fixe). |
| Un nouveau tour arriverait après `SIGTERM` | Refusé : `acceptTasksAfterContextClose = false`. On ne démarre pas un tour sur un pod qui meurt. |
| `SIGKILL` avant la fin du drainage | Hors cadre nominal : `terminationGracePeriodSeconds` est dimensionné **au-dessus** de la somme des phases d'arrêt bornées. |

---

## Critères d'acceptation

- [ ] `server.shutdown: graceful` et `spring.lifecycle.timeout-per-shutdown-phase` sont posés dans
      `application.yml`, pour **tous** les profils (leçon F-77 : un réglage d'arrêt dupliqué par
      profil finira corrigé d'un seul côté).
- [ ] `chatStreamExecutor` est configuré avec `waitForTasksToCompleteOnShutdown = true`,
      `awaitTerminationSeconds = app.shutdown.turn-drain-seconds` et
      `acceptTasksAfterContextClose = false`.
- [ ] Le délai de drainage est **configurable** (`app.shutdown.turn-drain-seconds`, défaut 300) et
      vaut la même valeur pour le pool et pour la phase de cycle de vie.
- [ ] `terminationGracePeriodSeconds` du Deployment backend est **strictement supérieur** à la somme
      des deux phases bornées (arrêt web + drainage du pool), marge comprise.
- [ ] La stratégie de déploiement est `maxUnavailable: 0`, `maxSurge: 1` : les pods sont remplacés
      **un par un**, et le pod neuf sert le trafic pendant que l'ancien draine.
- [ ] `LiveTurnRegistry` sait dire combien de tours sont vivants sur ce pod (`liveCount()`), sans
      compter les tours déjà terminés.
- [ ] À la fermeture du contexte, une ligne de journal indique le nombre de tours vivants
      (`INFO` quand il n'y en a aucun, `WARN` quand il y en a).
- [ ] Aucun secret, aucune donnée utilisateur dans cette ligne de journal : un **compte**, jamais un
      identifiant d'utilisateur ni un contenu de message.

---

## Périmètre

### Hors scope (explicite)

- **Reprendre** un tour interrompu sur un autre pod : non. Le tour reste local au pod (ADR-016) ;
  ici on lui laisse le temps de finir, on ne le déplace pas.
- Prévenir l'utilisateur à l'écran qu'un drainage est en cours : non (aucune UI dans cette SF).
- Le contrôle **avant** de déployer : c'est SF-84-09.
- Le pool `turnAttachExecutor` (rebranchements, lecture seule) : il n'exécute aucun tour ; le
  drainer allongerait l'arrêt sans rien sauver.
- Les autres déploiements du namespace (frontend, `diagram-renderer`) : sans état de tour.

---

## Contraintes de validation

| Réglage | Obligatoire | Valeur | Règle |
|---|---|---|---|
| `app.shutdown.turn-drain-seconds` | Non (défaut) | 300 | Borne dure du drainage, en secondes. Doit rester ≤ `timeout-per-shutdown-phase`. |
| `spring.lifecycle.timeout-per-shutdown-phase` | Oui | 310 s | Marge de 10 s au-dessus du drainage, sinon Spring coupe la phase avant le pool. |
| `terminationGracePeriodSeconds` | Oui | 660 | ≥ 2 × 310 s (phase web + phase pool) + marge. |
| `maxUnavailable` / `maxSurge` | Oui | `0` / `1` | Un pod à la fois, capacité jamais dégradée pendant un drainage long. |

Pourquoi 300 s : c'est l'ordre de grandeur d'un tour d'agent long observé en production, pas une
valeur ronde choisie au hasard. Au-delà, on préfère perdre le tour plutôt que geler un déploiement
correctif — et le journal le dit.

---

## Technique

### Endpoint(s)

Aucun. Cette subfeature ne crée ni n'expose aucune route.

### Tables impactées

Aucune. Rien n'est lu ni écrit en base : le registre des tours vivants est **en mémoire**, par pod.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun.

### Fichiers touchés

| Fichier | Nature |
|---|---|
| `backend/src/main/resources/application.yml` | `server.shutdown`, `spring.lifecycle.timeout-per-shutdown-phase`, `app.shutdown.turn-drain-seconds` |
| `backend/src/main/java/fr/claudegateway/chat/ChatStreamConfig.java` | drainage du pool `chatStreamExecutor` |
| `backend/src/main/java/fr/claudegateway/atelier/live/LiveTurnRegistry.java` | `liveCount()` |
| `backend/src/main/java/fr/claudegateway/atelier/live/TurnDrainReporter.java` | **nouveau** — journalise les tours restants à la fermeture du contexte |
| `k8s/base/backend/deployment.yaml` | `terminationGracePeriodSeconds`, `strategy` |

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | Rien ne touche au `Principal` ni à la session. |
| Contexte tenant | Non | Aucun accès aux données : le registre est en mémoire et déjà clefé `(userId, workspaceId)`. Aucune lecture inter-utilisateur n'est introduite — `liveCount()` rend un **entier**, jamais une ligne. |
| Plans / limites | Non | Aucun quota touché. |
| Navigation / routing | Non | Aucun front. |
| **Cycle de vie du pod** (transversale de fait) | **Oui** | Tout ce qui s'exécute sur `chatStreamExecutor` : `AtelierMcpTurnLauncher.runLoop`, `AtelierChatController`, `AtelierAgentController`, `ChatController`. Tous **bénéficient** du drainage ; aucun ne change de comportement à chaud. Les sondes (F-77) ne sont pas touchées — ni leurs chemins, ni leurs groupes, ni leurs délais. |

---

## Plan de test

### Tests unitaires

- [ ] `LiveTurnRegistry.liveCount()` — 0 sur un registre vide.
- [ ] `LiveTurnRegistry.liveCount()` — compte les tours ouverts pour plusieurs utilisateurs.
- [ ] `LiveTurnRegistry.liveCount()` — ne compte **pas** un tour fermé (`close`).
- [ ] `LiveTurnRegistry.liveCount()` — un second `open` sur le même projet remplace le premier :
      le compte reste à 1.
- [ ] `TurnDrainReporter` — aucun tour vivant : journalise sans exception, ne bloque pas.
- [ ] `TurnDrainReporter` — des tours vivants : le compte annoncé est celui du registre.

### Tests d'intégration

- [ ] Contexte Spring : le bean `chatStreamExecutor` est bien un `ThreadPoolTaskExecutor` dont
      l'attente d'arrêt vaut le délai configuré.
- [ ] Une tâche longue soumise à `chatStreamExecutor` **se termine** quand on ferme l'exécuteur,
      au lieu d'être interrompue (preuve directe du drainage, sans démarrer Kubernetes).
- [ ] Contrôle négatif : le même exécuteur **sans** le réglage n'attend pas — un test qui ne peut
      pas échouer ne vaut rien.
- [ ] Manifeste : `terminationGracePeriodSeconds` et `strategy` présents et cohérents avec les
      délais de `application.yml` (test de cohérence sur les fichiers, pas sur le cluster).

### Isolation workspace / `user_id`

- [x] Non applicable — aucun accès aux données. `liveCount()` rend un entier agrégé, jamais le
      contenu ni l'identité d'un tour ; les accès nominatifs existants (`find`, `liveTurnsOf`)
      restent clefés par `userId` et ne sont pas modifiés.

---

## Dépendances

### Subfeatures bloquantes

- `SF-84-01` (le registre des tours vivants) — **done**.

### Questions ouvertes impactées

- Aucune. `docs/OPEN_QUESTIONS.md` n'a pas d'entrée sur l'arrêt des pods.

---

## Notes et décisions

- **Pourquoi pas un `SmartLifecycle` maison.** Le mécanisme existe déjà dans Spring :
  `ExecutorConfigurationSupport` implémente `SmartLifecycle` (phase `1073741823`, vérifiée dans le
  bytecode de `spring-context` 6.2) et son `stop()` attend les tâches actives quand
  `waitForTasksToCompleteOnShutdown` est actif. La phase du pool est **inférieure** à celle de
  l'arrêt gracieux du serveur web (`SmartLifecycle.DEFAULT_PHASE - 1024`), donc l'ordre est déjà le
  bon : on cesse d'accepter, **puis** on draine. Écrire notre propre cycle de vie aurait ajouté un
  ordonnancement à maintenir pour reproduire ce que le cadre fait déjà.
- **Pourquoi `maxUnavailable: 0`.** Sans cela, un drainage de 5 minutes sur deux pods simultanés
  laisse la production sans capacité pendant tout le drainage. Un pod à la fois coûte un pod de
  plus pendant le rollout, et c'est le prix de l'absence de coupure.
- **Ce que cette SF ne promet pas.** Un tour plus long que le délai reste perdu. La seule façon de
  ne jamais en perdre serait de rendre le tour reprenable sur un autre pod — écarté par ADR-016.
