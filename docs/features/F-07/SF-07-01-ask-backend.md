# Mini-spec — [F-07 / SF-07-01] Q&A documentaire — endpoint `POST /ask` (backend)

## Identifiant

`F-07 / SF-07-01`

## Feature parente

`F-07` — Q&A documentaire (ask)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-07-01-ask-backend`

---

## Objectif

> En une phrase : répondre à une question ancrée sur les documents indexés de l'utilisateur en
> combinant recherche vectorielle top-K (pgvector) et relais cité vers Claude, avec repli gracieux
> si aucun contenu indexé n'est pertinent.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur authentifié envoie `POST /api/ask` avec `{ "question": "...", "model"?: "...", "topK"?: n }`.
2. Le service vérifie **le quota AVANT tout appel fournisseur** (`QuotaService.assertWithinQuota`) — un
   utilisateur à quota atteint reçoit `402 quota_exceeded` sans embedding ni appel Claude.
3. Le service valide le modèle demandé (liste blanche `ModelCatalog`, défaut sinon).
4. La question est vectorisée via l'abstraction `EmbeddingProvider.embed([question])`
   (Provider Independence — jamais un SDK direct).
5. Recherche des top-K chunks les plus proches, **isolée `user_id`**, via l'abstraction
   `EmbeddingStore.search(userId, queryVector, topK)` :
   - `PgVectorEmbeddingStore` (Postgres) : `ORDER BY embedding <-> CAST(? AS vector) LIMIT ?`
     avec `WHERE user_id = ?`.
   - `NoopEmbeddingStore` (dev/tests/H2, pas de colonne vector) : retourne une liste vide → déclenche
     le repli.
6. Les chunks correspondants sont rechargés **filtrés `user_id`** (défense en profondeur), ré-ordonnés
   par distance ; leurs documents sont chargés **filtrés `user_id`** pour la citation
   `[filename:page:chunkIndex]`.
7. Construction d'un prompt cité (contexte numéroté + consigne de citer ses sources) transmis à Claude
   via `AIProvider.complete` (jamais Anthropic en direct).
8. La consommation de tokens est enregistrée (`QuotaService.recordUsage`).
9. Réponse `200` : `{ answer, model, grounded: true, citations: [...] }`.

### Cas de repli (fallback si non indexé)

Si la recherche ne renvoie aucun chunk (aucun document indexé / store no-op / aucune correspondance) :
Claude est tout de même interrogé **sans contexte documentaire**, la réponse est marquée
`grounded: false` et `citations: []`. Décision par défaut réversible (voir Notes).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `question` absente/vide | `validation_error` | 400 |
| `question` trop longue (> 8000) | `validation_error` | 400 |
| Modèle hors liste blanche | `validation_error` | 400 |
| Non authentifié | rejet | 401 |
| Quota atteint | `quota_exceeded` (aucun appel fournisseur) | 402 |
| Fournisseur d'embeddings non configuré | `provider_unavailable` | 503 |
| Fournisseur d'embeddings en échec | `provider_error` | 502 |
| Fournisseur IA non configuré | `provider_unavailable` | 503 |
| Fournisseur IA en échec | `provider_error` | 502 |

---

## Contrat API (FIGÉ — importé tel quel par SF-07-02 frontend)

### `POST /api/ask` — Auth requise (JWT)

**Request body** (`application/json`) :
```json
{
  "question": "Quelles sont les obligations de confidentialité du contrat ?",
  "model": "claude-opus-4-8",
  "topK": 5
}
```
- `question` : **obligatoire**, non vide (après trim), max 8000 caractères.
- `model` : optionnel (null → modèle par défaut) ; doit être dans la liste blanche sinon 400.
- `topK` : optionnel (null → défaut `app.rag.ask.top-k` = 5) ; borné `[1, 20]` par le service.

**Response `200`** :
```json
{
  "answer": "…réponse de Claude, citant [contrat.pdf:3:2]…",
  "model": "claude-opus-4-8",
  "grounded": true,
  "citations": [
    {
      "documentId": "a1b2…",
      "filename": "contrat.pdf",
      "page": 3,
      "chunkIndex": 2,
      "snippet": "…extrait de 240 caractères max…"
    }
  ]
}
```
- `grounded` : `true` si au moins un chunk a servi de contexte, `false` en repli.
- `page` : `number | null` (le n° de page n'est pas dérivé en F-06 → souvent `null`).
- `citations` : `[]` en repli.

**Erreurs** : enveloppe standard `{ "error": "<code>", "message": "<fr>" }`
(`validation_error` 400, `quota_exceeded` 402, `provider_unavailable` 503, `provider_error` 502).

---

## Critères d'acceptation

- [ ] `POST /api/ask` avec question valide et chunks indexés → `200`, `grounded=true`, `citations` non vide,
      réponse Claude obtenue via `AIProvider`.
- [ ] Le prompt transmis à Claude contient les marqueurs de citation `[filename:page:chunkIndex]`.
- [ ] Sans aucun chunk pertinent → `200`, `grounded=false`, `citations=[]` (repli, pas d'erreur).
- [ ] Question vide → `400 validation_error` ; modèle hors liste blanche → `400 validation_error`.
- [ ] Quota atteint → `402 quota_exceeded`, **aucun** appel embedding ni Claude.
- [ ] Non authentifié → `401`.
- [ ] Isolation : la recherche et le chargement des chunks/documents filtrent sur `user_id` ; un
      utilisateur ne peut jamais citer le document d'un autre (test dédié).
- [ ] La consommation de tokens de l'appel est enregistrée via `QuotaService.recordUsage`.
- [ ] Aucun secret/clé ni contenu brut fournisseur journalisé ; erreurs neutres.
- [ ] DDL vectoriel de recherche isolé `dbms=postgresql` ; tests H2 verts (store no-op).

---

## Périmètre

### Hors scope (explicite)

- Pas de persistance de la Q&A en conversation/historique (`/ask` est **sans état** en V1).
- Pas de filtrage par `documentId` unique (recherche sur tout le corpus indexé de l'utilisateur).
- Pas de re-ranking avancé ni de seuil de distance (top-K brut).
- Pas de streaming de la réponse.
- Écran Angular → SF-07-02.

---

## Valeurs initiales

Aucune entité créée. `/ask` est en lecture (recherche) + relais ; il n'écrit que le compteur d'usage
(`usage_counters`, via `QuotaService`, comportement F-10 inchangé).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-------------|----------------------------|---------------|
| question | Oui | 8000 | non vide après trim | trim() |
| model | Non | 64 | liste blanche `ModelCatalog` | — |
| topK | Non | — | entier borné `[1,20]` (défaut 5) | clamp service |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/ask` | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| chunks | SELECT (recherche vectorielle + rechargement, isolation `user_id`) | Postgres : `<->` pgvector ; H2 : no-op |
| documents | SELECT (filename/page pour citation, isolation `user_id`) | — |
| usage_counters | UPDATE (via `QuotaService.recordUsage`) | comportement F-10 |

### Migration Liquibase

- [x] Non applicable — aucune table/colonne nouvelle. La colonne `chunks.embedding vector(1536)` et
  l'index ivfflat existent déjà (migration `011`). La recherche pgvector n'est que du SQL de lecture,
  confinée à `PgVectorEmbeddingStore` (Postgres). Aucune nouvelle migration requise.

### Composants / classes

- `ask/AskController` (POST /ask) — pas de logique métier.
- `ask/AskService` — orchestration : quota → embedding → recherche → chargement isolé → prompt cité →
  `AIProvider` → usage.
- `ask/dto/AskRequest`, `ask/dto/AskResponse`, `ask/dto/CitationResponse`.
- `ask/AskProperties` (`app.rag.ask.top-k`, `app.rag.ask.snippet-max-chars`) + `ask/AskConfig`.
- Extension `rag/store/EmbeddingStore` : `List<ScoredChunk> search(UUID userId, float[] q, int topK)`
  (record `rag/store/ScoredChunk`) ; impl `PgVectorEmbeddingStore` (SQL natif) + `NoopEmbeddingStore` (vide).
- `rag/ChunkRepository` : `findByIdInAndUserId(Collection<UUID>, UUID)`.
- `shared/error/GlobalExceptionHandler` : ajout des handlers `EmbeddingProviderUnavailableException`
  (503) et `EmbeddingProviderException` (502) — transversal, réutilise les codes existants.

### Préoccupation transversale — Plans / limites (composants impactés)

`/ask` consomme des tokens → soumis au **même gate que `/chat`** :
- `QuotaService.assertWithinQuota(userId)` (avant appel) et `QuotaService.recordUsage(...)` (après).
- Aucun nouveau plan/quota introduit ; réutilise `EntitlementService`/`app.quota` inchangés.
- Composants vérifiés : `ChatService` (référence), `QuotaService`, `EntitlementService` — comportement
  inchangé, aucun nouveau gate.

---

## Plan de test

### Tests unitaires (`AskServiceTest`, Mockito — H2-safe)

- [ ] Nominal : chunks trouvés → `grounded=true`, citations construites, prompt contient
      `[filename:...:chunkIndex]`, `recordUsage` appelé.
- [ ] Repli : `EmbeddingStore.search` renvoie vide → `grounded=false`, `citations=[]`, Claude appelé
      sans contexte, `recordUsage` appelé.
- [ ] Quota atteint : `assertWithinQuota` lève → aucune interaction embedding/AIProvider.
- [ ] Modèle hors liste blanche → `UnsupportedModelException`.
- [ ] Isolation : `EmbeddingStore.search` et `ChunkRepository.findByIdInAndUserId` appelés avec le
      `user_id` courant ; documents chargés filtrés `user_id`.
- [ ] `topK` borné (`0`/négatif/`>20` → clampé).

### Tests unitaires store (`PgVectorEmbeddingStoreTest`, JdbcTemplate mocké)

- [ ] `search` émet la requête paramétrée attendue (littéral vecteur, `user_id`, `LIMIT topK`) et mappe
      les lignes en `ScoredChunk` ordonnés.

### Tests d'intégration (`AskApiIntegrationTest`, `@SpringBootTest` + H2, store no-op, `StubEmbeddingProvider`)

- [ ] `POST /api/ask` question valide → `200` (repli `grounded=false` en H2 puisque store no-op) —
      valide bout-en-bout la validation, l'auth, le relais et l'enveloppe de réponse.
- [ ] `POST /api/ask` question vide → `400 validation_error`.
- [ ] Non authentifié → `401`.
- [ ] Isolation : les chunks/documents d'un autre utilisateur ne sont jamais retournés/cités
      (préparés en base, requête d'Alice ne voit pas ceux de Bob).

### Isolation utilisateur

- [x] Applicable — recherche + rechargement chunks + chargement documents filtrés `user_id`.

---

## Dépendances

### Subfeatures bloquantes

- `F-06` (chunks + embeddings + pgvector) — **done** (migration `011`, `EmbeddingProvider`,
  `EmbeddingStore`).

### Questions ouvertes impactées

- [x] OQ-01 (dimension 1536) — tranchée (F-06), réutilisée.
- [x] OQ-03 (IVFFlat lists=100) — tranchée (F-06) ; réévaluation rappel/latence notée mais **non
      bloquante** pour F-07.

---

## Notes et décisions

- **Repli ungrounded (réversible)** : en l'absence de chunk pertinent, on répond via Claude sans
  contexte (`grounded=false`) plutôt que d'échouer. Alternative (message figé « aucun document
  indexé ») réversible par config/logique. Choix orienté valeur + transparence.
- **`/ask` sans état (réversible)** : pas de persistance en conversation en V1 pour un périmètre net.
  Rattachement à une conversation = évolution ultérieure.
- **Recherche corpus complet (réversible)** : pas de filtre `documentId` en V1 ; ajout d'un scope
  document = évolution simple (paramètre optionnel + `AND document_id = ?`).
- **Distance L2** cohérente avec l'index `vector_l2_ops` de la migration `011`.
</content>
