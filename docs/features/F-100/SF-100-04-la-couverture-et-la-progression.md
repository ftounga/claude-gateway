# Mini-spec — F-100 / SF-100-04 — La couverture et la progression

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §4.4 (« il dit ce qu'il n'a pas lu »), §5 (échec bruyant
> avec le geste, progression visible et annulable, rattrapage dit), §12 bis SF-100-04 (*lire ce canal*,
> *ignorer ce fil* depuis la couverture) et §4.2 (corrections souveraines). Cadrage validé.

## Identifiant

`F-100 / SF-100-04`

## Feature parente

`F-100` — Le Radar : la synchro du soir

## Statut

`done` — livrée le 2026-09-13 (PR #528)

## Date de création

2026-09-13

## Branche Git

`feat/SF-100-04-couverture-progression`

---

## Objectif

Rendre chaque synchro **lisible et pilotable** : une couverture résumée qui **dit en tête** ce qui n'a pas
été lu (et le geste quand la session a manqué), la progression d'une synchro en cours, son **annulation**,
et les gestes *ignorer ce fil* / *lire ce canal* posés depuis la couverture — souverains et annulables.

---

## Contexte

SF-100-03 produit la couverture brute (compteurs, fils non entièrement lus, échec nommé) ; SF-100-02 le
battement. Il manque ce que le résumé du matin (F-102) affichera : une **phrase de tête honnête**, la liste
des manques avec leurs gestes possibles, et les routes qui agissent. Écrans : F-102 (tracé au cadrage).

---

## Comportement attendu

### Cas nominal

1. **Résumé de couverture** — `GET /api/radar/hosts/{hostId}/syncs` gagne, par synchro, `summary` :
   - `headline` : une phrase, **jamais rassurante à tort** :
     - en cours : « Synchro en cours : 12 conversations sur 40. » ;
     - `SUCCEEDED` : « Synchro complète : 38 conversations et 2 réunions lues. » ;
     - `PARTIAL` : « Synchro partielle : 3 fils non entièrement lus, 6 canaux actifs non lus, 1 réunion sans
       transcription. » (seuls les compteurs non nuls) ;
     - `FAILED` : la phrase de l'échec (`failure.sentence`, par ex. « Session Microsoft expirée : rien n'a
       été synchronisé… ») ;
     - `CANCELLED` : « Synchro annulée à 22 h 41 : ce qui avait été lu est conservé. » ;
     - rattrapage : préfixe « Synchro d'hier soir non faite, rattrapée à 8 h 12. » (déclencheur `CATCH_UP`,
       heure dans le fuseau du poste).
   - `remedy` : le geste quand il y en a un (session expirée, Teams non ouvert, navigateur non détecté) ;
   - `items` : les manques (`ref`, `label`, `kind`, `status`, `detail`) avec `actions` possibles :
     `IGNORE` pour un fil ou un canal, `READ_CHANNEL` pour un canal actif non lu ; une règle déjà posée est
     signalée (`rule`).
2. **Progression** — `GET …/schedule` : `running` gagne `phase`, `done`, `total` (du dernier battement).
3. **Annuler** — `POST /api/radar/hosts/{hostId}/syncs/{syncId}/cancel` : une synchro `RUNNING` passe
   `CANCELLED` (fin, couverture conservée et marquée `cancelled`), le poste est libéré, et le runner est
   prévenu **au mieux** (`teams_radar_cancel`, délai court) — de toute façon il s'arrête au prochain battement
   ou lot (409). Les lots déjà déposés restent dans la file (F-101 décide), aucun nouveau n'entre.
4. **Ignorer ce fil / lire ce canal** — `POST /api/radar/hosts/{hostId}/thread-rules`
   `{ "conversationRef", "rule": "IGNORE" | "READ_CHANNEL", "label" }` → la règle est posée (idempotent :
   la même règle rend l'existante) ; `GET …/thread-rules` les liste ; `DELETE …/thread-rules/{ruleId}`
   l'**annule**. Aucune synchro ne modifie une règle ; la prochaine collecte la lit (SF-100-03).
5. **Runner** — `teams_radar_cancel {sync_id}` : si c'est la synchro en cours, la collecte s'arrête à sa
   prochaine étape et **aucune fin n'est envoyée** (la gateway a déjà clos) ; sinon `cancelled=false`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste d'autrui ou inconnu | `not_found` | 404 |
| Synchro d'un autre poste | `not_found` | 404 |
| Annuler une synchro déjà close | `radar_state_conflict` | 409 |
| Runner injoignable à l'annulation | annulation faite quand même (la gateway fait foi) | 200 |
| Règle : `conversationRef` vide ou > 512, `rule` inconnue, `label` > 200 | `radar_invalid` | 400 |
| Annuler une règle d'autrui / inconnue | `not_found` | 404 |
| Couverture illisible (synchro antérieure) | `summary.headline` depuis le seul statut | 200 |

---

## Critères d'acceptation

- [ ] `summary.headline` : en cours, complète, partielle (compteurs non nuls seuls), échouée (phrase de
      l'échec + `remedy`), annulée, rattrapée (préfixe avec l'heure du poste) ; jamais « complète » si un
      manque est compté.
- [ ] `summary.items` : fils non entièrement lus, canaux non lus, réunions sans transcription, avec
      `actions` (`IGNORE`, `READ_CHANNEL`) et la règle déjà posée.
- [ ] `running` expose `phase`, `done`, `total`.
- [ ] Annuler : `CANCELLED`, poste libéré, runner prévenu au mieux ; déjà close → 409 ; après annulation, un
      battement ou un lot du runner reçoit 409.
- [ ] Règles : pose idempotente, liste, annulation ; lues par la collecte suivante.
- [ ] Runner : `teams_radar_cancel` arrête la collecte en cours sans fin envoyée.
- [ ] **Isolation** : Bob ne lit, n'annule ni ne règle rien du poste d'Alice ; une règle du poste A n'apparaît
      pas sur le poste B.

---

## Périmètre

### Hors scope (explicite)

- L'écran (F-102 / SF-102-01).
- Le sort des lots déjà en file d'une synchro annulée (F-101).
- Le dossier de dépôt (SF-100-05).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| délai de `teams_radar_cancel` | 5 s | au mieux |
| éléments de `summary.items` | 40 | ceux de la couverture |

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| `conversationRef` | Oui | 1 → 512 caractères |
| `rule` | Oui | `IGNORE`, `READ_CHANNEL` |
| `label` | Non | ≤ 200 caractères |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/syncs` (enrichi de `summary`) | JWT | droit Teams (Radar) |
| GET | `/api/radar/hosts/{hostId}/schedule` (`running` enrichi) | JWT | droit Teams (Radar) |
| POST | `/api/radar/hosts/{hostId}/syncs/{syncId}/cancel` | JWT | droit Teams (Radar) |
| GET | `/api/radar/hosts/{hostId}/thread-rules` | JWT | droit Teams (Radar) |
| POST | `/api/radar/hosts/{hostId}/thread-rules` | JWT | droit Teams (Radar) |
| DELETE | `/api/radar/hosts/{hostId}/thread-rules/{ruleId}` | JWT | droit Teams (Radar) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_syncs` | UPDATE / SELECT | annulation |
| `radar_host_settings` | UPDATE | verrou rendu |
| `radar_thread_rules` | INSERT / SELECT / DELETE | créée en 088 |

### Migration Liquibase

- [x] Non applicable (088 porte déjà `radar_thread_rules`)

### Composants

| Composant | Rôle |
|-----------|------|
| `radar/sync/RadarCoverageSummary` | phrase de tête, manques, gestes possibles (pur, testable) |
| `radar/RadarReadService` / `RadarViews.SyncView` | `summary` |
| `radar/sync/RadarSyncCancelService` | annulation |
| `radar/sync/RadarThreadRuleService` | règles |
| `radar/sync/RadarScheduleService` | progression dans `running` |
| `radar/sync/RadarSyncController` | routes |
| runner `teams/RadarSyncAgent`, `RadarTools`, `TeamsTools` | `teams_radar_cancel` |

### Composants Angular

- Aucun (F-102).

---

## Plan de test

### Tests unitaires

- [ ] `RadarCoverageSummaryTest` — chaque issue ; compteurs nuls omis ; rattrapage avec l'heure du poste ;
      échec avec `remedy` ; couverture illisible ; actions par nature de manque ; règle déjà posée.
- [ ] runner `RadarSyncAgentTest` — annulation de la synchro en cours : arrêt, pas de fin ; autre synchro :
      `cancelled=false`.

### Tests d'intégration

- [ ] `RadarCoverageApiIntegrationTest` — `GET /syncs` rend `summary` ; `running` avec la progression ;
      annulation (statut, poste libéré, runner prévenu, battement suivant 409) ; déjà close 409 ; runner
      injoignable → annulée quand même ; règles : pose, idempotence, liste, annulation, validation, puis
      présentes dans l'entrée de la collecte suivante.

### Isolation utilisateur

- [x] Applicable — Bob → 404 sur annulation, règles et synchros d'Alice ; règle du poste A absente du poste B.

---

## Dépendances

### Subfeatures bloquantes

- SF-100-02, SF-100-03 — prérequis de branche.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants : `RadarScopeResolver` (inchangé), `RadarSyncCancelService`,
  `RadarThreadRuleService`, `RadarReadService.syncs` — toute requête à `user_id` + `host_id`.
- **Plans / limites : oui (réemploi).** `TeamsAccessService.requireAccess` (droit Teams provisoire du Radar).
- **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **La phrase de tête est calculée par la gateway**, pas par l'écran : c'est la règle §4.4 (« jamais un
  résumé qui a l'air complet ») tenue à un seul endroit, testée. Réversible.
- **Annuler ne dépend pas du runner** : la gateway fait foi, le runner s'arrête au premier échange suivant.
- **Une réunion sans transcription est un manque affiché, pas un échec** : la politique du client peut la
  désactiver ; le dire suffit.
