# F-38 — Exécution sur machine connectée (runner local) — Cadrage

> Statut : **cadrage validé** (option A retenue par l'owner le 2026-08-29).
> **Livraison en un bloc** : l'owner a demandé le 2026-08-29 la totalité du périmètre d'un seul
> tenant. Les neuf subfeatures s'enchaînent sans arrêt ; SF-38-06 reste un **point de contrôle**
> et non un point de livraison.

---

## 1 — Le problème

L'Atelier (F-28 Phase 2) exécute de vraies commandes, mais dans le **sandbox d'Anthropic** :
un conteneur jetable dans le cloud, structurellement aveugle à l'infrastructure du client.

Chez un client, la boucle est donc **ouverte aux deux bouts** et c'est l'opérateur qui la referme
à la main : export `.zip` → import → rapatriement des fichiers modifiés → relance manuelle de
`kubectl` / `npm test` → **copier-coller de la sortie** dans la session. À chaque itération.

Le navigateur du client, lui, atteint déjà `portal.ng-itconsulting.com`. Ce qui manque n'est pas
la puissance de l'agent, c'est **le fil entre la session et la machine**.

## 2 — La solution retenue (option A)

Un **runner** posé sur la machine du client ouvre lui-même une connexion **sortante** WSS/443
vers la gateway. Aucun port entrant, aucune règle de pare-feu à demander : du trafic HTTPS
sortant, comme le navigateur. Modèle éprouvé (runners GitHub auto-hébergés, tunnels VS Code).

La gateway route les appels d'outils de la boucle d'agent vers ce runner au lieu du sandbox.
La sortie remonte en streaming dans la session déjà ouverte.

### Option B écartée
L'accès au dossier local depuis le navigateur (File System Access API) réglait les fichiers mais
**jamais l'exécution** — or c'est le retour des commandes qui est la douleur principale. Écartée
le 2026-08-29 : l'owner peut lancer un exécutable chez ses clients.

## 3 — Décisions d'architecture

| # | Décision | Justification |
|---|----------|---------------|
| D1 | Le workspace gagne une **cible d'exécution** : `SANDBOX` (actuel) \| `RUNNER` | Symétrique de la *source* `ARCHIVE`\|`GIT` introduite par F-31 |
| D2 | En mode `RUNNER`, on **n'utilise pas les Managed Agents** | Ils exécutent les outils chez Anthropic ; impossible de les rerouter |
| D3 | On réutilise la **boucle tool-use maison** `AtelierChatService.runLoop` (SF-28-02) | Déjà écrite, déjà en production ; on lui ajoute un outil `bash` et on route les outils fichiers vers le runner au lieu de S3 |
| D4 | Le runner est un **`.jar` Java 21** (ou un script Node en repli) | Un `.jar` n'est pas un exécutable mais une donnée passée à `java`, binaire déjà approuvé sur un poste de dev : il démarre là où un `.exe` est bloqué par AppLocker/WDAC. Ni installeur, ni droits admin, ni base de registre, ni `PATH`, ni service. **Jamais de binaire compilé autonome.** |
| D5 | **Aucune furtivité** : premier plan, sortie en clair, aucune persistance, aucun démarrage automatique, aucune reconnexion silencieuse | Tout ce qui imite un logiciel espion déclenche les règles écrites pour les logiciels espions |
| D6 | Racine imposée au lancement (`--workspace /chemin`), refus de tout accès au-dessus | Confinement gardé **par le runner**, pas par le serveur |
| D7 | La validation d'action (F-33) devient **obligatoire et non désactivable** en mode `RUNNER` | `always_allow` est acceptable dans un conteneur jetable, pas dans le réseau d'un client |
| D8 | Registre de connexions derrière une interface **`RunnerRegistry`** : `InMemory` (dev, tests) / `PgNotify` (production) | Tranchée par défaut le 2026-08-29 faute d'arbitrage explicite — voir §4. Réversible : changer d'implémentation ne touche aucun appelant |
| D9 | Le jeton runner est porté par une **chaîne de sécurité Spring dédiée** (`@Order(1)`, `securityMatcher("/runner/**")`), la chaîne principale restant inchangée | Un jeton runner ne doit jamais authentifier un endpoint utilisateur. Isoler les chaînes rend la non-régression structurelle, pas seulement testée |

## 4 — Point d'architecture ouvert : 2 replicas backend

**Le problème.** En production, `claude-gateway-backend` tourne à **2 replicas**
(`k8s/overlays/production/kustomization.yaml`). Le runner et le navigateur sont **deux clients
distincts** : la connexion WSS du runner atterrit sur le pod A, la requête SSE du navigateur sur
le pod B. Un registre de connexions en mémoire échoue donc une fois sur deux, et les sessions
collantes n'y changent rien.

**Options.**

| | Approche | Coût | HA |
|---|---|---|---|
| **a** | Redis pub/sub entre replicas | Nouveau composant d'infra (absent de la stack) | conservée |
| **b** | **Postgres `LISTEN`/`NOTIFY`** | Zéro nouveau composant | conservée |
| c | Épingler le backend à 1 replica | Zéro travail | **perdue** |
| d | Deployment relais dédié à 1 replica | Nouvelle image, nouveau CI, nouvelle ops | conservée |

**Recommandation : (b).** Aucun composant supplémentaire, HA conservée, et le volume est
dérisoire (un opérateur, des commandes interactives). Contraintes connues : payload `NOTIFY`
plafonné à 8 Ko (donc découpage de la sortie en fragments) et une connexion JDBC dédiée hors du
pool Hikari (`PGConnection.getNotifications()`).

Le point est **abstrait derrière une interface `RunnerRegistry`**, exactement comme
`WorkspaceStorage` en SF-28-01 : implémentation `InMemory` pour le profil `dev` et les tests,
implémentation `PgNotify` pour la production. Le choix reste ainsi réversible.

## 5 — Découpage en subfeatures

Chaque subfeature vise ≤ 2 jours.

| ID | Subfeature | Contenu |
|----|-----------|---------|
| SF-38-01 | Identité du runner : appairage et jetons | Code d'appairage à usage unique (TTL court) généré dans l'UI, échangé par le runner contre un jeton lié à `user_id` + workspace, révocable. Chaîne de sécurité dédiée (D9). Migration `runner_tokens`. |
| SF-38-02 | Canal et registre de connexions | Endpoint WS `/api/runner/ws` authentifié par le jeton de SF-38-01, handshake, heartbeat, `RunnerRegistry` (InMemory + PgNotify), statut « runner connecté » exposé en API. **Pas encore d'exécution.** |
| SF-38-03 | Runner — connexion | Nouveau module `runner/` : `.jar` Java 21, connexion sortante WSS, **support `HTTPS_PROXY` + truststore d'entreprise**, appairage, heartbeat, affichage en clair, `Ctrl-C` propre. |
| SF-38-04 | Runner — outils fichiers | `read` / `write` / `list` / `search` confinés à la racine, refus de toute sortie de racine. Aucun processus enfant. |
| SF-38-05 | Cible d'exécution `RUNNER` (backend) | Le workspace porte sa cible ; `runLoop` route les outils fichiers vers le runner au lieu de S3. |
| SF-38-06 | Écrans (frontend) | Sélecteur de cible, indicateur runner connecté/déconnecté, écran d'appairage. Conforme `DESIGN_SYSTEM.md`. |
| — | **Jalon de valeur** | **À ce stade les `.zip` disparaissent dans les deux sens**, sans aucune démarche auprès de la DSI du client. |
| SF-38-07 | Outil `bash` | Exécution, streaming stdout/stderr ligne à ligne, code retour, timeout, interruption (réutilise F-32). |
| SF-38-08 | Garde-fous d'exécution | Validation obligatoire par commande (F-33 non désactivable en mode runner), journal d'audit (migration `runner_commands`), coupe-circuit et révocation. |
| SF-38-09 | Repli de transport | Long-polling HTTP si le proxy du client tue le WebSocket. À déclencher au premier client où ça casse. |

> **Ordre corrigé le 2026-08-29** : l'appairage précède désormais le canal WebSocket. En
> rédigeant la mini-spec on a vu la dépendance — le handshake WS a besoin d'un jeton à valider,
> donc le jeton doit exister avant le canal.

### Pourquoi `bash` arrive après le point de contrôle

Un runner qui lit et écrit des fichiers sans jamais engendrer de processus enfant a le
comportement d'un client de synchronisation : il ne déclenche essentiellement rien côté EDR.

L'exécution de commandes, elle, a la signature comportementale d'une balise C2 — connexion
sortante persistante + processus shell engendrés. En Java, le motif « `java` engendre un shell »
est en outre celui de Log4Shell et des failles Struts, et fait l'objet de règles dédiées.

**Conséquence opérationnelle — dépendance externe, hors du code : SF-38-07 et au-delà exigent une
déclaration préalable auprès de l'équipe sécurité du client** (hash du `.jar`, domaine contacté, port, comportement exact) pour
obtenir une exclusion EDR. Cela se compte en jours et s'anticipe **avant** la mission. La totalité du code est livrée en un bloc, mais
**l'usage de `bash` chez un client donné reste suspendu à cette autorisation** : c'est le seul
élément du périmètre qu'aucune ligne de code ne peut débloquer.

## 6 — Hors périmètre

- Runner partagé entre plusieurs utilisateurs → touche la notion d'organisation (**F-17, V3**).
- Plusieurs runners simultanés sur un même workspace.
- Exécution en arrière-plan hors session ouverte ; service/démon persistant (contredit D5).
- Déploiement privé de la gateway : **ce n'est pas F-18**. La gateway reste hébergée, seul le
  point d'exécution se déplace.

## 7 — Conformité à la gouvernance

- **Gateway-First** : la boucle d'agent reste celle d'Anthropic ; on route l'exécution des outils.
- **Provider Independence** : le routage se branche sur l'abstraction `AiAgentProvider` existante.
- **Isolation multi-tenant** : jeton runner lié à `user_id` ; filtre `user_id` sur le registre,
  les jetons et le journal d'audit.
- **Préoccupation transversale — Auth / Principal** : un **nouveau type de porteur** (le runner,
  authentifié par jeton et non par JWT utilisateur) entre dans le système. L'analyse d'impact
  (liste des composants résolvant le Principal et le tenant) est **obligatoire dans la mini-spec
  de SF-38-01**, avec tests de non-régression sur les endpoints existants.

## 8 — Préalable non technique

Accord **explicite et écrit** du client avant toute pose du runner. On ouvre un canal
d'exécution dans son réseau : c'est techniquement simple et contractuellement sensible.
Aucun déploiement discret, dans aucune circonstance.
