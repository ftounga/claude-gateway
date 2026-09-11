# Mini-spec — F-69 / SF-69-02 · Le bouton, et ce qu'il écrit avant d'effacer

## Identifiant

`F-69 / SF-69-02`

## Feature parente

`F-69` — Supprimer un projet, supprimer un poste

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-69-02-le-bouton-et-ce-qu-il-ecrit`

---

## Objectif

Poser à l'écran les deux gestes de suppression que le serveur sait déjà faire, avec une confirmation
qui **écrit noir sur blanc** que le dossier sur la machine n'est pas touché, et un refus de
suppression de poste qui dit **combien** de projets restent et **où** ils sont.

---

## Comportement attendu

### Cas nominal — supprimer un projet

**Où** : la liste des projets de `/atelier`, sur chaque ligne, dans un menu de dépassement
(`mat-icon-button` + `mat-menu`). C'est la seule vue qui liste **tous** les projets, y compris ceux
rattachés à aucun poste — et ce sont exactement les projets d'essai que le PO veut balayer.

1. « Supprimer » ouvre un `MatDialog` de confirmation nommant le projet.
2. Le dialogue écrit **deux listes**, côte à côte et de même poids :
   - *Ce qui sera effacé* : la conversation et son historique, les réglages du projet, son journal
     d'exécution, les fichiers importés dans la gateway.
   - *Ce qui n'est pas touché* : **le dossier sur votre machine** — aucun fichier, aucun dossier, rien
     n'est supprimé sur l'ordinateur ; et le poste, qui reste appairé.
3. Confirmer → `DELETE /api/workspaces/{id}` → la ligne disparaît de la liste, `MatSnackBar`
   « Projet supprimé. Le dossier sur votre machine n'a pas été touché. »
4. Si le projet supprimé était celui ouvert dans le terminal, l'écran revient à la liste.

### Cas nominal — supprimer un poste

**Où** : la carte du poste sur `/forge`, dans un menu de dépassement. Les postes ne sont listés nulle
part ailleurs.

1. « Supprimer le poste » ouvre un `MatDialog`.
2. **S'il reste des projets** : le dialogue est un **refus**, pas une confirmation. Il dit le nombre
   (« porte encore 3 projets »), rappelle qu'ils sont listés **sur cette carte**, et n'offre qu'un
   bouton « Fermer ». Aucun appel réseau n'est parti.
3. **S'il n'en reste aucun** : confirmation classique — ce qui part (l'appairage, les jetons, le
   journal de la machine côté gateway), ce qui ne l'est pas (**la machine elle-même et ses fichiers**,
   et le runner installé dessus).
4. Confirmer → `DELETE /api/runner-hosts/{hostId}` → la carte disparaît, snackbar de confirmation.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| 404 sur la suppression d'un projet | Snackbar « Projet introuvable. Il a peut-être déjà été supprimé. » et la ligne est retirée de la liste — l'écran était en retard, il se remet à jour. |
| 409 `host_has_projects` (un projet créé entre-temps dans un autre onglet) | Snackbar portant **le message du serveur** (il contient le compte), et la vue est relue. Le serveur fait foi. |
| 403 | Snackbar « La Forge est nécessaire pour ce geste. » |
| Réseau muet / 5xx | Snackbar d'échec, **rien n'est retiré de l'écran** : une ligne qui disparaît sans que le serveur ait confirmé reviendrait au rafraîchissement suivant. |
| Annuler le dialogue | Aucun appel, aucun changement. |

---

## Critères d'acceptation

- [ ] La liste des projets de `/atelier` offre « Supprimer » sur **chaque** projet, rattaché ou non.
- [ ] La confirmation de suppression d'un projet **écrit explicitement** que le dossier sur la machine
      n'est pas touché — vérifié par un test sur le texte rendu, pas seulement sur l'existence du
      dialogue.
- [ ] Annuler n'appelle rien.
- [ ] Confirmer appelle `deleteWorkspace(id)` **une seule fois** et retire la ligne à la réponse,
      jamais avant.
- [ ] Supprimer le projet ouvert dans le terminal ramène à la liste.
- [ ] Un échec réseau laisse la liste **intacte** et affiche un message.
- [ ] La carte d'un poste sur `/forge` offre « Supprimer le poste ».
- [ ] Avec des projets dessous, le dialogue **refuse** : il dit le nombre, et aucun appel réseau ne
      part.
- [ ] Sans projet, confirmer appelle `deleteRunnerHost(hostId)` et la carte disparaît.
- [ ] Un 409 renvoyé malgré tout affiche le message du serveur et relit la vue.
- [ ] Aucun `window.confirm` / `alert` / `prompt` (interdits par le design system).
- [ ] Aucune couleur hors charte : le bouton destructif est `mat-flat-button color="warn"` (§5). La
      couleur d'identité du poste (§9 / SF-49-03) et les pastilles de mission (F-60) sont **intactes**.

---

## Périmètre

### Hors scope (explicite)

- La corbeille, la restauration, l'archivage.
- Toute suppression de fichiers sur la machine.
- La suppression depuis les lignes de projet de `/forge` : cette vue reste une vue d'état (F-49), et
  elle ne liste de toute façon que les projets **rattachés**.
- La sélection multiple.

---

## Technique

### Appels API

| Méthode | URL | Nouveau ? |
|---|---|---|
| DELETE | `/api/workspaces/{id}` | nouveau **côté client** (l'endpoint existe depuis F-28) |
| DELETE | `/api/runner-hosts/{hostId}` | nouveau côté client |

### Composants Angular

- `DeleteProjectDialogComponent` (`atelier/delete-project-dialog/`) — confirmation nommant le projet,
  deux listes : effacé / pas touché.
- `DeleteHostDialogComponent` (`postes/delete-host-dialog/`) — refus **ou** confirmation, selon le
  nombre de projets reçu en entrée.
- `AtelierService` — `deleteWorkspace(id)`, `deleteRunnerHost(hostId)`.
- `AtelierComponent` — menu de dépassement par ligne de projet, retrait de la liste, sortie du
  terminal si c'était le projet ouvert.
- `PostesComponent` — menu de dépassement sur la carte, retrait de la carte, relecture sur 409.

### Migration Liquibase

- [x] **Non applicable** — aucun backend dans cette subfeature.

---

## Plan de test

### Tests unitaires (Jasmine/Karma)

- [ ] `DeleteProjectDialogComponent` — le texte rendu contient la phrase sur le dossier de la machine.
- [ ] `DeleteHostDialogComponent` — avec `remainingProjects > 0`, aucun bouton de confirmation et le
      nombre est écrit ; avec `0`, la confirmation est offerte.
- [ ] `AtelierComponent` — annuler n'appelle pas le service ; confirmer appelle et retire la ligne ;
      erreur réseau → liste intacte ; projet ouvert supprimé → retour à la liste.
- [ ] `PostesComponent` — refus local sans appel ; suppression d'un poste vide ; 409 → message du
      serveur + relecture.
- [ ] `AtelierService` — les deux méthodes tapent la bonne URL avec le bon verbe.

### Isolation utilisateur

- [x] Non applicable côté écran — l'identité vient du JWT, aucun identifiant d'utilisateur ne circule
      dans ces appels. L'isolation est tenue et testée côté gateway (SF-69-01).

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | Non | aucun |
| Contexte tenant | Non | aucun identifiant d'utilisateur côté client |
| Plans / limites | Non | aucun gate modifié |
| Navigation / routing | **Oui, marginalement** | `AtelierComponent` uniquement : supprimer le projet **ouvert** quitte le terminal et revient à la liste (même chemin que `leaveTerminal()`, déjà existant). Aucune route ajoutée, aucun guard modifié, aucune redirection nouvelle. `PostesComponent` ne navigue pas. |

---

## Dépendances

- **SF-69-01** — `done` (mergée) : l'écran ne promet aucun refus que le serveur n'applique pas.
- F-68 / SF-68-01 — `done` : `/forge` est l'accueil, `/atelier` la liste des projets.

---

## Notes et décisions

- **Deux écrans, deux gestes** : le projet se supprime là où **tous** les projets sont listés
  (`/atelier`), le poste là où **tous** les postes le sont (`/forge`). Chaque objet se supprime où il
  est listé exhaustivement.
- **Le refus est dit avant le clic** : proposer un bouton qui refusera à coup sûr serait une fausse
  promesse. Le serveur garde le dernier mot — l'écran peut être en retard, lui ne l'est jamais.
