# Mini-spec — F-175 / SF-175-03 — Déjà demandé

## Identifiant
`F-175 / SF-175-03` — feature parente `F-175` *Le fil des attentes* — dépend de SF-175-01/02 (mergées).
Branche : `feat/SF-175-03-deja-demande`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Que `record_blocker` **reconnaisse** une attente déjà là sur le poste — même clé dans un autre
terminal, ou même demande dite autrement — et rende l'existante au lieu d'en créer une seconde.

## Comportement attendu

### Cas nominal (ordre de reconnaissance, D3)
1. **Même clé, ce terminal** (F-154, inchangé) → l'existante, quel que soit son état.
2. **Même clé, ouverte, autre terminal du poste** → l'existante (`KEY_ON_HOST`). Une clé **fermée**
   ailleurs ne bloque pas une nouvelle demande.
3. **Même sens** : la description est vectorisée par le fournisseur abstrait d'embeddings de F-162 /
   F-174 (`recallEmbeddingProvider`), comparée par pgvector aux attentes **ouvertes** du poste (ou du
   terminal hébergé) ; sous le seuil `app.atelier.actions.dedup-max-distance` (défaut **0,20**, soit
   similarité ≥ 0,80) → l'existante (`MEANING`). Les attentes du périmètre encore sans vecteur sont
   rattrapées (≤ 50, un seul appel) avant la comparaison.
4. Sinon : inscription, puis embedding **après validation** de la transaction, en tâche de fond
   (pool `atelierEmbeddingExecutor`). Une édition de la description re-vectorise.
5. **Réponse de l'outil** : dit comment l'attente a été reconnue (« DÉJÀ SUR LE POSTE », « UNE
   ATTENTE QUI DIT LA MÊME CHOSE »), son état (« DÉJÀ DEMANDÉ — demandé à Zahi le 30/09 par Teams »)
   et depuis quand elle attend (« En attente depuis 4 j »). L'agent ne redemande pas ; il peut
   proposer une relance.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Pas de clé d'embeddings (`APP_EMBEDDING_API_KEY`) | chemin sémantique éteint : clé seule juge |
| Fournisseur ou base vectorielle en panne | « aucun doublon » → on inscrit (ne jamais perdre une demande) |
| Embedding après inscription en échec | l'attente reste sans vecteur ; rattrapée à la prochaine comparaison |
| H2 (tests/dev) | colonne absente, chemin éteint (aucune requête vectorielle) |

## Critères d'acceptation
- [ ] Même clé ouverte ailleurs sur le poste → pas de seconde ligne, outil « DÉJÀ SUR LE POSTE ».
- [ ] Clé fermée ailleurs → nouvelle inscription.
- [ ] Même sens sous le seuil → pas de seconde ligne, outil « QUI DIT LA MÊME CHOSE ».
- [ ] Au-dessus du seuil, ou chemin éteint, ou panne → inscription normale.
- [ ] Recherche vectorielle limitée à `user_id` + poste (ou terminal hébergé) + attentes ouvertes.
- [ ] Migration 143 PostgreSQL seulement, additive.

## Plan de test
- **Unitaires** : `TerminalActionSemanticDedupTest` (éteint, seuil, rattrapage, panne, embedding) ;
  `TerminalActionToolTest` (clé ailleurs ouverte / fermée, sens, rien de proche → embedding).
- **Intégration PostgreSQL réel** : `TerminalActionEmbeddingStorePostgresTest` (Testcontainers
  pgvector) — migration 143, isolation compte + poste, ouvertes seulement, ordre par distance,
  rattrapage.
- Suite backend complète.

## Impacts
- `terminal_actions.embedding vector(1536)` (migration `143-terminal-actions-embedding.xml`,
  `dbms="postgresql"`), non mappée JPA.
- Backend : `TerminalActionEmbeddingStore`, `TerminalActionSemanticDedup` (nouveaux), service
  (`record`, `insert`, `edit`), exécuteur (messages).
- Config : `app.atelier.actions.dedup-max-distance` (défaut 0,20, réglable sans redéploiement de code).

### Préoccupations transversales
Contexte tenant : **oui** — nouvelle lecture vectorielle. Composants : `TerminalActionEmbeddingStore`
(`user_id` + `host_id`/`workspace_id` du terminal possédé dans chaque WHERE), service `record`
(résolution des correspondances via `findByIdAndUserId`). Auth, plans, navigation : non.

## Hors périmètre
La carte « Déjà demandé » dans le fil (SF-175-05) ; fusion manuelle de deux attentes.

## Arbitrages (réversibles)
- Seuil 0,20 (plus strict que le rappel F-148 à 0,30) : une fausse fusion cacherait une vraie demande.
- Embedding synchrone de la **requête** au moment d'inscrire (un appel court, comme F-148) ; celui de
  la **ligne** reste asynchrone.
- Pas d'index vectoriel : quelques dizaines de lignes par poste, filtrées avant la distance.
