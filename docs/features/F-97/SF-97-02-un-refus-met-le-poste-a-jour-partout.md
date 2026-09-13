# Mini-spec — F-97 / SF-97-02 — Un refus reçu n'importe où met le poste à jour partout

## Identifiant

`F-97 / SF-97-02`

## Feature parente

`F-97` — Le statut du poste dit vrai (cadrage : `CADRAGE-F-97-le-statut-dit-vrai.md` §4)

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-97-02-le-statut-date`

---

## Objectif

Tenir l'état des postes **à un seul endroit** de l'écran, mis à jour par le sondage **et** par tout
refus « poste hors ligne », et faire **dater** le statut au lieu de l'affirmer (« en ligne · vu il y a
12 s »).

---

## Le constat

Le PO a appris que son poste était éteint **dans le terminal**, en lançant une commande, puis a dû
revenir sur la Forge pour le voir hors ligne. Deux défauts côté écran :

1. **Trois états séparés** : la Forge (`PostesComponent`) lit la vue d'ensemble, le terminal et l'en-tête
   du projet (`AtelierComponent` → `AtelierTerminalComponent`) lisent le statut du projet. Aucun ne sait
   ce que l'autre a appris.
2. **Aucun refus n'atteint l'écran** sous une forme exploitable : un `runner_unavailable` rendu par un
   appel d'outil part au modèle, qui le **raconte** en texte. Rien de structuré ne sort du flux.

---

## Comportement attendu

### Cas nominal

1. **Le flux dit le refus** (ajout minimal côté gateway, additif) : quand un appel d'outil vers la
   machine d'un projet rend `runner_unavailable`, le tour publie un événement SSE
   `runner_offline` `{ hostId, at }` (`at` = instant serveur en ms). Il passe par le tour vivant
   (F-84) : le terminal, un aperçu de supervision et une tuile de mosaïque le reçoivent tous, par le
   même `AtelierService.dispatchSseEvent`.
2. **`HostPresenceService`** (nouveau, `providedIn: 'root'`) tient, par poste, `connected`,
   `lastSeenAt` et l'instant du dernier refus. Deux écrivains :
   - `record(hostId, connected, lastSeenAt)` — le sondage (Forge : vue d'ensemble ; atelier : statut du
     projet) ;
   - `markOffline(hostId, at)` — tout refus : l'événement `runner_offline`, et le 409
     `runner_browse_unavailable` de la navigation dans les dossiers d'un poste.
3. **La preuve la plus récente gagne, en heure serveur** (aucune horloge client) :
   - un refus est **ignoré** si le poste a battu **après** lui (`lastSeenAt > at`) — un refus rejoué par
     le tampon d'un tour ne rend pas hors ligne un poste revenu ;
   - un relevé « connecté » est **tenu pour hors ligne** si son `lastSeenAt` n'est pas postérieur au
     dernier refus — une réponse de sondage partie avant le refus ne le contredit pas.
4. **Lecteurs** : la Forge (pastille d'état, bandeau « carte non lue »), l'en-tête du terminal (pastille
   de moteur et état du poste) lisent l'état du service ; l'atelier passe au terminal un statut dont
   `connected` / `lastSeenAt` viennent du service.
5. **L'écran date** : `presenceLabel(connected, lastSeenAt, now)` rend « en ligne · vu il y a 12 s »,
   « hors ligne · vu il y a 18 min », « jamais connecté » (hors ligne sans aucun battement). Unités :
   s < 60, min < 60, h < 24, j au-delà. Le terminal affiche « ma machine — en ligne · vu il y a 12 s ».
6. **La date avance à la seconde, sans appel** : `HostPresenceService.now` est un signal rafraîchi
   chaque seconde tant qu'un écran l'observe (`watchClock()` compté par référence, relâché à la
   destruction). Le minuteur tourne hors de la zone Angular : il ne déclenche aucun appel et ne
   retient aucun test.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `runner_offline` sans `hostId` (gateway plus ancienne, charge illisible) | ignoré | — |
| `runner_offline` rejoué alors que le poste a battu depuis | ignoré, le poste reste en ligne | — |
| Relevé « connecté » arrivé après un refus, `lastSeenAt` antérieur au refus | le poste reste hors ligne | — |
| Relevé « connecté » avec un battement postérieur au refus | le poste repasse en ligne | — |
| `lastSeenAt` dans le futur (horloge du navigateur en retard) | « vu il y a 0 s », jamais une durée négative | — |
| Projet rattaché à aucun poste | aucun événement, aucune écriture, libellés inchangés | — |
| Poste « Hébergé » (F-71) | aucune pastille d'état, inchangé | — |

---

## Critères d'acceptation

- [ ] Un `runner_offline` reçu dans le terminal fait passer la Forge hors ligne **sans nouvel appel**
      à `/api/runner-hosts/overview`.
- [ ] Un 409 `runner_browse_unavailable` de la navigation dans les dossiers d'un poste invalide ce poste.
- [ ] Un refus antérieur au dernier battement connu est ignoré ; un sondage antérieur au refus ne le
      contredit pas ; un battement postérieur rétablit « en ligne ».
- [ ] Forge et terminal affichent « en ligne · vu il y a N s », « hors ligne · vu il y a N min »,
      « jamais connecté » quand `lastSeenAt` est nul.
- [ ] Le libellé relatif avance quand l'horloge avance, **sans requête HTTP**.
- [ ] La gateway publie `runner_offline { hostId, at }` quand un appel runner rend `runner_unavailable`,
      et seulement dans ce cas.
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md` : pastilles existantes (`badge--success`, `badge--neutral`,
      `badge--error`).

---

## Périmètre

### Hors scope (explicite)

- Le calcul du statut côté gateway (SF-97-01, livrée).
- Toute modification du runner, des durées de battement ou de la cadence de sondage (15 s).
- L'invalidation depuis l'arbre de fichiers d'un projet (`runner_browse_unavailable` sur
  `/api/workspaces/{id}/…`) : la réponse ne porte pas le poste ; le sondage du projet corrige en ≤ 15 s.
- La refonte de la Forge (F-98), qui reprendra ce service.
- Un canal serveur → navigateur hors d'un tour (pas de WebSocket d'écran) : hors d'une commande, le
  sondage reste la source.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| état d'un poste dans `HostPresenceService` | absent | tant qu'aucun relevé ni refus : les écrans retombent sur la donnée qu'ils ont chargée |
| `now` | `Date.now()` à la construction | rafraîchi chaque seconde tant qu'un écran observe |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `runner_offline.hostId` | Oui | chaîne non vide (UUID du poste) | ignoré sinon |
| `runner_offline.at` | Non | nombre, ms depuis l'époque | `Date.now()` si absent |

---

## Technique

### Endpoint(s)

Aucun endpoint nouveau. Événement SSE **additif** sur `POST /api/workspaces/{id}/chat/stream` et
`GET /api/workspaces/{id}/chat/attach` : `event: runner_offline`, `data: {"hostId": "...", "at": 1757750000000}`.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Backend (additif)

- `AtelierProgressListener.onRunnerOffline(UUID hostId)` — méthode par défaut neutre.
- `AtelierChatService.executeToolOnRunner` — appelle `onRunnerOffline` quand le résultat est
  `runner_unavailable` et que le projet a un poste.
- `AtelierChatController` — publie `runner_offline` dans le tour.

### Composants Angular

- `HostPresenceService` (nouveau) + `presenceLabel` (fonction pure).
- `AtelierService` — `dispatchSseEvent` (`runner_offline`), `hostFolders` (409 → invalidation).
- `PostesComponent` — alimente le service à chaque lecture, lit l'état et le libellé daté.
- `AtelierComponent` — alimente le service au relevé du statut, passe au terminal le statut fusionné.
- `AtelierTerminalComponent` — libellé daté dans la pastille de moteur.

### Préoccupations transversales

- Auth / Principal : non. Contexte tenant : non (l'événement ne porte que le poste du projet de
  l'utilisateur, sur son propre flux). Plans / limites : non.
- Navigation : non (aucune route ni garde).

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `HostPresenceService` — refus → hors ligne ; refus antérieur au dernier battement ignoré ; sondage
      antérieur au refus ne le contredit pas ; battement postérieur rétablit ; `presenceLabel` pour les
      trois formes et l'horloge en retard ; `watchClock` fait avancer `now` sans HTTP et s'arrête au
      dernier relâchement.
- [ ] `AtelierService` — `runner_offline` écrit dans le service ; charge sans `hostId` ignorée ; 409
      `runner_browse_unavailable` de `hostFolders` invalide le poste.
- [ ] `PostesComponent` — un refus reçu ailleurs fait passer la pastille hors ligne sans nouvel appel
      à la vue d'ensemble ; libellés datés ; « jamais connecté ».
- [ ] `AtelierTerminalComponent` — pastille « ma machine — en ligne · vu il y a N s ».

### Tests unitaires / intégration (backend)

- [ ] `AtelierChatService` — `onRunnerOffline(hostId)` appelé sur `runner_unavailable`, jamais sur un
      autre échec.

### Isolation workspace

- [x] Applicable — l'événement est publié dans le tour du projet de l'utilisateur (flux déjà isolé par
      `requireOwned`) et ne porte que le poste de ce projet ; aucune lecture de données nouvelle.

---

## Dépendances

### Subfeatures bloquantes

- SF-97-01 — `done` (PR #494).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Un événement additif côté gateway** : sans lui, aucun refus n'atteint l'écran autrement qu'en
  prose du modèle. C'est la plus petite surface qui rende le critère du cadrage vérifiable.
- **Heure serveur contre heure serveur** pour arbitrer refus et sondage : ni horloge client, ni ordre
  d'arrivée — un tampon de tour rejoué ou un sondage lent ne mentent pas.
- **Minuteur hors zone** : un minuteur d'une seconde dans la zone ferait échouer tout test
  `fakeAsync` d'un écran qui l'observe, et n'apporte rien — les signaux programment eux-mêmes le
  rafraîchissement.
