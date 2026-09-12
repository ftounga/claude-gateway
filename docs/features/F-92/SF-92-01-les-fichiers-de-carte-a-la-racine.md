# Mini-spec — F-92 / SF-92-01 — Les fichiers de carte, à la racine du poste

## Identifiant

`F-92 / SF-92-01`

## Feature parente

`F-92` — La carte du poste

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-92-01-carte-du-poste`

---

## Objectif

Faire exister **la carte** : six fichiers `.md` posés **à la racine du poste**, structurés mais
vides, ajoutés au paquet « savoir-durable » et déposés à l'activation **sans jamais écraser** ce qui
est déjà là.

---

## Comportement attendu

### Ce qu'est la carte

La carte, ce sont **les fichiers de la racine du poste**, à côté des dossiers de projets. Aucune
convention de chemin n'est imposée : la racine est celle que le runner a déclarée, `dev` chez l'un,
`infra` chez l'autre. **Les projets sont des dossiers, la carte des fichiers** — aucune confusion
possible, et c'est ce qui rend la transposition gratuite.

| Fichier | Ce qu'il porte |
|---|---|
| `README.md` | Vue d'ensemble, tableau des grands domaines, annuaire des projets, **et les contacts** : qui accorde quoi, par quel canal, avec quelle convention d'échange |
| `acces.md` | Comment joindre chaque environnement — **récap VPN en tableau**, forges, bastions, droits — **et les pièges**. Chaque fait est **daté** |
| `reseau.md` | Réseaux, plages, DNS, domaines, flux ouverts, certificats |
| `plateformes.md` | Clusters, serveurs, hébergements, stockage, files, ordonnanceurs |
| `donnees.md` | Bases, schémas, sauvegardes, restaurations éprouvées, données sensibles |
| `exploitation.md` | Supervision, alertes, astreinte, procédures, incidents marquants |

Chaque fichier porte, **en tête**, la règle d'écriture : *n'y mettre que des **faits**, datés, avec
leur source ; et pointer vers la source de vérité quand elle existe ailleurs plutôt que de la
recopier.*

### Le genre `MAP` et son point de chute

Un fichier de paquet porte désormais **trois** genres. Le genre décide **où** le fichier se pose :

| Genre | Point de chute | Inchangé depuis |
|---|---|---|
| `SKILL` | `.claude/skills/` de **chaque projet** du poste | F-51 |
| `TEMPLATE` | la racine de **chaque projet** du poste | F-51 |
| `MAP` | la **racine du poste**, une seule fois | **cette subfeature** |

### Cas nominal — l'activation

1. L'utilisateur active « savoir-durable » sur un poste réel connecté.
2. Les fichiers `SKILL` et `TEMPLATE` se déposent dans chaque projet (inchangé).
3. Les fichiers `MAP` se déposent **une fois**, à la racine du poste, par le runner.
4. L'annonce (`preview`) et le plan réalisé portent une **section racine** distincte des projets :
   on voit que six fichiers iront à la racine, et lesquels.
5. Tout est en place partout → l'activation passe `APPLIED`. Sinon elle reste `PENDING`, et le geste
   « appliquer » de F-75 la rejouera.

### La règle qui prime : **jamais écrasé**

La présence d'un fichier de carte est établie **fichier par fichier**, par une lecture explicite :

| Ce que le runner répond à `read_file` | Ce qu'on en conclut | Ce qu'on écrit |
|---|---|---|
| succès | le fichier est là | **rien** (`KEEP`) |
| `not_found` | le fichier est absent | le gabarit (`CREATE`) |
| tout le reste (`io_error`, `too_large`, `is_directory`, runner muet…) | **on ne sait pas** | **rien** (`UNKNOWN`) |

On n'utilise **pas** l'arborescence (`list_files`) pour cette décision : à la racine d'un poste réel
elle est récursive et **tronquée** (SF-38-21), et une troncature muette ferait conclure « absent »
pour un fichier bien présent — donc écraser la carte d'un client. Le doute ne fait jamais écrire.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Runner **non connecté** | Aucune écriture. La section racine est `readable = false`, toutes ses entrées `UNKNOWN`, l'activation reste `PENDING`, le geste « appliquer » reste offert |
| Poste **« Hébergé »** (F-71) | Il n'a pas de machine, donc pas de racine : la section racine est `supported = false` et **ne bloque pas** la complétion. Le message le dit **avec son geste** : « Ce poste n'est pas une machine : la carte vit à la racine d'un poste réel. Connectez une machine pour qu'elle ait une carte. » |
| Lecture d'un fichier de carte **refusée** (`io_error`, droits) | Entrée `UNKNOWN`, rien d'écrit, activation `PENDING` |
| Écriture **refusée** par la machine | Entrée `UNKNOWN`, activation `PENDING` |
| Poste d'un **autre utilisateur** | 404 « introuvable » (inchangé, `GovernanceHostScope.require`) |
| Ressource de gabarit **absente** du produit | Le paquet **n'est pas semé du tout** (règle du tout ou rien de F-52, inchangée) |

### Le point de conception à tracer (demande explicite du cadrage)

**Un fichier de la racine ne peut pas apparaître comme projet candidat.** Vérifié dans
`RunnerHostFolderBrowser.folderNames` : un chemin sans `/` est ignoré (`slash <= 0`), et les six
fichiers de carte sont à la racine, donc sans `/`. **Rien à corriger.** Un test de non-régression est
ajouté qui les nomme un par un : c'est une garantie qu'on ne veut pas voir disparaître par mégarde.

### Le défaut corrigé au passage

`RunnerRelayClient.payload` appelait `target.workspaceId().toString()` sans garde. Un appel de
**poste** (`workspaceId` nul — déjà le cas de `RunnerHostFolderBrowser` depuis F-71) relayé vers un
pod pair lève donc un `NullPointerException` au lieu d'exécuter. Le dépôt de la carte emprunte
exactement ce chemin : le champ devient facultatif dans la trame de relais.

---

## Critères d'acceptation

- [ ] `GovernanceFileKind` porte `MAP`, documenté comme « à la racine du **poste** ».
- [ ] Le paquet « savoir-durable » apporte **six** fichiers `MAP` : `README.md`, `acces.md`,
      `reseau.md`, `plateformes.md`, `donnees.md`, `exploitation.md`.
- [ ] Chacun est **structuré et vide** : des en-têtes de section prêts à recevoir des faits, et
      **aucun fait inventé**.
- [ ] Chacun porte la règle d'écriture : **des faits, datés, avec leur source**.
- [ ] `acces.md` porte un **tableau VPN** et une section **pièges** dont chaque ligne prévoit
      « constaté le … ».
- [ ] `README.md` porte une section **contacts** (qui accorde quoi, par quel canal, avec quelle
      convention d'échange).
- [ ] L'activation sur un poste réel connecté crée les six fichiers **à la racine**, une seule fois.
- [ ] Un fichier de carte **déjà présent** est laissé **tel quel**, contenu différent compris.
- [ ] Une lecture **inconclusive** (autre que `not_found`) n'écrit **rien**.
- [ ] Les fichiers `MAP` ne sont **pas** déposés dans les projets ; les `TEMPLATE` / `SKILL` ne sont
      **pas** déposés à la racine du poste.
- [ ] Le plan de dépôt porte une section **racine** (`root`) distincte de `projects`.
- [ ] Poste « Hébergé » : `supported = false`, aucune écriture, et la complétion **n'est pas**
      bloquée par la racine.
- [ ] Runner éteint : aucune écriture, activation `PENDING`.
- [ ] Chaque lecture et chaque écriture à la racine est **auditée** sous un nom d'outil propre.
- [ ] Un fichier de la racine n'apparaît **jamais** comme dossier candidat (test de non-régression
      nommant les six fichiers).
- [ ] Un appel de poste relayé vers un pod pair ne lève plus de `NullPointerException`.
- [ ] Le paquet est **republié** : sa version s'incrémente au démarrage, et les postes déjà activés
      voient « republié depuis » et disposent du geste « appliquer » (F-75, inchangé).
- [ ] Tests verts, backend compilé.

---

## Plan de test minimal

### Unitaires

- `GovernanceMapDepositTest` — `KEEP` quand la lecture réussit ; `CREATE` quand `not_found` ;
  `UNKNOWN` sur `io_error`, sur `runner_unavailable`, sur un contenu illisible ; et **aucune
  écriture** dans les deux derniers cas.
- Séparation des genres : un `MAP` ne part pas dans un projet, un `TEMPLATE` ne part pas à la racine.
- « Hébergé » : aucune tentative d'appel runner, `supported = false`, complétion non bloquée.
- `RunnerHostFolderBrowserTest` — les six noms de fichiers de carte à la racine ne produisent
  **aucun** dossier candidat.
- `GovernancePackageSeederTest` — le paquet porte six fichiers `MAP` aux bons chemins, et le semeur
  reste idempotent (second passage : aucune écriture).
- `RunnerRelayClientTest` (ou équivalent sur `payload`) — `workspaceId` nul → trame sans le champ,
  pas d'exception.

### Intégration

- `GovernanceSeededPackageIntegrationTest` — le paquet semé expose les six chemins de carte avec le
  genre `MAP`.
- Activation de bout en bout sur un poste simulé : six écritures à la racine, zéro à la seconde
  activation.

### Isolation utilisateur

- Le poste d'un autre utilisateur rend 404 et **n'écrit rien** à sa racine.
- Chaque lecture d'activation porte le `user_id` (inchangé, re-vérifié).

---

## Tables / endpoints / composants impactés

### Tables

Aucune migration. `governance_package_files.kind` est un `varchar(16)` sans contrainte de valeur :
`MAP` y entre tel quel.

### Endpoints

| Endpoint | Changement |
|---|---|
| `GET /api/governance/hosts/{hostRef}/{packageId}/preview` | la réponse gagne `root` |
| `POST /api/governance/hosts/{hostRef}/{packageId}` | dépose aussi la carte à la racine |
| `POST /api/governance/hosts/{hostRef}/{packageId}/apply` | idem, et la réponse gagne `root` |

### Composants

| Composant | Changement |
|---|---|
| `GovernanceFileKind` | `MAP` |
| `GovernanceHostFiles` | **nouveau** — lit et écrit à la racine du poste, par le runner, audité |
| `GovernanceDepositService` | dépôt racine + dépôt projets |
| `GovernanceDepositPlan` / `GovernanceRootDepositPlan` | section racine |
| `GovernancePackageSeeder` | six gabarits de carte de plus |
| `resources/governance/savoir-durable/carte/*.md` | **nouveaux** gabarits |
| `resources/governance/savoir-durable/regles.md` | la section « La carte du poste » |
| `RunnerRelayClient` | `workspaceId` facultatif dans la trame de relais |

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | Le dépôt racine vise un **poste**. Résolution du tenant : `GovernanceHostScope.require` (poste possédé) — le seul chemin d'entrée, inchangé. Composants vérifiés : `GovernanceHostController` (toutes les routes passent par `require`), `GovernanceDepositService.deposit` (activation lue par `userId + hostId`), `GovernanceDepositService.depositOnNewProjectQuietly` (poste dérivé d'un projet **possédé**), `GovernanceHostFiles` (reçoit un `GovernanceHostRef` déjà vérifié, jamais un identifiant brut), `RunnerAuditService.recordCall` (ligne portée par `user_id`) |
| Plans / limites | non | — |
| Navigation / routing | non | — |

---

## Périmètre

### Hors scope (explicite)

- **Lire et rendre** la carte à l'écran : c'est SF-92-02 (lecture) et SF-92-03 (écran).
- Faire pointer la **promotion** vers la carte : c'est F-93.
- Le **juge indépendant** : F-94. L'**intégrité** (`infra-doctor`) : F-95.
- Écrire quoi que ce soit **dans** la carte à la place de l'utilisateur : les gabarits sont vides, et
  ils le restent.
- Imposer une convention de chemin de racine : il n'y en a pas, et il n'y en aura pas.
- Les cinq scripts du prompt d'origine : hors périmètre définitif (F-50).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|---|---|---|
| `kind` d'un fichier de paquet | `TEMPLATE` | inchangé ; `MAP` est explicite, jamais déduit |
| Contenu des gabarits de carte | **vide de faits** | des en-têtes, une règle d'écriture, rien d'autre |
| Section racine d'un poste « Hébergé » | `supported = false` | il n'y a pas de machine |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|---|---|---|---|---|
| chemin d'un fichier `MAP` | oui | 255 (`GovernancePath.MAX_LENGTH`) | relatif, `/`, sans `..` | `GovernancePath.normalizeOrNull`, à la publication **et** à l'écriture |
| contenu d'un gabarit | oui | 64 000 (`GovernancePackageFile.MAX_CONTENT_LENGTH`) | Markdown | — |
| écriture runner | — | 512 Kio (`RunnerToolGateway.MAX_WRITE_BYTES`) | UTF-8 | — |
