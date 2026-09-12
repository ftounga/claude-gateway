# Mini-spec — F-92 / SF-92-02 — Lire la carte : ce que la machine sait

## Identifiant

`F-92 / SF-92-02`

## Feature parente

`F-92` — La carte du poste

## Statut

`done` — mergée le 2026-09-12 (PR #455)

## Date de création

2026-09-12

## Branche Git

`feat/SF-92-02-lire-la-carte`

---

## Objectif

Rendre la carte **lisible depuis la gateway** : un endpoint qui dit, poste par poste, quels fichiers
de carte existent, **combien de faits** chacun porte et **dans quelles sections** — et un second qui
rend le contenu exact d'un fichier de carte.

---

## Comportement attendu

### Pourquoi cette subfeature existe

SF-92-01 a posé les fichiers. **Un fichier qu'on ne voit jamais n'est pas un savoir, c'est un
fichier.** Tant que la carte ne se lit qu'en ouvrant un terminal, elle n'existe pas pour
l'utilisateur — et le but du PO (*« à chaque projet qu'on ajoute, la connaissance de l'infra
augmente »*) reste invérifiable. Cette subfeature fournit la **matière** ; SF-92-03 la met à l'écran.

### Cas nominal — le relevé de la carte

`GET /api/governance/hosts/{hostRef}/map`

1. Le poste est vérifié **possédé** (`GovernanceHostScope.require` — 404 sinon, sans oracle).
2. Les fichiers attendus sont ceux de genre `MAP` apportés par les paquets **actifs sur ce poste**.
   On ne lit **rien d'autre** : c'est une lecture de gouvernance, pas un explorateur de fichiers.
3. Chacun est lu à la racine par `GovernanceHostFiles`, et **résumé** :

   | Champ | Ce qu'il dit |
   |---|---|
   | `path` | le nom du fichier à la racine |
   | `title` | son titre de premier niveau (`# …`), ou le nom du fichier à défaut |
   | `present` | vrai s'il existe sur la machine |
   | `readable` | faux si la machine n'a pas su répondre |
   | `sections` | une entrée par section de niveau 2, avec **le nombre de faits** qu'elle porte |
   | `facts` | le total des faits du fichier |
   | `truncated` | vrai si le producteur a coupé le contenu — une coupe se **dit** |

4. Le relevé porte les **totaux** du poste : fichiers attendus, fichiers présents, sections, **faits**.
   C'est le seul chiffre qui répond à la question du PO, et il n'a de sens qu'agrégé.

### Ce qu'est un « fait »

Un gabarit livré est **structuré et vide** : il ne doit compter **aucun** fait. Ne comptent donc pas :

- les lignes vides, les titres (`#`, `##`, `###`…) ;
- les **consignes** : citation (`> …`) et texte en italique seul (`_…_`, `*…*`) ;
- l'**en-tête** d'un tableau et sa ligne de séparation (`|---|---|`) ;
- une ligne de tableau dont **toutes** les cellules sont vides (`| | | |`) ;
- une case à cocher **vide** (`- [ ]` sans texte) et une puce vide.

Compte pour **un fait** : une ligne de tableau portant au moins une cellule renseignée, une puce
renseignée, une case à cocher renseignée, ou une ligne de texte ordinaire.

**Ce compte est une jauge, pas une autorité.** Il sert à montrer que la carte se remplit ; aucune
décision produit ne s'y attache.

### Cas nominal — lire un fichier de carte

`GET /api/governance/hosts/{hostRef}/map/file?path=acces.md`

Rend le **contenu exact**, borné à 200 000 caractères (la borne de F-75 / SF-75-02, réemployée), avec
le drapeau de troncature. **Seuls les chemins de carte des paquets actifs** sont lisibles : un chemin
quelconque rend 404, même s'il existe à la racine.

### La lecture est bornée en coût

Six fichiers, six allers-retours. Deux gardes :

1. **On ne lit que si le poste peut l'être** : poste « Hébergé » → aucun appel, aucun paquet actif →
   aucun appel.
2. **On s'arrête au premier refus de transport.** Si une lecture revient `runner_unavailable`,
   `runner_timeout` ou `runner_not_on_this_node`, les suivantes ne partent pas : le relevé est rendu
   **non lu**, avec son action corrective. Sans cette garde, un runner branché mais muet ferait
   attendre six délais d'affilée.

### Cas d'erreur — chaque message porte son action corrective

| Situation | Réponse | Message |
|---|---|---|
| Poste **« Hébergé »** | 200, `supported = false` | « Ce poste n'est pas une machine : la carte vit à la racine d'un poste réel. Connectez une machine pour qu'elle ait une carte. » |
| **Aucun paquet actif** portant une carte | 200, `governed = false` | « Aucune gouvernance active sur ce poste : activez « Le savoir durable » depuis l'écran Gouvernance pour que sa carte existe. » |
| Runner **non connecté** / muet | 200, `readable = false` | « La racine de ce poste n'a pas pu être lue : lancez le runner sur la machine, puis rechargez. » |
| Un fichier **absent** de la racine | 200, ce fichier `present = false` | « Ce fichier de carte manque : reprenez « Appliquer » sur ce poste pour le reposer. » |
| Un fichier **illisible** (droits) | 200, ce fichier `readable = false` | « Ce fichier n'a pas pu être lu sur la machine : vérifiez les droits, puis rechargez. » |
| `path` **absent** de la requête de contenu | 400 | « Chemin de fichier requis. » |
| `path` **hors carte** | 404 | « Ce chemin n'appartient pas à la carte de ce poste. » |
| Poste d'un **autre utilisateur** | 404 | « Poste introuvable. » |
| Sans accès Forge | 402 / 403 (inchangé) | — |

**Une machine éteinte n'est jamais rendue comme une carte vide** : c'est la distinction qui compte,
et elle est portée par `readable` — la même discipline que `GovernanceProjectFiles.listPaths`.

---

## Critères d'acceptation

- [ ] `GET /governance/hosts/{hostRef}/map` rend le relevé de la carte d'un poste possédé.
- [ ] Les fichiers lus sont **exactement** les fichiers `MAP` des paquets **actifs** sur ce poste.
- [ ] Un gabarit **livré tel quel** compte **0 fait**, et ses sections apparaissent toutes.
- [ ] Un tableau rempli d'une ligne compte **1 fait** ; son en-tête et sa séparation n'en comptent
      aucun ; une ligne de cellules vides n'en compte aucun.
- [ ] Une consigne en italique ou en citation ne compte **aucun** fait.
- [ ] Une case `- [ ]` vide ne compte aucun fait ; renseignée, elle en compte un.
- [ ] Les totaux du poste (fichiers présents / attendus, sections, faits) sont exacts.
- [ ] Poste « Hébergé » → `supported = false`, **aucun appel runner**, message avec son geste.
- [ ] Aucun paquet actif → `governed = false`, **aucun appel runner**, message avec son geste.
- [ ] Runner muet → `readable = false`, message avec son geste, et **au plus une** lecture tentée.
- [ ] `GET …/map/file?path=` rend le contenu exact, borné, et dit s'il a été coupé.
- [ ] Un chemin hors carte rend **404**, même s'il existe à la racine.
- [ ] Le poste d'un autre utilisateur rend **404** et ne déclenche aucune lecture.
- [ ] Aucun contenu de fichier n'est journalisé.
- [ ] Tests verts, backend compilé.

---

## Plan de test minimal

### Unitaires

- `GovernanceMapDigestTest` — le compte de faits : gabarit livré → 0 ; tableau à en-tête → 0 ;
  tableau à une ligne renseignée → 1 ; ligne de cellules vides → 0 ; citation et italique → 0 ;
  `- [ ]` vide → 0, renseignée → 1 ; texte ordinaire → 1 ; titre de niveau 1 rendu comme `title` ;
  sections de niveau 2 listées dans l'ordre du fichier ; contenu sans section → un fichier à 0
  section mais dont les faits comptent quand même.
- `GovernanceMapReadingServiceTest` — fichiers attendus = fichiers `MAP` des paquets actifs ;
  « Hébergé » et « aucun paquet actif » ne déclenchent aucun appel ; un refus de transport **arrête**
  les lectures suivantes ; un `not_found` rend `present = false` sans arrêter les autres ; chaque
  message porte son action corrective ; un chemin hors carte lève « introuvable ».

### Intégration

- `GovernanceMapApiIntegrationTest` — 401 sans jeton ; 404 sur le poste d'autrui ; 200 sur le sien ;
  400 sans `path` ; 404 sur un `path` hors carte ; poste « Hébergé » → `supported = false`.

### Isolation utilisateur

- Le poste d'un autre utilisateur rend 404 et **aucune** lecture ne part vers sa machine.
- Les activations lues le sont par `user_id` + `host_id` (inchangé, re-vérifié).

---

## Tables / endpoints / composants impactés

### Tables

Aucune. Lecture pure.

### Endpoints

| Endpoint | Rôle |
|---|---|
| `GET /api/governance/hosts/{hostRef}/map` | **nouveau** — le relevé de la carte |
| `GET /api/governance/hosts/{hostRef}/map/file?path=…` | **nouveau** — le contenu d'un fichier de carte |

### Composants

| Composant | Changement |
|---|---|
| `GovernanceMapDigest` | **nouveau** — le résumé d'un fichier de carte (pur, sans I/O) |
| `GovernanceMapReadingService` | **nouveau** — assemble le relevé d'un poste |
| `GovernanceHostFiles` | expose le code d'erreur de transport pour la garde d'arrêt |
| `GovernanceHostController` | deux routes de lecture |
| `dto/GovernanceMapView`, `GovernanceMapFileView`, `GovernanceMapSectionView`, `GovernanceMapFileContent` | **nouveaux** |

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | Deux routes de plus visant un **poste**. Résolution du tenant : `GovernanceHostScope.require` — le seul chemin, inchangé. Composants vérifiés : `GovernanceHostController` (les deux nouvelles routes appellent `atelierAccess.requireAccess()` puis `hostScope.require`, comme les six existantes), `GovernanceMapReadingService` (ne reçoit qu'un `GovernanceHostRef` déjà vérifié), `GovernanceActivationService.activeOn` (lecture par `userId` + `hostId`), `GovernanceHostFiles` (inchangé), `RunnerAuditService` (ligne portée par `user_id`) |
| Plans / limites | non | — |
| Navigation / routing | non | — |

---

## Périmètre

### Hors scope (explicite)

- **L'écran** : c'est SF-92-03.
- **Écrire** dans la carte depuis la gateway : la carte s'écrit depuis le terminal du poste, par
  l'utilisateur ou par le modèle. Un endpoint d'écriture ferait de la gateway un éditeur.
- Juger la **qualité** d'un fait (daté ? sourcé ?) : c'est l'intégrité du poste, F-95.
- Comparer la carte aux notes de projets : c'est le juge, F-94.
- Mettre la carte en cache : à décider quand un coût réel aura été mesuré, pas avant.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|---|---|---|
| `facts` d'un gabarit livré | **0** | un gabarit est structuré et vide ; s'il comptait, la jauge mentirait dès le premier jour |
| `supported` | `false` pour « Hébergé » | il n'y a pas de machine |
| `governed` | `false` tant qu'aucun paquet actif n'apporte de carte | rien à lire, rien à dire |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|---|---|---|---|---|
| `hostRef` (chemin) | oui | — | UUID ou `hosted` | `GovernanceHostRef.parse` |
| `path` (requête de contenu) | oui | 255 | relatif, `/`, sans `..` | `GovernancePath.normalizeOrNull` puis comparaison **stricte** à la liste des chemins de carte |
| contenu rendu | — | 200 000 caractères | UTF-8 | coupé, et la coupe est **dite** |
