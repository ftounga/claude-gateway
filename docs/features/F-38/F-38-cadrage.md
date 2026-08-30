# F-38 — Exécution sur machine connectée (runner local) — Cadrage

> Statut : **cadrage validé** (option A retenue le 2026-08-29).
> **Livraison en un bloc** : les dix subfeatures s'enchaînent ; SF-38-06 est un **point de
> contrôle** (les zips disparaissent), pas un point d'arrêt.
>
> **Avancement au 2026-08-30 — 9 subfeatures sur 10 livrées sur `main`** (vague `wave-2026-08-30`) :
> SF-38-01→08 et SF-38-10 sont livrées, **point de contrôle SF-38-06 atteint** ; **reste SF-38-09
> (repli de transport)**. Voir le tableau §5 pour le détail et `docs/PRODUCT_SPEC.md` pour l'historique.

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
| SF-38-09 | Repli de transport | Long-polling HTTP si un proxy tue le WebSocket. | **À livrer** — seule subfeature restante |
| SF-38-10 | Exclusions côté runner | `.runnerignore` (repli `.gitignore`) + liste par défaut non désactivable (D10), appliquée avant toute lecture. | **Livrée** (PR #194, remontée avant SF-38-05) |

### Écarts de séquence assumés
**SF-38-10 a été remontée juste après SF-38-04, avant SF-38-05** : livrer le routage backend avant les
exclusions aurait laissé exister sur `main` un socle de lecture **sans filtre**. Le point de contrôle
SF-38-06 est atteint, et la suite (SF-38-07, SF-38-08) a été livrée dans la foulée comme prévu.
**SF-38-09 (repli de transport) est la seule subfeature restante** : sans elle, le mode `RUNNER` exige
un WebSocket sortant praticable — un proxy d'entreprise qui coupe le WSS n'a pas encore de repli.

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
