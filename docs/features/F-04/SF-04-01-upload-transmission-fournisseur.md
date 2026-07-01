# Mini-spec — F-04 / SF-04-01 Upload & transmission fichier au fournisseur

## Identifiant

`F-04 / SF-04-01`

## Feature parente

`F-04` — Upload & transmission fichiers (sans OCR ni indexation)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-04-01-upload-transmission-fournisseur`

---

## Objectif

Exposer `POST /api/upload` (multipart) qui valide un fichier, le **transmet au fournisseur IA**
(Anthropic Files API via l'interface `AIProvider`, jamais Anthropic en direct) et renvoie une
référence neutre du fichier, en persistant uniquement ses **métadonnées** (aucun OCR, aucune
indexation, aucun stockage du contenu — PROJECT.md §11.6).

---

## Comportement attendu

### Cas nominal

1. L'utilisateur authentifié envoie `POST /api/upload` en `multipart/form-data`, champ `file`.
2. Le backend valide : fichier non vide, type MIME dans la liste blanche (types supportés par
   Claude : PDF, images png/jpeg/gif/webp, texte plain/markdown/csv), taille ≤ 32 Mo.
3. Le service transmet le contenu au fournisseur via `AIProvider.uploadFile(...)` (Files API,
   en-tête beta `files-api-2025-04-14`) et récupère l'identifiant fichier du fournisseur.
4. Une ligne `uploaded_files` est persistée : `id` (app), `user_id` (isolation), `provider_file_id`,
   `filename`, `media_type`, `size_bytes`, `created_at`. **Le contenu binaire n'est jamais stocké.**
5. Réponse `200` : `{ id, filename, mediaType, sizeBytes }` (jamais le `provider_file_id` brut ni la clé).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Fichier absent / vide | `validation_error` | 400 |
| Type MIME hors liste blanche | `unsupported_file_type` | 415 |
| Taille > 32 Mo | `file_too_large` | 413 |
| Non authentifié | 401 (entry point JSON) | 401 |
| Fournisseur non configuré (clé absente) | `provider_unavailable` | 503 |
| Échec appel fournisseur | `provider_error` | 502 |

---

## Critères d'acceptation

- [ ] `POST /api/upload` avec un PDF valide → 200 + `{id, filename, mediaType, sizeBytes}` et une ligne `uploaded_files` créée pour l'utilisateur courant.
- [ ] Le fichier est transmis au fournisseur via l'interface `AIProvider` (jamais d'appel Anthropic direct dans le service).
- [ ] Fichier vide → 400 `validation_error` ; type non supporté → 415 ; > 32 Mo → 413.
- [ ] Non authentifié → 401 ; aucune ligne créée.
- [ ] Fournisseur dormant (clé absente) → 503 ; aucune ligne créée.
- [ ] Aucune clé (plateforme/BYOK) ni `provider_file_id` n'apparaît dans la réponse ni dans les logs.
- [ ] `user_id` renseigné = utilisateur du JWT (jamais depuis un paramètre client).

---

## Périmètre

### Hors scope (explicite)

- Aucun OCR, aucune extraction de texte, aucune indexation/embeddings (V2).
- Aucun stockage du contenu binaire (ni base, ni objet S3) au-delà du relais.
- Rattachement du fichier à un message de chat → SF-04-02.
- UI d'upload → SF-04-03.
- Liste / suppression des fichiers uploadés (relève de F-08/F-11, hors F-04 V1).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| id | UUID app | `@UuidGenerator` |
| user_id | utilisateur JWT | `CurrentUser.requireId()` |
| created_at | now() | base |

---

## Contraintes de validation

| Champ | Obligatoire | Taille max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-----------|----------------------------|---------------|
| file | Oui | 32 Mo | MIME ∈ liste blanche | — |
| filename | Oui | 255 | non vide (dérivé du multipart) | `StringUtils.cleanPath` |
| media_type | Oui | 128 | issu du `Content-Type` de la part | lowercase |

Liste blanche (config `app.upload.allowed-types`, valeur par défaut) :
`application/pdf, image/png, image/jpeg, image/gif, image/webp, text/plain, text/markdown, text/csv`.
Taille max : `app.upload.max-size` (défaut 32 Mo). Décision réversible (arbitrage tracé dans la PR).

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/upload` (multipart `file`) | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `uploaded_files` | INSERT | métadonnées uniquement, isolation `user_id` |

### Migration Liquibase

- [x] Oui — `007-uploaded-files.xml` (changesets postgresql + h2), UUID `007-uploaded-files-postgresql` / `-h2`.

### Interface fournisseur

- `AIProvider.uploadFile(ProviderFileUpload) : ProviderFileReference` (nouveau contrat, implémenté par `AnthropicProvider`).

---

## Plan de test

### Tests unitaires

- [ ] `UploadService` — nominal : valide + délègue à `AIProvider.uploadFile` + persiste métadonnées.
- [ ] `UploadService` — fichier vide → `EmptyFileException`.
- [ ] `UploadService` — type non supporté → `UnsupportedFileTypeException`.
- [ ] `UploadService` — taille > max → `FileTooLargeException`.
- [ ] `AnthropicProvider.uploadFile` — dormant (clé absente) → `AIProviderUnavailableException` (sans réseau).

### Tests d'intégration

- [ ] `POST /api/upload` (PDF) → 200 + corps attendu + ligne persistée.
- [ ] `POST /api/upload` sans part → 400.
- [ ] `POST /api/upload` type non supporté → 415.
- [ ] `POST /api/upload` non authentifié → 401.
- [ ] `POST /api/upload` fournisseur dormant → 503, la réponse ne contient jamais la clé.

### Isolation utilisateur

- [x] Applicable — la ligne `uploaded_files` porte le `user_id` du JWT ; vérifié en intégration.

---

## Dépendances

### Subfeatures bloquantes

- `F-02` (interface `AIProvider`, `CurrentUser`, sécurité JWT) — Done.

### Questions ouvertes impactées

- Aucune (liste blanche + taille max tranchées par défaut, tracées comme arbitrage réversible).

---

## Notes et décisions

- **Arbitrage réversible** : `POST /upload` transmet au fournisseur et renvoie une **référence
  neutre** ; le `provider_file_id` reste interne (colonne `uploaded_files`). Alternative écartée :
  renvoyer directement le `file_id` Anthropic (fuite d'un détail fournisseur).
- **Arbitrage réversible** : liste blanche MIME + plafond 32 Mo (aligné sur la limite requête PDF
  d'Anthropic). Externalisés en config → ajustables sans changement de code.
- Multipart Spring configuré (`spring.servlet.multipart.max-file-size/max-request-size`) ;
  `MaxUploadSizeExceededException` mappée en 413.
