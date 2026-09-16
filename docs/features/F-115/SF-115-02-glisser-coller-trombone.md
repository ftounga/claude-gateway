# Mini-spec — F-115 / SF-115-02 — Glisser, coller, trombone dans le terminal

> Base : `docs/features/F-115/CADRAGE-F-115-glisser-des-fichiers-dans-le-terminal.md` §4 (ce que voit
> l'utilisateur) et §7 (préoccupations transversales). S'appuie sur l'endpoint de dépôt livré en
> SF-115-01 (`POST /workspaces/{id}/deposit`, multipart, réponse `{files:[{path,size,target}]}`).
> Réutilise le précédent glisser-déposer de F-85 (`documents.component`, voile sans couleur nouvelle,
> `outline` en tirets `--cg-accent`) et la parité trombone de SF-28-13.

## Identifiant

`F-115 / SF-115-02`

## Feature parente

`F-115` — Glisser des fichiers dans tous les terminaux, comme dans Claude Code

## Statut

`ready`

## Date de création

2026-09-16

## Branche Git

`feat/SF-115-02-glisser-coller-trombone`

---

## Objectif

Permettre de déposer un fichier dans **tout** terminal (projet hébergé, projet local, poste, Teams) et
sur les tuiles de mosaïque — par glisser-déposer, collage presse-papiers ou bouton trombone — avec un
voile « Déposer ici », une progression annulable et un bloc discret « fichier déposé » dans le fil.

---

## Comportement attendu

### Cas nominal

1. **Glisser-déposer** : survoler le terminal avec un fichier fait apparaître un **voile « Déposer
   ici »** sur toute la zone (charte, aucune couleur nouvelle : `outline` en tirets `--cg-accent`).
   Lâcher le(s) fichier(s) lance le dépôt vers l'endpoint SF-115-01.
2. **Bouton trombone** dans le composer (parité SF-28-13, étendue aux terminaux runner) : ouvre le
   sélecteur de fichiers ; le(s) fichier(s) choisi(s) suivent le même chemin de dépôt.
3. **Collage** (Cmd/Ctrl+V) d'une image ou d'un fichier depuis le presse-papiers dans le terminal :
   même chemin de dépôt (un fichier collé sans nom reçoit un nom par défaut horodaté).
4. **Progression** : pendant l'envoi, une barre de progression **annulable** s'affiche ; annuler
   interrompt la requête (aucun fichier écrit réputé).
5. **Bloc « fichier déposé »** : au succès, un bloc discret apparaît dans le fil — « fichier déposé :
   `<chemin>` — 2,3 Mo » — que l'agent lira (SF-115-03). **Aucun filtre de type** au dépôt.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| L'endpoint répond une erreur nommée (400/409/413/502) | message lisible affiché dans le fil (poste hors ligne, dossier non inscriptible, taille dépassée, nom invalide), jamais un silence |
| Annulation par l'utilisateur | la requête est interrompue ; un bloc « dépôt annulé » discret, pas d'erreur |
| Dépôt sur un terminal en lecture seule sans composer (mosaïque) | le glisser et le collage restent possibles (dépôt vers le workspace de la tuile) ; le trombone n'apparaît que là où le composer existe |
| `dragover` sans `preventDefault` | empêché — le navigateur ne doit pas ouvrir le fichier hors de l'app |

---

## Critères d'acceptation

- [ ] Le glisser-déposer fonctionne sur `app-atelier-terminal` quelle que soit la déclinaison — projet
      hébergé, projet local, poste, Teams — et sur les tuiles de mosaïque (composant unique réutilisé).
- [ ] Un voile « Déposer ici » apparaît au survol d'un fichier et disparaît au `dragleave`/`drop` ;
      **aucune couleur hors `DESIGN_SYSTEM.md`** (voile = `--cg-accent` en tirets + fond `--cg-*`).
- [ ] Un bouton trombone est présent dans le composer (là où le composer existe) et ouvre le
      sélecteur de fichiers.
- [ ] Le collage (Cmd/Ctrl+V) d'un fichier/image dans le terminal lance un dépôt.
- [ ] La progression s'affiche et est **annulable** ; l'annulation interrompt la requête HTTP.
- [ ] Au succès, un bloc discret « fichier déposé : `<chemin>` — `<taille lisible>` » apparaît dans le
      fil ; sur erreur, le message nommé de l'endpoint est affiché.
- [ ] Aucun filtre de type appliqué au dépôt (contrairement à l'upload documentaire F-85).
- [ ] `AtelierService.deposit(...)` envoie un `multipart/form-data` (champ `files`) à
      `POST /api/workspaces/{id}/deposit` avec suivi de progression et annulation.
- [ ] `ng build` vert.

---

## Périmètre

### Hors scope (explicite)

- L'endpoint et l'écriture côté serveur (livrés en SF-115-01).
- L'injection des chemins dans la consigne du tour et la lecture par l'agent (SF-115-03).
- Le dépôt d'un **enregistrement** Teams (F-104 / SF-104-04, déjà prévu ailleurs).
- Tout filtrage de type façon F-85.

---

## Technique

### Composants Angular impactés (préoccupation transversale — surfaces terminal)

> **Un seul composant de présentation** anime les quatre déclinaisons de terminal + la mosaïque
> (`app-atelier-terminal`). Ajouter le dépôt là couvre **toutes** les surfaces d'un coup ; il faut
> néanmoins vérifier chaque contexte d'instanciation.

| Composant | Fichier | Rôle / vérification |
|-----------|---------|---------------------|
| `AtelierTerminalComponent` | `frontend/src/app/atelier/terminal/atelier-terminal.component.{ts,html,scss}` | reçoit le glisser/coller/trombone, le voile, la progression, les blocs ; **présentation seule** — émet `filesSelected` / `depositCancel`, ne fait aucun appel HTTP |
| `AtelierComponent` (conteneur) | `frontend/src/app/atelier/atelier.component.ts` | projet hébergé, projet local, poste, Teams : câble le dépôt via `AtelierService.deposit`, gère la progression et pousse les blocs |
| `MosaiqueComponent` | `frontend/src/app/mosaique/mosaique.component.{ts,html}` | tuiles (lecture seule) : câble le dépôt vers le workspace de la tuile |
| `AtelierService` | `frontend/src/app/core/services/atelier.service.ts` | **créé** : `deposit(id, files)` multipart + progression + annulation |

- **Navigation / routing : non.** Aucune route, aucun guard, aucune redirection nouvelle — le dépôt
  vit **dans** la vue du terminal (paramètre visuel, pas de changement d'URL), donc aucun chemin de
  navigation existant n'est touché. (Vérifié : aucune modification de `app.routes` ni des guards.)
- **Auth / tenant : oui** — le dépôt part sur `POST /workspaces/{id}/deposit` avec le Bearer courant ;
  l'isolation est celle de l'endpoint SF-115-01 (`requireOwned`, `user_id`+`host_id`). Le front n'ajoute
  aucun nouveau moyen de résoudre le tenant.
- **Design System** : voile et blocs n'utilisent que des jetons `--cg-*` existants ; police et
  espacements (multiples de 4) conformes ; notifications via le fil, pas d'`alert()`.

### Endpoints consommés

| Méthode | URL | Notes |
|---------|-----|-------|
| POST | `/api/workspaces/{id}/deposit` (multipart, `files`) | livré en SF-115-01 ; réponse `{files:[{path,size,target}]}` |

---

## Plan de test

### Tests unitaires (specs de composant / service)

- [ ] `AtelierTerminalComponent` — `dragover` affiche le voile, `dragleave`/`drop` le retire ; `drop`
      émet `filesSelected` avec les fichiers ; `preventDefault` appelé sur `dragover` et `drop`.
- [ ] `AtelierTerminalComponent` — le bouton trombone est présent quand le composer existe et déclenche
      la sélection ; absent en lecture seule sans composer.
- [ ] `AtelierTerminalComponent` — un `paste` porteur d'un fichier émet `filesSelected` ; un `paste` de
      texte seul ne déclenche rien.
- [ ] `AtelierTerminalComponent` — la barre de progression et le bouton **annuler** sont rendus quand
      `depositing` est vrai ; « annuler » émet `depositCancel`.
- [ ] `AtelierTerminalComponent` — un bloc « fichier déposé : … — taille » est rendu depuis les
      `depositNotices` ; un bloc d'erreur porte le message nommé.
- [ ] `AtelierService.deposit` — POST multipart vers `/api/workspaces/{id}/deposit`, champ `files`,
      émet la progression et se laisse annuler (HttpTestingController).

### Tests d'intégration

- [ ] Chemin complet dans `AtelierComponent` (spec conteneur) : `filesSelected` → `deposit` →
      succès → bloc dans le fil ; erreur → bloc nommé (mock `AtelierService`).

### Isolation utilisateur

- [x] Applicable (indirecte) — l'appel porte le Bearer courant et cible `/{id}` ; l'isolation est
      garantie serveur (SF-115-01, testée là). Aucune donnée d'un autre tenant n'est manipulée côté front.

---

## Dépendances

### Subfeatures bloquantes

- SF-115-01 (endpoint de dépôt) — done (PR #651).

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

- **D1 — le terminal reste présentation seule** : il émet `filesSelected` / `depositCancel` et reçoit
  l'état (`depositing`, `depositProgress`, `depositNotices`) ; l'appel HTTP est fait par le conteneur
  (`AtelierComponent` / `MosaiqueComponent`), comme le couple `draft`/`send` existant. Respecte les
  coding-rules (un composant ne fait pas d'appel HTTP direct).
- **D2 — voile sans couleur nouvelle** : reprise exacte du précédent F-85 (`outline` tirets
  `--cg-accent`), conforme au blocage design-system de CLAUDE.md.
- **D3 — dépôt sur les tuiles de mosaïque (lecture seule)** : le glisser et le collage y restent
  possibles (le CADRAGE §4 les cite) ; seul le **trombone** est réservé aux surfaces avec composer.
