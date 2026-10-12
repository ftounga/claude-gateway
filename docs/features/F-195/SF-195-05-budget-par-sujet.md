# SF-195-05 — Budget par sujet

**Feature parente :** F-195 Plusieurs sujets à la fois · **Type :** backend · **Estimation :** 0,5 j

## Objectif
Une interjection prise en compte prolonge le budget du tour au lieu de consommer celui du premier sujet ; un arrêt sur budget dit quelles demandes le tour portait.

## Comportement nominal
- Chaque lot d'interjections pris en compte (à la frontière d'étape ou entre deux appels) ajoute :
  - du **temps** : `app.atelier.interjection-extra-ms` (défaut 600 000 = 10 min), échéance bornée à **2 × budget de tour** ;
  - des **étapes** : `app.atelier.interjection-extra-steps` (défaut 15), plafond borné à **2 × max-iterations**.
- `0` sur l'un ou l'autre = rien n'est ajouté (coupe-circuit, comportement d'avant).
- Le **plafond de dépense** (quota, `max-turn-tokens`) est inchangé.
- Arrêt sur budget de temps ou d'étapes alors que le tour portait plusieurs demandes ⇒ la réponse se termine par « Ce message portait N demandes ; … Relance celles qui n'ont pas abouti : 1) … 2) … » (étiquette d'interjection retirée, 120 caractères au plus par demande). La gateway ne devine pas laquelle a abouti : elle les nomme toutes.
- Journal : cause d'arrêt « budget de temps » reconnue même avec la note.

## Cas limites / erreurs
- Sans interjection : budgets inchangés (non-régression).
- Prolongation demandée au-delà du double : bornée.
- Une seule demande : aucune note ajoutée.

## Critères d'acceptation
1. Plafond 2 étapes + interjection (+3) ⇒ le tour atteint sa réponse finale à la 3e étape.
2. Sans prolongation ⇒ arrêt, réponse avec la note listant « 1) sujet A » et « 2) et aussi le sujet B ».
3. Prolongation +100 ⇒ jamais plus de 4 étapes.
4. Budget 500 ms + interjection (+500 ms) ⇒ réponse finale ; sans interjection ⇒ `BUDGET_REACHED_REPLY`.
5. Note : étiquette retirée, demande tronquée.

## Plan de test
- `AtelierChatServiceBudgetPerSubjectTest` (6 cas) ; suite backend complète (non-régression `AtelierChatServiceBudgetTest`).
- Isolation utilisateur : sans objet (aucun accès données nouveau).

## Impacts
- `AtelierChatService` (boucle : `deadline`/`iterationCap` locaux, note, `stopCause`). Aucune table, aucun endpoint. Préoccupations transversales : aucune (le gate « Plans / limites » de quota n'est pas touché).

## Hors périmètre
- Savoir automatiquement quel sujet a abouti (la réponse du modèle le dit, doctrine SF-195-01).
