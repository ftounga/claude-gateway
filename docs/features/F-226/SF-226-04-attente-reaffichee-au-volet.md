# SF-226-04 — Ce qui attend se réaffiche à la réouverture du volet

**Feature :** F-226 · **Statut :** done (2026-11-01) · **Estimation :** < ½ j · **Branche :** `feat/SF-226-04-question-au-volet` · **Dépend de :** SF-221-03 (volets), F-84 / F-164 (état du tour)

## Objectif
Rouvrir le volet d'un chantier dont le tour attend une autorisation ou une réponse remontre aussitôt l'invite, sans dépendre du flux de rebranchement.

## Constat (code)
À l'ouverture, le volet (`terminal-lane-pane`) lit l'état du tour (`GET …/chat/turn?chantier=N`) puis se rebranche au flux ; l'autorisation et la question n'arrivaient **que** par les apartés `confirm_state` / `question_state` de ce flux. Or le volet n'a pas le repli « par fenêtres » de l'écran principal (F-84 / SF-84-04) : derrière un proxy d'entreprise qui retient le flux SSE (cas du PO chez le client), rien n'arrive et le volet rouvert ne montre ni l'invite ni la question — le tour reste bloqué en silence. L'état du tour portait déjà l'autorisation (`pending`), que le volet ignorait ; il ne portait pas la question.

## Comportement nominal
1. `GET /api/workspaces/{id}/chat/turn[?chantier=N]` porte en plus **`pendingQuestion`** : `{ callId, questions, remainingMs }` (lot tel que posé par l'agent, temps restant calculé par la gateway), ou `null`. Une question expirée n'est jamais rendue.
2. Le volet, dès cette lecture (tour vivant), affiche l'**autorisation** (`pending`) et la **question** (`pendingQuestion`) avec leur échéance réelle, puis se rebranche comme avant.
3. La même demande rejouée ensuite par le flux **ne réinitialise pas** l'invite : la saisie en cours (réponse commencée, refus motivé) est gardée, seule l'échéance est recalée.

## Contrat API (additif)
| Champ | Type | Sens |
|---|---|---|
| `pendingQuestion` | objet \| null | `callId` (string), `questions` (lot JSON, tel quel), `remainingMs` (long) |

Un écran d'avant l'ignore. Un tour vivant **chez un pair** (autre pod) : la question reste portée par le flux relayé (le relais d'état ne la transporte pas — inchangé, voir risques).

## Erreurs
| Situation | Comportement |
|---|---|
| Question/autorisation expirée | non rendue, rien ne s'affiche |
| Tour fini entre-temps | `live=false` : rien ne s'affiche, aucun rebranchement |
| Réponse déjà donnée ailleurs | le flux rejoue `question_resolved` : l'invite se ferme (inchangé) ; un envoi tardif → 409 « n'attend plus de réponse » (inchangé) |
| Volet d'un sujet d'autrui | 404 (inchangé, `requireSteerable`) |

## Critères d'acceptation
- [x] Volet rouvert, tour en attente d'une autorisation : l'invite est là sans aucun événement de flux.
- [x] Idem pour une question structurée (carte « demande »).
- [x] La même question rejouée par le flux garde la réponse en cours.
- [x] Question expirée / tour fini : rien.

## Plan de test
- Backend : `AtelierChatControllerAttachTest` (+2 : la question en attente et son temps restant, JSON non échappé ; question expirée jamais rendue). Suite complète.
- Frontend : `terminal-lane-pane.component.spec` (+3 : réaffichage dès l'état, rejeu sans perte, rien si fini). Suite complète + build.
- Isolation : inchangée (l'état d'un fil de chantier exige `requireSteerable` → 404 chez autrui, déjà testé SF-221-01).

## Impact
`AtelierTurnStateResponse` (+`PendingQuestionView`, constructeur d'avant conservé), `AtelierChatController.turnState` ; `atelier.models` (+`AtelierTurnPendingQuestion`), `terminal-lane-pane.component`. Aucune migration, aucune route nouvelle. Préoccupations transversales : aucune (ni auth, ni tenant, ni plans, ni navigation).

## Arbitrages
- **Lire l'état plutôt qu'ajouter au volet le suivi par fenêtres** de l'écran principal : la cause du symptôme (invite invisible) est réglée par une lecture JSON qui traverse tout proxy ; le direct complet du volet derrière un proxy reste un sujet à part (risque résiduel). Réversible.
- **Écran principal inchangé** : il a déjà le repli par fenêtres qui rejoue les apartés.

## Hors périmètre
Transport de la question dans l'état relayé entre pods ; suivi par fenêtres du volet ; ramener un volet fermé à la main quand son chantier pose une question (la puce F-226 et le tableau le signalent déjà).
