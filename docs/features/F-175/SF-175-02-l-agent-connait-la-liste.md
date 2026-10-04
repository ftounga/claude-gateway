# Mini-spec — F-175 / SF-175-02 — L'agent connaît la liste

## Identifiant
`F-175 / SF-175-02` — feature parente `F-175` *Le fil des attentes* — dépend de **SF-175-01** (mergée).
Branche : `feat/SF-175-02-agent-connait-la-liste`. Statut : `in-progress`. Date : 2026-10-04.

## Objectif
Que l'agent **voie** les attentes ouvertes du poste à chaque tour, les fasse passer à « Demandé »,
et ne **ferme plus** d'autorité : il **propose**, l'utilisateur confirme.

## Comportement attendu

### Cas nominal
1. **Liste jointe au tour (D4)** : là où les outils d'attente sont donnés (droit d'espace), les
   attentes ouvertes (`A_FAIRE`, `DEMANDE`) **du poste** — ou du seul terminal s'il est hébergé —
   sont préfixées à la **consigne du tour**, jamais à la consigne système (préfixe stable F-134 /
   F-171). Le message **persisté** reste la parole de l'utilisateur. Format compact, une ligne par
   attente : état (`DEMANDÉ le jj/mm à X par Y · attend depuis N j` ou `À FAIRE · ouverte depuis N j`),
   terminal d'origine si ailleurs, fermeture proposée le cas échéant, `key=` (ou `id=` sans clé),
   description, ce que ça débloque. **Ce terminal d'abord, puis le reste du poste**, les plus récentes
   d'abord. Bornes : ≤ 30 lignes et ≤ 3 000 caractères ; le surplus est **compté**. Aucune attente
   ouverte → rien (consigne inchangée à l'octet près).
2. **`update_blocker`** (`key` ou `id`, `status` ∈ {A_FAIRE, DEMANDE}, `requested_to?`, `channel?`) :
   fait passer À faire ↔ Demandé. Fermer est refusé (« propose-le avec close_blocker »).
3. **`close_blocker` devient une proposition (D5)** : il pose `proposed_status` (FAIT, ou ANNULE si
   `cancelled`), `proposed_reason` (la parole de l'utilisateur), `proposed_at`. L'attente **reste
   ouverte**. Le résultat d'outil le dit (« reste OUVERTE… ne dis pas qu'elle est fermée »).
4. **Désignation** par `key` (ce terminal d'abord, puis le poste, une ouverte de préférence) ou par
   `id` (lu dans la liste). Une attente **d'un autre poste** ou d'un autre compte est introuvable.
5. **Geste de l'utilisateur** : `POST /workspaces/{wid}/actions/{id}/proposal/confirm` applique la
   proposition (état + raison + `closed_at`) ; `POST …/proposal/dismiss` l'écarte (« Pas encore »),
   l'attente reste telle quelle. Tout changement d'état par l'utilisateur efface la proposition.
6. **Doctrine** (guide des outils, stable) : lire la liste avant d'inscrire, ne pas redemander un
   « demandé », `update_blocker` quand la demande part, `close_blocker` propose.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `key` et `id` absents | résultat d'outil en erreur, le tour continue |
| `id` illisible / clé inconnue / autre poste | « Aucune attente… N'insiste pas » (pas d'exception) |
| `update_blocker` vers FAIT/ANNULE ou statut inconnu | résultat en erreur explicite |
| Attente déjà fermée | « déjà fermée, rien n'a changé » |
| Base indisponible | résultat en erreur ; le tour continue ; la liste jointe est omise (best-effort) |
| confirm / dismiss sur l'attente d'autrui | 404 |
| confirm sans proposition | sans effet (200) |

## Critères d'acceptation
- [ ] La liste est préfixée à la consigne du tour (jamais au système), ce terminal puis le poste,
      bornée 30 lignes / 3 000 car., vide sans attente.
- [ ] `update_blocker` passe À faire ↔ Demandé avec à qui / par où ; refuse la fermeture.
- [ ] `close_blocker` laisse l'attente ouverte et pose la proposition.
- [ ] confirm applique, dismiss écarte ; un geste de statut efface la proposition.
- [ ] Les trois outils sont donnés **ensemble**, sous la même garde d'espace.
- [ ] **ISOLATION** : compte et terminal **du tour** ; un `id` d'un autre poste est introuvable ;
      confirm/dismiss d'autrui → 404.

## Plan de test
- **Unitaires** : `TerminalActionTurnNoteTest` (ordre, format « demandé », bornes, hébergé,
  proposition visible, vide) ; `TerminalActionToolTest` (3 outils, guide, proposition, confirm /
  dismiss, update, id d'un autre poste, clé inconnue, déjà fermée, pannes, isolation).
- **Intégration** : `TerminalActionApiIntegrationTest` — confirm / dismiss de bout en bout + Bob 404.
- Suite backend complète (contexte Spring) + `npm run build`.

## Impacts
- Table `terminal_actions` : + `proposed_status`, `proposed_reason`, `proposed_at` (migration
  `142-terminal-actions-close-proposal.xml`, colonnes nullables).
- Backend : `TerminalActionTurnNote` (nouveau), service (`resolveForAgent`, `agentUpdate`,
  `proposeClose`, `confirmProposal`, `dismissProposal`), outils (catalogue, exécuteur),
  `AtelierChatService` (dispatch des 3 outils, `withPendingActions`), contrôleur (2 routes).
- Front : modèle (`proposed*`) et service (`confirmProposal`, `dismissProposal`) — rendu en SF-175-04/05.

### Préoccupations transversales
Contexte tenant : **oui** — résolution par `id`/`key` à l'échelle du poste. Composants :
`TerminalActionService.resolveForAgent` (filtre `user_id` + même terminal ou même `host_id` du
terminal possédé), `TerminalActionTurnNote` (lecture `user_id` + `host_id` du terminal possédé).
Auth, plans, navigation : non.

## Hors périmètre
Dédoublonnage par sens (SF-175-03) ; rendu des cartes [Confirmer] [Pas encore] (SF-175-05) ;
panneau et bande (SF-175-04).

## Arbitrages (réversibles)
- La liste suit la même garde que les outils : sans droit d'espace, ni outils ni liste.
- `close_blocker` n'a plus de paramètre requis (`key` **ou** `id`), contrôlé à l'exécution.
