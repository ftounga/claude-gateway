# Mini-spec — F-14 / SF-14-02 Export conversations & réponses citées (frontend)

## Identifiant

`F-14 / SF-14-02`

## Feature parente

`F-14` — Export conversations/réponses (PDF/Markdown des échanges et réponses citées)

## Statut

`ready`

## Date de création

2026-07-02

## Branche Git

`feat/SF-14-02-export-frontend`

> **Contrat importé de SF-14-01-backend** (figé) : `GET /api/conversations/{id}/export?format=…`
> et `POST /api/export/answer?format=…`, réponse binaire + `Content-Disposition: attachment`.

---

## Objectif

> En une phrase : permettre à l'utilisateur de télécharger la conversation active (écran Chat) et la
> réponse citée courante (écran Q&A) en **Markdown** ou **PDF** via un menu d'export.

---

## Comportement attendu

### Cas nominal

1. **Chat** — un bouton « Exporter » (icône `download`) dans l'en-tête du fil ouvre un `mat-menu`
   proposant « Markdown » et « PDF ». Le clic appelle `ExportService.exportConversation(id, format)`,
   reçoit un `Blob`, et déclenche le téléchargement navigateur (nom de fichier issu de l'en-tête
   `Content-Disposition`, sinon `conversation-{id}.{md|pdf}`). Bouton masqué si aucune conversation
   active.
2. **Q&A** — un bouton « Exporter » sous la réponse ouvre un `mat-menu` (Markdown / PDF). Le clic
   appelle `ExportService.exportAnswer(answer, format)` avec la `AskResponse` courante et déclenche
   le téléchargement.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Erreur réseau / 4xx / 5xx | `MatSnackBar` « L'export a échoué. Veuillez réessayer. » ; aucun téléchargement |
| Aucune conversation active | Bouton d'export non affiché |
| Aucune réponse affichée | Bouton d'export non affiché |

---

## Critères d'acceptation

- [ ] Le menu d'export du Chat propose Markdown et PDF et n'apparaît que si une conversation est active.
- [ ] `ExportService.exportConversation` appelle `GET /api/conversations/{id}/export?format={format}`
      avec `responseType: 'blob'` et résout un `Blob`.
- [ ] `ExportService.exportAnswer` appelle `POST /api/export/answer?format={format}` avec le corps
      `AnswerExportRequest` dérivé de la `AskResponse` et `responseType: 'blob'`.
- [ ] Le téléchargement est déclenché via un `<a download>` (objet URL révoqué après clic).
- [ ] Une erreur HTTP déclenche un `MatSnackBar` d'erreur, sans exception non gérée.
- [ ] Le menu d'export de la réponse n'apparaît que lorsqu'une réponse est présente.
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md`; boutons Material; espacements multiples de 4px.

---

## Périmètre

### Hors scope (explicite)

- Aucun rendu client du Markdown/PDF (le backend produit le fichier).
- Pas de prévisualisation avant téléchargement.
- Pas de choix de nom de fichier par l'utilisateur (nom fourni par le backend).

---

## Technique

### Composants Angular

- `ExportService` (`core/services/export.service.ts`) — `exportConversation(id, format)`,
  `exportAnswer(answer, format)` ; type `ExportFormat = 'markdown' | 'pdf'` ; utilitaire de
  déclenchement de téléchargement à partir d'un `HttpResponse<Blob>`.
- `ChatComponent` — ajout d'un `mat-menu` d'export dans l'en-tête (import `MatMenuModule`).
- `AskComponent` — ajout d'un `mat-menu` d'export sous la réponse (import `MatMenuModule`).

### Endpoints consommés (existants côté backend SF-14-01)

| Méthode | URL |
|---------|-----|
| GET | `/api/conversations/{id}/export?format={markdown\|pdf}` |
| POST | `/api/export/answer?format={markdown\|pdf}` |

### Migration / tables

Aucune.

---

## Plan de test

### Tests unitaires (`export.service.spec.ts`)

- [ ] `exportConversation` émet un GET vers l'URL attendue avec `responseType blob` et le bon `format`.
- [ ] `exportAnswer` émet un POST vers `/api/export/answer?format=pdf` avec le corps attendu.
- [ ] Le nom de fichier est extrait de l'en-tête `Content-Disposition` quand présent.

### Tests composant (`chat.component.spec.ts`, `ask.component.spec.ts`)

- [ ] Chat : cliquer « Exporter → Markdown » appelle `ExportService.exportConversation(id,'markdown')`.
- [ ] Chat : sur erreur, un snackbar est affiché.
- [ ] Ask : cliquer « Exporter → PDF » appelle `ExportService.exportAnswer(answer,'pdf')`.

### Isolation

- [ ] Non applicable côté frontend (isolation garantie backend via JWT ; le service n'envoie aucun
      identifiant utilisateur).

---

## Dépendances

### Subfeatures bloquantes

- `SF-14-01` (backend, contrat figé) — endpoints doivent être mergés avant le frontend (backend AVANT
  frontend). Tests frontend indépendants (mock `HttpTestingController`).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- Téléchargement via création d'un `<a>` avec `URL.createObjectURL` puis révocation — pas de
  dépendance tierce (pas de `file-saver`).
- Le service lit `HttpResponse` complète (`observe: 'response'`) pour récupérer `Content-Disposition`.
</content>
