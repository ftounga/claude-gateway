# Mini-spec — [F-23 / SF-23-02] Dossier de fichiers par conversation — frontend

## Identifiant

`F-23 / SF-23-02`

## Feature parente

`F-23` — Dossier de fichiers par conversation

## Statut

`ready`

## Date de création

2026-07-03

## Branche Git

`feat/SF-23-02-frontend-dossier-fichiers`

---

## Objectif

> En une phrase : dans l'écran de chat, offrir un panneau « Fichiers du dossier » listant les fichiers
> téléversés dans la conversation active, alimenté par `GET /api/conversations/{id}/files` (contrat figé SF-23-01).

---

## Comportement attendu

### Cas nominal

1. Une conversation est active (`activeConversationId != null`) → un bouton « Fichiers » (icône `folder`) apparaît dans la toolbar du chat.
2. Au clic, un panneau se déploie et charge `GET /api/conversations/{id}/files`.
3. Chaque fichier est affiché : nom, type (badge), taille lisible (Ko/Mo), date. Ordre : plus récent en premier (fourni par le backend).
4. Panneau vide → message « Aucun fichier dans cette conversation ». Nouvelle conversation → bouton masqué.
5. Après l'envoi d'un message avec pièces jointes, un rafraîchissement du panneau (s'il est ouvert) reflète les nouveaux fichiers.

Le frontend ne parle qu'à Claude Gateway (`/api/...`), jamais à un fournisseur ; isolation garantie côté backend (JWT).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Échec de chargement (`GET .../files`) | `MatSnackBar` d'erreur ; panneau reste ouvert mais vide |
| Conversation non encore persistée (nouvelle) | Bouton masqué (pas d'appel) |

---

## Critères d'acceptation

- [ ] Bouton « Fichiers » visible uniquement si une conversation est active ; masqué en nouvelle conversation.
- [ ] Clic → chargement + affichage nom/type/taille/date, triés (ordre backend), état vide géré.
- [ ] Erreur de chargement → snackbar, sans crash.
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; badges de type réutilisant les classes existantes du design system.
- [ ] Tests sur **mock** du service (indépendants du backend), build + specs verts.

---

## Périmètre

### Hors scope (explicite)

- Téléchargement/aperçu du contenu (V1 : pas de contenu persisté).
- Détachement/suppression d'un fichier depuis le panneau.
- Pagination (liste courte par conversation en V1).

---

## Technique

### Contrat importé (SF-23-01)

`GET /api/conversations/{id}/files` → `ConversationFile[]` :
`{ id: string; filename: string; mediaType: string; sizeBytes: number; createdAt: string }`

### Composants Angular

- `ChatService` (ou modèle `chat.models.ts`) : + `ConversationFile` + `getConversationFiles(id)` sur `/api/conversations/{id}/files`.
- `ChatComponent` : signal `conversationFiles`, signal `filesPanelOpen`, méthode `toggleFilesPanel()` / `loadConversationFiles()`.
- `chat.component.html` : bouton toolbar + panneau de liste (état vide, badges de type, taille formatée).

### Migration Liquibase

- [ ] Non applicable (100 % frontend).

---

## Plan de test

### Tests unitaires (mock service)

- [ ] Ouverture du panneau → `getConversationFiles` appelé, liste rendue.
- [ ] Panneau vide → message d'état vide.
- [ ] Erreur de chargement → snackbar, pas de crash.
- [ ] Bouton masqué si `activeConversationId === null`.

### Isolation

- [x] Non applicable côté frontend (isolation garantie backend) ; le service n'envoie que l'id de conversation, le JWT porte le `user_id`.

---

## Dépendances

### Subfeatures bloquantes

- SF-23-01 (backend) — mergée avant le merge de cette SF (backend AVANT frontend).

---

## Notes et décisions

- **Arbitrage réversible (UX)** : panneau déployable dans l'écran de chat (bouton toolbar `folder`) plutôt qu'une route dédiée
  `/conversations/{id}/files` — le dossier de fichiers est contextuel à la conversation active. Alternative écartée : page séparée
  (navigation supplémentaire, moins fluide). Réversible : le service est réutilisable par une future route.
- Taille formatée côté frontend (Ko/Mo) sans dépendance ; badges de type via classes du design system.
