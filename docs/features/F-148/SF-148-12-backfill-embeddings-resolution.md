# Mini-spec — F-148 / SF-148-12 — Backfill des embeddings de la mémoire de résolutions

## Identifiant

`F-148 / SF-148-12`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-10-01

## Branche Git

`feat/SF-148-12-backfill-embeddings-resolution`

---

## Objectif

> En une phrase : embeddre les résolutions **déjà enregistrées** (≈293 lignes `resolution_memory` sans vecteur) par un worker planifié, borné et best-effort, pour que le rappel sémantique (SF-148-11) voie aussi le stock antérieur — sinon il ne verrait que les résolutions écrites après activation.

---

## Comportement attendu

### Cas nominal

- Un worker planifié (patron `AtelierEmbeddingBackfillWorker`/SF-162-06) appelle périodiquement `runOnce()`.
- `runOnce()` draine les résolutions **sans embedding** (`embedding IS NULL`, `question` non vide), par lots de `batch-size`, plafonnées à `max-per-run` par passe ; embed la **question** en un appel groupé et range chaque vecteur par id.
- **Éteint sans clé** (`isConfigured()` faux) : `runOnce()` renvoie 0, aucun appel réseau.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Pas de `APP_EMBEDDING_API_KEY` | `runOnce()` → 0, aucune interaction |
| Échec d'embedding d'un lot | La passe s'arrête (rien perdu, reprise au tick suivant), jamais d'exception propagée |
| Lecture d'un lot en échec | La passe s'arrête proprement |
| Plus aucune ligne sans vecteur | La passe s'arrête (rien à faire) |

---

## Critères d'acceptation

- [ ] `ResolutionMemoryEmbeddingStore.findUnembeddedBatch(limit)` : `SELECT id, question ... WHERE embedding IS NULL AND question IS NOT NULL AND question <> '' ORDER BY created_at DESC LIMIT ?`.
- [ ] `ResolutionMemoryEmbeddingBackfillService.runOnce()` : borné (`max-per-run`), par lots (`batch-size`), range chaque vecteur ; 0 sans clé ; s'arrête sur échec de lot sans lever.
- [ ] `ResolutionMemoryEmbeddingBackfillWorker` : `@Scheduled` + `@ConditionalOnProperty(app.atelier.recall.semantic.backfill.enabled, matchIfMissing=true)`, délègue à `runOnce()`, n'interrompt jamais le planificateur.
- [ ] Dormance : sans clé, le backfill ne touche rien (zéro appel réseau, aucune écriture).
- [ ] Aucune migration ; aucun endpoint ; aucun composant cluster (Postgres + pool existants).

---

## Périmètre

### Hors scope (explicite)

- Toute modification de `AtelierChatService` / du préfixe / du cache F-134.
- Le chemin de rappel (SF-148-11) et le socle (SF-148-10) — déjà livrés.

---

## Technique

### Composants backend

- `ResolutionMemoryEmbeddingStore.findUnembeddedBatch(limit)` + record `UnembeddedResolution(id, question)`.
- `ResolutionMemoryEmbeddingBackfillService` (patron `AtelierEmbeddingBackfillService`).
- `ResolutionMemoryEmbeddingBackfillWorker` (patron `AtelierEmbeddingBackfillWorker`).

### Configuration

- Réutilise `app.atelier.recall.semantic.backfill.*` (`enabled`, `batch-size`, `max-per-run`, `interval`) de `RecallSemanticProperties` — même cadence que le backfill des messages.

### Migration Liquibase

- [x] Non applicable.

---

## Plan de test

### Tests unitaires

- [ ] `ResolutionMemoryEmbeddingStoreTest` — `findUnembeddedBatch` : SQL (`embedding IS NULL`, `question <> ''`, `LIMIT`), limit ≤ 0 → vide sans requête.
- [ ] `ResolutionMemoryEmbeddingBackfillServiceTest` (patron SF-162) — éteint sans clé ; draine par lots borné ; respecte `max-per-run` ; s'arrête sur échec sans lever.

### Isolation workspace / tenant

- [x] Non applicable au **calcul** du backfill : embeddre une résolution sur SA propre ligne (par id) ne croise aucun tenant ; c'est la **recherche** (SF-148-10/11) qui filtre `(user_id, host_id)`, pas l'indexation. Même raisonnement que `AtelierEmbeddingBackfillService` (SF-162-06).

---

## Préoccupation transversale — Contexte tenant

- **Déclenchée** : écriture de vecteurs sur des lignes existantes.
- **Composants impactés** : `ResolutionMemoryEmbeddingStore.findUnembeddedBatch` (balayage global, écriture par id sur la ligne elle-même — aucune lecture croisée de tenant) ; `store(id, vector)` (UPDATE par id). La recherche reste la seule à filtrer `(user_id, host_id)` (SF-148-10/11, inchangée). Aucun autre composant concerné.

---

## Notes et décisions

- **Gateway-First / Provider Independence** : réutilise `recallEmbeddingProvider` abstrait ; aucune capacité IA réimplémentée.
- **Dormance sûre** : sans clé, worker inerte (comme le backfill des messages).
- **Async** : calcul lourd hors thread HTTP (worker planifié) — règle « traitements lourds asynchrones ».
