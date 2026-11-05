# Mini-spec — F-248 / SF-248-01 Mesurer d'abord

## Identifiant
`F-248 / SF-248-01`

## Feature parente
`F-248` — Les pancartes (cadrage `CADRAGE-F-248-pancartes.md`, §6, §7)

## Statut
`done` (2026-11-05) · **Estimation :** 0,75 j (réestimé ½ j sur le code) · **Branche :** `feat/SF-248-01-mesurer-d-abord` · **Dépend de :** —

## Objectif
Poser la mesure des pancartes **avant** les pancartes : délai premier mot → pancartes (`cards_lag_ms`), nombre de
pancartes (`cards_count`), compteurs fermés `c_*`, bases des portes K1 (premier jeton / premier son) et K4 (i/s), et le
coupe-circuit `APP_JARVIS_CARDS_ENABLED`.

## Comportement attendu

### Cas nominal
1. `jarvis_turn_timings` reçoit `cards_lag_ms` (délai entre la première phrase jouée et la première pancarte montrée,
   ms, ≥ 0) et `cards_count` (pancartes du jeu) ; `POST /api/jarvis/timings` les accepte (facultatifs).
2. `RenderCounts.KEYS` et `PIECE_COUNT_KEYS` reçoivent les clés fermées `c_set`, `c_n1`, `c_n2_6`, `c_n7p`, `c_tgt`,
   `c_cit`, `c_open`, `c_link`, `c_big`, `c_cmp`, `c_more`, `c_voice`, `c_touch`, `c_close2s`, `c_old`, `c_2d` ;
   `jarvis_usage_days.render_counts` passe de 1 000 à 2 000 caractères (le vocabulaire a grandi).
3. `JarvisCardsProperties` (`app.jarvis.cards.enabled`, env `APP_JARVIS_CARDS_ENABLED`, défaut **vrai**).
4. `GET /api/admin/jarvis/cards/measure?days=7` (ADMIN) : répliques, premier jeton rapide et premier son p50/p95 (K1),
   `cards_lag_ms` p50/p90 et part des jeux montrés **avant** le premier mot (K2, doit rester 0), totaux `c_*` et classes
   d'i/s (`fps_*`, K4) de `render_counts`, taux d'ouverture `c_open / c_set` et de fermeture rapide
   `c_close2s / c_set`, et l'état du coupe-circuit. Tout sous le `user_id` de l'appelant.
5. L'écran recopie `cardsLagMs` / `cardsCount` posés par le jeu (SF-248-04/05) dans le chronomètre du tour.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `cardsLagMs` négatif ou > 600 000 | refus | 400 |
| `cardsCount` négatif ou > 1 000 | refus | 400 |
| Mesure par un USER | refus | 403 |
| Écran d'avant F-248 | colonnes à `null`, rien ne casse | 204 |
| Clé `c_*` inconnue dans `render_counts` | ignorée (vocabulaire fermé) | — |

## Contraintes de validation
`cardsLagMs` ∈ [0, 600 000] ; `cardsCount` ∈ [0, 1 000] ; fenêtre 1-90 j (7 par défaut) ; `render_counts` ≤ 2 000 car.

## Critères d'acceptation
- [x] Les deux colonnes sont rangées depuis `POST /api/jarvis/timings` ; hors bornes ⇒ 400.
- [x] Les 16 clés `c_*` sont admises par `RenderCounts.parse` ; une clé inconnue est ignorée.
- [x] La mesure rend K1 (p50/p95), K2 (part avant le premier mot), `c_*`, `fps_*`, taux d'ouverture ; 403 pour un USER ; rien d'un autre compte.
- [x] `APP_JARVIS_CARDS_ENABLED` absent ⇒ vrai ; `false` ⇒ faux (lu par la mesure, utilisé à SF-02).

## Plan de test
- Unitaires : `RenderCountsTest` (+1 : clés `c_*`), `JarvisCardsPropertiesTest`, `jarvis-turn-clock.spec.ts` (+1).
- Intégration : `JarvisCardsMeasureApiIntegrationTest` (colonnes, 400, agrégats, 403, isolation à deux ADMIN).
- Non-régression : `JarvisLatency*`, `JarvisStateMeasure*`, `JarvisAdoption*`.

## Impact
- Tables : `jarvis_turn_timings` (+2 colonnes), `jarvis_usage_days.render_counts` (varchar 1000 → 2000) — migration `248-01-jarvis-cards-measure.xml`, réversible.
- Endpoints : `GET /api/admin/jarvis/cards/measure` (nouveau, ADMIN) ; `POST /api/jarvis/timings` (+2 champs facultatifs).
- Backend : `jarvis/cards/JarvisCardsProperties`, `JarvisCardsMeasureService`, `JarvisCardsMeasureController` ; `RenderCounts`, `JarvisUsageDay`, `JarvisLatencyService`, `JarvisTurnTiming`.
- Frontend : `pieces/jarvis-piece-measure.ts`, `core/models/jarvis.models.ts`, `jarvis-turn-clock.ts`.
- Préoccupations transversales : Contexte tenant — `JarvisCardsMeasureService` (`user_id` sur les deux lectures). Auth, plans, navigation : non.

## Hors périmètre
Les pancartes elles-mêmes (SF-02 →). La base K4 se lit dans les classes `fps_*` existantes ; le banc headless est en SF-10.
