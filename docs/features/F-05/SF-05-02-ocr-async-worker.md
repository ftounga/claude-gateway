# Mini-spec — [F-05 / SF-05-02] OCR asynchrone PDF : worker de polling

## Identifiant

`F-05 / SF-05-02`

## Feature parente

`F-05` — OCR (Textract)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-05-02-ocr-async-worker`

---

## Objectif

> Compléter le traitement OCR **asynchrone** des documents PDF/TIFF : un worker intra-backend
> (`@Scheduled`) interroge périodiquement les jobs OCR soumis (statut `PROCESSING`) via l'interface
> `OcrProvider`, et met à jour le document (`EXTRACTED` + texte/brut, ou `FAILED`).

---

## Comportement attendu

### Cas nominal

1. Un document PDF/TIFF a été soumis en SF-05-01 (statut `PROCESSING`, `provider_job_id` renseigné).
2. Le worker `OcrPollingWorker` s'exécute périodiquement (`app.ocr.polling.interval`, défaut 15 s).
3. Pour chaque document `PROCESSING` avec un `provider_job_id`, il appelle
   `OcrProvider.pollAsync(jobId)` :
   - `IN_PROGRESS` → le document reste `PROCESSING` (réessai au cycle suivant).
   - `SUCCEEDED` → `extracted_text` + `textract_raw` persistés, statut `EXTRACTED`.
   - `FAILED` → statut `FAILED`, message métier neutre.
4. La logique de complétion vit dans `DocumentService.pollPendingJobs()` (le worker n'est qu'un
   déclencheur de planification — layering respecté).

### Cas d'erreur

| Situation | Comportement attendu | Effet |
|-----------|---------------------|-------|
| Fournisseur OCR indisponible/en échec lors du polling | le document reste `PROCESSING`, réessai au cycle suivant ; log neutre | pas de perte, pas de secret loggé |
| Job renvoyé `FAILED` par le fournisseur | document `FAILED`, message neutre | terminal |
| Document `PROCESSING` sans `provider_job_id` (anomalie) | ignoré (pas de NPE) | robustesse |

---

## Critères d'acceptation

- [ ] `pollPendingJobs()` fait passer un document `PROCESSING` dont le job est `SUCCEEDED` à
      `EXTRACTED` avec `extractedText` peuplé.
- [ ] Un job `IN_PROGRESS` laisse le document `PROCESSING` (aucun changement).
- [ ] Un job `FAILED` fait passer le document à `FAILED` avec message neutre.
- [ ] Une exception fournisseur pendant le polling laisse le document `PROCESSING` (réessayable),
      sans propager de stacktrace ni journaliser de secret/contenu.
- [ ] Le polling ne traite que les documents au statut `PROCESSING` (pas de retraitement des
      `EXTRACTED`/`FAILED`).
- [ ] Le worker planifié est désactivable par configuration (désactivé en tests).

---

## Périmètre

### Hors scope (explicite)

- Extraction synchrone (SF-05-01, déjà livrée).
- Workers dédiés / file de messages (V2 — OQ-10 : décision par défaut = scheduler intra-backend).
- Retry/backoff sophistiqué, dead-letter (V2).

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| app.ocr.polling.enabled | booléen, défaut `true` ; `false` en profil test |
| app.ocr.polling.interval | durée (ms) entre deux cycles, défaut 15000 |

---

## Technique

### Endpoint(s)

Aucun (traitement de fond).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| documents | SELECT (par statut) / UPDATE | complétion des jobs async |

### Migration Liquibase

- [ ] Non applicable (schéma inchangé : `provider_job_id`/`textract_raw` créés en 010).

### Composants

- `OcrPollingWorker` (`@Scheduled`) — déclencheur périodique, appelle `DocumentService.pollPendingJobs()`.
- `DocumentService.pollPendingJobs()` — logique de complétion (business).
- `@EnableScheduling` (nouvelle config `SchedulingConfig`).

---

## Plan de test

### Tests unitaires

- [ ] `pollPendingJobs()` — job `SUCCEEDED` → `EXTRACTED` + texte/brut.
- [ ] `pollPendingJobs()` — job `IN_PROGRESS` → reste `PROCESSING`, non sauvegardé.
- [ ] `pollPendingJobs()` — job `FAILED` → `FAILED` + message neutre.
- [ ] `pollPendingJobs()` — exception fournisseur → reste `PROCESSING` (réessayable).

### Tests d'intégration

- [ ] Bout-en-bout : `POST /documents` (pdf, stub async) → `PROCESSING` ; après
      `pollPendingJobs()` (stub renvoie `SUCCEEDED`) → `GET /documents/{id}` = `EXTRACTED`
      avec texte, isolation `user_id` préservée.

### Isolation user_id

- [x] Applicable — le worker met à jour les documents sans jamais mélanger les propriétaires
      (mise à jour par entité, `user_id` inchangé) ; test bout-en-bout vérifie la lecture isolée.

---

## Dépendances

### Subfeatures bloquantes

- `SF-05-01` — statut : done (mergée, PR #37).

### Questions ouvertes impactées

- [x] OQ-10 — **contournée / décidée par défaut** : worker intra-backend `@Scheduled` (V1).
      Réversible vers workers dédiés + file (V2). Tracée dans `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Traitement lourd async (règle dure)** : le polling s'exécute hors du thread HTTP, dans le
  scheduler. Aucune opération OCR lourde n'est faite en ligne.
- **Préoccupation transversale (Navigation/limites/auth)** : aucune — pas de nouvel endpoint, pas
  de changement d'auth ni de résolution de tenant. Le worker réutilise `DocumentRepository` et
  n'altère pas l'isolation `user_id`.
- Le worker est **désactivé en tests** (`app.ocr.polling.enabled=false`) pour un déterminisme
  total : la logique est testée en appelant `pollPendingJobs()` directement.
