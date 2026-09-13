# Mini-spec — [F-99 / SF-99-04] La clôture d'un sujet

---

## Identifiant

`F-99 / SF-99-04`

## Feature parente

`F-99` — Le Radar : le registre de l'organisation
(cadrage validé : `CADRAGE-le-radar.md`, §6, §12 bis)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-99-04-radar-cloture`

---

## Objectif

Appliquer les règles de fin d'un sujet du cadrage §6 : un signal explicite **propose** la clôture
(`CLOSE_PROPOSED`, un clic pour confirmer ou refuser), la parole de l'utilisateur **clôt
immédiatement**, le silence ne clôt **jamais** (sommeil à 21 jours), et un sujet clos qui reprend
vie est **annoncé, jamais rouvert en silence** ; les engagements encore ouverts sont signalés à la
clôture.

---

## Comportement attendu

### Cas nominal

1. **Signal explicite lu dans une source** — `registry.proposeClosure(scope, subjectId, evidenceIds)` :
   - avec preuve (sinon rien) ; le sujet passe en `CLOSE_PROPOSED`, l'état d'avant est retenu
     (`previous_state`), `close_proposed_at` posé, les preuves reliées en `CLOSE_SIGNAL` (la phrase qui
     justifie) et rangées dans la chronologie ;
   - **ignoré** si toutes ses preuves sont antérieures au dernier **refus** de l'utilisateur
     (`close_rejected_at`) : un refus n'est pas remis en cause par le même signal ;
   - sur un sujet déjà clos : la preuve est seulement reliée en `CLOSE_SIGNAL` (pas de réveil).
2. **Confirmer / refuser la proposition** —
   `POST /api/radar/hosts/{hostId}/subjects/{subjectId}/close-proposal/confirm` → `CLOSED` ;
   `POST .../close-proposal/reject` → retour à l'état d'avant, `close_rejected_at` posé.
3. **L'utilisateur clôt** — `POST /api/radar/hosts/{hostId}/subjects/{subjectId}/close` : `CLOSED`
   immédiatement, **souverain**, `closed_at` posé, sans confirmation.
4. Clôturer (2 ou 3) **rend les engagements encore ouverts** du sujet (`OPEN` / `POSTPONED`, non
   désavoués) dans la réponse `{ correction, openCommitments }` : l'écran demande « le fermer aussi ? »
   et l'utilisateur répond par les corrections d'engagement de SF-99-02.
5. **Le silence** — `RadarDormancyService.sweep(now)`, lancé chaque nuit (`RadarDormancyWorker`,
   `app.radar.dormancy.cron`, défaut 03:30) : un sujet ouvert (`NEW`, `ADVANCING`, `WAITING`,
   `BLOCKED`) sans activité depuis **21 jours** (`app.radar.dormancy.days`) passe `DORMANT`
   (`dormant_since`, état d'avant retenu). **Jamais `CLOSED`.** Une activité plus récente que la
   dernière connue le **ramène à son état d'avant** ; l'utilisateur peut aussi redire l'état.
6. **Le réveil d'un sujet clos** — une preuve rangée dans la chronologie d'un sujet `CLOSED`, datée
   **après** sa clôture : le sujet **reste clos**, `woke_at` est posé ; il réapparaît dans la liste par
   défaut avec `awake = true` et sa page rend `wakeEvidenceIds`. L'utilisateur :
   - **rouvre** : correction `SET_STATE` de SF-99-02 (efface clôture, réveil, sommeil, proposition) ;
   - **laisse clos** : `POST .../subjects/{subjectId}/wake/dismiss` (`wake_dismissed_at` : seules des
     preuves rangées ensuite réveilleront à nouveau) ;
   - **rattache ailleurs** : séparation de SF-99-03 (le réveil est recalculé sur ce qui reste).
7. **Une synchro sur un sujet proposé ou clos** : `setState` sur `CLOSE_PROPOSED` met à jour l'état
   d'avant (la proposition reste) ; sur `CLOSED`, la preuve est de l'activité (réveil), l'état ne
   change pas.
8. **Journal et annulation** : `CLOSE`, `CONFIRM_CLOSE`, `REJECT_CLOSE`, `DISMISS_WAKE` sont des
   corrections journalisées (champs touchés avant / après) et annulables par la route de SF-99-02.
9. **Lecture** : un sujet clos sort de `GET /subjects` sauf s'il est réveillé ; `includeClosed=true` ou
   `q=` (recherche sur nom et alias) les rend tous — **consultable et cherchable**. `SubjectDetail`
   expose `previousState`, `closeProposedAt`, `closeSignalEvidenceIds`, `closedAt`, `dormantSince`,
   `wokeAt`, `wakeEvidenceIds` ; `SubjectSummary` expose `awake`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Clore un sujet déjà clos | `radar_state_conflict` | 409 |
| Confirmer / refuser alors que le sujet n'est pas `CLOSE_PROPOSED` | `radar_state_conflict` | 409 |
| Écarter un réveil alors que le sujet n'est pas réveillé | `radar_state_conflict` | 409 |
| Sujet fusionné | `radar_subject_merged` | 409 |
| Proposition de clôture sans preuve | `RadarEvidenceRequiredException`, rien n'est écrit | — (interne) |
| Sujet d'un autre poste | `not_found` | 404 |
| Sans droit Teams | `teams_forbidden` | 403 |

---

## Critères d'acceptation

- [ ] `proposeClosure` → `CLOSE_PROPOSED`, `closeSignalEvidenceIds` ; refuser → état d'avant ; le même
      signal (preuves antérieures au refus) ne repropose pas ; un signal postérieur repropose.
- [ ] `close` → `CLOSED`, `stateSovereign`, réponse avec les engagements ouverts (hors désavoués, hors tenus).
- [ ] Confirmer la proposition → `CLOSED`.
- [ ] `sweep` : 22 jours de silence → `DORMANT` ; 20 jours → inchangé ; `CLOSED` et `CLOSE_PROPOSED`
      jamais touchés ; aucun sujet n'est clos par le silence.
- [ ] Une activité plus récente ramène un sujet `DORMANT` à son état d'avant.
- [ ] Preuve postérieure à la clôture → reste `CLOSED`, `wokeAt` posé, listé par défaut avec `awake` ;
      `wake/dismiss` le retire ; `SET_STATE ADVANCING` rouvre et efface la clôture.
- [ ] Recherche `q` trouve un sujet clos par son alias.
- [ ] Annuler `CLOSE` rétablit l'état d'avant.
- [ ] Isolation : clore le sujet d'un autre poste → 404 ; le balayage d'un poste ne touche pas l'autre
      poste au-delà de ses propres sujets silencieux.

---

## Périmètre

### Hors scope (explicite)

- Détection du signal de clôture dans les échanges (F-101 / SF-101-04 appellera `proposeClosure`).
- Le résumé du matin qui annonce réveils et sommeils (F-102) ; boutons (F-102 / F-103).
- Clôture automatique des engagements ouverts (le cadrage demande une question, pas un geste implicite).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `previous_state`, `close_proposed_at`, `close_rejected_at`, `closed_at`, `dormant_since`, `woke_at`, `wake_dismissed_at` | `null` | posés par les règles ci-dessus |
| `app.radar.dormancy.days` | 21 | cadrage §6 |
| `app.radar.dormancy.cron` | `0 30 3 * * *` | nuit, après la synchro de 22 h |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| q | Non | 200 | texte | — | trim, minuscules |
| previous_state | — | — | états ouverts uniquement | — | — |
| dormancy.days | — | — | entier ≥ 1 | — | repli 21 si invalide |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/close` | Oui | droit Teams |
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/close-proposal/confirm` | Oui | droit Teams |
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/close-proposal/reject` | Oui | droit Teams |
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/wake/dismiss` | Oui | droit Teams |
| GET | `/api/radar/hosts/{hostId}/subjects?q=` | Oui | droit Teams (étendue) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_subjects` | UPDATE | + 7 colonnes de fin de vie |
| `radar_evidence_links` | INSERT | `target_kind = CLOSE_SIGNAL` |
| `radar_corrections` | INSERT / UPDATE | `CLOSE`, `CONFIRM_CLOSE`, `REJECT_CLOSE`, `DISMISS_WAKE` |

### Migration Liquibase

- [x] Oui — `084-radar-closure.xml`

### Composants Angular (si applicable)

- Aucun (F-102 / F-103, planifiées).

---

## Plan de test

### Tests service

- [ ] `RadarClosureServiceTest` — proposition, refus et non-reproposition, confirmation, clôture avec
      engagements ouverts, réveil / écart / réouverture, setState sur proposé et clos, annulation, 409.
- [ ] `RadarDormancyServiceTest` — seuil 21 jours, états épargnés, retour à l'état d'avant, deux postes.

### Tests d'intégration

- [ ] `RadarClosureApiIntegrationTest` — les quatre routes, liste par défaut (clos exclu, réveillé
      inclus), `q`, 409, 404.

### Isolation

- [x] Applicable — clore depuis un autre poste → 404 ; balayage : le sujet silencieux du poste B passe en
      sommeil sans toucher le sujet actif du poste A.

---

## Dépendances

- SF-99-01, 02, 03 — `done`.
- Questions ouvertes : aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants : `RadarClosureService` (nouveau, `RadarScope`),
  `RadarDormancyService` (balayage **par périmètre** : il recherche d'abord les couples
  `(user_id, host_id)` concernés, puis traite chacun avec des requêtes filtrées), `RadarRegistry`.
- **Plans / limites : oui (lecture seule).** `TeamsAccessService.requireAccess()` sur les nouvelles routes.
- **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **Le sommeil s'applique aussi à un état dit par l'utilisateur** : le cadrage §6 n'en exempte aucun ;
  la marque de souveraineté est conservée et l'état d'avant revient à la première activité. Le sommeil
  n'est pas une réécriture : c'est un signal, réversible sans geste.
- **Réveil calculé, pas stocké en liens** : une preuve réveille si elle est datée après la clôture et
  rangée après le dernier écart ; `woke_at` est recalculé après une séparation.
- **Balayage multi-pods** : idempotent (poser `DORMANT` deux fois ne change rien) ; pas de verrou.
