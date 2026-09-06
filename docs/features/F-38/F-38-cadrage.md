# F-38 — Exécution sur machine connectée (runner local) — Cadrage

> Statut : **cadrage validé** (option A retenue le 2026-08-29).
> **Livraison en un bloc** : les dix subfeatures s'enchaînent ; SF-38-06 est un **point de
> contrôle** (les zips disparaissent), pas un point d'arrêt.
>
> **Avancement au 2026-08-30 — découpage entièrement livré : 10 subfeatures sur 10 sur `main`**
> (vague `wave-2026-08-30`) : SF-38-01→10 sont livrées, **point de contrôle SF-38-06 atteint**.
> Voir le tableau §5 pour le détail (PR et migrations) et l'historique de `docs/PRODUCT_SPEC.md`
> pour le contenu de chaque livraison.
>
> **Clôture au 2026-09-06 — F-38 est Terminée.** Quatre subfeatures ont été ajoutées après la
> première clôture : SF-38-11 (délai dépassé ≠ annulation, PR #200), SF-38-12 et SF-38-13
> (**relais inter-pods** complet, PR #201 et #202) et SF-38-14 (purge à la suppression de compte,
> PR #203). Le chantier est **déployé en production** depuis le 2026-08-30 (image
> `staging-b907947`). Le seul reliquat — le **smoke manuel bout en bout** — est **parqué** :
> il n'est pas automatisable, il demande une vraie machine et un opérateur. Protocole :
> `docs/features/F-38/SMOKE-manuel-bout-en-bout.md` ; planification demandée au PO en **OQ-13**.

---

## 1 — Le problème

L'Atelier (F-28 Phase 2) exécute de vraies commandes, mais dans le **sandbox d'Anthropic** :
un conteneur jetable dans le cloud, sans accès à un projet, un cluster ou un environnement qui
vivent sur **une machine précise** (un serveur de dev, une VM, un cluster).

Quand le projet sur lequel on veut travailler est sur une autre machine, la boucle est **ouverte
aux deux bouts** et c'est l'utilisateur qui la referme à la main : export `.zip` → import →
rapatriement des fichiers modifiés → relance manuelle de `kubectl` / `npm test` →
**copier-coller de la sortie** dans la session. À chaque itération.

Le navigateur, lui, atteint déjà `portal.ng-itconsulting.com`. Ce qui manque n'est pas la
puissance de l'agent, c'est **le fil entre la session et la machine**.

## 2 — La solution retenue (option A)

Un **runner** posé sur la machine ouvre lui-même une connexion **sortante** WSS/443 vers la
gateway. Aucun port entrant. Modèle éprouvé (runners GitHub auto-hébergés, tunnels VS Code).

La gateway route les appels d'outils de la boucle d'agent vers ce runner au lieu du sandbox.
La sortie remonte en streaming dans la session déjà ouverte.

### Option B écartée
L'accès au dossier local depuis le navigateur (File System Access API) réglait les fichiers mais
**jamais l'exécution** — or c'est le retour des commandes qui est la douleur principale. Écartée
le 2026-08-29.

## 2 bis — Distribution et démarrage du runner

- **Où vit le code** : un module Maven **`runner/`** dans ce dépôt (à côté de `backend/` et
  `frontend/`), produisant un **fat-jar autonome** `runner/target/claude-runner.jar` (SF-38-03).
- **Récupération sur la machine** :
  - défaut v1 — le jar est construit (`./mvnw -pl runner package`) et **déposé** sur la machine
    (un seul fichier, ni installeur ni droits admin) ;
  - confort — l'écran d'appairage (SF-38-06) offre un **lien de téléchargement** du jar servi par
    la gateway et **la commande à coller**.
- **Démarrage** : processus au premier plan —
  `java -jar claude-runner.jar --gateway <url> --workspace <racine> --code <code-appairage>`.
  Il échange le code contre un jeton (`POST /runner/pair`), ouvre la connexion **sortante** WSS,
  affiche son activité en clair, et se coupe au `Ctrl-C`. Le jeton est réutilisé jusqu'à
  expiration/révocation (pas de réappairage à chaque lancement).

## 3 — Décisions d'architecture

| # | Décision | Justification |
|---|----------|---------------|
| D1 | Le workspace gagne une **cible d'exécution** : `SANDBOX` (actuel) \| `RUNNER` | Symétrique de la *source* `ARCHIVE`\|`GIT` introduite par F-31 |
| D2 | En mode `RUNNER`, on **n'utilise pas les Managed Agents** | Ils exécutent les outils chez Anthropic ; impossible de les rerouter |
| D3 | On réutilise la **boucle tool-use maison** `AtelierChatService.runLoop` (SF-28-02) | Déjà écrite, déjà en production ; on lui ajoute un outil `bash` et on route les outils fichiers vers le runner au lieu de S3 |
| D4 | Le runner est un **`.jar` Java 21** (ou un script Node en repli) | Runtime déjà présent sur un poste de dev ; ni installeur, ni droits admin, ni service |
| D5 | Runner **observable et arrêtable** : premier plan, sortie en clair, aucune persistance, aucun démarrage automatique | Un outil qui exécute des commandes à distance doit pouvoir être vu et coupé à tout instant (`Ctrl-C`) |
| D6 | Racine imposée au lancement (`--workspace /chemin`), refus de tout accès au-dessus | Confinement gardé **par le runner** |
| D7 | La validation d'action (F-33) devient **obligatoire et non désactivable** en mode `RUNNER` | `always_allow` est acceptable dans un conteneur jetable, pas sur une vraie machine |
| D8 | Registre de connexions derrière une interface **`RunnerRegistry`** : `InMemory` (dev, tests) / `PgNotify` (production) | Tranchée par défaut le 2026-08-29 — voir §4. Réversible |
| D9 | Le jeton runner est porté par une **chaîne de sécurité Spring dédiée** (`@Order(1)`, `securityMatcher("/runner/**")`), la chaîne principale restant inchangée | Un jeton runner ne doit jamais authentifier un endpoint utilisateur. Isoler les chaînes rend la non-régression structurelle |
| D10 | **Exclusions appliquées par le runner** : `.runnerignore` (repli `.gitignore`) + une liste par défaut **non désactivable** (`.env`, `*.pem`, `id_rsa*`, `.aws/`, `.kube/config`, `.ssh/`) | Sans exclusions, l'agent lirait les secrets présents dans l'arborescence. Le filtre est **côté runner** : ce qui est exclu ne quitte jamais la machine |
| D11 | Le journal d'audit trace les **lectures de fichiers** et les commandes | Pour pouvoir dire précisément ce qui a été lu et exécuté |

## 4 — Point d'architecture : 2 replicas backend

En production, `claude-gateway-backend` tourne à **2 replicas**. Le runner (WSS) et le navigateur
(SSE) sont deux clients distincts : ils atterrissent sur des pods différents. Un registre en
mémoire échoue une fois sur deux.

**Recommandation : Postgres `LISTEN`/`NOTIFY`** (option retenue) — aucun composant d'infra
supplémentaire (pas de Redis dans la stack), HA conservée, volume dérisoire. Abstrait derrière
`RunnerRegistry` (`InMemory` en dev/tests, `PgNotify` en prod), à la manière de `WorkspaceStorage`
(SF-28-01). Choix réversible.

## 5 — Découpage en subfeatures

Chaque subfeature vise ≤ 2 jours.

| ID | Subfeature | Contenu | Statut |
|----|-----------|---------|--------|
| SF-38-01 | Identité du runner : appairage et jetons | Code d'appairage à usage unique (TTL court) généré dans l'UI, échangé par le runner contre un jeton lié à `user_id` + workspace, révocable. Chaîne de sécurité dédiée (D9). Migration `runner_pairing_codes` + `runner_tokens`. | **Livrée** (PR #188, migration `047`) |
| SF-38-02 | Canal et registre de connexions | Endpoint WS `/api/runner/ws` authentifié par le jeton de SF-38-01, handshake, heartbeat, `RunnerRegistry` (InMemory + PgNotify), statut « runner connecté » exposé en API. | **Livrée** (PR #191, aucune migration) |
| SF-38-03 | Runner — connexion | Module `runner/` : `.jar` Java 21, connexion sortante WSS, **support `HTTPS_PROXY` + truststore d'entreprise**, appairage, heartbeat, affichage en clair, `Ctrl-C` propre. | **Livrée** (PR #192, module `runner/`) |
| SF-38-04 | Runner — outils fichiers | `read` / `write` / `list` / `search` confinés à la racine, refus de toute sortie de racine. | **Livrée** (PR #193) |
| SF-38-05 | Cible d'exécution `RUNNER` (backend) | Le workspace porte sa cible ; `runLoop` route les outils fichiers vers le runner au lieu de S3. | **Livrée** (PR #195, migration `048`) |
| SF-38-06 | Écrans (frontend) | Sélecteur de cible, indicateur runner connecté/déconnecté, écran d'appairage. Conforme `DESIGN_SYSTEM.md`. | **Livrée** (PR #196) |
| — | **Point de contrôle** | **À ce stade les `.zip` disparaissent dans les deux sens.** | **Atteint** le 2026-08-30 (SF-38-06, PR #196). |
| SF-38-07 | Outil `bash` | Exécution, streaming stdout/stderr ligne à ligne, code retour, timeout, interruption (réutilise F-32). | **Livrée** (PR #197) |
| SF-38-08 | Garde-fous d'exécution et traçabilité | Validation obligatoire par commande (F-33 non désactivable en mode runner), journal d'audit (commandes ET lectures, migration `runner_audit`), coupe-circuit et révocation. | **Livrée** (PR #198, migration `049`) |
| SF-38-09 | Repli de transport | Long-polling HTTP si un proxy tue le WebSocket. | **Livrée** (PR #199, aucune migration) |
| SF-38-10 | Exclusions côté runner | `.runnerignore` (repli `.gitignore`) + liste par défaut non désactivable (D10), appliquée avant toute lecture. | **Livrée** (PR #194, remontée avant SF-38-05) |
| SF-38-11 | Le délai dépassé n'est plus une annulation | Un `bash` coupé par le timeout était rendu comme une interruption utilisateur : issue terminale trompeuse. | **Livrée** (PR #200, aucune migration) |
| SF-38-12 | Relais inter-pods — appels d'outils | Le registre porte l'adresse du pod ; connecteur interne 8081 authentifié par secret partagé ; `tool_call`/`tool_result` relayés au pod propriétaire de la socket. | **Livrée** (PR #201, aucune migration) |
| SF-38-13 | Relais inter-pods — flux, annulation, porte | Décision de la porte, annulation, interruption de tour et marque F-32 diffusées aux pairs (Service headless) ; test chronométré : le flux relayé n'est pas bufferisé. | **Livrée** (PR #202, aucune migration) |
| SF-38-14 | Purge du runner à la suppression de compte | Codes d'appairage, jetons et journal d'audit effacés avec le compte. | **Livrée** (PR #203, aucune migration) |
| — | **Déploiement en production** | Image `staging-b907947` : migrations 048/049, `APP_RUNNER_REGISTRY=pg-notify`, jar servi par `GET /api/runner/download`, relais 8081, `/api/internal/**` inatteignable depuis l'ingress. | **Fait** le 2026-08-30 |
| — | **Smoke manuel bout en bout** | Appairage réel, WSS sortant, repli long-polling derrière un proxy, `Ctrl-C`. Non automatisable. | **Parqué** le 2026-09-06 — protocole `SMOKE-manuel-bout-en-bout.md`, planification au PO (OQ-13) |

### Écarts de séquence assumés
**SF-38-10 a été remontée juste après SF-38-04, avant SF-38-05** : livrer le routage backend avant les
exclusions aurait laissé exister sur `main` un socle de lecture **sans filtre**. Le point de contrôle
SF-38-06 est atteint, et la suite (SF-38-07, SF-38-08, SF-38-09) a été livrée dans la foulée comme prévu.
C'est le **seul** écart de séquence du lot ; le découpage a été livré en entier, sans subfeature
abandonnée ni ajoutée.

### Ce qui restait ouvert après la clôture du lot — et ce qu'il en reste
Trois limites avaient été assumées et tracées à la clôture du lot du 2026-08-30. **Deux sont levées** :

- ~~**pas de relais inter-pods**~~ — le routage `findLocal()`, la porte de confirmation en mémoire et
  les canaux de repli attachés à un seul pod condamnaient le mode `RUNNER` dès le second pod, alors que
  l'HPA `min 1 / max 4` peut en créer un tout seul. **Levée** par SF-38-12 (registre porteur de
  l'adresse du pod, connecteur interne 8081 authentifié, relais de `tool_call`/`tool_result`) et
  SF-38-13 (porte, annulation, interruption F-32 et flux non bufferisé). Cadrage :
  `CADRAGE-multi-replica.md`.
- ~~**le fat-jar n'est pas empaqueté**~~ — **levée** au déploiement du 2026-08-30 :
  `APP_RUNNER_JAR_PATH` sert le jar embarqué dans l'image (`GET /api/runner/download` → 200, 2,5 Mo).

**Reste, et restera hors de la suite de tests** : le parcours **bout en bout sur une vraie machine** —
appairage réel, connexion WSS sortante, bascule long-polling derrière un proxy qui coupe l'`Upgrade`,
`Ctrl-C`. Ce n'est pas un manque de code mais un **acte d'exploitation** : il faut une machine tierce,
un vrai réseau contraint et un opérateur. Il est donc **parqué** le 2026-09-06 sous forme de protocole
exécutable — `SMOKE-manuel-bout-en-bout.md` — et sa **planification est demandée au PO** (OQ-13).
Il ne conditionne plus le statut de F-38 : le code est livré, testé et déployé ; ce qui manque est une
**constatation terrain**, pas une livraison. Un KO au smoke ouvrira une subfeature correctif ciblée
(**`SF-38-22`…** — les numéros 15 à 21 ont été consommés le 2026-09-06 par le lot issu du second
passage du banc d'essai), pas une réouverture en bloc.

### Pourquoi `bash` arrive après le point de contrôle
Un runner qui ne fait que lire et écrire des fichiers apporte déjà l'essentiel (fin des zips)
et constitue un incrément plus simple et plus sûr. L'exécution de commandes (`bash`) est la
brique la plus sensible ; on la livre une fois le socle en place.

## 5 bis — Ce que le runner change pour les données

| | Aujourd'hui (`.zip`) | Avec le runner |
|---|---|---|
| Transbordement manuel | à la charge de l'utilisateur | **supprimé** |
| Stockage du projet côté gateway (S3) | oui | **non — la gateway relaie, elle ne stocke plus** |
| Contenu lu transmis au fournisseur | oui | **oui, inchangé** (Provider-First : Claude doit voir le code) |
| Surface de lecture | ce qui est mis dans l'archive | **tout ce qui est sous la racine** → d'où D10 |

Le **BYOK est déjà branché** dans `AtelierChatService` (`resolveActiveApiKey`) : le mode runner
réutilisant la même boucle (D3), une clé personnelle y fonctionne sans code supplémentaire — le
traitement se fait alors sur le compte du détenteur de la clé.

## 6 — Hors périmètre

- Runner partagé entre plusieurs utilisateurs → touche la notion d'organisation (**F-17, V3**).
- Plusieurs runners simultanés sur un même workspace.
- Service/démon persistant (contredit D5).
- Déploiement privé de la gateway : **ce n'est pas F-18**. La gateway reste hébergée, seul le
  point d'exécution se déplace.

## 7 — Conformité à la gouvernance

- **Gateway-First** : la boucle d'agent reste celle d'Anthropic ; on route l'exécution des outils.
- **Provider Independence** : le routage se branche sur l'abstraction `AiAgentProvider` existante.
- **Isolation multi-tenant** : jeton runner lié à `user_id` ; filtre `user_id` sur le registre,
  les jetons et le journal d'audit.
- **Préoccupation transversale — Auth / Principal** : un **nouveau type de porteur** (le runner,
  authentifié par jeton et non par JWT utilisateur) entre dans le système. L'analyse d'impact est
  **obligatoire dans la mini-spec de SF-38-01**, avec tests de non-régression.

## 8 — Note d'usage

Le runner exécute des commandes sur la machine où il tourne. Si cette machine ne t'appartient pas,
obtiens l'accord de son propriétaire avant de l'y poser — précaution de bon sens, comme pour tout
outil d'accès distant.
