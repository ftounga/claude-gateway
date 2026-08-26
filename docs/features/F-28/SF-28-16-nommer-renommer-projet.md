# Mini-spec — [F-28 / SF-16] Nommer et renommer un projet

---

## Identifiant

`F-28 / SF-16`

## Feature parente

`F-28` — Atelier (Claude Code Lite)

## Statut

`ready`

## Date de création

2026-08-26

## Branche Git

`feat/SF-28-16-nommer-renommer-projet`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Permettre de **choisir le nom d'un projet à sa création** et de le **renommer ensuite**.

---

## Contexte

Le nom d'un projet est aujourd'hui **subi** : il est dérivé du nom de l'archive téléversée, ou du dépôt
cloné. Un utilisateur qui importe `export-final-v2.zip` se retrouve avec un projet nommé ainsi, sans
recours.

Le **backend accepte pourtant déjà un nom** à la création (`name`, paramètre optionnel de
`POST /workspaces`, honoré par `WorkspaceService.create`) : **c'est le frontend qui ne le transmet
jamais**. La moitié du besoin est donc déjà en place, inutilisée.

Le renommage, lui, n'existe pas : `POST /workspaces/{id}/file/rename` renomme un **fichier**, pas le
projet.

---

## Comportement attendu

### Cas nominal

1. À la création — par archive comme par dépôt Git — un champ **nom** est proposé, pré-rempli par le
   nom déduit (archive ou dépôt) et modifiable.
2. Laissé tel quel, le comportement actuel est conservé à l'identique.
3. Depuis la liste des projets, une action **Renommer** ouvre une saisie pré-remplie du nom courant.
4. Le nouveau nom apparaît immédiatement dans la liste, l'en-tête du terminal et l'explorateur.
5. Le renommage ne touche **ni les fichiers, ni la session, ni l'historique** — c'est une étiquette.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Nom vide ou blanc | Refusé, message explicite ; l'ancien nom reste |
| Nom trop long (> 255) | Refusé côté client **et** côté serveur — la colonne est bornée |
| Projet d'un autre utilisateur | 404, comme tout accès non possédé |
| Dialogue fermé sans valider | Aucun appel réseau |
| Deux projets du même nom | **Autorisé** : le nom est une étiquette, pas un identifiant |

---

## Critères d'acceptation

- [ ] Le nom est proposé et modifiable à la création, par archive **et** par dépôt Git
- [ ] Sans saisie, le nom déduit est conservé — comportement actuel inchangé
- [ ] `POST /workspaces/{id}/rename` renomme le projet, isolé par `requireOwned`
- [ ] Un nom vide, blanc ou trop long est refusé **côté serveur**, pas seulement côté client
- [ ] Le nom est élagué avant enregistrement
- [ ] Le renommage ne modifie ni fichiers, ni session sandbox, ni historique
- [ ] Deux projets peuvent porter le même nom
- [ ] Isolation : renommer le projet d'un autre utilisateur → 404, aucune écriture
- [ ] Aucune table créée, aucune migration (la colonne `name` existe déjà)

---

## Périmètre

### Hors scope

- Renommer depuis l'explorateur de fichiers (l'action vit dans la liste des projets et l'écran du projet)
- Historique des noms successifs
- Nom imposé unique par utilisateur : ce serait une contrainte sans bénéfice

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Longueur | 1 à 255 caractères après élagage (borne de la colonne) |
| Unicité | Aucune |
| Défaut | Nom de l'archive sans extension, ou nom du dépôt — inchangé |

---

## Technique

### Endpoint(s)

| Méthode | Chemin | Rôle |
|---------|--------|------|
| `POST` | `/workspaces/{id}/rename` | Renomme le projet, renvoie le détail à jour |

Le paramètre `name` de `POST /workspaces` **existe déjà** : seul le frontend change.

### Tables impactées / Migration

Aucune. `workspaces.name` existe et est déjà borné à 255.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/AtelierController.java` | + endpoint de renommage |
| `atelier/WorkspaceService.java` | + `renameWorkspace` (validation, élagage, isolation) |
| `atelier/dto/RenameWorkspaceRequest.java` | **Nouveau** |
| `core/services/atelier.service.ts` | + `renameWorkspace`, transmission du nom à la création |
| `atelier/atelier.component.*` | Champ nom à la création, action Renommer dans la liste |

> Le dialogue de saisie **existe déjà** (`TextPromptDialogComponent`, SF-28-14) : il est réutilisé, pas
> réécrit.

---

## Plan de test

### Tests unitaires

- [ ] Renommage nominal : le nom change, rien d'autre
- [ ] Nom vide / blanc / trop long → refus, ancien nom intact
- [ ] Élagage des espaces de bord
- [ ] Isolation : projet d'un autre utilisateur → 404, aucune écriture
- [ ] Frontend : création avec nom saisi → le nom est transmis
- [ ] Frontend : création sans saisie → comportement actuel (non-régression)
- [ ] Frontend : dialogue annulé → aucun appel

### Tests d'intégration

- [ ] `POST /workspaces/{id}/rename` → 200, nom à jour dans `GET /workspaces`
- [ ] Isolation : renommage par un autre utilisateur → 404
- [ ] La session sandbox et l'historique survivent au renommage

### Isolation utilisateur

- [x] **Applicable** — `requireOwned` en premier, comme tout accès à un workspace ; aucun nouveau
  chemin de lecture ou d'écriture.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement. |
| Contexte tenant | **Non** | Le renommage passe par `requireOwned`, comme les endpoints existants ; aucun nouveau chemin d'accès. |
| Plans / limites | **Non** | Aucun quota ; renommer ne consomme rien. |
| Navigation / routing | **Non** | Aucune route. |

---

## Dépendances

- Aucune. `TextPromptDialogComponent` (SF-28-14) est réutilisé.

---

## Notes et décisions

- **La moitié existe déjà** : le backend honore `name` depuis SF-28-01. Le corriger côté frontend coûte
  quelques lignes — c'est le renommage qui demande du travail.
- **Pas d'unicité** : deux projets « refonte » sont légitimes. Imposer l'unicité créerait une erreur là
  où l'utilisateur ne voit qu'une étiquette.
- **Le renommage ne touche à rien d'autre** : ni le stockage (les fichiers vivent sous l'identifiant,
  pas sous le nom), ni la session sandbox, ni l'historique.
