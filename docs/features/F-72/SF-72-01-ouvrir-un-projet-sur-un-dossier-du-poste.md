# Mini-spec — F-72 / SF-72-01 — Ouvrir un projet sur un dossier du poste, en un seul geste

## Identifiant

`F-72 / SF-72-01`

## Feature parente

`F-72` — Connecter un poste, puis y ajouter des projets

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-72-01-projet-sur-dossier-du-poste`

---

## Objectif

Exposer `POST /api/runner-hosts/{hostId}/projects` : **ouvrir un projet sur un dossier déjà connu
du poste**, en un seul appel, avec un nom **dérivé du dossier** — pour que « ajouter un projet »
cesse d'être « créer un projet, puis lui trouver un poste, puis lui taper un chemin ».

---

## Comportement attendu

### Cas nominal

1. L'écran a listé les dossiers du poste (`GET /runner-hosts/{hostId}/folders`, SF-71-02) et
   l'utilisateur en a **cliqué un**.
2. Il appelle `POST /runner-hosts/{hostId}/projects` avec `{ "path": "clients/EDENRED" }`.
3. La gateway vérifie l'**appartenance** du poste (`requireOwned`), **normalise** le chemin
   (`RunnerProjectPath.normalize`, la même garde qu'au rattachement), puis, **dans une seule
   transaction** :
   - crée un projet `source = LOCAL`, cible d'exécution `RUNNER` ;
   - le **rattache** au poste avec ce chemin.
4. Le **nom** n'est pas demandé (D5) : c'est le **dernier segment** du chemin (`EDENRED`) ; pour la
   **racine** (`path` vide), c'est le **nom du poste**.
5. Réponse `201` avec le détail du projet — le même `WorkspaceDetailResponse` que partout ailleurs,
   pour que l'écran puisse l'adopter sans second appel.

### Un seul geste, ou rien

Le projet et son rattachement sont écrits **ensemble**. C'est le cœur de la subfeature : avec deux
appels, un échec au second laissait un projet **sans poste**, portant le nom du client — exactement
la seconde « entité EDENRED » que F-72 supprime.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Un projet de cet utilisateur **occupe déjà** ce chemin sous ce poste | Refus qui le **dit**, et nomme le projet existant | 409 `host_project_exists` |
| `path` absolu, avec `..`, avec lettre de lecteur, > 512 caractères | Refusé, **rien n'est créé** | 400 `invalid_project_path` |
| Poste d'un autre utilisateur, ou inexistant | Refus indifférencié (`requireOwned`) | 404 `not_found` |
| Accès Atelier absent | Refus | 403 |
| Sans JWT | Refus | 401 |
| Nom dérivé vide après nettoyage (dossier au nom uniquement blanc) | Repli sur le **nom du poste** | 201 |

**Le runner n'a pas à être connecté.** Ouvrir un projet est une écriture **en base** ; exiger une
machine joignable rendrait impossible de préparer ses projets le soir pour le lendemain. La lecture
des dossiers, elle, exige bien le runner — mais c'est l'appel **précédent**, et il a son propre
refus (SF-71-02).

---

## Critères d'acceptation

- [ ] `POST /runner-hosts/{hostId}/projects` avec un `path` crée **un** projet rattaché à ce poste,
      à ce chemin exact, et rend `201` avec son détail.
- [ ] Le **nom** du projet est le dernier segment du chemin ; à la racine, le nom du poste.
- [ ] Le projet créé a `source = LOCAL`, `executionTarget = RUNNER` et la **porte de confirmation
      armée** (F-73 / SF-73-02) — exactement comme `POST /workspaces/local`.
- [ ] Rejouer le **même** appel rend `409 host_project_exists` et **ne crée pas** de second projet.
- [ ] Un `path` invalide rend `400 invalid_project_path` et **ne crée rien**.
- [ ] Le poste d'un **autre** utilisateur rend `404`, et **aucun** projet n'est créé.
- [ ] Sans JWT : `401`.
- [ ] Le chemin écrit en base est le chemin **normalisé**, jamais la valeur brute reçue.

---

## Périmètre

### Hors scope (explicite)

- **Lister** les dossiers : c'est `GET /runner-hosts/{hostId}/folders` (SF-71-02), inchangé.
- **Créer** un dossier sur la machine (D8 de F-71).
- **Reprendre** les projets créés par l'ancien parcours (D10) : ce sont des essais, ils se
  suppriment (F-69).
- Renommer le projet : `PUT /workspaces/{id}` existe depuis SF-28-16.
- Détacher / rattacher : `PUT /workspaces/{id}/host` reste le geste, inchangé.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|-------|-------------|-------------|--------|---------------|
| `hostId` | oui | — | UUID de chemin | — |
| `path` | non (absent = la racine) | 512 | relatif, `/`, sans `..`, sans lettre de lecteur | `RunnerProjectPath.normalize` (réutilisé tel quel) |
| nom dérivé | — | 255 | — | tronqué à 255 si le dossier porte un nom plus long |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| POST | `/api/runner-hosts/{hostId}/projects` | JWT | accès Atelier |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | INSERT | `user_id` du JWT, `host_id` du chemin, `project_path` normalisé |
| `workspaces` | SELECT | `listByHost(userId, hostId)` pour refuser le doublon |
| `runner_hosts` | SELECT | `requireOwned` |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucune colonne, aucune table : `workspaces.host_id` et
      `workspaces.project_path` existent depuis F-48 (migration `064`).

### Classes

- `HostProjectRequest` (`runner/host/dto/`) — `path` seul.
- `WorkspaceService.openOnHost(userId, hostId, path, hostName)` — la transaction unique.
- `HostProjectExistsException` (`runner/host/`) + son handler → `409 host_project_exists`.
- `RunnerHostController.openProject` — `POST /{hostId}/projects`.

---

## Plan de test

### Unitaires (`WorkspaceServiceHostProjectTest`)

- [ ] Chemin `clients/EDENRED` → projet nommé `EDENRED`, `hostId` et `projectPath` posés.
- [ ] Chemin vide → projet nommé comme le **poste**, `projectPath` vide.
- [ ] Chemin déjà occupé par un projet du **même** utilisateur → `HostProjectExistsException`.
- [ ] Chemin occupé par un projet d'un **autre** utilisateur → aucune interférence (isolation).
- [ ] Chemin non normalisé (`./clients//EDENRED/`) → écrit normalisé.
- [ ] Nom de dossier de plus de 255 caractères → tronqué, pas d'erreur.
- [ ] Le projet créé porte `source = LOCAL`, `executionTarget = RUNNER`, `agentAskBeforeBash = true`.

### Intégration (`RunnerHostProjectsIT`)

- [ ] `POST` avec un chemin → `201`, et le projet apparaît dans `GET /workspaces`.
- [ ] Rejoué → `409 host_project_exists`, et `GET /workspaces` n'en compte toujours qu'un.
- [ ] `path = "../x"` → `400 invalid_project_path`, aucun projet créé.
- [ ] Poste d'un autre utilisateur → `404`, aucun projet créé.
- [ ] Sans JWT → `401`.

### Isolation `user_id` (**obligatoire**)

- [x] Applicable — `requireOwned(userId, hostId)` précède **tout**, le projet est créé avec le
  `user_id` du JWT, et le contrôle de doublon lit `listByHost(userId, …)` : le projet d'un autre
  compte au même chemin n'est ni vu ni touché.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | chemin `/runner-hosts/**`, chaîne JWT existante, inchangée |
| Contexte tenant | **oui** | `RunnerHostController.openProject` (identité du JWT, jamais d'un paramètre), `RunnerHostService.requireOwned`, `WorkspaceService.openOnHost` (`user_id` posé sur l'entité), `WorkspaceService.listByHost(userId, …)` pour le doublon |
| Plans / limites | non | aucun quota nouveau. Le **poste** est l'unité facturée (F-65 / D9) ; ouvrir un projet de plus sous un poste déjà payé ne franchit aucun gate. `WorkspaceCreatedEvent` reste émis comme pour toute création, et les consommateurs existants ne changent pas. |
| Navigation / routing | non | backend seul |

---

## Notes et décisions

- **A3 (cadrage)** : le nom vient du **dossier**. Demander un nom rouvrirait la question qui a
  produit les deux EDENRED — et le nom du dossier est, dans les faits, celui que l'utilisateur
  aurait tapé. Renommer reste possible d'un clic (SF-28-16).
- **Le refus du doublon est un refus, pas une reprise** : rendre le projet existant avec un `200`
  masquerait un double-clic sous une apparence de succès, et l'écran ne saurait pas s'il vient de
  créer quelque chose. Le `409` porte le nom du projet déjà ouvert — l'utilisateur sait où aller.
- **Pourquoi sous `/runner-hosts` et non sous `/workspaces`** : la ressource **mère** est le poste.
  C'est lui qu'on possède, lui dont on vérifie l'appartenance, et lui qui donne le nom par défaut.
  Un `POST /workspaces` qui porterait un `hostId` ferait l'inverse — partir du projet — c'est-à-dire
  exactement le parcours que F-72 corrige.
