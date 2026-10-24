# Mini-spec — [F-208 / SF-208-01] Le modèle du plan : 40 étapes, dépendances, sous-étapes

## Identifiant
`F-208 / SF-208-01` — Feature parente `F-208` (Le tableau des chantiers) — Statut `in-review` — Créée 2026-10-24
Branche : `feat/SF-208-01-modele-du-plan` — cadrage : `CADRAGE-F-208-tableau-des-chantiers.md` (décisions 1, 2, 3).

## Objectif
Le plan d'un chantier peut compter jusqu'à 40 étapes (au-delà, refus explicite), déclarer les dépendances entre étapes et détailler une étape en sous-étapes cochées avec preuve, sans jamais perdre ces informations à l'amendement.

## Comportement nominal
- `JourneyPlan.MAX_STEPS` = **40**. `set_subject_plan` avec plus de 40 étapes lisibles est **refusé** (résultat d'outil en erreur qui dit le nombre reçu et la limite, et propose les sous-étapes) — plus jamais d'étapes ignorées en silence.
- `set_subject_plan` accepte, par étape, `depends_on` : liste de rangs d'étapes **antérieures** (entiers ≥ 1 et < rang de l'étape). Doublons retirés, triés. Un rang invalide ⇒ refus explicite qui cite l'étape et le rang fautif. Absent ⇒ règle historique (les étapes précédentes), rien n'est deviné.
- Nouvel outil agent `update_sub_steps(step, sub_steps[{title, done, evidence}], chantier?)` : **remplace** la liste des sous-étapes de l'étape (idempotent), 12 au plus, titres ≤ 200, preuves ≤ 500. Mêmes gardes que `update_plan_step` (plan validé courant, Exécution ou Vérification). L'avancement n'est pas un amendement : plan courant et plan validé sont réécrits ensemble. Événement `SUB_STEPS_UPDATED` « étape · faites/total ».
- État dérivé « en cours » : une étape `A_FAIRE` avec au moins une sous-étape faite (aucun nouvel état stocké).
- Persistance : `dependsOn` et `subSteps` ajoutés au JSON du plan **seulement s'ils ne sont pas vides** (les plans existants relus et réécrits à l'identique).
- Amendement (`carryProgress`) : à rang égal et titre égal, les sous-étapes et les dépendances de l'ancienne étape sont reportées si le nouveau plan n'en donne pas ; l'avancement reste reporté si le contenu est inchangé (dépendances incluses dans le contenu).
- Note de tour : « en cours » pour une étape A_FAIRE entamée, « après 2, 3 » pour les dépendances, « sous-étapes 2/5 ». Borne de 3 000 caractères inchangée.
- GUIDE (texte fixe, préfixe stable) : 40 étapes au plus, `depends_on`, `update_sub_steps`.
- `update_sub_steps` rejoint les outils d'organisation permis en mode réponse/plan (`ANSWER_PLAN_TOOLS`), comme `update_plan_step`.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| 41 étapes ou plus | résultat d'outil en erreur « Le plan compte N étapes : 40 au plus… » ; plan inchangé |
| `depends_on` cite une étape ultérieure, elle-même, ou 0 | erreur « Étape M : depends_on ne peut citer que des étapes antérieures (1 à M-1) » |
| `update_sub_steps` hors Exécution/Vérification ou plan non validé | erreur « Aucun plan validé en cours d'exécution » |
| `update_sub_steps` étape inexistante | erreur « Il n'y a pas d'étape N » |
| plus de 12 sous-étapes / sous-étape sans titre | erreur explicite / sous-étape ignorée |
| sujet en Libre | erreur « Le sujet est en mode Libre » |
| chantier non ouvert | erreur « Le chantier N n'est pas ouvert » |

## Critères d'acceptation
- [ ] CA1 Un plan de 40 étapes est posé en entier ; 41 étapes ⇒ erreur au modèle, aucune écriture.
- [ ] CA2 `depends_on` valide est persisté et relu ; invalide ⇒ erreur qui cite l'étape.
- [ ] CA3 `update_sub_steps` remplace la liste, réécrit plan courant et plan validé, marque l'étape « en cours » dans la note de tour.
- [ ] CA4 Un amendement garde sous-étapes et dépendances d'une étape au même titre et au même rang.
- [ ] CA5 Un plan d'avant F-208 (sans `dependsOn`/`subSteps`) est relu et réécrit à l'octet près.
- [ ] CA6 Isolation : l'outil n'agit que sur le terminal du tour (`user_id` du tour, `target` filtré `user_id`/`workspace_id`).
- [ ] CA7 Non-régression : `JourneyGate*`, `JourneyTurnNote*`, intégration parcours/chantiers verts ; suite complète verte.

## Plan de test
- `JourneyPlanTest` : plafond 40 + refus 41, `depends_on` valide/invalide, sous-étapes (bornes, idempotence), aller-retour JSON avec et sans les nouveaux champs, report à l'amendement.
- `JourneyTurnNoteTest` : « en cours », « après 2 », « sous-étapes 1/2 ».
- `SubjectJourneyChantiersIntegrationTest` : `update_sub_steps` de bout en bout (focus et `chantier: N`), refus hors exécution, refus 41 étapes par l'outil.
- Isolation utilisateur : l'exécuteur ne lit aucun identifiant dans l'entrée (tour = terminal possédé) ; `target` filtre `user_id`.

## Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non (aucune nouvelle lecture ; `target` existant filtré `user_id`). Plans / limites : non. Navigation : non.

## Impacts
- `JourneyPlan`, `JourneyToolCatalog` (définitions + GUIDE), `JourneyToolExecutor`, `SubjectJourneyService` (`updateSubSteps`, `carryProgress`), `SubjectJourneyEvent` (`SUB_STEPS_UPDATED`), `JourneyTurnNote`, `AtelierChatService.ANSWER_PLAN_TOOLS`. Aucune table, aucun endpoint.

## Hors périmètre
Historique des versions, plan de tous les chantiers dans l'API, SSE (SF-208-02) ; écran (SF-208-03/04).
