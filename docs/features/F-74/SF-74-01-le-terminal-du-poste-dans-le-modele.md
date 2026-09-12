# Mini-spec — F-74 / SF-74-01 — Le terminal du poste dans le modèle, et son endpoint

## Identifiant

`F-74 / SF-74-01`

## Feature parente

`F-74` — Un terminal au niveau du poste

## Statut

`done` — mergée le 2026-09-12 (PR #401)

## Date de création

2026-09-12

## Branche Git

`feat/SF-74-01-terminal-du-poste`

---

## Objectif

Donner au **poste** un terminal qui est **un workspace comme les autres** — marqué `host_terminal`,
posé à la racine — et l'exposer par `POST /api/runner-hosts/{hostId}/terminal`, qui le **retrouve
ou le crée**.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur a un poste appairé. Il demande son terminal :
   `POST /api/runner-hosts/{hostId}/terminal` (corps vide).
2. La gateway vérifie l'**appartenance** du poste (`requireOwned` — 404 sinon, sans oracle
   d'existence).
3. Si un terminal de poste existe déjà pour ce `(user_id, host_id)`, elle le **rend tel quel**.
4. Sinon elle en crée un, dans la même transaction :

   | Champ | Valeur | Pourquoi |
   |---|---|---|
   | `name` | `Terminal du poste` | D5 — se lit sans explication dans le relevé F-61 |
   | `user_id` | l'appelant | racine d'isolation |
   | `host_id` | le poste | c'est ce qui le rattache |
   | `project_path` | `""` (la racine) | c'est là qu'on clone un dépôt le premier jour |
   | `source` | `LOCAL` | les fichiers vivent sur la machine |
   | `execution_target` | `RUNNER` | un terminal de poste s'exécute sur la machine, jamais en bac à sable |
   | `agent_ask_before_bash` | `true` | P4 — la porte de F-73 / SF-73-02, armée |
   | `host_terminal` | `true` | D1 — ce n'est pas un projet |

5. Réponse **`200`** avec le `WorkspaceDetailResponse` habituel (arborescence vide : le contenu vit
   sur la machine et se lit par le runner, SF-38-17), enrichi du drapeau `hostTerminal`.
6. L'écran n'a plus qu'à ouvrir `/atelier/{id}` : **c'est le terminal existant, sans une ligne de
   plus**.

**`200` et non `201`** : l'appel est **idempotent** et l'écran ne distingue pas les deux cas — il
demande « le terminal de ce poste », il le reçoit. Un `201` au premier appel et un `200` ensuite
obligerait chaque appelant à traiter deux codes pour un seul sens.

### Ce que le terminal du poste n'est pas — trois lectures qui s'en trouvent protégées

`listByHost` rend **les projets**, le terminal exclu (D3) :

| Lecture | Ce qui se serait passé sans l'exclusion |
|---|---|
| Vue d'ensemble des postes (F-49 / F-72) | un **projet fantôme** sur chaque carte |
| Doublon de `openOnHost` (F-72 / SF-72-01) | **impossible** d'ouvrir un vrai projet sur la racine — le terminal l'occupe |
| Garde de suppression (F-69 / SF-69-01) | un poste **jamais supprimable** : il porterait à vie un « projet » invisible |

Et symétriquement : **supprimer le poste supprime son terminal**, dans le même flux, avant la
ligne du poste. Un terminal de poste sans poste ne désigne plus rien.

### Ce que la vue d'ensemble porte en plus

`GET /api/runner-hosts/overview` gagne deux champs par poste :

- `hostTerminalId` — l'identifiant du terminal du poste, ou `null` s'il n'a jamais été ouvert ;
- `hostTerminalLive` — vrai si un onglet vit dessus **maintenant** (F-70).

Et `liveTerminals`, le compteur du poste, **l'inclut** : un terminal de poste ouvert est un
terminal ouvert sur ce poste. Le poste virtuel « Hébergé » (F-71) reste sans terminal de poste :
ce n'est pas une machine (P6), ses deux champs valent `null` / `false`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste inconnu **ou appartenant à quelqu'un d'autre** | `RunnerHostNotFoundException` — les deux cas indiscernables | 404 |
| Appelant sans accès Forge | refus d'accès Atelier (`atelierAccess.requireAccess()`), comme tous les autres chemins de ce contrôleur | 402 / 403 selon le cas existant |
| Non authentifié | refus | 401 |
| Second appel sur un poste qui a déjà son terminal | **le même terminal**, aucune création | 200 |

---

## Critères d'acceptation

- [ ] `POST /runner-hosts/{hostId}/terminal` crée un workspace `host_terminal = true`, `host_id` =
      le poste, `project_path = ""`, `source = LOCAL`, `execution_target = RUNNER`,
      `agent_ask_before_bash = true`, nommé `Terminal du poste`, et répond `200`.
- [ ] Un **second** appel rend **le même** identifiant et ne crée aucune seconde ligne.
- [ ] Le poste d'un **autre utilisateur** répond `404` et ne crée rien.
- [ ] `listByHost` (donc la carte du poste et le contrôle de doublon) **ne contient pas** le
      terminal du poste.
- [ ] Après avoir ouvert le terminal d'un poste, `POST /runner-hosts/{hostId}/projects` avec un
      `path` **vide** ouvre encore un projet sur la racine (le terminal ne compte pas comme doublon).
- [ ] `DELETE /runner-hosts/{hostId}` sur un poste **sans projet mais avec son terminal** répond
      `204` et le terminal disparaît de la base.
- [ ] `DELETE /runner-hosts/{hostId}` sur un poste **avec un projet** répond toujours `409`
      (F-69 inchangé).
- [ ] `GET /runner-hosts/overview` porte `hostTerminalId` et `hostTerminalLive`, et `liveTerminals`
      compte le terminal du poste quand il vit.
- [ ] Le terminal du poste **hérite de la gouvernance de son poste** (F-75), par le même événement
      de création que tout workspace.
- [ ] Aucune ligne n'est écrite dans `host_seat_months` par l'ouverture d'un terminal de poste
      (F-65 intact).

---

## Périmètre

### Hors scope (explicite)

- Un terminal **sans poste** (P6).
- Un terminal de poste sur le poste virtuel « Hébergé » (F-71) — ce n'est pas une machine.
- Tout écran : c'est SF-74-02.
- Renommer, déplacer ou dupliquer le terminal d'un poste.
- Un second terminal de poste sur la même machine.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `host_terminal` | `false` | Défaut en base **et** dans l'entité : toute ligne existante et tout `INSERT` qui l'omet reste un **projet**. |
| `name` | `Terminal du poste` | Écrit par la gateway, jamais demandé. |
| `project_path` | `""` | La racine du poste. |
| `agent_ask_before_bash` | `true` | P4 / ADR-019. |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| `hostId` (chemin) | Oui | — | UUID | — | — |
| `host_terminal` | Oui | — | booléen, `false` par défaut | **un seul par `(user_id, host_id)`**, garanti par le « retrouver ou créer » | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/runner-hosts/{hostId}/terminal` | Oui | utilisateur avec accès Forge |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | ALTER (colonne `host_terminal`), INSERT, SELECT, DELETE | la colonne est **additive**, `not null default false` |
| `runner_hosts` | SELECT | appartenance seulement |
| `live_terminals` | SELECT | déjà lu par la vue d'ensemble (F-70) |

### Migration Liquibase

- [x] Oui — `074-workspaces-host-terminal.xml` (**numéro libre suivant** : 073 est la dernière)
- [ ] Non applicable

---

## Plan de test

### Tests unitaires

- [ ] `WorkspaceService.openHostTerminal` — crée avec les bonnes valeurs initiales.
- [ ] `WorkspaceService.openHostTerminal` — **idempotent** : deux appels, un seul identifiant.
- [ ] `WorkspaceService.listByHost` — **exclut** le terminal du poste.
- [ ] `WorkspaceService.openOnHost` — un projet sur la racine reste possible malgré le terminal.
- [ ] `WorkspaceService.deleteHostTerminal` — supprime, et ne lève pas quand il n'y en a pas.
- [ ] `RunnerHostOverviewService` — `hostTerminalId` / `hostTerminalLive` et le compteur `liveTerminals`.

### Tests d'intégration

- [ ] `POST /runner-hosts/{id}/terminal` → `200` + drapeau `hostTerminal` vrai.
- [ ] `POST` deux fois → même `id`, une seule ligne.
- [ ] `POST` sur le poste d'un autre utilisateur → `404`.
- [ ] `DELETE /runner-hosts/{id}` avec terminal seul → `204`.
- [ ] `DELETE /runner-hosts/{id}` avec un projet → `409`.
- [ ] `GET /runner-hosts/overview` → le terminal **n'est pas** dans `projects`.

### Isolation workspace

- [x] Applicable — toute lecture et toute écriture filtrent `user_id` : `requireOwned` sur le poste,
      `findByUserIdAndHostId…` sur les workspaces. Un `hostId` reçu du client ne suffit jamais.

---

## Dépendances

### Subfeatures bloquantes

- Aucune. F-48, F-70, F-72, F-73 et F-75 sont livrées.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés et vérification |
|---|---|---|
| Auth / Principal | Non | aucun nouveau type d'auth ; `currentUser.requireId()` comme partout ailleurs |
| Contexte tenant | **Oui** — une nouvelle façon de retrouver un workspace (`host_id` + `host_terminal`) | `WorkspaceRepository` (lectures ajoutées, toutes préfixées `findByUserId…`), `WorkspaceService.listByHost` / `openHostTerminal` / `deleteHostTerminal`, `RunnerHostController` (`requireOwned` avant tout), `RunnerHostOverviewService` |
| Plans / limites | **Oui** — le plafond de quatre terminaux vivants (F-70) | `LiveTerminalService` **inchangé** : il compte des `workspace_id`, et le terminal du poste en porte un |
| Navigation / routing | Non (backend) | — |
| **Facturation** (F-65) | **Oui, et la réponse est « rien »** | `SeatLedgerService` n'est appelé qu'à la création, la clôture et la réouverture d'un **poste**. Aucun de ces chemins n'est touché. |
| **Consommation** (F-61) | **Oui, et la réponse est « rien à changer »** | `UsageTurnRepository` agrège par `(host_id, workspace_id)` : le terminal tombe sous le bon client. |

---

## Notes et décisions

Voir `F-74-cadrage.md` — D1 (le terminal du poste **est** un workspace marqué), D2 (créé à la
demande, idempotent), D3 (ce n'est pas un projet : trois lectures en dépendent), D4 (ce que ça ne
casse pas, F-61 et F-65 en tête), D5 (son nom).
