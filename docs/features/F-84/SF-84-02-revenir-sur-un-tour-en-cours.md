# Mini-spec — F-84 / SF-84-02 — Revenir sur un tour en cours

## Identifiant

`F-84 / SF-84-02`

## Feature parente

`F-84` — Le tour survit à son flux

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-84-02-revenir-sur-un-tour`

---

## Objectif

En rouvrant le terminal, l'écran **se rebranche** sur le tour en cours : il rejoue ce qu'il a manqué
depuis un **curseur**, puis reprend le direct — ni doublon, ni trou.

---

## Comportement attendu

### Cas nominal (mono-pod)

1. L'écran ouvre `GET /workspaces/{id}/chat/attach?cursor=N` (SSE). `N = 0` pour un écran neuf.
2. Le pod cherche le tour vivant de **ce couple `(userId, workspaceId)`**.
3. Trouvé : il émet `attached` `{turnId, cursor, startedAt}`, puis **rejoue** les événements de
   numéro `> N` conservés au tampon, puis passe au direct.
4. Chaque événement porte son numéro dans le champ **`id:` du protocole SSE** — c'est le curseur que
   l'écran renvoie s'il se rebranche à nouveau. Aucune charge utile ne change : un écran qui ignore
   `id:` se comporte exactement comme avant.
5. Le tour se termine : `done` (ou `error`) part comme sur le flux d'origine, puis le flux se clôt.

### Cas nominal (multi-pod)

Le tampon vit **en mémoire du pod qui exécute le tour** (décision PO du 2026-09-12, ADR-016 ; aucune
écriture d'événement en base sur le chemin chaud — F-76 avait déjà écarté cette voie).

1. Aucun tour local : le pod **sonde ses pairs** (`POST /internal/atelier/turn-owner`, diffusé),
   exactement le dispositif de `RunnerRelayBroadcaster` déjà éprouvé pour l'interruption et
   l'autorisation. Le pod propriétaire répond avec **son adresse** (`http://{POD_IP}:8081`).
2. Le pod qui tient le navigateur **relaie** le flux depuis cette adresse
   (`POST /internal/atelier/turn-stream`, NDJSON ligne à ligne, même patron que
   `RunnerRelayClient`), et recopie chaque ligne dans le flux SSE du spectateur.
3. Les numéros d'ordre sont ceux du **pod propriétaire** : un spectateur relayé et un spectateur
   local reçoivent la même suite, avec les mêmes numéros.

### Dégradation — jamais un comportement inventé

Comme le fait `RunnerCallRouter`, on **dégrade vers l'état d'origine** :

| Situation | Comportement |
|---|---|
| Aucun tour vivant ici, relais éteint (mono-pod, dev, tests) | événement `idle` `{live:false}` puis clôture |
| Aucun pair ne détient le tour | `idle` |
| Adresse du pair inconnue (présence non convergée) ou égale à la nôtre | `idle` |
| Pair injoignable, refus `401`, flux coupé | `idle` (et le spectateur peut réessayer) |

`idle` **dit exactement ce qui était vrai avant F-84** : rien de vivant à regarder ici. Aucun état
n'est deviné, aucun tour n'est inventé.

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Utilisateur sans accès Atelier | `error: forbidden` **dans le flux** (jamais un 406 sur un endpoint SSE) | 200 + flux |
| Tour d'un **autre utilisateur** | `idle` — le tour est **introuvable** depuis cette identité | 200 + flux |
| Curseur antérieur à ce que le tampon garde | `truncated` `{fromSeq, droppedThrough}` **avant** le rejeu | 200 + flux |
| Curseur négatif ou absent | traité comme `0` | 200 + flux |
| Plus aucun thread de relais disponible | `error: stream_busy` (mécanique `SseStreamDispatch` inchangée) | 200 + flux |

---

## Critères d'acceptation

1. Un écran qui se rebranche avec `cursor = N` reçoit **exactement** les événements `> N` conservés,
   dans l'ordre, **sans doublon ni trou**.
2. **Deux vues** branchées sur le même tour reçoivent la **même suite** d'événements.
3. Une vue rouverte **ne compte pas comme un flux de plus** : l'attache ne passe pas par le pool des
   flux émetteurs et ne prend **aucune** place au registre `LiveTerminalService`.
4. **Isolation** : se rebrancher sur le projet d'un autre utilisateur rend `idle`, jamais son tour.
5. `GET /workspaces/{id}/chat/turn` dit si un tour est vivant et à quel curseur il en est —
   localement **ou chez un pair**.
6. Multi-pod : un spectateur arrivé sur un autre pod reçoit le flux du pod propriétaire ; sans relais
   possible, il reçoit `idle` et rien d'autre.
7. L'écran se rebranche **tout seul** en rouvrant un projet, et se détache proprement en le quittant.
8. L'interruption explicite arrête toujours le tour (aucune régression F-32 / SF-38-07).

---

## Plan de test minimal

### Unitaires — backend

| Test | Vérifie |
|---|---|
| `LiveTurnTest#leRejeuDepuisUnCurseurNeLivreQueCeQuiAEteManque` (SF-84-01, conservé) | ni doublon ni trou |
| `AtelierChatControllerAttachTest#seRebrancherRejoueCeQuiAEteManquePuisPasseAuDirect` | rejeu + direct |
| `AtelierChatControllerAttachTest#deuxVuesSurLeMemeTourRecoiventLaMemeSuite` | même suite |
| `AtelierChatControllerAttachTest#sansTourVivantLeFluxDitIdleEtSeClot` | dégradation |
| `AtelierChatControllerAttachTest#onNeSeRebrancheJamaisSurLeTourDautrui` | isolation `user_id` |
| `AtelierChatControllerAttachTest#lEtatDuTourDitSilEstVivantEtSonCurseur` | endpoint d'état |
| `RelayTurnSourceTest#relaisEteintNeDitJamaisQuUnTourExiste` | dégradation multi-pod |
| `RelayTurnSourceTest#leRelaisSondeLesPairsEtRecopieLesEvenements` | relais NDJSON |

### Intégration

`AtelierChatApiIntegrationTest` : `GET /chat/attach` sans tour vivant rend `idle` ;
`GET /chat/turn` rend `live:false` ; **isolation** sur un projet d'autrui.

### Frontend

`atelier.service.spec` : `attachTurn` lit `id:` et alimente le curseur ; `idle` ne fait rien casser.
`atelier.component.spec` : rouvrir un projet déclenche l'attache ; quitter l'écran l'abandonne.

---

## Endpoints

| Méthode | Chemin | Nature |
|---|---|---|
| `GET` | `/api/workspaces/{id}/chat/attach?cursor=N` | **nouveau** — SSE, se rebrancher |
| `GET` | `/api/workspaces/{id}/chat/turn` | **nouveau** — JSON, l'état du tour |
| `POST` | `/internal/atelier/turn-owner` | **nouveau** — interne, qui détient le tour |
| `POST` | `/internal/atelier/turn-stream` | **nouveau** — interne, NDJSON du tour |

Aucune table. Aucune migration Liquibase.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Cycle de vie du tour** | `AtelierChatController` (+2 routes, relais inchangé), `AtelierAgentController` (inchangé : le rebranchement vise le terminal), `SseStreamDispatch` (réutilisé tel quel pour le refus), `chatStreamExecutor` (**non utilisé** par l'attache — voir « places ») | traité |
| **Autorisation** | `RunnerConfirmationGate` / `askPermission` / décision HTTP : **inchangés**. Une demande émise pendant l'absence est rejouée comme tout autre événement ; son **temps restant exact** est le sujet de SF-84-03 | traité |
| **Multi-pod** | `PgNotifyRunnerRegistry` (inchangé — c'est le **dispositif** qui est réutilisé, pas le registre des runners : le pod qui exécute un tour n'est pas celui qui tient la socket du runner, comme `RelayPeerResolver` l'établit déjà), `RunnerCallDispatcher` (inchangé), `RelayPeerResolver` + `RunnerRelayBroadcaster` (+1 sonde), `POD_IP` via `RunnerRelayProperties.selfBaseUrl()` | traité |
| **Places et plafond** | `LiveTerminalService` **non touché**. Vérifié : l'identifiant d'onglet vit dans `sessionStorage` (`live-terminal.service.ts`), donc **stable** d'une navigation à l'autre — rouvrir le terminal **renouvelle** la place de l'onglet, il n'en prend pas une seconde. L'attache utilise en outre un exécuteur **distinct** de `chatStreamExecutor` : une lecture n'épuise pas les places d'émission | traité |
| **Frontend** | `atelier.component` (`selectWorkspace` → attache, `ngOnDestroy` → détache), `atelier.service` (`attachTurn`, lecture de `id:`) | traité |

---

## Arbitrages (décidés par défaut, réversibles)

| Décision | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|
| Le curseur voyage dans le champ **`id:` de SSE** | C'est le champ du protocole prévu pour ça ; aucune charge utile ne change, donc aucun écran antérieur n'est cassé | Ajouter `seq` dans chaque charge utile — 12 formats à modifier, et un doublon de vérité | oui |
| L'attache a son **propre exécuteur** (`turnAttachExecutor`) | Une vue rouverte est une **lectrice** : elle n'ouvre aucun tour et ne coûte aucun token. La faire concourir avec les flux émetteurs la ferait refuser (`stream_busy`) au moment précis où l'on veut revoir le travail en cours | Réutiliser `chatStreamExecutor` | oui — c'est un choix de pool |
| Le relais inter-pods est **diffusé** (sonde à tous les pairs) puis **dirigé** (flux vers le propriétaire) | Exactement le raisonnement déjà écrit et éprouvé pour l'autorisation (`RelayPeerResolver`) : aucun annuaire ne dit où tourne une boucle | Un annuaire des tours en base — chemin chaud, écarté par F-76 puis par le PO | oui |

---

## Hors périmètre

- L'autorisation comme **état interrogeable** avec son temps restant exact (SF-84-03).
- Un tour qui **démarre** sans navigateur.
- Retirer l'interruption explicite ; changer le plafond de quatre ou la facturation.
- F-83 (la mosaïque) : SF-84-02 la rend possible, elle ne la livre pas.
