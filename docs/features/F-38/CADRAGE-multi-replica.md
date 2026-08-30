# F-38 — Cadrage : le mode `RUNNER` et les replicas multiples

> Statut : **cadrage à arbitrer** (rédigé le 2026-08-30, après livraison de SF-38-01→11).
> Question posée : le mode `RUNNER` suppose aujourd'hui « un replica unique ou une affinité
> d'ingress ». Que fait-on avant de le mettre en service ?

---

## 1 — L'état réel, mesuré (pas supposé)

| Fait | Mesure |
|---|---|
| Environnement déployé | ns `claude-gateway-staging` (nom legacy) — **c'est la production**, `portal.ng-itconsulting.com` |
| Replicas backend **maintenant** | **1** (`kubectl get deploy` : `1/1`, depuis 60 j) |
| **HPA actif** | `minReplicas: 1`, **`maxReplicas: 4`**, cible CPU 70 % — *il peut donc scaler tout seul* |
| Image déployée | `staging-1b16bde` — **aucune subfeature F-38 n'est en production** |
| `APP_RUNNER_REGISTRY` dans la configmap | **absente** → défaut `in-memory`, donc `PgNotifyRunnerRegistry` **inactif** |
| Overlay `production` (ns séparé, non déployé) | `replicas: 2` |

**Conséquence immédiate** : le problème n'est pas actuel — à 1 pod, le mode `RUNNER` fonctionne.
Il est **latent et auto-déclenchable** : c'est l'HPA, sous charge, qui créera le second pod. Autrement
dit la panne surviendra seule, sans intervention humaine, et précisément au moment de forte
activité. C'est le pire profil de défaut : invisible en recette, présent en pointe.

## 2 — Ce qui casse exactement

Le routage n'utilise que `RunnerRegistry.findLocal()` : si la socket du runner vit sur un autre pod
que celui qui exécute la boucle d'agent, l'appel échoue immédiatement en `runner_not_on_this_node`
(`RunnerCallDispatcher`). Le `PgNotifyRunnerRegistry` diffuse la **présence**, jamais les **messages**.

État vivant en mémoire d'un seul pod, inventaire complet :

| Où | Quoi | Effet si la requête arrive sur un autre pod |
|---|---|---|
| `RunnerCallDispatcher` | sockets par workspace, appels en vol | l'appel d'outil échoue |
| `RunnerConfirmationGate` (SF-38-08) | demandes d'autorisation en attente | la décision de l'utilisateur n'atteint pas la porte → refus au bout de 120 s |
| `RunnerPollingSessions` (SF-38-09) | canaux de repli long-polling | le repli ne répare rien : il s'enregistre aussi sur son propre pod |
| `AtelierSessionService.interruptedSessions` (F-32) | marque d'interruption | **antérieur à F-38** |
| `AtelierChatService.interruptedTurns` | marque d'interruption du tour | **antérieur à F-38** |

> **F-38 n'introduit pas la dépendance au pod : elle la rend fatale.** Avant, une interruption qui
> tombait sur le mauvais pod était un désagrément (le run continuait). Maintenant, un appel d'outil
> mal routé, c'est la fonction qui ne marche pas du tout.

## 3 — Les options

### Option A — Épingler à un seul pod
`minReplicas: 1` **et** `maxReplicas: 1` sur l'HPA, plus `strategy: Recreate` (sinon le `maxSurge`
du rolling update fait coexister deux pods le temps d'un déploiement).

- **Coût** : quasi nul, une configuration. Déployable aujourd'hui.
- **Ce qu'on paie** : on renonce à l'élasticité **de toute la gateway** — pas seulement du runner.
  Aujourd'hui c'est gratuit (1 pod depuis 60 jours, CPU à 0 %) ; ça cesse de l'être le jour où le
  trafic monte. Et `Recreate` introduit une courte interruption à chaque déploiement.
- **Ce que ça règle** : tout, tant qu'il n'y a qu'un pod. Y compris les défauts antérieurs (F-32).

### Option B — Relais inter-pods (recommandée en cible)
Le registre sait déjà sur quel `nodeId` vit un runner distant. On lui ajoute l'**adresse du pod**
(`status.podIP` par la downward API) et un **endpoint interne** — non routé par l'ingress, qui
n'expose que `/api`, `/oauth2`, `/login/oauth2` — pour relayer l'appel au pod propriétaire de la
socket, et rapatrier résultat, flux et annulation.

- **Coût** : deux subfeatures (voir §5). Pas de composant d'infra nouveau.
- **Ce qu'on paie** : un saut réseau intra-cluster par appel d'outil ; un chemin d'authentification
  interne à faire correctement (secret partagé, jamais l'ingress).
- **Ce que ça règle** : le routage, le flux, l'annulation **et** la porte de confirmation. L'HPA
  redevient utilisable.
- **Pourquoi HTTP pod-à-pod plutôt que Postgres `NOTIFY`** : la charge utile d'un `NOTIFY` est
  plafonnée à 8 000 octets quand un `tool_result` peut atteindre 512 Kio — il faudrait passer par une
  table relais (motif *outbox*), et le flux ligne à ligne de `bash` produirait un `INSERT` + `NOTIFY`
  par ligne. Le saut HTTP direct est plus simple et plus rapide.

### Option C — Le runner tire le travail depuis une file en base
Généraliser le long-polling de SF-38-09 : n'importe quel pod dépose l'appel en base, le runner le
récupère par son poll, dépose le résultat, n'importe quel pod le relit. La notion de « pod
propriétaire » disparaît.

- **Ce qu'on paie** : la latence d'un poll sur **chaque** appel, la perte du flux fin de `bash`, et
  la réécriture d'une partie de SF-38-02/03/05/09 qui vient d'être livrée.
- **Verdict** : la bonne architecture si on repartait de zéro, trop coûteuse maintenant.

### Option écartée — affinité de session sur l'ingress
Elle ne peut pas marcher, et c'est utile de dire pourquoi. Le navigateur et le runner sont **deux
clients distincts** : un cookie d'affinité colle le navigateur à un pod, il ne dit rien du pod où
atterrit la connexion WSS du runner. Il faudrait une affinité **par workspace**, partagée entre deux
connexions sans identifiant commun côté nginx. La seule variante qui fonctionne vraiment est « un
seul pod » — c'est l'option A, autant l'appeler par son nom.

### Option écartée — forcer le runner à se reconnecter
Demander au runner de se reconnecter quand il n'est pas sur le bon pod : rien ne garantit qu'il
atterrisse sur le bon (le load balancing est aveugle). Boucle possible, aucune convergence.

## 4 — Recommandation

**A maintenant, B en cible.**

1. **Épingler à 1 pod** (`maxReplicas: 1` + `Recreate`) et **configurer `APP_RUNNER_REGISTRY`** :
   cela débloque la mise en service de F-38 immédiatement, sans rien réécrire, et le coût réel est
   nul au trafic actuel. À tracer comme dette explicite, pas comme choix définitif.
2. **Livrer le relais inter-pods (B)** pour rendre l'HPA de nouveau utilisable, puis remettre
   `maxReplicas: 4`.

Ce que cette recommandation **assume** : que le trafic reste au niveau actuel le temps de livrer B.
Si ce n'est pas le cas, B devient prioritaire sur toute autre chose — un HPA plafonné à 1 est une
panne de capacité en attente.

## 5 — Découpage si B est retenue

| ID proposé | Contenu | Effort |
|---|---|---|
| SF-38-12 | Adresse du pod dans le registre (downward API `status.podIP`), endpoint interne authentifié par secret partagé, relais de `tool_call` → `tool_result` vers le pod propriétaire de la socket. Non routé par l'ingress ; test de non-régression prouvant qu'il reste inatteignable de l'extérieur. | ≤ 2 j |
| SF-38-13 | Relais du **flux** (`tool_stream`), de l'**annulation** et de la **porte de confirmation** (SF-38-08) — la décision de l'utilisateur doit atteindre la porte qui attend, quel que soit le pod qui reçoit la requête. | ≤ 2 j |

Les marques d'interruption de F-32 (`interruptedSessions`, `interruptedTurns`), pod-dépendantes
**avant** F-38, relèvent du même mécanisme : à traiter dans SF-38-13 ou dans une feature dédiée si
l'on veut couvrir tout l'Atelier — **à arbitrer**.

## 6 — Hors périmètre

- Redis ou tout composant d'infra supplémentaire (absent de la stack, TECH_STACK V2+).
- Le partage d'un runner entre plusieurs utilisateurs (F-17, V3).
- La refonte du canal en file persistée (option C).

## 7 — Ce qu'il faut décider

1. Déploie-t-on F-38 **maintenant** en épinglant à 1 pod (option A) ?
2. B est-elle programmée dans la foulée, ou attend-elle un besoin de capacité avéré ?
3. Le correctif de F-32 (interruption cross-pod) entre-t-il dans SF-38-13, ou fait-il l'objet d'une
   feature propre couvrant tout l'Atelier ?
