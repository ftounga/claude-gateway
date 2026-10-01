# Cadrage F-170 — Résilience du flux au proxy corporate

> Date : 2026-10-01 · Source : diagnostic prod (logs backend), décision PO « mitigation rapide ».
> Statut : cadrée → en cours.

---

## Problème (diagnostiqué, preuves en prod)

Le PO travaille derrière le **proxy corporate CAGIP**. Sur le flux chat du workspace
(`POST /workspaces/{id}/chat/stream`, SSE `text/event-stream`), les logs backend montrent de façon
**répétée** :

- `AsyncRequestNotUsableException: Servlet container error notification for disconnected client`
- `java.io.IOException: Broken pipe`

Le tour « vit dans le flux » (F-84) : quand le proxy coupe une connexion streaming restée **inactive**
(le modèle réfléchit, ou un outil long tourne → **aucun octet ne circule**), le flux tombe. Le tour
est alors **tronqué** côté écran : l'utilisateur voit « je me suis arrêté sans conclure »
(`LAST_RESORT_REPLY`) ou « je n'ai pas lancé l'outil ce tour-ci ».

En plus, **quand la coupure survient, le gestionnaire d'erreur plante lui-même** :
`HttpMessageNotWritableException: No converter for [ErrorResponse] with preset Content-Type
'application/x-ndjson'` — le filet `GlobalExceptionHandler.handleUnexpected` tente de sérialiser un
`ErrorResponse` objet sur un flux déjà engagé qui n'a pas de converter pour ce type.

---

## Objectif — MITIGATION (pas le gros refactor)

Le PO a **explicitement choisi la mitigation rapide**, et **PAS** le refactor « le tour survit à la
coupure » (ce dernier reste **hors périmètre** de F-170).

La mitigation tient en trois leviers backend :

1. **Heartbeat / keep-alive** sur le flux de tour **actif** : émettre périodiquement un signal « vide »
   (commentaire SSE `: ...`, ligne ignorée nativement par les clients) pour garder la connexion vivante
   à travers le proxy pendant **toute** la durée du tour, y compris les longues phases sans événement.
   - Intervalle **configurable par env** (défaut ~15 s, bien **sous** le seuil de coupure proxy).
   - Le heartbeat **ne doit pas** : être persisté au transcript, compter dans le compteur d'événements
     (numéro d'ordre / curseur), ni apparaître à l'utilisateur.
   - **Arrêté** à la fin du tour (succès / erreur / clôture).

2. **Gestion gracieuse du client déconnecté** : un broken pipe / client déconnecté sur le flux **ne
   doit pas** remonter comme erreur inattendue ni déclencher une tentative d'écrire un `ErrorResponse`.
   Le détecter (`AsyncRequestNotUsableException`) et le traiter en log discret (« client déconnecté »),
   proprement, sans bruit d'exception.

3. **Corriger** `No converter for ErrorResponse ... application/x-ndjson` : sur un flux de ce type (ou
   une réponse déjà engagée), ne jamais tenter d'écrire un `ErrorResponse` via un converter inexistant.
   Robustesse : une coupure client ne doit **jamais** produire de cascade d'exceptions.

4. **Frontend** : vérifier que le parseur du flux **ignore** les commentaires / heartbeats.
   (Pour SSE, les commentaires `:` ne sont pas des events — à vérifier.)

---

## Hors périmètre (explicite)

- **Le refactor « le tour survit à la coupure »** (persistance du tour indépendante du flux, reprise
  transparente sans tronquage). C'est le gros chantier que le PO a écarté au profit de la mitigation.
- Le **rebranchement par fenêtres** (`/attach?waitMs=`) existe déjà (F-84 / SF-84-04) et n'est pas
  retouché.
- La **relais ndjson entre pods** (`NdjsonTurnSubscriber`) a déjà son propre `ping` (20 s) et n'est pas
  retouché.
- Toute modification de l'isolation `user_id` / `workspace_id` (inchangée).

---

## Découpage

Le correctif est **backend-only** : le heartbeat est un commentaire SSE, et les deux parseurs SSE du
frontend (`AtelierService.dispatchSseEvent` et `dispatchAgentSseEvent`) **ignorent déjà** les lignes
de commentaire (`if (!data) return;` + aucune branche pour les lignes `:`) — vérifié par lecture du
code. Aucune modification frontend n'est donc nécessaire.

- **SF-170-01** — Résilience du flux de tour au proxy : heartbeat SSE + gestion gracieuse du client
  déconnecté + fin de la cascade `No converter`. (Backend seul.)

Si une tolérance frontend s'avérait nécessaire, elle ferait l'objet d'une SF-170-02 par le cycle
complet. Au cadrage, elle n'est **pas** nécessaire.
