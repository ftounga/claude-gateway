# Mini-spec — [F-99 / SF-99-02] Les corrections souveraines

---

## Identifiant

`F-99 / SF-99-02`

## Feature parente

`F-99` — Le Radar : le registre de l'organisation
(cadrage validé : `CADRAGE-le-radar.md`, §4.2, §9, §12 bis)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-99-02-radar-corrections`

---

## Objectif

L'utilisateur a le dernier mot : ses corrections (renommer, changer l'état, la prochaine étape,
l'échéance ; *fait*, *pas moi*, *reporter*, *abandonner*, *c'est moi*, *rouvrir* sur un engagement)
sont **marquées souveraines, jamais écrasées par une synchro**, tracées dans un **journal** et
**annulables**.

---

## Comportement attendu

### Cas nominal

1. **Correction d'un sujet** — `POST /api/radar/hosts/{hostId}/subjects/{subjectId}/corrections`,
   corps `{ "action": ..., "name"?, "state"?, "nextStep"?, "dueDate"? }` :
   - `RENAME` (`name` requis) : le nom change, `name_sovereign = true`, **l'ancien nom devient un
     alias** du sujet (il ne sera plus pris pour un autre sujet) ;
   - `SET_STATE` (`state` ∈ `NEW`, `ADVANCING`, `WAITING`, `BLOCKED`) : `state_sovereign = true` ;
     permis depuis n'importe quel état, y compris clos (c'est ainsi que l'utilisateur rouvre) ;
   - `SET_NEXT_STEP` (`nextStep`, vide = effacer) : `next_step_sovereign = true` ;
   - `SET_DUE_DATE` (`dueDate`, vide = effacer) : `due_date_sovereign = true`.
2. **Correction d'un engagement** — `POST /api/radar/hosts/{hostId}/commitments/{commitmentId}/corrections`,
   corps `{ "action": ..., "dueDate"? }` ; toute correction pose `sovereign = true` :
   - `DONE` → `KEPT` ; `ABANDON` → `ABANDONED` ; `REOPEN` → `OPEN` ;
   - `POSTPONE` (`dueDate` requis) → `POSTPONED`, échéance posée, `due_deduced = false` ;
   - `NOT_MINE` → `disowned = true` (« pas moi » : l'engagement sort de mes listes, il n'est pas
     supprimé) ; `CONFIRM` (« c'est moi ») → `certainty = CERTAIN`, `disowned = false`.
3. **Le journal** : chaque correction écrit une ligne `radar_corrections` — cible, action, **valeurs
   avant et après** (JSON), instant. `GET /api/radar/hosts/{hostId}/corrections?subjectId=` rend le
   journal, plus récent d'abord (les corrections d'un engagement portent le sujet de l'engagement).
4. **Annuler** — `POST /api/radar/hosts/{hostId}/corrections/{correctionId}/undo` : rétablit les
   valeurs **et les marques de souveraineté** d'avant, supprime l'alias créé par un `RENAME`, pose
   `undone_at`. Une correction **ne s'annule pas** si une correction plus récente, encore active, porte
   sur **les mêmes champs de la même cible** (annuler « l'état » sous un état redit ensuite mentirait).
5. **La synchro respecte la souveraineté** (`RadarRegistry`) : `setState`, `setNextStep`,
   `setDueDate` sur une valeur souveraine, `markCommitment` sur un engagement souverain **n'écrivent
   pas la valeur** ; la preuve entre quand même dans la chronologie (c'est de l'activité).
6. **Lecture** : `SubjectDetail` expose `nameSovereign`, `stateSovereign`, `nextStepSovereign`,
   `dueDateSovereign` ; `CommitmentView` expose `sovereign` et `disowned`. `GET /commitments` et le
   compte `openCommitments` **excluent les engagements désavoués** (`includeDisowned=true` pour les
   voir) ; la page d'un sujet les montre tous.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `action` absente ou inconnue | `validation_error` | 400 |
| `RENAME` sans nom, nom > 200 | `radar_invalid` | 400 |
| `SET_STATE` vers `DORMANT`, `CLOSE_PROPOSED`, `CLOSED` | `radar_invalid` (clôture : SF-99-04) | 400 |
| `POSTPONE` sans `dueDate` | `radar_invalid` | 400 |
| Action de sujet envoyée à un engagement (ou l'inverse) | `radar_invalid` | 400 |
| Sujet, engagement, correction d'un autre poste / inconnus | `not_found` | 404 |
| Annuler une correction déjà annulée | `radar_correction_conflict` | 409 |
| Annuler une correction recouverte par une correction plus récente et active (mêmes champs, même cible) | `radar_correction_conflict` | 409 |
| Sans droit Teams | `teams_forbidden` | 403 |

---

## Critères d'acceptation

- [ ] Après `SET_STATE BLOCKED` par l'utilisateur, un `registry.setState(ADVANCING)` de synchro laisse
      `BLOCKED` ; la preuve est dans la chronologie.
- [ ] Après `DONE`, un `registry.markCommitment(OPEN)` de synchro laisse `KEPT`.
- [ ] Idem pour `SET_NEXT_STEP` et `SET_DUE_DATE`.
- [ ] `RENAME` ajoute l'ancien nom aux alias ; l'annulation rétablit le nom, retire l'alias et la marque.
- [ ] `NOT_MINE` retire l'engagement de `GET /commitments` par défaut, pas de la page du sujet.
- [ ] Chaque correction écrit une ligne de journal avec avant / après ; `GET /corrections` les rend.
- [ ] L'annulation rétablit valeur **et** souveraineté d'avant ; une seconde annulation → 409 ; annuler
      un `SET_STATE` recouvert par un `SET_STATE` plus récent actif → 409 ; annuler un `RENAME` sous un
      `SET_STATE` plus récent → permis.
- [ ] Isolation : correction et annulation sur un autre poste → 404, rien n'est écrit.

---

## Périmètre

### Hors scope (explicite)

- Fusion, séparation, alias appris d'une fusion / séparation (SF-99-03).
- Clôture, proposition de clôture, sommeil, réveil (SF-99-04).
- Corrections de rôles et de personnes (aucun geste prévu par le cadrage).
- « Ignorer ce fil » (F-100 / SF-100-04, s'appuiera sur ce journal).
- Écrans et gestes (F-102) ; tour d'agent « Donner la nouvelle » (F-104).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `radar_subjects.*_sovereign` | `false` | lignes existantes comprises |
| `radar_commitments.sovereign` | `false` | idem |
| `radar_commitments.disowned` | `false` | idem |
| `radar_corrections.undone_at` | `null` | posé par l'annulation |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| action (sujet) | Oui | — | `RENAME`, `SET_STATE`, `SET_NEXT_STEP`, `SET_DUE_DATE` | — | — |
| action (engagement) | Oui | — | `DONE`, `NOT_MINE`, `POSTPONE`, `ABANDON`, `CONFIRM`, `REOPEN` | — | — |
| name | si `RENAME` | 200 | non vide | — | trim |
| state | si `SET_STATE` | — | `NEW`, `ADVANCING`, `WAITING`, `BLOCKED` | — | — |
| nextStep | Non | 500 | texte ; vide = effacer | — | trim |
| dueDate | si `POSTPONE` | — | `YYYY-MM-DD` | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/corrections` | Oui | droit Teams |
| POST | `/api/radar/hosts/{hostId}/commitments/{commitmentId}/corrections` | Oui | droit Teams |
| GET | `/api/radar/hosts/{hostId}/corrections` | Oui | droit Teams |
| POST | `/api/radar/hosts/{hostId}/corrections/{correctionId}/undo` | Oui | droit Teams |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_subjects` | UPDATE | + `name_sovereign`, `state_sovereign`, `next_step_sovereign`, `due_date_sovereign` |
| `radar_commitments` | UPDATE | + `sovereign`, `disowned` |
| `radar_subject_aliases` | INSERT / DELETE | l'ancien nom d'un `RENAME` |
| `radar_corrections` | INSERT / SELECT / UPDATE | le journal |

### Migration Liquibase

- [x] Oui — `082-radar-corrections.xml`

### Composants Angular (si applicable)

- Aucun. Gestes à l'écran : F-102 (SF-102-02), planifiée.

---

## Plan de test

### Tests unitaires / service

- [ ] `RadarCorrectionServiceTest` (Spring, H2) — chaque action de sujet et d'engagement ; souveraineté
      respectée par `setState`, `setNextStep`, `setDueDate`, `markCommitment` ; annulation (valeur +
      marque + alias) ; 409 double annulation, 409 annulation recouverte ; entrées invalides.

### Tests d'intégration

- [ ] `RadarCorrectionApiIntegrationTest` — les quatre routes ; 400 action absente ; `NOT_MINE`
      retire de `GET /commitments` ; journal rendu ; 404 sur un autre poste sans écriture.

### Isolation

- [x] Applicable — correction d'un sujet du poste B via le poste A → 404 ; annulation d'une correction
      de Bob par Alice → 404 ; journal disjoint.

---

## Dépendances

- SF-99-01 — `done`.
- Questions ouvertes : aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants : `RadarCorrectionService` (nouveau), `RadarCorrectionRepository`
  (méthodes à `userId` + `hostId`), `RadarScopeResolver` (inchangé).
- **Plans / limites : oui (lecture seule).** `TeamsAccessService.requireAccess()` sur les nouvelles
  routes, comme en SF-99-01.
- **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **Souveraineté par champ** pour un sujet (nom, état, prochaine étape, échéance) : la synchro doit
  continuer à ranger des preuves et à mettre à jour ce que l'utilisateur n'a pas corrigé ; **par
  engagement entier** pour un engagement (statut, échéance, porteur forment une seule réponse).
- **« Pas moi » = désaveu, pas suppression** : l'engagement reste consultable et annulable ; il sort
  des listes par défaut.
- **Annulation par champs** : chaque ligne du journal ne retient que les champs que son action touche ;
  l'annulation ne rétablit qu'eux, et se refuse si une correction plus récente et active les a
  recouverts. Une synchro qui a mis à jour un autre champ entre-temps n'est donc jamais défaite.
