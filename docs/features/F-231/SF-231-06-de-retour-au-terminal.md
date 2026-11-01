# Mini-spec — F-231 / SF-231-06 De retour au terminal

## Identifiant
`F-231 / SF-231-06` — Feature parente : F-231 « Le mode Jarvis » (cadrage, décisions **D1, D10, D11**).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-231-06-de-retour-au-terminal`.

## Objectif
Quand le PO revient sur un terminal où Jarvis a agi à la voix, il retrouve d'un coup d'œil ce qui a été fait et les messages qu'il lui reste à envoyer — sans réconciliation : tout est déjà dans le fil.

## Comportement attendu

### Cas nominal
1. **La marque.** Un envoi depuis l'écran Jarvis porte `via: "JARVIS"` (`POST /api/workspaces/{id}/chat[/stream]`). Le tour **neuf** qu'il ouvre est marqué : la demande **et** la réponse sont rangées avec `atelier_messages.via = 'JARVIS'` (migration **191**). Une précision versée dans un tour déjà vivant, ou un tour de suite, ne l'est pas. La marque est consommée une fois, par fil (chantier), et expire après 2 min si aucun tour ne la consomme.
2. **Dans le fil.** L'historique rend `via` ; la bulle de la demande affiche « Votre question · 🎙 via Jarvis ».
3. **« Pendant ton absence, via Jarvis ».** À l'entrée sur un terminal (écran principal, pas un volet du split), l'écran lit sa **dernière visite sur cet appareil** (F-225, lue avant d'inscrire celle-ci) et appelle `GET /api/workspaces/{id}/jarvis/absence?since=…`. S'il y a eu des demandes à la voix depuis : un encart en tête du fil liste les demandes et la phrase de chaque réponse (ligne « 🎙 », sinon première ligne de prose), les attentes créées depuis la première demande, et **les messages à envoyer** (encarts mail/message des réponses nées à la voix, non marqués envoyés) avec [Copier] et [Marquer envoyé] (F-211). Refermable. Absent sinon. L'écran Jarvis n'inscrit **jamais** de visite du terminal.
4. **Point de situation (F-225).** `envois[].viaJarvis` et `exchange.viaJarvis` : la carte affiche « 🎙 via Jarvis ».
5. **« Envoie-le-moi ».** Pas de code ici : l'outil `email_me` (F-110) existe dans tout terminal de poste et n'écrit qu'au PO. La consigne orale qui le rappelle à l'agent est posée par SF-231-03 (dans le message, jamais dans le système).
6. **« À envoyer (n) » dans le tableau du client.** `GET /api/jarvis/to-send?hostId=` : les encarts mail/message des 7 derniers jours de **tous** les sujets du poste (terminal du poste compris), pas marqués envoyés, le plus récent d'abord (30 au plus), marqués `viaJarvis` le cas échéant. Section absente si 0.

**Jarvis n'envoie jamais rien à un tiers** (D11) : aucune écriture vers un tiers n'est ajoutée.

### Contrat API
```json
// POST /api/workspaces/{id}/chat/stream  (ajout)
{ "message": "Prépare le mail pour Zahi", "via": "JARVIS" }
// GET /api/workspaces/{id}/chat  → chaque message porte  "via": "JARVIS" | null
// GET /api/workspaces/{id}/jarvis/absence?since=2026-11-01T08:00:00Z
{ "since": "…", "turns": [ { "messageId": "…", "request": "Prépare le mail pour Zahi", "at": "…",
    "chantierNumber": 3, "replyId": "…", "reply": "Message pour Zahi prêt, à envoyer depuis le terminal." } ],
  "attentes": [ { "id": "…", "description": "…", "status": "A_FAIRE", "createdAt": "…" } ],
  "toSend": [ { "workspaceId": "…", "workspaceName": "…", "messageId": "…", "blockIndex": 0, "kind": "MAIL",
    "subject": "Compte forge", "recipient": "Zahi", "proposedAt": "…", "sentAt": null, "excerpt": "Bonjour Zahi",
    "chantierNumber": 3, "viaJarvis": true } ] }
// GET /api/jarvis/to-send?hostId=…
{ "count": 1, "items": [ { …ToSend… } ] }
```

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| `via` autre que `JARVIS` | `validation_error` | 400 |
| Terminal d'autrui (absence) | introuvable | 404 |
| Sans droit Forge (absence) / sans droit runner (to-send) | refus | 403 |
| `since` absent | dernières 24 h | 200 |
| `since` plus vieux que 14 jours / dans le futur | borné à 14 jours / à maintenant | 200 |
| Poste d'autrui (to-send) | liste vide (aucun sujet sous ce `user_id`) | 200 |

## Contraintes de validation
`via` ∈ {`JARVIS`} ou absent. Absence : ≤ 30 demandes, ≤ 30 attentes, ≤ 30 encarts ; corps d'encart ≤ 8 000 caractères (Copier copie le message, pas un extrait). To-send : 7 jours, ≤ 30 éléments, extrait ≤ 300 caractères.

## Critères d'acceptation
- [ ] Un tour né à la voix range sa demande et sa réponse avec `via = JARVIS` ; un tour tapé, non ; la marque ne vaut qu'une fois et que pour son fil.
- [ ] La bulle de la demande affiche « 🎙 via Jarvis » au rechargement.
- [ ] L'encart liste demandes, phrases, attentes créées et messages à envoyer ; [Marquer envoyé] passe par F-211 ; absent si Jarvis n'a rien fait depuis la dernière visite.
- [ ] Le point de situation marque l'échange et les encarts nés à la voix.
- [ ] Le tableau du client affiche « À envoyer (n) » et le compte baisse quand un encart est marqué envoyé.
- [ ] Bob ne voit rien d'Alice (404 / liste vide).

## Plan de test
- **Unitaires (back)** : `AtelierChatServiceVoiceTurnTest` (marque rangée, une fois, par fil, tour tapé sans marque), `AtelierChatControllerForceTest` (la marque est posée avant la boucle, sur le pool SSE ; rien pour un message tapé), `JarvisRepliesTest`.
- **Intégration (back)** : `JarvisReturnApiIntegrationTest` (encart, `via` dans l'historique et la situation, compteur et marquage, **isolation**).
- **Frontend** : `terminal-jarvis-absence.component.spec`, `client-board-to-send.component.spec`, `thread-item.spec`, `client-board.component.spec` (service bouchonné).

## Tables / endpoints / composants impactés
- **Table** : `atelier_messages` + colonne `via varchar(16)` (migration **191**, nullable, aucune réécriture).
- **Endpoints** : `POST /chat`, `POST /chat/stream` (champ `via`), `GET /chat` (champ `via`), `GET /situation` (`viaJarvis`), **nouveaux** `GET /api/workspaces/{id}/jarvis/absence`, `GET /api/jarvis/to-send`.
- **Back** : `AtelierChatRequest`, `AtelierChatController`, `AtelierChatService` (marque), `AtelierMessage`, `AtelierMessageResponse`, `Situation`/`SituationService`, `JarvisReturnService`/`Controller`, `JarvisReplies`, dépôts `AtelierMessageRepository`, `MessageBlockSendRepository`.
- **Front** : `AtelierMessage.via`, `AtelierThreadItem.viaJarvis`, `atelier-terminal` (encart + marque), `terminal-situation`, `client-board` (+ `client-board-to-send`), `JarvisService`.

## Préoccupations transversales
- **Contexte tenant** ✅ — composants qui résolvent le sujet : `JarvisReturnService.absence` (`requireOwned` d'abord, puis lectures sous `user_id`), `JarvisReturnService.toSend` (`findByUserIdAndHostId`, puis `…UserIdAndWorkspaceIdIn…`), `AtelierChatService.markVoiceTurn` (clef `user:workspace:chantier`).
- **Navigation** ✅ — aucune route ajoutée ; les liens « À envoyer » pointent vers `/atelier/{id}` (route existante, garde existante).
- Auth / plans : non concernés.

## Hors périmètre
L'écran `/jarvis` (SF-231-02), la phrase orale et la consigne « envoie-le-moi » (SF-231-03), la confirmation orale (SF-231-04). Le détail des étapes validées / chantiers lancés n'est pas extrait des traces d'outils : la phrase de chaque réponse le dit (arbitrage).
