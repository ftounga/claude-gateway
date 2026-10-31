# SF-224-01 — Les tours coupés comptés, chaque modèle à son tarif

**Feature :** F-224 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-224-01-tours-coupes-et-tarifs`

## Objectif
Le coût calculé ne perd plus les tours coupés et ne surestime plus les sous-boucles : ce qui a été facturé est inscrit, au tarif du modèle réellement appelé.

## Constat (cadrage F-224, périmètre 1 et 3)
- L'inscription d'un tour se faisait en fin de boucle seulement : un tour en échec, interrompu par une exception ou tué par un déploiement ne laissait rien (12 déploiements, 34 messages sans réponse enregistrée la semaine du 24/10).
- L'exploration (Sonnet en prod) et les sous-tâches étaient chiffrées au tarif du modèle du tour (Opus).
- Haiku 4.5, rapporté sous un identifiant daté, retombait sur le tarif de repli.

## Comportement
1. **Tour coupé** : la boucle de `runLoopOnLane` est gardée ; sur une sortie anormale (`RuntimeException` ou `Error`), ce qui a été consommé jusqu'à la coupure (compaction, appels, sous-boucles) est inscrit **une fois** au coût réel (`recordUsage`, parts par modèle comprises) avec une mesure de tour « coupé (Type) », puis la coupure remonte telle quelle. Le chemin normal n'étant pas atteint, pas de double compte. BYOK : rien d'inscrit au quota (inchangé), la mesure seule.
2. **Sous-boucles à leur tarif** : `explore`, sous-tâche de lecture et sous-tâche écrivaine rendent le modèle qui les a servies ; leur consommation va dans la part de ce modèle (`otherModelShares`, F-172) quand il diffère du modèle du tour. Le volume total du tour ne change pas.
3. **Identifiants datés** : `pricingOf` retrouve la ligne de grille d'un identifiant daté (`-YYYYMMDD`), décliné (`-latest`), ou préfixé par une clé de la grille (la plus longue) ; la ligne exacte prime toujours (`claude-opus-5-5` n'est jamais lu `claude-opus-5`).

## Erreurs
| Situation | Comportement |
|---|---|
| L'inscription du tour coupé échoue | journalisée (type seulement), la coupure d'origine remonte intacte |
| Tour coupé avant tout appel | rien d'inscrit |
| Modèle inconnu | tarif de repli, marqué `pricingFallback` (inchangé) |

## Critères d'acceptation
- [x] Fournisseur en panne au 2e appel : le 1er est inscrit une seule fois (7/3 tokens), l'exception remonte.
- [x] Identifiant daté de Haiku 4.5 : son tarif, pas le repli.
- [x] Exploration Sonnet dans un tour Opus 5.5 : la part Sonnet chiffrée au tarif Sonnet.

## Plan de test
`AtelierChatServiceBudgetTest.aCutTurnStillCountsWhatWasSpentOnce`, `ProviderCostCalculatorTest.aDatedModelIdFindsItsOwnRatesNotTheFallback`, `aDelegatedExplorationIsBilledAtItsModelRate` ; suite backend complète. Isolation : aucun accès données nouveau (inscription sous le `userId` du tour, comme avant).

## Impact
`AtelierChatService` (garde de la boucle, `recordCutTurn`, `addDelegatedShare`, modèle des issues de délégation), `ProviderPricingProperties.pricingOf`. Aucune migration. Préoccupations transversales : plans/limites — le décompte du quota inclut désormais les tours coupés (`QuotaService.recordUsage`, seul appelant touché) ; c'est le but (le PO paie ces tokens).

## Hors périmètre
Appels annexes, rapprochement avec la facture (SF-224-02) ; écran (SF-224-03).
