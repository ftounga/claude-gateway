# Mini-spec — F-241 / SF-241-04 Avoir l'information ne veut pas dire s'en servir : trace, variété, rareté

## Identifiant
`F-241 / SF-241-04`

## Feature parente
`F-241` — Jarvis vous connaît : le portrait (cadrage §4 D11, D12 b/c ; §6 SF-241-04)

## Statut
`in-progress`

## Date de création
2026-11-02

## Branche Git
`feat/SF-241-04-allusions-variete`

---

## Objectif
Des allusions rares, jamais répétées, jamais forcées : chaque allusion dite est journalisée, la trace récente part dans le message, et deux filets déterministes tiennent la rareté et la variété.

---

## Comportement attendu

### Cas nominal
- **Lignes-marques** (cerveau rapide seul, texte figé `FAST_MARKS` après le bloc portrait — préfixe caché) : `✦ allusion: <sujet>` ou `✦ humour: <résumé>` (≤ 6 mots), ligne seule, **jamais dite** : la porte des phrases la retire du flux avant le découpage en phrases ; l'écran l'écarte aussi (filet).
- **Journal `jarvis_allusions`** (`user_id`, `at`, `kind` ALLUSION/HUMOUR, `spontaneous`, `topic` et `phrase` **scellés**) : chaque déclaration ✦ est journalisée ; une phrase dite qui reprend un nom propre du portrait (entourage, moment, goûts, dates, souvenirs) que la parole du PO ne contient pas est **rattrapée** même non déclarée. Purge à 60 jours (`JarvisAllusionPurgeWorker`, 03:55). « Tout effacer » et la suppression du compte le vident.
- **Spontanée ou amenée** : la parole du PO qui parle de sa vie (mot de la vie courante — fils, fille, petit, crevé, vacances… — ou mot de ≥ 5 lettres de son portrait) rend l'allusion **amenée** (« REPONSE ») ; sinon **spontanée**.
- **Message (volatil)** : `[Jarvis — déjà dit récemment, ne pas répéter : « … » ; « … » (humour) — allusion spontanée déjà faite aujourd'hui : oui/non]` — les 10 dernières entrées sur 30 jours.
- **Filets** (`SentenceGate`, spontanées seulement) : (b) une deuxième allusion spontanée dans la journée est écartée ; (c) une allusion dont le terme figure, au mot près, dans un sujet du journal des 7 derniers jours est écartée. Comptés `allusionsBlocked`.
- **Mesure** : `jarvis_turn_timings.personal_allusion` (SPONTANEE, REPONSE, AUCUNE ; liste fermée) ; `GET /api/admin/jarvis/latency` rend `allusions` (tours avec portrait, spontanées, en réponse, **taux d'allusion spontanée**) sur **ses** tours (403 hors ADMIN).
- **Banc CONVERSATION** : `recentAllusions` par scénario (« aujourd'hui: » = allusion spontanée du jour) ; le juge gagne 2 défauts graves (« allusion forcée », « allusion ou trait répété ») ; P06-P10.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Journal illisible (scellé dormant) | aucune trace jointe, aucun filet (c) ; jamais d'erreur à l'oral |
| Écriture du journal en échec | ignorée (best-effort), l'échange continue |
| Présentation | aucun portrait ⇒ aucune trace, aucun journal ; une ligne ✦ éventuelle n'est pas dite |
| `personalAllusion` hors liste à la mesure | ignoré (jamais de texte libre en base) |
| Non ADMIN sur la latence | 403 |

## Critères d'acceptation
- [ ] CA1 — chaque allusion dite est journalisée, déclarée ou rattrapée.
- [ ] CA2 — une deuxième allusion spontanée dans la journée n'est jamais dite.
- [ ] CA3 — aucune allusion spontanée ne répète un sujet des 7 derniers jours.
- [ ] CA4 — une allusion amenée par le PO n'est ni écartée ni comptée comme spontanée.
- [ ] CA5 — banc CONVERSATION ≥ 90 % et zéro défaut grave avec P06-P10 (porte de déploiement).
- [ ] CA6 — le taux d'allusion spontanée est lisible par l'administrateur, sur ses tours seulement.

## Périmètre — hors scope
Un détecteur d'humour automatique (jugé au banc seulement) ; les allusions d'Opus (il n'écrit pas de ✦ ; le filet D12 a de SF-241-03 reste sur ses lignes 🎙).

## Technique
- Tables : `jarvis_allusions` (nouvelle), `jarvis_turn_timings.personal_allusion` — migration `241-jarvis-allusions.xml` (rollback = table et colonne retirées).
- Endpoints : `POST /api/jarvis/timings` (+ `personalAllusion`), `GET /api/admin/jarvis/latency` (+ `allusions`).
- Composants : `JarvisAllusionService`, `JarvisAllusionPurgeWorker`, `JarvisPortraitNote` (`FAST_MARKS`, `allusionTerms`, `personalSaid`, `allusionTerm`, `repeats`), `SentenceGate`, écran (`isPortraitMark`, mesure).

### Préoccupations transversales
- [x] Contexte tenant — `JarvisAllusionService.recent/record(userId)` appelés seulement avec le `userId` du tour et seulement quand le portrait de ce compte sert ; au banc, la trace **fictive** du scénario (jamais `recent()`).

## Plan de test
- Unitaires : `JarvisAllusionServiceTest` (scellé, cache, note des 10, règle du jour, sujets 7 j, note de banc) ; `JarvisFastBrainAllusionTest` (✦ jamais dit + journal, rattrapage spontané, 2ᵉ du jour écartée, sujet récent écarté, amenée ni écartée ni spontanée, trace dans le message pas la consigne, isolation, banc = trace fictive).
- Intégration : `JarvisLatencyApiIntegrationTest` (+1 : taux, liste fermée, isolation ADMIN, 403) ; `JarvisPortraitApiIntegrationTest` (purge du compte vide le journal, l'autre compte intact).
- Écran : `jarvis-portrait.spec.ts` (`isPortraitMark`) ; `jarvis.component.spec.ts` (✦ jamais dite, mesure).

## Notes et décisions (arbitrages réversibles)
- **A10** : la journalisation se fait **côté passerelle**, dans la porte des phrases qui voit déjà chaque ligne : pas de `POST /api/jarvis/portrait/allusions` depuis l'écran (moins de chemins, aucune donnée personnelle qui transite deux fois).
- **A11** : le rattrapage déterministe s'appuie sur les **noms propres** du portrait (précis, peu de faux positifs) ; « spontanée » se décide sur la parole du PO (liste fermée + mots du portrait).
- **A12** : les filets (b) et (c) ne visent que les allusions **spontanées** (critère 4 : une allusion amenée n'est jamais écartée).
