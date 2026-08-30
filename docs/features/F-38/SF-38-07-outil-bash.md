# Mini-spec — F-38 / SF-38-07 — Outil `bash` (exécution de commandes sur le runner)

## Identifiant
`F-38 / SF-38-07`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`done`

## Date de création
2026-08-30

## Branche Git
`feat/SF-38-07-outil-bash`

---

## Objectif

> Donner au mode `RUNNER` un outil **`bash`** : le modèle demande une commande, le **runner**
> l'exécute sur la machine de l'utilisateur, en **diffuse la sortie ligne à ligne** (`tool_stream`)
> jusqu'à la session, rend son **code de sortie**, et l'appel est **borné** (délai, taille) et
> **interruptible**.

---

## Comportement attendu

### Cas nominal

1. **Exposition conditionnelle.** L'outil `bash` n'est ajouté à `buildTools()` que si le workspace est
   en cible **`RUNNER`**. En cible `SANDBOX` la liste d'outils est **inchangée** : le backend
   n'exécute jamais de commande lui-même (Gateway-First).
2. **Opt-in machine.** Le runner n'annonce la capacité `bash` dans sa trame `ready` que s'il a été
   démarré avec `--allow-bash` (ou `CLAUDE_RUNNER_ALLOW_BASH=true`). **Par défaut : désactivé.**
   Sans cette capacité, le backend refuse l'appel **avant émission** (`unsupported_tool`) avec un
   message qui dit quoi faire.
3. **Émission.** `RunnerToolGateway.bash` produit une trame
   `{"type":"tool_call","id":…,"tool":"bash","input":{"command":…,"cwd":…?},"timeoutMs":…}`
   (contrat §2.2). `timeoutMs` = `min(120 000, budget de tour restant)`, jamais nul.
4. **Exécution côté runner.** `BashTool` résout le `cwd` par le `PathGuard` (relatif, confiné,
   exclusions comprises ; défaut = racine `--workspace`), lance `sh -c <command>`
   (`cmd.exe /c` sous Windows), **ferme stdin** (une commande interactive reçoit EOF au lieu de
   pendre), et pompe `stdout` et `stderr` sur **deux threads dédiés** — jamais sur le thread de
   heartbeat ni sur celui de réception (contrat §6, piège identifié au cadrage).
5. **Streaming.** Chaque ligne complète (ou chaque bloc de 16 384 octets sans fin de ligne) part en
   `{"type":"tool_stream","id":…,"seq":N,"stream":"stdout"|"stderr","chunk":…}`. Le compteur `seq`
   est **unique et partagé** par stdout et stderr : l'ordre des `seq` est l'ordre réel, ce qui permet
   au backend de reconstituer l'entrelacement.
6. **Terminaison.** À la sortie du processus, le runner émet **une** trame `tool_result` :
   `ok=true`, `content=""` (la sortie est déjà passée en flux), `exitCode=<code>`, `durationMs`,
   `truncated` si la sortie a été coupée. Un code de sortie non nul reste `ok=true` : la commande a
   bien tourné, son échec est une **information** rendue au modèle.
7. **Relais session.** Le backend relaie chaque `chunk` au fil de l'eau dans le flux SSE du chat
   (événement `output`) ; l'écran l'affiche sous l'étape `bash` en cours. L'étape de progression est
   `AtelierStepEvent("bash", <commande tronquée à 200 caractères>)`.
8. **Résultat modèle** (contrat §3) :
   `"$ <commande>\n<sortie stdout+stderr entrelacée>\n[code de sortie: N]"`, sortie bornée à
   **131 072 octets** (tête conservée) avec le suffixe `\n… (sortie tronquée)` si coupée.
9. **Interruption (réutilise F-32).** `POST /workspaces/{id}/chat/interrupt` marque le tour du
   workspace **possédé** et émet `tool_cancel(reason="user_interrupt")` vers les appels en vol. Le
   runner tue le processus (`destroyForcibly`) et émet quand même son `tool_result` terminal
   (`ok=false`, `cancelled`). La boucle tool-use s'arrête à la **frontière sûre** suivante et rend un
   tour marqué comme interrompu.
10. **Budget de tour.** La boucle s'arrête si le tour dépasse **600 000 ms**, et le délai `bash` est
    ramené au budget restant. `STREAM_TIMEOUT_MS` du `SseEmitter` est relevé à **900 000 ms** et
    documenté : plus aucune combinaison de 12 itérations × 120 s ne peut faire expirer le flux
    pendant que des commandes tournent sur la machine de l'utilisateur.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|----------------------|------|
| Runner démarré **sans** `--allow-bash` | refus **avant émission**, message « L'exécution de commandes n'est pas activée sur ce runner… » | `unsupported_tool` |
| `command` absente, vide, > 8 192 caractères, ou porteuse d'un octet nul | refus **avant émission** | `invalid_input` |
| `cwd` absolu, contenant `..`, ou hors racine | refus (backend normalise ; **le runner fait foi**, D6) | `invalid_input` / `path_outside_root` |
| `cwd` inexistant ou qui n'est pas un dossier | erreur du runner | `not_found` / `not_a_file` |
| `cwd` exclu par `.runnerignore` / deny-list | erreur du runner | `excluded` |
| Le processus ne démarre pas (binaire absent, droits) | erreur du runner | `exec_failed` |
| Délai `timeoutMs` dépassé côté runner | processus **tué**, `tool_result` d'erreur | `timeout` |
| Silence du runner pendant `timeoutMs + 5 000 ms` | `tool_cancel(timeout)` émis, appel terminé | `runner_timeout` |
| `tool_cancel` reçu (interruption utilisateur) | processus **tué**, `tool_result` terminal quand même émis | `cancelled` |
| Une commande est déjà en cours sur ce runner | refus sans lancer de second processus | `denied` |
| Sortie dépassant 262 144 octets côté runner | flux coupé, `truncated=true`, le processus continue | — |
| Aucun runner / runner sur l'autre replica / socket fermée | inchangé (SF-38-05) | `runner_unavailable` / `runner_not_on_this_node` |
| `POST /chat/interrupt` sur un workspace d'un autre utilisateur | 404 (isolation `user_id`) | 404 |

---

## Critères d'acceptation

- [ ] L'outil `bash` est exposé au modèle **uniquement** en cible `RUNNER` ; en `SANDBOX` la liste
      d'outils est identique à avant (4 outils fichiers).
- [ ] Le runner n'annonce `bash` dans `ready` que si `--allow-bash` / `CLAUDE_RUNNER_ALLOW_BASH` est
      posé ; sinon `capabilities=["files"]` et l'appel est refusé **sans émission**.
- [ ] La trame émise est conforme au contrat §2.2 (`tool`=`bash`, `input.command`, `input.cwd`
      optionnel relatif, `timeoutMs` > 0 ≤ 120 000).
- [ ] Le runner émet des `tool_stream` avec un `seq` **partagé** stdout/stderr, croissant de 1, tous
      **avant** le `tool_result`.
- [ ] `tool_result` de `bash` porte `exitCode` ; un code non nul reste `ok=true`.
- [ ] Le texte rendu au modèle est exactement
      `"$ <cmd>\n<sortie>\n[code de sortie: N]"`, borné à 131 072 octets avec
      `\n… (sortie tronquée)` si coupé.
- [ ] L'étape de progression `bash` porte la commande tronquée à 200 caractères ; la sortie arrive
      dans la session au fil de l'eau (événement SSE `output`).
- [ ] `stdout`/`stderr` sont lus sur des threads **dédiés** : le heartbeat continue pendant une
      commande longue (aucune exécution sur `runner-heartbeat`).
- [ ] Un `tool_cancel` tue le processus et produit malgré tout **un** `tool_result` `cancelled`.
- [ ] Deux commandes simultanées sur le même runner : la seconde est refusée `denied` (pas
      d'exécution concurrente non bornée).
- [ ] `POST /workspaces/{id}/chat/interrupt` → 204 sur workspace possédé, 404 sinon ; le tour en
      cours s'arrête et rend un message d'interruption.
- [ ] Le budget de tour (600 s) arrête la boucle et le délai `bash` est clampé au budget restant ;
      `STREAM_TIMEOUT_MS` = 900 000 ms.
- [ ] `cd backend && ./mvnw test` vert ; `cd runner && ./mvnw test` vert ; `npm run build && npm test`
      verts.

---

## Périmètre

### Hors scope (explicite)

- **Validation obligatoire par commande (F-33 en mode runner) et journal d'audit `runner_audit`**
  → **SF-38-08**. Cette subfeature n'introduit **aucun** contournement : `executeToolOnRunner` reste
  le point de passage unique où SF-38-08 branchera la validation avant émission.
- **Repli long-polling** → SF-38-09.
- Terminal interactif, PTY, commandes en arrière-plan, variables d'environnement pilotées par le
  modèle, `stdin` alimenté par le modèle : hors v1.
- Aucun changement au contrat de messages, aucune migration Liquibase, aucune nouvelle table.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|-----------------|-------|
| `--allow-bash` (runner) | `false` | imposée : l'exécution de commandes est **opt-in** sur la machine |
| `capabilities` annoncées | `["files"]` | `["files","bash"]` seulement si l'opt-in est posé |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|--------------|-----------------------------|---------|---------------|
| `input.command` | Oui | 8 192 caractères | non vide après `strip()`, sans octet nul | Non | `strip()` |
| `input.cwd` | Non | 4 096 caractères | relatif, séparateur `/`, sans `..`, sans `/` initial | Non | `\`→`/`, segments `.`/vides retirés |
| `timeoutMs` | Oui | — | entier `[1 000 ; 120 000]`, clampé au budget restant | Non | — |
| `seq` (tool_stream) | Oui | — | entier ≥ 0, +1 par trame, partagé stdout/stderr | par `id` | — |
| `chunk` (tool_stream) | Oui | 16 384 octets | texte UTF-8, découpé sur fin de ligne quand possible | Non | — |
| `exitCode` | Oui pour `bash` | — | entier | Non | — |

Bornes : sortie diffusée par appel ≤ **262 144 octets** côté runner (au-delà `truncated=true`) ;
agrégat conservé et rendu au modèle ≤ **131 072 octets** (tête) ; budget de tour **600 000 ms** ;
`SseEmitter` **900 000 ms** ; **une seule** commande simultanée par runner.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|--------------|
| POST | `/api/v1/workspaces/{id}/chat/interrupt` | Oui (JWT) | propriétaire du workspace |

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `AtelierComponent` — relais de l'événement SSE `output` sous l'étape en cours, bouton
  « Interrompre » du mode Assistant en cible `RUNNER`.
- `AtelierService` — `output` dans `dispatchSseEvent`, `interruptChat(id)`.

### Classes

| Classe | Rôle |
|--------|------|
| `BashTool` (runner, nouveau) | Exécution du processus, pompes stdout/stderr, bornes, kill |
| `ToolRouter` (runner, nouveau) | Aiguillage `bash` → `BashTool`, reste → `FileTools` |
| `ToolContext` (runner, nouveau) | Contexte d'exécution : émission `tool_stream`, délai, annulation |
| `ToolExecutor` (runner, modifiée) | Signature `execute(tool, input, context)` + surcharge de confort |
| `ToolDispatcher` (runner, modifiée) | `tool_stream` (seq partagé), capacités annoncées, contexte |
| `RunnerConfig` (runner, modifiée) | `--allow-bash` / `CLAUDE_RUNNER_ALLOW_BASH` (drapeau booléen) |
| `RunnerCallDispatcher` (backend, modifiée) | Consommateur de flux par appel, `cancelWorkspace` |
| `RunnerToolGateway` (backend, modifiée) | `bash(...)` : bornes, `cwd`, délai, message d'opt-in |
| `AtelierChatService` (backend, modifiée) | Outil `bash` conditionnel, agrégat modèle, budget, interruption |
| `AtelierChatController` (backend, modifiée) | Événement SSE `output`, `POST /chat/interrupt` |
| `AtelierProgressListener` (backend, modifiée) | `onOutput(chunk)` **par défaut neutre** (additif) |

---

## Plan de test

### Tests unitaires

- [ ] `BashTool` — nominal : `echo` → chunk `stdout`, `exitCode=0`, `ok=true`.
- [ ] `BashTool` — `stderr` diffusé séparément, `seq` partagé et croissant.
- [ ] `BashTool` — code de sortie non nul → `ok=true` avec `exitCode` renseigné.
- [ ] `BashTool` — désactivé (pas d'opt-in) → `unsupported_tool`.
- [ ] `BashTool` — `command` vide / trop longue → `invalid_input`.
- [ ] `BashTool` — `cwd` relatif valide → exécution dans ce dossier ; `cwd` hors racine →
      `path_outside_root` ; `cwd` inexistant → `not_found` ; `cwd` fichier → `not_a_file`.
- [ ] `BashTool` — interruption du thread → processus tué, `cancelled`.
- [ ] `BashTool` — sortie volumineuse → coupée, `truncated=true`.
- [ ] `BashTool` — seconde commande simultanée → `denied`.
- [ ] `ToolRouter` — `bash` va au `BashTool`, les 4 autres au `FileTools`.
- [ ] `ToolDispatcher` — `ready` annonce `bash` seulement si activé ; `tool_stream` émis avant le
      `tool_result` avec un `seq` croissant ; un `tool_cancel` produit **une seule** trame terminale.
- [ ] `RunnerConfig` — `--allow-bash`, `--allow-bash=false`, `CLAUDE_RUNNER_ALLOW_BASH`.
- [ ] `RunnerCallDispatcher` — les `chunk` sont relayés au consommateur dans l'ordre puis **détachés**
      dès le résultat ; `cancelWorkspace` émet `tool_cancel(user_interrupt)`.
- [ ] `RunnerToolGateway` — trame `bash` conforme, `cwd` normalisé, commande invalide refusée sans
      émission, `unsupported_tool` traduit en message d'opt-in, délai clampé.
- [ ] `AtelierChatService` — `bash` absent des outils en `SANDBOX`, présent en `RUNNER`.
- [ ] `AtelierChatService` — agrégat `"$ cmd\n…\n[code de sortie: N]"`, troncature, étape `bash`,
      relais `onOutput`.
- [ ] `AtelierChatService` — interruption : la boucle s'arrête et rend le message d'interruption.

### Tests d'intégration

- [ ] `POST /workspaces/{id}/chat/interrupt` → 204 sur workspace possédé.
- [ ] `POST /workspaces/{id}/chat/interrupt` → 404 sur workspace d'un autre utilisateur.
- [ ] `POST /workspaces/{id}/chat/interrupt` → 401 sans jeton.

### Isolation workspace

- [x] Applicable — (1) HTTP : `requireOwned(userId, id)` **en premier** sur `/chat/interrupt`, test
      404 cross-utilisateur ; (2) canal : une trame `tool_stream`/`tool_result` n'est rattachée à un
      appel que si le `workspaceId` de l'appel est celui de la `RunnerIdentity` de la session
      (garde SF-38-05, conservée) ; (3) `cancelWorkspace` n'annule que les appels **de ce workspace**.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-01` … `SF-38-06`, `SF-38-10` — toutes `done`.

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` n'est tranchée ici.

---

## Notes et décisions

- **Arbitrage (réversible) — `bash` est opt-in côté runner (`--allow-bash`, défaut `false`).**
  Démarrer un runner a jusqu'ici signifié « laisse l'assistant lire et écrire ce dossier » ;
  exécuter des commandes arbitraires est un cran au-dessus, et la validation par commande
  (SF-38-08) n'est pas encore livrée. Le seul refus qui ne peut pas être contourné depuis la gateway
  est celui de la machine elle-même. L'alternative — activé par défaut — ouvrirait une fenêtre où
  n'importe quelle commande partirait sans aucune approbation.
- **Arbitrage (réversible) — une seule commande à la fois par runner.** `runLoop` est séquentiel, donc
  la limite ne coûte rien aujourd'hui ; elle évite qu'un runner futur (ou un rejeu) lance N processus
  en parallèle sur la machine de quelqu'un.
- **Arbitrage (réversible) — un code de sortie non nul est `ok=true`.** La commande a tourné ; son
  échec est une information utile au modèle, pas une panne de transport. Réserver `ok=false` aux
  vraies pannes garde les codes d'erreur du contrat lisibles.
- **Arbitrage (réversible) — la sortie partielle n'est pas rendue au modèle en cas d'erreur.** Le
  contrat §3 impose `ok=false → error.message`. Sur un `timeout`, la sortie déjà vue à l'écran reste
  visible dans la session, mais le modèle ne reçoit que le message d'erreur. Limitation assumée.
- **Piège du cadrage n°1 (plafonds qui se percutent) — traité.** `STREAM_TIMEOUT_MS` passe de
  300 000 à 900 000 ms **et** la boucle gagne un budget de tour de 600 000 ms, le délai `bash` étant
  clampé au budget restant. Sans les deux, trois commandes longues suffisaient à clore l'`SseEmitter`
  pendant que la boucle continuait d'exécuter des commandes sur la machine de l'utilisateur.
- **Piège du cadrage n°2 (heartbeat) — traité.** Le processus est attendu sur un thread worker
  (`runner-tool-*`) et ses flux pompés sur deux threads dédiés (`runner-bash-out/err`). Le thread
  `runner-heartbeat` n'est jamais bloqué : `last_seen_at` reste frais pendant une commande longue.
- **FLAG — pas encore de validation par commande.** Tant que SF-38-08 n'est pas livrée, la seule
  garde à l'exécution est l'opt-in `--allow-bash` de la machine. C'est explicitement le rôle de
  SF-38-08 de brancher F-33 avant émission ; rien ici ne la court-circuite.
- **FLAG — multi-replica** : inchangé (contrat §8), le routage reste `findLocal()` seulement.
