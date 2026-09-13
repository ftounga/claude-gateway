# Mini-spec — [F-101 / SF-101-01] La file d'analyse

---

## Identifiant

`F-101 / SF-101-01`

## Feature parente

`F-101` — Le Radar : la lecture des échanges
(cadrage validé : `docs/features/F-99/CADRAGE-le-radar.md`, §4.6, §5, §7, §12 bis)

## Statut

`done` — livrée le 2026-09-13 (PR #503)

## Date de création

2026-09-13

## Branche Git

`feat/SF-101-01-file-d-analyse`

---

## Objectif

Recevoir les **lots d'échanges** remontés par la collecte (F-100), les ranger dans une **file
d'analyse** idempotente, les faire traiter **en tâche de fond** un poste à la fois, et **supprimer le
texte brut** dès l'analyse réussie — au plus tard 7 jours après réception.

---

## Contexte

Le cadrage §5 sépare **la collecte** (runner, F-100) de **l'analyse** (gateway, F-101, portable
fermé). F-100 est livrée **en parallèle** et sa mini-spec n'existe pas encore sur `main` : cette
mini-spec **fige le contrat d'entrée** (ci-dessous) que F-100 appellera. L'analyse proprement dite
(tri SF-101-02, extraction SF-101-03/04, réserve SF-101-05) se branche sur la file par une interface ;
SF-101-01 livre la file sans analyseur, donc **inerte** : aucun lot n'est pris tant qu'aucun
analyseur n'est déclaré (SF-101-03).

---

## Contrat d'entrée (figé ici pour F-100)

### Appel

```java
RadarAnalysisIntake.submit(RadarScope scope, UUID syncId, RadarExchangeBatch batch)
    -> IntakeReceipt(UUID batchId, RadarAnalysisBatchStatus status, boolean duplicate)
```

- `scope` : le poste dont le runner a collecté (résolu côté F-100 par la session runner, jamais
  par un identifiant venu du lot) ;
- `syncId` : la synchro ouverte par `RadarRegistry.startSync(scope)` (F-99), du **même périmètre**.

### Forme du lot (JSON du cadre runner = sérialisation Jackson des records)

```json
{
  "batchKey": "teams:19:abc@thread.v2:1726252800000-1726256400000",
  "exchanges": [
    {
      "source": "TEAMS_MESSAGE",
      "conversationRef": "19:abc@thread.v2",
      "title": "Chantier MFA prestataires",
      "deepLink": "https://teams.microsoft.com/l/chat/19:abc@thread.v2",
      "messages": [
        {
          "sourceRef": "19:abc@thread.v2/1726253000123",
          "occurredAt": "2026-09-13T18:03:20.123Z",
          "authorKey": "marc.durand@client.fr",
          "authorName": "Marc Durand",
          "authorTitle": "RSSI",
          "fromMe": false,
          "text": "Tu peux me mettre en relation avec l'équipe Okta ? Jeudi idéalement.",
          "deepLink": "https://teams.microsoft.com/l/message/19:abc@thread.v2/1726253000123"
        }
      ]
    }
  ]
}
```

| Champ | Obligatoire | Borne | Règle |
|-------|-------------|-------|-------|
| `batchKey` | Oui | 128 | **clé d'idempotence** du lot dans le poste ; F-100 la dérive de ce qu'il a lu (fil + fenêtre) |
| `exchanges` | Oui | 1 → 50 | un échange = les messages **nouveaux** d'une conversation, d'une réunion ou d'un enregistrement |
| `exchange.source` | Oui | — | `TEAMS_MESSAGE`, `TEAMS_MEETING`, `LOCAL_RECORDING` (les preuves `USER_NOTE` / `PASTED_MAIL` n'entrent pas par la synchro, F-104) |
| `exchange.conversationRef` | Oui | 512 | identifiant stable du fil / de la réunion |
| `exchange.title` | Non | 200 | tronqué au-delà |
| `exchange.deepLink` | Non | 2048 | ignoré au-delà |
| `exchange.messages` | Oui | 1 → 200 | ordre chronologique |
| `message.sourceRef` | Oui | 512 | **identifiant stable dans la source** (devient `radar_evidence.source_ref`) ; unique dans le lot |
| `message.occurredAt` | Oui | — | ISO-8601 avec fuseau ; à la seconde pour une réunion |
| `message.authorKey` | Non | 320 | identité de source (adresse, identifiant Teams) ; absent = auteur inconnu |
| `message.authorName` | Non | 200 | |
| `message.authorTitle` | Non | 200 | fonction, si Teams la fournit |
| `message.fromMe` | Oui | — | vrai si l'utilisateur du poste l'a écrit (sert « à faire par moi ») |
| `message.text` | Oui | 8 000 | **tronqué** au-delà, jamais refusé |
| `message.deepLink` | Non | 2048 | lien au message, ou à la seconde de réunion |
| total du lot | — | 500 messages, 400 000 caractères de texte | au-delà : refus, F-100 découpe |

### Issues

| Situation | Issue |
|-----------|-------|
| Lot nouveau et valide | ligne `PENDING`, `duplicate=false` |
| Même `batchKey` déjà `PENDING`, `PROCESSING`, `DEFERRED` ou `DONE` | la ligne existante, `duplicate=true`, **rien n'est réécrit** |
| Même `batchKey` en `FAILED` ou `EXPIRED` | **remis en file** avec le nouveau texte (`PENDING`, tentatives à zéro), `duplicate=false` |
| Synchro inconnue ou d'un autre poste | `RadarNotFoundException` |
| Synchro annulée (`CANCELLED`) | `RadarStateConflictException` : un lot d'une synchro annulée n'est jamais analysé |
| Lot hors bornes (vide, trop gros, champ obligatoire absent, `sourceRef` en double) | `InvalidRadarInputException`, rien n'est écrit |

---

## Comportement attendu

### Cas nominal

1. **Réception** (`RadarAnalysisIntake.submit`) : validation du contrat, normalisation (trim,
   troncatures), écriture d'une ligne `radar_analysis_batches` : `status=PENDING`, `payload` = le lot
   normalisé en JSON, `expires_at = received_at + 7 jours`, compteurs d'échanges et de messages.
2. **Le travailleur** (`RadarAnalysisWorker`, `@Scheduled`, toutes les 15 s par défaut) appelle
   `RadarAnalysisQueue.runOnce(now)` :
   - **aucun analyseur déclaré** (`RadarBatchAnalyzer` absent) → rien n'est pris ;
   - sinon, pour au plus 4 postes ayant un lot prenable (`PENDING` ou `DEFERRED` échu, ou
     `PROCESSING` abandonné depuis plus que la durée du bail), il prend le **bail du poste**
     (`radar_analysis_leases`, une ligne par poste, prise par mise à jour conditionnelle) : **un seul
     traitement par poste à la fois, tous pods confondus** — l'ordre des lots compte (un sujet créé par
     le lot 1 doit être connu du lot 2) ;
   - traite jusqu'à 10 lots du poste **dans l'ordre de réception** : prise conditionnelle du lot
     (`PROCESSING`, tentative +1), lecture du texte, appel de l'analyseur **hors transaction** ;
   - applique l'issue **dans une seule transaction** :
     - `DONE` : les écritures de l'analyseur au registre, **puis `payload = NULL`**,
       `raw_deleted_at`, `analyzed_at`, compteurs → **le brut disparaît avec l'écriture des faits,
       ou pas du tout** ;
     - `RETRY` : `PENDING` avec échéance (1 min, 5 min, 30 min) ; à la 3ᵉ tentative → `FAILED` ;
       le brut reste (reprise possible jusqu'à l'expiration) ;
     - `DEFER` : `DEFERRED` jusqu'à l'échéance donnée par l'analyseur (réserve, droit), sans compter
       de tentative ;
   - si les écritures de l'analyseur échouent (registre qui refuse), rien n'est écrit et le lot
     passe en `RETRY` (code `WRITE_REJECTED`) ;
   - la consommation rapportée par l'analyseur (6 natures de jetons) est ajoutée au lot **et** à
     `radar_syncs.consumed_tokens` de sa synchro, quelle que soit l'issue.
3. **L'expiration** (`RadarAnalysisQueue.expireRaw(now)`, toutes les heures) : tout lot dont
   `expires_at` est passé perd son texte ; s'il n'était pas `DONE`, il passe `EXPIRED`.
4. **La purge** du Radar d'un poste ou d'un compte (SF-99-05) efface aussi ses lots et son bail.
5. **Lecture** : `GET /api/radar/hosts/{hostId}/syncs` gagne, par synchro, `analysis` : nombre de
   lots par statut, échanges et messages reçus, jetons consommés par l'analyse. **Jamais le texte.**
6. `finishSync` (F-99) ne réécrit plus la consommation à la baisse : elle garde le plus grand de
   l'existant et de la valeur passée (l'analyse continue après la fin de la collecte).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Lot invalide (contrat) | `InvalidRadarInputException`, rien n'est écrit | — (interne, F-100) |
| Synchro d'un autre poste / inconnue | `RadarNotFoundException` | — (interne) |
| Synchro annulée | `RadarStateConflictException` | — (interne) |
| Texte stocké illisible (JSON corrompu) | lot `FAILED` (code `CORRUPT_PAYLOAD`), brut effacé | — |
| Analyseur qui lève une exception | `RETRY` (code `ANALYZER_ERROR`) | — |
| Bail du poste tenu par un autre pod | le poste est sauté à ce passage | — |
| `GET /syncs` sur le poste d'autrui | `not_found` | 404 |

---

## Critères d'acceptation

- [ ] Migration `089-radar-analysis-queue.xml` : `radar_analysis_batches` et `radar_analysis_leases`,
      `user_id` + `host_id` non nuls, PostgreSQL et H2, rollback ; `ddl-auto: validate` passe.
- [ ] Deux `submit` du même `batchKey` → une ligne, le second `duplicate=true` ; un lot `FAILED`
      re-soumis repart `PENDING`.
- [ ] Un lot hors contrat (vide, > 50 échanges, `sourceRef` en double, texte total > 400 000) est
      refusé ; un message > 8 000 caractères est tronqué.
- [ ] Sans analyseur, `runOnce` ne prend aucun lot.
- [ ] `DONE` : les écritures de l'analyseur sont faites **et** `payload` est `NULL` dans la même
      transaction ; si l'écriture échoue, le brut reste et le lot est `RETRY`.
- [ ] `RETRY` × 3 → `FAILED` ; `DEFER` ne consomme pas de tentative.
- [ ] Les lots d'un poste sont traités dans l'ordre de réception ; un bail tenu empêche un second
      traitement du même poste.
- [ ] `expireRaw` efface le texte des lots de plus de 7 jours et les passe `EXPIRED` (sauf `DONE`).
- [ ] La consommation d'un lot s'ajoute à `radar_syncs.consumed_tokens` ; `finishSync` ne la baisse pas.
- [ ] **Isolation** : un lot ne peut viser la synchro d'un autre poste ; la purge d'un poste
      n'efface que ses lots ; `GET /syncs` ne compte que les lots du poste.

---

## Périmètre

### Hors scope (explicite)

- La réception du cadre runner et l'ouverture / fin de synchro (F-100).
- Le tri (SF-101-02), l'extraction et le rattachement (SF-101-03/04), la réserve et le coût en
  euros (SF-101-05). Aucun analyseur n'est déclaré par cette SF.
- Tout écran (F-102).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `status` | `PENDING` | un lot reçu attend |
| `attempts` | 0 | +1 à chaque prise, sauf report |
| `expires_at` | `received_at + 7 j` | `app.radar.analysis.raw-retention` |
| jetons (6 colonnes) | 0 | cumulés à chaque passage |
| `payload` | le lot normalisé | `NULL` dès `DONE` ou expiration |

---

## Contraintes de validation

Voir le tableau du **contrat d'entrée**. Constantes dans `RadarExchangeBatch`.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/syncs` (réponse enrichie de `analysis`) | Oui | droit Teams (inchangé) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_analysis_batches` | INSERT / SELECT / UPDATE / DELETE | nouvelle (089) |
| `radar_analysis_leases` | INSERT / SELECT / UPDATE / DELETE | nouvelle (089) |
| `radar_syncs` | SELECT / UPDATE | `consumed_tokens` cumulé |

### Migration Liquibase

- [x] Oui — `089-radar-analysis-queue.xml`

### Composants Angular (si applicable)

- Aucun (F-102).

---

## Plan de test

### Tests unitaires

- [ ] `RadarExchangeBatchTest` — normalisation, troncature, bornes, doublon de `sourceRef`.
- [ ] `RadarAnalysisPropertiesTest` — valeurs aberrantes → défauts ; échéances de reprise.

### Tests d'intégration

- [ ] `RadarAnalysisQueueIntegrationTest` (H2, analyseur factice) — idempotence et remise en file ;
      synchro d'autrui / annulée ; sans analyseur rien n'est pris ; `DONE` efface le brut et écrit ;
      écriture refusée → brut conservé, `RETRY` ; 3 `RETRY` → `FAILED` ; `DEFER` ; ordre de réception ;
      bail tenu ; `expireRaw` ; cumul `consumed_tokens` ; `finishSync` ne baisse pas.
- [ ] `GET /syncs` → `analysis` compté.

### Isolation

- [x] Applicable — lot sur la synchro d'un autre poste refusé ; purge d'un poste : lots de l'autre
      poste et de Bob intacts ; `GET /syncs` de Bob ne voit rien d'Alice.

---

## Dépendances

### Subfeatures bloquantes

- F-99 (registre, synchros, purge) — `done`.
- F-100 appellera `submit` — livrée en parallèle, non bloquante (le contrat est ici).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants impactés : `RadarAnalysisIntake` (la synchro doit être du
  périmètre), `RadarAnalysisQueue` (toute requête à `user_id` + `host_id`, bail par poste),
  `RadarPurgeService` (purge des lots), `RadarReadService.syncs`. Aucun résolveur existant modifié.
- **Plans / limites : non** (la réserve est SF-101-05). **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **Bail par poste plutôt qu'un verrou de base** (`SKIP LOCKED` absent du code, H2) : une ligne par
  poste, prise par `UPDATE … WHERE leased_until < now` ; l'insertion concurrente est arbitrée par
  l'index d'unicité. Réversible.
- **Le brut est effacé dans la transaction des écritures** : jamais de faits écrits avec un brut
  conservé au-delà, jamais un brut effacé sans faits.
- **Un lot `FAILED` re-soumis repart** : c'est ce qui permet à F-100 de rejouer une collecte
  reprise sans créer de doublon.
- **Troncature plutôt que refus** d'un message long : une synchro ne doit pas échouer sur un message.
