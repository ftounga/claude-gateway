# Mini-spec — [F-173 / SF-173-01] L'API du plan

## Identifiant

`F-173 / SF-173-01`

## Feature parente

`F-173` — La carte vivante (cadrage `CADRAGE-F-173-la-carte-vivante.md`, D1→D7 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-173-01-api-du-plan`

---

## Objectif

Servir à l'écran, **depuis l'index F-174 en base** (D1, jamais une lecture du poste), le plan de la carte d'un poste — ressources, liens, niveaux, fraîcheur, pièges, échéances, « à cartographier » — et la fiche d'une ressource.

---

## Comportement attendu

### Cas nominal

1. `GET /api/governance/hosts/{hostRef}/map/graph` lit les entités, relations, faits et sections indexés de **ce** poste (`user_id`, `host_id`) et rend le plan :
   - **Nœuds** : les entités sont **fusionnées par libellé normalisé** (une ressource citée dans 12 sections = 1 nœud). Une entité `MOTIF` (identifiant exact) dont l'identifiant figure parmi ceux d'une entité `MODELE` est fusionnée dans celle-ci. Une extrémité de relation sans entité devient un nœud de type `autre`. Identifiant de nœud **stable** : 16 premiers caractères hexadécimaux du SHA-256 du libellé normalisé (sûr dans une URL, inchangé par une ré-extraction).
   - **Type** du nœud : le plus fréquent parmi ses entités `MODELE`, sinon celui du motif. Domaine / environnement : premiers attributs connus. État : celui de l'entité la plus récemment constatée.
   - **Niveaux (D4)** : un nœud a pour **parent** l'autre extrémité d'une relation `dans` (A dans B → B parent de A) ou `heberge` (A heberge B → A parent de B). Un seul parent, jamais de cycle (le lien qui fermerait un cycle est ignoré). `depth` = 0 pour une racine.
   - **Faits d'un nœud** : faits des sections où il est cité dont le texte contient son libellé ou un de ses identifiants, plus les faits du poste qui portent un de ses identifiants exacts. Le nœud expose `facts` (nombre), `traps` (pièges parmi eux), `observedOn` (constat le plus récent), `stale` (constat plus vieux que `app.governance.map.fact-max-age-days`, F-139), `nextDue` (plus proche échéance), `toMap` (état `a_cartographier`), `children` (nombre d'enfants).
   - **Liens** : les relations entre nœuds (`source`, `target`, `nature`), dédoublonnées.
   - **Échéances** : faits `ECHEANCE` datés, triés, avec le nœud concerné et `overdue`.
   - **À cartographier** : nœuds d'état `a_cartographier` et faits dont le texte dit « à cartographier ».
   - **Fraîcheur** : `indexedAt` (dernière section indexée), `pendingSections` (sections en attente de lecture).
2. `GET /api/governance/hosts/{hostRef}/map/entities/{nodeId}` rend la **fiche** : le nœud, ses faits (**pièges en tête**, puis échéances, puis le reste, chacun avec fichier, section, ligne, date de constat, échéance, `stale`), ses relations (sens, nature, nœud d'en face cliquable) et ses sources (fichier + section).
3. Le plan est lu en base : il répond **poste hors ligne** (données datées par `indexedAt`).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Sans jeton | 401 |
| Compte sans accès à la Forge | 403 (garde `AtelierAccessService`, comme `GET …/map`) |
| Poste d'un autre compte ou inconnu | 404 (introuvable, jamais « interdit ») |
| Poste « Hébergé » ou index coupé (`APP_MAP_INDEX_ENABLED=false`) | 200, `indexed=false`, listes vides |
| Carte pas encore indexée | 200, `indexed=false`, `pendingSections` renseigné |
| Fiche d'un nœud inconnu de ce poste | 404 |

---

## Critères d'acceptation

- [x] Une ressource citée dans plusieurs sections n'apparaît qu'une fois ; un identifiant exact déjà porté par une ressource nommée n'en crée pas une seconde.
- [x] `A dans B` place A sous B ; un cycle `dans` ne boucle pas.
- [x] Un nœud porte ses pièges, son constat le plus récent, `stale` au-delà de l'âge de confiance, sa prochaine échéance.
- [x] La fiche met les pièges en tête et liste les relations des deux sens.
- [x] Isolation : un autre compte reçoit 404 sur le plan et la fiche ; aucune lecture sans `(user_id, host_id)`.
- [x] « Hébergé » : `indexed=false`, aucune erreur.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Nœuds rendus | ≤ 2 000 (les plus reliés / les plus riches en faits gardés ; `truncated=true`, `totalNodes` dit le vrai total) |
| Échéances rendues | ≤ 200 |
| « À cartographier » rendus | ≤ 200 |
| Faits d'une fiche | ≤ 80 (`totalFacts` dit le vrai total) |
| Texte d'un fait | tel qu'indexé (≤ 2 000 car., SF-174-02) |
| `nodeId` | 16 caractères `[0-9a-f]`, sinon 404 |

---

## Périmètre

### Hors scope (explicite)

- Tout écran (SF-173-02 → 07).
- Toute écriture (carte, index, poste) — lecture seule.
- Tout appel au modèle : le plan se calcule sur l'index existant.

---

## Technique

### Tables

Aucune nouvelle table, aucune migration. Lecture de `host_map_entities`, `host_map_relations`, `host_map_facts`, `host_map_sections` (migration 139, F-174).

### Contrat API (figé — consommé par SF-173-02 → 07)

`GET /api/governance/hosts/{hostRef}/map/graph` → 200

```json
{
  "indexed": true,
  "indexedAt": "2026-10-04T08:00:00Z",
  "pendingSections": 0,
  "factMaxAgeDays": 120,
  "totalNodes": 412,
  "truncated": false,
  "nodes": [{
    "id": "3f9a0c1d2e4b5a69", "label": "lzi-prod", "kind": "cluster",
    "parentId": "a1b2c3d4e5f60718", "depth": 1, "children": 0,
    "domain": "paiement", "environment": "prod", "state": "joignable",
    "identifiers": ["arn:aws:eks:eu-west-3:123456789012:cluster/lzi-prod"],
    "facts": 14, "traps": 2, "observedOn": "2026-09-30", "stale": false,
    "nextDue": "2026-11-02", "toMap": false
  }],
  "edges": [{ "id": "e0", "source": "3f9a0c1d2e4b5a69", "target": "a1b2c3d4e5f60718", "nature": "dans" }],
  "deadlines": [{ "nodeId": "…", "nodeLabel": "jeton GitLab", "text": "- le jeton expire le 2026-11-02",
                  "dueOn": "2026-11-02", "overdue": false, "path": "acces.md", "heading": "Jetons", "lineNo": 12 }],
  "toMap": [{ "nodeId": "…", "label": "registre Harbor", "text": "…", "path": "…", "heading": "…", "lineNo": 4 }]
}
```

`GET /api/governance/hosts/{hostRef}/map/entities/{nodeId}` → 200

```json
{
  "node": { "...": "même forme qu'un nœud du plan" },
  "totalFacts": 14,
  "facts": [{ "path": "acces.md", "heading": "Proxy", "lineNo": 7, "text": "…", "kind": "PIEGE",
              "observedOn": "2026-09-30", "dueOn": null, "stale": false }],
  "relations": [{ "nature": "dans", "direction": "out", "otherId": "…", "otherLabel": "compte prod" }],
  "sources": [{ "path": "acces.md", "heading": "Proxy" }]
}
```

Erreurs : 401 / 403 / 404 (corps d'erreur standard, sans trace).

### Composants backend

`governance/map/index/HostMapGraph` (service, calcul), `HostMapGraphController` (contrôleur mince, même garde que la consolidation SF-174-06). Lecture via les dépôts existants (`findByUserIdAndHostId…`).

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Contexte tenant | Oui | `HostMapGraphController` résout le poste par `GovernanceHostScope.require(userId, hostRef)` (404 hors compte) ; `HostMapGraph` ne lit que par `(user_id, host_id)` via `HostMapEntityRepository`, `HostMapRelationRepository`, `HostMapFactRepository`, `HostMapSectionRepository` |
| Auth | Non (garde existante réutilisée : JWT + `AtelierAccessService.requireAccess`) | — |
| Plans / limites, routing | Non | — |

---

## Plan de test

- [x] `HostMapGraphTest` (unitaire, dépôts simulés) : fusion par libellé et motif → modèle, parent `dans`/`heberge`, cycle ignoré, pièges / périmé / échéance d'un nœud, « à cartographier », borne des nœuds, fiche (pièges en tête, relations deux sens), nœud inconnu.
- [x] `HostMapGraphApiIntegrationTest` (H2 + Liquibase réel) : 401, 404 autre compte (plan et fiche), propriétaire (forme du plan + fiche), « Hébergé » → `indexed=false`, fiche inconnue → 404.
- [x] Suite complète verte.

## Dépendances

F-174 SF-174-02 (index) mergée.

## Notes et décisions

- **Arbitrage (réversible)** : identifiant de nœud = SHA-256 tronqué du libellé normalisé, plutôt que l'UUID d'une ligne d'entité — une ré-extraction change les UUID, le nom reste ; l'URL `?noeud=` reste valide.
- **Arbitrage (réversible)** : la hiérarchie des niveaux vient des seules relations `dans` / `heberge` dites par le texte ; sans elles, la ressource est à la racine (l'écran regroupe les racines par type au-delà d'un seuil, SF-173-02).
- **Arbitrage (réversible)** : les faits d'un nœud = correspondance libellé / identifiant dans ses sections + identifiants exacts sur tout le poste — pas de lecture du modèle, aucun coût.
