# Mini-spec — [F-30 / SF-09] Persistance des tours Terminal

---

## Identifiant

`F-30 / SF-09`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-24

## Branche Git

`feat/SF-30-09-persistance-tours-terminal`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Conserver les tours du mode Terminal — demande, réponse et **transcription des commandes** — pour
qu'ils survivent à un rechargement de page, comme ceux du mode Assistant.

---

## Contexte

Le mode Assistant persiste ses messages dans `atelier_messages` ; le mode Terminal **ne persiste
rien** : son tour assistant est créé avec un identifiant local, et l'historique rechargé ne le
contient pas. Recharger la page vide donc le terminal, alors que la **sandbox, elle, garde son état**
(SF-30-04) — l'utilisateur perd la trace de ce qu'il a fait dans un environnement toujours vivant.

Ce défaut existait depuis SF-28-11 mais restait discret. Depuis la vue immersive (SF-30-07), l'écran
se vide entièrement : il devient visible et gênant.

---

## Comportement attendu

### Cas nominal

1. À l'issue d'un run **abouti**, la demande de l'utilisateur et la réponse de l'agent sont persistées,
   comme en mode Assistant.
2. Le tour assistant porte en plus sa **transcription** : commandes, sorties, indicateur d'échec, et
   le coût du tour.
3. Au rechargement, l'historique restitue ces tours **avec** leur transcription : la vue terminal
   affiche les commandes et leurs sorties, pas seulement le texte final.
4. La transcription est reconstruite **côté backend** à partir des événements réellement reçus du
   fournisseur — jamais à partir de ce que déclare le client.
5. Les tours du mode Assistant sont inchangés : aucune transcription, aucun champ modifié.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Run en échec (erreur, timeout, quota) | **Rien n'est persisté** — cohérent avec l'écran, qui retire déjà le tour optimiste |
| Transcription volumineuse | Persistance **bornée** : au-delà d'un plafond configurable, les blocs excédentaires sont omis avec une mention explicite du nombre omis |
| Échec d'écriture de l'historique | Best-effort : le run est déjà livré à l'écran, un échec de persistance ne doit pas le faire échouer |
| Transcription illisible en base (donnée ancienne, JSON corrompu) | Le tour s'affiche **sans** transcription plutôt que de casser l'historique |

---

## Critères d'acceptation

- [ ] Un run abouti persiste un message `USER` et un message `ASSISTANT` pour le mode Terminal
- [ ] Le message assistant porte la transcription (commandes, sorties, échec, coût)
- [ ] Un run en échec ne persiste **rien**
- [ ] La transcription est reconstruite depuis les événements du fournisseur, jamais fournie par le client
- [ ] L'appariement commande↔sortie suit les mêmes règles que l'affichage (`toolUseId`, repli, orphelin)
- [ ] La persistance est bornée par un plafond **configurable**, avec mention des blocs omis
- [ ] Un échec de persistance n'interrompt pas le run (best-effort)
- [ ] L'historique (`GET /workspaces/{id}/chat`) restitue la transcription ; les tours sans transcription sont inchangés
- [ ] Une transcription illisible n'empêche jamais l'affichage de l'historique
- [ ] Isolation `user_id` : les messages sont écrits et relus sous le double filtre habituel

---

## Périmètre

### Hors scope

- Reprise de la conversation **dans la session sandbox** au rechargement : la session garde déjà son
  propre historique côté fournisseur (SF-30-04) ; on persiste l'affichage, pas le contexte de l'agent.
- Recherche ou export des transcriptions
- Purge/rétention des anciennes transcriptions : relève d'une politique de rétention globale

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Plafond de transcription | Configurable (`app.atelier.agent.max-transcript-chars`), défaut **100 000** caractères |
| Format stocké | Document JSON dans une colonne dédiée — donnée d'affichage, jamais requêtée |
| Persistance | Uniquement sur run abouti ; best-effort |
| Migration | `041-atelier-messages-terminal.xml`, réversible, Postgres **et** H2 |

---

## Technique

### Endpoint(s)

Aucun créé. `GET /workspaces/{id}/chat` renvoie un champ supplémentaire (additif).

### Tables impactées / Migration

`atelier_messages` — migration **041** : `terminal_json` (text, nullable). Colonne nullable : les
messages existants restent valides tels quels.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `db/changelog/migrations/041-atelier-messages-terminal.xml` | Migration (Postgres + H2) |
| `atelier/AtelierMessage.java` | + `terminalJson` |
| `atelier/agent/TerminalTranscript.java` | **Nouveau** — accumulation et appariement des blocs |
| `atelier/agent/AtelierSessionService.java` | Accumulation pendant le run + persistance en fin de run |
| `atelier/dto/AtelierMessageResponse.java` | + transcription dans la réponse d'historique |
| `atelier/agent/AtelierAgentProperties.java` | + `max-transcript-chars` |
| `core/models/atelier.models.ts`, `atelier.component.ts` | Lecture de la transcription à l'historique |

---

## Plan de test

### Tests unitaires

- [ ] Appariement : commande + sortie par `toolUseId`, y compris dans le désordre
- [ ] Appariement : sans `toolUseId` → dernière commande sans sortie ; sinon bloc orphelin
- [ ] Plafond : transcription au-delà de la borne → blocs omis + mention
- [ ] Run abouti → un `USER` et un `ASSISTANT` persistés, transcription incluse
- [ ] Run en échec → **aucune** écriture
- [ ] Échec de persistance → run livré malgré tout (best-effort)
- [ ] Historique : transcription restituée ; message sans transcription inchangé
- [ ] Historique : transcription illisible → tour rendu sans transcription, sans exception

### Tests d'intégration

- [ ] `GET /workspaces/{id}/chat` renvoie la transcription d'un tour Terminal
- [ ] Isolation : l'historique d'un autre utilisateur reste inaccessible (non-régression)

### Isolation utilisateur

- [x] **Applicable** — les messages sont écrits avec `user_id` + `workspace_id` et relus sous le
  double filtre existant. Aucun nouveau chemin de lecture : l'endpoint d'historique et son filtre
  sont inchangés, seule la charge utile s'enrichit.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement. |
| Contexte tenant | **Oui** | De nouveaux écrits apparaissent dans `atelier_messages`. Composants vérifiés : `AtelierSessionService` (écrit avec `userId` + `workspaceId` du run, déjà validés par `requireOwned` en tête de run), `AtelierMessageRepository` (lecture inchangée, filtrée par workspace **et** utilisateur), `AtelierChatController` (endpoint d'historique inchangé), `AtelierChatService` (écritures du mode Assistant inchangées). Aucun autre composant n'écrit dans cette table. |
| Plans / limites | **Non** | Aucun appel de quota ajouté ou modifié ; la persistance ne consomme ni tokens ni sandbox. |
| Navigation / routing | **Non** | Aucune route. |

---

## Dépendances

- **SF-30-04 / 05 / 07 (Done)** — session persistante, coût du tour, vue immersive.

---

## Notes et décisions

- **Transcription reconstruite côté backend** : le client pourrait envoyer la sienne, ce serait plus
  simple — mais l'historique deviendrait alors ce que le navigateur affirme, pas ce que le fournisseur
  a produit. Le service écoute déjà ces événements, il n'a rien à demander à personne.
- **Rien n'est persisté sur échec** : l'écran retire déjà le tour optimiste en annonçant que rien n'a
  été enregistré. Persister quand même contredirait ce que l'utilisateur vient de lire.
- **Document JSON plutôt qu'une table de blocs** : c'est une donnée d'affichage, restituée en bloc et
  jamais requêtée. Une table dédiée ajouterait des jointures sans usage.
