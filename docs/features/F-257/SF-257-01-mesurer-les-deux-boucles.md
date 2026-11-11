# Mini-spec — [F-257 / SF-257-01] Mesurer les deux boucles pareil

## Identifiant

`F-257 / SF-257-01`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage `CADRAGE-F-257-jarvis-converse-en-flux.md`)

## Statut

`in-review`

## Date de création

2026-11-11

## Branche Git

`feat/SF-257-01-mesurer-deux-boucles`

---

## Objectif

Chaque tour vocal mesuré dit de quelle boucle il vient (`legacy` aujourd'hui, `stream` demain) et l'administrateur
lit les deux boucles côte à côte (p50/p90 par étape, replis du flux, adoption de la réponse provisoire), avec un
protocole et des seuils de bascule figés **avant** la mesure.

---

## Comportement attendu

### Cas nominal

1. L'écran Jarvis actuel envoie `loop: "legacy"` dans `POST /api/jarvis/timings` (chronomètre `TurnClock`).
2. La passerelle range la valeur dans `jarvis_turn_timings.voice_loop` (liste fermée `legacy`, `stream`). Une mesure
   sans `loop` (écran d'avant, compagnon pas encore à jour) est rangée `legacy`.
3. `GET /api/admin/jarvis/latency` rend, en plus de l'existant, `byLoop` : pour chaque boucle (toujours les deux, dans
   l'ordre `legacy`, `stream`) le nombre de tours, p50/p90 de chaque étape (mêmes étapes que `steps`), le nombre de
   replis hors flux (`streamFallback` non nul) et le taux d'adoption de la réponse provisoire
   (`adoptee` / tours avec `speculation` non nulle). Les lignes anciennes (`voice_loop` NULL) comptent en `legacy`.
4. Le protocole de mesure et les seuils de bascule (cadrage §5) sont recopiés et **figés** ci-dessous.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `loop` hors liste (`"lune"`) | refus, rien n'est rangé | 400 |
| `loop` absent ou vide | rangé `legacy` | 204 |
| Lecture `byLoop` par un non-ADMIN | refus (patron existant `assertAdmin`) | 403 |
| Lecture par un autre ADMIN | ne voit que **ses** tours (filtre `user_id` existant) | 200 |

---

## Critères d'acceptation

- [ ] CA1 — Une mesure `{"loop":"stream"}` est rangée `voice_loop = 'stream'` ; une mesure sans `loop`, `legacy`.
- [ ] CA2 — `{"loop":"lune"}` → 400, aucune ligne.
- [ ] CA3 — `byLoop` contient toujours `legacy` puis `stream`, avec tours, étapes p50/p90, replis, taux d'adoption
      (`null` sans réponse provisoire).
- [ ] CA4 — Une ligne ancienne `voice_loop NULL` compte en `legacy`.
- [ ] CA5 — Un autre ADMIN ne voit pas les tours du premier dans `byLoop` (isolation `user_id`).
- [ ] CA6 — L'écran actuel envoie `loop: 'legacy'` sur chaque mesure, sans autre changement de comportement.
- [ ] CA7 — OQ-24 inscrite tranchée (décisions PO du 2026-11-11), cadrage §7 mis à jour, ADR-021 et `TECH_STACK.md`
      écrits (service Python Pipecat).

---

## Périmètre

### Hors scope (explicite)

- La boucle `stream` elle-même (SF-257-02 →), l'écran de réglage du drapeau (SF-257-03).
- La **fin réelle de parole** : elle est calculée par le banc F-256 sur son enregistrement (hors ligne), pas par
  l'écran. Cette SF ne crée **aucune** colonne de fin réelle ni d'identifiant de tour : l'identifiant `turn_id`
  arrive avec F-255 (en cours ailleurs) et sera réutilisé tel quel, jamais dupliqué. Le crochet côté boucle `stream`
  (horodatage serveur des événements de tour, lisible par le banc) est livré avec le service (SF-257-02/05).
- Un écran admin : la mesure Jarvis est lue par API aujourd'hui (`/admin/jarvis/latency`, sans écran), on garde ce
  patron.
- Aucune consigne, aucun outil, aucun modèle du cerveau rapide n'est touché (pas de banc Jarvis à rejouer, V8).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `voice_loop` | `legacy` si absent | NULL pour les lignes antérieures à la migration (lues `legacy`) |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `loop` | Non | 8 | `legacy`, `stream` (casse ignorée) | Non | `strip()`, minuscules |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/jarvis/timings` (champ `loop` ajouté) | Oui | accès Atelier |
| GET | `/api/admin/jarvis/latency` (champ `byLoop` ajouté) | Oui | ADMIN |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `jarvis_turn_timings` | ALTER (colonne `voice_loop varchar(8)`), INSERT, SELECT | additive, NULL pour l'existant ; purge 90 j inchangée |

Nom de colonne : `voice_loop` plutôt que `loop` (mot-clé PL/pgSQL, évite toute ambiguïté H2/PostgreSQL) — décision
hors cadrage, notée dans la PR.

### Migration Liquibase

- [x] Oui — `257-01-jarvis-turn-timings-loop.xml` (rollback : la colonne se retire)

### Composants Angular (si applicable)

- `TurnClock` (`jarvis-turn-clock.ts`) — porte `loop` (défaut `legacy`) dans la mesure.
- `TurnTimingReport` (`jarvis.models.ts`) — champ `loop?`.

---

## Protocole de mesure et seuils de bascule — FIGÉS le 2026-11-11

Repris du cadrage §5, sans modification ; toute révision passe par le PO **avant** la mesure.

1. **Même mesure pour les deux boucles** : mêmes étapes (`steps`), `voice_loop` distingue la boucle ; la fin réelle
   de parole est mesurée hors ligne par le banc F-256 sur l'enregistrement.
2. **Banc à vraie voix F-256** : corpus réel du PO (séances enregistrées avec son accord, F-255) + synthétique
   (phrases à pause, bruits, interruptions, haut-parleur), joué aux deux boucles, **deux passages**.
3. **Critères (tous requis)** :

| Critère | Seuil |
|---|---|
| Premier son, médiane / p90 (banc et usage réel) | ≤ 2,0 s / ≤ 3,0 s |
| Phrases entendues en entier | ≥ 95 % (et ≥ ancienne boucle + 10 points) |
| Fins de tour prématurées (corpus à pause) | ≤ 5 % |
| Interruptions : coupure du son après parole confirmée | ≤ 300 ms p90 ; faux déclenchements ≤ 1 par 10 min de voix de Jarvis |
| Échecs de session STT | 0 repli par tour ; reconnexion du fournisseur sans phrase perdue |
| Justesse | bancs CONVERSATION / PIÈCES / ORAL inchangés ; adoption de la préemption mesurée, réponse jamais dite sur un texte non confirmé |
| Usage réel | ≥ 3 séances PO sur ≥ 2 jours, ≥ 50 tours, avis du PO |
| Coût voix | ≤ 1,5 × l'ancienne boucle à l'heure de conversation |

4. **Bascule** : `stream` devient le défaut seulement si tous sont verts ; l'ancienne boucle reste 2 semaines.

---

## Plan de test

### Tests unitaires

- [ ] `JarvisLatencyServiceTest` — `byLoop` : lignes NULL comptées `legacy`, ordre `legacy`/`stream`, taux d'adoption
      (`null` sans réponse provisoire), replis comptés.

### Tests d'intégration

- [ ] `POST /api/jarvis/timings` `{"loop":"stream"}` → 204, rangé `stream` ; sans `loop` → `legacy`.
- [ ] `POST /api/jarvis/timings` `{"loop":"lune"}` → 400.
- [ ] `GET /api/admin/jarvis/latency` → `byLoop[0].loop = legacy`, `byLoop[1].loop = stream`, compteurs justes.
- [ ] Front : `jarvis-turn-clock.spec.ts` — la mesure porte `loop: 'legacy'`.

### Isolation utilisateur

- [x] Applicable — un autre ADMIN ne voit pas les tours du premier dans `byLoop`.

---

## Dépendances

### Subfeatures bloquantes

- Aucune.

### Questions ouvertes impactées

- [x] OQ-24 — tranchée le 2026-11-11 (PO) : Python/Pipecat, ElevenLabs gardé (Deepgram mesuré), l'audio passe par
      notre service.

---

## Notes et décisions

- **Vérification ElevenLabs du 2026-11-11 (préalable de SF-257-04, faite avant tout code)** : voir cadrage §3.4
  (complété). Plan du compte : simultanéité **15** (message `rate_limited` explicite au-delà) ; la clé n'a pas le droit
  `user_read`, le nom du plan n'est donc pas lisible par API.
- Préoccupations transversales : aucune (pas d'auth, de tenant, de plan ni de route touchés ; l'endpoint existant
  garde son filtre `user_id`).
