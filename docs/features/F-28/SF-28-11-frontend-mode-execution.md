# SF-28-11 — Écran Atelier : mode « Exécution » (Phase 2)

Feature parente : **F-28 — Atelier (Claude Code Lite)**
Type : Frontend (Angular) uniquement — préoccupation transversale : **aucune** (aucun endpoint/route/table nouveau ; consomme un endpoint backend déjà livré, SF-28-10)
Statut : En cours

## Objectif (une phrase)

Ajouter à l'écran Atelier un sélecteur **Édition ⇄ Exécution** : en mode « Exécution » (Phase 2), l'utilisateur voit **en direct** les commandes exécutées (bash, tests, build) dans le sandbox hébergé par Anthropic, puis la réponse finale et la liste des fichiers réellement modifiés — sans régression du mode « Édition » (Phase 1, streaming fichier de SF-28-05).

## Comportement nominal

1. L'utilisateur bascule le mode via un `mat-button-toggle-group` près du composer (« Édition » par défaut, « Exécution »).
2. En mode **Exécution**, `send()` ouvre `POST /api/workspaces/{id}/agent/stream` (SSE via `fetch` + `ReadableStream`, calqué sur `streamChat`).
3. Le frontend relaie chaque événement SSE :
   - `agent` `{ text }` → commentaire partiel de l'agent, accumulé ;
   - `action` `{ tool, detail? }` → puce d'étape en monospace (ex. `bash: npm test`) ;
   - `status` `{ state }` → état de session affiché sur le tour en cours ;
   - `done` `{ reply, changedFiles }` → message assistant final + liste des fichiers modifiés ; `refreshTree(id)` ;
   - `error` `{ error: <code> }` → retrait du tour utilisateur optimiste + snackbar lisible.
4. En mode **Édition**, le comportement reste **strictement** celui de SF-28-05 (`streamChat`).

## Cas d'erreur (mode Exécution)

| Code | Message UI |
|---|---|
| `forbidden` | « L'exécution est réservée à l'offre Gold. » |
| `agent_disabled` | « Le mode Exécution est momentanément indisponible. » |
| `workspace_not_found` | « Projet introuvable. » |
| `session_timeout` | « La session a dépassé le temps imparti. Réessayez sur une tâche plus courte. » |
| autre (`provider_error`, `internal_error`, `request_failed`, …) | « L'exécution a échoué. Veuillez réessayer. » |

Dans tous les cas d'erreur : le tour utilisateur optimiste est retiré (rien persisté), `submitting=false`, `execStreaming=null`.

## Critères d'acceptation (vérifiables)

- CA1 : en mode « Exécution », `send()` appelle `streamAgent` et **jamais** `streamChat` ; en mode « Édition » (défaut), l'inverse (non-régression).
- CA2 : à réception de `action`/`agent`/`status`, le tour en cours accumule les étapes (en monospace), le texte et l'état.
- CA3 : à `done`, un message assistant final est ajouté avec `reply`, les `changedFiles` sont affichés, et l'arborescence est rafraîchie (`getWorkspace`).
- CA4 : à `error('forbidden')`, la snackbar affiche le message Gold et **aucun** message assistant n'est ajouté ; le tour utilisateur optimiste est retiré.
- CA5 : `streamAgent` ne lève jamais ; `!response.ok` ⇒ `onError('request_failed')`.
- CA6 : le mode ne peut pas changer pendant un envoi en cours (`submitting`).

## Plan de test minimal

- **Frontend** (`atelier.component.spec`, mock `AtelierService` avec `streamAgent`) :
  - mode « Exécution » : `send()` → `streamAgent` appelé, `streamChat` non appelé ; handlers `onStatus/onAction/onAgent/onDone` → message assistant final avec `reply`, `changedFiles` renseignés, `getWorkspace` rappelé ;
  - accumulation du tour en cours (état + étapes `bash: …` + texte partiel) ;
  - `onError('forbidden')` → snackbar Gold, aucun message assistant ; `onError('session_timeout')` → message de délai ;
  - mode « Édition » (défaut) : `send()` → `streamChat` appelé, `streamAgent` non appelé (non-régression) ;
  - garde-fou : `setAgentMode` ignoré pendant `submitting`.
- **Isolation utilisateur** : garantie côté backend (SF-28-09/10) via `user_id` porté par le JWT ; le frontend ne résout aucun tenant.

## Tables / endpoints / composants impactés

- **Frontend uniquement** :
  - `atelier.models.ts` : `AtelierAgentStreamAction`, `AtelierAgentStreamDone`, `AtelierAgentStreamHandlers`.
  - `atelier.service.ts` : `streamAgent(id, message, handlers)` (fetch + SSE, calqué sur `streamChat`) + `dispatchAgentSseEvent`.
  - `atelier.component.ts/.html/.scss` : signal `agentMode`, `execStreaming`, `sendExec`, `mapAgentError`, `execStepLabel`, sélecteur `mat-button-toggle-group`, rendu du tour d'exécution et des `changedFiles`.
- **Aucune table, aucune migration, aucun endpoint, aucune route nouvelle.**

## Hors périmètre

- Le flux backend `/api/workspaces/{id}/agent/stream` (SF-28-10) et le cycle de session/sandbox (SF-28-08/09).
- Le surcompteur sandbox, les plafonds dépense/minutes et le bandeau de coût (SF ZONE ARGENT, cadrage §6).
- La bascule persistée par workspace derrière flag (rollout progressif).

## Provider-First / Gateway-First

Le frontend **relaie** le flux d'exécution produit par les Managed Agents d'Anthropic via la Gateway ; il ne réimplémente aucun moteur d'agent ni sandbox. Callbacks `fetch` hors zone Angular re-synchronisés via `NgZone.run`.
