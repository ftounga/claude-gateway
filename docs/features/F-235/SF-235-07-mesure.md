# Mini-spec — F-235 / SF-235-07 — La mesure (D14)

## Identifiant
`F-235 / SF-235-07` · Feature parente `F-235` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-235-07-mesure`

## Objectif
> Rendre lisible à J+7 ce que fait le cerveau rapide — latence par cerveau, délégation, corrections du PO, réponses
> creuses ou répétées, coût — sans aucun contenu.

## Comportement attendu
### Cas nominal
- `jarvis_turn_timings` gagne (migration `235-jarvis-turn-timings-brain.xml`) : `brain` (`FAST`, `FAST_DELEGATED`,
  `OPUS`), `fast_model`, `fast_first_token_ms`, `grounding_trips`, `fillers_removed`, `repeats_removed`,
  `imitations_removed`, `forced_delegation`, `delegation_reason` (liste fermée), `correction`.
- L'écran les déclare dans `POST /api/jarvis/timings` (champs facultatifs) à partir du `done` du cerveau rapide
  (cerveau, modèle, premier jeton, compteurs des garde-fous) et de la délégation (raison d'une délégation d'office).
- **Corrections** : une parole dite ≤ 30 s après une réponse rapide et classée localement (liste fermée : « non »,
  « c'est faux », « vérifie », « demande à Opus », « réfléchis-y », « tu te trompes »…) marque `correction`.
- `GET /api/admin/jarvis/fast-brain/measure?days=7` (ADMIN, ses tours) : nombre de tours par cerveau ; premier son
  p50/p90 par cerveau ; premier jeton rapide p50/p90 ; taux de délégation ; délégations d'office par raison ;
  délégations forcées ; corrections (nombre, taux sur les réponses rapides) ; part des réponses rapides dont une
  phrase creuse ou répétée a été retirée (avant retrait ; ce qui est entendu en contient 0 par construction) ;
  cumuls des retraits, ancrages, imitations ; coût € du mois (`jarvis_usage_days.fast_brain_cost_usd`) ; tours Opus
  évités (= réponses rapides).
- Purge 90 j existante inchangée (même table).

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| `brain` ou `delegationReason` hors liste, modèle illisible, compteur hors bornes | refus | 400 |
| Non-ADMIN sur la mesure | refus | 403 |
| Tour d'avant F-235 (sans `brain`) | compté comme `OPUS` | — |

## Critères d'acceptation
- [x] Les champs sont rangés sans contenu (test : la mesure ne contient pas la parole).
- [x] La mesure agrège par cerveau, délégation, corrections, creuses, sous `user_id` (un autre ADMIN voit 0).
- [x] 403 pour un non-ADMIN ; 400 hors liste.

## Plan de test
- `JarvisLatencyApiIntegrationTest.fastBrainMeasure` (agrégats, isolation, 403, 400) ; tests existants inchangés.
- Front : `jarvis.component.spec.ts` (mesure FAST + correction + délégation forcée), `jarvis-fast-brain.spec.ts`
  (classement des corrections).

## Tables / endpoints / composants
- `jarvis_turn_timings` (colonnes D14). `POST /api/jarvis/timings` (champs additifs),
  `GET /api/admin/jarvis/fast-brain/measure` (nouveau).
- Back : `JarvisTurnTiming`, `JarvisLatencyService`, `JarvisFastBrainMeasureService` / `Controller` (nouveaux).
- Front : `jarvis-turn-clock.ts`, `jarvis.models.ts`, `jarvis-fast-brain.ts`, `jarvis.component.ts`.

## Préoccupations transversales
- **Auth / Principal** : nouvel endpoint ADMIN (`AdminService.assertAdmin`, 403) ; `POST /jarvis/timings` inchangé
  (accès Forge). Principal inchangé.
- **Contexte tenant** : écriture sous le `user_id` du principal (sujet d'autrui ignoré, existant) ; lecture
  `findByUserIdAndCreatedAtGreaterThanEqual`.

## Hors périmètre
Banc et mise en service (SF-235-08).

## Arbitrages (par défaut, réversibles)
- Compteurs relayés par l'écran depuis le `done` du serveur (même canal que les chronos existants) plutôt qu'une
  seconde écriture côté serveur.
- « Après retrait » n'est pas mesuré : le retrait est systématique, ce qui est dit n'en contient aucun.
