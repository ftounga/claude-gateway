# Mini-spec — F-52 / SF-52-03 — Le paquet, publié au démarrage

## Identifiant

`F-52 / SF-52-03`

## Feature parente

`F-52` — Premier paquet de gouvernance

## Statut

`done`

## Date de création

2026-09-10

## Branche Git

`feat/SF-52-03-le-premier-paquet`

---

## Objectif

Faire exister le **contenu** : un paquet `savoir-durable`, publié par le produit au démarrage, qui
apporte les règles, les trois contrôles, les deux gabarits et les deux skills de la gouvernance du PO.

---

## Comportement attendu

### Cas nominal

1. Au démarrage (`ApplicationReadyEvent`), le semeur lit les fichiers du paquet depuis les ressources
   du produit (`resources/governance/savoir-durable/`) et cherche le paquet au slug `savoir-durable`.
2. **Absent** → il le crée en version 1, **publié**, avec ses règles, ses contrôles et ses cinq
   fichiers (trois gabarits, deux skills).
3. **Présent et identique** → **rien**. Ni écriture, ni incrément de version : un redémarrage ne doit
   pas faire croire à une nouvelle version d'un paquet que personne n'a touché.
4. **Présent et différent** (le produit a livré une nouvelle rédaction) → il est mis à jour, sa
   version est incrémentée, et ses fichiers sont remplacés.
5. **Un paquet dépublié par l'admin n'est jamais republié** : le drapeau `published` n'est posé qu'à
   la **création** (arbitrage A5 du cadrage). Ranger son catalogue est une décision d'admin ; le
   produit ne la reprend pas à chaque démarrage.
6. Le paquet est **publié**, jamais **activé**. Personne ne le subit : il écrit des fichiers sur la
   machine de l'utilisateur et bloque des fins de tour ; l'activation reste un geste (arbitrage A7).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Une ressource du paquet est absente ou illisible | Le semeur **renonce entièrement** et le dit dans le journal. Un paquet à moitié semé serait pire que pas de paquet | — |
| Un identifiant de contrôle n'existe plus dans le produit | Il est **ignoré**, avec une ligne de journal. Les autres contrôles restent cités | — |
| Un chemin de fichier serait invalide | Le semeur renonce : c'est une faute de rédaction du produit, pas de l'utilisateur | — |
| La base est indisponible au démarrage | L'exception est capturée et journalisée : le produit démarre. Un catalogue vide ne casse rien (F-51) | — |
| Le semeur est désactivé (`app.governance.seed-first-package=false`) | Rien n'est semé, et la ligne le dit | — |

---

## Critères d'acceptation

- [x] Au premier démarrage, un paquet `savoir-durable` **publié** existe, en version 1.
- [x] Il cite les trois contrôles : `commit-sans-trace-llm`, `juge-fin-de-tour`,
      `promotion-dette-bloquante` — dans cet ordre.
- [x] Il apporte `STATE.md` et `PLAN-ACTION.md` (gabarits) et `.claude/skills/explique.md` et
      `.claude/skills/plan-dashboard.md` (skills).
- [x] Ses règles nomment le principe, la règle des livrables, la promotion avec dette bloquante et la
      **forme exacte** du marqueur de fin de tour.
- [x] Un second démarrage sans changement **ne touche à rien** : même version, même date de mise à
      jour.
- [x] Un changement de contenu incrémente la version et remplace les fichiers.
- [x] Un paquet dépublié à la main reste dépublié après redémarrage.
- [x] Le gabarit `PLAN-ACTION.md` livré ne contient **aucune** case `- [ ]` non cochée — il serait
      sinon une dette dès son dépôt, et bloquerait la fin de chaque tour.
- [x] Aucune activation n'est créée : le paquet est publié, pas imposé.
- [x] Isolation : les tables `governance_packages` / `governance_package_files` ne portent pas de
      `user_id` (contenu produit, F-51) ; le semeur n'écrit dans **aucune** table qui en porte un.

---

## Périmètre

### Hors scope (explicite)

- **Activer le paquet pour qui que ce soit** : ni sélection, ni activation, ni « appliqué par
  défaut ». C'est le geste de l'utilisateur (F-51 / SF-51-02).
- **Un second paquet** : F-52 en apporte **un**.
- **L'organisation du poste personnel de l'auteur** (`~/dev/repos`, `~/poste/`, `~/methodo/`) et le
  site de méthodologie : écartés par le cadrage d'ensemble.
- **Un écran d'administration dédié** : SF-51-06 rédige et publie déjà ; ce paquet s'y affiche comme
  les autres.
- **Une migration de schéma** : le paquet est une **donnée**, pas une table.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| `slug` | `savoir-durable` — immuable, c'est ce qui identifie le paquet d'une version à l'autre |
| `rules` | ≤ 8 000 caractères (`GovernancePackage.MAX_RULES_LENGTH`) — ce texte part dans la consigne système à **chaque** tour |
| Contenu d'un fichier | ≤ 64 000 caractères ; total ≤ 256 000 (bornes F-51) |
| Chemins | Relatifs au projet, validés par `GovernancePath` |
| `app.governance.seed-first-package` | Booléen, défaut `true` |

---

## Technique

### Endpoint(s)

Aucun — le paquet est lu par les endpoints existants de F-51.

### Tables impactées

`governance_packages`, `governance_package_files` — **en écriture de données uniquement**, aucun
changement de schéma.

### Migration Liquibase

Aucune.

### Classes créées (`fr.claudegateway.governance`)

- `GovernancePackageSeeder` — le semeur idempotent.

### Ressources créées (`resources/governance/savoir-durable/`)

- `regles.md` — les règles, telles qu'elles rejoignent la consigne système.
- `STATE.md`, `PLAN-ACTION.md` — les gabarits.
- `explique.md`, `plan-dashboard.md` — les skills.
- `GOUVERNANCE.md` — la note de référence déposée à la racine du projet.

### Classes modifiées

Aucune.

### Composants Angular

Aucun — l'écran du catalogue (SF-51-05) et celui de l'administration (SF-51-06) rendent déjà un
paquet, ses fichiers annoncés et ses contrôles, sans rien savoir de leur contenu.

---

## Plan de test

### Tests unitaires

- `GovernancePackageSeederTest` (mocks de dépôts) : création au premier passage ; second passage sans
  écriture ; mise à jour + version incrémentée quand le contenu change ; paquet dépublié non
  republié ; contrôle inconnu ignoré ; ressource absente → renoncement complet ; base indisponible →
  démarrage préservé ; semeur désactivé.

### Tests d'intégration

- `GovernanceSeededPackageIntegrationTest` (contexte Spring, H2) : après `seed()`, le paquet est
  publié, cite les trois contrôles **du registre réel**, apporte les cinq fichiers, et son gabarit
  `PLAN-ACTION.md` ne porte aucune case non cochée. Aucune activation n'existe.

### Isolation workspace / utilisateur

- Le semeur n'écrit que dans les deux tables sans `user_id` ; un test vérifie qu'aucune sélection ni
  activation n'est créée.

---

## Dépendances

### Subfeatures bloquantes

- F-51 / SF-51-01 (les tables et le registre) — **Done**.
- F-52 / SF-52-01 et SF-52-02 (les trois contrôles cités) — **Done**.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Pourquoi un semeur et non une saisie à la main** : le contenu serait absent de toute installation
  neuve, et non reproductible. Un paquet livré par le produit est un contenu produit, versionné avec
  lui (arbitrage A4).
- **Pourquoi les fichiers vivent en ressources et non en dur dans le code** : ce sont des documents,
  ils se relisent et se corrigent comme tels. Le semeur les charge, il ne les écrit pas.
- **Pourquoi le gabarit de la carte est vide de cases non cochées** : le contrôle de dette compte les
  `- [ ]`. Un gabarit qui en apporterait créerait une dette au moment même de son dépôt — le paquet se
  mordrait la queue.
