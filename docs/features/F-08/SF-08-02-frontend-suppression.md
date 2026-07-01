# Mini-spec — [F-08 / SF-08-02] Suppression d'un document (frontend)

## Identifiant

`F-08 / SF-08-02`

## Feature parente

`F-08` — Statut des documents

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-08-02-frontend-suppression`

---

## Objectif

> En une phrase : permettre à l'utilisateur de supprimer définitivement un de ses documents depuis l'écran Documents, via une action destructive confirmée (droit à l'effacement RGPD, F-08).

---

## Comportement attendu

### Cas nominal

- Sur l'écran `/documents`, chaque ligne du tableau expose une action **Supprimer** (`mat-flat-button color="warn"`, à côté de « Voir »).
- Au clic, un `MatDialog` de confirmation s'ouvre (réutilise `ConfirmDialogComponent`) : titre « Supprimer le document », message rappelant que l'effacement est **définitif** et supprime les données dérivées, bouton de confirmation « Supprimer ».
- Si l'utilisateur confirme : appel `DELETE /api/documents/{id}` ; en cas de succès (`204`), un `MatSnackBar` de succès s'affiche, la liste est rafraîchie, et le panneau de détail est refermé si le document supprimé était sélectionné.
- Si l'utilisateur annule : aucune requête réseau, aucun changement d'état.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `DELETE` renvoie `404` (déjà supprimé / non trouvé) | `MatSnackBar` d'erreur neutre ; la liste est rafraîchie (le document disparaît). |
| `DELETE` renvoie une erreur réseau / 5xx | `MatSnackBar` d'erreur neutre ; la liste n'est pas altérée localement. |
| `DELETE` renvoie `401` | Pris en charge par l'`authInterceptor` existant (redirection login). |

---

## Contrat API (importé de SF-08-01 backend — figé)

### `DELETE /api/documents/{id}`
- Auth : Bearer JWT (ajouté par l'`authInterceptor`).
- Réponse : `204 No Content`.
- `404` si document introuvable pour l'utilisateur.

### `GET /api/documents/{id}/status` (disponible au contrat, non consommé par cette SF)
- Le polling actuel repose sur `GET /api/documents` (liste). L'endpoint `/status` reste disponible pour une adoption ultérieure ; ajouté au service pour compléter le contrat, sans changer la stratégie de polling.

---

## Critères d'acceptation

- [ ] Une action « Supprimer » est présente sur chaque ligne de document, en `color="warn"`.
- [ ] Le clic ouvre un `MatDialog` de confirmation (jamais `window.confirm`).
- [ ] Confirmer déclenche `DELETE /api/documents/{id}` puis rafraîchit la liste et affiche un snackbar de succès.
- [ ] Annuler n'émet aucune requête réseau.
- [ ] Si le document supprimé était affiché en détail, le panneau de détail est refermé.
- [ ] Une erreur `DELETE` affiche un snackbar d'erreur neutre, sans `window.alert`.
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md`.

---

## Périmètre

### Hors scope (explicite)

- Backend statut + suppression → **SF-08-01** (mergé).
- Suppression en masse / purge de compte → F-11.
- Adoption de l'endpoint `/status` par document pour le polling (reste sur la liste).
- Corbeille / restauration : la suppression est définitive.

---

## Technique

### Composants Angular impactés

| Composant | Opération |
|-----------|-----------|
| `documents.component.ts` / `.html` | Ajout action Supprimer + ouverture dialog + appel service |
| `core/services/documents.service.ts` | Ajout `delete(id)` (+ `status(id)` pour le contrat) |
| `core/models/documents.models.ts` | Ajout `DocumentStatusResponse` |
| `chat/confirm-dialog/confirm-dialog.component.ts` | Réutilisé (aucune modification) |

### Endpoints consommés

| Méthode | URL | Statut backend |
|---------|-----|----------------|
| DELETE | `/api/documents/{id}` | Mergé (SF-08-01) |

### Préoccupations transversales

| Préoccupation | Impact | Composants |
|--------------|--------|-----------|
| Navigation / routing | Aucun — pas de nouvelle route ni guard. L'écran `/documents` existant est enrichi. | `app.routes.ts` inchangé |
| Auth / Principal | Aucun changement — l'`authInterceptor` porte déjà le JWT. | inchangé |
| Contexte tenant | Isolation garantie côté backend (`user_id` du JWT). Le front n'envoie jamais d'`user_id`. | inchangé |

---

## Plan de test

### Tests unitaires (`documents.component.spec.ts`)

- [ ] Confirmer la suppression appelle `DocumentsService.delete(id)` puis rafraîchit la liste (mock service + mock `MatDialog` renvoyant `true`).
- [ ] Annuler (`MatDialog` renvoie `false`) n'appelle pas `delete`.
- [ ] Le panneau de détail sélectionné est refermé après suppression du document affiché.
- [ ] Une erreur `delete` (throwError) affiche un snackbar d'erreur sans `window.alert`.

### Isolation `user_id`

- [ ] Non applicable au frontend (isolation garantie backend, testée en SF-08-01). Le front n'émet jamais d'identifiant utilisateur.

---

## Dépendances

- SF-08-01 (backend `DELETE /api/documents/{id}`) — **mergé**.
- `ConfirmDialogComponent` réutilisable — **existant**.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Réutilisation de `ConfirmDialogComponent`** (chat) plutôt qu'un nouveau dialog : conforme DRY et design system (`MatDialog` pour confirmation destructive).
- **Arbitrage réversible** : on garde le polling sur la liste (`GET /api/documents`) ; l'endpoint `/status` est ajouté au service pour compléter le contrat mais non branché (évite un refactor du polling non requis par F-08).
</content>
</invoke>
