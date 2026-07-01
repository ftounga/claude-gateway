# Mini-spec — [F-06 / SF-06-01] Cœur d'ingestion RAG (chunking + embeddings + persistance)

## Identifiant

`F-06 / SF-06-01`

## Feature parente

`F-06` — Ingestion RAG (chunking 400 tokens / overlap 50, embeddings via API fournisseur, stockage `chunks.embedding` pgvector, auto-index)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-06-01-ingestion-core`

---

## Objectif

> Transformer le texte OCR d'un document `EXTRACTED` en chunks vectorisés persistés (`chunks` + `chunks.embedding` pgvector), via des abstractions fournisseur (`EmbeddingProvider`) et stockage vectoriel (`EmbeddingStore`), puis faire passer le document à `INDEXED`.

---

## Comportement attendu

### Cas nominal

1. En entrée : un `Document` de l'utilisateur au statut `EXTRACTED` portant `extractedText` non vide.
2. `IngestionService.ingest(document)` :
   - passe le document à `INDEXING` (persisté), pour empêcher une double prise en charge ;
   - découpe `extractedText` en chunks (~400 « tokens » / overlap 50) via `Chunker`, avec offsets `charStart`/`charEnd` ;
   - génère les embeddings des chunks via `EmbeddingProvider.embed(...)` (batch) — jamais un SDK fournisseur en direct ;
   - supprime les chunks pré-existants du document (idempotence : ré-ingestion sûre), persiste les nouveaux chunks (`chunks`, isolés par `user_id`) ;
   - persiste chaque vecteur via `EmbeddingStore.store(chunkId, embedding)` (pgvector en Postgres ; no-op ailleurs) ;
   - met `chunkCount`, passe le document à `INDEXED`, persiste.
3. Aucun traitement lourd n'est fait dans un thread HTTP : `ingest(...)` est destiné à être appelé par un worker (SF-06-02).

### Cas d'erreur

| Situation | Comportement attendu | Effet |
|-----------|---------------------|-------|
| `extractedText` vide/blanc | 0 chunk créé, document `INDEXED` avec `chunkCount = 0` | pas d'appel embeddings |
| `EmbeddingProvider` indisponible/en échec | document repasse à `FAILED`, `errorMessage` neutre ; aucun secret loggé | chunks nettoyés/non finalisés |
| Document non `EXTRACTED` (déjà `INDEXED`, `PROCESSING`, …) | ignoré (aucune action) | idempotence |
| Ré-ingestion d'un document déjà indexé | chunks précédents supprimés puis recréés (isolation `user_id`) | pas de doublon |

Aucune stacktrace ni secret (clé API embeddings) n'est journalisé ni renvoyé.

---

## Critères d'acceptation

- [ ] Un document `EXTRACTED` avec texte → `INDEXED`, N chunks persistés (`chunk_index` croissant, `char_start`/`char_end` cohérents), `chunkCount = N`.
- [ ] Le domaine (`IngestionService`, `Chunker`) ne dépend que des interfaces `EmbeddingProvider` / `EmbeddingStore` (Provider Independence) — aucun `import` SDK fournisseur hors du package `provider`.
- [ ] `StubEmbeddingProvider` (défaut) produit des vecteurs déterministes de dimension configurée (1536) sans dépendance externe ; `ApiEmbeddingProvider` (activé sur `app.rag.embedding.provider=api`) confine l'appel HTTP + la clé (jamais loggée).
- [ ] DDL vectoriel isolé en changesets `dbms=postgresql` ; les tests H2 passent (store `noop` par défaut, colonne `embedding` non mappée par l'entité).
- [ ] Ré-ingestion idempotente (pas de doublon de chunks).
- [ ] Isolation `user_id` : les chunks d'un document portent le `user_id` du propriétaire ; suppression/lecture filtrent sur `user_id`.
- [ ] Échec fournisseur → `FAILED` + message neutre, aucun secret loggé.
- [ ] `mvn -pl backend test` vert.

---

## Périmètre

### Hors scope (explicite)

- Le worker/scheduler d'auto-index (→ SF-06-02).
- L'affichage frontend des statuts `INDEXED`/`INDEXING` et du `chunkCount` (→ SF-06-03).
- La recherche vectorielle / Q&A (`/ask`) (→ F-07).
- La numérotation de page par chunk (`page_number`) : laissée `null` en F-06 (dérivation par page depuis le brut Textract = travail ultérieur, réversible). Les citations paginées relèvent de F-07.
- L'index HNSW (OQ-03) : on conserve IVFFlat `lists=100` déjà provisionné (002-pgvector).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `chunks.user_id` | `documents.user_id` | propriétaire du document (isolation) |
| `chunks.chunk_index` | 0..N-1 | ordre de découpage |
| `chunks.page_number` | `null` | non dérivé en F-06 |
| `documents.chunk_count` | 0 | mis à N à l'`INDEXED` |
| `documents.status` | `INDEXING` puis `INDEXED` | transition d'ingestion |

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs / Règle | Normalisation |
|-------|-------------|-----------------|---------------|
| `app.rag.chunk.max-tokens` | défaut 400 | > 0 | — |
| `app.rag.chunk.overlap-tokens` | défaut 50 | 0 ≤ overlap < max | — |
| `app.rag.embedding.dimension` | défaut 1536 (OQ-01) | > 0 | — |
| `app.rag.embedding.provider` | défaut `stub` | `stub` \| `api` | — |
| `app.rag.vector-store` | défaut `noop` | `noop` \| `pgvector` | — |

Tokenisation approximée par mots (séparateurs d'espaces) — arbitrage réversible ; un tokenizer exact est un travail ultérieur.

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Le contrat DTO existant `/api/documents` est **étendu** (figé pour SF-06-03 frontend) :
- `DocumentResponse` gagne `chunkCount: number`.
- `DocumentStatus` gagne les valeurs `INDEXING`, `INDEXED`.
- `DocumentDetailResponse` hérite de `chunkCount`.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `chunks` | reconstruction (migration 011) | `user_id` ajouté, FK → `documents(id)` CASCADE ; colonne `embedding vector(1536)` + index ivfflat (Postgres only) |
| `documents` | `ADD COLUMN chunk_count` | db-agnostique, défaut 0 |

### Migration Liquibase

- [x] Oui — `011-chunks-ingestion.xml` (numéro au-dessus de 010, dernier mergé sur `origin/main`).
  - `011-chunks-rebuild-postgresql` / `011-chunks-rebuild-h2` : drop + recreate `chunks` (base commune) ;
  - `011-chunks-embedding-postgresql` (dbms=postgresql) : `ADD COLUMN embedding vector(1536)` + index ivfflat — DDL vectoriel isolé ;
  - `011-documents-chunk-count` : db-agnostique.

### Composants Java (package `fr.claudegateway.rag`)

- `Chunk` (entity), `ChunkRepository` (isolation `user_id`).
- `provider/EmbeddingProvider` (interface), `StubEmbeddingProvider`, `ApiEmbeddingProvider`, `EmbeddingProperties`, exceptions.
- `store/EmbeddingStore` (interface), `NoopEmbeddingStore`, `PgVectorEmbeddingStore` (JdbcTemplate natif, `dbms` Postgres).
- `Chunker`, `RagProperties`, `RagConfig`.
- `IngestionService`.

---

## Plan de test

### Tests unitaires

- [ ] `ChunkerTest` — découpe (taille/overlap/offsets), texte vide, texte court (< 1 fenêtre).
- [ ] `IngestionServiceTest` (mock repo/provider/store) — nominal `EXTRACTED→INDEXED` + chunkCount ; texte vide → 0 chunk ; échec provider → `FAILED` ; ré-ingestion supprime les chunks ; document non `EXTRACTED` ignoré ; vecteurs envoyés au store.
- [ ] `StubEmbeddingProviderTest` — dimension = configurée, déterminisme.

### Tests d'intégration

- [ ] `IngestionIntegrationTest` (`@SpringBootTest`, H2, stub provider, noop store) — soumission image (déjà `EXTRACTED`) puis `ingest` → chunks persistés en base, isolation `user_id` vérifiée (un autre user ne voit pas les chunks), document `INDEXED`.

### Isolation utilisateur

- [x] Applicable — chunks portent `user_id` ; test : les chunks d'Alice ne sont pas lisibles/supprimables via le `user_id` de Bob.

---

## Dépendances

### Subfeatures bloquantes

- `F-05` (SF-05-01→03) — Done (fournit `documents.EXTRACTED` + `extracted_text`).

### Questions ouvertes impactées

- [x] OQ-01 (dimension embedding) — **défaut 1536** retenu et tracé (réversible via config `app.rag.embedding.dimension`).
- [x] OQ-02 (pgvector) — exploité : DDL `dbms=postgresql`, extension déjà activée (002-pgvector).
- [x] OQ-03 (IVFFlat vs HNSW) — **IVFFlat `lists=100`** conservé (défaut provisionné) ; HNSW = évolution ultérieure.

---

## Notes et décisions

- **Provider Independence (embeddings)** : le domaine dépend de `EmbeddingProvider` ; l'impl HTTP fournisseur (`ApiEmbeddingProvider`, format OpenAI-compatible `/embeddings`) est confinée au package `provider`, la clé lue depuis l'environnement, jamais loggée. Défaut `stub` (dev/tests, sans réseau).
- **Séparation génération vs stockage vectoriel** : `EmbeddingStore` isole la persistance pgvector (SQL natif `CAST(? AS vector)`) du domaine. `NoopEmbeddingStore` (défaut) garde les tests H2 verts sans colonne vectorielle ; `PgVectorEmbeddingStore` actif sur `app.rag.vector-store=pgvector` (cluster). L'entité `Chunk` ne mappe pas `embedding` (validation Hibernate OK sur H2 comme Postgres).
- **Async** : `ingest(...)` ne s'exécute jamais dans un thread HTTP ; le déclenchement planifié est SF-06-02 (OQ-10, worker intra-backend réversible).
