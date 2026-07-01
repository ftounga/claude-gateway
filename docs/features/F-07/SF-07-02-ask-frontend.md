# Mini-spec — [F-07 / SF-07-02] Q&A documentaire — écran « Poser une question » (frontend)

## Identifiant

`F-07 / SF-07-02`

## Feature parente

`F-07` — Q&A documentaire (ask)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-07-02-ask-frontend`

---

## Objectif

> En une phrase : offrir un écran Angular `/ask` où l'utilisateur pose une question sur ses documents
> indexés et reçoit la réponse de Claude avec ses citations, en consommant l'API `POST /api/ask`.

---

## Contrat importé

**Contrat API figé importé de SF-07-01-ask-backend** (`POST /api/ask`) :
- Request : `{ question: string; model?: string | null; topK?: number | null }`
- Response : `{ answer: string; model: string; grounded: boolean; citations: Citation[] }`
- Citation : `{ documentId: string; filename: string; page: number | null; chunkIndex: number; snippet: string }`
- Erreurs : `400 validation_error`, `402 quota_exceeded`, `503 provider_unavailable`, `502 provider_error`.

---

## Comportement attendu

### Cas nominal

1. Route `/ask` (lazy, `authGuard`). L'écran présente un champ question (`mat-form-field` outline,
   textarea) et un bouton « Poser la question ».
2. À la soumission : appel `AskService.ask({ question })` ; état `loading` (spinner + bouton désactivé).
3. Réponse : affichage de la réponse (`answer`) dans une carte ; si `grounded=false`, bandeau
   d'information « Réponse non basée sur vos documents indexés ». Liste des `citations`
   (filename, page si non nulle, chunkIndex, snippet).
4. Lien de navigation vers `/documents` (« Gérer mes documents ») et accès à l'écran depuis
   l'écran Documents.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Question vide | Bouton désactivé ; `mat-error` si champ requis touché |
| `402 quota_exceeded` | `MatSnackBar` : « Quota atteint. » |
| `503 provider_unavailable` | `MatSnackBar` : « Service momentanément indisponible. » |
| `502`/autre erreur | `MatSnackBar` : « Une erreur est survenue. » |

---

## Critères d'acceptation

- [ ] Route `/ask` protégée par `authGuard`, lazy-loaded.
- [ ] Soumission d'une question → appel `POST /api/ask` (via service sur `/api`), réponse affichée.
- [ ] Citations rendues (filename, page conditionnelle, chunkIndex, snippet).
- [ ] `grounded=false` → bandeau explicite ; `citations=[]` → pas de liste vide disgracieuse.
- [ ] Bouton désactivé si question vide ou pendant le chargement.
- [ ] Erreurs mappées via `MatSnackBar` (pas d'`alert()`), spinner pendant l'appel.
- [ ] Couleurs/polices/espacements conformes `DESIGN_SYSTEM.md`.
- [ ] Tests unitaires sur **mock** du service (indépendants du backend mergé), build + tests verts.

---

## Périmètre

### Hors scope (explicite)

- Pas de sélecteur de modèle avancé (le défaut backend est utilisé ; `model` non exposé en V1).
- Pas d'historique persistant des questions (l'écran est éphémère).
- Pas de rendu Markdown avancé de la réponse (texte simple, sauts de ligne préservés).

---

## Technique

### Composants Angular

- `core/models/ask.models.ts` — `AskRequest`, `AskResponse`, `Citation` (contrat figé).
- `core/services/ask.service.ts` — `ask(body): Observable<AskResponse>` → `POST /api/ask`.
- `ask/ask.component.ts|html|scss` — écran Q&A (standalone, Material : card, form-field, button,
  progress-spinner, icon).
- `app.routes.ts` — route `/ask` (lazy, `authGuard`).
- Lien depuis `documents.component.html` vers `/ask`.

### Préoccupation transversale — Navigation / routing (composants impactés)

- Nouvelle route `/ask` ajoutée à `app.routes.ts` (après `/documents`), `authGuard` appliqué comme les
  autres routes protégées. Le wildcard `**` (redirect `''`) reste en dernier.
- Ajout d'un lien `routerLink="/ask"` dans `documents.component.html` ; aucun chemin existant modifié.
- Vérification : les routes existantes (`/chat`, `/documents`, `/billing`, `/settings`, `/onboarding`)
  restent inchangées et fonctionnelles.

---

## Plan de test

### Tests unitaires (`ask.component.spec.ts`, `ask.service.spec.ts` — mock backend)

- [ ] `AskService.ask` émet un `POST /api/ask` avec le bon corps (HttpTestingController).
- [ ] Composant : soumission → affiche `answer` et citations ; bouton désactivé si question vide.
- [ ] `grounded=false` → bandeau affiché.
- [ ] Erreur `402`/`503` → `MatSnackBar` déclenché (spy), pas de crash.

### Isolation utilisateur

- [x] Non applicable côté frontend — l'isolation est garantie côté backend via le JWT (SF-07-01).
      Le service n'envoie jamais d'identifiant utilisateur.

---

## Dépendances

### Subfeatures bloquantes

- `SF-07-01` (backend `/ask`) — contrat figé ci-dessus ; **mergé avant ce frontend** (backend d'abord).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Modèle non exposé (réversible)** : l'UI n'expose pas le choix de modèle pour rester simple ; le
  défaut backend s'applique. Ajout d'un sélecteur = évolution triviale (le contrat accepte `model`).
- **Accès à l'écran** : lien depuis Documents (parcours naturel « j'indexe → je questionne »).
</content>
