# Mini-spec — F-14 / SF-14-01 Export conversations & réponses citées (backend)

## Identifiant

`F-14 / SF-14-01`

## Feature parente

`F-14` — Export conversations/réponses (PDF/Markdown des échanges et réponses citées)

## Statut

`ready`

## Date de création

2026-07-02

## Branche Git

`feat/SF-14-01-export-backend`

---

## Objectif

> En une phrase : exposer des endpoints permettant à l'utilisateur de télécharger une conversation
> ou une réponse documentée citée sous forme de fichier **Markdown** ou **PDF**.

---

## Comportement attendu

### Cas nominal

1. **Export d'une conversation** — `GET /api/conversations/{id}/export?format=markdown|pdf`
   - Le service résout la conversation possédée par l'utilisateur courant (isolation `user_id`,
     réutilise `ConversationService.getOwned` + `messagesOf`).
   - Il rend le fil complet (métadonnées + messages ordonnés) au format demandé.
   - Réponse `200` : le corps est le fichier ; en-têtes
     `Content-Type` (`text/markdown;charset=UTF-8` ou `application/pdf`) et
     `Content-Disposition: attachment; filename="conversation-{id}.{md|pdf}"`.

2. **Export d'une réponse citée** — `POST /api/export/answer?format=markdown|pdf`
   - Endpoint **stateless** : le corps de la requête (`AnswerExportRequest`) porte la réponse à
     exporter (F-07 étant sans persistance, le frontend renvoie la réponse déjà obtenue).
   - Le service rend un document « Réponse documentée » : question, réponse, modèle, statut
     d'ancrage, sources citées `[filename:page:chunkIndex] snippet`.
   - Réponse `200` : fichier + en-têtes comme ci-dessus, `filename="reponse-{yyyyMMdd-HHmmss}.{md|pdf}"`.

Le rendu (Markdown/PDF) est **synchrone** : opération purement CPU, bornée par la taille d'une
conversation (pas d'appel réseau, pas d'IA) — hors périmètre de la règle « traitements lourds async ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `format` absent | Défaut `markdown` | 200 |
| `format` non supporté (`docx`, ...) | `{"error":"validation_error"}` | 400 |
| Conversation inexistante ou appartenant à un autre utilisateur | `{"error":"not_found"}` | 404 |
| `question` ou `answer` vide (export réponse) | `{"error":"validation_error"}` | 400 |
| Requête non authentifiée | `{"error":"unauthorized"}` | 401 |

---

## Critères d'acceptation

- [ ] `GET /conversations/{id}/export?format=markdown` renvoie 200, `Content-Type text/markdown`,
      `Content-Disposition attachment`, et le corps contient le titre et le contenu des messages.
- [ ] `GET /conversations/{id}/export?format=pdf` renvoie 200, `Content-Type application/pdf`, et le
      corps est un PDF valide (préfixe `%PDF`).
- [ ] `format` absent ⇒ export Markdown par défaut (200).
- [ ] `format` inconnu ⇒ 400 `validation_error`.
- [ ] Export d'une conversation d'un autre utilisateur ⇒ 404 `not_found` (isolation `user_id`).
- [ ] Requête sans JWT ⇒ 401.
- [ ] `POST /export/answer?format=markdown` renvoie 200 et le corps contient question, réponse et
      chaque source citée `[filename:page:chunkIndex]`.
- [ ] `POST /export/answer` avec `answer` vide ⇒ 400 `validation_error`.
- [ ] Aucune donnée d'un autre utilisateur n'est accessible (export réponse = données envoyées par
      l'appelant uniquement ; export conversation = filtré `user_id`).

---

## Périmètre

### Hors scope (explicite)

- Aucune persistance d'historique d'export (pas de table `exports`).
- Pas d'export asynchrone / par lot / e-mail (rendu synchrone à la demande).
- Pas de mise en forme Markdown riche dans le PDF (rendu texte structuré, pas de moteur HTML/CSS).
- Pas d'export des pièces jointes binaires (seul le texte des messages est rendu).
- Frontend (boutons + téléchargement) ⇒ SF-14-02.

---

## Contrat API (figé — importé tel quel par SF-14-02)

### `GET /api/conversations/{id}/export`

- Query : `format` optionnel ∈ `{markdown, pdf}` (défaut `markdown`).
- Auth : JWT Bearer requis.
- 200 : bytes ; `Content-Type: text/markdown;charset=UTF-8` | `application/pdf` ;
  `Content-Disposition: attachment; filename="conversation-{id}.{md|pdf}"`.
- 400 `validation_error` | 404 `not_found` | 401 `unauthorized`.

### `POST /api/export/answer`

- Query : `format` optionnel ∈ `{markdown, pdf}` (défaut `markdown`).
- Auth : JWT Bearer requis.
- Body `AnswerExportRequest` :
  ```json
  {
    "question": "string (requis, non vide)",
    "answer": "string (requis, non vide)",
    "model": "string | null",
    "grounded": true,
    "citations": [
      { "documentId": "uuid|null", "filename": "string", "page": 1, "chunkIndex": 0, "snippet": "string" }
    ]
  }
  ```
- 200 : bytes ; en-têtes comme ci-dessus ; `filename="reponse-{yyyyMMdd-HHmmss}.{md|pdf}"`.
- 400 `validation_error` | 401 `unauthorized`.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Normalisation |
|-------|-------------|-------------|-----------------|---------------|
| `format` (query) | Non (défaut `markdown`) | — | `markdown` \| `pdf` (insensible casse) | trim/lowercase |
| `question` | Oui | — | non vide après trim | — |
| `answer` | Oui | — | non vide après trim | — |
| `model` | Non | 64 | texte libre | — |
| `citations[]` | Non | — | liste éventuellement vide | — |

---

## Technique

### Endpoints

| Méthode | URL | Auth |
|---------|-----|------|
| GET | `/api/conversations/{id}/export` | Oui |
| POST | `/api/export/answer` | Oui |

### Tables impactées

Aucune (lecture seule via `ConversationService`). **Aucune migration Liquibase.**

### Nouveau package `fr.claudegateway.export`

- `ExportController` — orchestration HTTP (aucune logique métier).
- `ExportService` — produit un `ExportedFile(filename, contentType, byte[] content)`.
- `ExportFormat` (enum `MARKDOWN`, `PDF`) + `fromParam` (400 si inconnu).
- `MarkdownExporter`, `PdfExporter` — rendu par format (PDF via OpenPDF, pur Java, aucun natif).
- `dto/AnswerExportRequest`, `dto/AnswerCitation`.
- `UnsupportedExportFormatException` → mappée 400 dans `GlobalExceptionHandler`.

### Dépendance ajoutée

`com.github.librepdf:openpdf` (LGPL/MPL, pur Java) — confinée à `PdfExporter`.

---

## Plan de test

### Tests unitaires (`ExportServiceTest`)

- [ ] conversation → Markdown : contient titre, rôles, contenus des messages.
- [ ] conversation → PDF : bytes non vides, préfixe `%PDF`.
- [ ] réponse → Markdown : contient question, réponse, citation `[filename:page:chunkIndex]`.
- [ ] réponse → PDF : préfixe `%PDF`.
- [ ] `ExportFormat.fromParam("docx")` → `UnsupportedExportFormatException`.

### Tests d'intégration (`ExportApiIntegrationTest`)

- [ ] GET export markdown → 200, `text/markdown`, `Content-Disposition` attachment, corps attendu.
- [ ] GET export pdf → 200, `application/pdf`, corps `%PDF`.
- [ ] GET export sans format → 200 markdown.
- [ ] GET export format inconnu → 400 `validation_error`.
- [ ] GET export conversation d'autrui → 404 `not_found`.
- [ ] GET export sans JWT → 401.
- [ ] POST answer markdown → 200 contient la réponse et la citation.
- [ ] POST answer `answer` vide → 400.
- [ ] POST answer sans JWT → 401.

### Isolation `user_id`

- [ ] Applicable — test : Alice ne peut pas exporter la conversation de Bob (404).

---

## Dépendances

### Subfeatures bloquantes

- `F-02` (conversations/messages) — Done. `F-07` (réponse citée, contrat) — Done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Arbitrage réversible 🟠** : « réponses citées » traité en **stateless** (le frontend renvoie la
  réponse F-07 obtenue), car F-07 ne persiste pas l'historique. Alternative écartée : créer une table
  d'historique de réponses (hors périmètre F-14, introduirait une nouvelle entité). Réversible : un
  futur historique pourra alimenter le même moteur de rendu.
- **Arbitrage réversible 🟠** : PDF via OpenPDF (rendu texte programmatique) plutôt qu'un moteur
  HTML→PDF (flying-saucer). Plus léger, sans dépendance native ; réversible (le moteur est confiné à
  `PdfExporter`).
- Rendu **synchrone** justifié : pas d'I/O réseau ni d'appel IA, coût borné par la taille du fil.
</content>
