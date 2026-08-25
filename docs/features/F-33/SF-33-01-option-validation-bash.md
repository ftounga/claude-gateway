# Mini-spec — [F-33 / SF-01] Option « demander avant d'exécuter » par projet (backend)

---

## Identifiant

`F-33 / SF-01`

## Feature parente

`F-33` — Validation d'action avant exécution

## Statut

`done` — livrée le 2026-08-25 (PR #155)

## Date de création

2026-08-25

## Branche Git

`feat/SF-33-01-validation-bash-option`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Donner à chaque projet une **option d'activation** (désactivée par défaut) qui, une fois posée, fait
ouvrir la session d'agent avec `permission_policy: always_ask` sur le seul outil `bash`, de sorte que
l'agent **demande l'autorisation** avant d'exécuter une commande.

---

## Contexte

L'agent tourne en `always_allow` : il exécute tout sans demander. C'était acceptable quand la sandbox
était **détruite à chaque message** — une erreur disparaissait avec la session. Depuis F-30 SF-30-04,
la sandbox **survit d'un message à l'autre** (ADR-014) : un `rm -rf` malheureux persiste, et sur un
projet Git (F-31) il porte sur un vrai dépôt cloné.

Provider-First : l'API expose déjà la capacité (`permission_policy` par outil, réglable pour une
session via `agent_with_overrides`) — on la **relaie**, on ne réimplémente aucune liste blanche de
commandes côté Gateway.

Cette SF pose **l'option et la politique** ; la demande de confirmation relayée à l'utilisateur et sa
réponse sont **SF-33-02**. Tant que SF-33-02 n'est pas livrée, activer l'option ferait attendre la
session sans interlocuteur : l'option reste donc **désactivée par défaut** (D1 du cadrage) et n'est
exposée à l'écran qu'en **SF-33-03**.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur appelle `PUT /workspaces/{id}/agent/confirmation` avec `{"enabled": true}`.
2. `requireOwned` d'abord (isolation `user_id`), puis la valeur est persistée sur le workspace.
3. La réponse porte l'état retenu **et** `appliesToCurrentSession` : `false` quand une session est
   déjà ouverte (la politique d'outils est fixée à l'ouverture), `true` sinon.
4. À la **prochaine ouverture de session**, la session est créée avec une surcharge d'outils portant
   `permission_policy: {"type": "always_ask"}` sur `bash` — tous les autres outils restent en
   `always_allow`.
5. Option désactivée (défaut) : la session est créée **exactement comme avant F-33**, sans surcharge
   d'outils — aucune régression pour qui n'active rien.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Workspace inexistant ou appartenant à un autre utilisateur | `404 not_found` — aucune écriture |
| Corps absent ou `enabled` non booléen | `400 validation_error` |
| Non authentifié | `401` |
| Option activée alors qu'une session est déjà ouverte | `200` avec `appliesToCurrentSession: false` — la session en cours **n'est pas** modifiée (la dire protégée serait faux) |
| Option activée puis désactivée avant l'ouverture d'une session | La session s'ouvre sans surcharge (dernière valeur enregistrée) |

---

## Critères d'acceptation

- [ ] Colonne `agent_ask_before_bash` sur `workspaces`, `NOT NULL DEFAULT false` (migration `044`)
- [ ] `PUT /workspaces/{id}/agent/confirmation` enregistre l'option et renvoie `{enabled, appliesToCurrentSession}`
- [ ] `requireOwned` appelé **avant toute écriture** ; workspace d'un autre utilisateur ⇒ `404`
- [ ] Option **off** ⇒ corps de création de session **inchangé** (aucun champ `tools`) — non-régression
- [ ] Option **on** ⇒ la session est créée avec une surcharge d'outils : `default_config` en
      `always_allow`, `configs: [{name: "bash", permission_policy: {"type": "always_ask"}}]`
- [ ] La surcharge d'outils coexiste avec la surcharge de prompt système de F-34 (les deux dans le
      même `agent_with_overrides`)
- [ ] `GET /workspaces/{id}` expose `askBeforeBash` (champ **additif**)
- [ ] Le domaine ne connaît que `ManagedAgentProvider` : aucune forme JSON Anthropic hors du provider
- [ ] Aucune clé d'API ni donnée utilisateur journalisée sur ce chemin

---

## Périmètre

### Hors scope

- Relais de la demande de confirmation et endpoint de réponse → **SF-33-02**
- Bouton d'activation et invite d'autorisation à l'écran → **SF-33-03**
- Règles fines par commande (liste blanche/noire), mémorisation d'un choix
- Validation des écritures de fichiers (`write`/`edit`) : seul `bash` exécute (D2 du cadrage)
- Application immédiate à une session **déjà ouverte** (l'API le permet par `sessions.update`, mais
  un échec silencieux ferait croire à une protection inexistante — voir Notes)

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| `enabled` | booléen **obligatoire** (`@NotNull`) ; toute autre valeur ⇒ `400` |
| Valeur par défaut | `false` pour tous les projets, existants comme nouveaux |
| Portée | Par **workspace** (donc par utilisateur, via l'isolation), jamais globale |
| Moment d'application | À l'**ouverture** de session ; une session déjà ouverte garde sa politique |
| Outil visé | `bash` uniquement (constante backend, non paramétrable par l'utilisateur) |

---

## Technique

### Contrat API (figé — importé tel quel par SF-33-03)

| Méthode | Chemin | Corps | Réponse |
|---------|--------|-------|---------|
| `PUT` | `/api/workspaces/{id}/agent/confirmation` | `{"enabled": true}` | `200` `{"enabled": true, "appliesToCurrentSession": false}` |

Erreurs (corps `{"error": "...", "message": "..."}`) :

| Code HTTP | `error` | Sens |
|-----------|---------|------|
| `400` | `validation_error` | `enabled` absent ou non booléen |
| `401` | `unauthorized` | Non authentifié |
| `404` | `not_found` | Workspace inconnu ou non possédé |

Évolution **additive** de `GET /api/workspaces/{id}` : champ booléen `askBeforeBash`.

### Tables impactées / Migration

| Table | Changement |
|-------|-----------|
| `workspaces` | `+ agent_ask_before_bash boolean NOT NULL DEFAULT false` |

Migration `044-workspaces-agent-confirmation.xml` (PostgreSQL + H2, `rollback` = `dropColumn`).

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `db/changelog/migrations/044-workspaces-agent-confirmation.xml` | Migration |
| `atelier/Workspace.java` | Champ `agentAskBeforeBash` |
| `atelier/WorkspaceService.java` | `setAskBeforeBash(userId, workspaceId, enabled)` (isolation d'abord) |
| `atelier/dto/WorkspaceDetailResponse.java` | Champ additif `askBeforeBash` |
| `atelier/dto/AgentConfirmationRequest.java` | DTO de requête (`@NotNull Boolean enabled`) |
| `atelier/dto/AgentConfirmationResponse.java` | DTO de réponse |
| `atelier/AtelierAgentController.java` | `PUT /agent/confirmation` |
| `atelier/agent/SessionPermissions.java` | Politique d'outils **du domaine** (aucune forme Anthropic) |
| `atelier/agent/ManagedAgentProvider.java` | Surcharge `createSession(..., SessionPermissions)` |
| `atelier/agent/AnthropicManagedAgentProvider.java` | Traduction en `agent_with_overrides.tools` |
| `atelier/agent/AtelierSessionService.java` | Politique lue sur le workspace à l'ouverture |

---

## Plan de test

### Tests unitaires

- [ ] `setAskBeforeBash` : `requireOwned` d'abord ; workspace d'un autre utilisateur ⇒ 404, aucune sauvegarde
- [ ] Option off ⇒ `createSession` reçoit une politique « tout autorisé » (non-régression)
- [ ] Option on ⇒ `createSession` reçoit une politique « demander avant `bash` »
- [ ] Option on + instructions de projet (F-34) ⇒ les deux surcharges sont transmises
- [ ] Provider : politique « tout autorisé » ⇒ corps **sans** `tools` (comparaison stricte du corps)
- [ ] Provider : politique « demander » ⇒ corps avec `agent_with_overrides.tools[0].configs[0]` =
      `{name: bash, permission_policy: {type: always_ask}}` et `default_config` en `always_allow`
- [ ] Provider : politique « demander » + prompt système ⇒ `system` **et** `tools` dans le même objet

### Tests d'intégration

- [ ] `PUT /workspaces/{id}/agent/confirmation` `{"enabled":true}` ⇒ `200`, `enabled=true`
- [ ] Session déjà ouverte ⇒ `appliesToCurrentSession=false`
- [ ] Sans authentification ⇒ `401`
- [ ] Workspace d'un autre utilisateur ⇒ `404 not_found`
- [ ] Corps invalide (`{}`) ⇒ `400`
- [ ] `GET /workspaces/{id}` expose `askBeforeBash`

### Isolation utilisateur

- [x] **Applicable** — `requireOwned(userId, workspaceId)` en première instruction du service, avant
  toute lecture/écriture. L'identifiant d'utilisateur vient du JWT, jamais du corps de requête.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement du Principal ni du mode d'authentification. L'endpoint vit sous `/workspaces/**`, déjà couvert par la configuration de sécurité existante. |
| Contexte tenant | **Oui** | Nouvel endpoint écrivant une donnée de workspace. Composants vérifiés : `AtelierAgentController` (`currentUser.requireId()` seul), `WorkspaceService.setAskBeforeBash` (`requireOwned` en premier), `AtelierSessionService.openSession` (lit la politique sur le workspace **déjà possédé**, jamais sur un identifiant reçu du client), `AnthropicManagedAgentProvider` (ne reçoit qu'une politique, aucun identifiant utilisateur). Aucun autre composant ne résout de tenant sur ce chemin. |
| Plans / limites | **Non** | Aucun quota, aucun gate modifié. La politique ne change ni le pré-vol (`assertWithinQuota`, `assertWithinSandboxLimit`) ni le décompte (`recordUsage`, `recordSandboxSeconds`). |
| Navigation / routing | **Non** | Aucun écran ni route frontend (SF-33-03). |

---

## Dépendances

- **F-30 SF-30-04** (session persistante par workspace) — c'est ce qui rend la validation nécessaire.
- **F-34 SF-34-01** (`agent_with_overrides`) — la surcharge d'outils passe par le même objet.

---

## Notes et décisions

- **Désactivé par défaut** (D1 du cadrage) : la fonctionnalité change le rythme d'utilisation de
  l'agent. Qui ne l'active pas garde exactement le comportement d'aujourd'hui, et rien ne peut se
  bloquer en attente d'une confirmation que personne n'attend.
- **`bash` seul** (D2) : c'est l'outil qui exécute. Demander confirmation pour chaque `read` rendrait
  l'agent inutilisable, et refuser une lecture ne protège de rien.
- **Application à l'ouverture, pas à chaud** (arbitrage de cette SF) : l'API permet de changer les
  outils d'une session `idle` (`sessions.update`), mais l'appel peut échouer (session occupée,
  fournisseur indisponible). Une protection annoncée mais non appliquée est pire que pas de
  protection : on préfère dire franchement `appliesToCurrentSession: false` et laisser
  « Réinitialiser la sandbox » (F-30 SF-30-06) donner la main. **Réversible** : brancher
  `sessions.update` plus tard ne changerait pas le contrat.
- **Politique exprimée dans le domaine, traduite dans le provider** : `SessionPermissions` est un
  record du domaine (`askBeforeShellCommands`), la forme `agent_toolset_20260401` /
  `permission_policy` reste confinée à `AnthropicManagedAgentProvider` (Provider Independence).
- **La surcharge d'outils remplace en bloc** : chez le fournisseur, un `tools` de surcharge ne
  fusionne pas avec celui de l'agent. Le provider renvoie donc le toolset complet, avec le même
  `default_config` que celui posé au provisionnement de l'agent — sans quoi la session perdrait les
  outils de lecture/écriture.
