# Mini-spec — [F-05 / SF-05-03] Écran documents : soumission OCR + suivi + texte extrait

## Identifiant

`F-05 / SF-05-03`

## Feature parente

`F-05` — OCR (Textract)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-05-03-documents-viewer`

## Contrat importé

> Contrat API importé de **SF-05-01-backend** (mergée, PR #37) : `POST /documents`,
> `GET /documents`, `GET /documents/{id}`. Aucun endpoint nouveau côté backend.

---

## Objectif

> Offrir à l'utilisateur un écran `/documents` pour **soumettre un document à l'OCR**, **suivre le
> statut** de ses documents (UPLOADED/PROCESSING/EXTRACTED/FAILED) et **consulter le texte extrait**.

---

## Comportement attendu

### Cas nominal

1. `/documents` (route protégée `authGuard`) charge la liste via `GET /documents`.
2. L'utilisateur choisit un fichier et clique « Lancer l'OCR » → `POST /documents` (multipart).
   La liste est rafraîchie ; un `MatSnackBar` de succès s'affiche.
3. La liste (mat-table + mat-paginator) affiche : nom, type, **badge de statut**, date.
4. Un document `PROCESSING` déclenche un rafraîchissement périodique léger de la liste tant qu'au
   moins un document est en cours (pour refléter le passage à `EXTRACTED` par le worker backend).
5. « Voir » sur une ligne charge le détail via `GET /documents/{id}` et affiche le **texte extrait**
   (police mono) ou le message d'erreur si `FAILED`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Type non supporté (415) | `MatSnackBar` erreur « Type de document non supporté. » |
| Fichier trop volumineux (413) | `MatSnackBar` erreur « Document trop volumineux. » |
| Échec réseau/serveur sur chargement | `MatSnackBar` erreur, liste vide gérée |
| Aucun fichier sélectionné | bouton désactivé (pas d'appel) |

---

## Critères d'acceptation

- [ ] `GET /documents` alimente une mat-table paginée avec badge de statut conforme au design system.
- [ ] Sélectionner un fichier puis « Lancer l'OCR » appelle `POST /documents` et rafraîchit la liste.
- [ ] « Voir » appelle `GET /documents/{id}` et affiche `extractedText` (ou `errorMessage`).
- [ ] Une erreur 415/413 affiche un `MatSnackBar` explicite (pas de `window.alert`).
- [ ] Le bouton d'envoi est désactivé sans fichier sélectionné.
- [ ] Couleurs/typo/espacements conformes à `DESIGN_SYSTEM.md` (classes `.badge--*`, palette).

---

## Périmètre

### Hors scope (explicite)

- Suppression de document / RGPD, filtres/tri avancés, gestion documentaire complète → **F-08**.
- Q&A documentaire / RAG → **F-07**.

---

## Technique

### Service / Modèles

- `DocumentsService` : `submit(file)`, `list()`, `get(id)` → `/api/documents`.
- `documents.models.ts` : `DocumentResponse`, `DocumentDetailResponse`, `DocumentStatus`.

### Composants Angular

- `DocumentsComponent` (standalone, signals, Angular Material) — route lazy `/documents` protégée
  par `authGuard`.

### Design System

- `mat-table` + `mat-paginator` ; badges via classes globales `.badge--success/--warning/--error/--neutral` ;
  action principale `mat-flat-button color="primary"` ; notifications via `MatSnackBar` (`snack-*`) ;
  texte extrait en police mono (`code/pre`). Espacements multiples de 4/8px.

---

## Plan de test

### Tests unitaires (service)

- [ ] `submit(file)` poste un `FormData` sur `/api/documents`.
- [ ] `list()` GET `/api/documents` ; `get(id)` GET `/api/documents/{id}`.

### Tests composant (service mocké)

- [ ] Chargement initial → liste peuplée.
- [ ] Soumission → `submit` appelé + rafraîchissement.
- [ ] « Voir » → `get` appelé, texte affiché.
- [ ] Erreur 415 → snackbar erreur (pas d'alert).
- [ ] Badge de statut mappé correctement (EXTRACTED→success, PROCESSING→warning, FAILED→error).

### Isolation

- [x] Non applicable côté frontend (isolation garantie backend via JWT/`user_id`) ; les tests
  utilisent un **mock** du service, indépendants du backend mergé.

---

## Dépendances

### Subfeatures bloquantes

- `SF-05-01` (contrat API) — done (PR #37). `SF-05-02` (async worker) — done (PR #38).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Navigation (préoccupation transversale)** : ajout d'une route `/documents` (lazy, `authGuard`)
  + un lien d'accès depuis l'écran Settings. Composants de navigation impactés : `app.routes.ts`
  (nouvelle route), `settings.component.html` (lien). Aucun guard modifié, aucune redirection
  existante changée → pas de régression sur les chemins existants.
- Rafraîchissement périodique borné aux documents `PROCESSING` (léger), désactivé quand tout est
  terminal — évite le polling inutile.
