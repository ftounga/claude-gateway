# SF-224-06 — Les coûts en euros partout, et chaque montant juste et situé

**Feature :** F-224 · **Statut :** done (2026-10-31) · **Estimation :** 1,5 j (back 1 j + front 0,5 j) · **Branches :** `feat/SF-224-06-euros-partout-back`, puis `feat/SF-224-06-euros-partout-front` · **Dépend de :** SF-224-01 → 05 (mergées)

**Origine :** suite proposée par SF-224-05 (inventaire des coûts restés en dollars) + correctifs de justesse relevés sur F-224.

## Objectif
Le PO lit et saisit tous les coûts en euros (routines, plafond d'exécution, vidéos) et chaque montant de l'écran de rapprochement dit ce qu'il couvre, sans jour partiel ni appel annexe ni consommation perdue pour le fausser.

## Comportement

### 1. Euros partout (stockage inchangé, en dollars)
- **Routines** : budgets **saisis en euros** (0,10 – 50 € par exécution, 1 – 2 000 € par mois), convertis en dollars au taux unique (`APP_COST_USD_TO_EUR`, défaut 0,92) et appliqués en dollars comme avant. Requête : `maxRunEur`, `monthlyBudgetEur` (priment sur `…Usd`, gardés pour compatibilité). Vues : `maxRunEur`, `monthlyBudgetEur`, `monthSpentEur` ; exécutions : `costEur`, `capEur`. Outil de l'agent : `budget_eur` (`budget_usd` encore lu).
- **Plafond d'exécution** (`AtelierSpendCap`) : coupe en dollars, **dit** « INCOMPLET — la routine a atteint son budget de 4,60 € pour cette exécution ».
- **Vidéos** : le devis rendu à l'agent est en euros (« Coût estimé : 14,98 à 29,85 € (voix … · agent … · rendu 0 € — votre poste) », coût par tour, seuil de confirmation d'une retouche) ; la fourchette convertie ne se resserre jamais (borne basse au cent inférieur, haute au supérieur). Galerie, fiche et total du mois : `costEur`, `estimateLowEur`, `estimateHighEur`, `spentEur`.

### 2. Rapprochement : le périmètre de chaque montant
- `scopes` (textes) : **facturé** = toute l'organisation Anthropic (toutes ses clés) ; **calculé** = la passerelle (tours + annexes, clé plateforme, BYOK exclu) ; **clé de la passerelle** ; **par clé** (estimation à la grille, pas un montant facturé) ; **aujourd'hui** (partiel).
- **Clé de la passerelle** : `app.cost.anthropic-admin.gateway-key-id` (`APP_ANTHROPIC_GATEWAY_KEY_ID`, un IDENTIFIANT `apikey_…`, pas la clé). Posée : sa ligne est marquée (`gateway: true`), chaque jour porte `gatewayKeyUsd/Eur` et `gatewayGapUsd/Eur` (clé − calculé, périmètre égal), total `gatewayKeyTotalUsd/Eur`. Absente : `gatewayKeyHint` dit comment la poser.
- **« Aujourd'hui » partiel** : le jour UTC courant est marqué `partial`, sans écart, et **hors des totaux** (jours complets seulement).

### 3. Justesse du journal
- **Filtre `kind = 'TOUR'`** sur les lectures qui parlent de tours : coût moyen d'un tour (estimation des courses autonomes, devis vidéo), consommation par client lue à côté du quota, coût et nombre de tours par projet, diagnostic du produit. Le coût par client et le rapprochement gardent les annexes (dépense réelle).
- **`pricingOf` sans préfixe libre** : seuls les suffixes `-AAAAMMJJ` et `-latest` sont retirés ; un modèle absent de la grille (« claude-sonnet-5-1 ») retombe sur le repli signalé, jamais sur le tarif d'un aîné. `pricingOrFallback` pour les lectures d'estimation (diagnostic, suggestions), qui ne peuvent plus lever de NPE sur un modèle inconnu.
- **Consommations perdues** : une exploration ou une sous-tâche qui tombe en route (`AtelierExploration`, `AtelierTask`) remonte avec ce qu'elle avait déjà consommé (`SubLoopFailure`) ; l'appelant l'impute au tour **une fois**, dans la part de son modèle. Une sous-tâche écrivaine achevée dont la restitution échoue garde sa consommation. Le chemin normal et le chemin d'échec sont exclusifs : pas de double compte.

## Erreurs
| Situation | Comportement |
|---|---|
| Budget en euros hors bornes | 400 « Le budget par exécution va de 0,10 € à 50,00 € » / « Le budget mensuel va de 1,00 € à 2 000,00 € » |
| Budget mensuel < budget par exécution (après conversion) | 400 inchangé |
| Taux absent ou ≤ 0 | 0,92 (règle existante) |
| Clé de la passerelle non désignée | pas de colonne passerelle, `gatewayKeyHint` |
| Jour sans relecture par clé | `gatewayKeyUsd` absent (inconnu, pas zéro) |
| Sous-boucle en panne avant tout appel | la cause d'origine remonte, rien d'imputé |
| Passerelle antérieure (champ `…Eur` absent) côté écran | « — », jamais un dollar présenté comme euro |

## Contraintes de validation
Bornes en euros : 0,10 ≤ par exécution ≤ 50,00 ; 1,00 ≤ mensuel ≤ 2 000,00 ; conversion au centime `HALF_UP`. Aucune colonne ni migration : les plafonds restent rangés en dollars.

## Critères d'acceptation
- [x] 4,60 € saisis → 5,00 $ rangés, vue 4,60 € ; 92 € → 100 $ ; 50,01 € refusé avec un message en euros.
- [x] Plafond atteint : « … 0,46 € pour cette exécution » pour un plafond de 0,50 $.
- [x] Devis vidéo : « Coût estimé : 14,98 à 29,85 € … rendu 0 € » pour 16,29 à 32,44 $ ; seuil de retouche « 2,76 € » pour 3 $ ; galerie `costEur` 0,46 pour 0,50 $.
- [x] Rapprochement : aujourd'hui `partial`, sans écart, hors totaux ; hier facturé 80 $ / calculé 50,25 $ / écart 29,75 $ ; `scopes` présents ; clé désignée : `gateway: true`, 52 $ (47,84 €), écart passerelle 1,75 $ (1,61 €).
- [x] Moyenne d'un tour = 3,00 $ avec deux tours (2 $, 4 $) et un appel annexe (0,01 $) ; coût par client garde l'annexe (6,01 $).
- [x] « claude-sonnet-5-1 », « claude-opus-5-9 » → repli signalé ; « claude-opus-5-5-20261001 » → son tarif.
- [x] Exploration (Sonnet) en panne à son 2e appel : son 1er (7/3) imputé une fois, dans la part Sonnet.
- [x] Front : routines saisies et lues en €, vidéos en €, rapprochement avec périmètres, colonne passerelle, « aujourd'hui (partiel) ».

## Plan de test
- Backend : `ProviderCostCalculatorTest.aNewModelSharingAPrefixIsNeverPricedAsItsElder`, `UsageTurnKindFilterIntegrationTest` (4 cas, isolation `user_id` : un autre compte à 100 $ n'entre jamais), `ProviderCostReconciliationApiIntegrationTest` (partiel, périmètres, clé de la passerelle ; 403 inchangé), `AtelierTaskTest` (panne en route / avant tout appel), `AtelierChatServiceExploreModelTest.aSubLoopThatFailsMidWayStillChargesWhatItSpentToItsModelOnce`, `RoutineServiceTest.budgetsAreTypedInEurosAndKeptInDollars`, `AtelierSpendCapTest`, `AtelierChatServiceSpendCapTest`, `VideoQuoteCalculatorTest`, `VideoQuoteIntegrationTest`, `VideoCostTrackerIntegrationTest` ; suite complète.
- Front : specs routines (saisie €, affichage €), vidéo (`video-cost`), carte de rapprochement (périmètres, partiel, passerelle) ; suite complète ; build.
- Isolation : aucune lecture nouvelle sans `user_id` ; le rapprochement reste administrateur (403 testé).

## Impact
- Backend : `ProviderPricingProperties` (`pricingOf`, `pricingOrFallback`, `eur`, `usdOf`), `UsageTurnRepository` (filtres par nature, méthodes par défaut — signatures conservées), `ProviderCostReconciliationService` (+ `application.yml` `gateway-key-id`), `SubLoopFailure` (nouveau), `AtelierExploration`, `AtelierTask`, `AtelierChatService` (catch des sous-boucles, taux du plafond), `AtelierSpendCap`, routines (`RoutineDtos`, `RoutineService`, `RoutineRunService`, `RoutinePlanner`, `RoutineToolExecutor`, `RoutineToolCatalog`), vidéos (`VideoQuoteService`, `VideoGalleryResponse`, `VideoResponse`, `VideoCostTracker`, `VideoController`), `ProductSurveyService`, `SessionSuggestionService`. Aucune table, aucune migration, aucun endpoint nouveau (contrats enrichis).
- Front : `core/models/routine.models`, `atelier-slash-routines`, `atelier-slash-cost-routines`, `terminal-routines-band`, `core/models/videos.models`, `shared/videos/video-cost`, `video-gallery`, `admin/cost/admin-cost.models`, `admin-cost-reconciliation`.
- Préoccupations transversales :
  - **Plans / limites** : ☑ — composants impactés : `RoutineService.apply` (bornes des budgets en €), `RoutinePlanner.capFor` / `RoutineTurnRunner.execute` / `AtelierChatService.capSpend` (inchangés : plafond appliqué en dollars), `QuotaService.recordUsage` (les sous-boucles en panne y imputent désormais leur consommation déjà facturée). Aucune nouvelle porte.
  - Auth / tenant / navigation : aucune.

## Arbitrages (gates 🟠 réversibles, décidés par défaut)
1. **Devise de saisie des plafonds = euro, stockage en dollars** (SF-224-05 l'avait renvoyé au PO) : la demande PO du 31/10 est « les coûts en euros » ; ranger en euros exigerait une migration et figerait un taux. Retour arrière : l'écran renvoie `…Usd` (toujours accepté), aucune donnée à reprendre. Bornes en euros (0,10 – 50 €, 1 – 2 000 €) plutôt que les bornes en dollars converties (0,09 – 46 €) : lisibles. Défauts serveur inchangés en dollars (5 $ = 4,60 €).
2. **Totaux du rapprochement sur jours complets** : aujourd'hui reste affiché mais n'entre plus dans les totaux ni les écarts (la facture du jour arrive en retard). Alternative écartée : garder aujourd'hui dans les totaux avec une mention — l'écart total resterait faux chaque matin.
3. **Clé de la passerelle désignée par configuration** (identifiant, pas la clé) plutôt que devinée par l'indice partiel de la clé : aucune manipulation du secret. Alternative écartée : comparer l'indice `partial_key_hint` à la clé en mémoire.
4. **Coût d'une vidéo en euros converti depuis le montant exact** (6 décimales), pas depuis le montant arrondi au cent en dollars : plus juste ; les euros affichés peuvent différer d'un centime de « dollars arrondis × taux ».

## Hors périmètre
Facturation Stripe (déjà en euros) ; consommation en jetons ; note de diagnostic `PosteToolsMeasureService` ($ avant → $ après, technique) ; une clé API par client (doc `docs/ops/cout-facture-anthropic.md` §3, décision PO).
