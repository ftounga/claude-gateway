# Mini-spec — [F-221 / SF-221-01] Le fil par chantier (backend)

## Identifiant
SF-221-01

## Feature parente
F-221 — Deux chantiers côte à côte : un fil par chantier, le terminal en split

## Statut
in-progress

## Date de création
2026-10-28

## Branche Git
`feat/SF-221-01-fil-par-chantier`

---

## Objectif
Un sujet porte un **fil par chantier**. Les messages sont rattachés à un chantier. Le contexte d'un tour de chantier contient le passé commun et son fil, jamais l'autre chantier. Il y a un tour vivant par fil.

## Comportement attendu

### Cas nominal
- **Le fil d'un envoi.** `POST /api/workspaces/{id}/chat/stream` et `POST /chat` acceptent `chantier` (entier ≥ 1, facultatif) dans le corps.
  - Absent : fil commun. C'est le comportement d'aujourd'hui pour tous les clients existants.
  - Les messages `USER`, les précisions et `ASSISTANT` du tour sont écrits avec `chantier_number = chantier`.
- **Tour vivant par fil.** `LiveTurnRegistry` est clefé `(userId, workspaceId, chantier)`.
  - Un envoi sur le fil N pendant que le fil M tourne ouvre un **second** tour. Il n'y a pas de précision croisée.
  - Un envoi sur le fil N pendant que N tourne reste une précision (F-84 / SF-84-06).
  - `LiveTurn` et le résumé de tour (`TurnSummary`) portent `chantier`.
- **Rebranchement et état.** `GET /chat/turn`, `GET /chat/attach`, `POST /chat/steer` et `POST /chat/interrupt` acceptent `?chantier=N`. Absent : fil commun.
  - Le relais entre pods porte `chantier`, nullable. Une requête d'un pod ancien vaut fil commun.
- **Historique.** `GET /chat` sans paramètre rend tout le sujet, comme aujourd'hui. `GET /chat?chantier=N` rend **ce que voit le fil N** (règle ci-dessous). Chaque message rend son `chantier`.
- **Ce que voit le fil N.** C'est la même règle pour le rejeu au modèle et pour l'historique :
  - les messages antérieurs à `opened_at` du chantier N, tous fils confondus ;
  - les messages `chantier_number IS NULL` ;
  - les messages `chantier_number = N`.

  Si le chantier N est inconnu (jamais ouvert), le fil N voit le commun et ses propres messages. Le fil commun voit tout.
- **Ordre stable.** Le rejeu regroupe les messages par tour : messages consécutifs d'un même fil, une demande puis sa réponse. Les tours sont ordonnés par leur premier message. Sur un fil unique, l'ordre est inchangé, octet pour octet.
- **Clefs d'état du tour par fil.** L'interruption, « tout autoriser », le plafond de dépense, la machine du tour, la marque de mutation, les compteurs runner et la notification tue sont clefés `(user, sujet, fil)`.
  - Interrompre le fil N n'arrête pas le fil M.
  - Les commandes en cours sur le poste et les autorisations en attente ne sont annulées en bloc que si **aucun autre fil** du sujet n'a de tour vivant sur ce pod.
- **Le parcours vu du fil.**
  - **Note du tour** : pour un tour du fil N, `JourneyTurnNote` est rendue avec **N au focus** (`SubjectJourneyService.forLane`) ; les autres chantiers ouverts, focus réel compris, sont résumés. Le focus persisté ne bouge pas.
  - **Outils du parcours** : `chantier` absent vaut **le fil du tour**, au lieu du focus.
- **Compaction** : par sujet (cadrage A4). Deux tours qui la déclenchent en même temps sont sérialisés par sujet.
- **Mesure** : `atelier_turn_measures.chantier_number` est renseigné.

### Cas d'erreur
| Situation | Comportement | Code |
|-----------|--------------|------|
| `chantier` ≤ 0 ou non entier dans le corps | refus de validation | 400 |
| `?chantier=0` / négatif sur turn/attach/interrupt | traité comme fil commun (paramètre ignoré) | — |
| Fil N dont le chantier est clos ou inconnu | le tour part quand même (fil N = commun + ses messages) ; les outils du parcours répondent « chantier non ouvert » | — |
| Sujet d'autrui | `requireOwned` d'abord | 404 |
| Interruption du fil N alors que M tourne | seul N est marqué ; commandes runner non annulées en bloc | 204 |

## Critères d'acceptation
- [ ] Un envoi `chantier=7` écrit `USER` et `ASSISTANT` avec `chantier_number=7` ; sans `chantier`, `null`.
- [ ] Deux tours, fil 7 et fil 8 du même sujet, sont vivants **en même temps**. Un envoi sur le fil 8 pendant le tour 7 ouvre un tour, sans précision dans le 7.
- [ ] Le rejeu d'un tour du fil 8 ne contient aucun message du fil 7 postérieur à l'ouverture du chantier 8. Il contient les messages communs et ceux d'avant l'ouverture.
- [ ] Sur un fil unique (tous `null`), la liste rejouée est identique à celle d'avant SF-221-01 (test d'égalité).
- [ ] `GET /chat?chantier=8` applique la même règle. `GET /chat` rend tout.
- [ ] Interrompre le fil 7 laisse le fil 8 tourner. « Tout autoriser » donné au fil 7 ne vaut pas pour le fil 8.
- [ ] La note du tour du fil 8 montre le chantier 8 en focus, sans changer le focus persisté. `update_plan_step` sans `chantier` dans un tour du fil 8 vise le chantier 8.
- [ ] Bob ne trouve pas le tour du fil 7 d'Alice (`GET /turn?chantier=7` → 404 sur le sujet d'Alice).

## Périmètre

### Hors scope (explicite)
- L'autonome dans le fil du chantier (SF-221-02). La course reste sur le fil commun dans cette SF.
- L'écran : split, envoi avec `chantier` (SF-221-03).
- Les suites en split (SF-221-05) et le worktree (SF-221-06).
- `recall`, Plume, la prédiction de suite et le bilan : ils lisent tout le sujet (cadrage A10).
- Le MCP (`tour_lancer`) et les routines restent sur le fil commun.

## Valeurs initiales
- `chantier_number` vaut `null` pour tous les messages existants et pour tout envoi sans `chantier`.

## Contraintes de validation
- `chantier` : entier ≥ 1, facultatif (`@Positive`).

## Technique

### Endpoint(s)
- `POST /api/workspaces/{id}/chat` et `/chat/stream` : corps + `chantier`.
- `GET /chat`, `GET /chat/turn`, `GET /chat/attach`, `POST /chat/steer`, `POST /chat/interrupt`, `POST /chat/confirm` : `?chantier=` (facultatif).
- Relais interne : `TurnRequest`, `SteerRequest`, `InterruptRequest`, `ConfirmRequest` + `chantier` (nullable).

### Tables impactées
- `atelier_messages` : + `chantier_number int NULL`, index `(workspace_id, user_id, chantier_number)`.
- `atelier_turn_measures` : + `chantier_number int NULL`.

### Migration Liquibase
- [x] Oui — `178-chantier-thread.xml` (numéro revérifié sur `origin/main` au merge). Rollback : suppression des colonnes et de l'index.

### Composants Angular (si applicable)
- Aucun (le champ `chantier` des messages est ajouté au modèle front sans usage, SF-221-03).

## Plan de test

### Tests unitaires
- [ ] `ChantierThread` (pur) : filtre (avant ouverture / commun / son fil / autre fil exclu), regroupement par tour (identité sur fil unique, deux fils entrelacés réordonnés par tour).
- [ ] `LiveTurnRegistry` : deux fils vivants en même temps, `openOrSteer` ne précise que dans son fil, `find` par fil, `anyLive`.
- [ ] `SubjectJourneyService.forLane` : N au focus, focus réel parmi les autres, focus persisté inchangé.
- [ ] `JourneyToolExecutor` : `chantier` absent → fil du tour.

### Tests d'intégration
- [ ] `AtelierChatService` : tour `chantier=8` → messages taggés, rejeu filtré envoyé au fournisseur (fournisseur simulé capture la requête).
- [ ] Interruption du fil 7 : marque du fil 7 seule.
- [ ] `GET /chat?chantier=8` (contrôleur) : filtre + champ `chantier` rendu.
- [ ] Migration jouée sur H2 (contexte complet — suite backend complète).

### Isolation utilisateur
- [x] Applicable — le registre reste clefé par `userId` ; `GET /chat?chantier=` filtre `user_id` ; test Bob/Alice sur `turn` et `history`.

## Préoccupations transversales
- **Contexte tenant** : composants impactés et vérifiés :
  - `LiveTurnRegistry` (clef) ;
  - `AtelierChatController` (stream / attach / turn / steer / interrupt / confirm / history) ;
  - `TurnSteering` ;
  - `RelayTurnSource` et `AtelierRelayController` ;
  - `RunnerRelayBroadcaster` (interrupt / confirm) ;
  - `AtelierThreadHandoffService` (« un tour tourne-t-il ? » → `anyLive`) ;
  - `AutonomyService` (sujet occupé → `anyLive`, inchangé fonctionnellement en 01) ;
  - `AtelierMcpTurnLauncher` (fil commun) ;
  - `TourSuivreTool` et `AutorisationsEnAttenteTool` (MCP, fil commun / tous fils).

## Dépendances

### Subfeatures bloquantes
- Aucune (cadrage F-221 mergé, PR #1332).

### Questions ouvertes impactées
- Aucune.

## Notes et décisions
- Le fil est porté pendant le tour par un champ **transitoire** du `Workspace` chargé par `runLoop` (`turnChantier`). Ce n'est pas un nouveau paramètre dans 42 méthodes. Toutes les clefs d'état passent par `turnKey(userId, workspace)`.
- Aucun nouveau constructeur sur un bean ni sur un record de configuration (mémoire « deux constructeurs »).
- **Requête de mesure (J+7)**, part relue par fil :

  ```sql
  select coalesce(chantier_number::text,'commun') fil, count(*),
         round(100.0*sum(cache_read_tokens)/nullif(sum(cache_read_tokens+cache_write_tokens+input_tokens),0),1) relu
  from atelier_turn_measures where occurred_at > now()-interval '7 days' group by 1;
  ```
