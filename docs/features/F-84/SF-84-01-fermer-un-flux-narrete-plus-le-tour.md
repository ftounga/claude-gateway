# Mini-spec — F-84 / SF-84-01 — Fermer un flux n'arrête plus le tour

## Identifiant

`F-84 / SF-84-01`

## Feature parente

`F-84` — Le tour survit à son flux

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-84-01-tampon-de-tour`

---

## Objectif

Un envoi SSE qui échoue **détache le spectateur** au lieu d'interrompre le tour : les événements du
tour passent par un **tampon ordonné attaché au tour**, dont l'émetteur n'est qu'un consommateur.

---

## Comportement attendu

### Cas nominal

1. `POST /workspaces/{id}/chat/stream` (idem `/agent/stream`) ouvre un **tour vivant**
   (`LiveTurn`) dans un registre en mémoire, clé `(userId, workspaceId)`.
2. Chaque notification de la boucle (`text`, `action`, `output`, `progress`, `plan`,
   `confirm_request`, `confirm_resolved`, `agent`, `status`, `action_result`, `done`, `error`) est
   **publiée dans le tampon** : elle reçoit un numéro d'ordre (`seq`) strictement croissant, est
   conservée, puis poussée à **chaque abonné**.
3. L'émetteur SSE de la requête est un **abonné** parmi d'autres.
4. Si `emitter.send()` lève (`IOException` / `IllegalStateException`), **cet abonné est retiré** et
   son émetteur complété. La publication continue, le tour continue.
5. Le tour se termine sur `done` / `error` ; le tour vivant est alors **fermé** (retiré du registre,
   abonnés restants complétés).

### Ce qui arrête un tour — liste fermée

| Cause | Inchangée ? |
|---|---|
| Le tour a fini (réponse finale) | oui |
| Le plafond (budget de tour / quota) | oui |
| L'interruption explicite (F-32 / SF-38-07, `POST .../interrupt`) | **oui — geste inchangé** |
| Un navigateur qui part | **non : ne l'arrête plus** |

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `emitter.send` échoue (client parti) | abonné détaché, tour poursuivi, aucun log d'erreur (cas nominal) |
| Tous les abonnés partis | le tour continue jusqu'à son terme ; les événements restent au tampon |
| Erreur de pré-vol (quota, forbidden, byok…) | publiée comme événement `error`, puis tour fermé — inchangé |
| Tampon plein | voir « Bornes mémoire » |
| Deuxième tour ouvert sur le même projet | le tour précédent est fermé (abonnés complétés) avant l'ouverture — un projet n'a qu'un tour vivant |

### Bornes mémoire (exigence explicite du cadrage)

Le tampon d'un tour est **doublement borné** :

- **500 événements** au plus ;
- **512 000 caractères** de charge utile JSON au plus.

Au dépassement, les **plus anciens** événements sont retirés et la borne haute retirée est retenue
(`droppedThrough`). Conséquence **dite, jamais devinée** : un abonné qui demande un rejeu depuis un
curseur antérieur à `droppedThrough` reçoit d'abord un événement `truncated`
(`{fromSeq, droppedThrough}`) — le rejeu de SF-84-02 s'appuiera dessus. Aucune conservation illimitée,
aucun tour de 30 étapes qui fait fuir la mémoire du pod.

---

## Critères d'acceptation

1. Un tour dont le flux SSE est **fermé brutalement** (le premier `send` lève) **va jusqu'au bout** :
   la boucle métier exécute toutes ses étapes et rend son résultat. Vérifié **côté serveur**.
2. Le même comportement vaut pour les **deux** contrôleurs : `AtelierChatController` **et**
   `AtelierAgentController` (même mécanique, mêmes `StreamAbortedException` — les deux sont traités).
3. `StreamAbortedException` n'existe plus comme signal d'arrêt : plus aucune classe du paquet
   `atelier` ne la déclare.
4. Deux abonnés sur le même tour reçoivent **la même suite** d'événements, dans le même ordre.
5. Le tampon ne dépasse jamais ses bornes ; au dépassement, les plus anciens partent et
   `droppedThrough` avance.
6. L'**interruption explicite** arrête toujours le tour (aucune régression F-32 / SF-38-07).
7. Isolation : le registre est clé par `(userId, workspaceId)` — un tour n'est jamais trouvable
   depuis l'identité d'un autre utilisateur.

---

## Plan de test minimal

### Unitaires

| Test | Vérifie |
|---|---|
| `AtelierChatControllerTurnSurvivalTest#leTourVaJusquAuBoutQuandLeFluxEstFermeBrutalement` | **LE test qui prouve la feature** — rouge avant, vert après |
| `AtelierAgentControllerTurnSurvivalTest#leRunVaJusquAuBoutQuandLeFluxEstFermeBrutalement` | idem, second contrôleur |
| `LiveTurnTest#deuxAbonnesRecoiventLaMemeSuite` | même suite, même ordre |
| `LiveTurnTest#unAbonneEnEchecEstDetacheSansArreterLaPublication` | détachement, pas interruption |
| `LiveTurnTest#leTamponEstBorneEnNombreEtEnTaille` | bornes + `droppedThrough` |
| `LiveTurnRegistryTest#leTourDunAutreUtilisateurNestJamaisTrouve` | isolation `user_id` |
| `LiveTurnRegistryTest#ouvrirUnNouveauTourFermeLePrecedent` | un tour vivant par projet |

### Intégration

`AtelierChatApiIntegrationTest` existant : aucune régression sur le flux nominal (le `done` arrive).

### Isolation utilisateur

Couverte par `LiveTurnRegistryTest` (clé composite) et par l'inchangé `requireOwned` en tête de
boucle.

---

## Composants impactés

### Backend

| Fichier | Nature |
|---|---|
| `atelier/live/TurnEvent.java` | **nouveau** — un événement numéroté |
| `atelier/live/LiveTurn.java` | **nouveau** — le tampon ordonné + les abonnés |
| `atelier/live/TurnSubscriber.java` | **nouveau** — un consommateur (interface) |
| `atelier/live/SseTurnSubscriber.java` | **nouveau** — l'émetteur SSE comme consommateur |
| `atelier/live/LiveTurnRegistry.java` | **nouveau** — les tours vivants du pod |
| `atelier/AtelierChatController.java` | le relais publie au tampon ; `StreamAbortedException` retirée |
| `atelier/AtelierAgentController.java` | idem |

### Hors de ce lot

`SseStreamDispatch` (le refus au plafond de flux est antérieur au tour et ne change pas) ;
`chatStreamExecutor` (le tour tourne toujours sur ce pool — il n'est pas question ici de démarrer un
tour sans navigateur, hors périmètre).

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Cycle de vie du tour** | `AtelierAgentController` (relais + 8 `sendX`), `AtelierChatController` (relais + 9 `sendX`), `SseStreamDispatch` (inchangé : refus avant tout tour), `chatStreamExecutor` (inchangé) | traité |
| **Autorisation** | `RunnerConfirmationGate` (inchangée — SF-84-03), `AtelierChatService.askPermission` (inchangée), décision HTTP (inchangée) ; seul change le fait qu'un `confirm_request` **survive au départ du navigateur** au lieu de tuer le tour | traité |
| **Multi-pod** | aucun changement dans ce lot : le tampon est local au pod qui exécute (SF-84-02 traite le spectateur distant) | sans objet ici |
| **Places et plafond** | `LiveTerminalService` **non touché** : une place reste liée à un onglet ouvert, pas à un tour | sans objet ici |
| **Frontend** | aucun changement : les événements et leur ordre sont identiques au fil | sans objet ici |

---

## Hors périmètre

- Se rebrancher sur un tour en cours (SF-84-02).
- L'autorisation comme état interrogeable (SF-84-03).
- Un tour qui **démarre** sans navigateur.
- Retirer l'interruption explicite ; changer le plafond de quatre ou la facturation.
- F-83 (suspendue).
