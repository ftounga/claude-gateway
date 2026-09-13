# Mini-spec — [F-107 / SF-107-07] La Vigie ouvre le runner

---

## Identifiant

`F-107 / SF-107-07`

## Feature parente

`F-107` — L'offre par espace : la plateforme se paie, en BYOK aussi
(cadrage : `CADRAGE-F-107-l-offre-par-espace.md` ; `docs/features/F-106/CADRAGE-F-106-la-vigie.md` §3)

## Statut

`done` — livrée le 2026-09-13 (PR #548)

## Date de création

2026-09-13

## Branche Git

`feat/SF-107-07-la-vigie-ouvre-le-runner`

---

## Objectif

Un compte qui a la Vigie sans la Forge (Gold Vigie, option Vigie seule, essai Vigie) peut appairer un
poste, le voir, le couper et ouvrir son terminal Teams, parce que le runner est commun aux deux espaces ;
projets, terminaux de projet, terminal de poste, carte et gouvernance restent réservés à la Forge.

---

## Contexte

Question laissée ouverte par SF-107-04 (question PO « la Vigie sans la Forge »). Toutes les routes du
runner sont gardées par `AtelierAccessService.requireAccess()` (droit **Forge**) : un compte Vigie seul
voit la présentation de la Vigie, mais `/teams/access`, `/runner-hosts/**` et les routes du terminal
Teams lui répondent 403. **Décision du PO** (cadrage F-106 §3 : « Connecter un client depuis la Vigie :
même parcours d'appairage que la Forge ») : le runner est commun.

---

## Comportement attendu

### Cas nominal

1. **Trois gardes, une seule source** — `AtelierAccessService` (paquet `atelier`) gagne :
   - `requireRunnerAccess()` / `hasRunnerAccess()` : droit **Forge ou Vigie** (bypass `ADMIN` conservé,
     droits lus dans `SpaceEntitlementService`) ;
   - `requireTerminalAccess(workspaceId)` / `hasTerminalAccess(workspaceId)` : si le workspace est le
     **terminal Teams** possédé par l'utilisateur courant → garde runner ; sinon (projet, terminal de
     poste, inconnu, à autrui) → garde **Forge**. Refus = `AtelierAccessDeniedException` (403
     `atelier_forbidden`, code inchangé).
   - `requireAccess()` / `hasAccess()` restent la garde Forge, inchangées.
2. **Postes (`RunnerHostController`)** — garde runner sur : créer un poste, lister, `/spaces`,
   activer/retirer un espace, `/overview`, détail, renommer, état de mission, supprimer (le refus « poste
   avec projets » reste), code d'appairage, jetons (liste, révocation), statut, coupe-circuit, terminal
   Teams. L'espace visé exige **son** droit : `FORGE` → droit Forge, `VIGIE` → droit Vigie (création,
   activation, vue d'ensemble). Garde **Forge** conservée sur : dossiers du poste, ouvrir un projet,
   terminal du poste.
3. **Terminal Teams** — `POST /runner-hosts/{id}/teams-terminal` : garde runner + droit Vigie (inchangé)
   + client activé dans la Vigie (inchangé). Routes d'usage gardées par `requireTerminalAccess(id)` :
   `AtelierChatController` (toutes), `GET /workspaces/{id}`, `GET /workspaces/{id}/engine`,
   `RunnerManagementController` (`status`, `audit`), `TeamsLinkController`, `TeamsMomentController`.
   Les flux SSE (`stream`, `attach`) résolvent `hasTerminalAccess(id)` sur le fil de la requête.
4. **`GET /teams/access`** — garde runner (et non plus Forge) ; réponse inchangée.
5. **`GET /workspaces?space=VIGIE`** — garde Vigie ; ne rend **que les terminaux Teams** du compte.
   `GET /workspaces` (sans paramètre ou `FORGE`) : garde Forge, liste complète, inchangée.
6. **Téléchargement du runner** — `/runner/download/**` est déjà public (chaîne `/runner/**`) : rien à
   changer, vérifié par un test.
7. **Écran du terminal (`AtelierComponent`)** — quand `GET /workspaces` répond 403 Forge **et** que
   l'adresse désigne un workspace, l'écran relit `GET /workspaces?space=VIGIE` ; s'il y trouve le
   terminal demandé, il l'ouvre. Sinon : présentation Forge
   comme aujourd'hui. `/atelier` sans identifiant : inchangé.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Compte sans Forge ni Vigie sur une route runner | Refus `atelier_forbidden` | 403 |
| Compte Vigie seul sur un projet / terminal de poste (`/workspaces/{id}`, chat, statut) | Refus `atelier_forbidden` | 403 |
| Compte Vigie seul : ouvrir un projet, dossiers, terminal de poste | Refus | 403 |
| Compte Vigie seul : `overview` / création / activation en espace `FORGE` | Refus | 403 |
| Compte Forge seul : terminal Teams (ouverture) ou espace `VIGIE` | Refus Teams (inchangé) | 403 |
| Compte Vigie seul : terminal Teams d'un autre utilisateur | Garde Forge appliquée → refus (indiscernable d'un projet) | 403 |
| Espace inconnu dans `?space=` | `invalid_client_space` (inchangé) | 400 |

---

## Critères d'acceptation

- [x] CA1 — Compte Vigie seul (Gold Vigie) : `POST /runner-hosts` `space=VIGIE`, code d'appairage,
      `GET /runner-hosts`, `overview?space=VIGIE`, statut, coupe-circuit → 200.
- [x] CA2 — Compte Vigie seul : `GET /teams/access` → 200 `entitled=true` ; `POST .../teams-terminal`
      → 200 ; `GET /workspaces/{teamsId}`, historique du chat, `GET /workspaces?space=VIGIE` → 200.
- [x] CA3 — Compte Vigie seul : `GET /workspaces/{projetId}`, historique du chat d'un projet,
      `POST /runner-hosts/{id}/projects`, `POST .../terminal`, `overview` (Forge), `GET /workspaces` → 403.
- [x] CA4 — Compte Forge seul : postes, projets, overview Forge → 200 ; terminal Teams et espace Vigie → 403
      (inchangé).
- [x] CA5 — ADMIN sans abonnement : tout ouvert (postes, projets, terminal Teams).
- [x] CA6 — Compte sans droit : `GET /runner-hosts` → 403.
- [x] CA7 — Écran : un 403 Forge avec un identifiant demandé relit la liste Vigie et ouvre le terminal Teams.

---

## Périmètre

### Hors scope (explicite)

- Carte du poste, gouvernance (`GovernanceHostController`, `GovernanceUserController`), Git, fichiers,
  import, export, cible d'exécution, rattachement : **Forge uniquement, inchangés**.
- `AtelierAgentController` (agent géré en bac à sable) : Forge uniquement, inchangé.
- Facturation du poste (supplément par client par espace, SF-107-05) : inchangée.
- Aucune nouvelle UI dans la Vigie : ses écrans appellent déjà ces routes.

---

## Valeurs initiales

Aucune.

## Contraintes de validation

`space` ∈ {`FORGE`, `VIGIE`} (existant, `ClientSpace.parse`).

---

## Technique

### Endpoint(s)

| Méthode | URL | Garde après |
|---------|-----|-------------|
| GET | `/api/teams/access` | runner (Forge ou Vigie) |
| POST/GET/PUT/DELETE | `/api/runner-hosts`, `/spaces`, `/{id}/spaces/{space}`, `/overview`, `/{id}`, `/{id}/mission`, `/{id}/pairing-code`, `/{id}/tokens[/{tokenId}]`, `/{id}/status`, `/{id}/kill`, `/{id}/teams-terminal` | runner (+ droit de l'espace visé) |
| GET/POST | `/api/runner-hosts/{id}/folders`, `/{id}/projects`, `/{id}/terminal` | Forge (inchangé) |
| * | `/api/workspaces/{id}/chat/**`, `GET /api/workspaces/{id}`, `/{id}/engine`, `/{id}/runner/{status,audit}`, `/{id}/teams/link`, `/{id}/teams/moments/{img}` | terminal (Teams → runner ; sinon Forge) |
| GET | `/api/workspaces?space=VIGIE` | Vigie — nouveau paramètre additif |

### Tables impactées

`workspaces` (lecture de `teams_terminal`), `subscriptions` (lecture). **Aucune migration.**

### Migration Liquibase

Aucune.

### Composants Angular

`atelier.component` (repli sur la liste Vigie), `atelier.service` (`listWorkspaces(space?)`).

### Préoccupations transversales

- [x] **Plans / limites** — gardes de droit d'espace modifiées. Composants impactés :
  `AtelierAccessService` (nouvelles gardes), `RunnerHostController`, `TeamsAccessController`,
  `AtelierController` (`list`, `detail`, `engine`), `AtelierChatController` (toutes routes),
  `RunnerManagementController`, `TeamsLinkController`, `TeamsMomentController`. Non touchés et restant
  Forge : `AtelierAgentController`, `GitWorkspaceController`, `GovernanceHostController`,
  `GovernanceUserController`, le reste d'`AtelierController`. `TeamsAccessService`,
  `SpaceEntitlementService`, `AdministratorEntitlement` : inchangés (règle ADMIN conservée). Quotas :
  non touchés.
- [x] **Navigation / routing** — `/atelier/:id` d'un terminal Teams s'ouvre pour un compte Vigie seul ;
  `/atelier` sans identifiant, `/forge`, `/vigie` : comportements inchangés (vérifiés par les specs
  existantes de `atelier.component`, `postes.component`, `vigie.component`).
- [ ] Auth / Principal — non.
- [ ] Contexte tenant — non (isolation `user_id` inchangée, en aval).

---

## Plan de test

### Tests unitaires

- [ ] `AtelierAccessServiceTest` — garde runner : Forge seul, Vigie seul, ADMIN → ouvert ; aucun droit →
      refus. Garde terminal : terminal Teams possédé + Vigie seul → ouvert ; projet + Vigie seul → refus ;
      workspace inconnu + Vigie seul → refus ; projet + Forge → ouvert.
- [ ] `AtelierChatControllerAttachTest` / `...TurnSurvivalTest` — adaptés à `hasTerminalAccess(id)`.

### Tests d'intégration

- [ ] `VigieRunnerAccessApiIntegrationTest` (nouveau) — CA1 à CA6 avec comptes Gold Vigie, Gold Forge,
      ADMIN, sans droit ; téléchargement du runner public.
- [ ] Non-régression : `HostSpacesApiIntegrationTest`, `TeamsTerminalApiIntegrationTest`,
      `RunnerHostOverviewApiIntegrationTest`, `HostTerminalApiIntegrationTest`, contexte Spring.

### Frontend

- [ ] `atelier.component.spec` — 403 Forge + identifiant demandé → liste Vigie relue, terminal ouvert ;
      403 sans identifiant → présentation Forge (inchangé).
- [ ] `atelier.service.spec` — `listWorkspaces('VIGIE')` envoie `space=VIGIE`.

### Isolation

- [x] Applicable — la garde terminal lit le workspace par `(id, user_id)` du principal ; le terminal
      Teams d'autrui retombe sur la garde Forge (refus) ; test d'intégration dédié.

---

## Dépendances

### Subfeatures bloquantes

SF-107-02, SF-107-03, SF-107-04, SF-106-01, SF-106-03 (livrées).

### Questions ouvertes impactées

Aucune (question PO tranchée le 2026-09-13).

---

## Notes et décisions

- **Gardes dans `AtelierAccessService`** plutôt qu'un nouveau service : un seul endroit pose l'exception
  `atelier_forbidden` que l'écran sait lire ; le droit reste dans `SpaceEntitlementService`.
- **`?space=VIGIE` plutôt qu'une liste filtrée silencieusement** : `/atelier` sans identifiant garde sa
  présentation Forge pour un compte Vigie seul, et la liste Forge ne change pas de sens.
- **Espace visé = droit de l'espace** : un compte Vigie seul ne peut pas activer un client dans la Forge
  ni lire la vue d'ensemble Forge (qui porte les projets) — c'est la page de présentation qui répond.
