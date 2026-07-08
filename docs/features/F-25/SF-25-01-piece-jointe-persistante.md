# SF-25-01 — Pièces jointes persistées et rejouées dans tout le fil (backend)

Parent : **F-25 — Mémoire des pièces jointes dans la conversation**
Type : subfeature backend
Statut : En cours

## Objectif (une phrase)

Rendre les pièces jointes (images/PDF) **persistantes dans l'historique** et les **re-transmettre à Claude à chaque tour**, comme claude.ai — afin qu'un fichier joint reste analysable partout dans le fil, pas seulement au message où il est joint.

## Problème corrigé

Aujourd'hui, une pièce jointe n'est envoyée au fournisseur **qu'au tour où elle est jointe** (elle est appliquée au dernier message, jamais rejouée). L'historique est reconstruit à partir du **texte seul** des messages → l'image/document « disparaît » du contexte aux tours suivants. Symptôme observé : Claude décrit correctement une image au tour 1, puis « ne la voit plus » plus tard dans la même conversation.

## Comportement nominal (cible)

1. Quand un message est envoyé avec `attachmentIds`, chaque fichier est **rattaché à CE message** (colonne `uploaded_files.message_id`), en plus du rattachement conversation (F-23).
2. À chaque tour, la reconstruction de l'historique **recharge les pièces jointes de chaque message** et les ré-injecte comme blocs de contenu (`image`/`document`) auprès du fournisseur.
3. Le tour courant et les tours passés sont traités **uniformément** : le message courant, une fois persisté et rattaché à ses fichiers, entre dans l'historique rejoué.

## Cas d'erreur (inchangés)

| Cas | Réponse |
|-----|---------|
| `attachmentIds` contient un id inconnu/d'un autre utilisateur | 404, avant toute écriture (isolation `user_id`) |
| > 10 pièces jointes | 400 (validation) |

## Critères d'acceptation vérifiables

- [ ] Colonne `uploaded_files.message_id` (nullable, FK → `messages(id)` `ON DELETE SET NULL`, index) — migration H2+Postgres.
- [ ] `ChatMessage` porte ses propres pièces jointes (`List<ProviderAttachment>`).
- [ ] Un fichier joint au tour N est présent dans la requête fournisseur **au tour N ET au tour N+k** (vérifié par test).
- [ ] Les blocs `image`/`document` sont construits **par message** (plus de « seulement le dernier »).
- [ ] Isolation `user_id` préservée (404 avant écriture) — non-régression.
- [ ] `libraryDocumentIds` (F-24) et le reste du flux inchangés.

## Plan de test minimal

- **`AnthropicProviderTest`** : deux messages, chacun avec sa pièce jointe → chacun rend ses blocs (le 1ᵉʳ message garde son image même s'il n'est pas le dernier).
- **`ChatServiceTest`** : (a) le fichier joint est rattaché au message (message_id posé) ; (b) au tour suivant (historique avec un ancien message porteur d'un fichier), la requête fournisseur re-contient ce fichier ; (c) isolation 404 inchangée.
- **`ChatApiIntegrationTest`** : upload → message 1 avec pièce jointe → message 2 sans pièce jointe ; le stub fournisseur voit la pièce jointe **aux deux** tours.

## Tables / endpoints / composants impactés

- **Table** : `uploaded_files` (+`message_id`), migration `034-uploaded-files-message`. Aucune table nouvelle.
- **Classes** : `UploadedFile` (+`messageId`), `UploadedFileRepository` (+lecture par message_id, batch), `ChatMessage` (+attachments), `AnthropicProvider` (blocs par message), `ChatService` (rattachement au message + rechargement des pièces jointes par message à la reconstruction de l'historique).
- **Endpoints** : `POST /chat` et `/chat/stream` (comportement enrichi, contrat de requête inchangé).

## Préoccupation transversale

- **Contexte tenant** : la lecture des pièces jointes par message reste sous filtre `user_id` (les fichiers portent `user_id`, la conversation est déjà vérifiée possédée). Composants impactés listés ci-dessus.
- Auth/Principal, plans/limites, navigation : inchangés.

## Hors périmètre

- Import bibliothèque → conversation (F-24) : subfeature distincte (bénéficiera du même mécanisme).
- Troncature/résumé des conversations très longues (limite de contexte) : optimisation ultérieure.
- Frontend : aucun changement nécessaire (le contrat de requête ne change pas).
