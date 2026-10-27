# Mini-spec — [F-220 / SF-220-06] Reporter, à l'écran

## Identifiant
SF-220-06

## Feature parente
F-220 — Le tableau de bord du client

## Statut
Done

## Date de création
2026-10-27

## Branche Git
`feat/SF-220-06-reporter-ecran`

---

## Objectif
Donner sur la carte du tableau du client le geste « Reporter » (1 jour, 3 jours, 1 semaine), ranger les chantiers reportés à part, et permettre de ne plus les reporter.

## Comportement attendu

### Cas nominal
- **Contrat importé de SF-220-05** : `POST /api/workspaces/{ws}/journey/chantiers/{n}/snooze` `{days}` → `{number, until}` ; `DELETE …/snooze` → 204 ; le tableau porte `chantiers[].snoozedUntil` et `counts.snoozed`.
- Menu ⋮ de la carte : « Reporter d'un jour », « Reporter de 3 jours », « Reporter d'une semaine » (+ « Clore le chantier », existant).
- Un chantier reporté (`snoozedUntil` non nul) **n'apparaît dans aucun groupe** ni dans la sélection ; il est rangé dans une section **« Reportés (k) »** repliée en bas du tableau (bouton qui déplie). Chaque ligne : sujet, chantier, titre, « reporté jusqu'au 30/10 », [Ne plus reporter].
- Après un geste : snackbar (« Chantier 2 reporté jusqu'au 30/10. » / « Chantier 2 de retour dans le tableau. ») et relecture.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Report refusé (chantier fermé entre-temps, jours invalides) | snackbar avec le message du serveur | 400 |
| Terminal d'autrui | snackbar d'erreur générique | 404 |

## Critères d'acceptation
1. « Reporter de 3 jours » appelle la route avec `{days: 3}`, affiche la date de fin, relit le tableau.
2. Un chantier reporté n'est dans aucun groupe ; la section « Reportés (1) » le montre une fois dépliée.
3. « Ne plus reporter » appelle `DELETE` puis relit.
4. Un refus du serveur s'affiche en snackbar.
5. Les chantiers reportés ne sont jamais sélectionnables pour un lancement groupé.

## Périmètre
### Hors scope (explicite)
- Choisir une date libre (trois durées suffisent ; la route accepte 1 à 30 jours).

## Valeurs initiales
| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| section « Reportés » | repliée | se déplie au clic |

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| durée | 1, 3 ou 7 jours (menu) |

## Technique
### Endpoint(s) consommés
| Méthode | URL |
|---------|-----|
| POST | `/api/workspaces/{ws}/journey/chantiers/{n}/snooze` |
| DELETE | `/api/workspaces/{ws}/journey/chantiers/{n}/snooze` |

### Migration Liquibase
- [x] Non applicable

### Composants Angular
- `ClientBoardService.snooze / unsnooze`, `client-board.models.ts` (`snoozedUntil`, `counts.snoozed`, `isSnoozed`, `untilLabel`), `ClientBoardComponent` (menu, section repliée).

## Préoccupations transversales
- Aucune (affichage et deux appels d'un contrat existant ; isolation au serveur).

## Plan de test
### Tests unitaires
- `client-board.models.spec.ts` : `isSnoozed`, `untilLabel`, sélection exclue.
- `client-board.component.spec.ts` : reporter (appel + relecture), section repliée/dépliée, ne plus reporter, refus → snackbar.
### Isolation utilisateur
- [x] Non applicable — serveur (SF-220-05).

## Dépendances
- SF-220-05 — Done (PR #1319). SF-220-03 — Done.
