# Mini-spec — [F-220 / SF-220-07] Hors chantier

## Identifiant
SF-220-07

## Feature parente
F-220 — Le tableau de bord du client

## Statut
Done

## Date de création
2026-10-27

## Branche Git
`feat/SF-220-07-hors-chantier`

---

## Objectif
Montrer sur le tableau du client, dans une section **« Hors chantier »**, les attentes ouvertes de tous les sujets du poste qu'aucun chantier ouvert ne montre déjà, avec le même code couleur « qui débloque ? », leur ancienneté et leurs gestes — pour que rien ne s'oublie.

## Contexte (besoin PO, 2026-10-27)
« Dans un sujet, il y a des tâches qui ne sont pas dans des chantiers. Sur le dashboard, on le verra ou pas. » Aujourd'hui non : une attente (`terminal_actions`, F-154/F-175/F-180) n'apparaît au tableau que si une étape de chantier la cite par `waits_on`. En prod : 56 attentes ouvertes non fusionnées (CAGIP 22, EDENRED 34).

## Comportement attendu

### Cas nominal
`GET /api/runner-hosts/{hostId}/chantiers` (SF-220-02) gagne :
```json
{
  "counts": { "outil": 3, "moi": 2, "autrui": 4, "live": 1, "dormant": 5, "snoozed": 0, "horsChantier": 3 },
  "horsChantier": [ {
    "actionId": "…", "workspaceId": "…", "workspaceName": "ingress-prod", "hostTerminal": false,
    "description": "Demander le certificat à Gino", "blocks": "…", "status": "DEMANDE", "qui": "CLIENT",
    "categorie": "ACCES", "person": "Gino", "requestedTo": "Gino", "requestedAt": "…", "createdAt": "…",
    "unblocker": { "who": "AUTRUI", "label": "On attend quelqu'un", "detail": "Demandé à Gino.", "step": null,
                   "waitingOn": "Gino", "since": "…" },
    "lastActivityAt": "…", "idleDays": 4, "dormant": true, "followUpDue": true
  } ]
}
```
- **Quelles attentes** : statut `A_FAIRE` ou `DEMANDE`, `merged_into_id` nul, dont le terminal est un **sujet du tableau** (projets du poste et terminal du poste, jamais le terminal Teams — même périmètre que SF-220-02), et **non citées** par le `waits_on` d'une étape **restante** (ni faite ni vérifiée) d'un chantier ouvert montré au tableau (celles-là sont déjà visibles comme « ce qui manque »). La citation se juge **par identifiant d'attente**, avec la même résolution que SF-220-01 (`actionOfKey` : ce terminal d'abord, puis le poste).
- **Qui débloque** (même code couleur que F-220, jamais `OUTIL`) :

  | `status` | `qui` | Couleur | `detail` |
  |---|---|---|---|
  | tout | `MOI` | **MOI** (orange, « Ça attend votre décision ») | « Un geste à faire par vous. » |
  | `A_FAIRE` | `CLIENT`, `TIERS` ou nul | **MOI** | « À demander[ à <person>]. » |
  | `DEMANDE` | `CLIENT`, `TIERS` ou nul | **AUTRUI** (gris-bleu, « On attend quelqu'un ») | « Demandé[ à <à qui>]. » |

  `waitingOn` (AUTRUI seulement) = `requested_to`, sinon `person` ; `since` = `requested_at`, sinon `created_at`.
- **Ancienneté** : `lastActivityAt` = `updated_at` (son dernier geste) ; `idleDays`, `dormant` comme les chantiers (`app.client-board.dormant-days`). `followUpDue` = relance due (F-175 `TerminalActionFollowUp`).
- **Ordre** : ma décision avant quelqu'un ; dans chaque groupe, le plus dormant d'abord ; puis la plus ancienne. 200 au plus (au-delà, coupé).
- **Compteurs** : `moi`, `autrui` et `dormant` **incluent** la section ; `horsChantier` = sa taille.
- **Écran** (onglet « Chantiers » du poste) : après les groupes de chantiers, une section « Hors chantier (N) », sous-groupée **Ma décision** puis **Quelqu'un**, mêmes pastilles (icône + libellé, jamais la couleur seule). Chaque tâche montre : sa pastille, son sujet, son énoncé, ce qu'elle bloque, « Auprès de X · depuis N j » (AUTRUI), son ancienneté (« dormant depuis N j » mis en avant), « à relancer » si due. Gestes : **Ouvrir le sujet** (le terminal), **Marquer fait** (`POST …/actions/{id}/close`), **Marquer demandé** (`POST …/actions/{id}/status` `{status: DEMANDE}`, seulement `A_FAIRE` et `qui ≠ MOI`). Les filtres MOI / AUTRUI / Dormants de l'en-tête s'appliquent aussi à la section ; le filtre OUTIL la masque.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste d'autrui ou inconnu | `requireOwned` d'abord, aucune donnée | 404 |
| Attentes illisibles | best-effort : `horsChantier: []`, les chantiers restent | 200 |
| Marquer fait / demandé sur l'attente d'autrui | routes F-175 existantes (`requireOwned` + `user_id`) | 404 |
| Geste refusé (déjà fermée…) | message d'erreur, le tableau est relu | 4xx |

## Critères d'acceptation
1. Une attente ouverte d'un sujet du poste, citée par aucune étape → listée dans `horsChantier`, avec `workspaceName`.
2. Une attente citée par le `waits_on` d'une étape restante d'un chantier ouvert → **absente** de `horsChantier`. Citée seulement par une étape faite → présente.
3. `qui=MOI` → `MOI` ; `A_FAIRE` + `CLIENT` → `MOI` ; `DEMANDE` + `CLIENT` demandée à « Gino » → `AUTRUI`, `waitingOn = "Gino"`, `since` = date de la demande.
4. Fermée (`FAIT`/`ANNULE`) ou fusionnée → absente. Attente d'un autre poste ou du terminal Teams → absente.
5. `counts.moi`/`counts.autrui`/`counts.dormant` incluent la section ; `counts.horsChantier` = sa taille.
6. Ordre : `MOI` avant `AUTRUI`, le plus dormant d'abord.
7. Bob → 404 sur le poste d'Alice.
8. Écran : la section s'affiche (pastille + libellé + icône), les gestes appellent les routes F-175 existantes et relisent le tableau ; le filtre MOI ne garde que les tâches MOI ; responsive téléphone (gestes en pleine largeur).

## Périmètre
### Hors scope (explicite)
- **Relancer** : le geste F-175 prépare un brouillon dans la saisie du terminal ; il reste dans le terminal (« Ouvrir le sujet »). Le tableau signale seulement « à relancer ».
- Rattacher une attente à un chantier, la reporter, l'éditer, l'annuler : dans le terminal (routes existantes).
- Le terminal Teams et les postes hébergés (sans poste).
- Le badge de l'onglet reste le nombre de chantiers ouverts.

## Valeurs initiales
Aucune (lecture seule, aucune donnée persistée).

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| `horsChantier` | 200 éléments au plus |
| `description` / `blocks` | déjà bornés à l'inscription (300 / 200) |

## Technique
### Endpoint(s)
| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/runner-hosts/{hostId}/chantiers` (enrichi) | JWT | USER |
| POST | `/api/workspaces/{ws}/actions/{id}/close` (existant, F-154) | JWT | USER |
| POST | `/api/workspaces/{ws}/actions/{id}/status` (existant, F-175) | JWT | USER |

### Tables impactées
| Table | Opération | Notes |
|-------|-----------|-------|
| `terminal_actions` | SELECT | `user_id` + statuts ouverts, filtrées sur les sujets du poste possédé |

### Migration Liquibase
- [x] Non applicable

### Composants
- Backend : `WaitInfo` (+ `actionId`), `SubjectJourneyService.waitInfo`, `ClientBoard` (`LooseTask`, `counts.horsChantier`, `horsChantier`), `ClientBoardService` (attentes non citées, marque, ordre, compteurs).
- Frontend : `client-board.models.ts` (contrat + lectures pures), `ClientBoardComponent` (section, gestes via `TerminalActionsService`).

## Préoccupations transversales
- **Contexte tenant : oui** (lecture transverse aux sujets d'un poste). Composants qui résolvent le périmètre : `RunnerHostService.requireOwned` (seule résolution du poste, en premier) ; `ClientBoardService.subjects` (`listByHost` / `findHostTerminal`, filtrés `user_id` + `host_id`) ; `TerminalActionRepository.findByUserIdAndStatusInOrderByCreatedAtAsc` (sous `user_id`) puis filtre sur les sujets possédés ; gestes : `TerminalActionService.require` (`requireOwned` + `user_id` + `workspace_id`). Tests : Bob → 404, attente d'un autre poste absente.
- Auth : non. Plans / limites : non. Navigation : non (route `/atelier/{ws}` existante).

## Plan de test
### Tests unitaires
- `ClientBoardServiceTest` : marque d'une tâche (table des couleurs), ordre, compteurs avec tâches.
### Tests d'intégration
- `ClientBoardIntegrationTest` : critères 1, 2, 3, 4, 5, 7.
### Front (Karma)
- `client-board.models.spec.ts` : regroupement et filtre des tâches.
- `client-board.component.spec.ts` : section rendue (pastille + libellé), « Marquer fait » / « Marquer demandé » appellent les routes et relisent, filtre MOI.
### Isolation utilisateur
- [x] Applicable — Bob → 404 ; attente d'un autre poste d'Alice absente.

## Dépendances
- SF-220-02, SF-220-03 — Done. F-175 (statuts, routes), F-180 (`qui`) — Done.
- Questions ouvertes : aucune.

## Notes et décisions (arbitrages)
1. **`A_FAIRE` + `qui=CLIENT`/`TIERS` → orange (MOI)** : personne n'a encore rien demandé, la demande est à faire par l'utilisateur. Différent d'une étape de chantier (SF-220-01 la compte « quelqu'un ») : là, l'étape attend un tiers ; ici, la tâche elle-même est la demande à envoyer. `qui` nul suit la même règle (A_FAIRE → moi, DEMANDE → quelqu'un).
2. **Citée = par une étape restante** : une étape faite ne montre plus son attente au tableau ; une attente encore ouverte derrière elle serait sinon invisible.
3. **Terminal Teams exclu** : même périmètre de sujets que SF-220-02 (critère 6).
4. **Relancer** reste dans le terminal (brouillon dans la saisie) : le tableau dit « à relancer » et ouvre le sujet.
