# Mini-spec — F-121 / SF-121-01 — Vrai Grep (regex) + Glob, déclarés sur poste ET hébergé

## Identifiant

`F-121 / SF-121-01`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`in-progress`

## Date de création

2026-09-16

## Branche Git

`feat/SF-121-01-grep-glob`

---

## Objectif

Donner à la boucle maison deux outils de recherche de première classe — `grep` (regex, `--include`,
contexte `-A/-B/-C`, modes `content`/`files_with_matches`/`count`) et `glob` (motif + tri par date de
modification) — **déclarés sur les deux cibles** (RUNNER = poste, SANDBOX = hébergé), en remplacement
de la recherche sous-chaîne cap 8000 réservée au sandbox et du `grep` bash dépendant du shell.

---

## Comportement attendu

### Cas nominal

- **`grep`** (params : `pattern` requis, `path` optionnel = sous-arbre, `include` optionnel = glob de
  nom de fichier, `ignore_case` optionnel, `output_mode` ∈ {`content` (défaut), `files_with_matches`,
  `count`}, `before`/`after`/`context` optionnels = lignes de contexte) :
  - `content` : lignes correspondantes au format `chemin:ligne: texte` ; les lignes de contexte au
    format `chemin-ligne- texte`. Résultat borné (8000 car.), suffixe « … (résultats tronqués) ».
  - `files_with_matches` : un chemin par ligne (fichiers ayant ≥ 1 correspondance), triés.
  - `count` : `chemin:N` par fichier ayant N > 0 correspondances.
  - Le moteur est une **expression régulière Java** (`java.util.regex.Pattern`) appliquée ligne à
    ligne sur l'arbre du projet — **indépendante du shell** : elle fonctionne à l'identique sur un
    poste `cmd.exe` (qui n'a ni `grep` ni `rg`), ce qui est précisément l'écart que la SF corrige.
- **`glob`** (params : `pattern` requis = motif glob type `**/*.java`, `path` optionnel = base) :
  chemins des fichiers correspondants **triés par date de modification décroissante**, un par ligne,
  bornés.
- Les deux outils sont exécutés **là où vivent les fichiers** : sur le poste via le runner
  (`FileTools`, moteur Java sur l'arbre local), et dans le stockage hébergé via `AtelierChatService`
  (même moteur, mêmes formats de sortie — la doctrine « formats identiques sur les deux cibles »).
- Déclarés sur les **deux** cibles, et disponibles à la sous-boucle d'exploration (outils de lecture)
  et au mode `ANSWER_PLAN`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| `pattern` absent/vide | `invalid_input` — « Paramètre requis manquant : pattern » (runner) / erreur d'outil (sandbox) |
| `pattern` regex invalide | résultat d'erreur explicite « Expression régulière invalide », jamais une stacktrace |
| `pattern` trop long (> 1024 car.) | `invalid_input` avant émission (borne gateway, comme `search_files`) |
| `path` de recherche absolu ou `..` (gateway) | `invalid_input` — « Répertoire de recherche invalide » |
| échec de transport runner (timeout/indispo/mauvais nœud/protocole) | résultat « non concluant » (cohérent SF-119-04), jamais un négatif |
| aucun résultat | « Aucun résultat. » |

---

## Critères d'acceptation

- [ ] `grep` regex renvoie les lignes au format `chemin:ligne: texte` (runner ET sandbox).
- [ ] `grep` avec `include` ne cherche que dans les fichiers dont le nom correspond au glob.
- [ ] `grep` avec `context`/`before`/`after` renvoie les lignes voisines au format `chemin-ligne- texte`.
- [ ] `grep` `output_mode=count` renvoie `chemin:N` par fichier ; `files_with_matches` renvoie les chemins.
- [ ] `grep` `ignore_case=true` est insensible à la casse.
- [ ] `glob` renvoie les chemins correspondants triés par date de modification décroissante.
- [ ] `grep` et `glob` sont déclarés dans la panoplie sur **RUNNER et SANDBOX**.
- [ ] Une regex invalide rend une erreur explicite, jamais une stacktrace.
- [ ] Isolation : le moteur ne lit que l'arbre du projet du contexte (runner = racine du poste du
      tour ; sandbox = `workspaceService.tree(userId, workspaceId)`, déjà filtré par `user_id`).

---

## Périmètre

### Hors scope (explicite)

- Le tri des résultats de `grep` autrement que dans l'ordre de balayage (déjà trié par chemin).
- Le mode multiline / regex sur plusieurs lignes.
- Le retrait des outils historiques `list_files`/`search_files` (conservés tels quels).
- La mention de `grep`/`glob` dans le texte du rôle du prompt (relève de SF-121-03).
- Toute normalisation vers `rg`/`grep -rn`/`find` côté runner : **remplacée** par un moteur Java sur
  l'arbre (voir Notes et décisions), plus robuste et testable, et surtout indépendant du shell.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs |
|-------|-------------|-------------|-------------------|
| `pattern` (grep/glob) | Oui | 1024 (gateway) | regex Java (grep) / motif glob (glob) |
| `path` | Non | 4096 | chemin relatif normalisé (gateway) |
| `include` | Non | 1024 | motif glob de nom de fichier |
| `output_mode` | Non | — | `content` \| `files_with_matches` \| `count` (défaut `content`) |
| `before`/`after`/`context` | Non | — | entier ≥ 0, borné à 100 |
| `ignore_case` | Non | — | booléen |

---

## Technique

### Tables impactées

Aucune. Aucun endpoint. Aucune migration.

### Composants impactés

| Composant | Changement |
|-----------|-----------|
| `runner/FileTools.java` | nouveaux outils `grep` + `glob` (moteur regex/glob Java sur l'arbre) |
| `backend/RunnerToolGateway.java` | relais `grep(...)` + `glob(...)` (bornes, normalisation chemin) |
| `backend/AtelierChatService.java` | déclaration `grep`/`glob` (2 cibles), routage `callRunner`, `runnerOutcome`, `auditTarget`, `stepFor`, `executeToolOnStorage` (moteur sandbox), `READ_ONLY_TOOLS`, `ANSWER_PLAN_TOOLS` |

### Analyse transversale

- **Auth / tenant** : aucun nouveau moyen de résoudre le tenant. Le moteur sandbox lit via
  `workspaceService.tree/readFile(userId, workspaceId)` (isolation `user_id` inchangée) ; le runner
  lit l'arbre de la racine du poste du tour (déjà borné par le tour, F-48). Pas de régression.
- **Plans / limites** : aucun nouveau gate. Ces outils sont de la lecture (comme `search_files`).
- **Navigation / routing** : aucun changement UI.

---

## Plan de test

### Tests unitaires (runner — `FileToolsTest`)

- [ ] `grep` regex → `chemin:ligne: texte`
- [ ] `grep` `include` filtre par nom de fichier
- [ ] `grep` `context`/`before`/`after` → lignes de contexte
- [ ] `grep` `count` et `files_with_matches`
- [ ] `grep` `ignore_case`
- [ ] `grep` regex invalide → erreur explicite
- [ ] `glob` motif + tri par mtime décroissant

### Tests unitaires (backend)

- [ ] `buildTools` déclare `grep` et `glob` sur RUNNER et sur SANDBOX
- [ ] `executeToolOnStorage` : `grep` regex + `glob` sur l'arbre du workspace (mêmes formats)
- [ ] `callRunner` route `grep`/`glob` vers `RunnerToolGateway`

### Isolation workspace

- [ ] Applicable — sandbox : la recherche ne lit que l'arbre du `workspaceId` possédé.

---

## Notes et décisions

- **Décision technique (documentée, écart au cadrage)** : le cadrage F-121 suggérait « normaliser
  vers `rg` si présent sinon `grep -rn`/`find` selon la famille de shell ». On implémente à la place
  un **moteur regex/glob Java** dans `FileTools`, pour trois raisons : (1) c'est exactement ce qui
  supprime la dépendance au shell que la SF vise à corriger (un poste `cmd.exe` n'a ni `grep` ni
  `rg`) ; (2) c'est déterministe et testable en CI sans dépendre d'un binaire installé ; (3) c'est
  cohérent avec l'existant (`search_files` du runner est déjà un balayage Java). Le moteur sandbox
  réutilise les mêmes formats de sortie pour tenir la doctrine « formats identiques sur les deux
  cibles ».
- Réversibilité : purement additive (deux outils déclarés en plus). Retirer les déclarations suffit à
  revenir au comportement d'avant.
