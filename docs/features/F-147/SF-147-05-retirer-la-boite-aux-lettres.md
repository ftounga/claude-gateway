# Mini-spec — F-147 / SF-147-05 — Retirer la boîte aux lettres

## Identifiant
`F-147 / SF-147-05` — feature parente `F-147`

## Objectif
Que plus rien ne **relève le dossier de dépôt** de sa propre initiative : ce qui est déposé depuis
l'écran est traité **tout de suite** (SF-147-01) et rattrapé **au démarrage** s'il l'a fallu
(SF-147-06). Le dossier reste ; la boîte aux lettres disparaît.

## Le défaut
> *« Je ne veux pas de mécanisme où derrière on va positionner des fichiers. Après, on ne sait pas
> quand le runner va venir prendre, de manière asynchrone. »*

La synchro du soir relève le dossier (`RadarDepositCollector`, F-100 / SF-100-05) : tout fichier qui
s'y trouve est transcrit puis remonté **en échanges Radar**, à une heure que personne ne choisit. C'est
exactement le mécanisme refusé — et, depuis SF-147-02, il produit en plus le **mauvais résultat** : des
preuves Radar là où l'on veut désormais une **réunion**.

## Ce qu'on ne supprime pas
**Le dossier.** Il est la zone d'arrivée du transfert par morceaux (F-104 / SF-104-04) : le supprimer
casserait le geste que le PO veut garder. Ce qui part, c'est le **relevé automatique**.

## Comportement attendu
1. La synchro du soir ne relève **plus** le dossier de dépôt.
2. Le dépôt depuis l'écran est **inchangé** : transfert par morceaux, transcription immédiate, réunion.
3. La reprise au démarrage (SF-147-06) est **inchangée** : elle ne prend que les dépôts venus de l'écran.
4. Un fichier posé **à la main** dans le dossier n'est plus jamais traité — et c'est le but.
5. Le compte rendu de synchro ne parle plus du dépôt : il ne promet rien qu'il ne fait.

| Cas d'erreur | Comportement |
|---|---|
| Runner d'une version antérieure | il continue de relever ; rien ne casse, la gateway n'en dépend pas |
| Dossier contenant d'anciens fichiers non traités | ils restent en place, intouchés ; aucun n'est perdu |

## Critères d'acceptation
- [x] La collecte du Radar **ne contient plus** le relevé du dossier de dépôt.
- [x] Le dossier est **toujours créé** et annoncé au démarrage (zone d'arrivée).
- [x] Le dépôt depuis l'écran fonctionne **à l'identique** (non-régression).
- [x] La reprise au démarrage fonctionne **à l'identique** (non-régression).
- [x] Le message d'accueil ne promet plus un relevé à la prochaine synchro.

## Hors scope
La suppression du dossier (**jamais** : c'est la zone d'arrivée) · le retrait du code du collecteur
lui-même, gardé tant que d'anciens postes peuvent l'utiliser — **seul son branchement** est retiré.

## Technique
| Élément | Changement |
|---|---|
| `TeamsTools.radarCollector()` | ne chaîne plus `RadarDepositCollector` |
| `TeamsTools.withRadarDeposit` | le message d'accueil dit la vérité nouvelle |
| `RadarDepositCollector` | **conservé** (code mort côté branchement, vivant côté tests) — il redeviendra utile si un relevé explicite est un jour demandé |

Aucune table, aucune migration, aucune route.

## Plan de test — suite runner complète, **1345 verts**
- [x] Le branchement retiré ne casse aucun test existant du Radar ni de la synchro.
- [x] Dépôt depuis l'écran (`RadarDepositReceiverTest`, 15) et reprise (`RadarDepositResumeTest`, 6) verts.
- [x] `RadarDepositCollector` et ses tests restent en place : le code n'est pas jeté, seul son
      branchement automatique disparaît.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | rien ne change dans les chemins d'accès |
| Plans / limites | non | une transcription automatique **en moins** par synchro : du temps de calcul rendu au poste |
| Navigation / routing | non | aucun écran |
