# Mini-spec — F-84 / SF-84-03 — L'autorisation ne vit plus dans le flux

## Identifiant

`F-84 / SF-84-03`

## Feature parente

`F-84` — Le tour survit à son flux

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-84-03-autorisation-etat-du-tour`

---

## Objectif

Une demande d'autorisation devient un **état du tour**, interrogeable — et non un événement qu'on
rate si l'on n'était pas branché au bon moment.

---

## Comportement attendu

### Cas nominal

1. La boucle pose une demande d'autorisation : le tour l'**enregistre comme son état**
   (`toolUseId`, outil, détail, délai, instant de la demande) **et** la publie, en un seul geste —
   il n'existe pas d'instant où la demande serait publiée sans être l'état du tour.
2. Un écran qui arrive **après coup** et se rebranche la voit **encore en attente** : après le rejeu,
   il reçoit un `confirm_state` portant son **temps restant exact**.
3. La demande est tranchée (ici, ailleurs, ou par expiration) : le tour **retire** son état et publie
   `confirm_resolved` — dans cet ordre, pour qu'un écran qui se branche entre les deux ne trouve
   jamais une attente déjà close.
4. `GET /workspaces/{id}/chat/turn` porte ce même état : `pending` avec son `remainingMs`, ou `null`.
5. Multi-pod : la sonde `turn-owner` rapporte l'attente du pod propriétaire ; le flux relayé
   transporte le `confirm_state` comme tout autre événement.

### Le temps restant vient de la gateway — jamais de l'écran

Règle posée en **SF-47-02**, et c'est ici qu'elle compte le plus : le `confirm_state` porte
`remainingMs`, **recalculé à l'instant de l'envoi** (`timeoutMs - (maintenant - instant de la
demande)`). Un écran qui rejouerait le `confirm_request` d'origine afficherait deux minutes alors
qu'il en reste vingt secondes. Le rejeu donne donc l'événement tel qu'il fut ; le `confirm_state` qui
le suit **corrige** le compte à rebours.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Aucune demande en attente | aucun `confirm_state` ; `pending` vaut `null` — l'absence n'est pas une erreur |
| Demande **expirée** (temps restant ≤ 0) | aucun `confirm_state`, et `pending` vaut `null` : on n'affiche jamais une invite qui ne peut plus être tranchée |
| Demande tranchée pendant le rejeu | l'état a déjà été retiré : aucun `confirm_state` |
| Tour d'un autre utilisateur | le tour est introuvable, donc son attente aussi (SF-84-02) |
| Tour vivant chez un pair | l'attente est celle du pair, relayée telle quelle |

---

## Ce qui ne change pas

- **La décision** passe déjà par HTTP (`POST /workspaces/{id}/chat/confirm`) et ne change pas.
- `RunnerConfirmationGate` : inchangée. Elle reste la seule autorité sur le délai
  (`timeoutMs()`), sur l'attente et sur le refus par expiration. Le silence ne vaut jamais
  autorisation.
- `AtelierChatService.askPermission` : inchangée.
- L'expiration reste **serveur** : l'écran affiche un temps restant, il ne décide de rien.

---

## Conséquence à écrire, pas à appliquer

**SF-73-04** (porte de confirmation désarmée par défaut, décidée en urgence le 2026-09-12) devient
**levable** : le motif de ce désarmement était qu'une demande partie dans un flux mort n'était jamais
vue, et ce motif disparaît. **Cette subfeature ne la lève pas** — c'est une décision du PO, qui se
prend après vérification en production.

---

## Critères d'acceptation

1. Un écran qui se rebranche **pendant** une attente la voit encore en attente.
2. Le `confirm_state` porte le **temps restant exact**, recalculé côté gateway — jamais le délai
   d'origine.
3. Une attente **expirée** ou **tranchée** ne produit aucun `confirm_state`.
4. `GET /chat/turn` porte `pending` (avec `remainingMs`) quand une demande attend, `null` sinon.
5. La **décision HTTP** est inchangée, et l'expiration reste décidée par la porte.
6. Multi-pod : une attente portée par un pair est visible depuis un autre pod.
7. **Isolation** : l'attente d'un autre utilisateur n'est jamais visible.
8. `RunnerConfirmationGate` n'est pas modifiée.

---

## Plan de test minimal

### Unitaires

| Test | Vérifie |
|---|---|
| `LiveTurnApprovalTest#uneDemandeEnAttenteEstUnEtatDuTour` | l'état existe et se lit |
| `LiveTurnApprovalTest#unEcranArriveApresCoupVoitLattenteAvecSonTempsRestant` | le cas du cadrage |
| `LiveTurnApprovalTest#leTempsRestantEstRecalculeJamaisLeDelaiDorigine` | SF-47-02 |
| `LiveTurnApprovalTest#uneDemandeTrancheeNestPlusUnEtat` | retrait à la résolution |
| `LiveTurnApprovalTest#uneDemandeExpireeNestPlusAffichee` | temps restant ≤ 0 |
| `AtelierChatControllerAttachTest#lEtatDuTourPorteLattenteEtSonTempsRestant` | endpoint d'état |
| `AtelierChatControllerAttachTest#lattenteDunPairEstVisibleDepuisUnAutrePod` | multi-pod |

### Intégration

`AtelierTurnRelayApiIntegrationTest` : `turn-owner` rapporte l'attente du propriétaire.

### Isolation utilisateur

Couverte par SF-84-02 (clef `(userId, workspaceId)`) — une attente n'est atteignable que par le tour
qui la porte.

### Frontend

`atelier.service.spec` : `confirm_state` alimente l'invite avec **son** temps restant.
`atelier.component.spec` : une demande posée pendant l'absence est visible au retour, et son compte à
rebours part du temps restant, pas du délai d'origine.

---

## Composants impactés

| Fichier | Nature |
|---|---|
| `atelier/live/PendingApproval.java` | **nouveau** — l'attente comme état |
| `atelier/live/LiveTurn.java` | publication et retrait de l'attente, `confirm_state` au branchement |
| `atelier/live/RemoteTurnSource.java` | l'état distant porte l'attente |
| `atelier/dto/AtelierTurnStateResponse.java` | champ `pending` |
| `atelier/AtelierChatController.java` | le relais enregistre l'attente en publiant |
| `runner/relay/AtelierRelayController.java` | `turn-owner` rapporte l'attente |
| `runner/relay/RelayTurnSource.java` | lecture de l'attente distante |
| `frontend` — `atelier.service.ts` | `confirm_state` → invite avec son temps restant |

Aucune table. Aucune migration Liquibase.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Autorisation** | `RunnerConfirmationGate` **non modifiée** (seule autorité du délai et du refus par expiration) ; `AtelierChatService.askPermission` **non modifiée** ; la **décision HTTP** (`/chat/confirm`, `/agent/confirm`, relais `/internal/runner/confirm`) **non modifiée** ; le compte à rebours de l'écran reçoit désormais un temps restant **exact** au lieu du délai d'origine | traité |
| **Cycle de vie du tour** | `AtelierChatController` (enregistrement de l'attente au fil du relais) ; `AtelierAgentController` **inchangé** — la porte F-33 vit chez le fournisseur, pas dans `RunnerConfirmationGate`, et l'unifier mélangerait deux durées de vie (c'est déjà la position d'`AtelierRelayController`) ; `SseStreamDispatch`, `chatStreamExecutor` inchangés | traité |
| **Multi-pod** | `RelayTurnSource` + `AtelierRelayController` (l'attente voyage dans la sonde et dans le flux) ; `PgNotifyRunnerRegistry` et `RunnerCallDispatcher` inchangés | traité |
| **Places et plafond** | `LiveTerminalService` non touché ; une attente n'est pas un flux | sans objet |
| **Frontend** | `atelier.service` (un `else if` de plus, routé vers l'invite existante) ; `atelier.component` **inchangé** — `showConfirmation` sait déjà partir d'un délai annoncé par la gateway (SF-47-02) | traité |

---

## Hors périmètre

- **Lever SF-73-04** : décision du PO, après vérification en production.
- Changer le délai d'expiration, ou qui décide de l'expiration.
- Changer la décision HTTP.
- Un tour qui démarre sans navigateur ; le plafond de quatre ; la facturation ; F-83.
