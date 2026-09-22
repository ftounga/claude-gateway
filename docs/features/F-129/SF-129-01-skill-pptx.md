# Mini-spec — F-129 / SF-129-01 — Skill `pptx` : l'agent produit un vrai .pptx

## Identifiant

`F-129 / SF-129-01`

## Feature parente

`F-129` — Produire des présentations PPTX et les lire entièrement dans l'app

## Statut

`ready`

## Date de création

2026-09-22

## Branche Git

`feat/SF-129-01-skill-pptx`

---

## Objectif

> Déposer avec le produit une skill `pptx` (recette `python-pptx`) qui apprend à l'agent à produire
> un vrai fichier `.pptx` sur le terminal, avec un échec **nommé** quand `python-pptx` est absente.

---

## Comportement attendu

### Cas nominal

Le paquet de gouvernance `savoir-durable` (semé au démarrage par `GovernancePackageSeeder`, patron
F-51/F-52/F-96) dépose un fichier de skill supplémentaire `.claude/skills/pptx.md`. Quand l'agent
reçoit une demande de présentation, il ouvre la skill, écrit un script `python-pptx` (titres, puces,
images, tableaux, notes) et l'exécute sur le terminal (`bash`, déjà activé — SF-38-19). Le script
produit un `.pptx` dans le répertoire de travail.

Le dépôt est **idempotent** (patron F-96) : contenu identique → aucune écriture ni incrément de
version ; contenu différent → mise à jour + version incrémentée ; un paquet dépublié par l'admin
reste dépublié ; « tout ou rien » — une ressource manquante fait renoncer entièrement.

### Où ça tourne (drapeau)

`python-pptx` est une dépendance Python. Sur un **poste banque**, `pip install python-pptx` est
souvent **bloqué** (proxy/policy). La skill est donc explicite :

- **Sandbox** (Anthropic managed) : `pip install python-pptx` autorisé → la production marche
  toujours.
- **Poste** : la production marche **si** `python-pptx` est déjà présente ; sinon **échec nommé**
  (message clair : la lib manque, `pip install` peut être bloqué, proposer de basculer sur le
  sandbox) — jamais un traceback nu ni un `.pptx` vide.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Une ressource du paquet manque | Le semeur renonce **entièrement** (aucun paquet à moitié semé) — invariant F-96 existant, préservé |
| `python-pptx` absente à l'exécution | La skill impose un **échec nommé** (import vérifié en tête de script) : message clair, pas de traceback nu |
| `pip install` bloqué sur le poste | La skill nomme la cause (proxy/policy banque) et propose le sandbox |

---

## Critères d'acceptation

- [ ] CA1 — Le semeur dépose un fichier de skill de plus : `.claude/skills/pptx.md`, genre `SKILL`,
      déclaré artefact généré (F-96).
- [ ] CA2 — Le contenu de la skill enseigne la recette `python-pptx` (titres, puces, images,
      tableaux, notes).
- [ ] CA3 — La skill impose une **vérification de disponibilité** de `python-pptx` avec **échec
      nommé** si absente (import gardé en tête de script).
- [ ] CA4 — La skill documente **où ça tourne** (sandbox : `pip install` ok ; poste : si présente,
      sinon échec nommé) — le drapeau du cadrage.
- [ ] CA5 — Le dépôt reste **idempotent** : un redémarrage sans changement n'écrit rien
      (test `secondPassWritesNothing` reste vert avec le fichier en plus).
- [ ] CA6 — Aucune régression du semeur : tous les tests `GovernancePackageSeederTest` passent, y
      compris la liste exacte des chemins/genres mise à jour.

---

## Périmètre

### Hors scope (explicite)

- La **capture** du `.pptx` dans l'app (upload → stockage → liste → téléchargement) → **SF-129-02**.
- Le **rendu par slides** (images) et la **visionneuse** → **SF-129-03**.
- La **charte/gabarits** de marque → SF-129-04 (option).
- `docx`/`xlsx` → SF-129-05 (plus tard).

---

## Technique

### Endpoint(s)

Aucun. C'est un dépôt de contenu par le semeur existant.

### Tables impactées

Aucune. Le semeur écrit dans `governance_packages` / `governance_package_files` (déjà existantes,
sans `user_id` — un paquet est un contenu produit, comme un plan tarifaire, cf. javadoc du semeur).

### Migration Liquibase

- [ ] Non applicable.

### Composants impactés

- `backend/src/main/java/fr/claudegateway/governance/GovernancePackageSeeder.java` — ajouter
  `new SeededFile("pptx.md", ".claude/skills/pptx.md", GovernanceFileKind.SKILL)` à `FILES`.
- `backend/src/main/resources/governance/savoir-durable/pptx.md` — la skill (nouvelle ressource).
- `backend/src/test/java/fr/claudegateway/governance/GovernancePackageSeederTest.java` — mettre à
  jour la liste exacte des chemins et des genres attendus.

### Runner / migration / composant cluster (drapeau)

- Mise à jour runner : **NON** (contenu déposé par le serveur ; l'agent le lit sur la machine via
  les outils runner existants).
- Migration : **NON**.
- Nouveau composant cluster : **NON**.

---

## Plan de test

### Tests unitaires

- [ ] `firstPassCreatesAndPublishes` — la liste exacte des chemins inclut `.claude/skills/pptx.md`
      et son genre `SKILL`.
- [ ] `secondPassWritesNothing` — idempotence préservée avec le fichier en plus.
- [ ] Nouveau test — le contenu de la skill `pptx.md` cite `python-pptx`, la vérification de
      disponibilité (échec nommé) et le choix sandbox/poste.

### Tests d'intégration

- Non applicable (pas d'endpoint).

### Isolation utilisateur

- [ ] Non applicable — le paquet est un contenu produit, sans `user_id` (invariant documenté du
      semeur). Aucun accès aux données utilisateur n'est ajouté.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (première SF de la chaîne).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Divergence assumée vs cadrage D2** (signalée pour arbitrage, portée par l'instruction PO du lot) :
  le cadrage parlait d'un worker LibreOffice **serveur** pour le rendu ; l'instruction du PO impose,
  cluster `legalcase-shared` à capacité, de **ne pas** ajouter de pod LibreOffice permanent. La
  conversion `.pptx`→images se fera donc **dans le sandbox/terminal** (SF-129-03), pas sur le
  cluster. SF-129-01 ne fait que la **production** : aucun impact cluster.
- Un seul fichier de skill (patron des skills existantes `explique.md`, `plan-dashboard.md`), avec
  frontmatter `name`/`description`.
