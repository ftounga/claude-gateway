# SF-222-01 — Amendement dans l'objectif sans clic, « go » écrit, aucun accord inventé

**Feature :** F-222 · **Statut :** done (2026-10-31) · **Estimation :** 1 j · **Branche :** `feat/SF-222-01-amendement-et-go-ecrit`

## Objectif
Supprimer les attentes fabriquées : un amendement qui garde l'objectif s'applique sans clic, un « go » écrit valide un plan, et l'agent n'inscrit plus d'accord que le PO n'a pas demandé.

## Constat (cadrage F-222, causes 1, 2, 3, 5)
~29 interventions de déblocage en 2,5 jours ; chaque amendement exigeait un clic (guide, note de tour, porte) ; aucun outil ne prenait un « go » écrit ; l'agent inscrivait lui-même des « attends mon accord » puis les respectait comme règles du projet ; F-221 disait « ne fusionne rien sans accord ».

## Comportement
1. `set_subject_plan` reçoit `same_objective` (booléen). Amendement **dans l'objectif** sur un plan validé et courant (aucun autre amendement en attente), en Exécution ou Vérification → nouvelle version **validée d'office**, phase Exécution, événement `PLAN_AMENDED_IN_SCOPE` journalisé, résultat d'outil « appliqué SANS CLIC… Annonce-le en une phrase ».
2. Sans `same_objective` (objectif changé) → amendement à revalider (inchangé). Le **premier plan** d'un chantier garde toujours le clic.
3. Outil **`valider_plan`** (chantier + citation `user_words` exigés, modèle `confirmer_diagnostic`) : valide le plan du chantier, passage en Exécution, focus inchangé.
4. Guide (préfixe stable) : critère « même objectif / objectif changé ; dans le doute, changement d'objectif », et validation écrite. Porte et note de tour : « un clic, ou un « go » écrit → valider_plan ».
5. Doctrine (préfixe stable, `DECIDE_BY_DEFAULT_DOCTRINE`) : « AUCUN ACCORD INVENTÉ » (plans, suites, prompts rédigés, gouvernance) ; une règle rédigée par l'agent ne vaut pas consigne ; l'accord d'un tiers est une attente ; la lecture n'attend jamais. La SEULE EXCEPTION exige que le PO l'ait écrit lui-même.
6. Fil isolé F-221 (message, pas le préfixe) : « fusionne quand relecteur-mr et les tests sont verts, sauf consigne contraire ».

## Erreurs
| Situation | Comportement |
|---|---|
| `valider_plan` sans chantier ou sans citation | refus de l'outil, rien ne bouge |
| `valider_plan` sur un chantier qui n'attend pas de validation | erreur lisible (« Il n'y a pas de plan en attente de validation. »), tour non interrompu |
| `same_objective` sur le premier plan | ignoré : plan à valider (SET) |
| `same_objective` alors qu'un amendement attend déjà | ignoré : amendement à revalider (pas de validation en douce) |

## Critères d'acceptation
- [x] Amendement dans l'objectif en Exécution : v+1 validée, phase Exécution, `awaitingValidation=false`, 1 événement `PLAN_AMENDED_IN_SCOPE`.
- [x] Amendement sans `same_objective` : phase Plan, à valider.
- [x] Premier plan avec `same_objective` : à valider.
- [x] `valider_plan` : refus sans numéro ou citation ; avec, chantier hors focus en Exécution, focus inchangé.
- [x] Doctrine et guide présents dans la consigne (littéraux stables).

## Plan de test
- `SubjectJourneyChantiersIntegrationTest` : `amendmentInScopeAppliesWithoutClick`, `firstPlanKeepsTheClick`, `validatePlanTool`.
- `JourneyToolCatalogGuideTest`, `AtelierChatServiceSystemPromptTest` (doctrine).
- Isolation : l'outil prend `userId`/workspace du tour ; `validatePlan` commence par `requireOwned`.

## Impact
`JourneyToolCatalog`, `JourneyToolExecutor`, `SubjectJourneyService`, `SubjectJourneyEvent`, `JourneyGate`, `JourneyTurnNote`, `AtelierChatService`. Cache : guide, liste d'outils et doctrine changent une fois (littéraux stables). Préoccupations transversales : aucune (pas d'auth, de tenant, de quota, de route). Aucune migration (`type` d'événement en texte, 21 caractères ≤ 32).

## Arbitrage (gate 🟠 réversible)
Le jugement « même objectif » est confié à l'agent (critère écrit dans le guide, « dans le doute = changement ») plutôt qu'à une comparaison automatique des plans : aucune heuristique fiable n'existe sur des titres d'étapes libres. Garde : seulement sur un plan validé et courant, jamais sur le premier plan, toujours journalisé et annoncé. Retour arrière : retirer `same_objective` de la définition d'outil.

## Hors périmètre
Liste blanche des scripts de lecture (SF-222-02) ; clic perdu, chantiers zombies, envois en double (SF-222-03) ; la règle alarm4tech (donnée de gouvernance du PO, signalée).
