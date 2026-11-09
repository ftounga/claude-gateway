# Mini-spec — [F-252 / SF-252-01] Préparer vite, et le mesurer

## Identifiant

`F-252 / SF-252-01`

## Feature parente

`F-252` — Jarvis répond vite (cadrage `docs/features/F-252/CADRAGE-F-252-jarvis-repond-vite.md`, correctif B)

## Statut

`done`

## Date de création

2026-11-08

## Branche Git

`feat/SF-252-01-preparer-vite`

---

## Objectif

Mesurer la préparation serveur du cerveau rapide (`prep_ms`) et la raccourcir — lectures indépendantes en parallèle,
univers mis en cache 30 s par compte — sans rien changer à ce que le modèle reçoit.

---

## Comportement attendu

### Cas nominal

1. `POST /api/jarvis/converse` : dès l'entrée dans la décision, les lectures **indépendantes** partent en parallèle
   sur des fils virtuels : état du budget, plafond Jarvis, contexte (sujet, attentes, à envoyer, fils, alertes,
   courrier, état connu), écran et pièces, univers de référence, portrait, allusions récentes, clé du compte, réglage
   d'humour, sobriété. Chaque résultat est **consommé dans l'ordre d'avant** : le message, la consigne, les gardes et
   les pancartes sont **identiques** à l'octet près à ceux de la version séquentielle.
2. Les pancartes (catalogue F-248, attaché au fil) et les lignes du bloc « État connu » (garde de fraîcheur F-247)
   lues sur un fil parallèle sont reversées au fil de la décision, **dans l'ordre séquentiel d'avant**.
3. L'univers (`JarvisUniverseService.universe`, la lecture la plus lourde) est mis en cache **par `user_id`**, 30 s
   (`app.jarvis.fast-brain.universe-ttl`, défaut `30s`) ; le texte et les fiches de personnes sont recalculés à chaque
   réplique à partir de l'univers mis en cache (heure courante).
4. `prepMs` = temps entre l'entrée dans la décision et le départ du premier appel au modèle ; il part dans l'événement
   `done` (`null` si aucun appel : délégation forcée, plafond, garde « preuve »).
5. L'écran recopie `prepMs` dans la mesure du tour ; la passerelle le range dans `jarvis_turn_timings.prep_ms`.
6. La migration ajoute aussi, sans les remplir encore, les colonnes de mesure des subfeatures suivantes
   (`speculation`, `end_silence_ms`, `anticipations_dropped`) : une seule migration, les écrans d'avant restent valides.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Une lecture parallèle échoue | même repli que la version séquentielle (bloc omis, portrait absent, budget NONE…) — jamais d'exception | 200 (SSE) |
| Une lecture parallèle dépasse le temps (fil bloqué) | attente bornée (2 s), puis lecture refaite sur le fil de la décision | 200 (SSE) |
| `prepMs` négatif, > 600 000 ou non numérique dans la mesure | refusé comme toute marque | 400 |
| `speculation` hors liste fermée | ignoré (`null`), comme les autres listes fermées | 204 |
| Univers d'un autre compte | impossible : la clé du cache est le `user_id` du principal | — |

---

## Critères d'acceptation

- [x] CA1 — Pour une même requête et un même état, le message envoyé au modèle, la consigne système et la liste des
      pancartes sont identiques avec et sans préparation parallèle (test d'égalité).
- [x] CA2 — Les lectures indépendantes s'exécutent en parallèle : avec 5 lectures simulées de 100 ms, la préparation
      dure < 300 ms (contre ≥ 500 ms en série).
- [x] CA3 — Une lecture en échec donne le même résultat que la version séquentielle (bloc omis), sans exception.
- [x] CA4 — L'univers n'est lu qu'une fois par compte en 30 s ; relu après 30 s ; deux comptes ne partagent jamais
      d'entrée (isolation `user_id`).
- [x] CA5 — `done.prepMs` est présent (≥ 0) quand le modèle est appelé, `null` sinon.
- [x] CA6 — `POST /api/jarvis/timings` range `prepMs`, `speculation` (liste fermée), `endSilenceMs`,
      `anticipationsDropped` ; une valeur hors bornes est refusée (marques) ou ignorée (liste fermée).
- [x] CA7 — L'écran recopie `done.prepMs` dans la mesure du tour.
- [x] CA8 — Aucun changement de consigne ni de modèle (`JarvisFastBrainPrompt.VERSION` inchangé).

---

## Périmètre

### Hors scope (explicite)

- Lancer la préparation avant la retranscription (SF-252-02) ou avant la fin de tour (SF-252-04).
- Toute modification de la consigne (SF-252-03) ; tout changement de modèle.
- Le cache de toute autre lecture que l'univers (courrier, état, fils : volatils).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `prep_ms` | `NULL` | rempli par les écrans à partir de SF-252-01 |
| `speculation`, `end_silence_ms`, `anticipations_dropped` | `NULL` | remplis par SF-252-02 / SF-252-04 |

---

## Contraintes de validation

| Champ | Obligatoire | Bornes | Valeurs autorisées | Normalisation |
|-------|-------------|--------|--------------------|---------------|
| `prepMs` | Non | 0 – 600 000 | entier (marque) | arrondi côté écran |
| `speculation` | Non | 12 car. | `adoptee`, `relancee`, `perdue`, `anticipee` | minuscules ; hors liste ⇒ `null` |
| `endSilenceMs` | Non | 0 – 600 000 | entier (marque) | — |
| `anticipationsDropped` | Non | 0 – 1 000 | entier (compteur) | — |
| `app.jarvis.fast-brain.universe-ttl` | Non | > 0 | durée | défaut 30 s ; ≤ 0 ⇒ 30 s |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---------|-----|------|------------|
| POST | `/api/jarvis/converse` | Oui | `done.prepMs` (additif) |
| POST | `/api/jarvis/timings` | Oui | 4 champs facultatifs (additifs) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `jarvis_turn_timings` | ALTER + INSERT | 4 colonnes nullables |

### Migration Liquibase

- [x] Oui — `252-01-jarvis-turn-timings-speed.xml` (colonnes additives, rollback = drop)

### Composants touchés

- Backend : `JarvisFastBrainService` (préparation parallèle, `Sink.prepared`), `JarvisFastContextBuilder` (cache de
  l'univers), `JarvisFastPrep` (préparation parallèle bornée), `application.yml` (`universe-ttl`, `parallel-prep`), `JarvisCardCatalog` (reversement ordonné d'un catalogue de
  fil), `JarvisFastBrainController` (`DoneEvent.prepMs`), `JarvisLatencyService` / `JarvisTurnTiming` (colonnes).
- Frontend : `jarvis-fast-brain.ts` (`FastDone.prepMs`), `jarvis-turn-clock.ts` (`speed`), `jarvis.models.ts`,
  `jarvis.component.ts` (une ligne : recopie de `prepMs`).

### Préoccupations transversales

- Auth / Principal : non touché. Contexte tenant : le cache est clé `user_id` (composant impacté :
  `JarvisFastContextBuilder.universe`). Plans / limites : budget et plafond lus en parallèle, **même décision**
  (composants : `budgetState`, `JarvisBudgetService.hardStop`). Navigation : non touchée.

---

## Plan de test

### Tests unitaires

- [x] `JarvisFastBrainPrepTest` — égalité du message, de la consigne et des pancartes avec/sans parallélisme (CA1).
- [x] `JarvisFastBrainPrepTest` — 5 lectures de 100 ms préparées en < 300 ms (CA2) ; une lecture en échec ⇒ bloc omis
      (CA3) ; `prepared(ms)` appelé une fois avant le premier appel, jamais sans appel (CA5).
- [x] `JarvisFastContextBuilderTest` — univers lu une fois en 30 s, relu après, deux comptes séparés (CA4).
- [x] `JarvisCardCatalog` — reversement d'un catalogue de fil dans l'ordre.
- [x] Frontend `jarvis-turn-clock.spec.ts` — `speed` recopié dans la mesure (CA7).

### Tests d'intégration

- [x] `JarvisLatencyApiIntegrationTest` (existant, étendu) — `prepMs`, `speculation`, `endSilenceMs`,
      `anticipationsDropped` rangés ; `prepMs` > 600 000 ⇒ 400 ; `speculation` inconnue ⇒ `null` (CA6).
- [x] Tests existants du cerveau rapide (`JarvisFastBrainServiceTest`, `JarvisFastBrainApiIntegrationTest`,
      `JarvisFastBrainStateTest`, `JarvisFastBrainPortraitTest`, `JarvisFastBrainAllusionTest`) verts sans changement.

### Isolation utilisateur

- [x] Applicable — le cache de l'univers est clé `user_id` ; test : l'univers du compte A n'est jamais rendu au compte
      B ; la mesure reste rangée sous le `user_id` du principal (existant).

---

## Dépendances

- Aucune subfeature bloquante. Questions ouvertes : aucune.

## Notes et décisions

- Décision de dev : au plus 6 lectures de préparation simultanées par pod (`MAX_CONCURRENT_READS`) ; le pool Hikari est
  de 10 connexions (`k8s/base/backend/configmap.yaml`) et neuf lectures partent par réplique, le reste du trafic garde
  sa place. Coupe-circuit `APP_JARVIS_FAST_BRAIN_PARALLEL_PREP=false` (retour exact à la série).
- Aucun banc Jarvis : ni la consigne ni le modèle ne changent (V8) ; CA1 prouve que le modèle reçoit la même chose.
- Mesure post-déploiement :
  `select percentile_cont(0.5) within group (order by prep_ms), percentile_cont(0.9) within group (order by prep_ms), count(*) from jarvis_turn_timings where prep_ms is not null and created_at > now() - interval '7 days';`
