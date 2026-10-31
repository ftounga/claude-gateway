# SF-227-02 — Les écrans : sous son chantier, « Au fil de l'eau », rattacher, revoir les anciennes

**Feature :** F-227 · **Statut :** done (2026-10-31) · **Estimation :** 1 j · **Branche :** `feat/SF-227-02-attentes-ecrans` · **Dépend de :** SF-227-01 (mergée)

## Objectif
Montrer chaque attente là où elle sert : sous son chantier dans le tableau du client et le tableau des chantiers, les autres « au fil de l'eau », avec les gestes [Rattacher] et [Revoir les attentes de plus de 14 jours].

## Comportement
1. **Tableau du client** : une attente rangée sous un chantier montré du sujet s'affiche juste sous la ligne de ce chantier (en retrait, filet gauche) ; les autres (sujet sans chantier, chantier fermé ou reporté) restent dans la section du sujet, renommée **« Au fil de l'eau »** (déjà groupée par sujet, SF-220-08). Le sous-titre dit « N au fil de l'eau ».
2. **[Rattacher au chantier N]** / **[Ranger au fil de l'eau]** dans le menu « … » de l'attente : les chantiers ouverts du même sujet → `PUT …/actions/{id}/chantier`, puis relecture.
3. **[Revoir les attentes de plus de 14 jours]** (barre d'outils) → `POST …/stale-tasks/propose` ; snackbar « N attentes … clôture proposée, à confirmer ». Une attente proposée montre « Clôture proposée — Sans geste depuis N jours : périmée ? » avec **[Clore]** (route F-175 `proposal/confirm`) et **[Garder]** (`proposal/dismiss`). **[Clore les N proposées]** : confirmation `MatDialog`, puis chaque proposition confirmée une à une ; bilan en snackbar.
4. **Tableau des chantiers** (terminal) : la carte du chantier liste « Attentes de ce chantier · N » (état, description, personne).

## Erreurs
| Situation | Comportement |
|---|---|
| Rattacher refusé (chantier fermé entre-temps) | message du serveur en snackbar, relecture |
| Revue en échec | « La revue des attentes a échoué. » |
| Clôture en lot partielle | « K attentes closes · M en échec » |

## Critères d'acceptation
- [x] Attente avec `chantierNumber` d'un chantier montré : sous ce chantier, classe `cb__item--attached`, juste après sa ligne.
- [x] Sinon : « Au fil de l'eau » ; le compteur ne compte que celles-là.
- [x] Rattacher / détacher appellent la route avec le bon numéro (ou `null`).
- [x] Revue : appel, proposition visible, [Garder] et [Clore les N proposées] (avec confirmation).
- [x] Tableau des chantiers : attentes listées sur la carte, rien sans attente.

## Plan de test
Specs `client-board.component.spec` (3 nouveaux + libellé « Au fil de l'eau ») et `terminal-chantiers-board.component.spec` (1). Isolation : routes du backend (SF-227-01, `requireOwned` d'abord).

## Impact
`client-board.component.(html|ts|scss)` (le `<li>` d'une attente devient un gabarit unique `#taskItem`, réutilisé sous les chantiers et au fil de l'eau), `client-board.models`, `client-board.service`, `terminal-actions.service`, `journey.models`, `terminal-chantiers-board`. Design system : ambre §12 (`#FFF8E1`) pour la clôture proposée, `--cg-gold-ink` pour l'état ; confirmation en lot via `MatDialog`. Préoccupations transversales : aucune.

## Hors périmètre
Rattacher depuis le panneau des attentes du terminal (le menu du tableau du client suffit) ; chantier fictif.
