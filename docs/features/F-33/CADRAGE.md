# Cadrage — F-33 : Validation d'action avant exécution

## Identifiant / Statut / Date

`F-33` · `livrée` (SF-33-01 PR #155, SF-33-02 PR #156, SF-33-03 PR #157) · 2026-08-25

## Objectif

Permettre à l'utilisateur d'**autoriser ou refuser une commande** avant que l'agent ne l'exécute.

## Contexte

L'agent tourne en `always_allow` : il exécute tout sans demander. C'était acceptable quand la sandbox
était **détruite à chaque message** — une erreur disparaissait avec la session. Depuis SF-30-04, la
sandbox **survit d'un message à l'autre** : une suppression malheureuse persiste, et l'utilisateur n'a
aucun droit de regard.

## Ce que l'API offre

`permission_policy: {"type": "always_ask"}`, réglable **par outil** (`configs: [{name: "bash", …}]`).
Au déclenchement, la session **se met en pause** et attend un événement client :

```json
{"type": "user.tool_confirmation", "tool_use_id": "…", "result": "allow"}
{"type": "user.tool_confirmation", "tool_use_id": "…", "result": "deny", "message": "…"}
```

## ⚠️ Le piège central — à lire avant d'implémenter

**Une session en attente de confirmation émet `session.status_idle`.** Or `awaitCompletion` traite
aujourd'hui `session.status_idle` comme **la fin du run** : sans traitement spécifique, une demande de
confirmation serait interprétée comme « l'agent a terminé », le run se clôturerait, et la commande ne
s'exécuterait jamais — silencieusement.

Il faut donc distinguer **idle-parce-que-fini** de **idle-en-attente-de-confirmation**, en s'appuyant
sur la demande de confirmation reçue dans le flux d'événements plutôt que sur le seul statut.

Second point : le run est **synchrone** sur le pool SSE, la réponse de l'utilisateur arrive sur une
**autre requête HTTP**. Il faut un rendez-vous entre les deux, avec un délai d'attente borné.

## Décisions par défaut (à contredire si besoin)

| # | Décision | Pourquoi |
|---|----------|----------|
| D1 | **Désactivé par défaut**, activable par workspace | Aucune régression : qui ne l'active pas garde le comportement actuel |
| D2 | Quand activé : `always_ask` sur **`bash` uniquement** | C'est l'outil qui exécute ; demander confirmation pour chaque lecture rendrait l'agent inutilisable |
| D3 | Sans réponse dans le délai imparti → **refus** | Le silence ne vaut pas autorisation : c'est le sens même de la fonctionnalité |
| D4 | Le refus peut porter un **motif**, relayé à l'agent | L'agent peut alors proposer autre chose plutôt que de rester bloqué |
| D5 | Endpoint dédié `POST /workspaces/{id}/agent/confirm`, isolé par `requireOwned` | Cohérent avec les endpoints d'agent existants |

## Découpage

| SF | Contenu |
|----|---------|
| **SF-33-01** | Option d'activation par workspace + `permission_policy` à l'ouverture de session (backend) |
| **SF-33-02** | Demande de confirmation relayée en SSE + endpoint de réponse + rendez-vous borné (backend) |
| **SF-33-03** | Invite d'autorisation dans la vue terminal (autoriser / refuser avec motif) (frontend) |

## Hors scope

Règles fines par commande (liste blanche/noire) ; mémorisation d'un choix (« toujours autoriser ce
type ») ; validation des écritures de fichiers.
