# Mini-spec — [F-99 / SF-99-05] La purge et l'export

---

## Identifiant

`F-99 / SF-99-05`

## Feature parente

`F-99` — Le Radar : le registre de l'organisation
(cadrage validé : `CADRAGE-le-radar.md`, §4.6, §12 bis, §14)

## Statut

`done` — livrée le 2026-09-13 (PR #499)

## Date de création

2026-09-13

## Branche Git

`feat/SF-99-05-radar-purge-export`

---

## Objectif

Donner à l'utilisateur l'**export Markdown** du Radar d'un poste et la **purge** complète de ce
Radar — à la clôture de mission (F-60), au retrait de la Vigie (F-106) ou à sa demande —, avec une
trace sans contenu ; et garantir qu'aucun Radar ne survit à son poste ni à son compte.

---

## Comportement attendu

### Cas nominal

1. **Export** — `GET /api/radar/hosts/{hostId}/export` → `text/markdown; charset=UTF-8`, en pièce
   jointe `radar-<nom-du-poste>-<AAAA-MM-JJ>.md` :
   - en tête : poste, date, rappel « extraits, pas des archives » ;
   - chaque sujet (clos compris, sujets fusionnés exclus car contenus dans leur cible) : état, alias,
     prochaine étape, échéance, **résumé phrase par phrase avec renvois** `[P1]`, personnes et rôles,
     engagements (sens, statut, certitude, échéance, renvois), chronologie (date, source, citation, lien) ;
   - l'annuaire (personne, fonction, sujets et rôles) ;
   - le texte venu des sources est **échappé** (aucun Markdown injecté ne s'interprète).
2. **Purge demandée** — `POST /api/radar/hosts/{hostId}/purge`, corps
   `{ "reason": "MISSION_CLOSED" | "VIGIE_REMOVED" | "USER_REQUEST", "confirm": true }` :
   - supprime **toutes** les lignes `radar_*` du périmètre `(user_id, host_id)` : sujets, alias,
     phrases, personnes, rôles, engagements, preuves, liens, synchros, corrections ;
   - écrit une **trace** `radar_purges` : raison, instant, nombre de sujets et de preuves supprimés —
     **aucun contenu** ;
   - rend la trace. L'écran (F-102 / F-106) propose l'export **avant** d'appeler la purge.
   - `MISSION_CLOSED` exige que le poste soit en mission clôturée (`runner_hosts.mission_status = CLOSED`).
3. **Traces** — `GET /api/radar/hosts/{hostId}/purges` : les purges du poste, plus récentes d'abord.
4. **Le poste disparaît** — à la suppression d'un poste (`RunnerHostLifecycleEvent.DELETED`), son
   Radar est purgé dans la même transaction (raison `HOST_DELETED`).
5. **Le compte disparaît** — `AccountService.deleteAccount` purge tous les Radars et traces de
   l'utilisateur.
6. **Droits** : export, purge et traces exigent la **possession du poste**, **pas** le droit Teams :
   un utilisateur dont l'option a expiré garde le droit de récupérer et d'effacer ses données.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `confirm` absent ou faux | `radar_invalid` | 400 |
| `reason` absente, inconnue, ou `HOST_DELETED` (réservée) | `radar_invalid` / `validation_error` | 400 |
| `MISSION_CLOSED` sur un poste dont la mission n'est pas clôturée | `radar_state_conflict` | 409 |
| Poste inconnu ou d'autrui | `not_found` | 404 |
| Non authentifié | 401 | 401 |

---

## Critères d'acceptation

- [ ] L'export contient chaque sujet (clos compris), ses phrases avec renvois, ses engagements, sa
      chronologie et l'annuaire ; un sujet fusionné n'apparaît pas en double ; `*`, `_`, `#`, `[`, `<`
      d'une citation sont échappés.
- [ ] Après purge : plus aucune ligne `radar_*` du poste ; les lignes de l'autre poste du même
      utilisateur et celles de Bob restent intactes ; une trace avec les compteurs existe.
- [ ] `confirm` faux → 400, rien n'est supprimé ; `MISSION_CLOSED` sur mission en cours → 409.
- [ ] Supprimer un poste purge son Radar.
- [ ] Supprimer le compte purge les Radars de tous ses postes.
- [ ] Export et purge accessibles sans droit Teams, jamais sur le poste d'autrui (404).

---

## Périmètre

### Hors scope (explicite)

- **Purge automatique au geste de clôture de mission** : la clôture existante (F-60) ne propose pas
  encore l'export ; brancher une suppression irréversible sur ce geste sans la proposition exigée par
  le cadrage détruirait des données sans prévenir. La route est prête (`MISSION_CLOSED`) ; le
  branchement se fait avec l'écran qui propose l'export (voir Notes).
- Retrait de la Vigie (F-106 non livrée) : la raison `VIGIE_REMOVED` est prête.
- Export JSON, export du compte entier (F-11) — inchangé.
- Écrans (F-102 / F-106).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `radar_purges.purged_at` | instant de la purge | |
| `radar_purges.subjects_count` / `evidence_count` | nombres supprimés | trace sans contenu |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| reason | Oui | — | `MISSION_CLOSED`, `VIGIE_REMOVED`, `USER_REQUEST` (API) ; `HOST_DELETED` (interne) | — | — |
| confirm | Oui | — | `true` | — | — |
| nom de fichier | — | 80 | `[a-z0-9-]` | — | nom du poste translittéré |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/export` | Oui | propriétaire du poste |
| POST | `/api/radar/hosts/{hostId}/purge` | Oui | propriétaire du poste |
| GET | `/api/radar/hosts/{hostId}/purges` | Oui | propriétaire du poste |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| toutes les `radar_*` | DELETE | suppression en masse filtrée `(user_id, host_id)` ou `user_id` |
| `radar_purges` | INSERT / SELECT / DELETE | nouvelle ; supprimée avec le compte |
| `runner_hosts` | SELECT | possession, état de mission |

### Migration Liquibase

- [x] Oui — `085-radar-purges.xml`

### Composants Angular (si applicable)

- Aucun (F-102 / F-106, planifiées).

---

## Plan de test

### Tests unitaires

- [ ] `RadarMarkdownTest` — échappement, nom de fichier.

### Tests service / intégration

- [ ] `RadarPurgeServiceTest` — purge d'un poste (compteurs, autres périmètres intacts), purge d'un
      utilisateur, suppression d'un poste via `RunnerHostService.deleteWithCredentials`.
- [ ] `RadarPurgeExportApiIntegrationTest` — export (contenu, en-têtes, échappement), purge (400 sans
      confirmation, 409 mission non close, 200 et trace), traces, accès sans droit Teams, 404 autrui.
- [ ] `AccountServiceTest` (existant, mis à jour) — la purge Radar fait partie de la suppression du compte.

### Isolation

- [x] Applicable — purge du poste A : poste B d'Alice et poste de Bob intacts ; Bob → 404 sur l'export
      et la purge du poste d'Alice.

---

## Dépendances

- SF-99-01 → 04 — `done`. F-60 (état de mission) et F-69 (suppression d'un poste) — livrées.
- Questions ouvertes : aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants : `RadarPurgeService` (suppressions filtrées), `RadarHostLifecycleListener`
  (périmètre pris dans l'événement du poste), `AccountService.deleteAccount` (purge par `user_id`),
  `RadarExportService`. Tous les dépôts `radar_*` gagnent des suppressions en masse filtrées.
- **Plans / limites : oui.** Export, purge et traces **n'appellent pas** `TeamsAccessService` (décision
  ci-dessous) ; les autres routes Radar inchangées.
- **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **Pas de droit d'option pour exporter ou effacer** : le droit d'accès et d'effacement de ses données
  ne dépend pas d'un abonnement en cours (cadrage §14 : « export et suppression à la demande »).
- **Confirmation explicite dans le corps** (`confirm: true`) : la purge est irréversible, un appel
  accidentel ne doit pas suffire.
- **Clôture de mission sans purge automatique (gate irréversible)** : tracée comme risque résiduel et
  question au PO — brancher la purge sur le geste de clôture quand l'écran de clôture proposera l'export
  (F-102 / F-106).
