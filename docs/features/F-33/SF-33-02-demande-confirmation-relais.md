# Mini-spec — [F-33 / SF-02] Demande de confirmation relayée et réponse bornée (backend)

---

## Identifiant

`F-33 / SF-02`

## Feature parente

`F-33` — Validation d'action avant exécution

## Statut

`done` — livrée le 2026-08-25 (PR #156)

## Date de création

2026-08-25

## Branche Git

`feat/SF-33-02-confirmation-rendez-vous`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Relayer à l'utilisateur, **dans le flux d'exécution en cours**, la commande que l'agent veut lancer,
et lui permettre d'**autoriser ou refuser** par un endpoint dédié — avec un délai borné au terme
duquel le silence vaut **refus**.

---

## Contexte

SF-33-01 pose la politique : la session ouvre en `always_ask` sur `bash`. Sans cette SF, l'agent
demanderait dans le vide et le run se figerait jusqu'au timeout.

### ⚠️ Le piège central (tracé au cadrage, à traiter ici)

**Une session en attente de confirmation émet `session.status_idle`.** Or `awaitCompletion` traite
aujourd'hui `session.status_idle` comme **la fin du run** : sans traitement spécifique, une demande de
confirmation serait lue comme « l'agent a terminé », le run se clôturerait, et la commande ne
s'exécuterait jamais — silencieusement.

Le fournisseur donne le moyen de distinguer les deux : l'`idle` d'attente porte une **raison d'arrêt**
`requires_action`, tandis que l'`idle` de fin porte `end_turn` (ou une autre raison terminale). Le run
ne se termine donc que sur un `idle` **non** `requires_action`.

Second point : le run est **synchrone** sur le pool SSE, la réponse de l'utilisateur arrive sur une
**autre requête HTTP**. Le rendez-vous ne passe pas par un verrou partagé : la réponse est postée à la
session chez le fournisseur, et la boucle de polling la voit revenir dans le flux d'events. Cela
fonctionne aussi quand les deux requêtes tombent sur **deux répliques différentes**.

---

## Comportement attendu

### Cas nominal

1. Un run est en cours sur un projet ayant activé l'option (SF-33-01).
2. L'agent veut lancer une commande : le fournisseur émet un usage d'outil marqué « à autoriser »
   (`evaluated_permission: ask`), puis passe la session `idle` avec `stop_reason: requires_action`.
3. La boucle d'attente **ne clôt pas le run** : elle relaie la demande (identifiant, outil, commande)
   et continue à interroger la session.
4. Le flux SSE émet un événement `confirm_request` : l'écran affiche la commande et attend.
5. L'utilisateur appelle `POST /workspaces/{id}/agent/confirm` avec `allow` ou `deny` (+ motif).
6. Le relais poste `user.tool_confirmation` à la session. L'agent exécute, ou reçoit le refus **et son
   motif**, et poursuit autrement. Le run se termine normalement par son `done`.
7. Le flux SSE émet `confirm_resolved` (quelle demande, quelle décision) pour que l'écran retire
   l'invite — y compris quand la décision a été prise ailleurs (autre onglet, autre réplique).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune réponse dans le délai imparti | **Refus automatique** avec motif explicite (D3 du cadrage) ; le run continue, l'agent est informé |
| Workspace inexistant ou appartenant à un autre utilisateur | `404 not_found` — aucun appel fournisseur |
| Aucune session en cours pour ce workspace | `409 no_active_session` |
| Identifiant de demande inconnu / déjà tranché (réponse concurrente, expiration) | `502 provider_error` — la demande n'était plus à trancher ; le run n'est pas affecté |
| Le fournisseur refuse le relais (session morte, panne) | `502 provider_error` |
| `decision` absente ou hors `allow`/`deny` | `400 validation_error` |
| Non authentifié | `401` |
| Refus automatique qui croise une réponse humaine | La seconde réponse est rejetée par le fournisseur ; l'échec est **avalé** côté boucle (log debug) — le run est déjà réglé |

---

## Critères d'acceptation

- [ ] Un `idle` portant `requires_action` **ne termine pas** le run : la boucle continue
- [ ] Un `idle` terminal (`end_turn`, autre) termine le run comme avant (non-régression)
- [ ] Un usage d'outil « à autoriser » est relayé avec l'**identifiant d'event** (`sevt_…`), et non le
      `tool_use_id` — c'est celui-là que le fournisseur attend en réponse
- [ ] `POST /workspaces/{id}/agent/confirm` poste `user.tool_confirmation` (`allow` / `deny` + motif)
- [ ] `requireOwned` appelé **avant tout appel fournisseur** ; workspace d'un autre utilisateur ⇒ `404`
- [ ] Sans réponse dans `confirmTimeout`, la boucle poste un **refus** motivé et le run se poursuit
- [ ] Une réponse déjà donnée (event de confirmation vu dans le flux) **annule** l'échéance
- [ ] Les événements SSE `confirm_request` et `confirm_resolved` sont **additifs** (un client qui les
      ignore garde le comportement actuel)
- [ ] Un projet sans l'option ne voit **aucun** de ces chemins (aucune demande, aucun événement)
- [ ] Aucune clé d'API ni donnée utilisateur journalisée sur ce chemin

---

## Périmètre

### Hors scope

- Invite d'autorisation à l'écran → **SF-33-03**
- Mémorisation d'un choix (« toujours autoriser ce type de commande »)
- Règles automatiques par motif de commande (liste blanche/noire)
- Reprise d'une demande après rechargement de page : le flux SSE porte l'invite ; une page rechargée
  perd l'invite, la demande expire alors par le délai (refus) — comportement assumé et sûr

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| `toolUseId` | non vide (`@NotBlank`) ; jamais fabriqué côté client à partir d'autre chose que l'événement reçu |
| `decision` | `allow` ou `deny` (insensible à la casse) ; toute autre valeur ⇒ `400` |
| `reason` | facultatif, borné à **500** caractères ; relayé tel quel à l'agent (aucune interprétation) |
| Délai de réponse | `app.atelier.agent.confirm-timeout`, défaut **PT2M**, borné par le `sessionTimeout` (PT10M) |
| Silence | vaut **refus**, jamais autorisation (D3 du cadrage) |
| Commande relayée | tronquée comme les autres détails d'action (aucune nouvelle borne) |

---

## Technique

### Contrat API (figé — importé tel quel par SF-33-03)

| Méthode | Chemin | Corps | Réponse |
|---------|--------|-------|---------|
| `POST` | `/api/workspaces/{id}/agent/confirm` | `{"toolUseId":"sevt_1","decision":"allow"}` ou `{"toolUseId":"sevt_1","decision":"deny","reason":"trop risqué"}` | `204 No Content` |

Erreurs (corps `{"error": "...", "message": "..."}`) :

| Code HTTP | `error` | Sens |
|-----------|---------|------|
| `400` | `validation_error` | `toolUseId` vide, `decision` absente ou inconnue, `reason` > 500 |
| `401` | `unauthorized` | Non authentifié |
| `404` | `not_found` | Workspace inconnu ou non possédé |
| `409` | `no_active_session` | Aucune exécution en cours |
| `502` | `provider_error` | Demande inconnue/déjà tranchée, ou fournisseur indisponible |

Évolutions **additives** du flux SSE `POST /api/workspaces/{id}/agent/stream` :

```
event:confirm_request
data:{"toolUseId":"sevt_1","tool":"bash","detail":"rm -rf build"}

event:confirm_resolved
data:{"toolUseId":"sevt_1","decision":"deny"}
```

`decision` de `confirm_resolved` vaut `allow`, `deny`, ou `timeout` (refus automatique).

### Tables impactées / Migration

**Aucune.** La demande en attente ne vit que le temps du run : elle appartient au flux d'events du
fournisseur, jamais à la base.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/ManagedEventListener.java` | `onConfirmationRequest` / `onConfirmationResolved` |
| `atelier/agent/AtelierAgentListener.java` | Miroir applicatif des deux callbacks |
| `atelier/agent/AnthropicManagedAgentProvider.java` | Détection `ask` + `requires_action`, relais `user.tool_confirmation`, échéance de refus |
| `atelier/agent/ManagedAgentProvider.java` | `confirmToolUse(sessionId, toolUseId, allow, message)` |
| `atelier/agent/AtelierAgentProperties.java` | `confirmTimeout` (défaut `PT2M`) |
| `atelier/agent/AtelierSessionService.java` | `confirmToolUse(userId, workspaceId, …)` + pont des deux callbacks |
| `atelier/dto/AgentConfirmRequest.java` | DTO de requête |
| `atelier/AtelierAgentController.java` | `POST /agent/confirm` + événements SSE |
| `resources/application.yml` | `confirm-timeout` |

---

## Plan de test

### Tests unitaires

- [ ] Provider : `idle` + `requires_action` ⇒ la boucle **continue** (pas de `SessionRun` renvoyé)
- [ ] Provider : `idle` + `end_turn` ⇒ le run se termine (non-régression)
- [ ] Provider : usage d'outil `evaluated_permission=ask` ⇒ `onConfirmationRequest(eventId, tool, commande)`
- [ ] Provider : l'identifiant relayé est **l'`id` de l'event**, pas le `tool_use_id`
- [ ] Provider : `user.tool_confirmation` vu dans le flux ⇒ `onConfirmationResolved(id, décision)`, échéance annulée
- [ ] Provider : échéance dépassée ⇒ `POST` d'un `deny` motivé + `onConfirmationResolved(id, "timeout")`
- [ ] Provider : `confirmToolUse` poste `{"events":[{"type":"user.tool_confirmation","tool_use_id":…,"result":"allow"}]}`
- [ ] Provider : refus avec motif ⇒ `result: deny` et `message` porté
- [ ] Provider : échec de refus automatique **avalé** (le run se poursuit)
- [ ] Service : `confirmToolUse` ⇒ `requireOwned` d'abord ; workspace d'un autre utilisateur ⇒ 404, `verifyNoInteractions(provider)`
- [ ] Service : aucune session ⇒ `NoActiveSessionException`, aucun appel fournisseur
- [ ] Service : la demande et sa résolution sont relayées au listener applicatif

### Tests d'intégration

- [ ] `POST /workspaces/{id}/agent/confirm` `allow` ⇒ `204`
- [ ] `deny` + motif ⇒ `204`, motif transmis au service
- [ ] Sans authentification ⇒ `401`
- [ ] Workspace d'un autre utilisateur ⇒ `404 not_found`
- [ ] Aucune session ⇒ `409 no_active_session`
- [ ] Échec fournisseur ⇒ `502 provider_error`
- [ ] Corps invalide (`decision` inconnue) ⇒ `400`
- [ ] Le flux SSE émet `confirm_request` puis `confirm_resolved`

### Isolation utilisateur

- [x] **Applicable** — `requireOwned(userId, workspaceId)` en première instruction ; l'identifiant de
  session n'est **jamais** accepté du client, il est lu sur le workspace possédé. Le `toolUseId` fourni
  par le client ne désigne rien à lui seul : il n'a d'effet que dans la session de **ce** workspace.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement du Principal. Endpoint sous `/workspaces/**`, déjà couvert par la configuration de sécurité. |
| Contexte tenant | **Oui** | Nouvel endpoint agissant sur la session d'un workspace. Composants vérifiés : `AtelierAgentController` (`currentUser.requireId()` seul), `AtelierSessionService.confirmToolUse` (`requireOwned` en premier, session lue sur le workspace possédé), `AnthropicManagedAgentProvider` (ne reçoit qu'un identifiant de session déjà résolu). Aucun autre composant ne résout de tenant sur ce chemin. |
| Plans / limites | **Oui** | Répondre à une demande ne consomme rien : **aucun pré-vol quota** — refuser la réponse faute de quota laisserait le run bloqué jusqu'au refus automatique, en facturant l'attente. Le temps d'attente reste décompté par le chemin habituel (`recordSessionUsage` en fin de tour) : c'est du bac à sable réellement réservé. Appels vérifiés : `assertWithinQuota`, `assertWithinSandboxLimit`, `recordUsage`, `recordSandboxSeconds` — aucun modifié. |
| Navigation / routing | **Non** | Aucun écran ni route frontend (SF-33-03). |

---

## Dépendances

- **F-33 SF-33-01** (option + politique `always_ask`) — mergée.
- **F-30 SF-30-01/02** (relais des actions et sorties en SSE) — même canal.

---

## Notes et décisions

- **Le rendez-vous passe par la session, pas par un verrou** : le run tourne sur le pool SSE, la
  réponse arrive sur un autre thread — et possiblement sur une autre réplique. La réponse est postée
  au fournisseur ; la boucle la voit revenir dans le flux d'events. Aucun état partagé entre
  instances, donc rien à synchroniser.
- **Le silence vaut refus** (D3) : c'est le sens même de la fonctionnalité. Le refus automatique porte
  un motif explicite, pour que l'agent sache qu'il n'a pas été jugé mais oublié.
- **L'échéance vit dans la boucle de polling**, seule à savoir ce qui est encore en attente. Elle est
  annulée dès que la confirmation est vue dans le flux — y compris quand elle vient d'ailleurs.
- **Un refus automatique qui croise une réponse humaine** est rejeté par le fournisseur : l'échec est
  avalé (log debug). Faire échouer un run parce qu'on a répondu deux fois serait absurde.
- **`tool_use_id` = identifiant de l'event** (`sevt_…`), pas le `toolu_…` du bloc d'outil. C'est le
  contrat du fournisseur, et s'en écarter produirait un refus silencieux de la confirmation.
- **Pas de persistance de la demande** : elle ne vaut que pendant le run. Une page rechargée perd
  l'invite et la demande expire en refus — le résultat sûr.
