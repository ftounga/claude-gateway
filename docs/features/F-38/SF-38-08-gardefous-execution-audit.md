# Mini-spec — F-38 / SF-38-08 — Garde-fous d'exécution et traçabilité

## Identifiant
`F-38 / SF-38-08`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`done`

## Date de création
2026-08-30

## Branche Git
`feat/SF-38-08-gardefous-execution-audit`

---

## Objectif

> En cible `RUNNER`, aucune commande ne part sur la machine de l'utilisateur sans son accord
> explicite (validation **obligatoire et non désactivable**, D7), tout ce que l'agent lit, écrit et
> exécute laisse une **trace** consultable (`runner_audit`, D11), et l'utilisateur dispose d'un
> **coupe-circuit** qui rompt la liaison immédiatement.

---

## Comportement attendu

### Cas nominal

#### 1 — Porte de validation dans la boucle Assistant (D7)

1. Le mécanisme F-33 déjà livré n'est **pas réutilisable tel quel** : `AtelierSessionService.confirmToolUse()`
   relaie la décision au fournisseur Managed Agent, or D2 interdit les Managed Agents en cible
   `RUNNER`. La boucle concernée est `AtelierChatService.runLoop`, qui n'avait **aucun** point de
   confirmation. SF-38-08 y ajoute une porte **neuve**, côté gateway, sans toucher au chemin sandbox.
2. Avant d'émettre un `tool_call` de type **`bash`** en cible `RUNNER`, la boucle **suspend** l'appel
   et demande une décision à l'utilisateur. L'identifiant de la demande est **l'`id` de corrélation
   du contrat** (= `tool_use` du fournisseur, contrat §1) : le même identifiant sert au WS, au SSE et
   à la ligne d'audit. Aucun second identifiant n'est créé.
3. La demande est relayée dans le flux SSE du chat via les événements **existants** `confirm_request`
   (`{toolUseId, tool, detail}`) puis `confirm_resolved` (`{toolUseId, decision}`) — mêmes noms et
   mêmes charges utiles que F-33 côté Terminal, pour que l'écran n'ait qu'un seul modèle mental.
4. L'utilisateur répond via `POST /workspaces/{id}/chat/confirm` (corps `AgentConfirmRequest` :
   `toolUseId`, `decision` = `allow|deny`, `reason` facultatif). Sur **`allow`**, le `tool_call` est
   émis normalement. Sur **`deny`**, il n'est **jamais émis** (contrat §6) : la boucle rend
   directement au modèle un résultat d'erreur reprenant le motif, et le modèle peut proposer autre
   chose.
5. **Le silence ne vaut pas autorisation** : sans réponse dans `app.runner.confirmation.timeout-ms`
   (défaut **120 000 ms**), la demande est **refusée** et `confirm_resolved` porte
   `decision: "timeout"`.
6. **Non désactivable** (D7) : la porte ne lit pas `workspaces.agent_ask_before_bash` en cible
   `RUNNER` — elle s'applique toujours. Le flag est en outre **forcé à `true`** au passage en cible
   `RUNNER`, et toute tentative de le remettre à `false` sur un projet en cible `RUNNER` est
   **refusée** (`409 execution_target_runner`). `always_allow` n'existe pas sur une vraie machine.
7. Interruption (F-32 / SF-38-07) et fin de tour **libèrent** toute demande encore en attente : elle
   est résolue en refus, jamais laissée pendante.

#### 2 — Journal d'audit (D11)

8. Une ligne `runner_audit` est écrite **par appel terminé** et **par appel refusé avant émission**,
   clef de corrélation `call_id` = l'`id` du contrat. Champs : `tool`, `target` (chemin, terme
   recherché ou commande tronquée à 1 000 caractères), `outcome`
   (`OK|ERROR|DENIED|TIMEOUT|CANCELLED`), `error_code`, `exit_code`, `duration_ms`, `bytes`,
   `user_id`, `workspace_id`, `token_id`, `created_at`.
9. `user_id` vient **toujours** du propriétaire du workspace (`requireOwned` en amont), `token_id` du
   registre local (`RunnerRegistry.findLocal`) — jamais d'un champ de message.
10. **Piège du cadrage traité** : `buildSystemPrompt()` lit `CLAUDE.md` + tous les fichiers sous
    `.claude/skills/` et `skills/` à **chaque** message. Ces lectures d'**amorçage** ne produisent
    **pas** une ligne par fichier : elles sont **agrégées en une seule ligne** `tool = "bootstrap"`,
    `target = "consigne système (N lecture(s))"`, `bytes` = total des octets lus. Les lectures
    demandées par le **modèle** restent, elles, une ligne chacune.
11. L'écriture d'audit est **best-effort et non bloquante** : hors transaction (la boucle
    `runLoop` est volontairement non transactionnelle), toute erreur d'écriture est journalisée en
    `warn` et **n'interrompt jamais** le tour. Un audit indisponible ne doit pas empêcher de
    travailler ; l'inverse (bloquer l'agent parce que la trace échoue) serait pire.
12. Le journal est consultable par son propriétaire :
    `GET /workspaces/{id}/runner/audit?limit=<1..200>` (défaut 50), du plus récent au plus ancien,
    sous double filtre `user_id` + `workspace_id`. Un bouton « Journal d'activité » l'affiche dans
    l'Atelier.

#### 3 — Coupe-circuit et révocation immédiate

13. **Révocation immédiate** : `DELETE /workspaces/{id}/runner/tokens/{tokenId}` (existant) ne se
    contente plus de poser `revoked_at` — si la socket de ce jeton vit sur ce nœud, elle est
    **fermée sur-le-champ** et ses appels en vol terminés en `runner_unavailable`. Sans cela, un
    jeton révoqué continuait de servir jusqu'à la fermeture de la connexion : une révocation qui ne
    révoque rien.
14. **Coupe-circuit** : `POST /workspaces/{id}/runner/kill` fait, dans cet ordre, (a) révoquer
    **tous** les jetons actifs du workspace, (b) fermer la socket et terminer les appels en vol,
    (c) ramener la cible d'exécution à **`SANDBOX`**. Le runner ne peut plus se reconnecter (son
    jeton est révoqué : le handshake le refuse) et la boucle ne route plus rien vers la machine.
    Réponse : `{revokedTokens, disconnected, executionTarget}`. Le geste est **tracé**
    (`tool = "kill_switch"`).
15. Idempotent : couper une liaison déjà coupée renvoie `200` avec `revokedTokens: 0`,
    `disconnected: false`. Un coupe-circuit qui échoue parce qu'il n'y a rien à couper serait un
    piège au moment où l'on en a besoin.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|----------------------|-----------|
| `POST /chat/confirm` avec un `toolUseId` inconnu, déjà tranché ou expiré | refus explicite, aucune exécution | 409 `no_pending_confirmation` |
| `POST /chat/confirm` sur le workspace d'un **autre** utilisateur | traité comme inexistant | 404 |
| `POST /chat/confirm` avec `decision` absent ou hors `allow`/`deny` | validation du corps, rien n'est tranché | 400 |
| Refus utilisateur (`deny`) | `tool_call` **jamais émis**, résultat d'erreur au modèle, audit `DENIED` | 200 (flux) |
| Aucune réponse dans le délai | refus automatique, `confirm_resolved: timeout`, audit `TIMEOUT` | 200 (flux) |
| `PUT /agent/confirmation {enabled:false}` sur un projet en cible `RUNNER` | refus : la validation n'est pas désactivable (D7) | 409 `execution_target_runner` |
| `POST /runner/kill` sur le workspace d'un autre utilisateur | traité comme inexistant | 404 |
| `GET /runner/audit?limit=0` ou `limit=1000` | borné à `[1..200]`, jamais d'erreur | 200 |
| Écriture d'audit impossible (base indisponible) | `warn` journalisé, le tour continue | — |
| Runner déconnecté au moment de la validation | l'appel échoue en `runner_unavailable` après décision | 200 (flux) |

---

## Critères d'acceptation

- [x] En cible `RUNNER`, un appel `bash` n'est **jamais** émis avant décision : le test observe qu'aucune trame ne part tant que la confirmation n'est pas résolue.
- [x] `allow` → le `tool_call` part et la commande s'exécute ; `deny` → aucune trame, le modèle reçoit une erreur portant le motif.
- [x] Sans réponse dans le délai, la demande est **refusée** (jamais autorisée), et le flux porte `confirm_resolved: timeout`.
- [x] En cible `SANDBOX`, **aucune** porte de validation ne s'ajoute : la boucle est strictement inchangée (non-régression).
- [x] `PUT /agent/confirmation {enabled:false}` renvoie `409` sur un projet en cible `RUNNER` ; le flag est forcé à `true` au passage en cible `RUNNER`.
- [x] Une ligne `runner_audit` existe pour chaque appel terminé (`OK`), refusé (`DENIED`), expiré (`TIMEOUT`), annulé (`CANCELLED`) et en erreur (`ERROR`), avec `call_id` = `id` de corrélation.
- [x] Les lectures d'amorçage de la consigne système produisent **une seule** ligne `bootstrap`, pas une par fichier.
- [x] `GET /workspaces/{id}/runner/audit` renvoie les lignes du workspace **possédé** uniquement, du plus récent au plus ancien, `limit` borné.
- [x] Révoquer un jeton ferme immédiatement la socket portée par ce jeton et termine ses appels en vol.
- [x] `POST /runner/kill` révoque tous les jetons actifs, ferme la socket, repasse la cible à `SANDBOX`, et est idempotent.
- [x] Isolation : audit, coupe-circuit et confirmation d'un workspace d'un autre utilisateur → `404`, aucune donnée lue ni écrite.
- [x] Aucun secret (jeton runner, clé API) n'apparaît dans une ligne d'audit ni dans un log.
- [x] Frontend : la demande d'autorisation s'affiche dans le fil Assistant (Autoriser / Refuser + motif) et le coupe-circuit est accessible depuis la barre du projet, avec confirmation préalable.

---

## Périmètre

### Hors scope (explicite)

- Validation unitaire des **écritures de fichier** (`write_file`) : voir « Notes et décisions » (arbitrage tracé).
- Politique d'autorisation persistante par commande (liste blanche / motifs mémorisés) : rien n'est mémorisé, chaque commande est tranchée pour elle-même.
- Journal d'audit des sessions **sandbox** (F-30) : `runner_audit` ne trace que la cible `RUNNER`.
- Export / rétention / purge du journal (RGPD au-delà de la suppression du projet).
- Repli long-polling (SF-38-09) et exclusions (SF-38-10, déjà livrée).
- Alerting, seuils, détection d'anomalie sur le journal.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|-----------------|-------|
| `runner_audit.outcome` | — | imposée par l'issue de l'appel : `OK`, `ERROR`, `DENIED`, `TIMEOUT`, `CANCELLED` |
| `runner_audit.created_at` | `now()` | posée à l'écriture, côté application |
| `runner_audit.token_id` | jeton de la connexion locale | `null` si aucun runner local (appel refusé avant émission) |
| `workspaces.agent_ask_before_bash` | forcé `true` | au passage en cible `RUNNER` (D7) |
| `workspaces.execution_target` | `SANDBOX` | après un coupe-circuit |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|--------------|-----------------------------|---------|---------------|
| `toolUseId` (confirm) | Oui | 64 | identifiant de corrélation du contrat §1 | — | `trim()` |
| `decision` (confirm) | Oui | — | `allow` \| `deny` (insensible à la casse) | — | `trim()` |
| `reason` (confirm) | Non | 500 | texte libre, relayé au modèle | — | `trim()`, vide → absent |
| `runner_audit.call_id` | Oui | 64 | tronqué à 64 caractères | Non | — |
| `runner_audit.tool` | Oui | 32 | `list_files`, `read_file`, `write_file`, `search_files`, `bash`, `bootstrap`, `kill_switch` | Non | — |
| `runner_audit.target` | Non | 1 000 | chemin relatif, terme recherché, ou commande tronquée | Non | tronqué à 1 000 |
| `runner_audit.outcome` | Oui | 16 | `OK`, `ERROR`, `DENIED`, `TIMEOUT`, `CANCELLED` | Non | — |
| `runner_audit.error_code` | Non | 32 | liste close du contrat §4 | Non | tronqué à 32 |
| `limit` (audit) | Non | — | entier borné à `[1..200]`, défaut 50 | — | clampé |

Notes :
- `duration_ms` et `bytes` sont recopiés du `tool_result` (contrat §2.4) ; `bytes` reste `null` si le runner ne le renseigne pas.
- Le **message d'erreur** du runner n'est pas stocké : seul son `error_code` l'est. Un message peut contenir un fragment de chemin ; le code, jamais.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|--------------|
| POST | `/api/workspaces/{id}/chat/confirm` | JWT | accès Atelier (Gold/ADMIN) |
| GET | `/api/workspaces/{id}/runner/audit` | JWT | accès Atelier |
| POST | `/api/workspaces/{id}/runner/kill` | JWT | accès Atelier |
| DELETE | `/api/workspaces/{id}/runner/tokens/{tokenId}` | JWT | accès Atelier (comportement **étendu**) |
| PUT | `/api/workspaces/{id}/agent/confirmation` | JWT | accès Atelier (refus 409 en cible `RUNNER`) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_audit` | CREATE / INSERT / SELECT | table neuve, migration 049 |
| `runner_tokens` | UPDATE | `revoked_at` posé par le coupe-circuit |
| `workspaces` | UPDATE | `agent_ask_before_bash` forcé, `execution_target` ramené à `SANDBOX` |

### Migration Liquibase

- [x] Oui — `049-runner-audit.xml` (changeSets `049-runner-audit-postgresql` et `049-runner-audit-h2`, rollback `dropTable`)
- [ ] Non applicable

### Composants Angular

- `AtelierComponent` — invite d'autorisation dans le fil Assistant, bouton coupe-circuit, ouverture du journal.
- `RunnerAuditDialogComponent` *(neuf)* — journal d'activité du runner (dialogue Material).
- `AtelierService` — `confirmChatToolUse`, `killRunner`, `getRunnerAudit`.

---

## Plan de test

### Tests unitaires

- [x] `RunnerConfirmationGate` — `allow` avant échéance → décision `ALLOW`.
- [x] `RunnerConfirmationGate` — `deny` avec motif → décision `DENY`, motif conservé.
- [x] `RunnerConfirmationGate` — silence → `TIMEOUT` (refus), la demande n'est plus en attente.
- [x] `RunnerConfirmationGate` — `resolve` d'un id inconnu → `NoPendingConfirmationException`.
- [x] `RunnerConfirmationGate` — `resolve` par un autre `user_id` → refusé (isolation).
- [x] `RunnerConfirmationGate` — `cancelWorkspace` libère les demandes en attente.
- [x] `AtelierChatService` — cible `RUNNER` + `bash` refusé → aucune trame émise, erreur rendue au modèle, audit `DENIED`.
- [x] `AtelierChatService` — cible `RUNNER` + `bash` autorisé → trame émise, audit `OK` avec `exit_code`.
- [x] `AtelierChatService` — cible `SANDBOX` → aucune demande de confirmation (non-régression).
- [x] `AtelierChatService` — lectures d'amorçage → **une** ligne `bootstrap`, pas N.
- [x] `RunnerAuditService` — écriture en échec → aucune exception propagée.
- [x] `RunnerKillSwitchService` — révoque tous les jetons actifs, ferme la socket, repasse en `SANDBOX` ; idempotent.
- [x] `AtelierSessionService.setAskBeforeBash(false)` en cible `RUNNER` → `ExecutionTargetModeException`.

### Tests d'intégration

- [x] `POST /workspaces/{id}/chat/confirm` → 204 sur une demande en attente.
- [x] `POST /workspaces/{id}/chat/confirm` → 409 sur un id inconnu.
- [x] `POST /workspaces/{id}/chat/confirm` → 400 sans `decision`.
- [x] `GET /workspaces/{id}/runner/audit` → 200, lignes du workspace, `limit` borné.
- [x] `POST /workspaces/{id}/runner/kill` → 200, jetons révoqués, cible `SANDBOX`.
- [x] `DELETE /runner/tokens/{tokenId}` → 204 et socket fermée.

### Isolation workspace

- [x] Applicable — `confirm`, `audit`, `kill` sur un workspace d'un autre utilisateur → `404`, et une décision ne peut pas trancher la demande d'un autre utilisateur.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-01` (jetons) — statut : done
- `SF-38-02` (canal + registre) — statut : done
- `SF-38-03` (runner) — statut : done
- `SF-38-05` (cible `RUNNER`, dispatcher) — statut : done
- `SF-38-07` (outil `bash`, interruption) — statut : done

### Questions ouvertes impactées

- [x] Aucune question ouverte de `docs/OPEN_QUESTIONS.md` n'est levée par cette subfeature.

---

## Notes et décisions

- **Arbitrage — périmètre de la validation unitaire.** La porte couvre **`bash`** seul. C'est la
  lettre de D7 (« la validation d'action (F-33) devient obligatoire ») : F-33 valide l'**exécution
  de commandes**. Étendre la validation à `write_file` a été envisagé — sur une vraie machine une
  écriture est destructrice — mais l'usage central du runner est précisément que l'agent édite les
  fichiers ; un clic par écriture rendrait le mode inutilisable et pousserait à chercher un
  contournement, ce qui affaiblirait la garde au lieu de la renforcer. Les écritures restent
  **tracées** (`runner_audit`), **confinées** (racine `--workspace`, D6), **filtrées** (D10) et
  révocables d'un geste (coupe-circuit). Décision **réversible** : étendre la porte à `write_file`
  ne coûte qu'une ligne dans `requiresConfirmation()`.
- **Porte neuve, pas de réemploi.** Le chemin F-33 existant (`AtelierSessionService.confirmToolUse`)
  reste **inchangé** : il sert le mode Terminal / Managed Agent. La porte de SF-38-08 vit dans
  `RunnerConfirmationGate` et n'est consultée que par `AtelierChatService.runLoop`.
- **Multi-replica.** La porte est en mémoire : la décision doit atteindre le pod qui tient le tour.
  Même contrainte que le routage des appels (contrat §8, déjà flaggée en SF-38-05) : replica unique
  ou affinité d'ingress. Une réponse arrivée sur l'autre pod renvoie `409 no_pending_confirmation`
  plutôt que d'autoriser à l'aveugle — le silence ne vaut pas autorisation, et une décision perdue
  ne vaut pas exécution.
- **Audit hors transaction.** Conforme au contrat §9 : jamais dans la transaction d'un `touch`
  heartbeat, jamais bloquant pour la boucle.
