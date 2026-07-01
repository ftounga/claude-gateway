# Mini-spec — [F-15 / SF-15-01] Fournisseur d'embeddings local (in-process)

> Source de vérité : `docs/PROJECT.md` (amendement 2026-07-01, ADR-011). Feature F-15 « Embeddings
> locaux » — voir `docs/PRODUCT_SPEC.md`.

---

## Identifiant

`F-15 / SF-15-01`

## Feature parente

`F-15` — Embeddings locaux (migration vers un modèle local, suppression de la dépendance à un
fournisseur d'embeddings externe / clé API)

## Statut

`ready`

## Date de création

2026-07-02

## Branche Git

`feat/SF-15-01-embeddings-local`

---

## Objectif

> En une phrase : fournir une implémentation **locale, in-process, sans réseau ni clé API** de
> `EmbeddingProvider`, activable par `app.rag.embedding.provider=local`, afin que le pipeline RAG
> (F-06/F-07) génère ses vecteurs **sans dépendre d'un fournisseur d'embeddings externe**.

---

## Comportement attendu

### Cas nominal

Lorsque `app.rag.embedding.provider=local`, le bean `LocalEmbeddingProvider` est le seul
`EmbeddingProvider` actif (mutuellement exclusif avec `stub` / `api` via `@ConditionalOnProperty`).
`IngestionService` (F-06) et `AskService` (F-07) l'utilisent via l'interface, **sans aucun changement
de code métier** (Provider Independence). Pour chaque texte :

1. Normalisation Unicode (minuscules) + tokenisation en mots (séparateurs non alphanumériques).
2. Extraction de features lexicales : mots entiers **+** n-grammes de caractères (3-grammes) des mots
   → capture la morphologie et rend robuste aux variantes.
3. Projection par **hashing trick signé** (FNV-1a 32-bit → index dans `[0, dimension)` + signe ±1)
   avec accumulation de la fréquence de terme (TF).
4. **Normalisation L2** du vecteur (norme = 1), longueur = dimension configurée
   (`app.rag.embedding.dimension`, défaut **1536** — OQ-01 conservée).

Résultat : un vecteur par texte, dans le même ordre que l'entrée. Deux textes partageant du
vocabulaire ont une **distance plus faible** que deux textes sans mot commun (sémantique lexicale
réelle, contrairement au `stub` qui n'a aucune proximité inter-textes).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Liste de textes vide | Retourne une liste vide (aucun appel, aucune erreur) | — |
| Texte `null` ou vide/blanc | Vecteur nul normalisé → **vecteur zéro** (dimension respectée), pas d'exception | — |
| Dimension configurée invalide (≤ 0) | Défaut 1536 appliqué par `EmbeddingProperties` (existant) | — |

> Le provider local n'appelle aucun réseau : il ne lève **jamais** `EmbeddingProviderUnavailableException`
> ni `EmbeddingProviderException`. Aucune clé, aucun secret n'est requis, lu ou journalisé.

---

## Critères d'acceptation

- [ ] `LocalEmbeddingProvider implements EmbeddingProvider`, actif **uniquement** si
  `app.rag.embedding.provider=local` (`@ConditionalOnProperty havingValue="local"`), sans
  `matchIfMissing` (n'altère pas le défaut `stub`).
- [ ] `embed()` retourne un vecteur par texte, dans l'ordre, chacun de longueur `dimension()`.
- [ ] `dimension()` reflète `app.rag.embedding.dimension` (défaut 1536) → compatible avec la colonne
  existante `chunks.embedding vector(1536)` **sans migration ni ré-indexation**.
- [ ] Déterminisme : deux appels sur le même texte produisent des vecteurs identiques.
- [ ] Similarité lexicale : deux textes partageant du vocabulaire sont plus proches (L2) que deux
  textes disjoints — testé.
- [ ] Vecteurs normalisés L2 (norme ≈ 1) pour un texte non vide ; vecteur zéro pour texte vide/blanc.
- [ ] Aucune dépendance réseau, aucune clé, aucun SDK fournisseur : le domaine ne dépend que de
  l'interface `EmbeddingProvider` (Provider Independence respectée).
- [ ] Aucun secret journalisé (le provider n'en manipule aucun).

---

## Périmètre

### Hors scope (explicite)

- **Bundling d'un runtime transformer ONNX (all-MiniLM réel, 384-dim)** : nécessiterait de lourdes
  dépendances natives + téléchargement de poids au runtime → **risque CI/build**. Décision d'arbitrage
  ci-dessous : l'implémentation locale livrée est un vectoriseur lexical in-process ; le backend
  all-MiniLM ONNX pourra être branché **plus tard sur la même interface** sans changement du domaine.
- **Passage de la dimension à 384** : imposerait une migration du type de colonne (`vector(384)`) +
  ré-indexation complète (OQ-01). Conservé à 1536 (défaut). Hors scope de cette SF (réversible).
- **UI** : F-15 est une feature d'infrastructure (swap de provider). Aucun écran utilisateur.
- **Changement du provider par défaut en production** : `stub` reste le défaut du code ; l'activation
  `local` se fait par configuration d'environnement (`APP_RAG_EMBEDDING_PROVIDER=local`).

---

## Technique

### Endpoint(s)

Aucun. Composant interne du pipeline RAG (pas d'API exposée).

### Tables impactées

Aucune. Réutilise `chunks.embedding vector(1536)` (migration `011`, Postgres) via l'abstraction
`EmbeddingStore` existante. Tests H2 verts (store no-op, colonne vectorielle non mappée).

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — dimension conservée à 1536, colonne existante réutilisée. (Un futur passage
  à 384 exigerait un changeset `dbms=postgresql` numéroté au-dessus de `031`, hors scope.)

### Composants Angular (si applicable)

Aucun (feature sans UI).

---

## Plan de test

### Tests unitaires (`LocalEmbeddingProviderTest`)

- [ ] `dimension()` = dimension configurée ; vecteurs de la bonne longueur (ex. 1536 et une petite
  dimension de test).
- [ ] Déterminisme : `embed(["x"])` deux fois → vecteurs égaux.
- [ ] Normalisation L2 ≈ 1 pour un texte non vide.
- [ ] Texte vide/blanc/`null` → vecteur zéro (longueur = dimension), pas d'exception.
- [ ] Liste vide → liste vide.
- [ ] Ordre préservé et taille du lot = taille de l'entrée.
- [ ] Similarité lexicale : `d(A, A')` (textes proches, mots communs) `<` `d(A, B)` (texte disjoint).

### Tests d'intégration

- [ ] Non applicable au sens endpoint (aucune API). Le pipeline d'ingestion existant
  (`IngestionIntegrationTest`, profil `test`) reste vert : le provider `local` n'est pas le défaut,
  donc le comportement `stub` des tests est inchangé (non-régression).

### Isolation utilisateur (`user_id`)

- [x] **Non applicable directement** — le provider ne lit ni n'écrit de données ; il transforme du
  texte en vecteur. L'isolation `user_id` reste garantie en amont/aval par `IngestionService` /
  `AskService` / `EmbeddingStore` (inchangés). Aucune régression d'isolation introduite.

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Analyse |
|--------------|-----------|---------|
| Auth / Principal | Non | Aucun changement d'auth, aucun endpoint. |
| Contexte tenant (`user_id`) | Non | Provider sans état, sans accès données ; isolation inchangée (composants amont/aval non modifiés : `IngestionService`, `AskService`, `EmbeddingStore`). |
| Plans / limites | Non | Aucun appel aux services de quota. |
| Navigation / routing | Non | Aucune route (pas d'UI). |

---

## Dépendances

### Subfeatures bloquantes

- `F-06 / SF-06-01` (interface `EmbeddingProvider`, propriétés `app.rag.embedding.*`) — **done**.

### Questions ouvertes impactées

- [x] **OQ-01 — Dimension d'embedding** : conservée à **1536** (défaut). Le modèle local natif 384
  reste un basculement futur réversible (`app.rag.embedding.dimension` + migration `vector(384)` +
  ré-indexation). Tracé, non bloquant (conformément à la consigne : défaut 1536).

---

## Notes et décisions (arbitrages)

- **Arbitrage A (réversible 🟠) — implémentation locale = vectoriseur lexical in-process** plutôt que
  runtime transformer ONNX (all-MiniLM). Motif : éviter de lourdes dépendances natives ML + un
  téléchargement de poids au runtime, sources de fragilité CI/build (casser `main` = échec dur). Le
  cœur de F-15 — « suppression de la dépendance fournisseur » (aucune clé, aucun réseau) — est atteint.
  Le backend all-MiniLM ONNX se branche ultérieurement sur la **même** interface `EmbeddingProvider`
  sans toucher au domaine (Provider Independence). Réversible.
- **Arbitrage B (réversible 🟠) — dimension conservée à 1536** (OQ-01) : réutilise la colonne pgvector
  existante → **zéro migration, zéro ré-indexation**, tests H2 verts. Passage à 384 = évolution future
  réversible.
- **Arbitrage C — feature sans UI** : F-15 est une bascule d'infrastructure ; aucun écran. La règle
  « backend mergé sans frontend planifié » ne s'applique pas (la feature n'a pas d'UI).
- Gateway-First respecté : un vectoriseur lexical de récupération n'est pas un moteur IA / clone de
  Claude ; les embeddings RAG sont déjà dans le périmètre (F-06, ADR-011). F-15 en fournit une variante
  locale.
