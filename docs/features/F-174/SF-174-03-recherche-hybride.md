# Mini-spec — [F-174 / SF-174-03] Le fait juste, retrouvé à coup sûr

## Identifiant

`F-174 / SF-174-03`

## Feature parente

`F-174` — La carte qui répond (cadrage `CADRAGE-F-174-la-carte-qui-repond.md`, D1→D10 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-174-03-recherche-hybride`

---

## Objectif

Joindre au message de chaque tour les faits de la carte qui répondent à la question par une recherche hybride sur l'index (identifiant exact > entité nommée > similarité sémantique pgvector > lexical), bornée à 20 faits / 6 000 caractères, avec repli intégral sur le rappel lexical F-137.

---

## Comportement attendu

### Cas nominal

1. `HostMapKnowledgeProvider.factsFor` : si l'index est allumé (D4) **et** porte au moins un fait pour le poste du tour, `HostMapSearch` cherche, puis `HostMapFactsBlock` compose le bloc (journal `strategy = HYBRID`). Sinon, ou si la recherche ne rend rien, ou sur toute erreur : **le bloc F-137 d'avant, à l'identique** (journal `LEXICAL`).
2. Maillons (D5), un fait retenu une fois n'est pas répété :
   - **identifiant exact** : identifiants reconnus dans la question (motifs SF-174-02) + termes distinctifs F-137, égalité exacte sur la colonne `identifiers` des faits ; au plus 8 faits par identifiant ;
   - **entité nommée** : entités de l'index dont le libellé (≥ 4 caractères, mot entier) ou un identifiant est cité ; leurs faits, sauf si le nom est porté par plus de 8 faits (il décrit alors la carte, pas la question) ;
   - **sémantique** : embedding de la question (fournisseur F-162 via `EmbeddingProvider`), 8 plus proches voisins pgvector filtrés `(user_id, host_id)`, gardés sous la distance cosine 0,50 ;
   - **lexical** : termes distinctifs F-137 sur le texte des faits indexés (même règle anti-bruit).
3. Bloc (D6) : même en-tête et même marque « à re-vérifier » que F-137/F-139 ; chaque fait cite `[fichier § section]` ; au plus `APP_MAP_INDEX_MAX_FACTS` (20) faits et `APP_MAP_INDEX_MAX_CHARS` (6 000) caractères ; un fait n'est jamais coupé, le bloc dit s'il en reste. Il rejoint la **consigne du tour**, jamais le bloc système (préfixe stable, F-171).
4. Embeddings des faits : le travailleur de l'index (SF-174-02) embedde au plus 200 faits sans vecteur par passe, si la clé d'embedding est présente (sinon rien, et le maillon sémantique est éteint).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Index éteint (`APP_MAP_INDEX_ENABLED=false`) ou vide pour ce poste | Bloc F-137 inchangé |
| Recherche sans résultat | Bloc F-137 (qui peut lui-même être vide) |
| Exception dans la recherche | Bloc F-137, jamais d'échec du tour |
| Fournisseur d'embeddings absent ou en panne | Maillon sémantique sauté, les trois autres servent |
| Base H2 (dev / tests) | Pas de colonne vectorielle : maillon sémantique éteint (pas de clé) |

---

## Critères d'acceptation

- [x] Ordre des maillons respecté ; aucun doublon.
- [x] Seuil sémantique appliqué ; bornes de faits et de caractères tenues sans couper un fait.
- [x] Repli intégral sur F-137 (index éteint, vide, sans résultat, en panne).
- [x] Toute lecture filtrée `(user_id, host_id)` du poste du tour.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Faits par tour | `APP_MAP_INDEX_MAX_FACTS`, défaut 20 |
| Caractères du bloc | `APP_MAP_INDEX_MAX_CHARS`, défaut 6 000 |
| Distance cosine max | `app.map-index.semantic-max-distance`, défaut 0,50 |
| Voisins sémantiques | `app.map-index.semantic-top-n`, défaut 8 |

---

## Périmètre

### Hors scope (explicite)

- Priorité des pièges et échéances (SF-174-04), outil `carte_chercher` (SF-174-05).
- Le sommaire de carte du bloc système (F-136) : inchangé.

---

## Technique

### Tables

- `host_map_facts.embedding vector(1536)` + index hnsw (migration `140`, PostgreSQL seulement, patron 135/137).

### Composants backend

- `governance/map/index/HostMapSearch`, `HostMapSemantic`, `HostMapFactEmbeddingStore` ; `governance/map/HostMapFactsBlock`.
- `HostMapKnowledgeProvider` (choix hybride / repli), `HostMapIndexService` (étape d'embeddings), `HostMapIndexProperties` (+ `semanticMaxDistance`, `semanticTopN`).

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Contexte tenant | Oui | `HostMapKnowledgeProvider` (poste résolu par `GovernanceHostScope`, inchangé) → `HostMapSearch` (toutes requêtes `(user_id, host_id)`) → `HostMapFactEmbeddingStore.searchSimilar` (SQL filtré `user_id` + `host_id`) |
| Auth, plans, routing | Non | — |

---

## Plan de test

- [x] `HostMapSearchTest` : ordre des maillons, seuil, borne, nom trop fréquent, mot entier, pas de lecture sans propriétaire.
- [x] `HostMapFactsBlockTest` : en-tête, source fichier § section, péremption, borne sans couper.
- [x] `HostMapKnowledgeProviderHybridTest` : hybride quand l'index existe (journal HYBRID) ; repli F-137 quand index vide, éteint, sans résultat, en erreur (journal LEXICAL).
- [x] Suite complète verte.

## Dépendances

SF-174-02 mergée.

## Notes et décisions

- **Arbitrage (réversible)** : seuil sémantique 0,50 (plus large que les 0,30 de F-148 qui comparent question à question) : ici on compare une question à une ligne de fait, plus courte et d'une autre forme ; réglable par env.
- **Arbitrage (réversible)** : le maillon lexical est rejoué sur les faits indexés (même règle), et le bloc F-137 d'origine reste le repli complet : rien de ce que l'agent recevait n'est perdu quand l'index ne trouve rien.
