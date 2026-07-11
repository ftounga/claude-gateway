# SF-28-02 — Atelier : Claude lit/édite les fichiers via une boucle tool-use (backend)

Parent : **F-28 — Atelier (Claude Code Lite — Phase 1)** · Type : backend · Statut : En cours

## Objectif (une phrase)
Permettre à l'utilisateur de discuter avec Claude à propos de son workspace, Claude pouvant **lire et éditer les fichiers** via une **boucle d'outils** exécutée par le backend (opérations fichiers uniquement, **sans exécution de commandes**).

## Comportement nominal
1. `POST /workspaces/{id}/chat {message}` : le backend construit la consigne système (contenu de `CLAUDE.md` + skills du workspace + rôle de l'agent), expose 4 outils bornés au workspace + `user_id` (`list_files`, `read_file`, `write_file`, `search_files`), et lance la **boucle** : `nextTurn` → si le modèle demande des outils, le backend les exécute et renvoie les `tool_result`, puis relance — jusqu'à la réponse finale (garde-fou : max 12 itérations).
2. Réponse : `{reply, actions:[{type:read|write, path}], messageId}`. L'échange (user + réponse finale) est **persisté par workspace** (`atelier_messages`). L'usage tokens est comptabilisé (F-10).
3. `GET /workspaces/{id}/chat` : historique.

## Cas d'erreur / sécurité
| Cas | Réponse |
|-----|---------|
| Workspace d'un autre utilisateur | **404** (isolation) |
| Quota atteint | **402** (avant tout appel fournisseur) |
| Outil avec chemin invalide (`..`/absolu) ou fichier introuvable | l'outil renvoie un **tool_result d'erreur** (le modèle se corrige) ; **aucun fichier hors workspace n'est touché** |
| Boucle qui ne converge pas | stoppée à 12 itérations, message de reprise |

## Architecture (Provider Independence, Gateway-First)
- Modèle neutre `agent/*` : `AgentTool`, `AgentToolCall`, `AgentContentBlock` (text/tool_use/tool_result), `AgentMessage`, `AgentTurn`, `AgentTurnRequest`.
- `AiAgentProvider` (interface) : `AgentTurn nextTurn(req)` = **un** aller-retour. Impl `AnthropicAgentProvider` (mapping `/v1/messages` tools + tool_use/tool_result + stop_reason + usage). Stub scriptable en test.
- `AtelierChatService` orchestre la boucle + outils (via `WorkspaceService`, isolation `user_id`) + quota + persistance. Le domaine ne dépend jamais d'Anthropic en direct.

## Critères d'acceptation
- [ ] La boucle exécute réellement les outils : un `write_file` modifie le fichier dans le workspace (testé).
- [ ] Réponse finale + actions remontées ; échange persisté ; usage comptabilisé.
- [ ] Isolation `user_id` (404 cross-user) ; path traversal refusé sans toucher l'extérieur.
- [ ] Garde-fou d'itérations. Aucun réseau en test (stub). Pas d'exécution de commandes (Phase 2).

## Tables / endpoints
- Nouvelle table `atelier_messages` (migration `037`, FK → `workspaces` CASCADE). Endpoints `POST`/`GET /workspaces/{id}/chat`.

## Hors périmètre
- Streaming des étapes (frontend/polish, SF-28-03). Exécution de code / agents (Phase 2). UI (SF-28-03).
