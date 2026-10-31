# SF-227-03 — Correctif prioritaire : la revue ne clôt que ce qu'elle propose, le vrai dernier geste, le plan prime (backend)

**Feature :** F-227 · **Statut :** done (2026-10-31) · **Estimation :** 1 j · **Branche :** `feat/SF-227-03-correctif-attentes` · **Migration :** 182 (pré-assignée) · **Origine :** audit du 31/10 (bugs 1, 2, 3, 5, 6, 8 ambiguës)

## Objectif
Rendre [Clore les N proposées] incapable de fermer autre chose que les propositions « périmée ? » de la revue, fonder « sans geste » sur une vraie date de geste, et ranger les attentes là où le plan et le fil les placent.

## Constat (audit du 31/10, prouvé en prod)
1. **Destructif** : 19 propositions ouvertes dont 15 en FAIT venues de l'agent (F-175) ; [Clore les N proposées] les passait toutes en FAIT, sous un texte « n'avait plus lieu d'être ».
2. « Sans geste » = `updated_at`, réécrit en masse le 24/10 par un traitement : la revue ne propose rien.
3. La revue propose des attentes que le tableau montre sur une ligne d'étape, sans [Clore]/[Garder] : propositions inaccessibles.
4. L'attente créée par l'utilisateur n'est jamais rangée sous un chantier.
5. La reprise 180 ignore `waitsOn` (une attente attendue par l'étape du chantier 7 rangée sous le 3) ; 8 attentes ambiguës restent sans signal.

## Comportement
1. **Migration 182** (`182-terminal-action-gesture.xml`) :
   - `terminal_actions.last_gesture_at` (timestamptz, nullable), repris = le plus récent de `created_at` et `requested_at` ;
   - `terminal_actions.proposal_source` (varchar 8, nullable) : `REVUE` | `AGENT`, repris = `REVUE` pour `ANNULE` + raison « Sans geste depuis … », `AGENT` pour toute autre proposition ;
   - rollback : les deux colonnes retirées.
2. **Dernier geste** : posé (avec `updated_at`) à la création, au changement d'état, à l'édition, à l'échéance, au rétablissement, au rattachement, à la confirmation et à l'écart ([Garder]) d'une proposition, à la clôture/annulation. Jamais par : la proposition de l'agent, la revue, la qualification, la reprise, l'interlocuteur. La revue et `idleDays` / `lastActivityAt` du tableau du client lisent `last_gesture_at`, à défaut `created_at`.
3. **Source de la proposition** : `proposeClose` (agent) pose `AGENT`, la revue pose `REVUE`, toute disparition de la proposition l'efface. Exposée en `proposalSource` sur `TerminalActionResponse` et `ClientBoard.LooseTask`.
4. **Clôture en lot, côté serveur** : `POST /api/runner-hosts/{hostId}/chantiers/stale-tasks/close` `{ "actionIds": [...] }` → ne ferme (ANNULE, raison recopiée) que les attentes **ouvertes du poste, sous `user_id`, dont la proposition est `REVUE` + `ANNULE`** ; toute autre est ignorée et comptée. Réponse `{ closed, skipped }`. Au plus 200 identifiants.
5. **Propositions accessibles** : la revue ne propose que les attentes que le tableau du client montre sur leur propre ligne (avec [Clore]/[Garder]) — jamais celles qu'une étape de chantier montre déjà. Le nombre annoncé = le nombre visible.
6. **Attente de l'utilisateur rangée** : `POST …/workspaces/{id}/actions` accepte `chantier` (le fil affiché) ; un chantier ouvert du sujet → rangée sous lui ; absent → `chantierOfTurn` (même règle que l'agent : le fil, sinon le chantier au focus) ; un numéro non ouvert est ignoré (la création n'échoue pas). `chantierOfTurn` passe dans `TerminalActionService`, l'outil de l'agent le réutilise.
7. **Le plan prime** (`waitsOn`) :
   - à la lecture du tableau du client, une attente attendue par une étape (quel que soit son état) d'**un seul** chantier ouvert est rangée sous lui, quel que soit `chantier_number` ;
   - **reprise au démarrage** (`AttenteChantierReprise`, idempotente, bornée, coupe-circuit `app.atelier.attentes.chantier-reprise.enabled`) : la même règle est écrite en base pour les attentes ouvertes non fusionnées.
8. **Les ambiguës** : une attente au fil de l'eau, sans chantier, d'un sujet qui a des chantiers ouverts déjà ouverts à sa naissance, porte `suggestedChantiers` (numéros) — l'écran propose [Rattacher au chantier N].

## Erreurs
| Situation | Comportement | HTTP |
|---|---|---|
| Clôture en lot d'une attente proposée par l'agent, ou FAIT | ignorée, comptée dans `skipped` | 200 |
| Clôture en lot d'une attente d'autrui / d'un autre poste | ignorée (jamais lue hors `user_id` + poste) | 200 |
| Poste d'autrui (revue, clôture en lot) | introuvable | 404 |
| Plus de 200 identifiants | « 200 attentes au plus par clôture. » | 400 |
| Création avec un chantier fermé | rangée par `chantierOfTurn`, sinon au fil de l'eau | 200 |

## Contraintes de validation
`days` borné [1, 365] (inchangé) ; `actionIds` ≤ 200, nul/vide → `{0,0}` ; `chantier` > 0 et ouvert, sinon ignoré à la création.

## Critères d'acceptation
- [x] Clôture en lot : une proposition AGENT (FAIT ou ANNULE) reste ouverte et proposée ; la REVUE passe ANNULE ; Bob → 404.
- [x] La revue propose une attente dont `updated_at` est récent mais `last_gesture_at` ancien ; pas celle dont le dernier geste est récent.
- [x] [Garder] pose le dernier geste : la revue suivante ne la repropose pas.
- [x] La revue ne propose pas une attente montrée par une étape de chantier.
- [x] `proposalSource` = `REVUE` / `AGENT` exposé ; effacé à la confirmation.
- [x] Création utilisateur avec `chantier` ouvert → rangée ; chantier fermé → ignoré.
- [x] Une attente attendue par l'étape du chantier 7, rangée sous le 3 → rangée sous le 7 (tableau et reprise).
- [x] Une attente née pendant deux chantiers ouverts → `suggestedChantiers` = les deux.
- [x] Migration 182 jouée sur H2 et PostgreSQL (Testcontainers), reprises correctes.

## Plan de test
- `ClientBoardIntegrationTest` : `bulkCloseOnlyClosesReviewProposals`, `staleReviewReadsLastGesture`, `keepIsAGesture`, `staleReviewSkipsStepShownTasks`, `planWaitsOnWins`, `ambiguousAttentesAreSuggested`, `userCreatedAttenteUnderChantier`, isolation Bob (404).
- `AttenteChantierRepriseTest` (intégration) : rangement par `waitsOn`, idempotence.
- Migration : suite complète (H2) + `TerminalActionEmbeddingStorePostgresTest` (Liquibase complet sur PostgreSQL).

## Impact
Tables : `terminal_actions` (+`last_gesture_at`, +`proposal_source`). Endpoints : `POST …/chantiers/stale-tasks/close` (nouveau), `POST …/actions` (+`chantier`). Code : `TerminalAction`, `TerminalActionService`, `TerminalActionResponse`, `TerminalActionController`, `TerminalActionToolExecutor`, `ClientBoard`, `ClientBoardService`, `ClientBoardController`, `AttenteChantierReprise` (nouveau).
**Préoccupations transversales** — contexte tenant : aucun nouveau mode de résolution ; la clôture en lot lit sous `user_id` + poste possédé (`requireOwned` d'abord) ; la reprise écrit sous le `user_id` + `workspace_id` de l'attente et du parcours. Auth, plans/limites, navigation : non touchés.

## Arbitrages (décidés par défaut, réversibles)
- Découpage : SF-227-03 = backend, SF-227-04 = écrans (contrat ci-dessus figé).
- Source de la proposition en colonne plutôt que déduite du texte : le garde-fou destructif ne repose pas sur une phrase.
- Clôture en lot côté serveur (filtrée) plutôt qu'une boucle côté écran : la sûreté ne dépend pas du client.
- Accessibilité des propositions : la revue se limite à ce qui est montré avec [Clore]/[Garder], plutôt que d'ajouter des gestes sur la ligne d'étape.

## Hors périmètre
Écrans (SF-227-04) ; Radar ; chantier fictif ; changer le repli de `chantierOfTurn` sur le focus.
