# Mini-spec — F-115 / SF-115-01 — Recevoir un dépôt côté serveur

> Base : `docs/features/F-115/CADRAGE-F-115-glisser-des-fichiers-dans-le-terminal.md` (tableau
> cible/dépôt §2, bornes §3, sécurité §5, découpage §6). Réutilise le stockage S3 du workspace hébergé
> (SF-28-13, `WorkspaceStorage.putFile`), le `write_file` du runner (contrat F-38) **complété** d'un
> `write_file_bytes` binaire par tranches (doctrine de transfert découpé de SF-108-06, portée ici au
> runner de fichiers), le coupe-circuit / liveness du poste (SF-38-08, `RunnerLiveness`), et
> l'isolation `user_id` (+ `host_id`).

## Identifiant

`F-115 / SF-115-01`

## Feature parente

`F-115` — Glisser des fichiers dans tous les terminaux, comme dans Claude Code

## Statut

`ready`

## Date de création

2026-09-16

## Branche Git

`feat/SF-115-01-recevoir-un-depot`

---

## Objectif

Recevoir côté serveur un fichier déposé dans un terminal et l'écrire **là où l'agent l'atteint** —
`entrees/` du workspace hébergé, ou `<racine>/.atelier/entrees/` du poste par le runner — sous bornes,
nom assaini, isolation stricte et coupe-circuit, en enregistrant le chemin déposé pour le tour.

---

## Comportement attendu

### Cas nominal

1. **Dépôt vers un workspace hébergé** (le workspace n'est pas une cible runner) :
   `POST /workspaces/{id}/deposit` (multipart, champ `files`) → pour chaque fichier, le nom est assaini
   (basename seul, pas de traversée), le chemin cible est **forcé** à `entrees/<nom>`, la taille est
   contrôlée (≤ 8 Mio hébergé), et les **octets bruts** sont écrits en S3 via
   `WorkspaceStorage.putFile("atelier/{userId}/{id}/entrees/<nom>", bytes, contentType)`. Réponse 200
   avec, par fichier, `{ path: "entrees/<nom>", size, target: "HOSTED" }`.
2. **Dépôt vers un poste / projet local** (workspace `isRunnerTarget()`) : le coupe-circuit est
   vérifié (`RunnerLiveness.isAlive(userId, hostId)`) ; sinon refus nommé. Le fichier est transféré au
   runner **par tranches** (`write_file_bytes`, Base64, ≤ 256 Kio par trame, `offset` croissant, la
   première tranche tronque/crée le fichier) vers `.atelier/entrees/<nom>` (≤ 100 Mio poste). Réponse
   200 `{ path: ".atelier/entrees/<nom>", size, target: "RUNNER" }`.
3. Dans les deux cas, chaque dépôt réussi est **enregistré** (table `atelier_deposited_files` :
   `user_id`, `workspace_id`, `path`, `size_bytes`, `created_at`, `consumed_at` nul) — c'est cet
   enregistrement que la consigne du tour lira (SF-115-03) et que le fil affiche (SF-115-02) ; le
   chemin déposé est rendu dans la réponse HTTP (« émis pour le tour »).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucun fichier / champ `files` vide | message « aucun fichier déposé » | 400 |
| Nombre de fichiers > borne (défaut 20) | message « trop de fichiers en un dépôt » | 400 |
| Nom vide/illisible après assainissement (`.`, `..`, `/`, `\`, octet nul, > 255) | `invalid_name`, aucun octet écrit | 400 |
| Taille > 8 Mio (hébergé) / > 100 Mio (poste) | `file_too_large` avec la borne, aucun octet écrit | 413 |
| Workspace inexistant ou d'un autre `user_id` | ressource introuvable (jamais « refusé ») | 404 |
| Poste hors ligne / coupé (coupe-circuit) | `runner_offline` « le poste est hors ligne », aucun octet | 409 |
| Dossier `.atelier/entrees` non inscriptible sur le poste (runner `io_error`) | `deposit_failed` « dossier non inscriptible », jamais « c'est fait » | 502 |
| Runner ancien sans `write_file_bytes` (`unsupported_tool`) | `deposit_failed` nommé, aucune écriture partielle réputée réussie | 502 |

---

## Critères d'acceptation

- [ ] `POST /workspaces/{id}/deposit` (multipart) existe, authentifié, résout la cible selon
      `workspace.isRunnerTarget()` : hébergé → `entrees/`, poste → `.atelier/entrees/`.
- [ ] Hébergé : les **octets bruts** (binaire compris : png, zip, mp4) sont écrits en S3 sous
      `entrees/<nom assaini>`, jamais ailleurs ; aucun filtre de type.
- [ ] Poste : le fichier est transféré **par tranches** via le nouvel outil runner `write_file_bytes`
      (Base64, `offset` croissant, première tranche tronquante), sous `.atelier/entrees/<nom>` ; aucune
      trame ne dépasse la borne de tranche. **Le « jamais hors de `entrees/` » est garanti côté
      gateway** (nom assaini + préfixe fixe `.atelier/entrees/` + `normalizePath` qui refuse `..` et
      l'absolu), **pas** par le runner : `PathResolver` ne confine volontairement pas (décision PO du
      2026-09-12, « poste sans confinement »).
- [ ] Bornes appliquées **avant** toute écriture : ≤ 8 Mio hébergé, ≤ 100 Mio poste, ≤ 20 fichiers par
      dépôt (réglables par `app.atelier.deposit.*`).
- [ ] Nom assaini : basename seul, refus de `..` / séparateurs / octet nul / > 255 ; le chemin final
      est **toujours** sous `entrees/` (hébergé) ou `.atelier/entrees/` (poste).
- [ ] Coupe-circuit : un poste hors ligne (`RunnerLiveness.isAlive` faux) refuse le dépôt avec un
      message nommé, sans tenter d'écrire.
- [ ] Isolation : `workspaceService.requireOwned(userId, id)` en tête ; le workspace d'autrui est
      introuvable ; le poste ciblé est celui du workspace (`RunnerTargets.of`).
- [ ] Chaque dépôt réussi crée une ligne `atelier_deposited_files` (avec `user_id` + `workspace_id`),
      et le chemin est rendu dans la réponse HTTP.
- [ ] `write_file_bytes` est ajouté au runner (`FileTools`) : écrit une tranche à `offset`, crée les
      dossiers parents, refuse un fichier > 100 Mio, reste sous la racine ; un tool inconnu répond
      toujours `unsupported_tool`.

---

## Périmètre

### Hors scope (explicite)

- Toute l'UI (glisser, coller, trombone, voile, progression, bloc dans le fil) → **SF-115-02**.
- L'injection des chemins déposés dans la consigne du tour et la lecture par l'agent → **SF-115-03**.
- OCR / RAG / indexation du fichier (pipeline documentaire F-05+) ; filtrage de type façon F-85.
- Dépôt ailleurs que sous `entrees/` ; dépôt vers Microsoft 365 (F-108).
- Reprise d'un transfert découpé interrompu (un transfert coupé est abandonné et dit, non repris).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `atelier_deposited_files.consumed_at` | `NULL` | passe à `NOW()` quand un tour l'a lu (SF-115-03) |
| `atelier_deposited_files.created_at` | `NOW()` | horodaté à la création |
| `atelier_deposited_files.user_id` | utilisateur connecté | isolation |
| `atelier_deposited_files.workspace_id` | workspace du terminal | isolation |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Normalisation |
|-------|-------------|-------------|------------------|---------------|
| `files[]` | oui | 20 fichiers | multipart, ≥ 1 | — |
| nom de fichier | oui | 255 car. | basename, sans `/ \ .. ` ni octet nul | `Path.getFileName`, trim |
| taille (hébergé) | — | 8 Mio | — | refus au-delà |
| taille (poste) | — | 100 Mio | — | refus au-delà |
| tranche `write_file_bytes` | — | 256 Kio (avant Base64) | Base64 | offset croissant |

Réglages : `app.atelier.deposit.max-hosted-bytes` (défaut `8388608`),
`app.atelier.deposit.max-runner-bytes` (défaut `104857600`), `app.atelier.deposit.max-files`
(défaut `20`), `app.atelier.deposit.chunk-bytes` (défaut `262144`).

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/workspaces/{id}/deposit` (multipart/form-data, champ `files`) | Oui | propriétaire du workspace |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_deposited_files` | CREATE (migration `108`) + INSERT | fichiers déposés en attente d'un tour ; index `(user_id, workspace_id)` |
| S3 (objets) | PUT | `atelier/{userId}/{workspaceId}/entrees/<nom>` (hébergé) |

### Migration Liquibase

- [x] Oui — `108-atelier-deposited-files.xml`

### Composants gateway impactés

- `AtelierController` — nouvel endpoint `deposit(...)` (délègue au service, aucune logique métier).
- `WorkspaceDepositService` (**créé**) — assainissement du nom, bornes, aiguillage hébergé/poste,
  écriture S3 binaire (`entrees/`), transfert découpé au poste, enregistrement du dépôt.
- `WorkspaceService` — méthode `depositHostedFile(userId, id, name, bytes, contentType)` (octets bruts
  sous `entrees/`, borne dépôt) **ou** exposition de `WorkspaceStorage` au service de dépôt.
- `RunnerToolGateway` — `writeFileBytes(target, callId, path, base64, offset)` → outil
  `write_file_bytes` (timeout allongé façon dépôt).
- `AtelierDepositedFile` (entité), `AtelierDepositedFileRepository` (**créés**).
- Lecture du coupe-circuit : `RunnerLiveness`, cible via `RunnerTargets.of(workspace)`.

### Composants runner impactés

- `FileTools` — `write_file_bytes` (nouvelle branche du `switch`) : `writeFileBytes(path, base64,
  offset, truncate)` ; borne 100 Mio ; création des parents ; sous la racine (`PathResolver`).

### Préoccupations transversales

- **Auth / tenant : OUI.** Composants qui résolvent le tenant, tous vérifiés :
  `CurrentUser.requireId()` (principal) → `WorkspaceService.requireOwned(userId, id)` (isolation
  workspace) → `RunnerTargets.of(workspace)` (host_id du seul workspace possédé). Le nouvel endpoint
  suit exactement ce chemin ; aucune donnée n'est lue sans `user_id`. Test de non-régression : dépôt
  sur un workspace d'un autre utilisateur → introuvable.
- **Plans / limites : partiel.** Le dépôt ne consomme **aucun quota de jetons** (aucun appel modèle) ;
  la seule limite est la borne de taille/nombre du dépôt (ci-dessus). Aucun appel à `QuotaService`.
- **Navigation / routing : non** (aucun écran ici).

---

## Plan de test

### Tests unitaires

- [ ] `WorkspaceDepositServiceTest` — nom assaini : `../evil`, `a/b`, `..`, `""`, `x`.repeat(300) →
      rejetés ; `capture.png` → `entrees/capture.png`.
- [ ] `WorkspaceDepositServiceTest` — borne hébergé (> 8 Mio → 413, aucun `putFile`) ; borne poste
      (> 100 Mio → 413, aucun appel runner) ; > 20 fichiers → 400.
- [ ] `WorkspaceDepositServiceTest` — cible hébergé : `putFile` appelé avec la clé `…/entrees/<nom>` et
      les octets bruts (binaire non altéré) ; cible poste : `writeFileBytes` appelé par tranches
      d'`offset` croissant, première tranche tronquante, chemin `.atelier/entrees/<nom>`.
- [ ] `WorkspaceDepositServiceTest` — poste hors ligne (`RunnerLiveness.isAlive` faux) → `runner_offline`
      sans appel runner ; runner `io_error` → `deposit_failed` ; `unsupported_tool` → `deposit_failed`.
- [ ] `FileToolsTest` (runner) — `write_file_bytes` écrit une tranche à offset, reconstitue un fichier
      binaire en deux tranches, crée les dossiers parents ; Base64 invalide → `invalid_input` ;
      `offset + tranche` > 100 Mio → `too_large` ; tranche > borne → `invalid_input`.
- [ ] `RunnerToolGatewayTest` — `writeFileBytes` envoie `{path, content(Base64), offset}` à
      `write_file_bytes` ; chemin invalide → `invalid`.

### Tests d'intégration

- [ ] `POST /workspaces/{id}/deposit` (hébergé) → 200, objet S3 présent sous `entrees/`, ligne
      `atelier_deposited_files` créée, réponse portant `path`.
- [ ] `POST /workspaces/{id}/deposit` sans fichier → 400 ; fichier trop gros → 413 ; nom piégé
      (`../x`) → 400.
- [ ] `POST /workspaces/{id}/deposit` (poste) avec runner simulé → 200 + tranches ; poste hors ligne
      → 409.
- [ ] `POST /workspaces/{id}/deposit` sur un workspace d'un autre utilisateur → 404.

### Isolation utilisateur

- [x] Applicable — un utilisateur A ne peut pas déposer dans le workspace de B (404), et ne voit pas
      ses dépôts (`atelier_deposited_files` filtré `user_id` + `workspace_id`).

---

## Dépendances

### Subfeatures bloquantes

- SF-28-13 (stockage S3 du workspace) — done.
- SF-38-08 (coupe-circuit / liveness) — done.
- SF-108-06 (doctrine de transfert découpé) — done (adaptée au runner de fichiers).

### Questions ouvertes impactées

- [ ] Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **D1 — un `write_file_bytes` runner est créé** : le `write_file` existant est **texte, 512 Kio**, sans
  fragmentation ; « tout type de fichier » jusqu'à 100 Mio impose une écriture **binaire par tranches**.
  On applique au runner de fichiers la doctrine de session découpée de SF-108-06 (les octets voyagent en
  Base64 par trames bornées, `offset` croissant), symétrique du `read_file_bytes` déjà livré (F-110).
- **D2 — bornes par `@Value`, pas dans le record `AtelierProperties`** : ce dernier est un record
  `@ConstructorBinding` volumineux ; ajouter des champs toucherait tous ses points de construction. Les
  bornes de dépôt sont injectées par `@Value("${app.atelier.deposit.*}")` dans le service (réglables
  sans livraison), décision réversible.
- **D3 — le dépôt est enregistré en base (`atelier_deposited_files`)** plutôt qu'émis seulement en SSE :
  un dépôt a lieu entre deux tours (composer puis envoyer) ; persister le chemin le rend disponible au
  **prochain** tour (SF-115-03) même si aucun tour n'est en vol. Le fil (SF-115-02) l'affiche depuis la
  réponse HTTP.
- **D4 — transfert relayé synchrone borné** : le transfert au poste est un relais d'E/S (comme `bash`
  streamé ou le dépôt Teams), pas un traitement lourd de calcul ; il reste dans la requête avec un
  timeout allongé et sera annulable côté client (SF-115-02). Aucun job asynchrone créé.
