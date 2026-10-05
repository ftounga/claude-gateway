# Mini-spec — F-176 / SF-176-06 — La mesure

## Identifiant
`F-176 / SF-176-06` — dépend de **SF-176-01 → 05** (journal `subject_journey_events` alimenté depuis SF-176-01).
Branche : `feat/SF-176-06-mesure`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Mesurer ce que le parcours guidé change — **incidents, relances du PO, retours arrière, avant/après** —
avec ce que les tables portent réellement, et dire explicitement ce qui reste à lire à la main.

## Comportement attendu

### Cas nominal
1. `GET /api/admin/journeys/measure?pivot=AAAA-MM-JJ&days=N` (**administrateur uniquement**, défaut
   14 jours, borne 90) : deux fenêtres `[pivot−N, pivot)` et `[pivot, min(pivot+N, maintenant))`.
2. Par fenêtre, pour le compte de l'administrateur (`user_id`) :
   - les **gestes du parcours** par type (`MODE_CHANGED`, `GUIDED_PROPOSED/ACCEPTED/DECLINED`,
     `PLAN_SET/AMENDED/VALIDATED`, `GATE_BLOCKED`, `DIAGNOSIS_*`, `STEP_UPDATED`, `REOPENED`,
     `CLOSE_PROPOSED`, `CLOSED`) ;
   - les **refus de la porte par classe** (`REVERSIBLE`, `EXTERNE`) ;
   - le nombre de **sujets guidés** et de **vérifications en échec** ;
   - l'**exécution** (`runner_audit`), séparée **guidé / libre** : terminaux actifs, appels, échecs
     d'outil, **retours arrière** (`git revert`, `git reset --hard`, `rollback`, `rollout undo`).
3. Notes : retours arrière par terminal (guidé / libre / avant), échecs pour 100 appels, modifications
   retenues par la porte — et toujours : « à compléter par la lecture humaine : incidents (grille
   F-172) et relances du PO, qu'aucune table ne porte ».

### Cas d'erreur
| Situation | Comportement |
|---|---|
| non administrateur | 403 |
| pivot illisible | 400 `invalid_pivot` |
| aucun sujet guidé après le pivot | note « rien à comparer encore » |

## Critères d'acceptation
- [ ] Gestes, refus par classe, sujets guidés et échecs de vérification comptés sur la fenêtre.
- [ ] Retours arrière et échecs séparés guidé / libre, avant / après.
- [ ] Les incidents et relances du PO sont signalés comme lecture humaine, jamais inventés.
- [ ] **ISOLATION** : les données d'un autre compte ne sont jamais comptées ; endpoint 403 hors admin.

## Plan de test
- **Intégration** : `JourneyMeasureIntegrationTest` (comptes, classes, guidé/libre, avant/après,
  isolation ; 403 / 200 / 400).
- Suite backend complète.

## Impacts
- Aucune migration (journal posé en 145, alimenté depuis SF-176-01).
- Backend : `JourneyMeasureService`, `JourneyMeasureController` (nouveaux).
- Front : aucun (même patron que la mesure F-174 : lecture par l'endpoint d'administration).

### Préoccupations transversales
- Auth : **oui** — garde `AdminService.assertAdmin()` réutilisée telle quelle (aucun changement de
  Principal). Composant impacté : `JourneyMeasureController` seulement.
- Contexte tenant : lectures sous `user_id` de l'administrateur. Plans / navigation : non.

## Hors périmètre
Un écran de mesure ; la catégorisation automatique des incidents.

## Arbitrages (réversibles)
- **Pas d'écran** : la mesure se lit par l'endpoint d'administration, comme celle de la carte (F-174).
- « Retour arrière » = commande de retour en arrière observée dans le journal d'exécution, plus les
  retours en Investigation du parcours — un indicateur, pas une vérité : la lecture humaine tranche.
- Un terminal est « guidé » sur une fenêtre s'il y a un geste du parcours en mode Guidé dans cette fenêtre.
