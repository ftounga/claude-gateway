# Mini-spec — F-38 / SF-38-05 — Cible d'exécution `RUNNER` (backend)

## Identifiant
`F-38 / SF-38-05`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`done`

## Date de création
2026-08-30

## Branche Git
`feat/SF-38-05-cible-execution-runner`

---

## Objectif

> Donner au workspace une **cible d'exécution** (`SANDBOX` | `RUNNER`, symétrique de la *source*
> `ARCHIVE` | `GIT` de F-31) et faire que, en cible `RUNNER`, la boucle tool-use
> `AtelierChatService.runLoop` exécute ses outils fichiers **sur la machine de l'utilisateur via le
> canal WebSocket runner**, jamais sur le stockage objet.

---

## Comportement attendu

### Cas nominal

1. Le workspace porte `execution_target` (`SANDBOX` par défaut, migration 048). Le propriétaire le
   bascule par `PUT /api/v1/workspaces/{id}/execution-target` ; la valeur est renvoyée dans le
   détail et le résumé du workspace (champs **additifs**).
2. En cible `SANDBOX` : **rien ne change** (stockage objet, garde-fou Git inchangé, Managed Agents
   disponibles). Zéro régression.
3. En cible `RUNNER`, sur un message d'atelier :
   - le garde-fou « mode Assistant interdit sur projet Git » n'est **pas** appliqué : un projet
     `GIT` + `RUNNER` est légitime (le dépôt est cloné sur la machine, pas dans une sandbox) ;
   - la **consigne système** (CLAUDE.md + skills) est lue **par le runner** (`list_files` +
     `read_file`), pas dans le stockage objet — sinon elle partirait vide, en silence ;
   - chaque appel d'outil du modèle (`list_files`, `read_file`, `write_file`, `search_files`) est
     traduit en une trame `tool_call` (contrat de messages §2.2) émise sur la socket runner de
     **ce nœud**, et la réponse `tool_result` est retraduite en `tool_result` pour le modèle
     (contrat §3) ;
   - `search_files` est **un seul** `tool_call` (jamais N lectures) ;
   - le champ de corrélation `id` est l'identifiant `tool_use` du fournisseur ; s'il est vide, le
     backend génère un UUID v4 et l'utilise **partout** dans ce tour (bloc `tool_use`, bloc
     `tool_result`, trame runner).
4. Le routage utilise **exclusivement** `RunnerRegistry.findLocal()` : la socket vit sur un nœud
   précis. Un `RunnerCallDispatcher` dédié tient `workspaceId -> ConcurrentWebSocketSessionDecorator`
   et `id -> appel en vol` ; le record `RunnerConnection` n'est **pas** modifié.
5. Le conteneur WebSocket accepte des trames texte de **1 Mio** (bean
   `ServletServerContainerFactoryBean`) — sans quoi le défaut de 8 192 octets couperait la première
   lecture de fichier un peu grosse.
6. En cible `RUNNER`, l'ouverture d'une session **Managed Agents** est refusée (D2/D3) : les outils
   s'exécuteraient chez le fournisseur, impossible à rerouter.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|----------------------|------|
| Aucun runner connecté pour ce workspace | `tool_result` d'erreur au modèle, code `runner_unavailable`, message « Aucun runner n'est connecté… » | — (résultat d'outil) |
| Runner connecté mais sa socket vit sur **l'autre replica** (`isConnected` vrai, `findLocal` vide) | erreur immédiate `runner_not_on_this_node` — aucun relais inter-pods en v1 | — |
| Le runner ne répond pas dans `timeoutMs + 5 000 ms` | `tool_cancel(reason="timeout")` émis, appel terminé en `runner_timeout`, tout `tool_result` tardif jeté | — |
| Réponse runner illisible / non conforme, ou `protocol_error` référençant l'appel | appel terminé en `runner_protocol_error` | — |
| Socket fermée avec des appels en vol | tous les appels du workspace terminés en `runner_unavailable` **avant** `registry.unregister` ; aucun rejeu | — |
| `write_file` avec un contenu > 524 288 octets | refus **avant** émission, `invalid_input` | — |
| Chemin `..` / absolu envoyé par le modèle | normalisé avant émission ; le runner revérifie et fait foi (D6) | — |
| Cible d'exécution inconnue dans le corps du `PUT` | 400 `validation_error` | 400 |
| Workspace d'un autre utilisateur | 404 (isolation `user_id`, `requireOwned` en premier) | 404 |
| Ouverture d'une session Managed Agents sur un workspace `RUNNER` | 409 `execution_target_runner` | 409 |
| `tool_result` / `tool_stream` portant un `id` inconnu, ou venant d'un autre workspace | jeté en silence (log debug) | — |

---

## Critères d'acceptation

- [ ] `workspaces.execution_target` existe (`varchar(16) NOT NULL DEFAULT 'SANDBOX'`) via la migration
      Liquibase **048**, changeSets `postgresql` + `h2`, rollback `dropColumn`.
- [ ] `PUT /workspaces/{id}/execution-target` bascule la cible du workspace **possédé** (404 sinon),
      refuse une valeur inconnue (400), et renvoie le détail à jour.
- [ ] `WorkspaceDetailResponse` et `WorkspaceSummaryResponse` portent `executionTarget`.
- [ ] En cible `SANDBOX`, la boucle tool-use est **inchangée** : le garde-fou Git s'applique toujours
      et les outils passent par `WorkspaceService` (tests existants verts, aucun appel runner).
- [ ] En cible `RUNNER`, un projet **Git** n'est plus refusé par `requireArchiveChatMode`.
- [ ] En cible `RUNNER`, `list_files` / `read_file` / `write_file` / `search_files` produisent
      **une** trame `tool_call` conforme (§2.2 : `type`, `id`, `tool`, `input`, `timeoutMs`) et le
      résultat renvoyé au modèle respecte §3 (contenu verbatim ; `write_file` → « Fichier écrit : »).
- [ ] En cible `RUNNER`, la consigne système est bâtie à partir du runner (`list_files` +
      `read_file` CLAUDE.md/skills), jamais du stockage objet.
- [ ] En cible `RUNNER`, `search_files` déclenche **un seul** `tool_call` `search_files`.
- [ ] `findLocal()` vide + `isConnected()` vrai ⇒ `runner_not_on_this_node` sans aucune émission.
- [ ] `findLocal()` vide + `isConnected()` faux ⇒ `runner_unavailable`.
- [ ] Absence de réponse ⇒ `tool_cancel(timeout)` émis et résultat `runner_timeout`.
- [ ] Fermeture de socket ⇒ appels en vol terminés en `runner_unavailable`.
- [ ] Un `tool_result` dont l'`id` appartient à un autre workspace est **ignoré** (isolation).
- [ ] Le bean `ServletServerContainerFactoryBean` fixe les tampons texte/binaire à 1 048 576 octets.
- [ ] `cd backend && ./mvnw test` vert ; `cd runner && ./mvnw test` vert (module non modifié).

---

## Périmètre

### Hors scope (explicite)

- **Outil `bash`**, `tool_stream` exploité pour l'agrégat de sortie et l'événement d'étape `bash`
  → **SF-38-07** (le dispatcher accepte et bufferise déjà les `tool_stream`, mais aucun outil ne les
  produit en SF-38-05).
- **Écrans** (sélecteur de cible, indicateur connecté) → **SF-38-06**. Aucun composant Angular ici.
- **Validation obligatoire + audit `runner_audit`** → **SF-38-08**.
- **Repli long-polling** → **SF-38-09**.
- **Relais inter-pods** : hors v1 (voir « Notes et décisions »).
- Aucun changement au module `runner/`.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|-----------------|-------|
| `workspaces.execution_target` | `SANDBOX` | imposée : toute ligne existante et toute création reste en sandbox — le comportement d'avant F-38 |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|--------------|-----------------------------|---------|---------------|
| `executionTarget` (corps du `PUT`) | Oui | 16 | `SANDBOX` \| `RUNNER` | Non | `trim()` + majuscules |
| `id` (corrélation runner) | Oui | 64 | chaîne opaque non vide ; UUID v4 généré si absent | par appel en vol | — |
| `tool` (trame) | Oui | — | `list_files` \| `read_file` \| `write_file` \| `search_files` | Non | identité (aucun préfixe) |
| `input.path` | Oui (`read_file`, `write_file`) | 4 096 | relatif, `/`, sans `..`, sans `/` initial | Non | `\`→`/`, segments `.`/vides retirés |
| `input.content` (`write_file`) | Oui (peut être vide) | **524 288 octets UTF-8** | texte | Non | refus au-delà (`invalid_input`) |
| `timeoutMs` | Oui | — | entier > 0 ; **30 000** pour les outils fichiers | Non | — |

Bornes imposées par le contrat de messages §5 : tampon de trame 1 048 576 octets des deux côtés ;
`content` d'un `tool_result` ≤ 524 288 octets (au-delà `truncated=true` → suffixe
`… (contenu tronqué)` ajouté au texte modèle) ; agrégat `bash` ≤ 131 072 octets (bufferisé mais non
exploité ici) ; grâce backend = `timeoutMs + 5 000 ms`.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|--------------|
| PUT | `/api/v1/workspaces/{id}/execution-target` | Oui (JWT) | propriétaire du workspace |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | ALTER (ajout `execution_target`) | `varchar(16) NOT NULL DEFAULT 'SANDBOX'` |

### Migration Liquibase

- [x] Oui — `048-workspaces-execution-target.xml` (changeSets `…-postgresql` et `…-h2`, rollback `dropColumn`)

### Composants Angular

Aucun (→ SF-38-06).

### Classes

| Classe | Rôle |
|--------|------|
| `WorkspaceExecutionTarget` (nouveau) | Enum `SANDBOX` \| `RUNNER` |
| `Workspace` (modifiée) | Colonne `execution_target`, `executionTargetOrDefault()`, `isRunnerTarget()` |
| `WorkspaceService.setExecutionTarget` (nouveau) | Bascule la cible du workspace possédé |
| `ExecutionTargetRequest` (nouveau) | DTO de requête |
| `RunnerCallDispatcher` (nouveau) | Sessions décorées par workspace, appels en vol par `id`, émission `tool_call`/`tool_cancel`, réception `ready`/`tool_stream`/`tool_result`/`protocol_error`, délais, purge à la fermeture |
| `RunnerCallResult` / `RunnerCallRequest` (nouveaux) | Résultat et demande d'appel, neutres vis-à-vis du transport |
| `RunnerErrorCodes` (nouveau) | Liste close des codes backend + messages modèle en français |
| `RunnerToolGateway` (nouveau) | Façade métier : 4 outils fichiers, normalisation de chemin, bornes, délais par défaut |
| `RunnerWebSocketHandler` (modifiée) | Attache/détache la session décorée, aiguille les trames non-heartbeat vers le dispatcher |
| `RunnerWebSocketConfig` (modifiée) | Bean `ServletServerContainerFactoryBean` (1 Mio) |
| `AtelierChatService` (modifiée) | Routage `SANDBOX`/`RUNNER`, garde-fou Git conditionné, consigne système et recherche via runner |
| `AtelierSessionService` (modifiée) | Refus d'ouverture de session Managed Agents en cible `RUNNER` (D2) |
| `ExecutionTargetModeException` (nouveau) | 409 `execution_target_runner` |

---

## Plan de test

### Tests unitaires

- [ ] `RunnerCallDispatcher` — nominal : `call()` émet une trame `tool_call` conforme et se résout
      sur le `tool_result` correspondant.
- [ ] `RunnerCallDispatcher` — `findLocal` vide + `isConnected` vrai → `runner_not_on_this_node`,
      **aucune** trame émise.
- [ ] `RunnerCallDispatcher` — aucun runner → `runner_unavailable`.
- [ ] `RunnerCallDispatcher` — pas de réponse → `runner_timeout` + `tool_cancel(timeout)` émis.
- [ ] `RunnerCallDispatcher` — fermeture de socket avec appel en vol → `runner_unavailable`.
- [ ] `RunnerCallDispatcher` — `tool_result` d'un autre workspace → ignoré (l'appel reste en vol).
- [ ] `RunnerCallDispatcher` — `protocol_error` avec `id` en vol → `runner_protocol_error`.
- [ ] `RunnerToolGateway` — `write_file` > 512 Kio → `invalid_input` sans émission ; chemin `..`
      normalisé avant émission ; `timeoutMs` = 30 000.
- [ ] `AtelierChatService` — cible `RUNNER` : `read_file` passe par le runner, **jamais** par
      `WorkspaceService` ; `write_file` renvoie « Fichier écrit : … » ; `truncated` → suffixe
      `… (contenu tronqué)`.
- [ ] `AtelierChatService` — cible `RUNNER` + source `GIT` : la boucle **n'est pas** refusée.
- [ ] `AtelierChatService` — cible `SANDBOX` + source `GIT` : refus inchangé (non-régression).
- [ ] `AtelierChatService` — cible `RUNNER` : la consigne système vient du runner.
- [ ] `AtelierChatService` — cible `RUNNER` : `search_files` = **un** appel runner.
- [ ] `AtelierChatService` — `id` d'appel vide → UUID généré, identique dans le bloc `tool_result`.
- [ ] `RunnerWebSocketHandler` — `heartbeat` inchangé ; `tool_result` aiguillé vers le dispatcher ;
      type inconnu ignoré ; fermeture → purge avant `unregister`.

### Tests d'intégration

- [ ] `PUT /workspaces/{id}/execution-target` → 200 et cible persistée.
- [ ] `PUT /workspaces/{id}/execution-target` → 400 sur valeur inconnue.
- [ ] `PUT /workspaces/{id}/execution-target` → 404 sur un workspace d'un autre utilisateur.
- [ ] `GET /workspaces/{id}` → expose `executionTarget`.
- [ ] Le contexte Spring démarre avec le bean `ServletServerContainerFactoryBean` (1 Mio).

### Isolation workspace

- [x] Applicable — deux niveaux : (1) HTTP, `requireOwned(userId, id)` en premier sur le `PUT` et
      dans la boucle ; test 404 cross-utilisateur. (2) Canal, une trame reçue n'est rattachée à un
      appel en vol que si le `workspaceId` de l'appel est **celui de la `RunnerIdentity` de la
      session** — jamais un identifiant lu dans le message ; test dédié.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-01` — done ; `SF-38-02` — done ; `SF-38-03` — done ; `SF-38-04` — done ; `SF-38-10` — done.

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` n'est tranchée ici.

---

## Notes et décisions

- **FLAG multi-replica (contrat §8, non résolu en douce).** `isConnected()` est vrai cross-replica
  (PgNotify + fraîcheur `last_seen_at`) mais la socket ne vit que sur un nœud. Le routage n'utilise
  donc que `findLocal()`, et un appel adressé à un runner hébergé par l'autre pod échoue
  immédiatement en `runner_not_on_this_node`. **Aucun relais inter-pods n'est implémenté en v1**
  (`NOTIFY` est plafonné à 8 000 octets : inutilisable pour du contenu de fichier). Conséquence
  d'exploitation à assumer : **le mode `RUNNER` suppose un replica unique ou une affinité d'ingress**.
  Signalé ici, à trancher hors de cette subfeature.
- **Arbitrage (réversible) — endpoint de bascule livré ici** plutôt qu'en SF-38-06 : l'écran a besoin
  d'un contrat backend stable avant d'exister, et l'endpoint est le pendant naturel de la colonne.
- **Arbitrage (réversible) — la cible est libre de la source.** `GIT` + `RUNNER` est explicitement
  permis (le dépôt est cloné sur la machine de l'utilisateur). Seule la combinaison
  `SANDBOX` + `GIT` garde son garde-fou historique.
- **Arbitrage (réversible) — refus explicite des Managed Agents en cible `RUNNER`** (409) plutôt
  qu'un basculement silencieux : D2 dit que les outils y sont exécutés chez le fournisseur, donc
  hors de portée du reroutage ; un refus lisible vaut mieux qu'une session qui travaille au mauvais
  endroit.
- **La `WebSocketSession` n'entre pas dans `RunnerConnection`** (contrat §7) : `PgNotifyRunnerRegistry`
  fabrique aussi des connexions distantes, sans socket. Le dispatcher tient la carte des sockets.
- **Pas de rejeu.** Un appel perdu avec la socket devient une erreur rendue au modèle ; rejouer un
  `write_file` serait destructeur.
