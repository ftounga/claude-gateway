# Mini-spec — [F-211 / SF-211-01] Message envoyé d'un clic — backend

## Identifiant
SF-211-01

## Feature parente
F-211 — Message envoyé d'un clic (cadrage : `CADRAGE-F-211-message-envoye-d-un-clic.md`)

## Statut
En cours

## Date de création
2026-10-24

## Branche Git
`feat/SF-211-01-message-envoye-backend`

---

## Objectif
Enregistrer qu'un encart mail/message d'une réponse d'agent a été envoyé par l'utilisateur (geste, sans tour), faire passer l'attente liée à « Demandé », et le dire à l'agent au tour suivant.

## Comportement attendu

### Cas nominal
1. `POST /api/workspaces/{id}/messages/{messageId}/sends/{index}` : le serveur relit le message **persisté** (réponse d'agent du terminal possédé), y repère le `index`-ième bloc ```` ```mail ```` / ```` ```message ```` (même découpage que le client : fence ≥ 3 backticks, premier jeton de la balise = type, alias `email|eml`, `teams|slack`), lit « Objet : » et « À : » en tête, et l'attribut `attente=<key>` de la balise. Il enregistre l'envoi (`sent_at` = horloge serveur) et rend la vue de l'envoi.
2. Si `attente=<key>` désigne une attente **ouverte** de ce terminal (ou de son poste, comme l'agent la résout) : elle passe `DEMANDE` (`requestedTo` = « À : » s'il existe, `channel` = `mail`/`message`). L'état d'avant est mémorisé sur l'envoi.
3. `DELETE …/sends/{index}` (« Annuler ») : l'envoi est annulé (`cancelled_at`) ; l'attente revient à son état d'avant **si elle n'a pas bougé entre-temps** (même statut `DEMANDE` et même `updated_at` que posé par le clic).
4. `GET /api/workspaces/{id}/messages/sends` : les envois actifs du terminal (messageId, index, type, objet, date, clé d'attente) — l'écran les relit au rechargement.
5. Note de tour : « --- Messages marqués envoyés par l'utilisateur depuis ton dernier tour --- » listant objet (ou début du corps), type, heure (Europe/Paris), destinataire et attente passée à « demandé ». Jointe à la **consigne du tour** (jamais au préfixe système), sur toutes les cibles sauf le terminal Teams. « Depuis ton dernier tour » = envois actifs postérieurs au dernier message utilisateur persisté. Vide ⇒ consigne inchangée à l'octet près.
6. Consigne `MESSAGE_BLOCK_DOCTRINE` (littéral stable) : balise facultative `attente=<key>` quand le message sert une attente ouverte, ligne facultative « À : … » en tête ; l'agent ne redemande plus si un message marqué envoyé est parti.
7. Re-marquer un envoi annulé le réactive (nouvelle date) ; marquer deux fois est sans effet.

### Cas d'erreur
- Terminal d'autrui ou inexistant → 404 (`requireOwned`, en premier).
- Message inexistant, d'un autre terminal ou d'un autre compte, ou message utilisateur → 404.
- `index` négatif ou au-delà du nombre de blocs mail/message du message → 400 lisible.
- Clé d'attente inconnue ou attente fermée → l'envoi est enregistré, l'attente n'est pas touchée (best-effort, jamais une erreur).
- Annuler un envoi inexistant ou déjà annulé → 204, sans effet.
- Attente modifiée entre le clic et l'annulation → l'envoi est annulé, l'attente n'est pas touchée.

## Critères d'acceptation
- [ ] CA1 : POST enregistre l'envoi et le rend (index, kind, subject, sentAt, attenteKey) ; GET le liste ; DELETE l'annule et il disparaît du GET.
- [ ] CA2 : un bloc ```` ```mail attente=acces-forge ```` avec « À : Zahi » fait passer l'attente `acces-forge` (A_FAIRE) à DEMANDE, requestedTo=Zahi, channel=mail ; Annuler la remet À_FAIRE sans demande notée.
- [ ] CA3 : Bob reçoit 404 sur POST/DELETE/GET du terminal d'Alice ; un messageId d'un autre terminal d'Alice → 404.
- [ ] CA4 : index hors bornes → 400.
- [ ] CA5 : la note de tour liste les envois postérieurs au dernier message utilisateur, rien d'autre ; vide sans envoi.
- [ ] CA6 : la consigne système contient la balise `attente=` et « À : » ; elle reste identique d'un tour à l'autre (stable).
- [ ] CA7 : le parseur Java découpe comme `shared/message-segments.ts` (code ignoré, fence vide ignoré, alias, attributs de balise).

## Périmètre

### Hors scope (explicite)
- L'écran (bouton, état, Annuler) : SF-211-02.
- Envoyer réellement le mail ; détection automatique de l'envoi.
- Terminal Teams (pas d'encart).

## Valeurs initiales
Aucune. Table vide au déploiement.

## Contraintes de validation
| Champ | Règle |
|---|---|
| `index` | 0 ≤ index < nombre de blocs mail/message du message |
| `subject` | tronqué à 300 caractères |
| `requestedTo` (« À : ») | tronqué à 120 caractères (borne F-175) |
| `attente_key` | normalisée comme une clé F-175, ≤ 120 caractères |
| `kind` | `MAIL` ou `MESSAGE` |

## Technique

### Endpoint(s)
- `POST /api/workspaces/{id}/messages/{messageId}/sends/{index}` → 200 `MessageBlockSendResponse`
- `DELETE /api/workspaces/{id}/messages/{messageId}/sends/{index}` → 204
- `GET /api/workspaces/{id}/messages/sends` → 200 liste

### Tables impactées
`message_block_sends` (nouvelle) ; `terminal_actions` (statut via le service existant).

### Migration Liquibase
`167-message-block-sends.xml` : id, user_id, workspace_id (FK cascade), message_id (FK `atelier_messages` cascade), block_index, kind, subject, recipient, attente_key, attente_action_id, attente_prev_status, attente_prev_requested_at, attente_prev_requested_to, attente_prev_channel, attente_marked_at, sent_at, cancelled_at ; unique (user_id, message_id, block_index). Rollback : drop table.

### Composants Angular (si applicable)
Aucun (SF-211-02).

## Plan de test

### Tests unitaires
- `MessageBlocksTest` : découpage (alias, code ignoré, fence vide, attributs, objet + destinataire dans les deux ordres, gras toléré).
- `MessageBlockSendNoteTest` : note vide sans envoi ; ligne avec objet / début du corps / attente ; bornée.
- `AtelierChatServiceSystemPromptTest` : la consigne porte `attente=` et « À : ».

### Tests d'intégration
- `MessageBlockSendApiIntegrationTest` : cycle POST/GET/DELETE ; passage DEMANDE puis retour ; attente bougée entre-temps non restaurée ; 400 index ; idempotence.

### Isolation workspace
- Bob → 404 sur les trois routes du terminal d'Alice ; message d'un autre terminal → 404.

## Préoccupations transversales
- **Contexte tenant** : nouvelle table et nouvelles routes. Composants impactés : `MessageBlockSendController` (identité via `CurrentUser`, `AtelierAccessService.requireTerminalAccess`), `MessageBlockSendService` (`WorkspaceService.requireOwned` en premier, lecture message filtrée `workspace_id`+`user_id`), `TerminalActionService` (nouvelles méthodes résolues comme l'agent, sous `user_id`), note de tour (lecture sous `user_id`). Test 404 inter-comptes.
- Auth / plans / navigation : non touchés.

## Dépendances

### Subfeatures bloquantes
Aucune (F-175, F-203 livrées).

### Questions ouvertes impactées
Aucune.

## Notes et décisions
- Repère `(message_id, block_index)` vérifié côté serveur sur le contenu persisté ; l'objet, le destinataire et la clé sont lus **par le serveur**, jamais fournis par le client.
- « Depuis ton dernier tour » sans état : borne = dernier message utilisateur persisté (un geste fait pendant un tour remonte au tour suivant).
- Purge : FK cascade sur le terminal et sur le message (la purge du compte supprime messages et terminaux).
