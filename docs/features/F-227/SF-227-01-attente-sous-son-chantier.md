# SF-227-01 — Chaque attente rangée sous son chantier (backend)

**Feature :** F-227 · **Statut :** done (2026-10-31) · **Estimation :** 1 j · **Branche :** `feat/SF-227-01-attente-sous-son-chantier` · **Migration :** 180 (pré-assignée)

## Objectif
Une attente née pendant un chantier est rangée sous lui (colonne `terminal_actions.chantier_number`), rattachable à la main, et les attentes anciennes peuvent être proposées à la clôture en lot.

## Constat (cadrage F-227)
64 attentes ouvertes, 6 rattachées (9 %, via `waitsOn`). 25 nées pendant un chantier ouvert sans lien : le vrai défaut. Décision du cadrage : **pas de chantier fictif par défaut**.

## Comportement
1. **Migration 180** : `terminal_actions.chantier_number` (int, nullable) + index `(user_id, workspace_id, chantier_number)`, retour arrière fourni.
2. **Reprise** (changeSet `180-terminal-action-chantier-reprise`) : une attente ouverte (`A_FAIRE`, `DEMANDE`), non fusionnée, sans chantier, née pendant qu'**un seul** chantier du même sujet était ouvert (`opened_at ≤ created_at ≤ closed_at`, ou encore ouvert) lui est rattachée. Ambigu : rien.
3. **À l'inscription par l'agent** (`record_blocker`, issue `RECORDED`) : rangée sous le **chantier du tour** = le fil F-221 (`turnChantier`, même source que le message), sinon le chantier au focus d'un sujet guidé ; rien en Libre. Une attente déjà existante retrouvée par sa clé garde son chantier.
4. **[Rattacher à un chantier]** : `PUT /api/workspaces/{id}/actions/{actionId}/chantier` `{ "chantier": n | null }` ; `n` doit être un chantier OUVERT du sujet (sinon 400), `null` = au fil de l'eau ; 404 chez autrui.
5. **Tableau du client** (`GET /api/runner-hosts/{hostId}/chantiers`) : chaque attente porte `chantierNumber` (si ce chantier est ouvert), `proposedStatus`, `proposedReason` ; `counts.horsChantier` ne compte que celles au fil de l'eau.
6. **Tableau des chantiers** (`GET …/journey`) : chaque chantier ouvert porte `attentes` (ouvertes, rangées sous lui, qu'aucune étape n'attend déjà).
7. **[Revoir les attentes de plus de 14 jours]** : `POST /api/runner-hosts/{hostId}/chantiers/stale-tasks/propose?days=14` (borné 1–365) **propose** la clôture F-175 (« n'a plus lieu d'être », raison « Sans geste depuis N jours : périmée ? ») des attentes ouvertes du poste sans geste depuis N jours. Rien n'est fermé ; la date du dernier geste n'est pas touchée ; une attente déjà proposée ne l'est pas deux fois. Réponse `{ days, proposed }`.

## Erreurs
| Situation | Comportement | HTTP |
|---|---|---|
| Rattacher à un chantier fermé ou inexistant | « Le chantier N n'est pas ouvert sur ce sujet. » | 400 |
| Attente ou poste d'autrui | introuvable | 404 |
| `days` hors bornes | ramené à [1, 365] | 200 |

## Critères d'acceptation
- [x] L'attente inscrite par l'agent dans un sujet guidé porte le chantier du tour.
- [x] Rattacher / détacher ; refus d'un chantier non ouvert ; 404 pour Bob.
- [x] Tableau du client : `chantierNumber` par attente ; `horsChantier` ne compte que le fil de l'eau.
- [x] Parcours : `chantiers[n].attentes`.
- [x] Revue des 14 jours : propose sans fermer, ancienneté intacte, idempotente, 404 pour Bob.
- [x] Reprise : un seul chantier ouvert à la naissance → rattachée ; ambigu, antérieure ou fermée → rien.

## Plan de test
`ClientBoardIntegrationTest` : `attentesUnderTheirChantier`, `staleReviewProposesClosure`, `migrationRepriseIsUnambiguous` (SQL lu dans la migration elle-même). Migration jouée sur H2 (suite) et sur PostgreSQL réel (Testcontainers, `TerminalActionEmbeddingStorePostgresTest` démarre tout Liquibase).

## Impact
`TerminalAction`, `TerminalActionRepository`, `TerminalActionResponse` (+`chantierNumber`), `TerminalActionService`, `TerminalActionToolExecutor`, `TerminalActionController` ; `ClientBoard(Service|Controller)` ; `SubjectJourneyResponse` (+`attentes`), `SubjectJourneyService`, `SubjectJourneyController`. Le Radar n'est pas touché. Préoccupations transversales : contexte tenant — toutes les lectures sous `user_id` + `requireOwned` du terminal ou du poste d'abord ; aucun nouveau mode de résolution du tenant.

## Hors périmètre
Écrans (SF-227-02) ; chantier fictif par défaut (écarté par le cadrage).
