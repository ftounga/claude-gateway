# Mini-spec — [F-13 / SF-13-02] Templates métier — écran Angular

## Identifiant

`F-13 / SF-13-02`

## Feature parente

`F-13` — Templates métier (modèles de prompts réutilisables)

## Statut

`done`

## Date de création

2026-07-01

## Branche Git

`feat/SF-13-02-templates-frontend`

---

## Objectif

> Offrir un écran `/templates` permettant de gérer ses modèles de prompts (créer, modifier,
> supprimer, lister) et d'en copier le contenu pour le réutiliser dans le chat.

---

## Comportement attendu

### Cas nominal

1. `/templates` (route lazy, `authGuard`) charge `GET /api/templates` et affiche les modèles dans
   une `mat-table` paginée (nom, catégorie en badge, date de mise à jour, actions).
2. « Nouveau modèle » ouvre un formulaire (`mat-form-field` outline) : nom, catégorie (`mat-select`
   AUDIT/REPORT/AUTRE), contenu (`textarea`) → `POST /api/templates` → rafraîchissement.
3. « Modifier » pré-remplit le formulaire → `PUT /api/templates/{id}` → rafraîchissement.
4. « Copier » copie le contenu du modèle dans le presse-papier (réutilisation dans le chat) →
   `MatSnackBar` de confirmation.
5. « Supprimer » ouvre une confirmation (`MatDialog` `ConfirmDialogComponent` réutilisé) →
   `DELETE /api/templates/{id}` → rafraîchissement.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Échec du chargement de la liste | `MatSnackBar` d'erreur, état vide |
| Échec création/màj (400) | `MatSnackBar` « Vérifiez les champs du modèle. » |
| Échec suppression | `MatSnackBar` d'erreur, liste inchangée |
| Presse-papier indisponible | `MatSnackBar` « Copie impossible. » |
| Champs formulaire invalides | `mat-error`, bouton d'envoi désactivé |

---

## Contrat consommé

> **Contrat importé de SF-13-01-backend** (section « Contrat API (FIGÉ) »). Aucune divergence.
> `TemplateResponse { id, name, category, content, createdAt, updatedAt }`,
> `category ∈ { AUDIT, REPORT, OTHER }`. Tests sur **mock** du service (indépendants du backend).

---

## Critères d'acceptation

- [ ] Route `/templates` protégée par `authGuard`, lazy-loaded.
- [ ] Liste affichée via `mat-table` + `mat-paginator`, catégorie rendue en badge design-system.
- [ ] Création : formulaire `outline`, `mat-error`, envoi désactivé si invalide → `POST` → refresh.
- [ ] Modification : pré-remplissage → `PUT` → refresh.
- [ ] Suppression : `MatDialog` de confirmation (jamais `window.confirm`) → `DELETE` → refresh.
- [ ] Copie du contenu dans le presse-papier + `MatSnackBar`.
- [ ] Erreurs via `MatSnackBar` (jamais `window.alert`).
- [ ] Couleurs/polices/espacements conformes `DESIGN_SYSTEM.md`.
- [ ] Lien d'accès depuis un écran existant (Settings).
- [ ] `npm run build` + `npm test` verts.

---

## Périmètre

### Hors scope (explicite)

- Insertion automatique dans le composer du chat (V1 = copier-coller ; réversible). Aucune
  modification du `ChatComponent` → pas de préoccupation transversale « navigation » déclenchée.
- Recherche/filtre par catégorie (amélioration ultérieure).
- Placeholders/variables dynamiques.

---

## Technique

### Composants Angular

- `TemplatesComponent` (`/templates`) — liste + formulaire création/édition + copie + suppression.
- `TemplatesService` (`core/services`) — `list/get/create/update/delete` sur `/api/templates`.
- `template.models.ts` — `TemplateCategory`, `TemplateResponse`, `TemplateRequest`.
- Réutilise `ConfirmDialogComponent` (chat) et les classes de badge du design system.

### Endpoints consommés

`GET/POST /api/templates`, `GET/PUT/DELETE /api/templates/{id}` (existants après merge SF-13-01).

### Migration

- Non applicable (frontend).

---

## Plan de test (`templates.component.spec.ts`, `templates.service.spec.ts`)

### Unitaires (composant, service mocké)

- [ ] Chargement liste au `ngOnInit`.
- [ ] Création : appelle `create` puis `refresh`.
- [ ] Édition : pré-remplit puis appelle `update`.
- [ ] Copie : appelle l'API presse-papier + snackbar.
- [ ] Suppression : confirm → `delete` + refresh ; cancel → aucun appel.
- [ ] Erreur de chargement → snackbar, pas de crash.

### Service (`HttpTestingController`)

- [ ] `list/get/create/update/delete` ciblent les bonnes URL/méthodes `/api/templates`.

### Isolation

- Non applicable côté front (garantie backend via JWT) — commentée.

---

## Dépendances

### Subfeatures bloquantes

- `SF-13-01` (backend) — **done** avant merge de cette SF (backend AVANT frontend).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Réutilisation** : « Copier dans le presse-papier » choisi plutôt qu'une intégration directe au
  composer de chat (Simplicity First, aucune modification transversale du chat). Arbitrage réversible.
- Tests frontend sur **mock** du service → indépendants de l'état du backend mergé.
</content>
