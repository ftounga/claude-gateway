# Mini-spec — [F-121 / SF-121-21] Bloc « environnement » injecté (date, git, OS, cwd)

## Identifiant

`F-121 / SF-121-21`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-121-21-bloc-environnement`

---

## Objectif

Injecter, en tête du préfixe système stable de la boucle maison (`AtelierChatService.buildSystemPrompt`),
un bloc « Environnement » **stable entre tours** — date du jour, répertoire de travail, dépôt git
(is-git + branche + statut court en instantané), plateforme/OS et shell — à la manière du bloc `<env>`
de Claude Code, sur les deux cibles SANDBOX et RUNNER.

---

## Comportement attendu

### Cas nominal

- `buildSystemPrompt` insère, juste après l'amorce de rôle (SF-148-02 préservée : le rôle reste la
  1re phrase) et avant les doctrines d'investigation, un bloc `--- Environnement ---`.
- **Cible SANDBOX** : Date du jour (jour, pas d'horodatage), répertoire de travail (chemin projet ou
  racine), plateforme = « espace de travail hébergé », dépôt git déduit des colonnes du workspace
  (`source=GIT` → `oui (branche : <git_branch>)`, sinon `non`). Pas de statut git (le stockage objet
  n'est pas une copie git vivante).
- **Cible RUNNER** : Date du jour, répertoire de travail (`root_name` du poste + `project_path`),
  plateforme/OS (`runner_hosts.os` remonté à l'appairage), shell (`runner_hosts.shell` élu au `ready`),
  et un **instantané git** capturé une seule fois via une commande bornée (`git status --short
  --branch`) exécutée hors du chemin critique par `PromptSourceStore.refresh` puis **figé** (write-once)
  et servi verbatim — `oui (branche : X)` + statut court borné, ou `non`.
- **Impératif cache (F-134)** : la seule donnée recalculée par tour est la date, à la granularité du
  **jour** (ne change pas d'un tour à l'autre) ; toutes les autres données sont stables (propriétés de
  poste/workspace) ou figées (instantané git write-once). Le préfixe reste donc byte-stable entre deux
  tours d'une même journée → cache préservé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Poste sans OS déclaré (`os = null`) | Ligne OS omise ; le reste du bloc rendu normalement |
| Shell non déclaré | Repli POSIX (`RunnerShell.resolve`), jamais d'échec |
| Instantané git pas encore capturé (1er tour, cache non amorcé) | Sous-bloc git omis ; capturé après le tour, présent au tour suivant (1 transition, comme l'amorçage F-148) |
| Commande git en échec de transport | Rien n'est figé ; ré-essayé au prochain refresh (jamais de tour raté) |
| Répertoire non git | Instantané figé à `Dépôt git : non` (sentinelle) — n'interroge plus git |
| Magasin de cache en panne | Bloc rendu sans le sous-bloc git (repli passant), jamais de tour raté |

---

## Critères d'acceptation

- [ ] Le bloc `--- Environnement ---` apparaît dans la consigne système sur les **deux** cibles.
- [ ] Le bloc porte la date du **jour** (format ISO `yyyy-MM-dd`), pas un horodatage à la seconde.
- [ ] Sur SANDBOX, un workspace `source=GIT` affiche `oui (branche : <git_branch>)`, un workspace
      archive affiche `non`.
- [ ] Sur RUNNER, l'OS et le shell déclarés du poste apparaissent quand ils sont connus.
- [ ] Sur RUNNER, l'instantané git figé (write-once) est servi verbatim et n'est pas ré-interrogé une
      fois capturé (stabilité du préfixe prouvée : deux rendus successifs identiques à l'octet).
- [ ] Deux appels consécutifs à `buildSystemPrompt` le même jour, environnement inchangé, produisent
      un bloc identique à l'octet (garantie cache F-134).
- [ ] Les doctrines existantes (investigation SF-119-02, retenue SF-120-01, style SF-121-03…) restent
      présentes et inchangées ; le rôle reste la première phrase (SF-148-02).
- [ ] Isolation : l'instantané git est lu/écrit sous `(user_id, workspace_id)` via le cache existant,
      jamais partagé entre workspaces.

---

## Périmètre

### Hors scope (explicite)

- Rafraîchissement live du statut git en cours de session (choix délibéré : instantané figé =
  sémantique Claude Code « snapshot in time, will not update during the conversation », et seule façon
  de ne pas casser le cache à chaque édition de fichier). Une branche changée en cours de vie du
  workspace n'est reflétée qu'après purge/ré-amorçage du cache.
- Toute nouvelle capacité IA, tout appel provider en dur (Provider Independence conservée : aucun modèle
  nommé, on ne touche qu'à la construction de consigne).
- Toute modification du protocole runner (on réutilise l'outil `bash` déjà au contrat).
- Frontend : aucun (bloc de consigne, invisible à l'écran).

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `prompt_source_files` | SELECT / INSERT | Réutilisée telle quelle : l'instantané git est rangé comme un pseudo-fichier au chemin réservé `/__prompt_source_env__` (même mécanique que `/__prompt_source_tree__`). **Aucune colonne, aucune table nouvelle.** |
| `runner_hosts` | SELECT | Lecture de `os`, `shell`, `root_name` (déjà stockés). |
| `workspaces` | SELECT | Lecture de `source`, `git_branch`, `project_path` (déjà chargés). |

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucun changement de schéma (pseudo-fichier dans la table existante).

### Composants Angular (si applicable)

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierEnvironmentBlockTest` — formatteur pur : rendu SANDBOX (git oui/non), rendu RUNNER
      (OS/shell présents, OS absent omis, shell défaut POSIX), date du jour présente, instantané git
      embarqué verbatim, deux rendus identiques (stabilité).
- [ ] `PromptSourceStoreTest` — capture write-once de l'instantané git : capturé une fois, figé
      ensuite ; sentinelle `non` sur répertoire non git ; rien figé sur échec de transport.

### Tests d'intégration (boucle)

- [ ] `AtelierChatServiceSystemPromptTest` — le bloc `--- Environnement ---` apparaît sur SANDBOX et
      RUNNER ; coexiste avec discipline/doctrine/style ; le rôle reste 1re phrase ; stabilité entre
      deux appels.

### Isolation workspace

- [x] Applicable — l'instantané git passe par le cache `(user_id, workspace_id)` ; test que le pseudo-
      fichier d'un workspace n'est pas servi à un autre (couvert par l'isolation existante de
      `PromptSourceStore`, réaffirmée).

---

## Dépendances

### Subfeatures bloquantes

- `F-148 / SF-148-06` (cache des sources de consigne) — Done ; réutilisé.
- `F-134` (préfixe stable) — contrainte de conception, respectée.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **D1 — Instantané git figé (write-once)** plutôt que rafraîchi par digest : le `git status` change à
  chaque édition ; rafraîchi, il casserait le cache à chaque tour d'écriture (l'anti-pattern F-134).
  Figé, il est byte-stable pour toute la vie du cache du workspace — exactement la sémantique de Claude
  Code. Le sous-bloc git est étiqueté « instantané au démarrage, non rafraîchi en cours de session ».
- **D2 — Réutilisation de `prompt_source_files`** (pas de table neuve) : l'instantané est un pseudo-
  fichier au chemin réservé `/__prompt_source_env__`, capturé dans `PromptSourceStore.refresh` (déjà
  hors chemin critique, throttlé, cible RUNNER). Zéro migration.
- **D3 — Date recalculée par tour, granularité jour** : seule donnée non figée ; ne change pas d'un
  tour à l'autre → cache préservé (F-134 = « rien qui change à CHAQUE tour »).
- **D4 — Aucune mise à jour runner** : `git status --short --branch` passe par l'outil `bash` déjà au
  contrat. Commande git identique quel que soit le shell (git est git).
- **Préoccupations transversales** : Auth/tenant → lecture/écriture sous `(user_id, workspace_id)` via
  le cache existant (aucun nouveau résolveur de tenant). Plans/limites → aucun appel de quota ajouté ;
  la capture git est hors tour (refresh), n'entame pas le budget de tour. Navigation → aucun. F-119
  intacte (doctrines inchangées). Gateway-First / Provider Independence : construction de consigne
  seulement, aucun modèle en dur.
