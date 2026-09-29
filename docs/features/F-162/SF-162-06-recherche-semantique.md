# Mini-spec — F-162 / SF-162-06 — La recherche sémantique du `recall`

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage de référence (fait foi) : `docs/features/F-162/CADRAGE-F-162-rappel-a-la-demande.md`.
> Étend : `docs/features/F-162/SF-162-01-outil-recall.md` (l'outil `recall` mot-clé).

---

## Identifiant

`F-162 / SF-162-06`

## Feature parente

`F-162` — Le rappel à la demande de l'historique (se souvenir sans tout rejouer)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-162-06-recall-semantique`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Donner à l'outil `recall` (SF-162-01, aujourd'hui mot-clé/`LIKE`) une **recherche sémantique** (par le
sens) — embeddings des messages + similarité **pgvector cosine**, isolée `user_id` + `workspace_id` —
de sorte que « adressage réseau » retrouve un passage sur « VPC CIDR » sans le mot exact, **le mot-clé
restant le repli** quand le sémantique est indisponible (jamais d'échec).

---

## Comportement attendu

### Cas nominal

1. À l'**écriture** d'un message (`USER` ou `ASSISTANT`), la gateway calcule son embedding
   (`text-embedding-3-small`, 1536 dim) et le range dans `atelier_messages.embedding` — **de façon
   ASYNCHRONE et best-effort** : le tour n'attend jamais l'embedding, et un échec d'embedding ne casse
   ni le tour ni l'enregistrement du message.
2. Quand le modèle appelle `recall(query)` **et que le sémantique est actif** (clé présente + chemin
   PostgreSQL), la gateway embed la requête et exécute une recherche des plus proches voisins pgvector
   (`ORDER BY embedding <=> :q::vector`, cosine), **filtrée `workspace_id` ET `user_id`**, bornée à
   top-N (défaut 5). Les messages retrouvés sont relus (double filtre `workspace_id` + `user_id`),
   étiquetés « tour N » + date exactement comme SF-162-01, et relayés au modèle.
3. Un **job de backfill** (planifié, borné par lots) embed les messages existants sans embedding, pour
   que le sémantique ne soit pas limité aux nouveaux messages.

### Repli (le mot-clé reste le filet)

- Sémantique **désactivé** (clé absente, coupe-circuit `enabled=false`, ou chemin non-PostgreSQL) →
  `recall` exécute la recherche **mot-clé SF-162-01 inchangée**.
- Sémantique **actif mais en échec** (embedding requête KO, erreur SQL) → **repli mot-clé** dans le
  même appel (`try/catch`, jamais d'exception remontée au tour).
- Sémantique **actif mais 0 résultat** (ex. messages pas encore embeddés pendant le backfill) →
  **repli mot-clé** dans le même appel : un message non encore embeddé reste retrouvable par le mot.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `query` absent ou vide | Résultat d'outil en **erreur** (inchangé SF-162-01) | résultat `is_error` |
| Clé `APP_EMBEDDING_API_KEY` absente | Sémantique **auto-désactivé** ; repli mot-clé silencieux | — |
| Embedding requête en échec | **Repli mot-clé** dans le même appel ; aucune exception | — |
| Embedding à l'écriture en échec | **Best-effort** : message enregistré, tour intact, embedding simplement absent (backfill rattrapera) | — |
| Fil d'un autre utilisateur / workspace | **Jamais** remonté : filtre `user_id` + `workspace_id` dans le SQL vecteur ET dans la relecture JPA | — |

---

## Critères d'acceptation

- [ ] Une **interface `EmbeddingProvider`** existe déjà (`fr.claudegateway.rag.provider.EmbeddingProvider`,
      Provider Independence) et est **réutilisée** ; une **impl OpenAI dédiée** au recall
      (`text-embedding-3-small`, 1536 dim) lit **`APP_EMBEDDING_API_KEY`** via
      `app.atelier.recall.semantic.*`, sans jamais dépendre d'un SDK Anthropic.
- [ ] Une **migration Liquibase** ajoute `CREATE EXTENSION IF NOT EXISTS vector` (idempotent) + colonne
      `embedding vector(1536)` sur `atelier_messages` + index de similarité **cosine** (hnsw
      `vector_cosine_ops`) — **tout le DDL vector en `dbms="postgresql"`** ; H2 ne reçoit **rien** de
      `vector`.
- [ ] La colonne `vector` **n'est pas mappée en JPA** (l'entité `AtelierMessage` est inchangée) ; elle
      est manipulée par **SQL natif** (`CAST(? AS vector)` en écriture, `<=>` en recherche), exécuté
      **uniquement sur le chemin PostgreSQL**.
- [ ] L'embedding à l'écriture est **asynchrone** (exécuteur dédié) et **best-effort** (un échec ne lève
      pas, n'annule pas le message ni le tour) — test : embed jette → aucune exception.
- [ ] Un **backfill** planifié, **borné** (taille de lot + plafond par passe), best-effort, isolé,
      embed les messages sans embedding.
- [ ] `recall` sémantique est **filtré `user_id` ET `workspace_id`** dans le SQL vecteur **et** dans la
      relecture JPA (défense en profondeur) — **test d'isolation** : un utilisateur / workspace ne
      rappelle jamais les messages d'un autre, même en sémantique.
- [ ] Le **repli mot-clé** est garanti : sémantique désactivé / en échec / 0 résultat ⇒ recherche
      mot-clé SF-162-01 dans le même appel (tests des trois cas).
- [ ] **Coupe-circuit** `app.atelier.recall.semantic.enabled` + auto-désactivation si pas de clé.
- [ ] Réglages externalisés (modèle, dimension, top-N, taille de lot, plafond backfill) **sans rouvrir
      le piège des deux constructeurs** : record `@ConfigurationProperties` à **constructeur compact
      unique** (aucun second constructeur).
- [ ] Les tests passent en **H2** (le chemin vector ne tourne pas en H2 ; c'est le repli mot-clé,
      l'`EmbeddingProvider` mocké, la sélection sémantique/repli, l'isolation, le best-effort qui sont
      testés).

---

## Ce qui est testé en CI (H2) vs à valider en PROD (PostgreSQL)

> Rappel mémoire projet : « vert en test ≠ marche en prod ». On ne prétend PAS que le sémantique est
> prouvé par la CI.

**Testé en CI (H2, déterministe) :**
- Repli mot-clé SF-162-01 (inchangé, non-régression).
- `EmbeddingProvider` **mocké** : sélection sémantique vs repli (sémantique actif→utilise les ids ;
  désactivé / erreur / 0 résultat→mot-clé).
- **Isolation** du chemin de relecture (`findByWorkspaceIdAndUserIdAndIdIn`) sur H2 réel.
- **Best-effort** de l'embedding à l'écriture (embed jette → pas d'exception, message enregistré).
- Gating de configuration (`isConfigured()` faux sans clé ; coupe-circuit `enabled`).
- Câblage de l'appel embed à l'écriture (le service demande l'embedding du message sauvegardé).

**À VALIDER EN PROD (PostgreSQL + pgvector, hors CI H2) :**
- La **vraie requête pgvector** (`<=>` cosine, index hnsw) et l'écriture `CAST(? AS vector)` — la
  colonne `vector` n'existe pas en H2, donc `AtelierMessageEmbeddingStore` (SQL natif) n'est **pas**
  exercé en CI.
- La migration `135` jouée sur la base PostgreSQL de prod (extension + colonne + index).
- Le rendement sémantique réel (« adressage réseau » → « VPC CIDR ») et le drainage du backfill.

---

## Périmètre

### Hors scope (explicite)

- **Aucune UI nouvelle** (la visibilité `recall` est SF-162-03, déjà livrée ; le sémantique réutilise
  le même chemin d'affichage). Backend seul.
- **Pas de rappel inter-projets** (autres fils) ; **pas** de « recharger tout l'ancien contexte ».
- **Pas de re-scoring / re-ranking IA** : `recall` reste recherche + relais, aucun moteur IA maison.
- **Pas de réutilisation de la config RAG** (`app.rag.embedding.*`) : le recall a son propre
  coupe-circuit et sa propre clé (`APP_EMBEDDING_API_KEY`) pour être activable indépendamment. Seule
  l'**interface** `EmbeddingProvider` est réutilisée (pas de duplication d'abstraction).

---

## Valeurs initiales / réglages

| Réglage | Propriété | Défaut | Note |
|---------|-----------|--------|------|
| Coupe-circuit | `app.atelier.recall.semantic.enabled` | `true` | Effectif seulement si la clé est présente |
| Clé API | `app.atelier.recall.semantic.api-key` | `${APP_EMBEDDING_API_KEY:}` | Vide ⇒ sémantique dormant |
| Base URL | `app.atelier.recall.semantic.base-url` | `https://api.openai.com/v1` | Format OpenAI `/embeddings` |
| Modèle | `app.atelier.recall.semantic.model` | `text-embedding-3-small` | 1536 dim, cheap |
| Dimension | `app.atelier.recall.semantic.dimension` | `1536` | = `vector(1536)` de la migration |
| Top-N | `app.atelier.recall.semantic.top-n` | `5` | aligné sur `RECALL_MAX_EXTRACTS` |
| Contenu embeddé max | (constante) | `8000` car. | borne d'entrée d'un embedding |
| Backfill : taille de lot | `app.atelier.recall.semantic.backfill.batch-size` | `50` | un appel embed groupé par lot |
| Backfill : plafond / passe | `app.atelier.recall.semantic.backfill.max-per-run` | `500` | borne dure par tick planifié |
| Backfill : activé | `app.atelier.recall.semantic.backfill.enabled` | `true` (prod), `false` (tests) | worker planifié |
| Backfill : intervalle | `app.atelier.recall.semantic.backfill.interval` | `PT30S` | fixed-delay |

> **Décision (piège des deux constructeurs)** : `RecallSemanticProperties` est un record
> `@ConfigurationProperties` à **constructeur compact unique** (valeurs par défaut dans le corps
> compact, comme `ImageGenerationProperties` / `EmbeddingProperties`). **Aucun second constructeur** —
> le démarrage Spring reste sain.

---

## Contraintes de validation

| Champ | Obligatoire | Règle | Normalisation |
|-------|-------------|-------|---------------|
| `query` | Oui | non vide (inchangé SF-162-01) | `trim` |
| `api-key` | Non | vide ⇒ dormant | — |
| contenu embeddé | — | tronqué à 8000 car. avant l'appel | flatten espaces |

---

## Technique

### Endpoint(s)

Aucun endpoint HTTP nouveau. L'outil vit dans la boucle d'agent ; le backfill est un worker planifié.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_messages` | ALTER (PG only) | + colonne `embedding vector(1536)` + index cosine hnsw |
| `atelier_messages` | UPDATE natif (PG only) | `SET embedding = CAST(? AS vector)` (embed à l'écriture + backfill) |
| `atelier_messages` | SELECT natif (PG only) | recherche `<=>` cosine, filtrée `workspace_id` + `user_id` |
| `atelier_messages` | SELECT JPA | relecture par ids, filtrée `workspace_id` + `user_id` |

### Migration Liquibase

- [x] Applicable — **`135-atelier-messages-embedding.xml`**. Trois changesets `dbms="postgresql"` :
      `CREATE EXTENSION IF NOT EXISTS vector` ; `ALTER TABLE atelier_messages ADD COLUMN embedding
      vector(1536)` ; `CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)`. **H2 : rien**
      (aucun changeset applicable → `MARK_RAN`). Rollback : `DROP COLUMN embedding` (l'extension est
      partagée, on ne la retire pas).

### Composants (nouveaux)

- `atelier/recall/RecallSemanticProperties.java` — record `@ConfigurationProperties("app.atelier.recall.semantic")`.
- `atelier/recall/OpenAiRecallEmbeddingProvider.java` — impl de `rag.provider.EmbeddingProvider` (OpenAI HTTP, patron `ApiEmbeddingProvider`/`OpenAiImageProvider`).
- `atelier/recall/AtelierMessageEmbeddingStore.java` — `JdbcTemplate` PG natif (store / searchSimilar / findUnembeddedBatch), patron `PgVectorEmbeddingStore`.
- `atelier/recall/AtelierSemanticRecall.java` — interface + `NONE` (repli total, comme `ResolutionMemory.NONE`).
- `atelier/recall/AtelierSemanticRecallService.java` — impl `@Component` : `isEnabled`, `embedAsync` (exécuteur dédié), `search`.
- `atelier/recall/AtelierSemanticRecallConfig.java` — `@Configuration @EnableConfigurationProperties` + exécuteur `atelierEmbeddingExecutor` (patron `RepoIndexRefreshConfig`).
- `atelier/recall/AtelierEmbeddingBackfillWorker.java` — worker `@Scheduled` borné, `@ConditionalOnProperty(app.atelier.recall.semantic.backfill.enabled)`.

### Points de câblage

- `AtelierMessageRepository` : + `findByWorkspaceIdAndUserIdAndIdIn(workspaceId, userId, ids)` (relecture isolée).
- `AtelierChatService` : + champ `semanticRecall = AtelierSemanticRecall.NONE` injecté par **setter
  `@Autowired(required=false)`** (patron `setResolutionMemory` — aucun constructeur touché) ; hooks
  `embedAsync` après les 3 `messageRepository.save(...)` (user, assistant, précisions) ; branche
  sémantique-puis-repli dans `recall(...)`.

### Préoccupations transversales

| Préoccupation | Impact | Composants vérifiés |
|--------------|--------|---------------------|
| **Contexte tenant** (isolation) | Nouveau chemin de lecture (vecteur) | `AtelierMessageEmbeddingStore.searchSimilar` (filtre `user_id`+`workspace_id` dans le SQL) ; `AtelierMessageRepository.findByWorkspaceIdAndUserIdAndIdIn` (re-filtre) ; `AtelierSemanticRecallService.search` (passe le `workspace.getId()` + `userId` du tour) ; `recall(...)` inchangé côté repère de tour. **Aucune** autre lecture de `atelier_messages` modifiée. |

---

## Plan de test

### Tests unitaires (service, mockés)

- [ ] Sémantique actif : `recall` appelle `semanticRecall.search(userId, workspaceId, query, N)`, relit
      par ids (isolé), relaie les extraits avec « tour N ».
- [ ] Repli quand sémantique désactivé (`isEnabled()==false`) ⇒ recherche mot-clé (`searchByContent`).
- [ ] Repli quand sémantique renvoie **0** ⇒ mot-clé dans le même appel.
- [ ] Repli quand `search` **jette** ⇒ mot-clé, aucune exception au tour.
- [ ] Embed à l'écriture : après un tour, `semanticRecall.embedAsync` est appelé pour le message
      utilisateur et pour la réponse assistant (ArgumentCaptor sur ids + contenu).
- [ ] Best-effort : `embedAsync` qui jette n'interrompt pas le tour (NONE + mock qui throw).

### Tests unitaires (composants)

- [ ] `RecallSemanticProperties` : défauts, `isConfigured()` (faux sans clé, vrai avec clé + enabled).
- [ ] `AtelierSemanticRecallService` : `isEnabled` selon la config ; `embedAsync` best-effort (provider
      jette → pas d'exception, store non appelé) ; `search` mappe les ids du store ; désactivé → vide.
- [ ] `OpenAiRecallEmbeddingProvider` : non configuré ⇒ `EmbeddingProviderUnavailableException`,
      **aucun** appel réseau ; `dimension()` = config.

### Tests d'intégration / data-layer (H2 réel)

- [ ] `findByWorkspaceIdAndUserIdAndIdIn` : isolation user + workspace (Alice/ws1, Alice/ws2, Bob/ws1) ;
      ids d'un autre tenant jamais renvoyés.

### Isolation workspace / utilisateur

- [x] Applicable — couverte par le data-layer ci-dessus **et** le test service (le `search` reçoit
      exactement `workspace.getId()` + `userId` du tour). Le SQL vecteur (filtre `user_id`+`workspace_id`)
      est **à valider en prod** (pas de pgvector en H2), et documenté comme tel.

---

## Dépendances

### Subfeatures bloquantes

- SF-162-01 (l'outil `recall` mot-clé) — **Livrée**. SF-162-06 l'étend sans la casser.

### Questions ouvertes impactées

- OQ RAG (embeddings/pgvector) : sans objet ici — ADR-011 a réactivé pgvector ; réutilisation de
  l'infra d'embeddings existante (`rag.provider`).

---

## Notes et décisions

- **Réutilisation vérifiée (mémoire projet « vérifier l'existant »)** : le projet possède déjà
  `fr.claudegateway.rag.provider.EmbeddingProvider` (+ impl OpenAI `ApiEmbeddingProvider`), le patron
  `PgVectorEmbeddingStore` et la migration `002-pgvector`. On **réutilise l'interface** et on **mirroir
  les patrons** ; on n'introduit **pas** une seconde abstraction. On crée une impl et une config
  **dédiées au recall** (clé + coupe-circuit propres) parce que le RAG documentaire et le rappel de
  conversation doivent pouvoir s'activer indépendamment.
- **Gateway-First / Provider-First** : `recall` = recherche + relais sur nos données ; embeddings via
  provider abstrait ; aucun moteur IA maison, aucune dépendance directe Anthropic.
- **Traitements lourds asynchrones** (CLAUDE.md) : embedding à l'écriture asynchrone ; backfill
  planifié et borné.
- **Piège des deux constructeurs** : record à constructeur compact unique ; injection dans
  `AtelierChatService` par setter `@Autowired(required=false)` (aucun constructeur touché).
- **Sécurité** : la clé vient exclusivement de l'environnement, jamais journalisée ; les logs d'échec
  ne portent que le modèle (patron STT/image/embeddings existant).
