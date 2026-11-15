# Mini-spec — F-260 / SF-260-01 Le volet de droite suit son tour par fenêtres

## Identifiant
`F-260 / SF-260-01`

## Feature parente
`F-260` — L'autorisation arrive dans le volet de droite (cadrage `CADRAGE-F-260-voix-off-autorisation-au-volet.md`)

## Statut
`ready` · **Estimation :** 0,5 j · **Branche :** `feat/SF-260-01-voix-off-autorisation`

## Objectif
Qu'une demande d'autorisation (ou une question) posée pendant un tour s'affiche dans le volet de droite de l'écran
partagé même quand un proxy retient le flux SSE, pour que le clic débloque l'action.

## Comportement attendu

### Cas nominal
1. Le volet envoie un message (`send`) ou se rebranche sur un tour vivant (`open`) : une **sonde** de
   `TURN_STREAM_PROBE_MS` (4 s) est armée.
2. Le flux prouve qu'il passe (`started`, `attached` ou `idle`) : la sonde se tait, rien ne change.
3. Sans nouvelle sous 4 s : le rebranchement retenu est abandonné (le flux d'émission, lui, reste ouvert) et le volet
   suit le tour **par fenêtres** (`followTurnInWindows`, `chantier` du volet, curseur du volet).
4. Les apartés des fenêtres (`confirm_state`, `question_state`) affichent l'autorisation / la question en attente ;
   le clic part sur `chat/confirm?chantier=N` comme avant.
5. Chaque événement numéroté ne s'applique qu'une fois, quelle que soit la source (curseur `acceptSeq`) : quand le
   flux retenu se vide d'un coup en fin de tour, rien n'est doublé.
6. Fin du tour (`done` non-suite, `error`, `idle`), changement de chantier ou fermeture du volet : fenêtres et sonde
   arrêtées.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Flux direct qui passe (réseau sans proxy) | aucune fenêtre ouverte ; comportement d'avant |
| Fenêtres déjà conclues, puis le flux d'émission se ferme sans `done` (tout filtré) | aucun « réponse non reçue », aucune relecture en boucle |
| Événement d'un chantier quitté | ignoré (génération du volet) |
| Volet fermé pendant la sonde | la sonde ne déclenche rien |

## Contraintes de validation
| Élément | Règle |
|---|---|
| Délai de sonde | `TURN_STREAM_PROBE_MS` (4 s), partagé avec l'écran principal |
| Fil suivi | `chantier` du volet, jamais le fil commun |

## Critères d'acceptation
- [ ] Sans nouvelle du flux en 4 s après un envoi, le volet appelle `followTurnInWindows(projet, curseur, …, {chantier})`.
- [ ] Un `confirm_state` reçu par fenêtre affiche la carte d'autorisation dans le volet ; « Autoriser » appelle `confirmChatToolUse(projet, …, chantier)`.
- [ ] Un `started` reçu avant 4 s : aucune fenêtre.
- [ ] Un même numéro d'événement livré deux fois (fenêtre puis flux) n'ajoute qu'une étape.
- [ ] Fin de tour, changement de chantier, fermeture : le suivi est arrêté (`stop`).
- [ ] Le flux d'émission fermé après une fin déjà reçue par fenêtres ne marque pas « réponse non reçue ».

## Plan de test
- **Unitaires** (`terminal-lane-pane.component.spec.ts`, `fakeAsync`) : sonde → fenêtres (envoi et rebranchement) ;
  `started` avant 4 s → pas de fenêtres ; `confirm_state` par fenêtre → carte affichée et clic routé au chantier ;
  dédoublonnage par `acceptSeq` ; arrêt en fin de tour / destruction ; `onClosed` après fin → pas d'« unanswered ».
  Le test « carte par fenêtre » échoue sur `main` (aucune fenêtre ouverte) et passe après.
- **Intégration** : sans objet côté backend (aucun changement) ; le contrat `chat/attach?waitMs&chantier` est déjà
  couvert (F-84 / SF-84-04, F-221).
- **Isolation utilisateur** : inchangée (mêmes endpoints, `requireTerminalAccess` + `requireOwned`).

## Impact
- Frontend : `atelier/terminal/terminal-lane-pane.component.ts` (+ spec).
- Backend : aucun. Tables : aucune. Endpoints : aucun nouveau.
- Préoccupations transversales : aucune (ni auth, ni tenant, ni plans, ni routing).

## Hors périmètre
Son / clignotement d'onglet pour le volet de droite ; délai d'autorisation ; refonte du transport.
