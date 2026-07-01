# Mini-spec — [F-06 / SF-06-02] Worker d'auto-indexation asynchrone

## Identifiant

`F-06 / SF-06-02`

## Feature parente

`F-06` — Ingestion RAG

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-06-02-ingestion-worker`

---

## Objectif

> Déclencher automatiquement l'ingestion RAG des documents `EXTRACTED` via un worker planifié intra-backend, hors du thread HTTP (traitement lourd asynchrone).

---

## Comportement attendu

### Cas nominal

1. `IngestionService.ingestPending()` : sélectionne les documents au statut `EXTRACTED`, et pour chacun :
   - le « réclame » en passant à `INDEXING` (persisté) pour rendre l'état visible et éviter une reprise pendant le traitement ;
   - appelle `ingest(document)` (SF-06-01) → `INDEXED` (ou `FAILED` en cas d'échec fournisseur) ;
   - un échec sur un document n'interrompt pas le lot (capturé, document suivant traité).
   - retourne le nombre de documents passés à `INDEXED` ce cycle.
2. `IngestionWorker` (`@Scheduled`, intra-backend) déclenche périodiquement `ingestPending()`. Il ne porte aucune logique métier ; toute exception inattendue est capturée pour ne jamais arrêter le planificateur.
3. Désactivable par configuration (`app.rag.ingestion.enabled=false`, cas des tests).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Fournisseur d'embeddings en échec pour un document | document `FAILED` (message neutre), le lot continue |
| Aucun document `EXTRACTED` | cycle sans effet, retourne 0 |
| Exception inattendue dans un cycle | capturée par le worker, planificateur non interrompu, message neutre |

---

## Critères d'acceptation

- [ ] `ingestPending()` indexe tous les documents `EXTRACTED` (chacun réclamé en `INDEXING` puis `INDEXED`), retourne le compte indexé.
- [ ] Un échec sur un document (`FAILED`) n'empêche pas l'indexation des autres du même cycle.
- [ ] Le worker s'exécute hors thread HTTP (`@Scheduled`), délègue à `IngestionService`, capture toute exception.
- [ ] Le worker est désactivable par config ; désactivé en tests (déterminisme).
- [ ] Aucun secret ni contenu journalisé.
- [ ] `mvn -pl backend test` vert.

---

## Périmètre

### Hors scope (explicite)

- La mécanique d'ingestion elle-même (chunking/embeddings/persistance) — livrée en SF-06-01.
- Un worker dédié + file (SQS) — V2 (OQ-10, réversible ; l'abstraction par état en base permet l'extraction sans réécriture du domaine).
- Reprise automatique des documents bloqués en `INDEXING` (crash mid-cycle) — risque résiduel V1, reprise manuelle ; automatisation ultérieure.

---

## Préoccupations transversales

- **Plans / limites** : non impactées (l'ingestion ne consomme pas le quota de tokens de chat F-10 ; aucun appel aux services de quota ajouté).
- **Auth / tenant** : le worker n'a pas de contexte HTTP ; l'isolation `user_id` est portée par les données (`ingest` filtre déjà sur `document.user_id`). Composants impactés : `IngestionService` (déjà isolé), aucun endpoint.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `documents` | SELECT (`status=EXTRACTED`) + UPDATE (`INDEXING`/`INDEXED`/`FAILED`) | via SF-06-01 |
| `chunks` | INSERT/DELETE | via SF-06-01 |

### Migration Liquibase

- [x] Non applicable (aucun changement de schéma).

### Composants Java

- `IngestionService.ingestPending()` (nouvelle méthode de sélection/boucle, robuste par document).
- `IngestionWorker` (`@Scheduled`, `@ConditionalOnProperty app.rag.ingestion.enabled`).
- Config `app.rag.ingestion.{enabled,interval}` (application.yml + désactivé en application-test.yml).

---

## Plan de test

### Tests unitaires

- [ ] `IngestionPendingTest` (mock repo/provider/store) — indexe tous les `EXTRACTED` (claim `INDEXING` → `INDEXED`), retourne le compte ; un échec fournisseur (`FAILED`) n'interrompt pas le lot ; aucun `EXTRACTED` → 0.
- [ ] `IngestionWorkerTest` — le worker délègue à `ingestPending()` ; une exception du service est capturée (planificateur non interrompu).

### Tests d'intégration

- [ ] Réutilise la couverture d'intégration SF-06-01 (H2, stub/noop). Pas de nouveau flux HTTP.

### Isolation utilisateur

- [x] Couverte par SF-06-01 (`ingest` isolé `user_id`). Le worker n'introduit aucun accès non filtré.

---

## Dépendances

### Subfeatures bloquantes

- `SF-06-01` — Done (fournit `IngestionService.ingest`).

### Questions ouvertes impactées

- [x] OQ-10 (worker intégré vs séparé) — **worker intra-backend `@Scheduled`** retenu (réversible), cohérent avec `OcrPollingWorker` (F-05).

---

## Notes et décisions

- Le worker réutilise le pattern éprouvé de `OcrPollingWorker` (F-05 / SF-05-02) : `@Scheduled(fixedDelay)`, désactivable, robuste aux exceptions.
- `fixedDelay` (et non `fixedRate`) : pas d'exécution concurrente, cohérent avec un traitement potentiellement long.
