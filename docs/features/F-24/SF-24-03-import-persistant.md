# SF-24-03 — Import bibliothèque interrogeable dans tout le fil (backend)

Parent : **F-24 — Import bibliothèque → conversation**
Type : subfeature backend (refonte de l'injection F-24 sur le mécanisme F-25)
Statut : En cours

## Objectif (une phrase)

Rendre un document importé de la bibliothèque **persistant dans la conversation** et **ré-injecté à chaque tour** (comme les pièces jointes F-25) — pour qu'on puisse l'interroger à l'import **et plus tard** dans le fil, au lieu de l'injection éphémère actuelle (un seul tour).

## Problème corrigé

Aujourd'hui (SF-24-01), le texte du document est injecté **uniquement au tour où on l'importe** et **non persisté** → il disparaît du contexte aux tours suivants. Même défaut que celui corrigé pour les pièces jointes (F-25).

## Comportement nominal (cible)

1. À l'envoi avec `libraryDocumentIds`, après validation (404/409), on **persiste un lien** message → document (table `message_library_documents`).
2. À **chaque** reconstruction de l'historique, pour chaque message porteur d'un lien, on **préfixe son contenu fournisseur** avec le texte OCR des documents liés (« Document de la bibliothèque « X » : … »). Le message **stocké** reste le texte propre de l'utilisateur (le préfixe n'existe qu'au moment de l'appel fournisseur).
3. Le document reste donc dans le contexte **tout le long** du fil, sans re-téléverser ni polluer l'affichage.

## Cas d'erreur (inchangés vs SF-24-01)

| Cas | Réponse |
|-----|---------|
| Document inconnu / d'un autre utilisateur | 404, avant toute écriture (isolation) |
| Document sans texte exploitable | 409 (`document_not_ready`), avant écriture |
| > 10 documents | 400 |

## Critères d'acceptation vérifiables

- [ ] Table `message_library_documents` (lien message↔document), migration H2+Postgres.
- [ ] Le lien est persisté après la sauvegarde du message (id disponible), 404/409 **avant** écriture (non-régression).
- [ ] Le contenu OCR d'un document importé au tour N est présent dans la requête fournisseur **au tour N ET au tour N+k** (test multi-tours).
- [ ] Le message **stocké** ne contient pas le texte du document (storage propre).
- [ ] Isolation `user_id` préservée.

## Plan de test minimal

- **`ChatServiceTest`** : validation 404/409 inchangée ; lien persisté après save.
- **`ChatApiIntegrationTest`** : (a) import d'un doc EXTRACTED → son texte est dans la requête fournisseur ; (b) **multi-tours** : au tour 2 (sans `libraryDocumentIds`), le texte du doc importé au tour 1 est **toujours** présent ; (c) doc d'un autre utilisateur → 404 ; (d) doc non prêt → 409.

## Tables / endpoints / composants impactés

- **Table** : `message_library_documents` (nouvelle), migration `035`. FK → `messages(id)` et `documents(id)` `ON DELETE CASCADE`.
- **Classes** : `MessageLibraryDocument` (entité lien) + repository (lecture groupée par message) ; `ChatService` (validation + lien + injection par message à la reconstruction) ; remplace `resolveLibraryContext`/`buildProviderMessages` (injection éphémère).
- **Endpoints** : `POST /chat`, `/chat/stream` (comportement enrichi ; contrat de requête inchangé).

## Préoccupation transversale

- **Contexte tenant** : validation des documents sous `findByIdAndUserId` (isolation) ; lecture des textes à la reconstruction restreinte aux documents liés à des messages de la conversation possédée. Composants ci-dessus.
- Auth/Principal, plans/limites, navigation : inchangés.

## Hors périmètre

- Affichage d'une « puce document » sur les messages passés (frontend) : polish ultérieur (SF-24-04). Le contrat de requête ne change pas → le frontend actuel fonctionne déjà.
- Import en **pièce jointe native** (PDF lu nativement) : possible plus tard via upload Anthropic Files au lieu du texte OCR ; non requis pour des documents textuels.
