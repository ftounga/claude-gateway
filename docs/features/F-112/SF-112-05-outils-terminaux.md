# Mini-spec — F-112 / SF-112-05 : Les outils Terminaux

## Identifiant

`F-112 / SF-112-05`

## Feature parente

`F-112` — Le serveur MCP

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-112-05-outils-terminaux`

---

## Objectif

Exposer sur le serveur MCP les outils **Terminaux** (cadrage §5) : lancer un tour d'agent (comme une
**tâche** rendue immédiatement), suivre ses événements **par curseur** (rejeu de F-84), préciser,
interrompre, et **lister les autorisations en attente sans jamais les accorder** — avec la garde
« une IA ne s'autorise jamais elle-même ».

---

## Comportement attendu

### Cas nominal

| Outil | Périmètre | Relaie | Modèle |
|---|---|---|---|
| `terminaux_lister` | `terminaux:ecrire` | `LiveTerminalService` (terminaux vivants + aperçus) | lecture |
| `terminal_ecrire` | `terminaux:ecrire` | lance un tour via `AtelierMcpTurnLauncher` (réutilise `LiveTurnRegistry`/`chatStreaming`) | **tâche** : rend `turn_id` tout de suite |
| `tour_suivre` | `terminaux:ecrire` | `LiveTurn.attach` par curseur (rejeu F-84) | lecture par curseur |
| `tour_preciser` | `terminaux:ecrire` | `TurnSteering.steer` (SF-84-06) | action non destructive |
| `tour_interrompre` | `terminaux:ecrire` | `AtelierChatService.interruptChat` | action |
| `autorisations_en_attente` | `terminaux:ecrire` | `LiveTurn.pendingApproval` — **liste**, n'accorde jamais | lecture |

- `terminal_ecrire` est **non bloquant** : ouvre (ou précise) un tour vivant et rend `turn_id`. Si un
  tour tourne déjà sur le projet, le message devient une **précision** (comportement F-84 §6, cohérent
  avec le web). L'**origine MCP** est publiée dans le fil du tour (« Tour lancé depuis <client> »).
- `tour_suivre` rejoue les événements **> curseur** depuis le tampon du tour et rend le nouveau
  curseur, l'état (vivant/fini) et l'autorisation en attente éventuelle. Le contenu de tour est
  marqué **non fiable** (§6.3).
- `autorisations_en_attente` liste les demandes d'autorisation ouvertes (outil, détail, échéance) et
  le **lien direct** vers l'application pour valider ; **aucun champ ne les accorde**.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Périmètre `terminaux:ecrire` absent | refus nommé, aucun service appelé |
| Projet inexistant / non possédé | erreur claire (isolation `user_id` par `requireOwned`) |
| Projet sur un poste non accessible | refus « poste non accessible » |
| Sans droit Forge/Vigie (entitlement) | refus « accès terminal refusé » |
| `tour_preciser`/`tour_interrompre` sans tour vivant | erreur nommée (pas de tour en cours) |
| `terminal_ecrire` message vide | erreur claire |

---

## Critères d'acceptation

- [ ] Les 6 outils sont découverts avec annotations (`terminal_ecrire`, `tour_preciser`,
      `tour_interrompre` non readOnly ; `terminaux_lister`, `tour_suivre`, `autorisations_en_attente`
      readOnly).
- [ ] `terminal_ecrire` rend un `turn_id` immédiatement ; `tour_suivre` sur ce `turn_id` rejoue les
      événements par curseur et progresse.
- [ ] `autorisations_en_attente` liste une demande ouverte **sans** l'accorder ; aucun outil MCP
      n'accorde une autorisation (pas de `confirm`).
- [ ] Périmètre manquant → refus ; entitlement manquant → refus ; poste non accessible → refus.
- [ ] Isolation : le tour/terminal d'un autre utilisateur est introuvable.
- [ ] Le contenu de tour rendu par `tour_suivre` est marqué `untrusted`.
- [ ] L'origine MCP est publiée dans le fil du tour lancé.

---

## Périmètre

### Hors scope

- Accorder une autorisation (garde §6.1 — **jamais**), la porte de confirmation reste dans l'app.
- Suivi d'un tour tournant sur un **autre pod** (dégradation : `tour_suivre` le signale vivant sans
  rejouer ; le rejeu multi-pod reste au flux SSE web). Documenté en note.
- Ressources/prompts (SF-112-08).

---

## Contraintes de validation

| Champ | Obligatoire | Format |
|---|---|---|
| `project_id` | Oui | UUID |
| `message` | Oui (`terminal_ecrire`, `tour_preciser`) | non vide après trim |
| `cursor` | Non (`tour_suivre`) | entier ≥ 0, défaut 0 |

---

## Technique

Aucun endpoint HTTP nouveau. `AtelierMcpTurnLauncher` (nouveau service, package `atelier`) réutilise
`LiveTurnRegistry`, `chatStreaming` et l'exécuteur `chatStreamExecutor` ; il publie des charges utiles
JSON de mêmes noms que le flux web (via des `Map`, sans coupler au contrôleur). Aucune migration.

### Préoccupations transversales

- **Auth / Principal** : oui. L'accès terminal (droit Forge/Vigie + bypass ADMIN) est rejoué en MCP
  via `McpTerminalAccess` (mêmes règles que `AtelierAccessService`, mais résolu depuis
  `McpCallContext` et non le `SecurityContext`, indisponible sur le thread d'outil). Composants
  impactés : `AtelierAccessService` (référence, non modifié), `SpaceEntitlementService`,
  `WorkspaceRepository`, `LiveTurnRegistry`, `AtelierChatService`, `TurnSteering`. Aucune route HTTP
  existante touchée.
- **Contexte tenant** : oui — `user_id`/`host_id` depuis le jeton ; `requireOwned` en aval.

---

## Plan de test

### Unitaires

- [ ] `McpTerminalAccess` — ADMIN bypass ; droit Forge → accès ; sans droit → refus.

### Intégration (client SDK réel, périmètres)

- [ ] Découverte des 6 outils et de leurs annotations.
- [ ] `terminal_ecrire` sans `terminaux:ecrire` → refus.
- [ ] `terminal_ecrire` sans entitlement → refus.
- [ ] `terminal_ecrire` puis `tour_suivre` → `turn_id` et événements par curseur (projet runner de test).
- [ ] `autorisations_en_attente` → liste sans accorder ; aucun outil `confirm` exposé.
- [ ] `tour_suivre` marque le contenu `untrusted`.
- [ ] Isolation : `tour_suivre`/`tour_preciser` sur le projet d'autrui → introuvable/refus.

### Isolation utilisateur

- [ ] Applicable — chaque appel avec `ctx.user().id()` ; test croisé A/B.

---

## Dépendances

- `SF-112-01/02/03` (fondation), `SF-112-04` (helpers `McpToolSupport`/`McpHostAccess`/`McpSecretFilter`) — done.
- F-84 (rejeu par curseur), F-107 (droits d'espace).

---

## Notes et décisions

- **Tâche** : le protocole MCP « Tasks » (révision 2026-07-28) n'est pas la révision négociée
  (2025-11-25). La sémantique de tâche est donc rendue **au niveau outil** : `terminal_ecrire` rend un
  identifiant tout de suite, `tour_suivre` donne l'avancement par curseur, `tour_interrompre` annule.
  Rien n'est bloqué. Le passage au protocole Tasks suivra la montée de révision (SF-112-01).
- **Une IA n'accorde jamais** : `AtelierChatService.confirmToolUse` (la porte de confirmation) n'est
  **pas** exposé. `autorisations_en_attente` ne fait que lister, avec le lien vers l'app.
- **Origine MCP dans le fil** : le tour lancé par MCP publie un événement de fil « Tour lancé depuis
  <client> (MCP) » visible par tout spectateur du tour.
