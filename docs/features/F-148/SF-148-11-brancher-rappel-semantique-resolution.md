# Mini-spec — F-148 / SF-148-11 — Brancher le rappel sémantique de la mémoire de résolutions

## Identifiant

`F-148 / SF-148-11`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-10-01

## Branche Git

`feat/SF-148-11-brancher-rappel-semantique`

---

## Objectif

> En une phrase : apparier `resolution_memory` par **similarité sémantique de la question** (embeddings pgvector, SF-148-10) au lieu du seul Jaccard lexical, avec **seuil de distance exigeant** et **repli Jaccard** si le sémantique est éteint, en échec, ou sans résultat au-dessus du seuil — sans toucher au chemin du tour ni au préfixe.

---

## Comportement attendu

### Cas nominal

- **À l'enregistrement** (`ResolutionMemoryStore.record`) : après la sauvegarde de la résolution, si le sémantique est configuré (`isConfigured()`), planifier l'embedding de la **question** (pas la conclusion — on apparie par la question) sur l'`atelierEmbeddingExecutor` + `recallEmbeddingProvider` existants, **best-effort async**, **après commit** (sinon l'UPDATE ne verrait pas encore la ligne). Hors chemin critique.
- **Au rappel** (`ResolutionMemoryStore.recall`) : si `isConfigured()`, embed la question entrante → `searchSimilarQuestions(userId, hostId, vec, topN)` → prendre la meilleure dont la **distance cosine ≤ seuil exigeant** → re-filtrer par `(id, user_id, host_id)` (défense en profondeur) → formater le bloc habituel (encadré « indice à VÉRIFIER »). Borné à **1 item** (préserve la forme du message existant).
- **Repli** : sémantique éteint / en échec / aucun résultat au-dessus du seuil → **Jaccard inchangé** (code SF-148-08 intact).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Pas de `APP_EMBEDDING_API_KEY` (`isConfigured()` faux) | Comportement **strictement identique** à aujourd'hui (Jaccard), aucun appel réseau |
| Appel d'embedding en échec (réseau, provider) | Repli Jaccard silencieux, le tour n'échoue jamais |
| Résultat sémantique mais distance > seuil | Repli Jaccard (anti-faux-positif) |
| Embedding à l'enregistrement en échec | La ligne reste sans vecteur ; le backfill (SF-148-12) rattrapera ; le `record` n'échoue pas |

---

## Critères d'acceptation

- [ ] `AtelierChatService:2068-2070` (injection du rappel dans le MESSAGE) **inchangé** (vérifié par diff).
- [ ] Préfixe système / cache F-134 **non touchés** (le rappel reste dans le message, borné à 1 item, conclusion bornée 1500 ch. — déjà le cas).
- [ ] Sémantique ON : une **paraphrase** (que Jaccard raterait, < seuil de tokens partagés) est appariée via l'embedding.
- [ ] Sémantique OFF : le rappel est **identique à l'octet** au Jaccard d'avant (zéro régression) — prouvé par les tests existants inchangés.
- [ ] Seuil de distance **exigeant** : une distance au-dessus du seuil ne déclenche **pas** de rappel sémantique (repli Jaccard).
- [ ] Isolation : la recherche sémantique porte `(user_id, host_id)` et la relecture re-filtre par `(id, user_id, host_id)`.
- [ ] L'enregistrement planifie l'embedding de la **question** (pas la conclusion), best-effort, après commit.
- [ ] Aucune nouvelle migration ; aucun nouvel endpoint ; aucun composant cluster.

---

## Périmètre

### Hors scope (explicite)

- Backfill des 293 lignes existantes → **SF-148-12**.
- Toute modification de `AtelierChatService` / du préfixe / du cache F-134.
- Changement de la signature publique de `ResolutionMemory` / `ResolutionMemoryStore.recall/record` (interne uniquement).

---

## Technique

### Composants backend

- **`ResolutionSemanticRecall`** (nouveau service, patron `AtelierSemanticRecallService`) : `isEnabled()`, `embedQuestionAsync(id, question)`, `searchSimilarQuestions(userId, hostId, question, topN)` → `List<ScoredResolution>`, `maxDistance()`. Deps : `@Qualifier("recallEmbeddingProvider") EmbeddingProvider`, `ResolutionMemoryEmbeddingStore`, `RecallSemanticProperties`, `@Qualifier("atelierEmbeddingExecutor") Executor`, `@Value` seuil.
- **`ResolutionMemoryStore`** : `record` planifie l'embedding (après commit) ; `recall` tente le sémantique puis retombe sur le Jaccard existant (refactoré en `recallLexical`).

### Seuil de distance

- `app.atelier.recall.semantic.resolution-max-distance`, **défaut `0.30`** (distance cosine ; ≈ similarité cosine 0.70). Exigeant : rejette le vaguement lié, garde les paraphrases proches. **Arbitrage** (flag) : valeur par défaut choisie par prudence (silencieux > bruyant), réglable par env sans redéploiement de code.

### Migration Liquibase

- [x] Non applicable (colonne/index déjà posés en SF-148-10).

---

## Plan de test

### Tests unitaires (`ResolutionMemoryStoreTest` étendu + nouveau)

- [ ] Sémantique ON + distance ≤ seuil → rappel issu de l'embedding (paraphrase appariée, Jaccard aurait raté).
- [ ] Sémantique ON + distance > seuil → repli Jaccard.
- [ ] Sémantique ON + aucun résultat → repli Jaccard.
- [ ] Sémantique OFF → Jaccard identique (tests SF-148-08 existants, mock `isEnabled()=false`).
- [ ] `record` + sémantique ON → `embedQuestionAsync` planifié avec l'id sauvegardé + la **question** bornée.
- [ ] `record` + sémantique OFF → aucun embedding planifié.
- [ ] Isolation : `searchSimilarQuestions(userId, hostId, …)` ; relecture `findByIdAndUserIdAndHostId(id, userId, hostId)`.

### `ResolutionSemanticRecallTest` (nouveau)

- [ ] `isEnabled()` suit `isConfigured()`.
- [ ] `searchSimilarQuestions` éteint / query vide → liste vide, aucun appel provider.
- [ ] Échec provider → liste vide (repli).
- [ ] `embedQuestionAsync` éteint / entrées vides → no-op.

### Isolation workspace / tenant

- [x] Applicable — préoccupation transversale « contexte tenant » cochée (voir section).

---

## Préoccupation transversale — Contexte tenant

- **Déclenchée** : le rappel résout désormais une résolution via un second chemin (sémantique).
- **Composants impactés** :
  - `ResolutionMemoryStore.recall` — passe `(user_id, host_id)` au sémantique et re-filtre la relecture par `(id, user_id, host_id)`.
  - `ResolutionSemanticRecall.searchSimilarQuestions` — délègue au store SQL isolé `(user_id, host_id)`.
  - `ResolutionMemoryEmbeddingStore.searchSimilarQuestions` (SF-148-10) — `WHERE user_id = ? AND host_id = ?`.
  - `ResolutionMemoryProvider` (inchangé) — impose déjà `host_id` (cible SANDBOX exclue).
  - Le chemin Jaccard (`findTop100ByUserIdAndHostId...`) reste filtré `(user_id, host_id)`.
- Aucun autre composant ne résout le tenant pour ce rappel.

---

## Notes et décisions

- **Gateway-First / Provider Independence** : l'embedding vient d'un `EmbeddingProvider` abstrait (`recallEmbeddingProvider`) ; aucune capacité IA réimplémentée, aucune dépendance Anthropic.
- **Règle PO (justesse)** : améliore le rappel ; conserve l'encadrement « indice à VÉRIFIER, peut être périmé » ; seuil exigeant + repli silencieux.
- **Dormance sûre** : sans clé, chemin et comportement strictement identiques à SF-148-08.
