# Mini-spec — F-04 / SF-04-03 Pièce jointe dans l'interface de chat

## Identifiant

`F-04 / SF-04-03`

## Feature parente

`F-04` — Upload & transmission fichiers (sans OCR ni indexation)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-04-03-frontend-piece-jointe`

---

## Objectif

Ajouter à l'écran de chat un contrôle « pièce jointe » : l'utilisateur choisit un fichier, celui-ci
est téléversé via `POST /api/upload`, affiché sous forme de puce, et son identifiant est inclus dans
`attachmentIds` à l'envoi du message (`POST /api/chat`). Le frontend ne parle qu'à Claude Gateway.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur clique sur le bouton trombone → sélecteur de fichier natif.
2. À la sélection, `UploadService.uploadFile(file)` appelle `POST /api/upload` (FormData).
3. Pendant l'upload : puce en état « en cours » (spinner) ; à la réponse : puce `filename` + taille,
   avec bouton de retrait.
4. À l'envoi du message, le corps `ChatRequest` inclut `attachmentIds` (ids des puces prêtes).
5. Après envoi réussi, les puces sont réinitialisées.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Upload échoue (415/413/5xx) | Message d'erreur inline ; la puce passe en erreur ; pas d'`attachmentId` ajouté |
| Envoi de message pendant un upload en cours | Le bouton d'envoi est désactivé tant qu'un upload est en cours |
| Retrait d'une puce | L'id est retiré d'`attachmentIds` |

---

## Critères d'acceptation

- [ ] Le bouton trombone déclenche la sélection puis l'upload via `UploadService` (mock en test).
- [ ] Une fois l'upload réussi, une puce affiche le nom + taille et permet le retrait.
- [ ] À l'envoi, `ChatRequest.attachmentIds` contient exactement les ids des puces prêtes.
- [ ] Une erreur d'upload affiche un message et n'ajoute pas d'`attachmentId`.
- [ ] Le bouton d'envoi est désactivé tant qu'un upload est en cours.
- [ ] Respect du design system (couleurs/typo/tokens existants du chat).

---

## Périmètre

### Hors scope (explicite)

- Prévisualisation d'image, glisser-déposer multiple, barre de progression fine (V2).
- Persistance des pièces jointes entre sessions.

---

## Technique

### Composants Angular

- `UploadService` (`core/services`) — `uploadFile(file: File): Observable<UploadedFileResponse>`.
- `upload.models.ts` — `UploadedFileResponse { id, filename, mediaType, sizeBytes }`.
- `chat.models.ts` — `ChatRequest.attachmentIds?: string[]` (contrat importé de SF-04-01/02).
- `chat.component` — bouton trombone, `input[type=file]`, liste de puces, gestion état.

### Contrats API (importés)

- `POST /api/upload` (multipart `file`) → `{ id, filename, mediaType, sizeBytes }` (SF-04-01).
- `POST /api/chat` accepte `attachmentIds` (SF-04-02).

---

## Plan de test

### Tests unitaires (mock backend)

- [ ] `UploadService` — `uploadFile` poste un `FormData` sur `/api/upload` et mappe la réponse (HttpTestingController).
- [ ] `ChatComponent` — après upload mocké réussi, une puce apparaît ; l'envoi inclut `attachmentIds`.
- [ ] `ChatComponent` — upload en erreur → message affiché, aucun `attachmentId`.

### Isolation

- [x] Non applicable côté front (isolation garantie backend via JWT).

---

## Dépendances

### Subfeatures bloquantes

- `SF-04-01` (contrat `/upload`) et `SF-04-02` (contrat `/chat attachmentIds`) — contrats figés.

---

## Notes et décisions

- **Arbitrage réversible** (cohérence écran) : intégration dans l'écran de chat existant plutôt qu'un
  écran dédié — le flux « joindre puis envoyer » colle à l'expérience Claude (PROJECT.md §5).
- Tests indépendants du backend mergé (mock du service).
