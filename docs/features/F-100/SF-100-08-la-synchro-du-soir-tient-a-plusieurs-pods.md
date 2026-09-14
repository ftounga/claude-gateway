# Mini-spec — [F-100 / SF-100-08] La synchro du soir tient à plusieurs pods

> Source de cadrage : `docs/features/RELIQUATS-2026-09-14.md` §A (cadrage validé par le PO le 2026-09-14).
> Ne rediscute pas le cadrage : cette mini-spec le décline.

---

## Identifiant

`F-100 / SF-100-08`

## Feature parente

`F-100` — Le Radar : la synchro du soir (déjà **Terminée** ; cette sous-feature durcit un risque résiduel).

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-100-08-multi-pods`

---

## Objectif

Prouver sur PostgreSQL réel que le verrou « une synchro à la fois par poste » tient sous deux planificateurs
concurrents, et donner une règle aux lots d'une synchro **annulée** : analysés s'ils sont complets, écartés
sinon, la couverture le disant.

---

## Comportement attendu

### Cas nominal (inchangé)

1. **Le verrou.** Le démarrage d'une synchro prend le poste par mise à jour conditionnelle
   (`RadarHostSettingsRepository.claim`, `WHERE running_sync_id IS NULL`). Deux démarrages concurrents,
   même poste, tous pods confondus : un seul gagne, l'autre lève `RadarSyncRunningException` et sa synchro
   créée est annulée par le rollback de sa transaction. **Aucun changement de ce comportement.**

2. **Les lots d'une synchro annulée.** Aujourd'hui, un lot déjà déposé dans la file d'analyse (F-101) avant
   l'annulation y **reste sans règle** et se fait analyser quand même. Désormais :
   - un lot dont l'**analyse est déjà complète** (`DONE`) au moment de l'annulation est **conservé** tel quel
     (ses faits sont écrits, « ce qui avait été lu est conservé ») ;
   - un lot dont l'analyse **n'est pas complète** (`PENDING`, `DEFERRED`, `PROCESSING`, `FAILED`) est
     **écarté** : statut terminal `DISCARDED`, texte brut effacé, plus jamais repris, **aucune réserve ni
     aucun jeton dépensé** pour une synchro que l'utilisateur a abandonnée.
   - La **couverture** de la synchro le dit : `GET …/syncs` → `analysis.batches.DISCARDED` compte ces lots.

### Écart : deux moments, une seule règle

- **À l'annulation** (`RadarSyncControlService.cancel`) : balayage immédiat des lots non terminaux de la
  synchro → `DISCARDED` (déterministe, et le brut d'une synchro abandonnée part tout de suite — cohérent avec
  la doctrine « des extraits, pas des archives »).
- **Au fil de l'analyse** (`RadarAnalysisQueue.process`) : filet pour la course « un lot est déposé juste
  après le balayage » — avant d'analyser un lot, si sa synchro est `CANCELLED` et que le lot n'est pas `DONE`,
  il est écarté au lieu d'être analysé. Sans cela, un dépôt validé dans la fenêtre entre la lecture du statut
  par l'intake et le commit de l'intake resterait orphelin (exactement le genre de course qu'un test
  PostgreSQL concurrent peut révéler).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Deux planificateurs lancent le même poste en même temps | Une seule synchro `RUNNING` ; l'autre repart sans rien créer |
| Lot déposé pour une synchro déjà annulée (intake) | Refusé (`RadarStateConflictException`) — **inchangé** |
| Lot `PENDING`/`DEFERRED`/`PROCESSING`/`FAILED` d'une synchro annulée | Écarté → `DISCARDED`, brut effacé |
| Lot `DONE` d'une synchro annulée | Conservé tel quel |
| Docker absent (test PostgreSQL) | Le test est **ignoré** (`@Testcontainers(disabledWithoutDocker = true)`), la suite reste verte |

---

## Critères d'acceptation

- [ ] Un test d'intégration sur **PostgreSQL réel** (Testcontainers) lance **deux planificateurs concurrents**
      sur le même poste et vérifie qu'il n'y a **jamais deux synchros `RUNNING`** du même poste.
- [ ] Le même test vérifie que le verrou tient : une seule ligne `radar_host_settings.running_sync_id` posée,
      une seule synchro non close.
- [ ] À l'annulation d'une synchro, ses lots non terminaux (`PENDING`, `DEFERRED`, `PROCESSING`, `FAILED`)
      passent `DISCARDED`, brut effacé ; ses lots `DONE` sont inchangés.
- [ ] Le worker n'analyse jamais un lot dont la synchro est `CANCELLED` : il l'écarte (`DISCARDED`), sans
      appeler l'analyseur, sans consommer de réserve ni de jetons.
- [ ] `GET …/syncs` expose `analysis.batches.DISCARDED` (la couverture le dit).
- [ ] Isolation : le balayage d'annulation et l'écart ne touchent que les lots du couple `user_id` + `host_id`
      de la synchro annulée.
- [ ] Le comportement nominal (synchro qui réussit, lots analysés `DONE`) est **inchangé** : suite F-100 /
      F-101 verte.

---

## Périmètre

### Hors scope (explicite)

- Toute modification du protocole runner, de l'écran, du contrat d'entrée F-101.
- Toute modification du chemin nominal de la collecte ou de l'analyse.
- La reprise/retente d'une synchro annulée (une synchro annulée reste annulée).

---

## Valeurs initiales

| Champ | Valeur | Règle |
|-------|--------|-------|
| `RadarAnalysisBatchStatus.DISCARDED` | nouveau statut terminal | Posé par l'annulation ou par le filet du worker ; jamais repris, jamais expiré, brut effacé |

---

## Contraintes de validation

Aucune nouvelle entrée utilisateur. Le statut est stocké en `varchar(16)` (`DISCARDED` = 9 caractères) :
**pas de migration** — le nouveau libellé tient dans la colonne existante, sur H2 comme sur PostgreSQL.

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. `GET /api/radar/hosts/{hostId}/syncs` gagne la clé `DISCARDED` dans `analysis.batches`
(la carte porte déjà tous les statuts, à 0 au besoin).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_analysis_batches` | UPDATE | `status = DISCARDED`, `payload = NULL` pour les lots écartés |
| `radar_host_settings` | (lecture concurrente) | vérifié par le test PostgreSQL, aucun changement de schéma |

### Migration Liquibase

- [x] Non applicable — nouvel énuméré stocké en chaîne, colonne `varchar(16)` inchangée.

### Composants Angular

Aucun. F-100 a déjà ses écrans (SF-100-06/07, résumé F-102). Le compteur `DISCARDED` transite par la carte
`analysis.batches` existante, que le frontend n'énumère pas par statut : **aucun impact frontend**.

---

## Plan de test

### Tests unitaires / intégration (H2, suite courante)

- [ ] `RadarSyncControlService.cancel` écarte les lots non terminaux (`PENDING`/`DEFERRED`/`PROCESSING`/
      `FAILED`) → `DISCARDED`, brut effacé ; conserve `DONE`.
- [ ] `RadarAnalysisQueue` : un lot dont la synchro est `CANCELLED` est écarté sans appel à l'analyseur, sans
      jeton ; le nominal (synchro `RUNNING`) est analysé comme avant.
- [ ] Isolation : l'écart ne touche pas les lots d'un autre poste / d'une autre synchro.

### Test d'intégration PostgreSQL réel (Testcontainers, ignoré sans Docker)

- [ ] Deux `RadarSyncPlanner.runOnce()` concurrents sur le même poste → une seule synchro `RUNNING`.
- [ ] Deux `RadarSyncLauncher.start()` concurrents sur le même poste → un seul succès, un
      `RadarSyncRunningException`.

### Isolation utilisateur

- [x] Applicable — l'écart et le balayage portent `user_id` + `host_id` ; couvert par un test dédié.

---

## Dépendances

### Subfeatures bloquantes

- `SF-100-02` (planification, verrou) — done.
- `SF-100-04` (couverture, annulation) — done.
- `SF-101-01` (file d'analyse) — done.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non touché).

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|---------------|-------------|---------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non (aucun nouveau moyen de résoudre le tenant ; `user_id`+`host_id` déjà portés partout) | — |
| Plans / limites | Non (l'écart **évite** une dépense de réserve, ne crée aucun gate) | — |
| Navigation / routing | Non | — |

---

## Notes et décisions

- **« Complet » = analyse complète (`DONE`).** Chaque lot déposé est déjà normalisé et auto-suffisant ; la
  seule complétude qui distingue « garder » de « écarter » pour une synchro annulée est l'état d'avancement de
  son **analyse**. Analyser les lots restants d'une synchro que l'utilisateur a annulée contredit
  l'annulation (dépense de réserve sur des données abandonnées) : c'est le « sans règle » que le cadrage
  corrige.
- **Deux mécanismes, une règle** (balayage à l'annulation + filet au worker) pour couvrir la course de dépôt
  concurrent — précisément le risque que la mini-spec doit prouver maîtrisé sur PostgreSQL réel.
- **Testcontainers** ajouté au `pom.xml` (portée `test`, versions gérées par le BOM Spring Boot). Image
  `pgvector/pgvector:pg16` (et non `postgres` nu) car la migration `002-pgvector` exige `CREATE EXTENSION
  vector`. `@Testcontainers(disabledWithoutDocker = true)` : sans démon Docker, le test est ignoré, jamais en
  échec.
