# Mini-spec — F-116 / SF-116-02 — L'affichage au fil de l'eau

## Identifiant

`F-116 / SF-116-02`

## Feature parente

`F-116` — Le terminal répond mot à mot, comme Claude Code

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-116-02-affichage-fil-de-l-eau`

---

## Objectif

Garantir — et prouver par des tests — que le terminal (projet, poste, Teams, mosaïque) affiche le
texte de l'agent **dès le premier delta** reçu de SF-116-01, sans qu'aucun tampon côté frontend
n'attende la fin du tour, et de façon cohérente avec la reprise par curseur (SF-84-02) et le repli
long-polling fenêtré derrière un proxy (SF-84-04).

---

## Comportement attendu

### Cas nominal

1. Le backend émet les deltas de texte via l'événement SSE `text` (SF-116-01).
2. `AtelierService.streamChat` (et `attachTurn`, et le repli long-polling `pollTurnWindows`) découpe
   le flux sur les frontières d'événement (`\n\n`) et **dispatch chaque événement dès qu'il arrive** :
   chaque `text` appelle `handlers.onText(delta)` sans attendre `done`.
3. Dans chaque écran, `onText` **ajoute** le delta à la ligne vivante (`current.text + delta`) sur un
   signal réactif ; la vue terminal rend `live.text` au fil de l'eau (Markdown se stabilisant à mesure
   que le texte arrive). Le texte grandit mot à mot.
4. À `done`, la réponse complète est posée dans le fil et la ligne vivante est vidée
   (`endTurnDisplay`) : le contenu persisté est celui du backend, la ligne vivante n'était que
   transitoire.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le flux est retenu par un proxy (pas de `started`) | Suivi par fenêtres (SF-84-04) ; les `text` rejoués y arrivent groupés par fenêtres, toujours ajoutés à la ligne vivante |
| Rebranchement en cours de tour (SF-84-02) | Le rejeu depuis le curseur rejoue les `text` déjà émis ; chacun est ajouté, la ligne se reconstitue |
| Le backend n'émet aucun delta (flux off / repli non streamé) | `onText` reçoit le commentaire complet en une fois (comportement historique) ; l'écran l'affiche identiquement |

---

## Critères d'acceptation

- [ ] **Affichage incrémental** : plusieurs événements `text` successifs sont relayés à `onText` **un
      par un, dans l'ordre**, chacun avant `done` — aucun tampon frontend n'attend la fin.
- [ ] **Dispatch dès l'arrivée** : un delta arrivant dans un chunk réseau distinct est dispatché sans
      attendre les chunks suivants (le découpage se fait par événement `\n\n`).
- [ ] **Rendu partiel** : la vue terminal rend un `live.text` partiel (texte incomplet) sans attendre
      la fin du tour.
- [ ] **Cohérence reprise/curseur** : les `text` rejoués au rebranchement (SF-84-02) sont ajoutés à la
      ligne vivante ; le curseur avance sur chaque événement de tour.
- [ ] **Repli proxy** : le suivi par fenêtres (SF-84-04) relaie les `text` de la même façon.
- [ ] **Aucune régression** : les écrans projet/poste/Teams/mosaïque continuent d'ajouter `onText` à
      la ligne vivante (`current.text + text`).

---

## Périmètre

### Hors scope (explicite)

- Le streaming backend (SF-116-01, livré et mergé).
- Le débit total (jetons, durée) et l'effort de réflexion (F-118).
- Toute refonte du rendu Markdown ou de la ligne vivante : le mécanisme `onText` existe depuis
  SF-28-05 ; SF-116-02 le **vérifie** et le **prouve**, il ne le réécrit pas.

---

## Préoccupations transversales

- **Auth / tenant / plans** : non — aucun changement de droits ni de données, coût inchangé.
- **Navigation / routing** : non — aucune route, guard ni redirection modifiés.
- Composants concernés (déjà porteurs de `onText`, vérifiés) : `atelier.service.ts`
  (`streamChat`/`attachTurn`/repli fenêtré + `dispatchSseEvent`), `atelier-terminal.component`
  (rendu `live.text`), `atelier.component` (projet + poste), `mosaique/live-turn-view`.

---

## Technique

### Endpoint(s)

Aucun. Contrat SSE inchangé (événement `text` déjà défini).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

- `AtelierTerminalComponent` — rend `streaming.text` au fil de l'eau (déjà en place ; test ajouté).
- `AtelierService` — dispatch SSE par événement (déjà en place ; test ajouté).

---

## Plan de test

### Tests frontend

- [ ] `atelier.service.spec` : plusieurs `text` (deltas) sont relayés à `onText` **dans l'ordre et
      avant `done`**, y compris répartis sur des chunks réseau distincts (dispatch dès l'arrivée).
- [ ] `atelier-terminal.component.spec` : un `streaming.text` partiel est rendu (la ligne vivante
      montre le texte incomplet) puis grandit — aucun tampon n'attend la fin.

### Isolation

- [x] Non applicable — pas d'accès données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-116-01` — **Done** (PR #614 mergée).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Le mécanisme existe déjà.** La plomberie `onText` (SF-28-05) fait défiler le texte mot à
  mot dès lors que le backend émet des deltas (livré en SF-116-01). Les quatre écrans consomment
  `onText` de façon identique (`current.text + text`). SF-116-02 est donc une **vérification tracée
  par des tests**, pas une réécriture — c'est exactement ce que demande le cadrage (« vérifie qu'aucun
  tampon n'attend la fin »).
- **D2 — Aucun tampon frontend.** `streamChat` découpe sur `\n\n` et dispatche chaque événement
  immédiatement ; `onText` écrit sur un signal réactif rendu sans debounce. Le seul « tampon » est le
  découpage d'événement SSE, nécessaire et borné à un événement.
