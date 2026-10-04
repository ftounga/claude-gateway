# Mini-spec — F-175 / SF-175-01 — Trois états et la portée poste

## Identifiant
`F-175 / SF-175-01` — feature parente `F-175` *Le fil des attentes* — évolution de F-154 (Terminée).
Branche : `feat/SF-175-01-etats-portee-poste`. Statut : `in-progress`. Date : 2026-10-04.

## Objectif
Donner aux attentes leurs **trois états** (À faire → Demandé → Fait, + Annulé) et leur **portée
poste**, avec l'API qui les liste par poste, change leur état, les édite et les rétablit.

## Comportement attendu

### Cas nominal
1. **États (D1)** : `A_FAIRE`, `DEMANDE`, `FAIT`, `ANNULE`. Migration des valeurs existantes
   `OPEN → A_FAIRE`, `DONE → FAIT`, `CANCELLED → ANNULE`. « Ouverte » = `A_FAIRE` ou `DEMANDE`.
2. **Demandé** porte `requested_at` (posé par le serveur au passage en `DEMANDE`), `requested_to`
   (à qui, ≤ 120) et `channel` (par où : courriel, Teams, ticket… ≤ 60). Retour `DEMANDE → A_FAIRE`
   possible (« pas de réponse, on refait ») : `requested_at` est conservé comme historique tant
   qu'on ne redemande pas ; une nouvelle demande le remplace.
3. **Portée poste (D2)** : `host_id` ajouté, **déduit du terminal** (`workspaces.host_id`) à
   l'inscription ; reprise des lignes existantes par la migration. Un terminal « Hébergé »
   (`host_id` nul) garde sa liste propre.
4. **API** (identité = `CurrentUser`, jamais un paramètre) :

| Méthode | URL | Corps | Réponse |
|---|---|---|---|
| GET | `/workspaces/{wid}/actions?openOnly=` | — | inchangé (ouvert = A_FAIRE + DEMANDE) |
| GET | `/workspaces/{wid}/actions/board` | — | `ActionBoardResponse` |
| POST | `/workspaces/{wid}/actions` | inchangé | action créée (`A_FAIRE`, `host_id` déduit) |
| PATCH | `/workspaces/{wid}/actions/{id}` | `{description?, blocks?, person?, kind?}` | action éditée |
| POST | `/workspaces/{wid}/actions/{id}/status` | `{status, note?, requestedTo?, channel?}` | action |
| POST | `/workspaces/{wid}/actions/{id}/close` · `/cancel` · `/reopen` | inchangés | action |

   `ActionBoardResponse` = `{ hostId, here: Action[], host: ElsewhereAction[], counts: {aFaire,
   demande}, oldestOpenAt }` : `here` = les attentes **de ce terminal** (ouvertes + fermées depuis
   7 jours), `host` = celles **des autres terminaux du même poste** (mêmes règles, avec le nom du
   terminal d'origine), `counts`/`oldestOpenAt` portent sur **tout le poste** (ou le seul terminal
   s'il est hébergé). L'action rendue porte désormais `hostId`, `requestedAt`, `requestedTo`,
   `channel`, `updatedAt`.
5. **Rétablir** (`reopen`) rend l'état d'avant la fermeture : `DEMANDE` si une demande avait été
   faite (`requested_at` non nul), `A_FAIRE` sinon.
6. **Inscription par l'agent** : une action déjà `DEMANDE` sous la même clé rend une issue nouvelle
   `ALREADY_REQUESTED` (« déjà demandé le … à … ») — l'agent ne redemande pas.

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| Statut inconnu / absent | `terminal_action_invalid` | 400 |
| `requestedTo` > 120, `channel` > 60, description vide ou > 300 | `terminal_action_invalid` | 400 |
| Édition d'une action fermée | `terminal_action_invalid` (« rétablissez-la d'abord ») | 400 |
| Terminal ou action d'un autre compte | introuvable | 404 |
| Changement vers l'état courant | sans effet, 200 (jamais une erreur) | 200 |

## Contraintes de validation
`status` ∈ {A_FAIRE, DEMANDE, FAIT, ANNULE} ; `note` ≤ 300 (raison de fermeture pour FAIT/ANNULE) ;
`requestedTo` ≤ 120 ; `channel` ≤ 60 ; bornes F-154 inchangées ; ≤ 50 ouvertes par terminal.

## Critères d'acceptation
- [ ] La migration 141 renomme les états et reprend `host_id` depuis `workspaces` (H2 + PostgreSQL).
- [ ] Une action créée dans un terminal rattaché à un poste porte ce `host_id`.
- [ ] `POST …/status` fait A_FAIRE → DEMANDE (pose `requested_at`, `requested_to`, `channel`),
      DEMANDE → A_FAIRE, → FAIT / ANNULE (avec la note pour raison, `closed_at` posé).
- [ ] `reopen` rend DEMANDE si une demande avait été faite, A_FAIRE sinon.
- [ ] `PATCH` édite une action ouverte ; refuse une action fermée.
- [ ] `board` rend ce terminal + le reste du poste + les compteurs ; un terminal hébergé ne voit que
      sa liste ; les fermées de plus de 7 jours n'y sont plus.
- [ ] `record_blocker` sur une clé `DEMANDE` rend `ALREADY_REQUESTED`.
- [ ] **ISOLATION** : Bob n'obtient que 404 sur le terminal, l'action et le board d'Alice ; le board
      d'Alice ne montre jamais une action de Bob, même sur un `host_id` forgé.
- [ ] Le front compile avec les nouveaux états (libellés « À faire / Demandé / Fait / Annulé »).

## Plan de test
- **Unitaires** (`TerminalActionServiceTest`) : transitions, `requested_at` posé par l'horloge,
  reopen → état d'avant, édition refusée si fermée, statut inconnu, bornes, `ALREADY_REQUESTED`.
- **Intégration** (`TerminalActionApiIntegrationTest`) : status / PATCH / board de bout en bout,
  poste partagé par deux terminaux, terminal hébergé.
- **Isolation** : Bob → 404 sur board / status / PATCH d'Alice.
- **Front** : spec existante adaptée aux nouveaux états.

## Impacts
- Table `terminal_actions` (+ `host_id`, `requested_at`, `requested_to`, `channel`, index
  `(user_id, host_id, status)`), migration `141-terminal-actions-states-host.xml`.
- Backend `atelier.actions` : entité, enum, repository, service, contrôleur, réponses, outil.
- Front : `terminal-actions.models.ts`, panneau (libellé d'état), spec.

### Préoccupations transversales
- Auth / Principal : non. Contexte tenant : **oui** — `host_id` est une nouvelle clé de lecture.
  Composants impactés : `TerminalActionService.board` (lit par `user_id` **et** `host_id`, le
  `host_id` venant du terminal possédé, jamais du client), `TerminalActionQueryService` (inchangé,
  `user_id`), purge compte / projet (inchangées, `user_id`). Plans / limites : non. Navigation : non.

## Hors périmètre
L'agent qui lit la liste et `update_blocker` (SF-175-02) ; dédoublonnage par sens (SF-175-03) ; la
bande, le panneau trois colonnes (SF-175-04) ; cartes (SF-175-05) ; relance et compteurs (SF-175-06) ;
reprise de l'existant (SF-175-07). Re-rattachement des attentes quand un terminal change de poste
(rare ; `host_id` reste celui de naissance — arbitrage tracé).
