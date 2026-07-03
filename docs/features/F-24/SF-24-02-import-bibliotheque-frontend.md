# SF-24-02 — Importer un document de la bibliothèque dans une conversation (frontend)

Parent : **F-24 — Import bibliothèque → conversation**
Type : subfeature frontend
Statut : En cours

## Objectif (une phrase)

Offrir dans le composer de chat un bouton « Depuis ma bibliothèque » ouvrant un dialogue de sélection des documents de la bibliothèque personnelle (F-08), et transmettre les documents choisis à l'envoi via `libraryDocumentIds` (contrat figé SF-24-01).

## Comportement nominal

1. Bouton `library_books` dans le composer (à côté de « Joindre un fichier »).
2. Clic ⇒ dialogue Material listant les documents de l'utilisateur (`DocumentsService.list()`), **filtrés** aux documents exploitables (texte extrait : statut `EXTRACTED`/`INDEXING`/`INDEXED`).
3. Sélection multiple (cases à cocher), validation ⇒ les documents choisis apparaissent comme **puces** distinctes des pièces jointes (icône bibliothèque), retirables.
4. À l'envoi : `libraryDocumentIds` = ids des documents sélectionnés, ajouté au corps `POST /chat/stream`.
5. Après envoi réussi (`onDone`) : la sélection est vidée (comme les pièces jointes).
6. Changement / nouvelle conversation ⇒ sélection réinitialisée.

## Cas d'erreur

| Cas | Comportement |
|-----|--------------|
| Échec de chargement de la liste | message d'erreur `MatSnackBar`, dialogue affiche l'état d'erreur |
| Aucun document exploitable | état vide explicite dans le dialogue (« Aucun document exploitable… ») |
| Document devenu invalide côté backend (404/409) | remonté par le flux d'erreur d'envoi existant (`onError` → snackbar) |
| Annulation du dialogue | aucune modification de la sélection |

## Critères d'acceptation vérifiables

- [ ] `ChatRequest.libraryDocumentIds?: string[]` ajouté au modèle.
- [ ] Bouton bibliothèque visible dans le composer.
- [ ] Le dialogue ne liste que les documents à texte exploitable (filtre de statut).
- [ ] Les documents sélectionnés s'affichent en puces retirables, visuellement distincts des pièces jointes.
- [ ] `send()` inclut `libraryDocumentIds` uniquement s'il y a au moins un document.
- [ ] La sélection est vidée après un envoi réussi et à chaque changement de conversation.

## Plan de test minimal

- `chat.component.spec` : envoi avec documents sélectionnés ⇒ le corps passé à `streamMessage` contient `libraryDocumentIds` attendus ; vidage après `onDone` ; réinitialisation sur `startNewConversation`/`selectConversation`.
- `library-picker.component.spec` (ou intégré) : filtre de statut (documents non exploitables exclus), état vide, retour de sélection, gestion d'erreur de chargement.
- Build Angular OK.

## Composants impactés

- **Modèle** : `core/models/chat.models.ts` (`ChatRequest`).
- **Nouveau** : `chat/library-picker/library-picker-dialog.component.ts` (dialogue de sélection).
- **Modifié** : `chat/chat.component.ts` + `.html` + `.scss` (bouton, signal de sélection, puces, envoi).
- Réutilise `DocumentsService` (aucun nouvel endpoint).

## Préoccupation transversale

- **Navigation/routing** : aucune nouvelle route (dialogue local à l'écran chat). Aucun guard modifié.
- Le frontend ne parle qu'à `/api` ; isolation `user_id` garantie côté backend.

## Hors périmètre

- Recherche/tri avancés dans le dialogue (liste simple des plus récents).
- Prévisualisation du contenu du document dans le dialogue.
- Conformité DESIGN_SYSTEM : réutilise les tokens `--cg-*` et le style `.badge` existants.
