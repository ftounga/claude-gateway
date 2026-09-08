# Mini-spec — F-45 / SF-45-05 — Le parcours guidé de mise en service

## Identifiant

`F-45 / SF-45-05`

## Feature parente

`F-45` — Mise en service guidée du runner sur poste d'entreprise

## Statut

`ready`

## Date de création

2026-09-08

## Branches Git

- `feat/SF-45-05-etat-runner-interpreteur` — backend (contrat API), poussée et mergée **en premier**
- `feat/SF-45-05-parcours-guide` — écran

---

## Objectif

> Que le dialogue « Connecter une machine » se lise comme un **parcours** — une étape ouverte à la
> fois, les précédentes repliées en une ligne — et que la connexion réussie s'y affiche comme une
> **conclusion** : le projet, la machine, l'interpréteur élu.

---

## Déclencheur

F-45 a triplé le contenu de ce dialogue : 4 étapes numérotées, un arbre de lecture à trois branches
et la fiche DSI cohabitent dans **une seule colonne de 376 lignes de gabarit**, toutes déployées en
permanence. Sur un écran de portable, l'utilisateur scrolle sans jamais voir où il en est — et les
trois branches de l'étape 1 sont, par construction, **fausses aux deux tiers** pour celui qui les
lit : il n'a obtenu qu'un seul des trois résultats.

Deux constats en découlent :

1. C'est un **parcours**, pas un formulaire. Une étape à la fois ; ce qui est fait se replie.
2. La première étape est un **diagnostic qui peut échouer** — et son échec ne renvoie pas à l'étape
   suivante mais **ailleurs** (le relais local, la DSI).

Et à l'autre bout : la connexion réussie, seule information que l'utilisateur attend depuis le début,
est aujourd'hui **une ligne perdue en bas** de l'étape 4.

---

## Comportement attendu

### Cas nominal — le parcours

Le dialogue garde ses **quatre** étapes, dans le même ordre (réseau → code → runner → lancement).
Une seule est **ouverte** à la fois ; les autres sont repliées sur leur en-tête, qui porte :

| Élément | Rôle |
|---|---|
| Le numéro, ou une **coche** quand l'étape est faite | Où j'en suis |
| Le titre | Ce que fait l'étape |
| Une **ligne de résumé** quand il y a un fait à rapporter | Ce que j'ai obtenu |

Tout en-tête est **cliquable**, à tout moment : replier n'est pas verrouiller. L'étape ouverte au
chargement est la **première non faite**.

Résumés repliés :

| Étape | État | Résumé |
|---|---|---|
| 1 — Accès réseau | non répondu | *(aucun)* |
| 1 | `200` | « Ce terminal atteint la passerelle. » |
| 1 | `407` | « Un proxy exige une authentification — voir la fiche pour votre DSI. » |
| 1 | aucun code | « Aucune réponse : le proxy n'est pas déclaré dans ce terminal. » |
| 2 — Code | code utilisable | « Code généré, valable encore m:ss. » |
| 2 | code expiré | « Code expiré. » |
| 3 — Runner | téléchargé | « Runner récupéré. » |
| 4 — Lancement | machine non vue | « En attente de la machine… » |
| 4 | machine vue | « Machine connectée. » |

### Cas nominal — l'étape 1, diagnostic à trois issues

L'étape 1 affiche la commande (inchangée, SF-45-01) puis demande **ce que le terminal a affiché**.
Trois boutons : `200`, `407`, « aucun code ». La branche correspondante — et **elle seule** — est
alors dépliée, avec ses gestes :

| Réponse déclarée | Ce qui s'affiche | Suite |
|---|---|---|
| `200` | Confirmation d'une ligne | L'étape 2 **s'ouvre** |
| `407` | Le remède du relais local + **la fiche DSI** | L'étape 1 **reste ouverte** |
| aucun code | Les commandes de découverte et de déclaration du système + **la fiche DSI** | L'étape 1 **reste ouverte** |

La déclaration est **révisable** : un bouton « Revenir au diagnostic » ramène aux trois choix.

### Cas nominal — la conclusion

Dès que le relevé d'état voit la machine (`GET /api/workspaces/{id}/runner/status`, SF-45-02), le
dialogue **replie toutes ses étapes** et affiche, en tête, une conclusion :

```
Machine connectée
Projet               : <nom du projet>
Interpréteur         : bash | PowerShell | cmd.exe   (si le runner l'a déclaré)
Dernier signe de vie : hh:mm
```

Suivie d'une phrase disant ce qui se passe ensuite : le runner reste connecté tant qu'il tourne, et
ce dialogue peut être fermé. Le bouton « Fermer » devient « Terminer ».

### Contrat API — l'interpréteur

`GET /api/workspaces/{id}/runner/status` gagne un champ **additif** :

```json
{ "connected": true, "lastSeenAt": "2026-09-08T10:12:00Z", "shell": "posix" }
```

`shell` vaut `posix`, `powershell` ou `cmd` — la valeur **normalisée** de `workspaces.runner_shell`
(SF-38-27), ou `null` si aucun runner ne l'a déclarée. Aucune table, aucune colonne, aucune
migration : la donnée existe depuis la migration **063**, elle n'était simplement pas exposée.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le runner est antérieur à SF-38-27 (aucun interpréteur déclaré) | `shell` vaut `null` ; la conclusion **omet la ligne**, elle n'écrit jamais « inconnu » |
| `runner_shell` porte une valeur hors liste blanche (base d'une version antérieure, écriture manuelle) | Le backend la **normalise à `null`** ; l'écran n'a rien à afficher |
| L'utilisateur n'a pas encore répondu au diagnostic | L'étape 1 reste ouverte, aucune branche dépliée, les étapes 2 à 4 restent atteignables d'un clic |
| Le diagnostic échoue (`407` / aucun code) | Rien n'est **verrouillé** : les étapes suivantes restent ouvrables — le remède peut venir d'un autre terminal ou de la DSI dans l'heure |
| La machine se connecte pendant que l'utilisateur lit l'étape 2 | Les étapes se replient et la conclusion s'affiche ; aucune étape n'est fermée irréversiblement — tout reste rouvrable d'un clic |
| Le relevé d'état échoue | Inchangé (SF-45-02) : silencieux, l'étape 4 garde « En attente de la machine… » |
| Le projet appartient à un autre utilisateur | Inchangé : `403`, le relevé s'arrête (SF-45-02) |

---

## Critères d'acceptation

- [ ] Une seule étape est dépliée à la fois ; les trois autres sont repliées sur leur en-tête.
- [ ] Un clic sur l'en-tête d'une étape repliée l'ouvre et replie celle qui l'était.
- [ ] Une étape faite porte une **coche** à la place de son numéro et sa ligne de résumé.
- [ ] L'étape ouverte à l'ouverture du dialogue est la **première non faite** (l'étape 1 au premier
      lancement).
- [ ] L'étape 1 n'affiche **aucune** branche tant que l'utilisateur n'a pas déclaré son résultat.
- [ ] Déclarer `200` marque l'étape 1 comme faite **et ouvre l'étape 2**.
- [ ] Déclarer `407` déplie le remède du relais local **et** la fiche DSI, sans ouvrir l'étape 2.
- [ ] Déclarer « aucun code » déplie les commandes de découverte/déclaration **et** la fiche DSI.
- [ ] « Revenir au diagnostic » ramène l'étape 1 à ses trois choix.
- [ ] Aucun échec de diagnostic ne rend une étape suivante inaccessible.
- [ ] Générer un code marque l'étape 2 comme faite et ouvre l'étape 3.
- [ ] Un téléchargement réussi marque l'étape 3 comme faite et ouvre l'étape 4.
- [ ] « J'ai déjà le runner » marque l'étape 3 comme faite sans télécharger.
- [ ] Dès que la machine est vue, **toutes** les étapes se replient et la conclusion s'affiche.
- [ ] La conclusion nomme le **projet** et l'heure du dernier signe de vie.
- [ ] La conclusion nomme l'**interpréteur élu** quand le backend le renvoie, et **omet la ligne**
      quand il vaut `null`.
- [ ] La conclusion affiche un libellé **écrit en dur** par valeur (`bash`, `PowerShell`, `cmd.exe`) :
      la valeur reçue n'est jamais rendue telle quelle.
- [ ] `GET /api/workspaces/{id}/runner/status` renvoie `shell` pour le propriétaire du projet.
- [ ] Une valeur de `runner_shell` hors liste blanche est renvoyée comme `null`.
- [ ] L'isolation `user_id` du relevé d'état est **inchangée** et reste couverte par son test.

---

## Périmètre

### Hors scope (explicite)

- **Fermer le dialogue automatiquement** à la connexion : parti pris de SF-45-02, conservé.
- **Verrouiller une étape** tant que la précédente n'est pas faite : le seul obstacle non résoluble
  seul (le `407`) demande une action **externe** ; verrouiller ferait du dialogue un piège.
- **Vérifier le réseau depuis la page** : impossible et trompeur (D2 de SF-45-01, inchangé).
- **Mémoriser l'avancement** entre deux ouvertures du dialogue : le code d'appairage expire en 5 min,
  un parcours repris une heure plus tard n'aurait rien de vrai à restaurer.
- **Exposer autre chose que le genre d'interpréteur** (version, chemin, système du poste) : ce sont
  des données de poste qui n'ont pas à remonter pour afficher une conclusion.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `openStep` | `'network'` | Première étape non faite ; au premier lancement, l'étape 1 |
| `networkVerdict` | `'unknown'` | Aucune branche dépliée tant que l'utilisateur n'a rien déclaré |
| `runnerObtained` | `false` | Passe à vrai sur un téléchargement réussi ou sur « J'ai déjà le runner » |
| `shell` (API) | `null` | Aucun runner n'a encore déclaré son interpréteur |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| `shell` (réponse API) | Non | 16 (colonne existante) | `posix` \| `powershell` \| `cmd` \| `null` | Non | `RunnerShell.fromDeclared` — hors liste blanche ⇒ `null` |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum | Statut |
|---------|-----|------|-------------|--------|
| GET | `/api/workspaces/{id}/runner/status` | Oui | propriétaire du projet | **Existant** (SF-38-02) — **champ additif** `shell` |

Aucun endpoint créé, aucun supprimé, aucune signature modifiée. Le filtre `user_id`
(`RunnerStatusService.status(userId, workspaceId)` → `WorkspaceService.requireOwned`) est **celui
qui existe** et n'est pas touché : le champ ajouté est lu **sur le workspace déjà vérifié**.

### Tables impactées

Aucune. `workspaces.runner_shell` existe depuis la migration **063** (SF-38-27).

### Migration Liquibase

- [x] **Non applicable.**

### Composants backend

- `RunnerStatusService` — `RunnerStatus` gagne `String shell`, lu sur le workspace **retourné par
  `requireOwned`** et normalisé par `RunnerShell.fromDeclared`.
- `RunnerStatusResponse` — champ `shell`.

### Composants Angular

- `RunnerPairingDialogComponent` — signaux `step`, `networkVerdict`, `runnerObtained`,
  `runnerShell` ; `stepDone()`, `stepSummary()`, `openStep()`, `declareNetworkResult()`,
  `shellLabel()`.
- `runner-pairing-dialog.component.html` — en-têtes cliquables, corps conditionnels, conclusion.
- `runner-pairing-dialog.component.scss` — jetons `--cg-*` uniquement.
- `atelier.models.ts` — `RunnerStatus.shell`.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Aucun nouveau type d'appel ni de principal |
| **Contexte tenant** | **Oui, en lecture** | Un champ de plus est lu **sur le workspace déjà vérifié** par `requireOwned(userId, id)`. Composants vérifiés : `RunnerStatusService.status` (filtre inchangé), `RunnerManagementController#status` (inchangé), `AtelierEngineService` (consomme le record `RunnerStatus` — recompilé, comportement inchangé), `AtelierService.getRunnerStatus` (front, inchangé). Aucun nouveau chemin d'accès aux données. |
| Plans / limites | Non | Aucun quota |
| Navigation / routing | Non | Aucune route ; le parcours vit **dans** le dialogue |
| **Minuteurs du composant** | **Oui** | Les deux `setInterval` existants (compte à rebours, relevé d'état) sont conservés tels quels et toujours arrêtés dans `ngOnDestroy` ; cette SF n'en ajoute aucun |
| **Contrat API partagé** | **Oui** | `RunnerStatus` (front) et `RunnerStatusResponse` (back) : champ **additif et optionnel**, un front antérieur l'ignore, un backend antérieur le laisse `undefined` — l'écran omet alors la ligne |

---

## Plan de test

### Tests unitaires — backend

- [ ] Un workspace dont `runner_shell` vaut `posix` produit un statut portant `shell = "posix"`.
- [ ] Un workspace sans `runner_shell` produit `shell = null`.
- [ ] Une valeur hors liste blanche produit `shell = null`.
- [ ] `RunnerStatusResponse.from` recopie le champ.

### Tests d'intégration — backend

- [ ] `GET /api/workspaces/{id}/runner/status` expose `shell` pour le propriétaire.
- [ ] L'accès par un autre utilisateur reste **refusé** (test d'isolation existant, non modifié).

### Tests unitaires — frontend

- [ ] Au chargement, seule l'étape 1 est dépliée.
- [ ] Un clic sur l'en-tête de l'étape 3 la déplie et replie l'étape 1.
- [ ] Aucune branche du diagnostic n'est rendue avant déclaration.
- [ ] `200` marque l'étape 1 faite et ouvre l'étape 2.
- [ ] `407` rend le relais local et la fiche DSI, et n'ouvre pas l'étape 2.
- [ ] « aucun code » rend les commandes proxy et la fiche DSI.
- [ ] « Revenir au diagnostic » remet l'étape 1 à `unknown`.
- [ ] Un code généré marque l'étape 2 faite et ouvre l'étape 3.
- [ ] Un téléchargement réussi marque l'étape 3 faite et ouvre l'étape 4.
- [ ] « J'ai déjà le runner » marque l'étape 3 faite sans appeler le service de téléchargement.
- [ ] La machine vue replie toutes les étapes et rend la conclusion.
- [ ] La conclusion nomme le projet et l'heure du dernier signe de vie.
- [ ] La conclusion rend « bash » pour `posix`, « PowerShell » pour `powershell`, « cmd.exe » pour `cmd`.
- [ ] Un `shell` absent ou inconnu n'ajoute **aucune** ligne d'interpréteur.

### Isolation utilisateur

- [x] **Applicable, couverte en amont et non modifiée** : le champ ajouté est lu sur le workspace
      retourné par `requireOwned(userId, id)`. Aucun filtre n'est ajouté, retiré ni contourné ; le
      test d'isolation de SF-38-02 reste vert sans modification.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-02` (relevé d'état + isolation) — **done**
- `SF-38-27` (élection et déclaration de l'interpréteur, migration 063) — **done**
- `SF-45-01` / `SF-45-02` / `SF-45-03` (contenu des étapes) — **done**, réorganisées ici, non
  réécrites

---

## Décisions (arbitrages tracés)

| # | Décision | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| **D1** | Un **accordéon** dans le même dialogue, pas un assistant multi-écrans | Une commande déjà lue se re-copie souvent ; un écran quitté qu'on ne peut pas rouvrir d'un clic coûte plus qu'il ne fait gagner | Un assistant « Suivant / Précédent » masquant les autres étapes | Oui |
| **D2** | L'étape 1 se conclut par une **déclaration de l'utilisateur** | Le navigateur ne peut pas lire le terminal (limite assumée de F-45) ; les trois branches affichées ensemble sont fausses aux deux tiers pour celui qui les lit | Garder l'arbre complet déployé | Oui |
| **D3** | **Rien n'est verrouillé** par un diagnostic en échec | Le `407` se lève côté DSI, pas côté poste : bloquer le parcours ferait du dialogue un piège pendant que le remède arrive | Interdire les étapes 2 à 4 tant que le réseau n'est pas vert | Oui |
| **D4** | La **fiche DSI** n'apparaît que sous les deux branches en échec | Elle n'a d'objet que s'il y a quelque chose à demander ; toujours visible, elle occupait un tiers de l'étape 1 pour tous ceux qui n'en avaient pas besoin | La laisser affichée en permanence | Oui |
| **D5** | L'avancement se déduit de **faits** (code généré, téléchargement réussi, machine vue), sauf l'étape 1 qui n'en a aucun | Une case « c'est fait » cochée à la main ment dès qu'on la coche par avance | Quatre cases à cocher | Oui |
| **D6** | La **conclusion remplace** le parcours, elle ne s'y ajoute pas | C'est la seule information attendue depuis le début ; la laisser en bas de l'étape 4 la rend invisible sur un portable | Une pastille verte dans l'étape 4 (l'état actuel) | Oui |
| **D7** | L'**interpréteur** vient d'un champ **additif** du relevé d'état existant | La donnée existe depuis la migration 063 et n'était pas exposée ; un endpoint dédié serait une route de plus pour trois valeurs possibles | `GET /runner/shell` ; ou renoncer à l'interpréteur dans la conclusion | Oui |
| **D8** | Le libellé de l'interpréteur est **écrit en dur** côté écran, par valeur | La valeur vient à l'origine d'un client (trame `ready`) ; elle est déjà filtrée par liste blanche côté serveur, et ne doit en plus **jamais** être rendue telle quelle | Afficher la chaîne reçue | Oui |
