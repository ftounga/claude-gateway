# Mini-spec — F-149 / SF-149-01 — Réparer l'application d'une activation

## Identifiant

`F-149 / SF-149-01`

## Feature parente

`F-149` — Déléguer l'audit lourd, politique de modèle, réparer l'activation

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-149-01-reparer-activation`

---

## Objectif

Un **paquet sans fichier** (un profil F-138 : des règles, aucun fichier) doit être marqué **appliqué**
(`applied_at` posé, statut `APPLIED`) **dès l'activation**, même si la machine est injoignable — rien à
déposer = rien qui manque — pour que ses règles soient enfin injectées.

---

## Constat vérifié (correction du cadrage)

Le cadrage affirme qu'« **aucun code actuel ne pose `applied_at`** » (grep exhaustif). **C'est un
faux négatif** : la fonction shell `grep` de l'environnement (wrapper claude, `--ignore-files`) rend
un résultat vide en silence — `rg` sur le fichier montre que `GovernanceDepositService.applyStatus`
(`:512`, appelée en `:149`) **pose bien `applied_at` + `APPLIED`** quand le dépôt aboutit
(`everythingInPlace`), depuis F-96. Ce point du cadrage (« poser `applied_at` au dépôt réussi ») est
donc **déjà satisfait** et couvert par le test `depositCreatesMissingAndNeverOverwrites`.

Le **défaut réel** est le cas **paquet sans fichier** : `deposit()` appelle `depositOn(run, ws, [])`
pour chaque dossier, qui **lit la machine** (`projectFiles.listPaths`) même quand la liste de fichiers
à déposer est **vide**. Machine éteinte → `present.isEmpty()` → `complete = false` →
`everythingInPlace = false` → **jamais `APPLIED`, jamais `applied_at`** → le profil **n'injecte
jamais** (via `deposited()` = `appliedAt != null`, `GovernanceActivationService:271`, F-135). Un profil
activé pendant que le runner est déconnecté est donc **inerte** (F-138 inerte).

---

## Comportement attendu

### Cas nominal

Un dépôt d'un **paquet sans fichier de projet** (`projectScopedFiles` vide — cas d'un profil) ne lit
plus la machine par dossier : `depositOn` court-circuite en `complete = true` (rien à déposer, rien qui
manque). `everythingInPlace` ne dépend alors que de la racine (`rootPlan`, `true` quand le paquet
n'apporte pas de carte). Résultat : `applyStatus` pose `APPLIED` + `applied_at` **dès l'activation**,
que la machine soit joignable ou non.

### Cas d'erreur / de bord

| Situation | Comportement attendu |
|-----------|----------------------|
| Paquet **avec fichiers**, dépôt abouti | `applied_at` posé (inchangé — `applyStatus`) |
| Paquet **avec fichiers**, un dossier illisible / écriture refusée | reste `PENDING`, `applied_at` **nul** (non-régression F-135) |
| Paquet **sans fichier**, machine **injoignable** | `APPLIED` + `applied_at` posé (le correctif) |
| Paquet **sans fichier**, machine joignable | `APPLIED` + `applied_at` posé (déjà le cas ; inchangé) |
| Paquet **carte seule** (MAP), machine injoignable | reste `PENDING` (la carte a un vrai fichier racine à déposer ; inchangé) |

---

## Critères d'acceptation

- [ ] Un paquet **sans fichier** dont la machine est **injoignable** est marqué `APPLIED` avec
      `applied_at != null`, et **`listPaths` n'est pas appelé** (court-circuit).
- [ ] Un paquet **avec fichiers** dont le dépôt aboutit garde `applied_at != null` (non-régression).
- [ ] Un paquet **avec fichiers** dont le dépôt échoue reste `PENDING` avec `applied_at == null`
      (non-régression F-135).
- [ ] `GovernanceActivationService.deposited()`/`activeOn` inchangés : une activation `applied_at`
      posé est rendue par `activeOn` ; `applied_at` nul ne l'est pas.
- [ ] Un profil (slug `profil-`) dont l'activation est `applied_at` posé fournit sa règle via
      `GovernanceRulesProvider.rulesFor` et sa phrase de rôle via `activeProfileRole`.
- [ ] Isolation `user_id` + `host_id` conservée sur tous les chemins touchés.

---

## Périmètre

### Hors scope (explicite)

- Le chemin `depositOnNewProjectQuietly` (dossier créé demain) ne pose jamais `applied_at` et n'est
  **pas** modifié dans son intention (il ne fait que rétrograder en `PENDING` si incomplet — sûr).
- La carte (F-136/137) : elle a de vrais fichiers racine ; son dépôt continue d'exiger la machine.
- Aucune UI. Aucune migration (le champ `applied_at` existe déjà).

---

## Contraintes de validation

Aucun nouveau champ soumis à validation. Le correctif est un court-circuit interne sur une liste de
fichiers vide.

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Chemin existant `POST /api/v1/gouvernance/hosts/{hostRef}/{packageId}`
(activate → deposit).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `governance_activations` | UPDATE (`status`, `applied_at`, `applied_version`) | via `applyStatus` (existant) |

### Migration Liquibase

- [x] Non applicable — le champ `applied_at` existe (`GovernanceActivation:89`).

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `GovernanceDepositServiceTest` — nouveau : paquet sans fichier + machine injoignable
      (`listPaths → Optional.empty()`) → `APPLIED` + `applied_at != null` + `listPaths` jamais appelé.
- [ ] `GovernanceDepositServiceTest` — conservé : `packageWithoutFilesIsAppliedImmediately`
      (machine joignable) reste vert.
- [ ] `GovernanceDepositServiceTest` — conservé : `depositCreatesMissingAndNeverOverwrites`
      (`applied_at != null` sur dépôt de fichiers).
- [ ] `GovernanceDepositServiceTest` — conservé : `partialFailureStaysPending` /
      `unreachableMachineStaysPending` (dépôt échoué → `applied_at == null`).
- [ ] `GovernanceActivationServiceTest` — conservé : `neverDepositedGivesNoRules` /
      `depositedThenUpdatedKeepsItsRules` (barrière `deposited()`).
- [ ] `GovernanceRulesProviderTest` — nouveau : un profil actif `applied_at` posé → `rulesFor` rend
      sa règle et `activeProfileRole` rend sa phrase de rôle.

### Tests d'intégration

- [ ] `ClaudeGatewayBackendApplicationTests` (contexte Spring) reste vert.

### Isolation

- [ ] Applicable — les lectures/écritures d'activation restent bornées `user_id` + `host_id` (chemin
      inchangé).

---

## Dépendances

### Subfeatures bloquantes

Aucune (SF-149-01 est le prérequis des deux autres SF).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Falsification (1re cause ≠ seule cause)** : les deux cas sont couverts — profil sans fichier
  (nouveau vert) ET paquet avec fichiers (dépôt réussi → `applied_at`, dépôt échoué → `null`). La
  non-régression F-135 (dépôt échoué reste non appliqué) est explicitement testée.
- **Cause exacte du correctif** : `depositOn` lit la machine même pour une liste de fichiers vide. On
  court-circuite : `files.isEmpty()` → `ProjectDeposit(readable=true, complete=true, entries=[])`,
  sans toucher la machine. C'est correct (rien à déposer ne peut pas manquer) et rend un profil
  applicable hors ligne.
- **Écart au cadrage documenté** : le point « aucun code ne pose `applied_at` » est un faux négatif du
  `grep` wrappé ; `applyStatus` le pose déjà. Le correctif porte donc uniquement sur le cas sans
  fichier.
