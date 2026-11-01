# SF-222-04 — Correctif du profil offensif

**Feature :** F-222 · **Statut :** done (2026-10-31) · **Estimation :** 1 j · **Branches :** `feat/SF-222-04-correctif-profil-offensif` (backend), `feat/SF-222-04-correctif-profil-offensif-front` (frontend) · **Dépend de :** SF-222-01/02/03 (mergées)

## Objectif
Fermer les trous laissés par SF-222-01→03 : un « go » écrit ne valide que la version lue, sur des mots réellement écrits, jamais dans une course sans utilisateur ; un amendement sans clic ne fait jamais entrer d'étape externe ; « en sommeil » veut dire la même chose partout ; un message ne se perd plus en voulant éviter un doublon.

## Constat (relecture de SF-222-01→03)
1. `valider_plan` validait la version COURANTE (`version=null`) : un amendement posé entre le « go » et l'appel était validé sans avoir été lu. La citation `user_words` n'était pas vérifiée (l'agent pouvait l'inventer), aucun garde n'empêchait l'outil dans une course autonome (aucun utilisateur n'y écrit), et `PLAN_VALIDATED` ne disait pas si la validation venait d'un clic ou d'un écrit.
2. `PLAN_AMENDED_IN_SCOPE` n'était visible nulle part pour l'utilisateur ; et un amendement « même objectif » pouvait ajouter une étape EXTERNE (push, apply, envoi) sans clic.
3. « En sommeil » : 3 jours (Exécution) au tableau des chantiers et dans la note de tour, mais « dormant » à 2 jours au tableau du client et dans le récapitulatif quotidien ; un chantier qui attend un tiers y était compté comme endormi.
4. Envois en double (`atelier.component.ts`, l. 2045/2050) : le dernier envoi était mémorisé AVANT le refus du plafond de terminaux (un renvoi dans les 3 s était avalé) ; la lecture des @-mentions bloquait tous les sujets, pas seulement le sien.
5. Liste blanche des scripts de lecture : une option d'interpréteur groupée (`python3 -Bc '…' voir`) ou un préchargement (`node --require=x.js jira.js voir`) passait pour une lecture ; le verbe était cherché après des options à valeur (`jira.py --mode voir creer` → lecture).

## Comportement
1. **`valider_plan`** exige `chantier`, `version` (la version du plan que l'utilisateur valide) et `user_words`.
   - La citation doit figurer (casse, accents, ponctuation et espaces ignorés) dans ce que l'utilisateur a écrit pendant CE tour (message + précisions) ; sinon refus : « Ces mots ne figurent pas dans ce que l'utilisateur a écrit… ».
   - Refus dans un tour sans utilisateur (course autonome, routine) : « Une course autonome ne valide jamais un plan… ».
   - Version différente de la version courante → refus du service (« Le plan a changé depuis (version N)… »).
   - `PLAN_VALIDATED` porte le **canal** : `… · clic` (bouton) ou `… · écrit « citation »` (outil, citation bornée à 120 caractères).
2. **Amendement dans l'objectif** : si le nouveau plan contient une étape EXTERNE absente du plan validé ou modifiée (titre ou contenu), l'amendement n'est PAS appliqué sans clic : il repasse par la validation (issue `AMENDMENT_EXTERNAL`, résultat d'outil explicite).
   - **Visible** : chaque version d'historique porte son origine (`PLAN`, `AMENDMENT`, `IN_SCOPE`) ; le tableau des chantiers affiche « appliqué sans clic · même objectif » sur les versions `IN_SCOPE`.
3. **« En sommeil » partout = 3 jours, hors attente de tiers** : `SubjectJourneyChantier.STALE_DAYS` (3) est le seuil du tableau des chantiers, de la note de tour, du tableau du client (`app.client-board.dormant-days`, défaut 3) et du récapitulatif quotidien. Un chantier (ou une attente hors chantier) dont « qui débloque » est un tiers (`AUTRUI`) n'est jamais en sommeil : il attend. Libellé « en sommeil depuis N j » ; notification « Des chantiers sont en sommeil ».
4. **Doublons d'envoi** : le dernier envoi n'est mémorisé qu'une fois le message réellement parti (après le plafond) ; la lecture des mentions ne bloque que le sujet concerné.
5. **Liste blanche** : aucune option d'interpréteur avant le script hors une liste sûre (python `-u -B -I -E -s -S -O -OO -q`, bash/sh `-e -u -x`, node : aucune) ; le verbe de lecture est le PREMIER argument après le script.
6. **Guide** (préfixe stable) : `valider_plan` avec version et citation, jamais en autonome ; une étape externe ajoutée ou modifiée exige la validation ; « en sommeil » = 3 jours en Exécution sans geste, hors attente de tiers.

## Erreurs
| Situation | Comportement |
|---|---|
| `valider_plan` sans version | refus de l'outil, rien ne bouge |
| citation absente des mots de l'utilisateur | refus de l'outil, rien ne bouge |
| course autonome / routine | refus de l'outil, rien ne bouge |
| version dépassée | refus lisible, rien ne bouge |
| amendement « même objectif » avec étape externe nouvelle | amendement à revalider (phase Plan) |

## Critères d'acceptation
- [x] `valider_plan` : refus sans version, citation inventée, tour autonome, version dépassée ; succès avec citation présente (accents/casse ignorés) → `PLAN_VALIDATED` « · écrit « … » ».
- [x] Clic [Valider] → `PLAN_VALIDATED` « · clic ».
- [x] Amendement même objectif + étape EXTERNE nouvelle → `AMENDMENT_EXTERNAL`, phase Plan ; sans étape externe nouvelle → appliqué, version d'historique `IN_SCOPE`.
- [x] `stale=false` pour un chantier en Exécution depuis 4 j qui attend un tiers ; tableau du client : dormant à 3 j, jamais pour `AUTRUI`.
- [x] Récapitulatif : un chantier qui dort 2 j ne déclenche plus le rappel ; à 3 j oui ; jamais s'il attend un tiers.
- [x] Front : un envoi refusé par le plafond n'empêche pas le renvoi ; un sujet B s'envoie pendant la lecture des mentions du sujet A ; version `IN_SCOPE` marquée.
- [x] Liste blanche : `python3 -Bc '…' voir`, `node --require=x.js jira.js voir`, `python3 jira.py --mode voir creer` restent des modifications ; les 9 formes de SF-222-02 restent des lectures.

## Plan de test
- Back : `SubjectJourneyChantiersIntegrationTest` (validatePlanTool réécrit : version, citation, canal ; autonome ; amendement externe ; origine des versions ; sommeil hors tiers, isolation Bob → 404), `JourneyToolCatalogGuideTest`, `JourneyRiskClassifierTest`, `JourneyTurnNoteTest`, `ClientBoardServiceTest`/`DormantChantierScannerTest` (seuil 3 j, tiers exclu).
- Front : `atelier.component.spec` (plafond puis renvoi, mentions d'un autre sujet), `terminal-chantiers-board.component.spec` (version sans clic), `client-board.models` (libellé).
- Isolation utilisateur : `requireOwned` en premier sur tous les chemins ; `valider_plan` prend `userId`/workspace du tour, jamais des paramètres.
- Suites complètes back et front, build.

## Impact
Back : `JourneyToolExecutor` (+contexte du tour), `JourneyToolCatalog` (guide, définition), `SubjectJourneyService` (validatePlan + canal, garde externe, origine), `SubjectJourneyController`, `SubjectJourneyPlanVersion` (+`origin`), `SubjectJourneyResponse` (PlanVersion.origin, stale hors tiers), `SubjectJourneyChantier`, `JourneyTurnNote`, `JourneyRiskClassifier`, `AtelierChatService` (contexte du tour), `ClientBoardService`, `DormantChantierScanner`, `PushEvent`. **Migration 183** (`subject_journey_plan_versions.origin varchar(16)`, nullable, rollback = drop).
Front : `atelier.component`, `journey.models`, `terminal-chantiers-board`, `client-board.models`.
Préoccupations transversales : aucune (ni auth, ni tenant, ni plans/limites, ni routage). Cache : guide et définition d'outil changent une fois (littéraux stables).

## Arbitrages (gates 🟠 réversibles)
- **Citation vérifiée par inclusion normalisée** dans les mots du tour (message + précisions) plutôt qu'un jugement sémantique : déterministe ; un « go » écrit dans un tour précédent ne vaut pas (il faut le redire). Retour arrière : retirer la vérification.
- **Tour sans utilisateur** = tour dont la notification de fin est tue (course autonome, routine) : le signal existant, sans nouvelle plomberie.
- **Origine des versions en colonne** (migration nullable) plutôt que lire le journal (sans numéro de chantier) : les versions anciennes restent sans origine (affichage inchangé).
- **Seuil unique 3 j** : le tableau du client passe de 2 à 3 j (propriété toujours réglable).

## Hors périmètre
Retirer la règle alarm4tech (donnée du PO) ; la clôture automatique des chantiers en sommeil ; le dédoublonnage côté serveur.
