# Mini-spec — [F-06 / SF-06-03] Écran documents : statut d'indexation + nombre de chunks

## Identifiant

`F-06 / SF-06-03`

## Feature parente

`F-06` — Ingestion RAG

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-06-03-documents-indexation`

## Contrat API

**Importé de SF-06-01-backend** (figé, déjà mergé sur `main`) :
- `DocumentStatus` = `'UPLOADED' | 'PROCESSING' | 'EXTRACTED' | 'INDEXING' | 'INDEXED' | 'FAILED'`
- `DocumentResponse` (GET/POST `/api/documents`) gagne `chunkCount: number`
- `DocumentDetailResponse` (GET `/api/documents/{id}`) hérite de `chunkCount`

---

## Objectif

> Refléter dans l'écran documents les nouveaux états d'indexation RAG (`INDEXING`/`INDEXED`) et le nombre de chunks, et afficher le texte extrait aussi pour les documents indexés.

---

## Comportement attendu

### Cas nominal

1. La liste des documents affiche un badge de statut incluant `Indexation…` (`INDEXING`) et `Indexé` (`INDEXED`), et une colonne « Chunks » (nombre de chunks, ou `—` tant que non indexé).
2. Le rafraîchissement périodique léger reste actif tant qu'un document est dans un état « en cours » (`PROCESSING` **ou** `INDEXING`), et s'arrête quand tout est terminal.
3. Le détail d'un document affiche le texte extrait pour `EXTRACTED` **et** `INDEXED` ; pour `INDEXED`, il indique le nombre de chunks.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Statut `FAILED` | badge d'erreur + message neutre (inchangé) |
| Échec de chargement de la liste/détail | `MatSnackBar` d'erreur (inchangé), pas de `window.alert` |

---

## Critères d'acceptation

- [ ] `statusDisplay('INDEXING')` → badge `badge--warning` (`Indexation…`) ; `statusDisplay('INDEXED')` → `badge--success` (`Indexé`).
- [ ] Colonne « Chunks » : affiche `chunkCount` pour `INDEXED`, `—` sinon.
- [ ] Le rafraîchissement continue tant qu'un document est `PROCESSING` ou `INDEXING`.
- [ ] Le détail affiche le texte extrait pour `EXTRACTED` et `INDEXED`.
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; badges via classes existantes ; notifications via `MatSnackBar` ; table avec `mat-paginator`.
- [ ] `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope (explicite)

- Toute nouvelle route ou tout nouvel appel API (contrat déjà mergé) ; consultation des chunks eux-mêmes (F-07).

---

## Préoccupations transversales

- **Navigation / routing** : aucune nouvelle route ni guard modifié. Route `/documents` (lazy, `authGuard`) inchangée. Composant impacté : `DocumentsComponent` uniquement.

---

## Technique

### Composants Angular

- `documents.models.ts` — `DocumentStatus` += `INDEXING`/`INDEXED` ; `DocumentResponse` += `chunkCount`.
- `documents.component.ts` — `STATUS_DISPLAY` (INDEXING/INDEXED), colonne `chunks`, condition de polling, condition d'affichage du texte.
- `documents.component.html` — colonne « Chunks », détail texte pour `INDEXED`.

### Endpoint(s)

Aucun nouveau (contrat SF-06-01 déjà en ligne).

### Migration Liquibase

- [x] Non applicable.

---

## Plan de test

### Tests unitaires (Karma/Jasmine)

- [ ] `documents.component.spec` — badges `INDEXING`/`INDEXED` mappés ; polling actif si un doc `INDEXING` ; détail affiche le texte pour `INDEXED`.
- [ ] `documents.service.spec` — fixtures incluant `chunkCount` (contrat étendu) ; appels inchangés.

### Isolation utilisateur

- [x] Non applicable côté frontend (garantie backend via JWT). Le frontend ne parle qu'à `/api/documents`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-06-01` (contrat DTO) + `SF-06-02` (worker qui produit `INDEXED`) — Done/mergées.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- `EXTRACTED` conserve son badge `badge--success` existant (état transitoire avant l'auto-indexation) pour ne pas casser le comportement/tests existants ; `INDEXED` est l'état terminal « prêt pour la recherche » (F-07).
- Réutilise les classes de badge existantes du design system (aucune nouvelle couleur).
