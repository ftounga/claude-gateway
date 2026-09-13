# Mini-spec — [F-99 / SF-99-01] Le modèle du registre

---

## Identifiant

`F-99 / SF-99-01`

## Feature parente

`F-99` — Le Radar : le registre de l'organisation
(cadrage validé : `CADRAGE-le-radar.md`, §3, §4, §12 bis)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-99-01-radar-modele`

---

## Objectif

Poser le registre du Radar — sujets, alias, personnes, rôles par sujet, engagements, preuves,
synchros — cloisonné par `user_id` **et** `host_id`, où **aucun fait n'entre sans preuve**, avec
l'API d'écriture interne que la synchro (F-100/F-101) et les outils (F-104) appelleront, et l'API REST
de lecture des écrans à venir (F-102/F-103).

---

## Contexte

Le cadrage §3 fixe les objets, §4 les règles qui empêchent le Radar de mentir. Le droit (option
Vigie) et la réserve sont en F-107 : F-99 ne les porte pas. Aucun écran n'existe encore ; aucune
synchro n'alimente encore le registre (F-100/F-101). F-99 livre donc un **registre vide mais sûr**,
lisible et testable de bout en bout.

---

## Comportement attendu

### Cas nominal

1. **Le périmètre `RadarScope(userId, hostId)`** est résolu par `RadarScopeResolver` : le poste
   (`runner_hosts`) doit appartenir à l'utilisateur, sinon « introuvable » (404, pas d'oracle).
   Toute méthode du registre et tout dépôt prennent **les deux** identifiants ; aucune méthode ne
   cherche par identifiant seul.
2. **Écriture interne — `RadarRegistry`** (aucune route REST en SF-99-01) :
   - `recordEvidence(scope, EvidenceInput)` : **idempotent** par `(user_id, host_id, source,
     source_ref)` (index d'unicité) — un second appel rend la preuve existante, jamais un doublon.
     Citation courte : au-delà de 280 caractères, tronquée à 279 + « … ».
   - `createSubject(scope, name, state, evidenceIds)` : **refusé sans preuve** ; relie les preuves à la
     chronologie du sujet ; `last_activity_at` = la plus récente des preuves.
   - `attachEvidence(scope, subjectId, evidenceIds)` : ajoute à la chronologie, sans doublon de lien.
   - `setState`, `setNextStep`, `setDueDate`, `replaceSummary(phrases)` : **chaque valeur et chaque
     phrase du résumé exigent au moins une preuve** du même périmètre, sinon rien n'est écrit.
   - `upsertPerson(scope, sourceKey, displayName, jobTitle)` : une personne par identité de source
     normalisée (minuscules, trim) dans le périmètre.
   - `assignRole(scope, subjectId, personId, role, evidenceIds)` : un rôle par personne et par sujet
     (le dernier sourcé remplace), avec preuve.
   - `recordCommitment(scope, CommitmentInput)` : sens (`ME_TO_OTHER`, `OTHER_TO_ME`,
     `INTRODUCTION`), description, porteurs, échéance (explicite ou déduite), certitude
     (`CERTAIN` / `PROBABLE`, jamais un score), statut `OPEN` ; **avec preuve** ; idempotent par
     `extraction_key` facultative.
   - `markCommitment(scope, commitmentId, status, evidenceIds)` : `KEPT`, `POSTPONED`, `ABANDONED`,
     `OPEN`, avec preuve.
   - `startSync(scope)` / `finishSync(scope, syncId, status, coverageJson, consumedTokens)`.
3. **Lecture REST** (sous `/api/radar/hosts/{hostId}`, droit Teams requis — voir Notes) :
   - `GET /subjects` — sujets hors `CLOSED` par défaut (`includeClosed=true` pour tous), triés par
     dernière activité ; filtre `state` facultatif.
   - `GET /subjects/{subjectId}` — le sujet, ses alias, son résumé phrase par phrase **avec les
     identifiants de preuve de chaque phrase**, les preuves de son état / prochaine étape / échéance,
     ses personnes et rôles, ses engagements (avec preuves), sa chronologie (preuves, plus récentes
     d'abord).
   - `GET /commitments` — filtres `direction`, `status` facultatifs ; échéance la plus proche d'abord.
   - `GET /people` — l'annuaire : personne, fonction, dernière interaction, sujets et rôles.
   - `GET /evidence/{evidenceId}` — une preuve.
   - `GET /syncs` — les 20 dernières synchros, plus récentes d'abord.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste inconnu ou d'un autre utilisateur | `not_found` (indiscernables) | 404 |
| Sujet / preuve d'un autre poste du même utilisateur | `not_found` | 404 |
| Utilisateur sans droit Teams (ni admin) | `teams_forbidden` | 403 |
| Non authentifié | 401 | 401 |
| `state` / `direction` / `status` hors énumération | `validation_error` | 400 |
| Registre : fait sans preuve, ou preuve d'un autre périmètre | `RadarEvidenceRequiredException`, rien n'est écrit | — (interne) |
| Registre : champ obligatoire vide (nom, description, source_ref, citation) | `InvalidRadarInputException` | — (interne) |

---

## Critères d'acceptation

- [ ] Migration `081-radar-registry.xml` : 9 tables, `user_id` + `host_id` non nuls partout, variantes
      PostgreSQL et H2, rollback ; `ddl-auto: validate` passe.
- [ ] Index d'unicité `(user_id, host_id, source, source_ref)` sur `radar_evidence` : deux
      `recordEvidence` identiques → une seule ligne.
- [ ] `createSubject`, `setState`, `setNextStep`, `setDueDate`, `replaceSummary`, `assignRole`,
      `recordCommitment`, `markCommitment` **sans preuve** → exception, **aucune ligne écrite**.
- [ ] Une preuve d'un autre poste (même utilisateur) ou d'un autre utilisateur est refusée comme
      « sans preuve ».
- [ ] `GET /subjects/{id}` renvoie chaque phrase du résumé avec ses identifiants de preuve.
- [ ] `GET /subjects` exclut `CLOSED` par défaut.
- [ ] **Isolation** : deux postes du même utilisateur ne se voient pas ; deux utilisateurs ne se
      voient pas (404 sur le poste d'autrui, 404 sur un sujet d'un autre poste).
- [ ] Citation > 280 caractères tronquée à 280.
- [ ] Sans droit Teams → 403 sur les lectures.

---

## Périmètre

### Hors scope (explicite)

- Corrections souveraines, journal, annulation (SF-99-02).
- Fusion, séparation, alias appris (SF-99-03) — les alias existent en table, seulement lus ici.
- États `CLOSE_PROPOSED` / `CLOSED` / `DORMANT` et leurs règles (SF-99-04) — l'énumération existe.
- Purge et export (SF-99-05).
- Droit Vigie et réserve de synchro (F-107 SF-107-03/04).
- Collecte, planification (F-100), analyse (F-101), écrans (F-102/F-103), outils (F-104).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `radar_subjects.state` | `NEW` si non précisé | un sujet découvert est présenté comme nouveau |
| `radar_commitments.status` | `OPEN` | un engagement naît ouvert |
| `radar_commitments.certainty` | fournie | obligatoire, jamais déduite par défaut |
| `radar_syncs.status` | `RUNNING` au démarrage | |
| `created_at` / `updated_at` | horodatage Hibernate | |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| subject.name | Oui | 200 | non vide | Non | trim |
| subject.next_step | Non | 500 | texte | Non | trim |
| summary sentence | Oui | 500 | non vide, ≤ 20 phrases | Non | trim |
| alias | Oui | 200 | non vide | (user, host, subject, normalisé) | trim + minuscules pour la clé |
| evidence.source | Oui | — | `TEAMS_MESSAGE`, `TEAMS_MEETING`, `LOCAL_RECORDING`, `USER_NOTE`, `PASTED_MAIL` | avec source_ref | — |
| evidence.source_ref | Oui | 512 | non vide | (user, host, source, ref) | trim |
| evidence.quote | Oui | 280 | non vide | Non | trim, tronquée à 279 + « … » |
| evidence.deep_link | Non | 2048 | texte | Non | trim ; au-delà, ignoré |
| person.source_key | Oui | 320 | non vide | (user, host, clé) | trim + minuscules |
| person.display_name | Oui | 200 | non vide | Non | trim |
| role | Oui | — | `DECIDES`, `DRIVES`, `EXPERT`, `INFORMED` | (user, host, subject, person) | — |
| commitment.description | Oui | 500 | non vide | Non | trim |
| commitment.direction | Oui | — | `ME_TO_OTHER`, `OTHER_TO_ME`, `INTRODUCTION` | — | — |
| commitment.status | Oui | — | `OPEN`, `KEPT`, `POSTPONED`, `ABANDONED` | — | — |
| commitment.certainty | Oui | — | `CERTAIN`, `PROBABLE` | — | — |
| commitment.extraction_key | Non | 128 | texte | (user, host, clé) | trim |
| subject.state | Oui | — | `NEW`, `ADVANCING`, `WAITING`, `BLOCKED`, `DORMANT`, `CLOSE_PROPOSED`, `CLOSED` | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/subjects` | Oui | droit Teams (admin bypass) |
| GET | `/api/radar/hosts/{hostId}/subjects/{subjectId}` | Oui | idem |
| GET | `/api/radar/hosts/{hostId}/commitments` | Oui | idem |
| GET | `/api/radar/hosts/{hostId}/people` | Oui | idem |
| GET | `/api/radar/hosts/{hostId}/evidence/{evidenceId}` | Oui | idem |
| GET | `/api/radar/hosts/{hostId}/syncs` | Oui | idem |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_subjects` | INSERT / SELECT / UPDATE | nom, état, prochaine étape, échéance, dernière activité |
| `radar_subject_aliases` | SELECT | écrit en SF-99-03 |
| `radar_subject_facts` | INSERT / SELECT / DELETE | phrases du résumé (position, texte) |
| `radar_people` | INSERT / SELECT / UPDATE | annuaire par poste |
| `radar_subject_roles` | INSERT / SELECT / UPDATE | rôle par personne et par sujet |
| `radar_commitments` | INSERT / SELECT / UPDATE | engagements |
| `radar_evidence` | INSERT / SELECT | preuves, uniques par identifiant de source |
| `radar_evidence_links` | INSERT / SELECT / DELETE | ce que chaque preuve justifie (chronologie, état, phrase, engagement, rôle…) |
| `radar_syncs` | INSERT / SELECT / UPDATE | synchros |
| `runner_hosts` | SELECT | possession du poste |

### Migration Liquibase

- [x] Oui — `081-radar-registry.xml`
- [ ] Non applicable

### Composants Angular (si applicable)

- Aucun (backend uniquement). Écrans : F-102 / F-103, planifiés.

---

## Plan de test

### Tests unitaires

- [ ] `RadarTextTest` — troncature de la citation, normalisation des clés, bornes.
- [ ] `RadarRegistryTest` (Spring, H2) — fait sans preuve refusé pour chaque écriture ; preuve d'un
      autre périmètre refusée ; idempotence des preuves et des engagements (`extraction_key`) ;
      `last_activity_at` suit la preuve la plus récente ; `replaceSummary` remplace les phrases.

### Tests d'intégration

- [ ] `RadarReadApiIntegrationTest` — `GET /subjects` (exclusion `CLOSED`, `includeClosed`),
      `GET /subjects/{id}` (phrases avec preuves, rôles, engagements, chronologie), `GET /commitments`
      filtré, `GET /people`, `GET /evidence/{id}`, `GET /syncs` ; 400 sur énumération invalide ; 401.
- [ ] 403 sans droit Teams (utilisateur non admin, sans abonnement).

### Isolation

- [x] Applicable — `RadarIsolationIntegrationTest` : Alice poste A / poste B, Bob : 404 sur le poste
      d'autrui, 404 sur un sujet d'un autre poste du même utilisateur, listes disjointes ; le registre
      refuse une preuve d'un autre poste.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (F-48 postes, F-89 droit Teams : livrés).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants impactés : `RadarScopeResolver` (nouveau, s'appuie sur
  `RunnerHostService.requireOwned`), tous les dépôts `radar_*` (méthodes à `userId` + `hostId`
  obligatoires). Aucun composant existant de résolution du tenant n'est modifié.
- **Plans / limites : oui (lecture seule).** Composant impacté : `TeamsAccessService.requireAccess()`
  appelé par `RadarController`. Aucun service de limite modifié.
- **Auth / Principal : non.** **Navigation : non** (backend).

---

## Notes et décisions

- **Droit provisoire = droit Teams** (réversible). Le cadrage §11 dit que le Radar suppose l'option
  Teams ; le droit Vigie (SF-107-03) n'existe pas encore. Le contrôleur appelle
  `TeamsAccessService.requireAccess()` ; SF-107-03 remplacera cet appel.
- **Preuves reliées par une table de liens** `radar_evidence_links (evidence_id, subject_id,
  target_kind, target_id)` plutôt qu'une colonne `subject_id` sur la preuve : une preuve est unique par
  identifiant de source (idempotence) mais peut justifier plusieurs faits, et la fusion / séparation
  (SF-99-03) déplace des liens, jamais des preuves.
- **Citation tronquée plutôt que refusée** : une synchro ne doit pas échouer sur une phrase longue.
- **Traitement d'erreurs dans le paquet** : `RadarExceptionHandler` (`@RestControllerAdvice` limité au
  paquet `radar`) plutôt que `GlobalExceptionHandler`, pour ne pas toucher un fichier partagé.
