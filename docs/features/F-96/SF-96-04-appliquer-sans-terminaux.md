# Mini-spec — [F-96 / SF-96-04] Appliquer met le poste à jour sans exiger les pseudo-terminaux

## Identifiant

`F-96 / SF-96-04`

## Feature parente

`F-96` — La gouvernance se met à jour

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-96-04-appliquer-sans-terminaux`

---

## Objectif

> En une phrase : exclure les **workspaces terminaux** (Terminal du poste F-74, Terminal Teams F-89) du périmètre de dépôt de la gouvernance, pour que « Appliquer » éteigne le bandeau « une version plus récente existe » dès que les **vrais projets** et la **racine** (carte) sont déposés — et nommer, sinon, le dossier réellement bloquant.

---

## Comportement attendu

### Cas nominal

Un « Appliquer » (`POST /governance/hosts/{ref}/{pkg}/apply` → `GovernanceDepositService.deposit`) dépose
la carte (racine, `MAP`) et les artefacts de projet (`TEMPLATE`/`SKILL`) dans **chaque vrai projet** du
poste. Quand la racine et tous les **vrais** projets sont complets, `applied_version` est bumpé à la
version du paquet, l'activation passe `APPLIED`, et le bandeau outdated disparaît.

Les **terminaux** (Terminal du poste, Terminal Teams) sont **hors périmètre de dépôt** : ils n'ont pas
de dossier de projet réel (ils vivent à la racine de la machine), ne reçoivent aucun artefact de projet
et ne retiennent jamais l'activation « en attente ».

### Cause racine corrigée

`depositOnNewProjectQuietly` (déclenché par `WorkspaceCreatedEvent` lors de la création d'un terminal via
`openHostTerminal`/`openTeamsTerminal`) déposait les gabarits/skills **sur le terminal** (`hostScope.projectOf`
= `requireOwned`, sans contrôle de nature) — les posant à la **racine de la machine**, à côté de la carte,
et remettant l'activation `PENDING`. Le dépôt d'apply (`run()`), lui, itère `projectsOf` = `listByHost`,
qui exclut déjà les terminaux (F-89) ; un garde-fou explicite y est ajouté par défense en profondeur.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Un vrai projet illisible (machine éteinte, dossier absent) | Activation reste `PENDING`, `applied_version` inchangé ; le compte rendu **nomme** le(s) dossier(s) bloquant(s) et dit que c'est pourquoi la mise à jour reste signalée | 200 |
| Création d'un terminal | Aucun dépôt sur le terminal ; l'activation n'est **pas** remise en attente | — |
| Fichier modifié localement (`KEEP_LOCAL`) | Jamais écrasé (idempotence + contenu utilisateur préservés) | 200 |

---

## Critères d'acceptation

- [ ] Les workspaces terminaux (nature `host_terminal` ou `teams_terminal`, jamais par leur nom) sont exclus du périmètre de dépôt (`depositOnNewProjectQuietly` + garde-fou dans `run()`).
- [ ] Sur un poste avec un projet réel + un Terminal Teams, après `deposit()`, `applied_version` = version du paquet et `outdated` = 0 ; le terminal n'est ni lu ni écrit.
- [ ] Créer un terminal ne dépose rien dessus et ne remet pas l'activation `PENDING`.
- [ ] Quand un dépôt reste `PENDING`, le compte rendu frontend **nomme** le(s) dossier(s) bloquant(s) et explique le lien avec le bandeau.
- [ ] Idempotence et `KEEP_LOCAL` inchangés : le contenu utilisateur n'est jamais écrasé.
- [ ] Isolation `user_id` + `host_id` inchangée.

---

## Périmètre

### Hors scope (explicite)

- Le semis du paquet / la carte vide (BUG 1 / SF-92-04).
- La reformulation du libellé du bandeau lui-même (au-delà du compte rendu de dépôt).
- Toute migration de schéma (aucune).
- Rendre un vrai projet illisible « non bloquant » pour la version (un vrai projet non déposé continue, à dessein, de retenir l'activation).

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Notes |
|---------|-----|------|-------|
| POST | `/governance/hosts/{ref}/{pkg}/apply` | Oui | dépôt sans terminaux ; version bumpée si racine + vrais projets complets |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `governance_host_activations` | UPDATE | `applied_version`/`status` — plus jamais retenus par un terminal |
| `governance_deposited_files` | INSERT/UPDATE | inchangé ; plus d'empreintes déposées sur un terminal |

### Migration Liquibase

- [x] Non applicable (correctif de logique)

### Composants Angular

- `GovernanceComponent` — compte rendu `report()` nommant les dossiers bloquants ; test de rendu.

---

## Préoccupations transversales — analyse d'impact

- **Contexte tenant** : la résolution du poste et l'isolation `user_id`/`host_id` ne changent pas ; seule la nature (terminal vs projet) du workspace filtre le dépôt. Composants vérifiés : `GovernanceDepositService.deposit` / `depositOnNewProjectQuietly` / `run` ; `GovernanceHostScope.projectsOf` (déjà `listByHost`, exclut les terminaux). Autres appelants de `projectsOf` (`IntegriteInspection`, `GovernanceFileReadingService`, `JugeMatiereReader`, `GovernanceActivationService`) inchangés : ils lisent déjà `listByHost` (sans terminaux). Aucune régression.

---

## Plan de test

### Tests unitaires

- [ ] `GovernanceDepositServiceTest` — poste avec projet réel + Terminal Teams → `deposit()` bumpe `applied_version`, terminal jamais lu/écrit (échoue avant).
- [ ] `GovernanceDepositServiceTest` — `depositOnNewProjectQuietly` sur un terminal → aucun dépôt, activation non remise en attente (échoue avant).

### Frontend

- [ ] `governance.component.spec` — un dépôt `PENDING` nomme le dossier bloquant dans le compte rendu.

### Isolation

- [x] Inchangée : dépôt par `user_id` + `host_id`.

---

## Dépendances

- SF-96-01/02/03 (Done). SF-92-04 (BUG 1) — indépendant, mergé.
- F-74 (Terminal du poste), F-89 (Terminal Teams) — nature des workspaces.

---

## Notes et décisions

- Terminaux reconnus par leur **nature** (`Workspace.isHostTerminal()`/`isTeamsTerminal()`), jamais par leur nom.
- La correction ne rend **pas** un vrai projet illisible « non bloquant » : un vrai projet non déposé retient l'activation à dessein, et le compte rendu le nomme désormais.
