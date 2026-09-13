# Mini-spec — [F-111 / SF-111-01] La version réelle et la Forge qui l'affiche

## Identifiant

`F-111 / SF-111-01`

## Feature parente

`F-111` — Le runner se met à jour d'un clic (cadrage : `CADRAGE-F-111-le-runner-se-met-a-jour.md`)

## Statut

`done` — PR #564 mergée le 2026-09-13

## Date de création

2026-09-13

## Branche Git

`feat/SF-111-01-version-reelle`

---

## Objectif

Le runner déclare à la connexion sa **vraie version de construction** (numéro sémantique + date + commit),
son **niveau de contrat**, sa version de Java, la présence d'un lanceur et ses capacités ; la gateway les
retient sur le poste, les compare à la version qu'elle sert, et la Forge comme la Vigie affichent
« à jour / Mise à jour disponible / requise / manuelle une dernière fois » dans la colonne des postes et
l'en-tête du client.

---

## Comportement attendu

### Cas nominal

1. **Construction** : `runner/pom.xml` porte le numéro sémantique (`1.0.0`, première version de F-111).
   Maven filtre une ressource `runner-build.properties` : `version`, `stamp` (date UTC `AAAAMMJJHHmm`,
   `maven.build.timestamp`), `commit` (propriété `runner.commit`, injectée par `backend/Dockerfile` via
   `ARG RUNNER_COMMIT`, `local` par défaut). L'**identifiant** de version est
   `<version>-<stamp>-<commit>`, ex. `1.0.0-202609131412-f30b4c0`.
2. **Trame `ready`** (les deux transports, même aiguilleur) : `runnerVersion` = identifiant (champ
   existant), plus `runnerBuild` `{version, stamp, commit}`, `contract` (niveau de contrat, `1` ici),
   `javaVersion` (version majeure de la JVM), `launcher` (vrai si le processus a été démarré par le
   lanceur de SF-111-02 ; `false` dans cette SF).
3. **Gateway** : `RunnerCallDispatcher.onReady` confie la déclaration complète au port
   `RunnerVersionRecorder` (méthode par défaut `recordRunnerDeclaration`, rétro-compatible) ;
   `RunnerHostService` persiste version, contrat, Java, lanceur, capacités sur `runner_hosts`
   (migration **101**), pour le `hostId` de la session uniquement.
4. **Version servie** : `ServedRunnerVersion` lit `runner-build.properties` **dans le jar servi**
   (identifiant, date, commit, contrat, Java minimal = 21) ; repli sur `Implementation-Version` ;
   `app.runner.min-version` garde la priorité.
5. **Comparaison** (`RunnerVersions`) : numéro sémantique d'abord, puis la date de construction si les
   deux versions en portent une. Une version illisible n'est jamais comparée.
6. **Conseil** (`RunnerUpdateAdvisor`, pur) rendu dans la vue d'ensemble (`runnerUpdate`) :

   | Statut | Condition |
   |---|---|
   | `UNKNOWN` | aucune version servie connue, ou aucune version déclarée / illisible |
   | `UP_TO_DATE` | version déclarée ≥ version servie |
   | `MANUAL_LAST_TIME` | plus ancienne **et** runner sans lanceur (`launcher` absent ou faux) |
   | `MANUAL_JAVA` | plus ancienne, lanceur présent, mais Java déclaré < Java minimal servi |
   | `AVAILABLE` | plus ancienne, lanceur présent, Java suffisant |

   `required` (« Mise à jour requise ») : plus ancienne **et** le poste sert Teams (terminal Teams
   ouvert, ou client actif dans la Vigie) **et** le runner n'annonce pas `teams` **et** ne déclare
   aucun contrat (runner antérieur à F-111 — un runner récent sans `teams` a été lancé avec
   `--no-teams`, une mise à jour n'y changerait rien).
7. **Écrans** (Forge et Vigie, composant partagé) :
   - **Colonne des postes** (`app-forge-rail`) : sous l'état, « Mise à jour disponible » /
     « Mise à jour requise » / « Mise à jour manuelle » en toutes lettres (pastille existante).
   - **En-tête du client** (`postes` et `vigie`) : « Runner 1.0.0 » + libellé du statut ; pour
     `AVAILABLE` : « Mise à jour disponible — 1.0.0 → 1.1.0 » ; pour `MANUAL_LAST_TIME` : « Runner sans
     mise à jour automatique : mise à jour manuelle une dernière fois » avec la **commande `curl`
     exacte** selon le système du poste (`runnerUpdateCommands`, réutilisée) ; pour `MANUAL_JAVA` :
     « Mise à jour manuelle requise (Java N requis) » + commande. Le bouton « Mettre à jour » arrive en
     SF-111-04.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Runner ancien sans `runnerBuild` / `contract` | Version retenue telle quelle, contrat/Java/lanceur nuls → `MANUAL_LAST_TIME` si plus ancien | — |
| Champ `contract`/`javaVersion` non numérique, capacités trop longues | Champ ignoré, rien n'est tronqué, la liaison continue | — |
| Écriture en base en échec pendant `ready` | Journal `WARN`, liaison maintenue (best-effort existant) | — |
| Jar servi absent (dev, tests) | `UNKNOWN`, aucun libellé de mise à jour | — |
| Vue d'ensemble d'un autre utilisateur | Inchangée : lecture par `user_id` uniquement | 200 (sans ses postes) |

---

## Critères d'acceptation

- [ ] CA1 — Le jar construit contient `runner-build.properties` ; `RunnerBuild.current().id()` rend `1.0.0-<stamp>-<commit>` (plus aucun `0.0.1`).
- [ ] CA2 — La trame `ready` porte `runnerVersion` (identifiant), `runnerBuild`, `contract`, `javaVersion`, `launcher`, sur WebSocket comme en long-polling (même `readyFrame`).
- [ ] CA3 — La gateway persiste version, contrat, Java, lanceur et capacités sur le poste de la session ; valeurs invalides ignorées.
- [ ] CA4 — `ServedRunnerVersion` rend l'identifiant, le contrat et le Java minimal du jar servi.
- [ ] CA5 — `RunnerUpdateAdvisor` rend les 5 statuts et `required` selon la table ci-dessus (tests unitaires).
- [ ] CA6 — `GET /runner-hosts/overview` rend `runnerUpdate` pour chaque poste (nul pour « Hébergé »).
- [ ] CA7 — Colonne des postes et en-tête du client, Forge et Vigie, affichent la version et le statut ; `MANUAL_LAST_TIME` affiche la commande `curl` du système du poste.
- [ ] CA8 — Isolation : la déclaration s'écrit sur le `hostId` de la session, jamais d'un champ de trame ; la vue ne lit que les postes de l'utilisateur.
- [ ] CA9 — Aucune couleur nouvelle (jetons `DESIGN_SYSTEM.md`, classes `badge--*` existantes).

---

## Périmètre

### Hors scope (explicite)

- Le lanceur (SF-111-02), la signature et la route de mise à jour (SF-111-03), le bouton et la commande
  `update` (SF-111-04), le retour arrière (SF-111-05).
- Toute modification du script de déploiement ou de l'infrastructure.
- La liste « ce qu'elle apporte » détaillée : un champ `notes` est prévu vide ici, rempli par le
  manifeste de SF-111-03.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `runner_contract` | `null` | écrit à la première trame `ready` qui le déclare |
| `runner_java` | `null` | idem |
| `runner_launcher` | `null` | idem (`null` = runner antérieur, traité comme sans lanceur) |
| `runner_capabilities` | `null` | liste séparée par des virgules, écrite à chaque `ready` |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `runnerVersion` | Non | 64 | texte (existant) | Non | trim ; ignoré si vide/trop long |
| `contract` | Non | — | entier 1..1000 | Non | ignoré sinon |
| `javaVersion` | Non | — | entier 8..1000 | Non | ignoré sinon |
| `launcher` | Non | — | booléen JSON | Non | ignoré sinon |
| `capabilities` | Non | 255 (joint) | noms `[a-z0-9_-]{1,32}` | Non | ignorés sinon ; liste non écrite si > 255 |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/runner-hosts/overview` (existant, champ ajouté) | JWT | droit Forge ou Vigie (inchangé) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_hosts` | ALTER + UPDATE | 4 colonnes nullables |

### Migration Liquibase

- [x] Oui — `101-runner-hosts-runner-declaration.xml` (premier numéro libre au-dessus de 097 et des 098–100 réservés)

### Composants Angular

- `RunnerUpdateNoticeComponent` (nouveau, `shared`) — version, statut, commande manuelle ; utilisé dans l'en-tête Forge et Vigie.
- `ForgeRailComponent` — libellé court du statut.
- `postes.component`, `vigie.component` — intègrent le composant.

### Préoccupations transversales

- Auth / Principal : **non**. Contexte tenant : **non** (lecture existante par `user_id`). Plans / limites : **non**. Navigation : **non**.
- Sécurité : la déclaration est une information, **aucun refus** n'en découle (règle SF-81-03 conservée).

---

## Plan de test

### Tests unitaires

- [ ] runner `RunnerBuildTest` — identifiant, repli sans ressource, Java courant.
- [ ] runner `ToolDispatcher`/ready — champs `runnerBuild`, `contract`, `javaVersion`, `launcher`.
- [ ] backend `RunnerVersionsTest` — ordre par date à numéro égal ; version sans date non comparée par date.
- [ ] backend `RunnerUpdateAdvisorTest` — 5 statuts + `required` (Teams, contrat).
- [ ] backend `ServedRunnerVersionTest` — lecture de `runner-build.properties` dans un jar de test.
- [ ] backend `RunnerHostServiceTest` — déclaration persistée ; valeurs invalides ignorées.
- [ ] backend `RunnerCallDispatcherTest` — `ready` confie la déclaration complète.
- [ ] frontend — composant de conseil (4 libellés + commande par système), colonne des postes.

### Tests d'intégration

- [ ] `RunnerHostOverviewApiIntegrationTest` — `runnerUpdate` rendu ; contexte Spring + Liquibase (migration 101 jouée).

### Isolation workspace

- [x] Applicable — la déclaration ne s'écrit que sur le poste de la session (test dispatcher) ; la vue d'un utilisateur B ne rend pas le poste de A (test existant conservé).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF-81-03 done).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Identifiant `<semver>-<AAAAMMJJHHmm>-<commit>`** : la date départage deux constructions au même
  numéro sémantique (un numéro oublié ne bloque pas une mise à jour) ; le commit identifie le code. Le
  `-` garde la compatibilité avec la lecture minimale de SF-81-03 (qui ignore ce qui suit).
- **D2 — Contrat `1`** dans cette SF ; il passera à `2` avec la commande `update` (SF-111-04). La gateway
  ne propose le bouton qu'à un runner `launcher=true` et `contract>=2`.
- **D3 — `required` limité aux runners antérieurs à F-111** privés de `teams` : c'est le cas vécu
  (`unsupported_tool`) ; au-delà, l'absence de `teams` est un choix (`--no-teams`).
