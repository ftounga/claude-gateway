# Mini-spec — F-84 / SF-84-06 — Un message envoyé pendant un tour devient une précision

## Identifiant

`F-84 / SF-84-06`

## Feature parente

`F-84` — Le tour survit à son flux

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-84-06-precision-pendant-le-tour`

---

## Objectif

Un message envoyé sur un projet dont le tour tourne encore n'ouvre **jamais** un second tour : il
devient une **précision** ajoutée au tour vivant, lue par le modèle à l'étape suivante — le
comportement de Claude Code.

---

## Contexte

**Décision du PO (2026-09-13)** : « Ajoute comme précision ». Jusqu'ici, `POST /chat/stream` sur un
projet dont le tour tourne **remplace** le tour (`LiveTurnRegistry.open`, avertissement journalisé
depuis SF-84-04) : l'ancienne boucle continue à l'aveugle, et deux boucles ont tourné en parallèle
en production (12:30:31 → 12:31:46, constat de SF-84-04).

Deux défauts s'ajoutent, relevés en lisant le code :

- **L'écran ne sait pas préciser** : `AtelierTerminalComponent.submit()` refuse tout envoi pendant un
  tour ; le chemin `steer` de SF-39-19 (`AtelierComponent.steer`) est inatteignable depuis le
  terminal.
- **Une précision peut se perdre** : la file vit dans `AtelierChatService.pendingSteers`, vidée à
  l'ouverture de chaque tour ; déposée pendant la rédaction finale, elle disparaît sans un mot. Elle
  n'est ni persistée, ni annoncée.

---

## Comportement attendu

### Cas nominal

1. **La file vit dans le tour vivant.** `LiveTurn` porte la file des précisions (ordre de dépôt,
   5 au plus). Déposer publie `steer_queued` (`steerId`, `text`, `queuedAt`) dans le tampon du tour :
   toute vue — rebranchement, mosaïque, pair relayé — la voit.
2. **Injection à l'étape suivante.** Au début de chaque itération (frontière sûre, après les
   résultats d'outils de l'itération précédente), la boucle prend **toutes** les précisions en
   attente, dans l'ordre : chacune est ajoutée comme message utilisateur, **persistée** (`USER`,
   dans `atelier_messages`, à sa place chronologique entre la demande et la réponse), puis annoncée
   par `steer_applied` (`steerId`, `step` = numéro de l'étape qui la lit, à partir de 1).
3. **Deux portes d'entrée, une seule règle.**
   - `POST /chat/steer` (l'écran sait qu'un tour tourne) : rend `200 {steerId, turnId}`.
   - `POST /chat/stream` alors qu'un tour tourne (l'écran ne le sait pas — retour sur l'écran,
     rebranchement retenu par un proxy) : **aucun nouveau tour**. La précision est déposée, le flux
     reçoit l'aparté `steered` (`steerId`, `turnId`, `cursor`, `startedAt`) puis le **rejeu complet**
     du tour et son direct, jusqu'à sa fin.
   Le choix « précision ou nouveau tour » est **atomique** dans `LiveTurnRegistry` : deux envois
   simultanés ne peuvent pas ouvrir deux tours.
4. **Précision arrivée pendant la rédaction finale.** Quand la boucle rend son résultat, le tour
   **prend la première précision restante ou se scelle**, en un seul geste sous son verrou :
   - file vide ⇒ scellé : toute précision ultérieure est refusée par ce tour (et l'envoi ouvre un
     tour neuf, normalement) ;
   - sinon ⇒ `done` est publié avec `followUp: true`, puis `steer_followup` (`steerId`), et un
     **tour de suite** part aussitôt dans le même tour vivant, avec la première précision comme
     demande ; les suivantes restent en file et sont lues à l'étape 1. L'écran le dit.
5. **Précision pendant une autorisation.** Elle est déposée et annoncée ; l'attente d'autorisation
   est **inchangée** (ni accord, ni refus). Elle est lue à l'étape qui suit la décision.
6. **Écran.** Pendant un tour de la boucle maison (terminal de projet, de poste, Teams — un seul
   composant), le champ reste actif et le bouton dit **« Préciser »**. La précision apparaît
   aussitôt dans le fil avec « en attente de l'étape suivante », puis « prise en compte à l'étape
   N », ou « ouvre un tour de suite ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `POST /steer` alors qu'aucun tour ne tourne (ou tour scellé) | `409 no_live_turn` ; l'écran envoie la précision comme **nouveau message** dès la fin du tour | 409 |
| 6ᵉ précision en attente | `409 too_many_steers` (`/steer`) ; `error: too_many_steers` dans le flux (`/stream`), **le tour n'est pas touché** | 409 / 200 SSE |
| Projet d'autrui (`/steer`) | `404` (`requireOwned` d'abord) | 404 |
| Tour d'un autre utilisateur sur le même projet | introuvable (clef `(userId, workspaceId)`) : l'envoi ouvre le tour de l'appelant, jamais une précision chez autrui | 200 SSE |
| Message vide | `400` (validation `@NotBlank`) | 400 |
| Tour **interrompu** ou en **erreur** avec des précisions en file | scellé ; `steers_dropped` (`steerIds`) publié ; l'écran marque « non prise en compte — tour arrêté » | 200 SSE |
| Tour chez un pair (multi-pod) | sonde des pairs puis envoi **ciblé** au détenteur, qui rend son reçu (`steerId`). Personne ne l'accepte ⇒ `409 no_live_turn` (`/steer`) ou nouveau tour (`/stream`) | 200 / 409 |

---

## Critères d'acceptation

- [x] CA1 — Une précision déposée pendant l'étape N est présente dans la requête fournisseur de l'étape N+1, **une seule fois**, après les résultats d'outils ; `steer_applied` porte `step = N+1`.
- [x] CA2 — Plusieurs précisions sont injectées, persistées et annoncées **dans l'ordre de dépôt**.
- [x] CA3 — Chaque précision injectée est persistée (`USER`) entre la demande et la réponse du tour.
- [x] CA4 — Précision déposée pendant l'appel qui rend la réponse finale : `done(followUp=true)`, `steer_followup`, puis un second `chatStreaming` avec la précision comme demande, dans le **même** tour vivant ; `done(followUp=false)` final.
- [x] CA5 — Précision déposée pendant une autorisation en attente : l'attente reste en attente, n'est ni accordée ni refusée ; la précision est lue ensuite.
- [x] CA6 — **Reproduction SF-84-04** : un `POST /chat/stream` reçu pendant un tour vivant du même utilisateur n'appelle **pas** `chatStreaming` une seconde fois ; son flux reçoit `steered` puis le rejeu ; aucun avertissement de remplacement n'est écrit.
- [x] CA7 — Isolation : un `POST /chat/stream` de BOB sur le même projet ne touche pas le tour d'ALICE (aucune précision chez elle, son tour continue).
- [x] CA8 — Après scellement, une précision est refusée (`no_live_turn`) et un envoi ouvre un tour neuf.
- [x] CA9 — Tour interrompu avec précision en file : `steers_dropped`, aucun tour de suite.
- [x] CA10 — Écran : pendant un tour local, le bouton dit « Préciser », l'envoi appelle `steerChat` ; la précision s'affiche « en attente de l'étape suivante » puis « prise en compte à l'étape N » ; `steer_followup` ⇒ « ouvre un tour de suite » et le terminal reste en tour ; `409 no_live_turn` ⇒ envoyée comme message à la fin du tour.
- [x] CA11 — Écran : `steered` sur un envoi ⇒ le message devient une précision, le tour est reconstruit par le rejeu, et la fin recharge le fil.
- [x] CA12 — Aucune couleur hors `DESIGN_SYSTEM.md`.

---

## Périmètre

### Hors scope (explicite)

- Le parcours **Managed Agents** (`/agent/stream`, bac à sable) : autre moteur ; pendant un tour de
  ce moteur, l'envoi reste refusé comme aujourd'hui.
- Modifier ou retirer une précision déjà déposée.
- Le texte jeton par jeton (SF-84-05).
- La mosaïque en lecture seule : elle reçoit les nouveaux événements et les ignore.
- L'ordre **exact** au rejeu des tours suivants : la précision persistée est relue comme message
  utilisateur **avant** la trajectoire d'outils du tour (la trajectoire est rejouée d'un bloc juste
  avant la réponse, SF-39-03). Structure valide pour le fournisseur ; l'ordre fin est gardé à
  l'écran et dans le tour vivant.

---

## Valeurs initiales

Non applicable — aucune entité créée.

## Contraintes de validation

| Champ | Obligatoire | Bornes | Règle |
|---|---|---|---|
| `message` (précision) | Oui | 1 – 32 000 caractères | même règle que tout message (`AtelierChatRequest`) — **l'ancienne borne de 4 000 caractères disparaît** : un message envoyé pendant un tour *est* une précision (décision PO), il ne peut pas être refusé pour sa longueur alors que le même texte serait accepté hors tour |
| précisions en attente | — | 5 | au-delà : `too_many_steers` |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---|---|---|---|
| POST | `/api/workspaces/{id}/chat/stream` | JWT, droit Atelier | tour vivant ⇒ précision + aparté `steered` + rejeu ; événements `steer_queued`, `steer_applied`, `steer_followup`, `steers_dropped` ; `done.followUp` |
| POST | `/api/workspaces/{id}/chat/steer` | JWT, droit Atelier | `204` → `200 {steerId, turnId}` ; `409 no_live_turn` |
| GET | `/api/workspaces/{id}/chat/attach` | JWT, droit Atelier | mêmes nouveaux événements ; une fenêtre ne s'arrête pas sur `done(followUp)` |
| POST | `/api/internal/atelier/steer` (relais) | secret partagé | réponse `{accepted, steerId}` ; dépose dans le `LiveTurn` local |

### Tables impactées

`atelier_messages` — **écriture** d'une ligne `USER` par précision injectée (aucun changement de
schéma).

### Migration Liquibase

- [x] Non applicable

### Composants

- Backend : `live/LiveTurn` (file, scellement, événements), `live/LiveTurnRegistry` (`openOrSteer`
  atomique), `AtelierChatController` (`relay` : précision / tour de suite / abandon ; `/steer`),
  `AtelierProgressListener` (`takeSteers`, `onSteerApplied`), `AtelierChatService` (injection +
  persistance ; file et relais retirés), `runner/relay/AtelierRelayController`,
  `RelayInterruptTarget`, `RunnerRelayBroadcaster.broadcastSteer`, `NoLiveTurnException` +
  `GlobalExceptionHandler`.
- Frontend : `AtelierService` (événements, `steerChat` typé, fenêtres), `AtelierComponent`
  (précision, états, tour de suite, renvoi différé), `AtelierTerminalComponent` (« Préciser », état
  de la précision), `atelier.types` / `atelier.models`.

### Préoccupations transversales

- Auth / Principal : **non**.
- Contexte tenant : **oui** — le tour est trouvé par `(userId, workspaceId)`. Composants vérifiés :
  `LiveTurnRegistry.find/open/openOrSteer`, `AtelierChatController.stream/steer/attach/turnState`,
  `AtelierRelayController.steer/turnOwner`, `RelayTurnSource`. Aucun ne résout le tenant autrement.
- Plans / limites : **oui** — un tour de suite est un `runLoop` : quota vérifié et consommation
  comptée comme tout message ; aucune place F-70 prise (même flux). Composants vérifiés :
  `QuotaService.assertWithinQuota/recordUsage` (inchangés, appelés par `runLoop`),
  `LiveTerminalService` (non appelé), `AtelierTurnBudget` (par exécution de boucle).
- Navigation / routing : **non**.

---

## Plan de test

### Tests unitaires

- [x] `LiveTurnSteerTest` — dépôt publié `steer_queued` ; ordre ; plafond 5 ; `takeSteers` vide la file ; `pollFollowUpOrSeal` scelle sur file vide ; dépôt refusé après scellement / fin ; l'attente d'autorisation n'est pas modifiée par un dépôt.
- [x] `LiveTurnRegistryTest` — `openOrSteer` : tour vivant ⇒ précision, pas de remplacement ; tour scellé ⇒ tour neuf ; autre utilisateur ⇒ tour neuf pour lui, rien chez l'autre.
- [x] `AtelierChatServiceTest` — précision lue à l'étape suivante, une fois ; ordre ; persistée `USER` ; `onSteerApplied(step)`.

### Tests d'intégration

- [x] `AtelierChatControllerSteerTest` — CA4 tour de suite ; CA5 autorisation ; CA6 renvoi au retour ⇒ pas de second tour ; CA7 isolation ; CA8 scellement ; CA9 interruption ; `/steer` 200 / `no_live_turn`.
- [x] `atelier.service.spec` — routage des nouveaux événements, `done.followUp`, fenêtre qui continue après `done(followUp)`.
- [x] `atelier.component.spec` — CA10, CA11.
- [x] `atelier-terminal.component.spec` — « Préciser », envoi autorisé pendant un tour, libellés d'état.

### Isolation workspace

- [x] Applicable — CA7 (`AtelierChatControllerSteerTest`) et `LiveTurnRegistryTest`.

---

## Dépendances

### Subfeatures bloquantes

- SF-84-01 à SF-84-04 — `done`.

### Questions ouvertes impactées

- Aucune. La question laissée ouverte par SF-84-04 (refus ou précision) est **tranchée par le PO**.

---

## Notes et décisions

- **D1 — La file quitte le service pour le tour vivant.** Elle n'a de sens que tant qu'un tour
  existe ; le service est un singleton, et sa file vidée « à l'ouverture du tour » était exactement
  ce qui perdait une précision tardive.
- **D2 — Tour de suite plutôt que prolongation.** Prolonger la boucle après une réponse finale
  mélangerait deux réponses dans un seul message persisté ; la mini-spec suit la consigne du PO (« un
  tour de suite, et l'écran le dit »).
- **D3 — Interruption et erreur n'ouvrent pas de tour de suite.** L'interruption est le geste qui
  arrête vraiment (F-84 cadrage §5) ; relancer derrière elle le contredirait. La précision est dite
  « non prise en compte », jamais perdue en silence.
- **D4 — `steered` rejoue depuis 0.** L'écran qui envoie sans savoir qu'un tour tourne n'en a rien
  vu ; il reçoit tout, comme un rebranchement.

### Décisions techniques prises pendant le dev

- **D5 — Précisions prises APRÈS les arrêts subis.** La boucle regarde interruption, budget de temps
  et plafond de consommation, **puis** prend les précisions juste avant l'appel fournisseur : une
  précision annoncée « prise en compte » part réellement au modèle. Celles qui restent sont rendues
  au tour vivant (tour de suite, ou `steers_dropped`).
- **D6 — Précision envoyée au pair détenteur, pas diffusée.** L'ancien `broadcastSteer` (SF-39-19)
  est retiré : une diffusion ne rend pas de reçu, et l'écran a besoin du `steerId` du seul pod qui
  exécute. `RelayTurnSource.steerRemoteTurn` sonde puis poste à l'adresse du propriétaire ;
  `RelayInterruptTarget.steerLocally` disparaît (la route interne dépose dans `LiveTurn`).
- **D7 — Un flux retenu perd l'aparté `steered`.** Derrière un proxy (SF-84-04), l'écran suit le tour
  par fenêtres, qui ne rejouent pas cet aparté. L'écran reconnaît alors l'envoi devenu précision à
  l'annonce `steer_queued` de **son** texte, s'il ne la rattache à aucune précision déjà affichée.
- **D8 — Borne de 4 000 caractères retirée** (voir Contraintes) ; la borne de 5 précisions en
  attente reste, désormais portée par `LiveTurn.MAX_PENDING_STEERS`.
- **D9 — Précisions arrivées après la fin** (`409 no_live_turn`) : regroupées en **un** message, envoyé
  dès que l'écran voit la fin du tour (ou aussitôt s'il l'a déjà vue).
