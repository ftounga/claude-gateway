# Mini-spec — F-71 / SF-71-01 — Le poste « Hébergé » : une vue, pas une ligne

## Identifiant

`F-71 / SF-71-01`

## Feature parente

`F-71` — Le poste « Hébergé », et le dossier qu'on désigne sans le taper

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-71-01-poste-heberge`

---

## Objectif

Faire apparaître dans `GET /api/runner-hosts/overview` un **poste virtuel « Hébergé »** qui regroupe
les projets sans machine (`host_id IS NULL`), **sans créer aucune ligne en base**.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur possède des projets dont `host_id` est nul — dépôt GitHub (`GIT`), archive importée
   (`ARCHIVE`), ou tout projet pas encore rattaché.
2. `GET /api/runner-hosts/overview` rend ses postes réels **puis**, en dernière position, une entrée
   supplémentaire :
   - `id = null`, `virtual = true`, `name = "Hébergé"` ;
   - `connected = false`, `missionStatus = null`, `rootName`/`os`/`shell`/`elevated`/`lastSeenAt`
     `= null`, `createdAt = null` ;
   - `projects` = les projets sans poste de **cet** utilisateur, triés comme ceux d'un poste réel ;
   - `liveTerminals` = le nombre de ces projets qui portent un terminal vivant (F-70) ;
   - `activeProjects = 0` et `lastActivityAt = null`.
3. Les postes réels portent désormais `virtual = false` — le champ est toujours présent.

### Pourquoi `activeProjects = 0` et `lastActivityAt = null`

L'activité d'un poste vient du **journal d'audit du runner** (`runner_audit`). Un projet hébergé
n'exécute rien par un runner : ce journal n'a **rien** à dire de lui, et une ligne qui prétendrait
le contraire serait fausse. « Ce qui tourne » sur un projet hébergé se lit dans son terminal — et le
signe de vie (`liveTerminals`), lui, est exact et rendu.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucun projet sans poste | **Aucune** entrée virtuelle dans la réponse (D3) | 200 |
| Aucun poste et aucun projet sans poste | Liste vide, comme aujourd'hui | 200 |
| Accès Atelier absent | Refus inchangé (`atelierAccess.requireAccess()`) | 403 |
| JWT absent | Refus inchangé | 401 |
| Un endpoint de poste appelé avec l'`id` du poste virtuel | **Inexprimable** : `id` est nul, aucune URL ne se construit | — |

---

## Critères d'acceptation

- [ ] `GET /runner-hosts/overview` ajoute **une** entrée `virtual = true` dès qu'il existe au moins
      un projet sans poste, et **aucune** sinon.
- [ ] L'entrée virtuelle porte `id = null` et `name = "Hébergé"`.
- [ ] Elle est **en dernière position**, après tous les postes réels, quel que soit leur tri.
- [ ] Ses projets sont **exactement** ceux de l'utilisateur dont `host_id IS NULL`.
- [ ] `liveTerminals` de l'entrée virtuelle compte les terminaux vivants de ces projets, et
      `liveTerminal` est exact sur chaque projet.
- [ ] `missionStatus` est **nul** sur l'entrée virtuelle et inchangé sur les postes réels.
- [ ] **Aucune** ligne n'est écrite dans `runner_hosts` (D4) : aucune migration, aucun `save`.
- [ ] Les projets d'un **autre** utilisateur n'apparaissent jamais dans l'entrée virtuelle.

---

## Périmètre

### Hors scope (explicite)

- Tout geste sur le poste virtuel : appairage, jetons, coupe-circuit, suppression, renommage, état
  de mission. Les endpoints existants prennent un `UUID` de chemin ; un `id` nul ne s'y envoie pas.
- L'affichage (carte, libellés, icône) — c'est SF-71-03.
- Le comptage de sièges (F-65) : un poste virtuel n'est **pas** un siège et n'entre dans aucun
  calcul de facturation. Aucun code de `billing/seat` n'est touché.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---------|-----|------|-----------|
| GET | `/api/runner-hosts/overview` | JWT + accès Atelier | +1 entrée possible, +1 champ `virtual` |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | SELECT | nouveau `findByUserIdAndHostIdIsNull` — **toujours** filtré `user_id` |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — le poste virtuel est une vue (D4).

### Classes

- `RunnerHostOverviewResponse` — champ `boolean virtual` + fabrique `hosted(...)`.
- `RunnerHostOverviewService` — `overview()` concatène l'entrée virtuelle ; `describeHosted(...)`.
- `WorkspaceRepository` / `WorkspaceService` — `listWithoutHost(userId)`.

---

## Plan de test

### Unitaires (`RunnerHostOverviewServiceTest`)

- [ ] Des projets sans poste → une entrée `virtual`, en dernier, nommée « Hébergé », `id` nul.
- [ ] Aucun projet sans poste → aucune entrée virtuelle.
- [ ] `liveTerminals` de l'entrée virtuelle = nombre de ses projets vivants.
- [ ] `missionStatus` nul, `connected` faux, `activeProjects` à 0 sur l'entrée virtuelle.
- [ ] Les postes réels gardent leur tri et portent `virtual = false`.

### Intégration (`RunnerHostControllerIT` / existant)

- [ ] `GET /runner-hosts/overview` → 200, JSON contenant `"virtual":true` et `"id":null`.
- [ ] `GET /runner-hosts/overview` sans projet orphelin → aucune entrée `virtual`.

### Isolation `user_id` (**obligatoire**)

- [x] Applicable — un projet sans poste de l'utilisateur B **n'apparaît pas** dans la vue de A.
  `listWithoutHost` part de `currentUser.requireId()`, jamais d'un paramètre.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucun changement |
| Contexte tenant | **oui** | `RunnerHostOverviewService.overview(userId)` (seul appelant), `WorkspaceRepository.findByUserIdAndHostIdIsNull` (filtre `user_id` dans la signature), `RunnerHostController.overview()` (identité du JWT) |
| Plans / limites | non | aucun gate touché ; le comptage de sièges (F-65) ne lit pas cette vue |
| Navigation / routing | non | backend seul |

---

## Notes et décisions

- **A1** : `id = null` plutôt qu'un UUID constant — un identifiant qui ressemble à une entité finit
  par être envoyé à un endpoint qui répond 404. Nul, il est inexploitable par construction.
- Le nom « Hébergé » est **écrit par la gateway** et non par l'écran : deux écrans qui nommeraient
  la même chose autrement seraient deux vérités.
