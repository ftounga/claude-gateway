# Mini-spec — F-148 / SF-148-10 — Colonne embedding + store vectoriel de la mémoire de résolutions

## Identifiant

`F-148 / SF-148-10`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-10-01

## Branche Git

`feat/SF-148-10-embedding-colonne-store`

---

## Objectif

> En une phrase : donner à la table `resolution_memory` une colonne vectorielle `embedding vector(1536)` (Postgres only) et un `ResolutionMemoryEmbeddingStore` (écriture + recherche de voisins isolée `(user_id, host_id)`), **dormants** — aucun branchement dans le chemin du tour à ce stade.

---

## Comportement attendu

### Cas nominal

- La migration `137` ajoute, **sur PostgreSQL uniquement** (changesets `dbms="postgresql"`, MARK_RAN sur H2 comme la `135`), la colonne `resolution_memory.embedding vector(1536)` et un index HNSW `vector_cosine_ops`.
- `ResolutionMemoryEmbeddingStore.store(id, vector)` range un vecteur sur la ligne d'une résolution (SQL natif `CAST(? AS vector)`).
- `ResolutionMemoryEmbeddingStore.searchSimilarQuestions(userId, hostId, queryVector, topN)` renvoie les résolutions les plus proches (ids + distance cosine), **filtrées `user_id` ET `host_id`** dans le `WHERE`, ordonnées par `embedding <=> CAST(? AS vector)` croissant, bornées à `topN`.
- `ResolutionMemoryRepository.findByIdAndUserIdAndHostId(id, userId, hostId)` — re-filtre par ids à la relecture (défense en profondeur, consommé par SF-148-11).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `id`/`vector` nul ou vecteur vide dans `store` | no-op (aucune écriture) |
| `userId`/`hostId`/`queryVector` nul, `topN ≤ 0` dans `searchSimilarQuestions` | liste vide |
| Base H2 (dev/tests) | colonne `vector` absente (MARK_RAN) ; le store n'est jamais sollicité (dormant) |

---

## Critères d'acceptation

- [ ] Migration `137-resolution-memory-embedding.xml` : colonne `embedding vector(1536)` + index HNSW `vector_cosine_ops`, changesets `dbms="postgresql"`, rollback `DROP COLUMN`/`DROP INDEX`.
- [ ] La migration est idempotente (`ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`) et ne touche pas les lignes existantes.
- [ ] `store(id, vector)` émet `UPDATE resolution_memory SET embedding = CAST(? AS vector) WHERE id = ?` ; no-op sur entrée nulle/vide.
- [ ] `searchSimilarQuestions` filtre **`user_id` ET `host_id`** dans le `WHERE` (isolation stricte) et borne par `topN` ; renvoie id + distance.
- [ ] `findByIdAndUserIdAndHostId` existe pour la défense en profondeur à la relecture.
- [ ] Tests d'isolation : la requête de recherche porte toujours `user_id` ET `host_id` ; entrées nulles → vide/no-op.
- [ ] Aucun branchement dans `AtelierChatService` ni dans `ResolutionMemoryStore.record/recall` (dormance totale — fait en SF-148-11).

---

## Périmètre

### Hors scope (explicite)

- Branchement sémantique dans `record`/`recall` → **SF-148-11**.
- Backfill des 293 lignes existantes → **SF-148-12**.
- Toute modification de `AtelierChatService:2068-2070` (injection dans le message) — **interdite**.
- Toute modification du préfixe système / du cache de prompt (F-134).

---

## Technique

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `resolution_memory` | `ALTER TABLE ADD COLUMN embedding vector(1536)` + index HNSW | Postgres only, additif, nullable |

### Migration Liquibase

- [x] Oui — `137-resolution-memory-embedding.xml` (prochain numéro libre après `136`, vérifié par `git fetch`).
- Patron exact de la `135-atelier-messages-embedding.xml` (3 changesets `dbms="postgresql"` : enable extension IF NOT EXISTS, add column, create index).

### Composants backend

- `ResolutionMemoryEmbeddingStore` — copie quasi-littérale d'`AtelierMessageEmbeddingStore` (SQL natif pgvector, jamais mappé JPA).
- `ResolutionMemoryRepository.findByIdAndUserIdAndHostId` — méthode dérivée.

---

## Plan de test

### Tests unitaires

- [ ] `ResolutionMemoryEmbeddingStore` — `store` émet le bon SQL (`UPDATE ... SET embedding = CAST(? AS vector) WHERE id = ?`) avec le littéral vecteur + id (JdbcTemplate mocké).
- [ ] `store` — id nul / vecteur nul / vecteur vide → aucune interaction JDBC.
- [ ] `searchSimilarQuestions` — le SQL contient `user_id = ?` ET `host_id = ?` ET `embedding IS NOT NULL` ET `LIMIT` ; args dans le bon ordre (user, host, vecteur, topN).
- [ ] `searchSimilarQuestions` — entrées nulles / `topN ≤ 0` → liste vide, aucune interaction JDBC.

### Isolation workspace / tenant

- [x] Applicable — la recherche filtre `user_id` ET `host_id` (vérifié sur le SQL). Préoccupation transversale « contexte tenant » cochée (voir section dédiée).
- La colonne vectorielle n'est pas testable en H2 (type `vector` absent) : même choix que `AtelierMessageEmbeddingStore` (non testé en intégration H2) ; validation au niveau du SQL émis via JdbcTemplate mocké.

---

## Préoccupation transversale — Contexte tenant

- **Déclenchée** : nouveau chemin de lecture de données par `(user_id, host_id)`.
- **Composants impactés** : `ResolutionMemoryEmbeddingStore.searchSimilarQuestions` (filtre `user_id` + `host_id` dans le `WHERE`), `ResolutionMemoryRepository.findByIdAndUserIdAndHostId` (re-filtre à la relecture). `store` écrit par id (ligne déjà isolée, aucune lecture croisée). Aucun autre composant ne résout le tenant ici ; la recherche reste systématiquement filtrée.

---

## Notes et décisions

- **Provider Independence** : réutilise `pgvector` + le patron `AtelierMessageEmbeddingStore` (SF-162-06). Aucune dépendance Anthropic.
- **Gateway-First** : aucune capacité IA réimplémentée ; le vecteur est fourni par un `EmbeddingProvider` abstrait (branché en SF-148-11), le store ne fait que ranger/chercher.
- **Dormance** : à l'issue de cette SF, rien n'appelle le store ; comportement du produit strictement inchangé.
