# Mini-spec — [F-174 / SF-174-07] La mesure d'après

## Identifiant

`F-174 / SF-174-07`

## Feature parente

`F-174` — La carte qui répond (cadrage `CADRAGE-F-174-la-carte-qui-repond.md`, D1→D10 validées le 2026-10-04)

## Statut

`done` (outil de mesure livré) — **lecture J+7 en attente** (fenêtre « après » complète le 2026-10-12 si l'index est mis en service le 2026-10-05)

## Date de création

2026-10-04

## Branche Git

`feat/SF-174-07-mesure-d-apres`

---

## Objectif

Comparer, sur deux fenêtres de 7 jours autour de la mise en service de l'index, les fouilles de la carte par l'agent et ce que la gateway lui joint, et dire si le coupe-circuit doit être envisagé.

---

## Comportement attendu

### Cas nominal

`GET /api/admin/map-index/measure?pivot=AAAA-MM-JJ&days=7` (administrateur) → `{ before, after, verdict, notes[] }`. Chaque fenêtre (`from`, `to`) :

| Champ | Source |
|---|---|
| `mapDigs` | `runner_audit` : appels `bash` / `grep` / `read_file` dont la cible cite un fichier de carte du même poste (requête de référence SF-174-01) |
| `turns`, `hybridTurns`, `emptyTurns` | `host_map_lookups` `kind = TURN` (toutes stratégies / `HYBRID` / `NONE`) |
| `factsJoined`, `charsJoined`, `pitfalls`, `deadlines` | sommes `host_map_lookups` `TURN` |
| `toolCalls` | `host_map_lookups` `kind = TOOL` (`carte_chercher`) |

Dérivés : `digsPerTurn`, `factsPerTurn`. La fenêtre « après » s'arrête à maintenant.

**Verdict et seuils de retour arrière** :

| Verdict | Règle |
|---|---|
| `INSUFFISANT` | une fenêtre sans tour |
| `RETOUR_ARRIERE_A_ENVISAGER` | fouilles par tour qui ne baissent pas, **ou** plus d'un tour sur deux sans fait joint ; la note donne le geste : `APP_MAP_INDEX_ENABLED=false` (retour à F-137, sans redéploiement de code) |
| `GAIN` | fouilles par tour en baisse et au moins un tour sur deux nourri |

Une note rappelle que les **relances du PO** (faits que la carte portait et que l'agent n'a pas utilisés) restent une lecture humaine : la mesure l'éclaire, elle ne la remplace pas (règle « justesse avant coût »).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Non administrateur | Refusé | 403 |
| `pivot` illisible | Message « attendu AAAA-MM-JJ » | 400 |
| `days` absent, ≤ 0 | 7 ; plafonné à 60 | 200 |
| `pivot` absent | il y a `days` jours | 200 |

---

## Critères d'acceptation

- [x] Les fouilles de la carte sont comptées sur le bon compte et le bon poste, pas les autres commandes.
- [x] Les consultations TURN / TOOL et la stratégie sont agrégées par fenêtre.
- [x] Le verdict suit les seuils ; le coupe-circuit est nommé.
- [x] Endpoint réservé à l'administrateur, isolé sur son `user_id`.

---

## Périmètre

### Hors scope (explicite)

- La lecture J+7 elle-même (faite par le PO / l'agent le 2026-10-12, consignée dans `PRODUCT_SPEC.md`).
- Un écran (l'API suffit à l'administrateur ; F-173 peut l'afficher).
- La mesure automatique des relances (jugement humain).

---

## Technique

### Endpoint

- **Nouveau** `GET /admin/map-index/measure` (`HostMapMeasureController`), garde `AdminService.assertAdmin()`.

### Composants backend

- `governance/map/index/HostMapMeasureService` (SQL `JdbcTemplate` filtré `user_id`), `HostMapMeasureController`.

### Tables

Aucune nouvelle (lecture de `runner_audit`, `host_map_files`, `host_map_lookups`).

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | Oui | nouvel endpoint `/admin/**` sous la garde unique `AdminService.assertAdmin()` (patron `RunnerDisconnectController`) ; aucun endpoint existant modifié |
| Contexte tenant | Oui | `CurrentUser.requireId()` → toutes les requêtes filtrées `user_id` |
| Plans, routing | Non | — |

---

## Plan de test

- [x] `HostMapMeasureIntegrationTest` (H2 + Liquibase) : comptage avant / après (fouilles, tours, hybrides, outil, isolation d'un autre compte, commande hors carte), verdict de retour arrière, 403 non-admin, 200 admin, 400 date illisible.
- [x] Suite complète verte.

## Dépendances

SF-174-01 à SF-174-06 mergées.

## Notes et décisions

- **Arbitrage (réversible)** : seuils simples et lisibles (baisse des fouilles par tour, moitié des tours nourris) plutôt qu'un score composite.
- **Arbitrage (réversible)** : la fenêtre « après » se termine à l'instant de la requête : la mesure peut être lue en cours de semaine, le verdict définitif à J+7.
