# Mini-spec — F-60 / SF-60-02 — L'état de mission se voit et se choisit

---

## Identifiant

`F-60 / SF-60-02`

## Feature parente

`F-60` — Cycle de vie des postes, visible d'un coup d'œil (`docs/PRODUCT_SPEC.md`)

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-60-02-etat-mission-ecrans`

---

## Objectif

Montrer l'état de mission d'un poste **partout où le poste apparaît**, le laisser **changer depuis
la vue d'ensemble**, et **ranger** les postes clôturés hors de la vue principale sans les faire
disparaître — sans jamais entrer en concurrence avec l'identité visuelle du poste (SF-49-03).

---

## Comportement attendu

### Cas nominal

1. **Une pastille d'état, pas un second aplat.** L'état entre par une pastille `.badge` de la
   charte (§5), **à côté du nom** : vert `.badge--success` pour *En cours*, ambre
   `.badge--warning` pour *En attente*, gris `.badge--neutral` pour *Clôturé*. Le filet gauche de
   la carte reste **celui de l'identité du poste** (SF-49-03) et reste **seul** : aucun second
   filet, aucun fond de carte teinté, aucune couleur de statut appliquée aux initiales.
2. **La couleur double un libellé, elle ne le remplace jamais.** La pastille écrit toujours
   *En cours*, *En attente* ou *Clôturé*. Un composant unique — `app-mission-badge` — le garantit :
   il n'a pas de mode « point seul ».
3. **Le choix se fait depuis `/postes`.** La pastille de la carte est un bouton qui ouvre un
   `mat-menu` à trois entrées. Le choix part immédiatement à la gateway ; la carte prend le nouvel
   état **quand la gateway l'a confirmé** — jamais avant. En cas d'échec, l'état affiché ne bouge
   pas et un `MatSnackBar` d'erreur le dit.
4. **Un clôturé se range.** La vue principale de `/postes` ne montre que les postes *en cours* et
   *en attente*. Les clôturés partent dans un repli refermé, intitulé « Missions clôturées (n) »,
   qui s'ouvre d'un clic et montre **exactement les mêmes cartes** — mêmes projets, même bouton
   Terminal, même possibilité de rouvrir la mission. Rien n'est perdu, rien n'est coupé.
5. **Un état porté ailleurs, sans bruit.** Dans la Forge, la ligne d'un projet et l'en-tête du
   terminal montrent l'état du poste **quand il n'est pas *En cours*** — c'est-à-dire quand il
   change la lecture de ce qu'on regarde. *En cours* est la norme et reste silencieux hors de
   `/postes`, où l'état est toujours écrit pour les trois valeurs.
6. **Le rafraîchissement ne piétine pas le geste.** Les relectures de la vue (toutes les quinze
   secondes) continuent de faire foi : l'état affiché est **toujours** celui rendu par la gateway.
7. **Le compteur du repli suit la vue.** « Missions clôturées (n) » compte ce que la réponse
   contient, pas une valeur mise en cache.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Le changement d'état échoue (réseau, 5xx) | La carte **garde** son état précédent ; snackbar « L'état de la mission n'a pas pu être enregistré. » | — |
| Le changement d'état est refusé (403, droit Atelier perdu) | Même repli : état inchangé + snackbar | 403 |
| Le poste a disparu entre-temps (404) | État inchangé + snackbar ; la relecture suivante fera disparaître la carte | 404 |
| La gateway ne rend pas `missionStatus` (backend antérieur) | Champ optionnel : l'écran retombe sur **En cours**, aucune pastille cassée, aucun « inconnu » | 200 |
| Le dernier poste non clôturé passe à `CLOSED` | La vue principale devient vide et le dit — « Toutes vos missions sont clôturées » — **sans** masquer le repli, qui reste ouvrable | — |
| Aucun poste du tout | Message d'accueil existant, inchangé | — |

---

## Critères d'acceptation

- [ ] `app-mission-badge` rend **toujours** un libellé écrit (`En cours` / `En attente` /
      `Clôturé`) ; aucun mode ne permet de n'afficher que la couleur.
- [ ] `app-mission-badge` emploie **exclusivement** les classes `.badge--success`,
      `.badge--warning`, `.badge--neutral` de la charte (§5) — **aucune** couleur littérale, et
      **aucun** ton de la palette d'identité §9.
- [ ] La carte de poste sur `/postes` porte, dans le même bloc : la pastille d'identité (§9), le
      nom écrit, l'état technique (`Connecté` / `Vu il y a …`), et la pastille de mission. Le filet
      gauche reste **le seul** élément coloré par l'identité.
- [ ] Un test vérifie qu'**aucun** style de la carte n'applique une couleur de statut à l'identité,
      ni une couleur d'identité à l'état : le filet gauche reste `hostTone(name).solid` quel que
      soit l'état de mission.
- [ ] Choisir « En attente » sur une carte appelle `PUT /api/runner-hosts/{id}/mission` avec
      `{"missionStatus":"PENDING"}` et affiche l'état **rendu par la réponse**.
- [ ] Un échec du changement d'état **laisse l'état affiché inchangé** et ouvre un snackbar.
- [ ] Un poste `CLOSED` **n'apparaît pas** dans la liste principale et **apparaît** dans le repli
      « Missions clôturées (n) », avec ses projets et son bouton Terminal intacts.
- [ ] Depuis le repli, remettre un poste « En cours » le fait remonter dans la liste principale.
- [ ] Quand tous les postes sont clôturés, la liste principale montre son message dédié **et** le
      repli reste présent et ouvrable.
- [ ] La liste des projets de la Forge et l'en-tête du terminal montrent l'état **quand il n'est
      pas `ACTIVE`**, avec son libellé écrit, et **rien** quand il vaut `ACTIVE` ou quand le projet
      n'est rattaché à aucun poste.
- [ ] Aucun acquis de **F-56** (passe de cohérence) ni de **SF-49-03** n'est annulé : jetons
      `--cg-space-*` pour les espacements, `--cg-font-*` pour les polices, `.badge` de la charte
      pour les pastilles, `app-host-badge` inchangé dans son rôle.
- [ ] `npm run build` et `npm test` verts ; `./mvnw -pl backend test` vert.

---

## Périmètre

### Hors scope (explicite)

- **Dates, jalons, facturation à la mission** — hors périmètre de F-60.
- **Filtrer, trier ou grouper `/postes` par état** au-delà du rangement des clôturés. Trois états,
  deux groupes : une barre de filtres serait plus de commandes que de contenu.
- **Changer l'état ailleurs que sur `/postes`.** La Forge **montre** l'état, elle ne le change pas :
  on ne clôt pas une mission depuis l'écran où l'on travaille dessus.
- **Le dialogue de mise en service** (`runner-pairing-dialog`) : choisir la machine à laquelle
  rattacher un projet est un geste technique ; l'état de mission n'y change rien et n'y entre pas.
- **Une confirmation à la clôture.** Le geste est **réversible** en un clic et ne coupe rien —
  un `MatDialog` de confirmation y serait du bruit. Les confirmations restent réservées au
  destructif (supprimer un poste, couper une liaison).
- **Masquer les projets d'un poste clôturé dans la Forge.** Une mission close reste consultable :
  ses projets restent dans la liste, avec leur état écrit.
- **Le mode sombre** — le produit n'en a pas.

---

## Valeurs initiales

| Élément | Valeur |
|---------|--------|
| État affiché quand `missionStatus` est absent de la réponse | `ACTIVE` (« En cours ») |
| Repli « Missions clôturées » | **Refermé** à l'ouverture de l'écran |

Le repli est refermé par défaut : sa raison d'être est de **retirer** les missions closes du champ
de vision. L'ouvrir d'office annulerait le rangement.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `RunnerHostOverview.missionStatus` | Non (champ additif) | — | `'ACTIVE' \| 'PENDING' \| 'CLOSED'` | Non | Valeur absente ou inconnue ⇒ traitée comme `ACTIVE`, jamais affichée telle quelle |
| `WorkspaceSummary.hostMissionStatus` | Non (champ additif) | — | idem | Non | idem |
| `RunnerStatus.hostMissionStatus` | Non (champ additif) | — | idem | Non | idem |
| Libellés | — | — | `En cours`, `En attente`, `Clôturé` — écrits en dur, jamais dérivés de la valeur d'API | — | — |

Une valeur inconnue est **repliée sur `ACTIVE`** et non affichée : une pastille qui écrirait la
valeur brute d'une API serait un « inconnu » déguisé, que la charte proscrit.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum | Changement |
|---------|-----|------|-------------|------------|
| PUT | `/api/runner-hosts/{hostId}/mission` | JWT | utilisateur + droit Atelier | **Consommé** (créé en SF-60-01) |
| GET | `/api/runner-hosts/overview` | JWT | idem | **Consommé** (`missionStatus`) |
| GET | `/api/workspaces` | JWT | idem | **Additif** : `hostMissionStatus` (nullable) |
| GET | `/api/workspaces/{id}/runner/status` | JWT | idem | **Additif** : `hostMissionStatus` (nullable) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_hosts` | `SELECT` | Lecture déjà filtrée `user_id` (`RunnerHostService.list` / `requireOwned`). Aucune requête supplémentaire par projet : la carte des postes est déjà bâtie par `AtelierController#list` depuis SF-49-03 |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucun schéma touché ; la colonne est celle de SF-60-01.

### Composants Angular

- `shared/mission-status.ts` — **nouveau** : type `HostMissionStatus`, libellés, classe de pastille,
  et `normalizeMissionStatus()` (repli `ACTIVE`). Fonctions pures, aucune dépendance Angular.
- `shared/mission-badge/mission-badge.component.*` — **nouveau** : pastille d'état, libellé
  toujours écrit, `.badge--*` de la charte.
- `postes/postes.component.*` — pastille de mission par carte, menu de choix, séparation
  actives / clôturées, repli dépliable, message de vue principale vide.
- `atelier/atelier.component.*` — état du poste sur la ligne du projet (si ≠ `ACTIVE`) et passage
  au terminal.
- `atelier/terminal/atelier-terminal.component.*` — état du poste en tête de barre (si ≠ `ACTIVE`).
- `core/models/atelier.models.ts` — `missionStatus` sur `RunnerHostOverview`,
  `hostMissionStatus` sur `WorkspaceSummary` et `RunnerStatus`.
- `core/services/atelier.service.ts` — `setHostMissionStatus(hostId, status)`.

### Backend

- `atelier/dto/WorkspaceSummaryResponse` — composante `hostMissionStatus`, alimentée par la même
  lecture des postes de l'utilisateur que `hostName` (aucun N+1 ajouté).
- `runner/dto/RunnerStatusResponse` — composante `hostMissionStatus`.

---

## Plan de test

### Tests unitaires (frontend, Jasmine)

- [ ] `mission-status` — `normalizeMissionStatus` rend `ACTIVE` pour `undefined`, `null`, `''` et
      pour une valeur inconnue ; rend la valeur pour les trois valeurs légitimes.
- [ ] `mission-status` — chaque état a un libellé écrit non vide et une classe `.badge--*` de la
      charte ; **aucune** valeur hexadécimale n'apparaît dans le module.
- [ ] `MissionBadgeComponent` — rend le libellé écrit pour les trois états ; la classe posée est
      celle attendue ; aucun mode n'affiche la couleur sans le texte.

### Tests d'intégration (frontend, TestBed)

- [ ] `PostesComponent` — un poste `PENDING` affiche « En attente » **et** garde son filet gauche à
      `hostTone(nom).solid` (l'identité n'est pas remplacée par l'état).
- [ ] `PostesComponent` — deux postes de même état mais de noms différents gardent **deux** filets
      différents ; deux postes de même nom et d'états différents gardent **le même** filet.
- [ ] `PostesComponent` — un poste `CLOSED` est absent de la liste principale, présent dans le
      repli, et le compteur du repli l'annonce.
- [ ] `PostesComponent` — choisir « Clôturé » appelle le service avec `CLOSED` et déplace la carte
      dans le repli après la réponse.
- [ ] `PostesComponent` — un échec du changement laisse la carte dans la liste principale avec son
      état d'origine et ouvre un snackbar.
- [ ] `PostesComponent` — tous les postes clôturés : le message dédié apparaît, le repli reste
      accessible.
- [ ] `AtelierComponent` — un projet dont le poste est `PENDING` écrit « En attente » ; un projet
      dont le poste est `ACTIVE` n'écrit rien ; un projet non rattaché n'écrit rien.
- [ ] `AtelierTerminalComponent` — l'en-tête écrit l'état quand il n'est pas `ACTIVE`, rien sinon.

### Tests d'intégration (backend, Spring)

- [ ] `GET /api/workspaces` → `hostMissionStatus` renseigné pour un projet rattaché, `null` sinon.
- [ ] `GET /api/workspaces/{id}/runner/status` → `hostMissionStatus` du poste du projet.

### Isolation utilisateur

- [x] **Applicable** — un projet rattaché au poste d'un **autre** utilisateur ne fait remonter ni
      `hostName` ni `hostMissionStatus` : la carte est bâtie depuis `RunnerHostService.list(userId)`,
      filtrée `user_id`, jamais depuis `hostId` seul. Test dédié côté backend.
      Côté écran, `PUT …/mission` ne porte que l'identifiant d'un poste déjà rendu par une vue
      elle-même filtrée : la gateway revérifie la possession (SF-60-01).

---

## Dépendances

### Subfeatures bloquantes

- `SF-60-01` — statut : **à livrer d'abord** (le contrat)
- `SF-49-02` — statut : **done** (l'écran à enrichir)
- `SF-49-03` — statut : **done** (l'identité visuelle à ne pas concurrencer)
- `SF-56-01` — statut : **done** (passe de cohérence ; ne rien annuler)

### Questions ouvertes impactées

- [ ] `OQ-15` — non touchée : cette SF n'ajoute aucune couleur, elle **réemploie** les pastilles
      de statut déjà déclarées au §5.

---

## Notes et décisions

### Arbitrage 1 — L'état prend la palette de **statut** (§5), jamais celle d'identité (§9)

**Décision** : vert / ambre / gris de la table §5, via les classes `.badge--*` existantes.
**Pourquoi** : c'est le piège inscrit au cadrage. Deux systèmes de couleur sur le même objet ne
cohabitent que si chacun garde son registre. L'identité répond à *chez qui suis-je* et vit sur la
palette dérivée du nom ; l'état répond à *où en est-on* et vit sur la palette de statut — celle que
l'utilisateur lit déjà comme « Connecté » ou « Actif ». Aucune couleur nouvelle n'est introduite :
il n'y a donc rien à valider au sens du §8.
**Alternative écartée** : une quatrième famille de tons propre aux missions — trois palettes sur un
écran, et plus rien ne se lit.
**Réversible** : oui.

### Arbitrage 2 — Une pastille à côté du nom, jamais un second filet ni un fond

**Décision** : la couleur d'état n'entre que par la pastille. Le filet gauche reste celui du poste.
**Pourquoi** : le filet est déjà pris (SF-49-03, arbitrage n° 5), et le §8 de la charte interdit le
fond coloré sur les cartes. Un second filet, ou un fond teinté par l'état, ferait exactement ce que
le cadrage interdit : mettre les deux informations en concurrence sur la même surface.
**Réversible** : oui.

### Arbitrage 3 — Le choix se fait depuis `/postes`, malgré la « lecture seule » de SF-49-02

**Décision** : la vue d'ensemble accueille ce geste, et lui seul.
**Pourquoi** : la lecture seule de SF-49-02 visait les **gestes de machine** — renommer, couper,
révoquer — dont le motif écrit est qu'ils transformeraient un écran de consultation en champ de
mines. Déclarer l'état d'une mission n'est ni destructif, ni irréversible : il n'écrit qu'une
colonne, ne coupe rien, et se défait d'un clic. Et c'est le seul écran d'où l'on voit toutes ses
missions à la fois — le seul, donc, où *ranger* a un sens.
**Alternative écartée** : le dialogue de mise en service. Il est enfoui, il parle d'appairage, et
on n'y va pas pour dire qu'une mission est finie.
**Réversible** : oui.

### Arbitrage 4 — Confirmation par la réponse, jamais optimiste

**Décision** : la carte ne change d'état qu'une fois la gateway confirmée.
**Pourquoi** : clôturer **range** la carte hors de la vue. Une mise à jour optimiste ferait
disparaître un poste de l'écran avant de savoir si l'ordre a abouti, puis le ferait réapparaître à
la relecture suivante. Le contrat de la charte est déjà celui-là ailleurs (`setExecutionTarget` :
« c'est la réponse qui fait foi »).
**Contrepartie assumée** : un aller-retour visible sur réseau lent.
**Réversible** : oui.

### Arbitrage 5 — *En cours* est silencieux hors de `/postes`

**Décision** : dans la Forge (liste des projets, en-tête du terminal), la pastille de mission
n'apparaît **que** pour *En attente* et *Clôturé*.
**Pourquoi** : sur `/postes`, on compare des missions — les trois états doivent s'écrire. Dans la
Forge, on travaille : répéter « En cours » sur chaque ligne d'une liste ajoute du bruit sans jamais
changer une décision, alors que « En attente » ou « Clôturé » en changent une. L'absence n'est
jamais ambiguë, puisque l'écran de référence, lui, écrit toujours l'état.
**Alternative écartée** : afficher les trois partout — testé mentalement sur une barre latérale de
huit projets, la colonne devient une colonne d'états identiques.
**Réversible** : oui — un booléen dans le gabarit.

### Arbitrage 6 — Un repli, pas un onglet ni une page

**Décision** : les clôturés vivent dans un `<details>`-like refermé, au bas de la même page.
**Pourquoi** : « se range **sans disparaître** ». Un onglet séparé les ferait disparaître pour de
bon aux yeux de qui ne connaît pas l'onglet ; une page dédiée demanderait une route, un titre et
une navigation pour trois cartes. Un repli refermé montre qu'il y a quelque chose là-dessous, dit
combien, et l'ouvre d'un clic.
**Réversible** : oui.

### Arbitrage 7 — Pas de confirmation à la clôture

**Décision** : clôturer ne demande rien.
**Pourquoi** : le geste ne coupe pas le runner, n'efface aucun historique et se défait en un clic
depuis le repli. Les confirmations de la charte sont réservées au destructif ; en poser une ici
apprendrait à l'utilisateur à cliquer « Oui » sans lire.
**Réversible** : oui.
