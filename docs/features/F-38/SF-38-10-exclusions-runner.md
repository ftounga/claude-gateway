# Mini-spec — F-38 / SF-38-10 — Exclusions côté runner

## Identifiant
`F-38 / SF-38-10`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`done`

## Date de création
2026-08-30

## Branche Git
`feat/SF-38-10-exclusions-runner`

---

## Objectif

> Empêcher **matériellement** qu'un fichier sensible de la machine quitte celle-ci, en appliquant
> **côté runner** — avant toute lecture, écriture ou listing — un filtre d'exclusion composé du
> fichier `.runnerignore` (repli `.gitignore`) et d'une **liste par défaut non désactivable**
> (`.env`, `*.pem`, `id_rsa*`, `.aws/`, `.kube/config`, `.ssh/`), conformément à la décision **D10**.

---

## Comportement attendu

### Cas nominal

1. À l'ouverture de la connexion, le runner charge ses règles d'exclusion depuis la racine
   `--workspace` :
   - si `<racine>/.runnerignore` existe → il est la **seule** source de règles utilisateur ;
   - sinon si `<racine>/.gitignore` existe → **repli** sur lui ;
   - sinon → aucune règle utilisateur, seule la liste par défaut s'applique.
2. La syntaxe est celle de `.gitignore` : commentaires `#`, lignes vides ignorées, `!` de négation,
   `/` final = dossier uniquement, `/` initial ou interne = motif **ancré à la racine**, motif sans
   `/` = comparé au **nom de base à n'importe quelle profondeur**, jokers `*` (dans un segment),
   `?` (un caractère), `**` (traverse les segments). **La dernière règle utilisateur qui correspond
   l'emporte** (comme git).
3. La **liste par défaut** (`.env`, `*.pem`, `id_rsa*`, `.aws/`, `.kube/config`, `.ssh/`) est
   évaluée **en dernier** et **gagne toujours** : une négation (`!.env`) ne la réactive jamais.
4. Le filtre est porté par **une seule garde traversée par tous les outils** : `PathGuard.resolve()`
   (donc `read_file`, `write_file`) et le balayage de `FileTools` (donc `list_files`,
   `search_files`) consultent le **même** objet `ExclusionRules`.
5. Un chemin est exclu si **lui-même ou l'un de ses dossiers ancêtres** est exclu. Un dossier exclu
   est **élagué** du balayage (`SKIP_SUBTREE`) : son contenu n'est ni ouvert ni lu.
6. `list_files` et `search_files` ne renvoient donc que des fichiers non exclus ;
   `read_file` / `write_file` sur un chemin exclu répondent `ok=false`,
   `error.code="excluded"`, **sans ouvrir ni créer quoi que ce soit**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| `read_file` sur un chemin exclu (`.env`, `.ssh/id_rsa`, `secrets/key.pem`…) | `ok=false`, `error.code="excluded"`, message avec chemin **relatif** — le fichier n'est **jamais ouvert** |
| `write_file` sur un chemin exclu | `ok=false`, `error.code="excluded"` — **aucun fichier créé, aucun dossier parent créé** |
| `.runnerignore` contenant `!.env` (tentative de réactivation d'une entrée de la deny-list) | La deny-list gagne : `.env` reste exclu (`excluded`) |
| `.runnerignore` illisible (permissions, I/O) | Avertissement console, **repli sur la liste par défaut seule** ; le runner démarre quand même |
| `.runnerignore` démesuré (> 1 Mio ou > 5 000 lignes) | Les lignes au-delà de la borne sont ignorées, avertissement console ; le runner démarre |
| Ligne de règle vide après normalisation, ou uniquement `!` | La ligne est ignorée, les autres règles restent actives |
| Chemin hors racine **et** exclu | `path_outside_root` reste prioritaire (le confinement SF-38-04 est évalué en premier) |

---

## Critères d'acceptation

- [ ] Le filtre est appliqué dans **les quatre** outils fichiers (`list_files`, `read_file`,
      `write_file`, `search_files`), au **même endroit** (`ExclusionRules` consulté par
      `PathGuard.resolve()` et par le balayage) — deviner le chemin ne contourne rien.
- [ ] Le code d'erreur renvoyé pour un chemin exclu est **exactement** `excluded` (liste close du
      contrat §4), avec un message français **sans chemin absolu**.
- [ ] `.env`, `*.pem`, `id_rsa*`, `.aws/`, `.kube/config`, `.ssh/` sont exclus **même sans**
      `.runnerignore`, et **même si** un `.runnerignore` tente de les réactiver par négation.
- [ ] `.runnerignore` présent ⇒ `.gitignore` **n'est pas lu** ; `.runnerignore` absent ⇒ repli sur
      `.gitignore` ; aucun des deux ⇒ deny-list seule.
- [ ] Les motifs sont résolus **relativement à la racine `--workspace`**, après normalisation du
      chemin (séparateur `/`, pas de `..`, pas de `/` initial).
- [ ] Un dossier exclu est élagué : aucun fichier situé dessous n'apparaît dans `list_files` ni dans
      `search_files`, et son contenu n'est jamais lu.
- [ ] **Non-régression d'amorçage** : `CLAUDE.md`, `.claude/skills/**` et `skills/**` restent
      lisibles et listés avec la deny-list par défaut (aucun motif « tout ce qui commence par un
      point »).
- [ ] `search_files` ne renvoie aucune ligne provenant d'un fichier exclu.
- [ ] `cd runner && ./mvnw test` est vert ; `cd backend && ./mvnw test` reste vert (aucune
      modification backend).

---

## Périmètre

### Hors scope (explicite)

- **Aucune modification backend** : la gateway relaie le `content` du runner tel quel (contrat §3) ;
  elle ne connaît pas les motifs et ne filtre rien.
- **Aucun écran** : pas d'UI d'édition des exclusions (SF-38-06 est l'écran d'appairage/état).
- **`bash`** (SF-38-07) : le filtre d'exclusion n'analyse pas une ligne de commande ; les gardes de
  `bash` relèvent de SF-38-07 et de la validation SF-38-08.
- **Rechargement à chaud** de `.runnerignore` : les règles sont chargées une fois à l'ouverture de la
  connexion ; modifier le fichier exige un redémarrage du runner (documenté dans `runner/README.md`).
- Aucune migration Liquibase, aucun endpoint HTTP, aucun composant Angular.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| Fichier de règles | Non | 1 Mio | `.runnerignore` à la racine, sinon `.gitignore` | — | lecture UTF-8, octets illisibles remplacés |
| Ligne de règle | — | 1 000 caractères | syntaxe gitignore (`#`, `!`, `/`, `*`, `?`, `**`) | — | `strip()`, `\` → `/`, lignes au-delà de la borne ignorées |
| Nombre de règles | — | 5 000 lignes | au-delà : lignes ignorées + avertissement | — | — |
| Chemin évalué | Oui | 4 096 (borne `PathGuard`) | relatif, séparateur `/`, sans `..` ni `/` initial | — | normalisation `PathGuard` **avant** évaluation |
| Deny-list | Oui | — | `.env`, `*.pem`, `id_rsa*`, `.aws/`, `.kube/config`, `.ssh/` | — | **non désactivable**, évaluée en dernier |

Notes :
- La deny-list est comparée **à n'importe quelle profondeur** (y compris `.kube/config` : un
  `projet/.kube/config` est exclu au même titre que celui de la racine). C'est plus strict que la
  sémantique git pour un motif contenant un `/` — arbitrage assumé : la deny-list ne doit pas
  dépendre de la profondeur.
- Une négation utilisateur ne peut pas réactiver un fichier situé sous un **dossier** exclu (même
  limite que git), et ne peut jamais réactiver une entrée de la deny-list.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular (si applicable)

Aucun.

### Classes impactées (module `runner/`)

| Classe | Opération | Notes |
|--------|-----------|-------|
| `ExclusionRules` | **création** | chargement `.runnerignore`/`.gitignore`, compilation des motifs, deny-list non désactivable, `isExcluded(relatif, dossier)` |
| `PathGuard` | modification | `resolve()` lève `excluded` ; nouveau constructeur `(root, ExclusionRules)` ; expose `exclusions()` |
| `FileTools` | modification | élagage des dossiers exclus et filtrage des fichiers dans le balayage (`list_files`, `search_files`) |
| `RunnerConnection` | modification | charge les règles à l'ouverture et journalise la source et le nombre de règles |
| `runner/README.md` | documentation | section « Exclusions » |

---

## Plan de test

### Tests unitaires

- [ ] `ExclusionRules` — deny-list par défaut : `.env`, `cert.pem`, `id_rsa`, `id_rsa.pub`,
      `.aws/credentials`, `.kube/config`, `.ssh/id_rsa` exclus **sans** fichier de règles.
- [ ] `ExclusionRules` — `!.env` dans `.runnerignore` **ne réactive pas** `.env`.
- [ ] `ExclusionRules` — `.runnerignore` présent ⇒ `.gitignore` ignoré ; absent ⇒ repli `.gitignore`.
- [ ] `ExclusionRules` — motifs : ancrage (`/build/`), nom de base à toute profondeur (`*.log`),
      `**`, `?`, dossier (`node_modules/`), négation utilisateur (`*.log` + `!keep.log`).
- [ ] `ExclusionRules` — non-régression d'amorçage : `CLAUDE.md`, `.claude/skills/x.md`,
      `skills/y.md` **non exclus** par défaut.
- [ ] `ExclusionRules` — fichier de règles illisible / démesuré ⇒ deny-list seule, pas d'exception.
- [ ] `PathGuard` — `resolve(".env")` et `resolve(".ssh/id_rsa")` lèvent `excluded` ;
      `resolve("../x")` lève toujours `path_outside_root` (priorité au confinement).
- [ ] `FileTools` — `read_file` sur chemin exclu ⇒ `excluded`, message sans chemin absolu.
- [ ] `FileTools` — `write_file` sur chemin exclu ⇒ `excluded` **et aucun fichier/dossier créé**.
- [ ] `FileTools` — `list_files` masque les fichiers exclus et le contenu des dossiers exclus.
- [ ] `FileTools` — `search_files` ne remonte aucune ligne d'un fichier exclu même si elle matche.

### Tests d'intégration

- [ ] `ToolDispatcher` (bout en bout du canal, sans réseau) — une trame
      `{"type":"tool_call","tool":"read_file","input":{"path":".env"}}` produit **une seule** trame
      `tool_result` avec `ok=false` et `error.code="excluded"`.
- [ ] Le même scénario sur `write_file` laisse le disque inchangé.

### Isolation workspace

- [ ] Applicable — le filtre est **relatif à la racine `--workspace`** de ce runner ; le test de
      confinement `path_outside_root` reste vert (aucun chemin d'un autre workspace n'est
      atteignable) et l'exclusion s'ajoute au confinement sans l'affaiblir. Aucun accès base de
      données ici : l'isolation `user_id`/`workspace_id` côté gateway est inchangée.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-03` — statut : done (connexion, racine `--workspace`)
- `SF-38-04` — statut : done (outils fichiers, `PathGuard`, `FileTools`)

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` n'est impactée.

---

## Notes et décisions

- **Ordonnancement (arbitrage orchestrateur)** : cette subfeature est remontée **juste après
  SF-38-04**, avant SF-38-05, pour qu'**aucun socle de lecture sans filtre d'exclusion n'existe sur
  `main`**. Sur `main`, le runner ne sait pas encore recevoir d'appels routés par la gateway
  (SF-38-05) : le filtre est donc en place **avant** que le premier octet puisse quitter la machine.
- **Une seule garde** : le piège identifié au cadrage est de ne filtrer que le listing. Le filtre est
  donc porté par `ExclusionRules` et consulté par `PathGuard.resolve()` (chemins explicites) **et**
  par le balayage (`list_files`/`search_files`) — deviner `.ssh/id_rsa` ne contourne rien.
- **Deny-list évaluée en dernier** : les règles utilisateur sont appliquées d'abord (dernière
  correspondance gagne), puis la deny-list qui **écrase** le verdict. C'est ce qui rend `!.env`
  inopérant (D10 : non désactivable).
- **Pas de motif « fichiers cachés »** : aucun motif du type `.*` n'est ajouté. Un tel motif
  exclurait `.claude/skills/**`, que `AtelierChatService.buildSystemPrompt` lit pour amorcer le
  prompt (via `readOptional`, qui avale l'exception) : les conventions du projet disparaîtraient
  **en silence**. La deny-list ne contient donc que des entrées littérales.
- **Limite connue assumée** : la deny-list est celle de D10, au mot près. `.env.local`,
  `id_ed25519`, `*.key`, `.npmrc` ne sont **pas** couverts par défaut ; ils doivent être ajoutés au
  `.runnerignore` du projet. Élargir la liste non désactivable serait une modification de D10, pas
  une décision d'implémentation.
- **`.git/` non exclu par défaut** : D10 ne le liste pas, et un dépôt ne s'ignore pas lui-même dans
  son `.gitignore`. Un projet qui veut le masquer l'ajoute à son `.runnerignore`. Limite connue.
