# Mini-spec — F-140 / SF-140-01 — La mesure qui décide

## Identifiant
`F-140 / SF-140-01` — feature parente `F-140`

## Objectif
Savoir si l'application **apprend vraiment** : mesurer le nombre d'appels d'outils qu'il faut pour
répondre, et voir s'il **baisse** à mesure que la carte grossit.

## Ce qui existe déjà — et qu'on ne refait pas
Vérification faite avant d'écrire : la **croissance** est déjà mesurée et affichée (F-93 / SF-93-02)
— *« depuis le 2 septembre, la carte est passée de 4 à 16 faits »*, plus les derniers gains. Cette
partie du cadrage de F-140 est **déjà livrée**, et cette subfeature ne la réécrit pas.

Ce qui manque est la mesure que l'audit a désignée comme **le critère de réussite** de F-136 et
F-137 :

> *« le nombre d'appels d'exploration par tour — s'il baisse à mesure que la carte grossit,
> l'application apprend vraiment ; sinon, on le sait. »*

Sans elle, la promesse de tout ce chantier reste **invérifiable**, et une régression future
passerait inaperçue.

## Comportement attendu
1. Pour un poste, on rend : le nombre de tours et d'appels d'outils sur les **7 derniers jours** et
   sur les **30 derniers**, et le **ratio** de chacun.
2. Le sens est écrit : le ratio récent **plus bas** que le ratio long signifie que l'agent cherche
   moins pour répondre.
3. Aucun tour sur la période ⇒ **aucun ratio** (on ne divise pas par zéro, et on n'invente pas).
4. La mesure est **lue**, jamais calculée par l'écran — un chiffre recalculé ailleurs finirait par
   en contredire un autre.

| Cas d'erreur | Comportement |
|---|---|
| Aucun tour | ratios `null`, et l'écran dit « pas encore mesurable » |
| Poste sans machine | même forme, tout à zéro |
| Poste d'un autre compte | 404, comme toutes les routes de gouvernance |

## Critères d'acceptation
- [ ] `GET /api/governance/hosts/{hostRef}/learning` rend tours, appels et ratios sur 7 et 30 jours.
- [ ] Sans tour sur la fenêtre, le ratio est `null` — jamais `0`, jamais une division par zéro.
- [ ] Seuls les tours et les appels **de ce poste** sont comptés.
- [ ] Un compte ne lit jamais la mesure d'un poste d'un autre.
- [ ] L'écran de la carte affiche la mesure, et dit quand elle n'est pas encore disponible.

## Hors scope
La croissance des faits (**déjà livrée**, F-93) · un historique graphique · toute alerte automatique.

## Technique
| Élément | Changement |
|---|---|
| **`HostLearningService`** *(nouveau)* | compte tours et appels par fenêtre |
| **`HostLearningView`** *(nouveau)* | le DTO |
| `GovernanceHostController` | `GET /{hostRef}/learning` |
| `RunnerAuditRepository` | comptage par poste et par période |
| `AtelierMessageRepository` | comptage des tours par poste et par période |
| `governance.component` | l'affiche sous la carte |

Aucune table, aucune migration.

## Plan de test
- [ ] Les comptes portent sur le bon poste et la bonne fenêtre.
- [ ] Sans tour ⇒ ratio `null`.
- [ ] Isolation : deux comptes, deux postes.
- [ ] L'écran affiche la mesure et son absence.

## Préoccupations transversales
| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | Les deux comptages filtrent `user_id` **et** `host_id`, et le poste passe par `GovernanceHostScope` comme toutes les routes voisines. |
| Plans / limites | non | lecture seule, aucun quota |
| Navigation / routing | non | une route de lecture de plus sous un préfixe existant |
