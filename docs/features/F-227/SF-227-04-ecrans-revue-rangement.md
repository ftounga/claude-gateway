# SF-227-04 — Écrans : [Clore les N] sûr, un seul vocabulaire, [Rattacher] dans le terminal, « Ce qui attend » par chantier

**Feature :** F-227 · **Statut :** done (2026-10-31) · **Estimation :** 1 j · **Branche :** `feat/SF-227-04-ecrans-revue-rangement` · **Dépend de :** SF-227-03 (mergée)

## Objectif
Aligner les écrans sur le correctif : [Clore les N proposées] ne compte et ne ferme que les propositions de la revue, le même mot partout, l'attente se range depuis le terminal, et le point de situation groupe ce qui attend par chantier.

## Comportement
1. **Tableau du client** : `proposedTasks` = attentes `proposalSource === 'REVUE'` et `proposedStatus === 'ANNULE'` ; le bouton n'apparaît que s'il y en a. [Clore les N proposées] → confirmation `MatDialog` puis **un seul appel** `POST …/stale-tasks/close` avec les identifiants montrés ; bilan « K attentes closes » (+ « · M laissées » si `skipped`).
2. **Une proposition de l'agent** sur le tableau : « Clôture proposée — L'agent pense que c'est fait / que c'est sans objet : « raison » », [Clore] / [Garder] ; une proposition de la revue : « Clôture proposée — Sans geste depuis N jours : périmée ? ».
3. **Panneau des attentes du terminal** : « Fermeture proposée » → « Clôture proposée » ; [Confirmer]/[Pas encore] → [Clore]/[Garder] ; « L'agent pense » seulement si `proposalSource !== 'REVUE'`, sinon la raison seule.
4. **Rattacher depuis le terminal** : l'attente du panneau montre « Chantier N » si rangée, et son menu propose [Rattacher au chantier N] / [Ranger au fil de l'eau] (chantiers ouverts du sujet, lus du parcours) → `PUT …/actions/{id}/chantier`.
5. **Ajout à la main** depuis un fil de chantier : la création envoie `chantier` (le fil affiché).
6. **Attentes ambiguës** (tableau du client) : « Née pendant les chantiers 3 et 5 » + [Rattacher au chantier N] direct par `suggestedChantiers`.
7. **Point de situation (F-225) « Ce qui attend »** : groupé — « Chantier N — titre » pour chaque chantier ouvert qui a des attentes, puis « Au fil de l'eau » ; accord « demandée ».

## Erreurs
| Situation | Comportement |
|---|---|
| Clôture en lot en échec | « La clôture des attentes a échoué. » ; relecture |
| Rattacher refusé | message du serveur en snackbar |

## Critères d'acceptation
- [x] Une proposition AGENT (FAIT) n'est pas comptée dans [Clore les N] ; le bouton est absent sans proposition de la revue.
- [x] [Clore les N] appelle `stale-tasks/close` avec les seuls identifiants REVUE.
- [x] Panneau du terminal : « Clôture proposée », [Clore]/[Garder], pas de « L'agent pense » pour la revue.
- [x] Panneau du terminal : rattacher appelle la route avec le bon numéro.
- [x] Point de situation : attentes groupées par chantier, puis « Au fil de l'eau ».

## Plan de test
Specs : `client-board.component.spec` (filtre REVUE, appel en lot, suggestions), `terminal-actions-panel` (vocabulaire, rattacher), `terminal-situation.component.spec` (groupement). Isolation : routes backend (SF-227-03).

## Impact
`client-board.component.(html|ts)`, `client-board.models`, `client-board.service`, `terminal-actions.service`, `terminal-action` modèle, `terminal-actions-panel`, `attente-card` (boutons [Clore]/[Garder]), `atelier-terminal` (fil affiché + chantiers ouverts passés au panneau), `terminal-situation.component`. Design system : ambre §12 inchangé. Préoccupations transversales : aucune.

## Hors périmètre
Gestes sur la ligne d'étape ; Radar.
