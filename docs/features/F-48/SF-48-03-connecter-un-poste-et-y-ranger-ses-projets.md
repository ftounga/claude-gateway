# Mini-spec — F-48 / SF-48-03 — Connecter un poste, et y ranger ses projets

## Identifiant

`F-48 / SF-48-03`

## Feature parente

`F-48` — Le poste comme unité

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-48-03-ecrans-du-poste`

---

## Objectif

Donner à l'écran le geste que le modèle rend possible : **appairer une machine une fois**, puis y
**ranger** chaque projet en disant sous quel sous-dossier il vit.

---

## Comportement attendu

### Cas nominal

1. Dans le dialogue de mise en service d'un projet, une étape neuve — **le poste** — précède la
   génération du code : l'utilisateur choisit un poste déjà connecté, ou en crée un en le nommant
   librement, et indique le **chemin du projet sous la racine** de ce poste (vide = la racine).
2. Le projet est **rattaché** au poste (`PUT /workspaces/{id}/host`).
3. Le code d'appairage est demandé **au poste** (`POST /runner-hosts/{id}/pairing-code`) — et il n'est
   demandé qu'une fois par machine : rattacher un second projet à un poste **déjà connecté** saute
   l'appairage et le téléchargement.
4. La commande de lancement affichée emploie `--root` et la racine de la machine, plus un chemin de
   projet.
5. Le coupe-circuit du projet coupe le **poste** (`POST /runner-hosts/{id}/kill`) et le dit — il
   ramène tous les projets de cette machine au bac à sable.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Nom de poste vide à la création | Le bouton reste inactif ; aucun appel |
| Chemin de projet refusé par la gateway (`invalid_project_path`) | Message sous le champ, l'étape reste ouverte |
| Poste supprimé entre-temps (404) | « Ce poste n'existe plus. » et la liste est rechargée |
| Coupe-circuit sur un projet sans poste | L'action n'est pas proposée |
| Échec réseau | Message générique ; aucun état n'est perdu |

---

## Critères d'acceptation

- [ ] L'étape « poste » précède l'étape « code » et se résume, une fois faite, par le nom du poste et
      le chemin du projet.
- [ ] Créer un poste et rattacher le projet se fait en **un seul geste** de l'utilisateur.
- [ ] Un projet rattaché à un poste **déjà connecté** n'affiche ni code ni téléchargement : la mise
      en service est déjà faite.
- [ ] La commande affichée emploie `--root`.
- [ ] Le coupe-circuit vise le poste, et l'écran dit qu'il coupe la machine entière.
- [ ] Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md` ; aucun `window.confirm`.
- [ ] `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope

- La **vue d'ensemble** des postes (F-49) : ici, on ne voit ses postes que depuis un projet.
- Le renommage et la suppression d'un poste depuis l'écran (l'API existe, l'écran viendra avec F-49).
- Le guide d'accueil (F-53).

---

## Impacts

### Composants frontend

- `core/models/atelier.models.ts` — `RunnerHost`, `RunnerStatus.hostId`, `WorkspaceDetail.hostId` et
  `projectPath` (remplacent `runnerRootName` / `runnerElevated`).
- `core/services/atelier.service.ts` — `listRunnerHosts`, `createRunnerHost`,
  `createHostPairingCode`, `killHost`, `attachWorkspaceToHost`.
- `atelier/runner/runner-pairing-dialog.component.*` — étape « poste ».
- `atelier/atelier.component.ts` — coupe-circuit par poste, ouverture du dialogue avec le poste connu.

### Tables / endpoints

Aucun endpoint neuf : SF-48-01 les a livrés.

### Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | inchangé |
| Contexte tenant | non | inchangé (l'API filtre `user_id`) |
| Plans / limites | non | inchangé |
| Navigation / routing | **non** | aucune route neuve : l'étape vit dans un dialogue déjà atteint depuis l'Atelier |

---

## Plan de test

### Unitaires (Karma)

- `runner-pairing-dialog.component.spec.ts` — l'étape « poste » précède le code ; créer un poste puis
  rattacher enchaîne les deux appels dans cet ordre ; un chemin refusé laisse l'étape ouverte avec son
  message ; la commande affichée porte `--root`.
- `atelier.service.spec.ts` — chaque méthode neuve tape la bonne URL avec le bon verbe.
- `atelier.component.spec.ts` — le coupe-circuit vise le poste du statut.

### Intégration

Sans objet côté frontend (les endpoints sont couverts en SF-48-01).

### Isolation utilisateur

Tenue par l'API ; l'écran n'envoie jamais d'identifiant d'utilisateur.
