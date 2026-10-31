# SF-222-03 — Le clic de validation ne se perd plus, les chantiers zombies se voient, un message part une fois

**Feature :** F-222 · **Statut :** done (2026-10-31) · **Estimation :** 1 j · **Branche :** `feat/SF-222-03-clic-zombies-doublons`

## Objectif
Fermer les trois défauts restants du cadrage F-222 : la validation perdue (cause 6), les chantiers zombies (cause 7), les envois en double (cause 8).

## Comportement
1. **Clic perdu** (data-platform, chantier 4, 29/10 : « J'ai validé le plan v2 » sans `PLAN_VALIDATED`). Cause probable, même défaut que SF-176-12 : le geste visait « le focus du moment » côté serveur, qui pouvait avoir changé entre l'affichage et le clic ; l'échec ne disait rien (« n'a pas pu être mis à jour »). Correctif : la bande du parcours envoie TOUJOURS le **numéro du chantier affiché** (`…/chantiers/{n}/plan/validate`, de même pour Planifier, Continuer l'investigation, Clore, Pas encore) ; un refus affiche le **message du serveur** (« Le plan a changé depuis (version 3)… ») et relit le parcours.
2. **Chantiers zombies** : un chantier ouvert, hors focus, **en Exécution sans geste depuis 3 jours** (`SubjectJourneyChantier.STALE_DAYS`) est `stale`. Le DTO `OpenChantier` porte `lastActivityAt` et `stale`. Tableau des chantiers : onglet « en sommeil », carte « En Exécution sans activité depuis N jours » + **[Clore]** (geste de l'utilisateur, jamais automatique). Note de tour : les zombies ne sont plus détaillés, une ligne les nomme (« En sommeil … : chantier(s) 3, 6 ») et ils sortent des « acteurs à préciser ». Le tableau du client montrait déjà les chantiers dormants avec [Clore] (F-220) : inchangé.
3. **Envois en double** (5 messages dupliqués, jusqu'à trois fois). Diagnostic sur le code (pas d'accès aux journaux de prod depuis l'agent) : (a) un envoi avec @-mentions garde la saisie pendant la lecture des fichiers, une 2e Entrée renvoyait le même texte ; (b) pendant un tour, un 2e geste rapide partait en précision identique. Correctif : un envoi pendant la lecture des mentions est ignoré, et le même texte vers le même sujet dans les 3 s est ignoré. Le renvoi après coupure (F-131) passe déjà par la vérification « tour actif ? » et n'est pas touché.

## Erreurs
| Situation | Comportement |
|---|---|
| Validation d'une version dépassée | message du serveur, parcours relu, aucune reprise |
| [Clore] échoue | « Le chantier n'a pas pu être clos. », tableau relu |
| Chantier en Plan ou Investigation depuis longtemps | pas « en sommeil » (il attend un geste, il est déjà signalé) |

## Critères d'acceptation
- [x] La validation vise `/chantiers/{n}/plan/validate` quand le chantier affiché est connu.
- [x] Un refus affiche le message du serveur et relit le parcours.
- [x] `stale=true` pour un chantier hors focus en Exécution sans geste depuis ≥ 3 jours ; `false` pour le focus et les autres phases.
- [x] Tableau : onglet « en sommeil », carte datée, [Clore] → `POST …/chantiers/{n}/close` seulement au clic.
- [x] Note de tour : zombies nommés, non détaillés.
- [x] Le même message n'est envoyé qu'une fois dans les 3 s.

## Plan de test
- Backend : `JourneyTurnNoteTest.staleChantiersAreNamedNotDetailed`, `SubjectJourneyChantiersIntegrationTest.staleChantierIsFlagged` (dont isolation : Bob → 404).
- Front : `terminal-journey-resume.spec` (numéro de chantier, message d'erreur), `terminal-chantiers-board.component.spec` (sommeil + [Clore]), `atelier.component.spec` (doublon).

## Impact
`SubjectJourneyChantier`, `SubjectJourneyResponse` (DTO, champs ajoutés), `JourneyTurnNote` ; `journey.models`, `atelier-terminal`, `terminal-chantiers-board`, `atelier.component`. Préoccupations transversales : aucune (endpoints existants, `requireOwned` en premier). Pas de migration (`updated_at` existe).

## Arbitrage (gate 🟠 réversible)
Seuil de sommeil fixé à 3 jours (cadrage : chantiers en Exécution depuis 6 à 10 jours), distinct du « dormant » du tableau du client (2 jours, toutes phases). La porte agrégée n'est PAS modifiée (un zombie l'ouvre toujours) : la refermer changerait le comportement de modifications en cours sans consigne ; signalé en risque.

## Hors périmètre
Clôture automatique (interdite par le cadrage) ; dédoublonnage côté serveur.
