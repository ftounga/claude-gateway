# SF-24-01 — Importer un document de la bibliothèque dans une conversation (backend)

Parent : **F-24 — Import bibliothèque → conversation**
Type : subfeature backend
Statut : En cours

## Objectif (une phrase)

Permettre à un tour de chat d'injecter, comme **contexte fournisseur**, le texte OCR d'un ou plusieurs documents déjà présents dans la **bibliothèque personnelle** (F-08) de l'utilisateur, sans re-téléverser le fichier.

## Contexte / positionnement

- La bibliothèque personnelle (onglet, ex-« Documents ») stocke des documents traités par le pipeline OCR/RAG (F-05→08). L'entité `Document` porte le texte extrait (`extractedText`).
- Le chat (F-02) relaie l'historique au fournisseur via `AIProvider` (jamais Anthropic en direct).
- Cette SF **relaie** un texte déjà extrait : elle n'ajoute **aucun** moteur d'IA, aucun ré-OCR, aucune ré-indexation. Conforme **Gateway-First** / **Provider-First**.
- Distinct de F-04 (pièces jointes `attachmentIds`, transmises comme fichiers au fournisseur) : ici on injecte du **texte de contexte** issu de la bibliothèque.

## Comportement nominal

1. `POST /chat` et `POST /chat/stream` acceptent un champ optionnel `libraryDocumentIds: UUID[]` (max 10).
2. Pour chaque id : le document est relu sous double filtre `id` + `user_id` (isolation multi-tenant).
3. Le texte extrait de chaque document est concaténé dans **un message USER de contexte**, préfixé du nom de fichier, **inséré en tête** de la liste des messages transmis au fournisseur.
4. Ce message de contexte n'est **pas persisté** dans l'historique de la conversation (il n'apparaît pas comme message utilisateur) : il n'existe que dans la requête fournisseur.
5. Le reste du tour (persistance USER/ASSISTANT, quota, streaming) est inchangé.

## Cas d'erreur

| Cas | Réponse |
|-----|---------|
| `libraryDocumentIds` contient un id inconnu **ou** appartenant à un autre utilisateur | **404** `DocumentNotFoundException`, aucun message persisté, aucun appel fournisseur |
| Document sans texte extrait (statut `UPLOADED`/`PROCESSING`/`FAILED`, `extractedText` null/vide) | **409** `DocumentNotReadyException` — le document n'est pas encore exploitable |
| Plus de 10 ids | **400** (validation `@Size`) |
| Quota atteint | **402** (inchangé, contrôle avant toute écriture) |

L'ordre de résolution garantit : erreur d'isolation/état **avant** toute persistance de message ou appel fournisseur.

## Critères d'acceptation vérifiables

- [ ] `ChatRequest.libraryDocumentIds` présent, `@Size(max=10)`.
- [ ] `reply(...)` et `prepareStream(...)` acceptent `libraryDocumentIds` ; surcharges 5-args conservées (délèguent avec `null`).
- [ ] La requête fournisseur (`ChatCompletionRequest.messages`) contient, en **position 0**, un message USER portant le texte extrait de chaque document demandé, préfixé de son `filename`.
- [ ] Un id d'un autre utilisateur ⇒ 404, **sans** persistance ni appel fournisseur (vérifié par test).
- [ ] Un document sans texte exploitable ⇒ 409, sans appel fournisseur.
- [ ] `libraryDocumentIds` null/vide ⇒ comportement strictement identique à l'existant.

## Plan de test minimal

**Unitaires (`ChatServiceTest`, `ChatServiceStreamTest`)**
- injection : la requête fournisseur capturée contient le texte du document en tête (nominal, 1 et 2 documents).
- isolation : `findByIdAndUserId` renvoie vide ⇒ `DocumentNotFoundException`, provider **jamais** appelé, aucun `messageRepository.save`.
- état : document `extractedText` vide ⇒ `DocumentNotReadyException`, provider non appelé.
- régression : `libraryDocumentIds = null` ⇒ aucune lecture `DocumentRepository`, messages fournisseur = historique seul.

**Intégration (`ChatControllerIT` / stream)**
- `POST /chat` avec `libraryDocumentIds` d'un document EXTRACTED de l'utilisateur ⇒ 200, provider stub voit le texte.
- `POST /chat` avec un id d'un autre utilisateur ⇒ 404.

## Tables / endpoints / composants impactés

- **Endpoints** : `POST /chat`, `POST /chat/stream` (champ ajouté, rétro-compatible).
- **Tables** : aucune migration (lecture seule de `documents`).
- **Classes** : `ChatRequest` (dto), `ChatService` (+`DocumentRepository`), `ChatController`, nouvelle `DocumentNotReadyException` (chat), mapping 409.

## Préoccupations transversales

- **Contexte tenant** : nouvel accès données `documents` → filtre `user_id` obligatoire via `findByIdAndUserId`. Composants impactés listés ci-dessus. Aucun autre chemin ne lit `documents` dans le flux chat.
- Auth/Principal : inchangé (identité issue du `CurrentUser`, jamais d'un paramètre client).
- Plans/limites : inchangé (quota appliqué comme avant, avant écriture).
- Navigation/routing : sans objet (backend).

## Hors périmètre

- UI (bouton « Depuis ma bibliothèque » + dialogue) ⇒ **SF-24-02** (frontend).
- Recherche sémantique RAG / sélection de chunks : non — on injecte le texte extrait complet (troncature de garde côté service si nécessaire).
- Persistance d'un lien document↔conversation : non.
