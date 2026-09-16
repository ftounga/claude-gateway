# Mini-spec — F-121 / SF-121-02 — Modèle de permission allow/ask/deny par outil + « toujours autoriser cette commande »

## Identifiant

`F-121 / SF-121-02`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`in-progress`

## Date de création

2026-09-16

## Branche Git

`feat/SF-121-02-permissions`

---

## Objectif

Remplacer la porte de confirmation binaire (autoriser/refuser, catégories câblées, remise à zéro
chaque tour) par une **politique de permission allow/ask/deny par outil — et par préfixe de commande
pour bash — persistée par workspace/user**, avec « toujours autoriser cette commande » et l'« ask »
étendu aux éditions (équivalent *acceptEdits*, configurable).

---

## Comportement attendu

### Cas nominal

- Pour chaque appel d'outil en cible RUNNER (hors écriture Teams, confirmée à chaque fois par
  SF-108-02), la boucle résout un **effet** :
  - **DENY** → l'appel est refusé **avant émission**, sans invite ; le modèle reçoit un motif, l'audit
    trace un refus.
  - **ASK** → une invite d'autorisation est posée à l'écran (sauf « tout autoriser pour ce message »).
  - **ALLOW** → l'appel s'exécute sans demander.
- L'effet vient de la **règle persistée la plus spécifique** (`atelier_permission_rules`) : pour
  `bash`, une règle de **préfixe de commande** l'emporte sur une règle d'outil, et le préfixe le plus
  long l'emporte. À défaut de règle, un **défaut** s'applique : `bash` demande selon
  `agent_ask_before_bash` (inchangé) ; une **édition** (`edit_file`/`write_file`) demande selon
  `app.atelier.ask-before-edit` (défaut `false` = comportement d'avant) ; tout le reste s'exécute.
- L'invite propose « **toujours autoriser cette commande** » : cochée, elle écrit une règle
  persistante (pour `bash`, sur le **premier mot** de la commande ; sinon sur l'outil entier), qui
  survit au tour **et au redémarrage** (relue à chaque tour).
- Isolation : toute lecture/écriture de règle filtre `(user_id, workspace_id)`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Règle DENY sur l'outil | refus immédiat, message « refusé par une règle de permission », rien n'est émis, audit `DENIED` |
| ASK non tranché dans le délai | refus (le silence ne vaut pas autorisation), audit `TIMEOUT` |
| Effet illisible en base | repli sûr sur `ASK` (jamais `ALLOW` par accident) |
| Politique non branchée (formes historiques) | repli exact sur le comportement binaire d'avant (bash selon `agent_ask_before_bash`, éditions non confirmées) |

---

## Critères d'acceptation

- [ ] Une règle « toujours autoriser cette commande » **persiste entre tours et redémarrage** (relue en base à chaque tour).
- [ ] Une règle **DENY** bloque l'outil avant toute émission, sans invite.
- [ ] L'« ask » s'applique aux **éditions** quand `app.atelier.ask-before-edit=true` ; pas par défaut.
- [ ] Pour `bash`, une règle de **préfixe de commande** l'emporte sur une règle d'outil ; le préfixe le plus long gagne.
- [ ] Isolation `(user_id, workspace_id)` sur toute lecture et écriture de règle.
- [ ] Rétrocompatibilité : sans politique branchée, comportement d'avant SF-121-02 à l'identique.
- [ ] Les règles d'un compte/projet sont purgées à la suppression du compte / du projet.

---

## Périmètre

### Hors scope (explicite)

- **Le bouton Angular « toujours autoriser cette commande »** : le backend expose tout le nécessaire
  (l'événement `confirm_request` porte `allowAlwaysOffered`, l'endpoint `POST …/confirm` accepte
  `alwaysAllowCommand`). Le composant frontend est **planifié en SF-121-02-FE** (voir Analyse
  transversale). En attendant, l'API est pilotable et testée de bout en bout côté serveur.
- Une UI d'édition/liste des règles persistées (gérer/supprimer une règle) — hors Lot 1.
- Le portage de la politique à la cible SANDBOX (la porte de confirmation est propre au RUNNER).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs |
|-------|-------------|-------------|-------------------|
| `tool` | Oui | 32 | nom d'outil |
| `command_prefix` | Non (bash) | 512 | premier mot de commande (ALLOW « toujours autoriser ») |
| `effect` | Oui | 8 | `ALLOW` \| `ASK` \| `DENY` (repli `ASK`) |
| `alwaysAllowCommand` (API) | Non | — | booléen ; n'a d'effet que si la décision autorise |
| `app.atelier.ask-before-edit` | Non | — | booléen, défaut `false` |

---

## Technique

### Endpoint(s)

| Méthode | URL | Changement |
|---------|-----|-----------|
| POST | `/api/workspaces/{id}/chat/confirm` | champ additif `alwaysAllowCommand` (rétrocompatible) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_permission_rules` (**neuve**) | INSERT / SELECT / UPDATE / DELETE | migration `075` ; index `(user_id, workspace_id)` |

### Migration Liquibase

- [x] Oui — `075-atelier-permission-rules.xml` (PostgreSQL + H2), réversible (`dropTable`).

### Composants impactés

| Composant | Changement |
|-----------|-----------|
| `AtelierPermissionRule` / `PermissionEffect` / `AtelierPermissionRuleRepository` / `AtelierPermissionService` (**neufs**) | entité, enum, repo, service (résolution + persistance) |
| `AtelierChatService` | gating par politique dans `executeToolOnRunner`, défauts, persistance à la confirmation, `confirmToolUse` (+`alwaysAllowCommand`), injection setter du service + `ask-before-edit` |
| `RunnerConfirmationGate` | `Outcome.persistRule` + `resolve(...persistRule)` |
| `AgentConfirmRequest` / `AtelierChatController` | champ `alwaysAllowCommand` relayé |
| `AtelierProgressListener.AtelierConfirmRequest` | champ `allowAlwaysOffered` (porté au flux SSE) |
| `AccountService` / `WorkspaceService` | purge des règles (compte / projet), par mutateur |

### Analyse transversale (obligatoire)

- **Auth / Principal** : aucun nouveau type d'auth, aucun changement du Principal. L'endpoint
  `/confirm` reste protégé par `requireTerminalAccess` (isolation `user_id`) ; le champ ajouté est
  additif. Endpoints utilisant l'auth : inchangés (seul `/confirm` reçoit un champ optionnel).
- **Contexte tenant** : aucun nouveau moyen de résoudre le tenant. Toute règle est lue/écrite via
  `(user_id, workspace_id)` (repository), `requireOwned` reste appliqué d'abord dans `confirmToolUse`.
- **Plans / limites** : nouveau **gate** (la politique de permission). Composants appelant un gate :
  `AtelierChatService.executeToolOnRunner` (seul point de décision) ; les écritures Teams (SF-108-02)
  et le « tout autoriser pour ce message » (SF-38-20) restent des chemins distincts, non modifiés
  dans leur intention (l'écriture Teams reste confirmée à chaque fois ; le blanket reste borné au tour).
- **Navigation / routing** : aucune route ajoutée. **Frontend** : le composant d'invite de
  confirmation du terminal recevra un bouton « toujours autoriser cette commande » (planifié
  **SF-121-02-FE**) ; en attendant, le flux SSE porte déjà `allowAlwaysOffered` et l'API accepte
  `alwaysAllowCommand`.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierPermissionServiceTest` : aucune règle → vide ; DENY d'outil ; préfixe de commande l'emporte ; préfixe le plus long gagne ; `alwaysAllowCommand` (bash → premier mot ; non-bash → outil) ; `setRule` upsert ; isolation `(user, workspace)`.
- [ ] `AtelierChatServicePermissionTest` : DENY bloque bash avant émission (aucun appel runner, aucune invite, audit refusé) ; règle ALLOW persistée exécute sans invite ; « ask » sur édition si configuré, sinon non ; « toujours autoriser » à la confirmation écrit une règle.

### Tests d'intégration / non-régression

- [ ] `AtelierChatServiceRelayTest` (résolution via la variante persist-aware) ; boot du contexte + Liquibase (migration `075`).

### Isolation workspace

- [ ] Applicable — toute lecture/écriture filtre `(user_id, workspace_id)`.

---

## Notes et décisions

- **Défaut « ask before edit »** exposé par `app.atelier.ask-before-edit` (@Value, défaut `false`)
  plutôt que par une colonne de workspace ou un composant `AtelierProperties` : plus faible surface,
  réversible sans livraison, et sans toucher aux nombreux constructeurs de compatibilité d'`AtelierProperties`.
- **Clef d'une règle « toujours autoriser » bash = premier mot** de la commande (`git`, `npm`, …) :
  choix prévisible et déterministe, opt-in explicite de l'utilisateur. Une règle DENY/ASK plus fine
  (préfixe plus long) reste exprimable et l'emporte.
- **Persistance branchée par mutateur** (`setPermissionService`) : `null` (formes historiques, tests
  antérieurs) ⇒ repli exact sur la porte binaire d'avant SF-121-02 (rétrocompatibilité stricte).
- Purge : à la suppression du **compte** (`AccountService`) et du **projet** (`WorkspaceService`),
  par injection-mutateur (aucun constructeur touché, aucun test existant cassé).
