# Mini-spec — [F-05 / SF-05-01] OCR synchrone (images) + socle documents

## Identifiant

`F-05 / SF-05-01`

## Feature parente

`F-05` — OCR (Textract)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-05-01-ocr-sync`

---

## Objectif

> Permettre à un utilisateur de soumettre un document pour extraction OCR ; les **images**
> (PNG/JPEG) sont extraites **synchroniquement** via une couche OCR abstraite (impl AWS Textract),
> et le résultat (texte + brut Textract) est persisté sur une entité `documents` isolée par `user_id`.

---

## Comportement attendu

### Cas nominal

1. `POST /documents` (multipart `file`), JWT requis.
2. Le service valide : présence, type MIME (liste blanche), taille (≤ plafond configuré).
3. Une ligne `documents` est créée (`user_id` du contexte, statut `UPLOADED`).
4. **Image** (png/jpeg) → OCR **synchrone** via `OcrProvider.extractSync` (impl Textract
   `DetectDocumentText`). Le texte extrait + le JSON brut (`textract_raw`) sont persistés,
   statut `EXTRACTED`.
5. **PDF / TIFF** → soumission **asynchrone** via `OcrProvider.startAsync` (Textract
   `StartDocumentTextDetection`) : `provider_job_id` persisté, statut `PROCESSING`.
   (Le polling de complétion est livré en **SF-05-02**.)
6. Réponse `201 Created` avec la vue publique du document (id, filename, mediaType, sizeBytes,
   status, createdAt).
7. `GET /documents` → liste des documents de l'utilisateur courant (isolation `user_id`).
8. `GET /documents/{id}` → détail d'un document (dont `extractedText` si `EXTRACTED`),
   `404` si le document n'appartient pas à l'utilisateur.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Fichier absent / vide | message explicite | 400 |
| Type MIME hors liste blanche | message explicite | 415 |
| Fichier trop volumineux | message explicite | 413 |
| Échec extraction OCR (image) ou fournisseur indisponible | document persisté en statut `FAILED`, message neutre stocké | 201 (document `FAILED`) |
| `GET /documents/{id}` d'un autre utilisateur ou inexistant | accès refusé (indiscernable) | 404 |
| Requête non authentifiée | rejet | 401 |

---

## Critères d'acceptation

- [ ] `POST /documents` avec une image valide → 201, document `EXTRACTED`, `extractedText` peuplé.
- [ ] `POST /documents` avec un PDF valide → 201, document `PROCESSING`, `provider_job_id` non nul.
- [ ] `POST /documents` sans fichier → 400 ; type non supporté → 415 ; trop gros → 413.
- [ ] Échec OCR sur image → document `FAILED` (pas de 500, message neutre, pas de détail fournisseur).
- [ ] `GET /documents` ne renvoie que les documents de l'utilisateur courant.
- [ ] `GET /documents/{id}` d'un document d'un autre utilisateur → 404 (isolation `user_id`).
- [ ] Le domaine ne dépend que de l'interface `OcrProvider` (aucun import SDK AWS hors provider).
- [ ] Aucune clé/secret/contenu brut de document journalisé.

---

## Périmètre

### Hors scope (explicite)

- Polling de complétion des jobs PDF asynchrones → **SF-05-02**.
- Chunking, embeddings, pgvector, RAG, `/ask` → **F-06/F-07**.
- Écran de gestion documentaire (liste/statut/suppression) → **F-08** (frontend viewer minimal
  d'OCR livré en **SF-05-03**).
- Stockage S3 persistant côté Gateway (le contenu binaire n'est pas conservé en base ; l'accès S3
  éventuel pour l'async Textract est encapsulé dans l'impl provider).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| status | `UPLOADED` | à la création, avant traitement |
| user_id | utilisateur du contexte de sécurité | jamais un paramètre client |
| extracted_text | null | peuplé à `EXTRACTED` |
| provider_job_id | null | peuplé à `PROCESSING` (async) |
| created_at | horodatage base | automatique |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| file | Oui | ≤ `app.ocr.max-size` (défaut 20 Mo) | MIME ∈ {application/pdf, image/png, image/jpeg, image/tiff} | Non | — |
| filename | Oui | 255 | nettoyé (`StringUtils.cleanPath`) | Non | trim |

Notes :
- Routage sync/async : `image/png`, `image/jpeg` → **sync** ; `application/pdf`, `image/tiff` → **async**.
- Textract sync (`DetectDocumentText`) supporte PNG/JPEG ≤ 10 Mo (limite fournisseur) ; le plafond
  applicatif (20 Mo) couvre l'async PDF. Réversible (config).
- Enum `DocumentStatus` : `UPLOADED`, `PROCESSING`, `EXTRACTED`, `FAILED`. `INDEXED` sera ajouté
  par F-06 (post-OCR).

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/documents` | Oui | utilisateur authentifié |
| GET | `/documents` | Oui | utilisateur authentifié |
| GET | `/documents/{id}` | Oui | utilisateur authentifié |

#### Contrat API figé (pour parallélisation front)

- `POST /documents` — `multipart/form-data`, part `file`.
  - 201 → `DocumentResponse { id: UUID, filename: string, mediaType: string, sizeBytes: number, status: "UPLOADED"|"PROCESSING"|"EXTRACTED"|"FAILED", createdAt: string(ISO) }`
  - 400 `validation_error` | 415 `unsupported_file_type` | 413 `file_too_large` | 401.
- `GET /documents` — 200 → `DocumentResponse[]` (uniquement ceux de l'utilisateur).
- `GET /documents/{id}` — 200 → `DocumentDetailResponse { ...DocumentResponse, extractedText: string|null, errorMessage: string|null }` ; 404 `not_found` si non possédé.
- `provider_job_id` et `textract_raw` ne sont **jamais** exposés au client.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| documents | remplacement placeholder (drop+create) + INSERT/SELECT/UPDATE | table V1 conforme avec `user_id` |

### Migration Liquibase

- [x] Oui — `010-documents.xml` (changesets `postgresql` + `h2` séparés ; détache d'abord la FK
  `fk_chunks_document` (table `chunks` placeholder conservée intacte avec sa colonne pgvector),
  puis drop+create `documents`). Numéro 010 = au-dessus du dernier mergé (009).

### Composants Angular (si applicable)

- Aucun (frontend viewer minimal en SF-05-03).

---

## Plan de test

### Tests unitaires

- [ ] `DocumentService` — image → `EXTRACTED`, texte + raw persistés (provider mocké).
- [ ] `DocumentService` — pdf → `PROCESSING`, `provider_job_id` persisté (provider mocké).
- [ ] `DocumentService` — échec provider sur image → `FAILED`, message neutre.
- [ ] `DocumentService` — validation type/taille/fichier vide.
- [ ] `DocumentService` — `getById` d'un autre utilisateur → introuvable (isolation).
- [ ] `StubOcrProvider` — extractSync/startAsync déterministes.

### Tests d'intégration

- [ ] `POST /documents` (image) → 201 `EXTRACTED`.
- [ ] `POST /documents` (pdf) → 201 `PROCESSING`.
- [ ] `POST /documents` sans fichier → 400.
- [ ] `POST /documents` type non supporté → 415.
- [ ] `GET /documents/{id}` d'un autre utilisateur → 404.
- [ ] `GET /documents` sans JWT → 401.

### Isolation user_id

- [x] Applicable — un utilisateur A ne peut pas lire un document de B (404).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (socle F-05).

### Questions ouvertes impactées

- [x] OQ-10 (worker intégré vs séparé) — **contournée** : soumission async ici, polling
  intra-backend (`@Scheduled`) en SF-05-02. Décision par défaut = scheduler intra-backend V1
  (réversible). Tracée dans `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Provider Independence (règle dure)** : OCR via l'interface `OcrProvider` ; impl `TextractOcrProvider`
  (AWS SDK) activée par `app.ocr.provider=textract` ; `StubOcrProvider` par défaut (dev/tests).
  Le domaine (`DocumentService`) n'importe jamais le SDK AWS.
- **Traitements lourds async (règle dure)** : le PDF (Textract async) n'est jamais traité dans le
  thread HTTP — il est seulement soumis ; le polling est asynchrone (SF-05-02). L'OCR image
  synchrone est un appel unique conforme à la spec (« images DetectDocumentText sync »).
- **Secrets** : clé/creds AWS via IRSA/env, jamais en dur, jamais loggés ; erreurs fournisseur
  → message métier neutre.
- **pgvector** : SF-05-01 ne touche pas `chunks`/vector ; la FK `chunks→documents` est détachée pour
  permettre le remplacement de `documents`, la colonne `chunks.embedding` (migration 002) reste intacte.
