# Mini-spec — F-49 / SF-49-01 — La vue d'ensemble, côté gateway

## Identifiant

`F-49 / SF-49-01`

## Feature parente

`F-49` — Vue d'ensemble des postes

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-49-01-vue-ensemble-postes`

---

## Objectif

Rendre en **un seul appel de lecture** tout ce qu'un écran doit savoir des postes d'un utilisateur :
leur état, les projets qui vivent dessous, et l'activité observée sur chacun.

---

## Comportement attendu

### Cas nominal

`GET /runner-hosts/overview` rend la liste des postes de l'utilisateur, du plus récemment vu au plus
ancien, et pour chacun :

1. **Ce que la machine est** — nom libre, racine déclarée (dernier segment seulement), système,
   interpréteur élu (`posix` / `powershell` / `cmd`, repassé par la liste blanche `RunnerShell`),
   droits (`elevated`), date de création.
2. **Où elle en est** — `connected` (registre **ou** heartbeat frais, tous replicas confondus, comme
   `RunnerStatusService`), `lastSeenAt` — le « depuis quand » se calcule à l'écran, la gateway ne
   rend que l'instant.
3. **Ce qui vit dessous** — ses projets : identifiant, nom, chemin sous la racine, cible d'exécution
   (`RUNNER` / `SANDBOX`), et pour chacun sa dernière activité observée, le dernier outil employé, le
   nombre d'appels dans la fenêtre d'observation, et un drapeau `active`.
4. **Ce qui tourne** — `activeProjects` : combien de projets de ce poste sont actifs maintenant.

L'activité vient du **journal d'audit du runner** (`runner_audit`), agrégé par projet sur une fenêtre
d'observation. Un projet est dit **actif** si sa dernière ligne de journal est plus récente que la
fenêtre d'activité. Les deux fenêtres sont des réglages :

| Réglage | Défaut | Rôle |
|---|---|---|
| `app.runner.overview.observed-window` | `PT1H` | Jusqu'où l'on remonte pour compter et dater l'activité |
| `app.runner.overview.active-within` | `PT2M` | En deçà de quoi un projet est dit « actif maintenant » |

Un poste sans aucun projet, sans runner jamais appairé, ou sans la moindre ligne de journal est un
cas **nominal** : il sort avec des listes vides et des dates nulles, jamais une erreur.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Utilisateur non authentifié | Rejeté par la chaîne JWT | 401 |
| Utilisateur sans accès Atelier | `atelierAccess.requireAccess()` refuse | 403 |
| Aucun poste | Liste vide, pas une erreur | 200 |
| Poste d'un autre utilisateur | **Jamais rendu** — la requête part de `user_id` | 200 (absent) |
| Projet rattaché à un poste d'un autre compte | Impossible : les projets sont lus par `user_id` **et** `host_id` | — |

---

## Critères d'acceptation

- [ ] `GET /runner-hosts/overview` rend un poste par ligne, ordonné par dernière connexion
      décroissante (les jamais-vus en fin, par date de création décroissante).
- [ ] Chaque poste porte `connected`, `lastSeenAt`, `os`, `shell`, `elevated`, `rootName`, `name`,
      `createdAt`.
- [ ] `shell` repasse par `RunnerShell.fromDeclared` : une valeur inconnue sort à `null`, jamais
      relayée telle quelle.
- [ ] Chaque poste porte la liste de ses projets (id, nom, `projectPath`, `executionTarget`),
      ordonnée par activité décroissante puis par nom.
- [ ] Chaque projet porte `lastActivityAt`, `lastTool`, `calls` (fenêtre d'observation) et `active`.
- [ ] `activeProjects` = nombre de projets dont `active` est vrai.
- [ ] Un poste sans projet et sans journal sort avec `projects: []`, `activeProjects: 0`,
      `lastActivityAt: null`.
- [ ] **Isolation** : la vue d'un utilisateur ne contient jamais un poste, un projet ou une ligne de
      journal d'un autre utilisateur — testé.
- [ ] Le journal n'expose ni cible complète non tronquée, ni contenu, ni sortie de commande : seuls
      l'outil, l'instant et un compteur sortent.
- [ ] Aucun canal, aucun flux : l'appel est une lecture, il se termine.

---

## Périmètre

### Hors scope (explicite)

- L'écran (SF-49-02).
- Toute **action** sur un poste : créer, renommer, couper, révoquer existent déjà sous
  `/runner-hosts/**` et ne bougent pas.
- Toute action **sur plusieurs postes à la fois** — hors périmètre F-49.
- Un flux vivant (SSE/WebSocket) par poste — écarté par l'arbitrage n° 3 du cadrage.
- Les projets **non rattachés** à un poste : ils ne sont sous aucune machine, et l'écran des projets
  les montre déjà.
- Toute nouvelle table ou colonne : la vue lit ce qui existe.

---

## Valeurs initiales

Sans objet — la subfeature ne crée aucune entité et n'écrit rien.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|---|---|---|---|---|---|
| `observed-window` (config) | Non | — | Durée ISO-8601, défaut `PT1H` | — | Bornée à `PT24H` |
| `active-within` (config) | Non | — | Durée ISO-8601, défaut `PT2M` | — | Bornée à `observed-window` |
| `shell` (sortie) | Non | 16 | `posix` / `powershell` / `cmd` sinon `null` | — | `RunnerShell.fromDeclared` |
| `lastTool` (sortie) | Non | 32 | Tel que journalisé (déjà tronqué à l'écriture) | — | — |

Notes :
- Aucun paramètre client : l'appel ne prend **rien**. Les fenêtres sont des réglages serveur, pas des
  entrées — un paramètre de fenêtre laisserait un client faire scanner tout le journal.
- `active-within` supérieure à `observed-window` est ramenée à `observed-window` au démarrage : un
  projet ne peut pas être « actif » sur une fenêtre qu'on n'observe pas.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---|---|---|---|
| GET | `/api/runner-hosts/overview` | Oui (JWT) | Utilisateur avec accès Atelier |

> Déclaré **avant** `GET /runner-hosts/{hostId}` n'est pas nécessaire : Spring privilégie le chemin
> littéral sur la variable. Un test le vérifie tout de même, parce que la règle est facile à oublier.

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `runner_hosts` | SELECT | Postes de l'utilisateur (`user_id`) |
| `workspaces` | SELECT | Projets par `user_id` + `host_id` |
| `runner_audit` | SELECT (agrégat) | `group by workspace_id`, filtré `user_id` + `host_id` + fenêtre ; couvert par `idx_runner_audit_user_host_created` |
| `runner_tokens` | SELECT | Heartbeat, via `RunnerStatusService` |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucune table, aucune colonne, aucun index nouveau : la vue lit l'existant,
      et l'index `idx_runner_audit_user_host_created` (migration 064) couvre déjà l'agrégat.

### Composants Angular (si applicable)

Aucun — SF-49-02.

---

## Plan de test

### Tests unitaires

- [ ] `RunnerHostOverviewService` — un poste connecté avec deux projets : les deux sortent, ordonnés
      par activité décroissante.
- [ ] `RunnerHostOverviewService` — un poste sans projet : `projects` vide, `activeProjects` = 0.
- [ ] `RunnerHostOverviewService` — activité de moins de 2 min → `active` vrai ; de plus de 2 min et
      moins d'1 h → `active` faux mais `lastActivityAt` et `calls` renseignés.
- [ ] `RunnerHostOverviewService` — activité plus vieille que la fenêtre d'observation → ignorée.
- [ ] `RunnerHostOverviewService` — `shell` inconnu en base → `null` en sortie.
- [ ] `RunnerHostOverviewService` — ordre des postes : dernier vu d'abord, jamais-vus ensuite.
- [ ] `RunnerHostOverviewService` — `active-within` > `observed-window` → ramenée à la fenêtre.

### Tests d'intégration

- [ ] `GET /runner-hosts/overview` → 200 et la forme attendue pour un utilisateur avec un poste.
- [ ] `GET /runner-hosts/overview` → 401 sans JWT.
- [ ] `GET /runner-hosts/overview` → 403 sans accès Atelier.
- [ ] `GET /runner-hosts/overview` → 200 et `[]` pour un utilisateur sans poste.
- [ ] `/runner-hosts/overview` n'est pas capté par `/runner-hosts/{hostId}` (pas de 400 sur l'UUID).

### Isolation workspace

- [x] Applicable — test : l'utilisateur A ne voit ni le poste de B, ni les projets de B rattachés à
      un poste de B, ni une ligne de journal de B.

---

## Dépendances

### Subfeatures bloquantes

- `SF-48-01` (le poste, modèle et appairage unique) — **done**
- `SF-48-03` (rattacher un projet à un poste) — **done**

### Questions ouvertes impactées

- [ ] Aucune. L'arbitrage n° 3 du cadrage (vue d'état, pas de flux vivants) est **tranché** le
      2026-09-10.

---

## Notes et décisions

**D1 — L'activité vient du journal d'audit, pas d'un registre en mémoire.** Le dispatcher connaît
les appels en vol (`inFlight`), mais **par pod** : sous HPA, un écran servi par le replica B ne
verrait rien de ce qui tourne sur le replica A. Le journal est en base, partagé, et déjà indexé par
`(user_id, host_id, created_at)`. La contrepartie est assumée : l'activité est celle des appels
**terminés**, donc un tour qui vient de démarrer apparaît avec le retard de son premier outil. Pour
une vue d'état rafraîchie, c'est le bon compromis ; un suivi à la seconde près, c'est le terminal du
projet, et il est à un clic. **Réversible** : le jour où la présence des appels en vol serait
diffusée entre pods, elle s'ajouterait au même DTO.

**D2 — Un appel d'agrégat, pas N appels.** L'écran aurait pu boucler sur `GET /runner-hosts` puis
`GET /workspaces` puis un journal par projet. Trois familles d'appels rejouées à chaque
rafraîchissement, pour une vue dont l'intérêt est justement de tout dire d'un coup. Un endpoint de
**vue** est ici le bon grain : il est en lecture seule, il ne duplique aucune règle métier, et il
tient la promesse de l'arbitrage n° 3 — un rafraîchissement = un appel.

**D3 — Aucun paramètre client.** Ni fenêtre, ni limite, ni pagination. Un utilisateur a quelques
postes, pas mille ; et une fenêtre pilotée par le client est une invitation à faire scanner tout le
journal depuis un navigateur.

**D4 — `lastTool` sort, la cible ne sort pas.** Savoir qu'un `bash` a tourné il y a 30 secondes suffit
à une vue d'état. La commande elle-même est déjà dans le journal du projet, derrière son écran, avec
sa troncature ; la recopier dans une vue rafraîchie toutes les 15 secondes l'exposerait bien plus
souvent, pour rien.
