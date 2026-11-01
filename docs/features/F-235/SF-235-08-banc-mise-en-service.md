# Mini-spec — F-235 / SF-235-08 — Banc et mise en service (D16, D17)

## Identifiant
`F-235 / SF-235-08` · Feature parente `F-235` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-235-08-banc-mise-en-service`

## Objectif
> Prouver la justesse du cerveau rapide avant de l'allumer : un banc à blanc, jugé par Opus, avec une porte chiffrée ;
> la mise en service reste un geste d'exploitation (coupe-circuit) après un passage réussi en production.

## Comportement attendu
### Cas nominal
- `POST /api/admin/jarvis/fast-brain/bench` `{set?: REAL|PERSONALITY, model?: claude-haiku-5-5|claude-sonnet-5-5}`
  (ADMIN) ⇒ **202** `{runId}` : le passage tourne **en tâche de fond** (plusieurs minutes, ~120 appels).
- **REAL** : les 60 dernières demandes vocales du compte appelant (`atelier_messages`, `via='JARVIS'`, `USER`, sous
  `user_id`), chacune avec le contexte compact reconstruit **aujourd'hui** ; **PERSONALITY** : le jeu des 20
  questions types synthétiques (SF-235-09) avec son contexte fictif.
- Chaque cas passe par `JarvisFastBrainService.bench` : la même décision et les mêmes garde-fous qu'en vrai, **à
  blanc** — ni mémoire de session, ni trace dans le fil, ni registre de délégations, ni synthèse ; le plafond propre ne
  bloque pas le banc ; le coût est inscrit au journal (appel annexe).
- Un **juge** (`app.jarvis.fast-brain-bench.judge-model`, défaut `claude-opus-5-5`, via `AiAgentProvider`) note chaque
  cas par l'outil strict `noter` : route attendue, fait non ancré dit, action non déléguée, creuse, touches d'humour,
  vouvoiement, imitation.
- `GET /api/admin/jarvis/fast-brain/bench/{runId}` ⇒ rapport : cas, jugés, accord de route, actions non déléguées,
  faits non ancrés, part de réponses creuses, **porte** (franchie ou non, raisons) ; **sans recopier les paroles** du
  compte (identifiants de messages seulement). En mémoire : 5 derniers passages par compte.
- **Porte REAL (D16)** : 0 action non déléguée ; 0 fait non ancré dit ; accord ≥ 90 % ; ≤ 5 % de creuses.
  **Porte PERSONALITY (critère 12)** : vouvoiement 20/20 ; ≤ 1 touche d'humour, 0 sur alertes et sujets sensibles ;
  ≤ 3 phrases et ≤ 300 caractères ; aucune imitation ; toute question d'action déléguée.

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Modèle candidat ou jeu inconnu | refus | 400 |
| Non-ADMIN | refus | 403 |
| Passage d'un autre compte / inconnu | introuvable | 404 |
| Juge muet ou en panne | cas « non jugé », la porte échoue | — |

## Critères d'acceptation
- [x] Le banc ne touche ni au fil, ni à la mémoire, ni au registre (test) ; le plafond ne le bloque pas.
- [x] Le candidat est le modèle de l'appel rapide ; le juge est Opus (test sur les requêtes).
- [x] Le rapport ne contient pas les paroles du compte (test).
- [x] Une action non déléguée fait échouer la porte (test) ; le jeu personnalité rejoue 20 questions.
- [x] ADMIN seulement, liste fermée, rapport d'autrui introuvable (tests).

## Plan de test
- Unitaires `JarvisFastBrainBenchServiceTest` (4) ; intégration `JarvisFastBrainDormantApiIntegrationTest` (banc :
  400/404/403, contexte Spring complet) ; `JarvisFastBrainServiceTest` (31) inchangé.

## Tables / endpoints / composants
Aucune table. Endpoints `POST /api/admin/jarvis/fast-brain/bench`, `GET /api/admin/jarvis/fast-brain/bench/{runId}`.
`JarvisFastBrainBenchService` / `Controller` (nouveaux), `JarvisFastBrainService.bench`,
`AtelierMessageRepository.findTop60ByUserIdAndViaAndRoleOrderByCreatedAtDesc`, `application.yml`.

## Préoccupations transversales
- **Auth / Principal** : endpoints ADMIN (`AdminService.assertAdmin`). **Contexte tenant** : demandes lues sous le
  `user_id` du principal, sujets par `findByIdAndUserId`, rapport clefé par compte.
- **Plans / limites** : appels du banc au journal d'usage (`recordAnnex`) ; plafond propre du cerveau rapide non
  appliqué au banc (geste ADMIN borné) ; quota du compte appliqué.

## Mise en service — note d'exploitation (non faite par cette SF)
**Le coupe-circuit reste `false`** dans le code : la porte D16 ne peut être franchie qu'en production, sur les vraies
demandes vocales du PO, après le déploiement de la vague. Déroulé :
1. Déployer (vague) ; `POST /api/admin/jarvis/fast-brain/bench {"set":"REAL"}` puis
   `{"set":"PERSONALITY"}` ; lire `GET …/bench/{runId}` (≈ 2-5 € de juge).
2. Porte franchie ⇒ `APP_JARVIS_FAST_BRAIN_ENABLED=true` (secret/config du déploiement) ; sinon relancer avec
   `"model":"claude-sonnet-5-5"` ; franchie ⇒ `APP_JARVIS_FAST_MODEL=claude-sonnet-5-5`,
   `APP_JARVIS_FAST_MONTHLY_BUDGET_EUR=50`, puis `ENABLED=true` ; les deux échouent ⇒ rester éteint, rapport au PO.
3. Retour arrière immédiat : `APP_JARVIS_FAST_BRAIN_ENABLED=false` (l'écran retombe sur le mode actuel au 503).
4. Mesure J+7 : `GET /api/admin/jarvis/fast-brain/measure?days=7` (SF-235-07).

## Hors périmètre
Basculer `enabled=true` dans `application.yml` (exige le passage du banc en production).

## Arbitrages (par défaut, réversibles)
- Banc **asynchrone** (règle « traitements lourds asynchrones ») avec rapport en mémoire (5 passages par compte).
- Contexte des cas REAL reconstruit à l'instant du banc (l'état historique n'est pas conservé).
- Activation laissée à l'exploitation : décider `enabled=true` sans banc réel violerait D16.
