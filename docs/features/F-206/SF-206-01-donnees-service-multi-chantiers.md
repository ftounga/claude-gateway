# Mini-spec — F-206 / SF-206-01 Données, migration et service multi-chantiers

## Identifiant
`F-206 / SF-206-01` — Feature parente : F-206 « Plusieurs chantiers ouverts dans un même sujet ».
Statut : `ready` — 2026-10-16 — Branche `feat/SF-206-01-chantiers-ouverts-donnees`.

## Objectif
Un sujet guidé peut porter plusieurs chantiers **ouverts** en même temps (ouvrir un chantier ne clôt plus les autres) ; un seul est le **focus** (celui affiché), et chaque geste peut viser un chantier par son numéro.

## Modèle (arbitrage de mise en œuvre, réversible)
- `subject_journey_chantiers` porte désormais l'état de **chaque** chantier : `status` (`OPEN` | `CLOSED`), `phase`, `phase_changed_at`, plan courant (`plan_json`, `plan_version`), plan validé (`validated_plan_json`, `validated_version`, `plan_validated_at`), diagnostic (`diagnosis`, `diagnosis_evidence`, `diagnosis_confidence`, `diagnosis_proposed_at`), `close_proposed_at`, `updated_at`. `closed_at` devient nullable (un chantier ouvert n'a pas de date de clôture). Unicité `(workspace_id, number)`.
- `subject_journeys` garde le mode, la proposition, et le **chantier en focus** (`chantier_number` = focus) dont il reste la **copie de travail** : chaque écriture du focus est recopiée (miroir) dans sa ligne `OPEN`. Ce choix évite de réécrire la quinzaine de lecteurs existants (porte, note, Cap, Radar, sujets_etat, mesure) qui lisent la ligne unique.
- **Migration** : colonnes ajoutées ; les archives existantes deviennent `CLOSED`. La ligne courante d'un sujet guidé ouvert **est** son chantier OPEN (focus) ; sa ligne miroir est écrite à la première écriture (pas de génération d'UUID en SQL, portable H2/PostgreSQL — même doctrine que la migration 147).

## Comportement nominal
1. **Ouvrir un chantier à côté** : `POST /workspaces/{id}/journey/chantiers` `{title}` (geste utilisateur) — et [Passer en guidé] d'une proposition faite alors que le sujet est déjà guidé — ouvre le chantier `max(numéros)+1` en Investigation, **sans clore** les autres ; il devient le focus. Le précédent focus reste OPEN, intact.
2. **Basculer** : `POST /journey/chantiers/{n}/focus` met le chantier ouvert `n` au focus (son état est rechargé tel quel). Idempotent sur le focus courant.
3. **Gestes par chantier** : `POST /journey/chantiers/{n}/plan/validate` `{version}`, `/diagnosis/confirm`, `/diagnosis/dismiss`, `/close`, `/close/dismiss` — même règles qu'aujourd'hui, appliquées au chantier `n`, **sans changer le focus**. Les routes historiques (`/journey/plan/validate`…) visent le focus.
4. **Clore** : le chantier `n` est archivé (`CLOSED`, `closed_at`, plan validé final). Si c'était le focus et qu'il reste des chantiers ouverts, le focus passe au plus récent d'entre eux et le sujet **reste Guidé** ; sinon, comme avant : retour en Libre (SF-176-07).
5. `GET /journey` rend en plus `chantiers[]` (chantiers ouverts, numéro croissant) : `number, title, openedAt, phase, phaseLabel, focus, planVersion, validatedVersion, awaitingValidation, diagnosisPending, closeProposed, done, total, executing`. `chantier` (existant) = le focus. `closedChantiers` compte les seuls `CLOSED` ; `GET /journey/chantiers` ne liste que les clos (inchangé).
6. Le service expose les surcharges `setPlan / submitDiagnosis / updateStep / reopenInvestigation (…, Integer chantier, …)` (défaut = focus) — consommées par les outils en SF-206-02.

## Cas d'erreur
| Situation | Réponse |
|---|---|
| `n` n'est pas un chantier ouvert du sujet (inconnu, clos) | 400 `journey_invalid` « Le chantier n n'est pas ouvert. » |
| Ouvrir un chantier à côté alors que le sujet n'est pas guidé | 400 — passer d'abord en Guidé (le menu ouvre le premier) |
| Terminal d'autrui (toute route) | 404 (requireOwned d'abord) |
| Validation d'une version dépassée du chantier `n` | 400 « Le plan a changé depuis… » (inchangé) |

## Contraintes de validation
Titre ≤ 200 caractères (tronqué, saut de ligne aplati) ; `n` entier ≥ 1 ; version entière ou absente.

## Critères d'acceptation
- [ ] Deux chantiers ouverts : ouvrir le 2 laisse le 1 en Exécution, plan validé intact.
- [ ] Basculer sur 1 recharge son plan et sa phase ; rebasculer sur 2 idem.
- [ ] Valider le plan du chantier 2 alors que le focus est 1 : le 2 passe en Exécution, le focus reste 1.
- [ ] Clore le focus avec un autre ouvert : focus = l'autre, mode Guidé ; clore le dernier : Libre.
- [ ] `closedChantiers` ne compte que les clos ; la liste des clos n'expose aucun ouvert.
- [ ] Parcours existant (un seul chantier, routes historiques) : comportement inchangé (tests F-176 verts).
- [ ] Bob : 404 sur toutes les nouvelles routes du terminal d'Alice.

## Plan de test
- Intégration `SubjectJourneyApiIntegrationTest` (ajouts) : ouvrir à côté, basculer, valider par chantier, clore focus avec/sans autre ouvert, chantier inconnu → 400, isolation Bob sur chaque route nouvelle.
- Non-régression : toute la suite F-176/F-196 existante.
- Migration : démarrage H2 de la suite (Liquibase) + précondition d'unicité.

## Impacts
- Tables : `subject_journey_chantiers` (colonnes + unicité), `subject_journeys` inchangée (sens de `chantier_number` = focus).
- Endpoints : `GET /journey` (champ additif `chantiers`), nouveaux `POST /journey/chantiers`, `POST /journey/chantiers/{n}/focus|plan/validate|diagnosis/confirm|diagnosis/dismiss|close|close/dismiss`.
- Composants : `SubjectJourney` (liste transitoire des autres ouverts), `SubjectJourneyChantier`, `SubjectJourneyChantierRepository`, `SubjectJourneyService`, `SubjectJourneyController`, `SubjectJourneyResponse`.
- Préoccupations transversales : Auth ☐ ; **Tenant ☑** — composants qui résolvent le terminal du parcours : `SubjectJourneyController` (CurrentUser + requireOwned sur chaque route, y compris `/chantiers/{n}/…`), `SubjectJourneyService.forTurn` (terminal du tour), `SubjectsStateService` (lecture par lot, inchangée) ; toute lecture de `subject_journey_chantiers` filtre `(user_id, workspace_id)`. Plans/limites ☐ ; Navigation ☐.

## Hors périmètre
Outils/GUIDE/porte/note (SF-206-02), Cap (SF-206-03), écran (SF-206-04).
