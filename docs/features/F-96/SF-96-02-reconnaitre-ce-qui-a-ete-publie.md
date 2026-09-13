# Mini-spec — F-96 / SF-96-02 — Reconnaître ce que le produit a déjà publié

## Identifiant

`F-96 / SF-96-02`

## Feature parente

`F-96` — La gouvernance se met à jour

## Statut

`ready`

## Date de création

2026-09-13

## Branche Git

`feat/SF-96-02-empreintes-publiees`

---

## Objectif

Reconnaître comme **artefact généré non modifié** un fichier déposé **avant** que le produit ne
retienne des empreintes — sans quoi la mise à jour ne toucherait que les postes activés après
F-96, et la **dette réelle laissée par F-95** (le gabarit `STATE.md` a gagné une section
« Statut ») ne serait jamais rattrapée.

---

## Le défaut réparé

SF-96-01 reconnaît une modification locale en comparant le fichier présent à **l'empreinte de ce
qu'on lui avait déposé**. Un poste activé avant F-96 n'a **aucune** empreinte : tous ses fichiers
retombent en `KEEP_LOCAL` — conservés, jamais mis à jour. Le mécanisme fonctionnerait donc pour
l'avenir et laisserait **exactement** les postes existants dans l'état que F-96 devait corriger.

Et la dette est réelle, pas théorique : F-95 a modifié le gabarit `STATE.md` (section « Statut »,
`en cours` / `clos`). Les postes déjà activés gardent l'ancien gabarit, sans cette section — et leur
contrôle de dette/clôture **ne se déclenchera jamais**.

---

## L'idée

Il y a une **deuxième façon** de savoir qu'un fichier est celui du produit : **son contenu est
exactement l'un de ceux que le produit a publiés à ce chemin**. Le produit tient donc, par fichier
de paquet, la liste des **empreintes de ses contenus antérieurs** — un **registre d'empreintes
publiées**, alimenté automatiquement à chaque republication.

| Reconnaissance | Source | Portée |
|---|---|---|
| Empreinte **du dépôt** (SF-96-01) | ce qu'on a écrit sur **ce** poste | fiable, mais seulement depuis F-96 |
| **Empreinte publiée** (celle-ci) | ce que le produit a publié **à ce chemin**, toutes versions | vaut pour **tous** les postes, y compris ceux d'avant |

Un fichier dont le contenu correspond à une empreinte publiée n'a, par construction, **été touché
par personne** : c'est mot pour mot une version que le produit a écrite.

---

## Comportement attendu

### Le registre

Colonne `known_digests` sur `governance_package_files` : les empreintes **sha-256** (fins de ligne
normalisées, même fonction qu'en SF-96-01) des contenus **antérieurs** publiés à ce chemin, une par
ligne, **les plus récentes d'abord**, bornées à `MAX_KNOWN_DIGESTS = 20`.

**Une colonne plutôt qu'une table** : le registre est une propriété du *fichier d'un paquet*, il
naît et meurt avec lui, il n'est jamais interrogé seul, et il est déjà lu par la même requête. Une
table de plus n'apporterait qu'une jointure.

**Alimenté à chaque republication.** Quand le contenu d'un chemin change — semeur
(`GovernancePackageSeeder`) comme rédaction d'admin (`GovernancePackageService.update`) —,
l'empreinte du contenu **qui vient d'être remplacé** est ajoutée en tête du registre du chemin. Le
registre est donc **reporté** à travers le « efface puis réécrit » des deux chemins d'écriture :
sans ce report, il serait perdu à chaque publication, c'est-à-dire toujours.

### Le rattrapage de ce qui a été publié avant F-96

Les contenus publiés avant cette subfeature ne sont plus en base (le semeur a déjà écrasé les
lignes). Ils existent en revanche dans le produit livré : le paquet « savoir-durable » embarque donc
une ressource **`empreintes-anterieures.txt`** — une empreinte par ligne, `sha256<espace>chemin`,
les lignes vides et `#` ignorées — que le semeur **fusionne** dans le registre au démarrage.

C'est la ligne `STATE.md` qui répare la dette F-95, et elle est vérifiable : c'est l'empreinte du
gabarit tel qu'il était publié avant SF-95-01.

### La décision, complétée

La table de SF-96-01 gagne une ligne, **avant** `KEEP_LOCAL` :

| État constaté | Issue |
|---|---|
| … (inchangé) | … |
| `generated`, aucune empreinte de dépôt, contenu ∈ **registre des empreintes publiées** | **`UPDATE`** |
| `generated`, contenu ∉ registre et ≠ empreinte de dépôt | `KEEP_LOCAL` |

Rien d'autre ne change : le doute conserve toujours, et le geste reste celui de l'utilisateur.

### Le poste dit qu'une mise à jour attend

`GovernanceHostSummary` gagne `outdated` — le nombre de paquets actifs sur ce poste dont une
**version plus récente** existe. Calculé en base, sans toucher la machine (comparaison
`appliedVersion` / `package.version`, celle-là même qui alimente déjà `GovernanceActivationView`).
Sans ce compte, l'écran ne peut signaler l'attente **que** sur le poste déjà ouvert, et personne
n'ira voir les autres.

---

## Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Ressource `empreintes-anterieures.txt` absente | le semeur continue, registre simplement non enrichi (ce n'est pas un fichier déposé : la règle du « tout ou rien » ne s'y applique pas) | — |
| Ligne mal formée dans la ressource | ignorée, une trace `debug`, le reste est chargé | — |
| Empreinte inconnue (fichier réellement modifié) | `KEEP_LOCAL` — le comportement protecteur | 200 |
| Registre plein | les plus anciennes empreintes tombent ; un contenu d'il y a 20 versions redevient « modifié localement », donc **conservé** | 200 |

---

## Critères d'acceptation

1. Un fichier dont le contenu correspond à une **empreinte publiée antérieure** est mis à jour
   (`UPDATE`) **même sans aucune empreinte de dépôt**.
2. Un fichier au contenu inconnu du registre reste `KEEP_LOCAL` et n'est **jamais** écrit.
3. Republier un paquet (semeur **ou** admin) **ajoute** l'empreinte du contenu remplacé en tête du
   registre du chemin, et **conserve** les empreintes déjà présentes.
4. Le registre ne dépasse jamais `MAX_KNOWN_DIGESTS`, les plus récentes étant conservées.
5. La ressource `empreintes-anterieures.txt` est fusionnée au démarrage, sans doublon, et elle
   contient l'empreinte du `STATE.md` publié **avant** F-95 — la dette est donc rattrapable.
6. Une ressource absente ou mal formée n'empêche pas le semeur d'aboutir.
7. `GET /governance/hosts` rend `outdated` par poste, sans aucun appel au runner.
8. Aucune empreinte publiée ne dépend d'un utilisateur : le registre est un **contenu produit**,
   comme le paquet lui-même ; l'isolation reste portée par les tables qui en portent une.

---

## Plan de test minimal

**Unitaires**
- `GovernanceKnownDigestsTest` : lecture/écriture de la liste, ordre, borne, doublons, valeurs nulles.
- `GovernancePackageSeederTest` : report du registre à la republication ; fusion de la ressource ;
  ressource absente/mal formée tolérée ; l'empreinte de l'ancien `STATE.md` est reconnue.
- `GovernancePackageServiceTest` : `update()` reporte le registre et y ajoute le contenu remplacé.
- `GovernanceDepositServiceTest` : `UPDATE` par empreinte publiée sans empreinte de dépôt ;
  `KEEP_LOCAL` si le contenu n'est dans aucun des deux.

**Intégration**
- `GovernanceDepositApiIntegrationTest` : un poste « d'avant », sans empreinte, voit son gabarit
  vierge annoncé `UPDATE` puis effectivement remplacé ; un gabarit rempli reste `KEEP_LOCAL`.
- `GovernanceHostApiIntegrationTest` : `outdated` rendu par poste ; **isolation** — le poste d'un
  autre utilisateur reste introuvable.

---

## Tables / endpoints / composants impactés

- **Colonne neuve** : `governance_package_files.known_digests` (migration
  `080-governance-known-digests.xml`).
- **Ressource neuve** : `governance/savoir-durable/empreintes-anterieures.txt`.
- **Backend** : `GovernancePackageFile`, `GovernancePackageSeeder`, `GovernancePackageService`,
  `GovernanceDepositService`, `GovernanceActivationService` (comptage `outdated`),
  `GovernanceHostSummary`.

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucune route nouvelle |
| **Contexte tenant** | **oui** | le registre est un contenu produit **sans** `user_id` (comme le paquet) ; `outdated` est calculé depuis `activations.findByUserIdAndHostId…`, filtré par utilisateur, sur un poste déjà vérifié possédé |
| Plans / limites | non | aucun quota touché |
| Navigation / routing | non | aucune route frontend |

## Hors périmètre

- Conserver le **contenu** des versions antérieures d'un paquet (le registre ne retient que des
  empreintes : il sert à reconnaître, pas à restaurer).
- Un historique de versions consultable à l'écran.
- Tout ajout au texte des règles : marge 83 caractères sur 8 000, et cette subfeature n'y touche pas.
