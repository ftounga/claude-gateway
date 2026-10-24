# Cadrage F-211 — Message envoyé d'un clic

**Demande PO du 2026-10-24** (dans la conversation Claude Code) : « Un bouton qui permet de cocher en disant que le message est envoyé. Comme ça, je n'ai pas à chaque fois à réécrire dans le terminal, à confirmer que le message a été envoyé. »

Livraison autonome. Justesse avant coût ; préfixe système stable ; charte `DESIGN_SYSTEM.md`.

## Existant vérifié (origin/main 72258496)
- Encart F-203 : bloc ```` ```mail ```` / ```` ```message ```` (consigne `MESSAGE_BLOCK_DOCTRINE`, `AtelierChatService` l.775), « Objet : » facultatif ; parseur **côté client** `shared/message-segments.ts` ; composant `chat/copy-block` (Copier, Copier l'objet). **Aucun identifiant, aucun état, aucune persistance.**
- Attentes F-175 (`TerminalAction`, A_FAIRE → DEMANDE → FAIT) : l'agent doit passer une attente à DEMANDE « quand la demande part » ; aujourd'hui il ne le sait que si l'utilisateur l'écrit. Route `POST /workspaces/{ws}/actions/{id}/status`. Le bloc « Attentes ouvertes » est joint à chaque tour (`TerminalActionTurnNote`). Une étape de plan peut attendre une attente (`waitsOn` = key).
- Aucun lien entre un encart et une attente.

## Décisions
1. **Bouton « Marquer envoyé »** sur chaque encart mail/message d'une réponse d'agent (terminal de projet et Forge ; pas sur le terminal Teams où l'encart n'existe pas). Au clic : l'encart affiche « ✓ Envoyé · 24/10 17:42 » et un lien « Annuler ».
2. **Repère stable** : `(message_id, block_index)` (rang du bloc mail/message dans le message, calculé par le même parseur côté client et vérifié côté serveur sur le contenu persisté). Persisté dans une table `message_block_sends` (migration **167** : id, user_id, workspace_id, message_id, block_index, kind MAIL|MESSAGE, subject ≤ 300, attente_key, sent_at, cancelled_at) — isolation `user_id` + `requireOwned`. L'état revient au rechargement (historique).
3. **Lien avec l'attente** : la consigne F-203 autorise une balise facultative ```` ```mail attente=<key> ```` quand le message sert une attente ouverte (et une ligne facultative « À : … »). Au clic, si la key désigne une attente ouverte du terminal : elle passe **DEMANDE** (`requestedTo` = « À : » s'il existe, `channel` = mail|message) ⇒ l'étape de plan qui l'attend le reflète déjà (F-208). « Annuler » la remet dans son état précédent si elle n'a pas bougé entre-temps.
4. **L'agent est informé sans que l'utilisateur écrive** : la note de tour (partie volatile, dans le message, jamais dans le préfixe) gagne une ligne « Messages marqués envoyés par l'utilisateur depuis ton dernier tour : « Objet » (mail, 17:42) … ». La consigne dit de ne plus redemander la confirmation d'envoi.
5. Le geste ne déclenche aucun tour (pas d'envoi de message dans le fil) ; pendant un tour, il est pris en compte au tour suivant.

## Préoccupations transversales
Contexte tenant : nouvelle table + route ⇒ filtre `user_id`, `requireOwned`, test 404 sur le terminal d'autrui.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-211-01 | Backend : migration 167, routes `POST/DELETE /workspaces/{id}/messages/{messageId}/sends/{index}`, état rendu avec l'historique, passage DEMANDE de l'attente liée, note de tour, consigne (`attente=`, « À : »), tests (isolation) | 🟠 |
| SF-211-02 | Front : bouton « Marquer envoyé », état « ✓ Envoyé », Annuler, relecture de l'historique, balise `attente=` absorbée par le parseur sans s'afficher | 🟢 |

## Hors périmètre
Envoyer réellement le mail depuis l'application (F-110 / courriel) ; détection automatique de l'envoi.
