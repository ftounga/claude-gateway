# Mini-spec — F-96 / SF-96-01 — La troisième issue : mettre à jour un artefact généré

## Identifiant

`F-96 / SF-96-01`

## Feature parente

`F-96` — La gouvernance se met à jour

## Statut

`ready`

## Date de création

2026-09-13

## Branche Git

`feat/SF-96-01-troisieme-issue`

---

## Objectif

Donner au dépôt une **troisième issue — `UPDATE`** — pour les fichiers qu'un paquet **déclare comme
artefacts générés** et **seulement s'ils n'ont pas été modifiés localement**, la modification locale
étant reconnue par une **empreinte de ce qui a été déposé**, retenue au dépôt.

---

## Le défaut réparé

`GovernanceDepositAction` n'a que deux issues : `CREATE` (le fichier n'existe pas) et `KEEP` (il
existe, laissé tel quel, **contenu différent compris**). Un **nouveau** fichier arrive au geste
« appliquer » ; le **contenu** d'un fichier existant, jamais. Conséquence : un skill corrigé
n'atteint jamais un poste qui a déjà l'ancienne version — un client reste sur la gouvernance du jour
de son activation, pour toujours, et le produit publie un catalogue qu'il ne peut pas faire évoluer.

La distinction qui manque était déjà écrite dans le prompt d'origine (`gouv-bootstrap`, §9-4) : les
scripts et les skills **sont écrasés, ce sont des artefacts générés, pas du contenu utilisateur**.
Aujourd'hui le produit traite **tout** comme du contenu utilisateur.

---

## Les deux arbitrages de fond

### A1 — Qu'est-ce qu'un artefact généré ?

**Ce n'est pas le genre du fichier qui décide, c'est le paquet qui le déclare.** Un drapeau
`generated` porté **par fichier de paquet** (`governance_package_files.generated`) :

| | Déclaration | Conséquence |
|---|---|---|
| Un skill publié par le produit | `generated = true` | mis à jour tant qu'il est resté celui qu'on avait déposé |
| Un gabarit (`STATE.md`, `PLAN-ACTION.md`), une carte (`acces.md`) | `generated = true` | idem |
| Un fichier qu'un paquet veut poser **une fois** puis ne plus jamais toucher | `generated = false` | `KEEP`, toujours, sans même lire la machine |

**Défaut = `true`**, à la création comme pour les lignes existantes. C'est l'arbitrage le plus
complet, et il est sûr : un fichier `generated` **n'est jamais écrasé s'il a été touché** — le
drapeau n'ouvre pas une porte, il dit seulement d'où vient le fichier.

### A2 — Le cas limite : un gabarit **encore vierge**

Tranché : **un gabarit encore vierge est un artefact généré, il se met à jour ; dès qu'il est
touché, il devient du contenu utilisateur, pour toujours.**

Pourquoi : un `STATE.md` vierge est **bit pour bit ce que le produit a déposé** — il ne contient le
travail de personne, et son unique raison d'être est d'être **le gabarit courant** le jour où
quelqu'un commence à écrire dedans. Le laisser périmé, c'est livrer à un client un gabarit dont on
sait qu'il est faux. À l'inverse, un `STATE.md` rempli est le journal d'un sujet : l'écraser serait
une perte de données.

Et cette règle ne demande **aucun mécanisme supplémentaire** : la comparaison d'empreinte la donne
gratuitement — empreinte identique = encore vierge = artefact ; empreinte différente = touché =
contenu utilisateur.

---

## Le point dur : reconnaître une modification locale

**Une empreinte de ce qui a été déposé, retenue au dépôt.** Le modèle ne la porte pas :
`governance_host_activations` retient une **version de paquet**, pas un contenu, et les dépôts ne
laissent aujourd'hui aucune trace. Une table est donc ajoutée — `governance_deposited_files`
(migration **079**, numéro libre suivant).

| Colonne | Rôle |
|---|---|
| `user_id`, `host_id` | isolation, en tête de l'index d'unicité |
| `workspace_id` | le dossier destinataire, ou la **clé réservée racine** `0…0` pour la carte |
| `package_id`, `path` | ce qui a été déposé, et où |
| `digest` | **sha-256** du contenu déposé, fins de ligne normalisées |
| `package_version` | la version du paquet au moment du dépôt |

Unicité : `(user_id, host_id, workspace_id, package_id, path)`.

**Normalisation des fins de ligne avant empreinte** (`\r\n` et `\r` → `\n`) : un runner Windows peut
réécrire les fins de ligne sans que personne n'ait touché au fichier, et une empreinte qui s'en
émeut classerait un fichier intact comme « modifié localement ». L'erreur serait sans danger
(conservation) mais elle empêcherait toute mise à jour sur un poste Windows.

---

## Comportement attendu

### La décision, fichier par fichier et destination par destination

| État constaté | Issue | Écrit ? |
|---|---|---|
| Le fichier n'existe pas | `CREATE` | oui, et l'empreinte est retenue |
| Il existe, le paquet ne le déclare pas `generated` | `KEEP` | non |
| Il existe et il est **déjà identique** à ce que le paquet apporte | `KEEP` | non (empreinte rafraîchie) |
| Il existe, `generated`, et son contenu **égale l'empreinte retenue au dépôt** | **`UPDATE`** | oui, nouvelle empreinte retenue |
| Il existe, `generated`, et son contenu **diffère** de l'empreinte retenue (ou aucune empreinte n'est retenue) | **`KEEP_LOCAL`** | **non** |
| Illisible, machine éteinte, chemin refusé | `UNKNOWN` | non |

`KEEP_LOCAL` est une **cinquième** valeur, distincte de `KEEP`, et c'est l'exigence qui fait la
valeur de la feature : *un fichier conservé parce qu'il a été modifié n'est pas la même chose qu'un
fichier conservé parce qu'il était déjà bon.* Sans la distinction, on ne sait jamais si sa
correction est arrivée.

### Le geste reste celui de l'utilisateur

Le dépôt reçoit un **mode** :

- `FULL` — geste explicite (`POST /hosts/{ref}/{packageId}` après confirmation de l'annonce,
  `POST …/apply`) : les cinq issues sont possibles.
- `CREATE_ONLY` — chemins **automatiques** (`depositOnNewProjectQuietly`, à la création d'un
  dossier) : `UPDATE` est **interdit**, un fichier existant reste `KEEP`/`KEEP_LOCAL`. Le produit
  n'écrit jamais par-dessus un fichier sans qu'on le lui ait demandé, et un dossier créé sur un
  répertoire existant ne doit pas voir ses fichiers réécrits dans son dos.

### L'annonce (`plan`) dit exactement ce que le dépôt fera

`GET …/preview` n'écrit rien, ne retient aucune empreinte, et rend les **mêmes issues** que le dépôt
qui suivrait (`UPDATE` compris). Une annonce qui ne saurait pas dire « sera mis à jour » ferait
découvrir la mise à jour après coup.

### Ce que ça coûte, et ce qui le borne

Décider `UPDATE` demande de **lire** le fichier présent. Deux bornes :

1. **Aucune lecture** quand l'empreinte retenue est déjà celle du contenu apporté : rien ne
   changerait, c'est `KEEP`. C'est le cas courant, et il reste à zéro appel.
2. `MAX_UPDATE_READS = 200` lectures par dépôt ; au-delà, les fichiers restants sont `UNKNOWN` —
   annoncés comme indéterminés, jamais écrits.

À la **racine du poste**, la lecture est **gratuite** : `GovernanceHostFiles.read` rend déjà la
présence *et* le contenu en un seul appel, là où le dépôt n'utilisait que la présence.

---

## Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Machine éteinte / dossier illisible | `UNKNOWN`, **rien n'est écrit**, activation `PENDING` | 200 |
| Fichier présent mais illisible | `UNKNOWN` (jamais `UPDATE` : le doute n'écrit pas) | 200 |
| Écriture refusée par le runner | `UNKNOWN`, empreinte **non** retenue, activation `PENDING` | 200 |
| Paquet non actif sur ce poste | « Ce paquet n'est pas actif sur ce poste. » | 404 |
| Chemin devenu invalide | ignoré, dépôt incomplet → `PENDING` | 200 |

---

## Critères d'acceptation

1. Un fichier `generated` déposé puis **non modifié**, dont le paquet change de contenu, est
   **remplacé** au geste « appliquer », et le plan rendu porte `UPDATE`.
2. Le même fichier **modifié sur la machine** est **conservé**, le plan porte `KEEP_LOCAL`, et
   aucune écriture n'est émise pour lui.
3. Un fichier **non déclaré** `generated` est `KEEP` même si son contenu diffère, et **aucune
   lecture** n'est émise pour lui.
4. Un fichier déjà identique à ce que le paquet apporte est `KEEP`, sans écriture.
5. Un fichier absent est `CREATE`, et son empreinte est retenue (une ligne par destination).
6. `plan()` n'écrit **rien**, ne retient **aucune** empreinte, et annonce les mêmes issues que le
   dépôt.
7. En mode `CREATE_ONLY` (création de dossier), un fichier existant n'est **jamais** mis à jour.
8. Un fichier dont seules les **fins de ligne** diffèrent (`\r\n`) est reconnu **non modifié**.
9. Une machine muette rend `UNKNOWN` partout, n'écrit rien, et laisse l'activation `PENDING`.
10. Les empreintes sont lues et écrites **filtrées par `user_id`** ; aucune requête n'existe sans.
11. La carte à la racine suit exactement les mêmes règles, **sans appel supplémentaire**.

---

## Plan de test minimal

**Unitaires — `GovernanceDepositServiceTest`** (complété)
- `UPDATE` d'un artefact inchangé ; `KEEP_LOCAL` d'un artefact modifié (aucune écriture) ;
  `KEEP` d'un non-`generated` (aucune lecture) ; `KEEP` d'un identique ; `CREATE` + empreinte ;
  `CREATE_ONLY` n'update pas ; `UNKNOWN` si illisible ; fins de ligne ; plan n'écrit rien.
- Racine du poste : `UPDATE` et `KEEP_LOCAL` sur un fichier de carte, sans appel supplémentaire.

**Unitaires — `GovernanceDigestTest`** : normalisation + sha-256, stabilité, vide, accents.

**Intégration — `GovernanceDepositApiIntegrationTest`** (complété) : `preview` puis `apply` annoncent
et exécutent la même chose ; **isolation** — l'empreinte d'un autre utilisateur n'est jamais lue.

**Semeur — `GovernancePackageSeederTest`** : tous les fichiers du paquet produit sont déclarés
`generated`, et la déclaration participe à `isUpToDate` (la version est incrémentée si elle change).

---

## Tables / endpoints / composants impactés

- **Table neuve** : `governance_deposited_files` (migration `079-governance-deposited-files.xml`).
- **Colonne neuve** : `governance_package_files.generated` (même migration).
- **Backend** : `GovernanceDepositService`, `GovernanceDepositAction` (+`UPDATE`, +`KEEP_LOCAL`),
  `GovernancePackageFile`, `GovernancePackageService` (validation + report du drapeau),
  `GovernancePackageSeeder` ; neufs : `GovernanceDepositedFile`,
  `GovernanceDepositedFileRepository`, `GovernanceDigest`.
- **DTO** : `GovernancePackageFileRequest` (+`generated`), `GovernanceFileDetail` (+`generated`).
- **Aucune route nouvelle** : `preview`, l'activation et `apply` existent déjà.

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucune route nouvelle, aucun changement d'authentification |
| **Contexte tenant** | **oui** | `governance_deposited_files` porte `user_id` en tête d'unicité ; toutes les lectures passent par `findByUserIdAndHostId…` ; `GovernanceDepositService` reçoit un poste **déjà vérifié possédé** par `GovernanceHostScope` ; les dossiers viennent de `GovernanceHostScope.projectsOf`, jamais d'un identifiant reçu du client |
| Plans / limites | non | aucun quota touché |
| Navigation / routing | non | aucune route frontend |

## Hors périmètre

- **Écraser du contenu utilisateur, dans quelque cas que ce soit.** Il n'y a **pas** de geste
  « forcer » ; s'il en fallait un, ce serait une décision du PO.
- Supprimer un fichier qu'un paquet ne porte plus (une suppression sur la machine d'un client est
  une autre décision).
- Reconnaître un fichier déposé **avant** F-96, sans empreinte retenue → `KEEP_LOCAL` ici, traité
  par **SF-96-02**.
- Toute modification du texte des règles (`regles.md`) : F-96 est mécanique. La consigne système du
  paquet « savoir-durable » fait **7 917 / 8 000** caractères (marge 83) ; cette subfeature n'y
  ajoute **rien**, et le test de garde existant le vérifie.
