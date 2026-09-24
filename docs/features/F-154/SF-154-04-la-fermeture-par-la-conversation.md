# Mini-spec — F-154 / SF-154-04 — La fermeture par la conversation

## Identifiant
`F-154 / SF-154-04` — feature parente `F-154` — dépend de **SF-154-01**, **02** et **03**

## Objectif
Qu'une action **disparaisse d'elle-même** quand l'utilisateur y répond dans le terminal.

## La demande
> PO : *« Si ça répond à une des demandes que j'avais faites, ça doit disparaître. »*

Sans cela, la liste ne se vide jamais : elle devient un cimetière, et un cimetière ne se regarde
plus.

## La décision de conception
**On ne construit pas un classifieur.** Le modèle lit déjà la conversation — c'est exactement la
règle *Provider-First* : relayer la capacité, ne pas la réimplémenter. L'agent reçoit donc un second
outil, **`close_blocker`**, et le guide lui dit **quand** l'appeler : quand l'utilisateur rapporte
que c'est fait, ou donne l'information qui était demandée.

**La raison conservée est la parole de l'utilisateur**, pas le résumé de l'agent. Une action qui
disparaît sans raison est une action qu'on refait ; une action fermée sur un résumé approximatif est
pire : elle fait croire à un fait qui n'a pas été dit.

## Comportement attendu
1. L'outil **`close_blocker`** ferme une action **par sa clé** — celle qui a servi à l'inscrire.
2. Il accepte aussi **`cancelled: true`** pour le cas où l'utilisateur dit que l'action **n'avait
   pas lieu d'être** : ce n'est pas la même chose que « c'est fait », et les confondre ferait
   mentir l'historique.
3. Le résultat rendu à l'agent **confirme ce qui a été fermé** — pour qu'il en tienne compte dans sa
   réponse, et **ne le répète pas** au tour suivant.
4. Fermer une action **déjà fermée** ou **inconnue** : un résultat qui le dit, **jamais une erreur**
   qui casse le tour.
5. La fermeture est **visible dans le fil** : le résultat de l'outil y apparaît en toutes lettres —
   *« Action close : « … » — vous avez dit : « … » »*.
6. **Le repentir reste possible** : « Rétablir » vit dans le menu (SF-154-03), et l'action rouverte
   y revient à sa place.

| Cas d'erreur | Comportement |
|---|---|
| Clé inconnue | résultat qui le dit ; l'agent ne doit pas insister |
| Action déjà fermée | résultat qui le dit, sans rien changer |
| Base indisponible | résultat en erreur ; le tour continue |
| Outil appelé sans le droit d'espace | l'outil n'est pas donné ; refus nommé s'il est quand même appelé |

## Critères d'acceptation
- [ ] `close_blocker` ferme l'action **ouverte** portant la clé donnée, et conserve **la parole de
      l'utilisateur** comme raison.
- [ ] `cancelled: true` la marque **annulée**, pas faite.
- [ ] Clé inconnue / action déjà fermée → **résultat**, pas exception.
- [ ] L'outil et son guide n'apparaissent **que** sous le droit d'espace, comme `record_blocker`.
- [ ] **ISOLATION** : la fermeture porte sur le compte et le projet **du tour**, jamais sur un
      identifiant lu dans les paramètres.
- [ ] Le guide dit **de ne pas fermer** sur une supposition — seulement sur ce que l'utilisateur a
      effectivement dit.

## Hors scope
Une **carte dédiée** dans le fil (« ✓ Action close … Rétablir » avec son bouton) : elle demande un
type de bloc, son événement de flux, sa persistance et son rendu — une subfeature à part. Ici la
fermeture est lisible **en toutes lettres** dans la trace de l'outil, et « Rétablir » vit dans le
menu. · Toute fermeture **automatique** sans que l'utilisateur ait rien dit.

## Technique
| Élément | Changement |
|---|---|
| `TerminalActionToolCatalog` | l'outil `close_blocker` + le guide étendu |
| `TerminalActionToolExecutor` | l'exécution et ses issues |
| `TerminalActionService.closeByKey(...)` | fermeture par clé, sous isolation |
| `TerminalActionRepository` | lecture par clé déjà présente (SF-154-02) |
| `AtelierChatService` | l'aiguillage (le catalogue et le guide sont déjà branchés) |

## Plan de test
- [ ] Fermeture par clé → statut `DONE`, raison = la parole de l'utilisateur.
- [ ] `cancelled: true` → statut `CANCELLED`.
- [ ] Clé inconnue → résultat qui le dit, aucun écrit.
- [ ] Action déjà fermée → résultat qui le dit, statut inchangé, raison d'origine préservée.
- [ ] Base en panne → résultat en erreur, jamais d'exception.
- [ ] Les **deux** outils sont donnés ensemble sous la garde, aucun sans elle.
- [ ] **ISOLATION** : le compte et le projet visés sont ceux du tour.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | inchangé |
| **Contexte tenant** | **oui** | `TerminalActionToolExecutor` — déjà audité en SF-154-02 — ne lit aucun identifiant dans les paramètres ; `closeByKey` appelle `requireOwned` en premier et filtre `(user_id, workspace_id, dedup_key)`. |
| **Plans / limites** | **oui** | même garde d'espace que `record_blocker` (`SpaceEntitlementService`, Forge/Vigie) : les deux outils sont donnés et retirés **ensemble**, il n'y a qu'un `isOpenFor`. |
| Navigation / routing | non | aucune route |
