# Mini-spec — F-38 / SF-38-06 — Écrans runner (frontend)

## Identifiant
`F-38 / SF-38-06`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`done`

## Date de création
2026-08-30

## Branche Git
`feat/SF-38-06-ecrans-runner`

---

## Objectif

> Donner à l'Atelier les trois écrans qui rendent le runner utilisable sans ligne de commande devinée :
> **choisir la cible d'exécution** (sandbox hébergé ⇄ ma machine), **voir si un runner est connecté**,
> et **appairer une machine** (code à usage unique, téléchargement du jar, commande à coller).

---

## Comportement attendu

### Cas nominal

1. Le détail du projet porte désormais `executionTarget` (`SANDBOX` par défaut, champ **additif** :
   absent d'un backend antérieur ⇒ `SANDBOX`, l'écran se comporte comme avant F-38).
2. Une barre « Où s'exécutent les outils » s'affiche sous la barre d'outils du projet :
   un `mat-button-toggle-group` **Sandbox hébergé** / **Ma machine**. La bascule appelle
   `PUT /api/workspaces/{id}/execution-target` et remplace le détail par la réponse du backend
   (jamais par la valeur optimiste).
3. En cible `RUNNER`, une **pastille d'état** est affichée à côté du sélecteur, alimentée par
   `GET /api/workspaces/{id}/runner/status` :
   - relevée à la bascule, à l'ouverture du projet, puis **toutes les 15 s** tant que la cible est
     `RUNNER` et qu'un projet est ouvert ;
   - libellé « Runner connecté » / « Aucun runner connecté », avec la **dernière activité**
     (`lastSeenAt`) en libellé relatif ;
   - le statut est explicitement présenté comme **différé**, pas temps réel : l'infobulle dit que le
     backend considère un runner connecté tant que son dernier signe de vie date de moins de 90 s
     (`app.runner.heartbeat.stale-after`) — un runner coupé par `Ctrl-C` reste donc affiché connecté
     jusqu'à une minute et demie ;
   - un bouton « Actualiser » force un relevé immédiat.
4. Un bouton « Connecter une machine » ouvre le **dialogue d'appairage**
   (`RunnerPairingDialogComponent`) :
   - **Étape 1 — code** : « Générer un code » appelle `POST /workspaces/{id}/runner/pairing-code` et
     affiche le code en gros, en monospace, avec un **compte à rebours** (`expiresAt`, TTL 5 min) et
     un bouton « Copier ». Le code est **à usage unique** : il n'est détenu qu'en mémoire par le
     dialogue, jamais rechargé, et il disparaît de l'écran dès qu'il expire. Un bouton « Générer un
     nouveau code » le remplace.
   - **Étape 2 — binaire** : « Télécharger le runner (.jar) » appelle `GET /api/runner/download`.
     Un **404 est un état normal** (`app.runner.jar-path` vide = valeur par défaut, le jar n'est pas
     empaqueté dans l'image) : le dialogue affiche alors un encart « binaire non publié sur cette
     gateway » **avec la commande de construction à copier** (`./mvnw -pl runner package`), sans
     snackbar d'erreur technique.
   - **Étape 3 — commande** : la ligne complète à coller sur la machine,
     `java -jar claude-runner.jar --gateway <origine> --workspace /chemin/du/projet --code <CODE>`,
     avec bouton « Copier ». L'origine vient de `window.location.origin` : rien n'est deviné.
5. Cohérence des modes : en cible `RUNNER`, le mode **Terminal** (Managed Agents) est **désactivé**
   avec l'explication — le backend le refuse (409 `execution_target_runner`, décision D2). À
   l'inverse, un projet **Git** en cible `RUNNER` retrouve le mode **Assistant** : le dépôt est cloné
   sur la machine, le garde-fou « Git ⇒ Terminal seulement » ne s'applique plus (SF-38-05).
6. Le fil d'exécution tolère un **type d'action inconnu** (contrat §3) : `AtelierStreamAction.type`
   est une chaîne libre, l'icône et le libellé par défaut ne supposent plus « lecture ».

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|----------------------|------|
| `PUT /execution-target` en échec (réseau, 500) | La cible **ne change pas** à l'écran (aucune valeur optimiste), snackbar « La cible d'exécution n'a pas pu être changée. » | — |
| `PUT /execution-target` → 404 | snackbar « Projet introuvable. » | 404 |
| `GET /runner/status` en échec | Pastille « état inconnu », **aucune** snackbar (un relevé périodique ne doit pas spammer l'écran) ; le relevé suivant corrige | — |
| `POST /pairing-code` en échec | Message d'erreur dans le dialogue, l'étape 1 reste utilisable | — |
| `GET /runner/download` → **404** `runner_jar_unavailable` | **État normal** : encart « binaire non publié sur cette gateway » + commande de build à copier. Aucune snackbar d'erreur | 404 |
| `GET /runner/download` → autre échec | snackbar « Le téléchargement du runner a échoué. » | — |
| Code d'appairage expiré (compte à rebours à zéro) | Le code est masqué, remplacé par « Code expiré » et le bouton de régénération. Un code consommé n'est **jamais** ré-affiché | — |
| Presse-papiers indisponible (`navigator.clipboard` absent / refusé) | snackbar « Copie impossible : sélectionnez le texte manuellement. » | — |

---

## Critères d'acceptation

- [x] `WorkspaceDetail.executionTarget` et `WorkspaceSummary.executionTarget` existent côté modèle,
      **optionnels** : absents ⇒ `SANDBOX`.
- [x] `AtelierService` expose `setExecutionTarget`, `getRunnerStatus`, `createRunnerPairingCode`,
      `downloadRunnerJar` sur les URL exactes du backend (`/api/workspaces/{id}/execution-target`,
      `/api/workspaces/{id}/runner/status`, `/api/workspaces/{id}/runner/pairing-code`,
      `/api/runner/download`).
- [x] La barre de cible d'exécution n'apparaît qu'avec un projet ouvert et bascule réellement la cible.
- [x] Un échec du `PUT` laisse la cible affichée inchangée.
- [x] En cible `RUNNER`, le statut est relevé à l'ouverture puis toutes les 15 s ; le sondage
      **s'arrête** en cible `SANDBOX`, à la fermeture du projet et à la destruction du composant.
- [x] L'infobulle de la pastille énonce explicitement le délai de 90 s (statut non temps réel).
- [x] Un échec du relevé de statut n'ouvre **aucune** snackbar.
- [x] Le dialogue d'appairage affiche le code, son compte à rebours, et le masque à expiration.
- [x] Un 404 sur `GET /runner/download` produit l'encart « non publié » + la commande de build,
      **pas** de snackbar d'erreur.
- [x] La commande à coller contient l'origine réelle, le code, et le chemin du projet saisi.
- [x] En cible `RUNNER`, le mode Terminal est désactivé ; un projet Git en cible `RUNNER` autorise
      le mode Assistant.
- [x] Palette, polices et espacements conformes à `docs/DESIGN_SYSTEM.md` (jetons `--cg-*`,
      `MatSnackBar`/`MatDialog`, jamais `window.alert/confirm/prompt`).
- [x] `npm run build` et `npm test` verts ; `cd backend && ./mvnw test` vert (backend non modifié).

---

## Périmètre

### Hors scope (explicite)

- **Aucune modification backend** : tous les endpoints consommés existent (SF-38-01/02/03/05).
- **Liste et révocation des jetons runner** → SF-38-08 (coupe-circuit et révocation).
- **Outil `bash`, streaming de sortie, événement d'étape `bash`** → SF-38-07. Cette subfeature se
  contente de ne pas casser sur un type d'action inconnu.
- **Validation obligatoire en mode runner** → SF-38-08.
- **Repli long-polling** → SF-38-09.
- Aucune migration Liquibase, aucun changement au module `runner/`.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|-----------------|-------|
| `executionTarget` (vue) | `SANDBOX` | Champ additif : absent de la réponse ⇒ `SANDBOX`, comportement historique |
| `runnerStatus` (écran) | `null` (« état inconnu ») | Aucun relevé tant que la cible n'est pas `RUNNER` |
| code d'appairage | aucun | Généré à la demande seulement, jamais rechargé ni persisté |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|--------------|-----------------------------|---------|---------------|
| `executionTarget` | Oui | — | `SANDBOX` \| `RUNNER` | Non | — |
| chemin du projet (dialogue) | Non | 200 | texte libre ; sert seulement à composer la commande affichée | Non | `trim()`, repli `/chemin/vers/le/projet` |

Notes :
- Le chemin saisi dans le dialogue **ne quitte jamais le navigateur** : il n'est pas envoyé au
  backend, il n'entre que dans la commande affichée.
- Le code d'appairage n'est ni journalisé, ni stocké, ni ré-affichable après fermeture du dialogue.

---

## Technique

### Endpoint(s) consommés (aucun créé)

| Méthode | URL | Auth | Origine |
|---------|-----|------|---------|
| PUT | `/api/workspaces/{id}/execution-target` | JWT | SF-38-05 |
| GET | `/api/workspaces/{id}/runner/status` | JWT | SF-38-02 |
| POST | `/api/workspaces/{id}/runner/pairing-code` | JWT | SF-38-01 |
| GET | `/api/runner/download` | publique (chaîne `/runner/**`) | SF-38-03 |

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `RunnerPairingDialogComponent` (`atelier/runner/`) — écran d'appairage : code + compte à rebours,
  téléchargement du jar (404 = état normal), commande à coller.
- `AtelierComponent` — barre de cible d'exécution, pastille d'état runner, sondage 15 s, cohérence
  des modes Assistant/Terminal.
- `AtelierService` / `atelier.models` — méthodes et DTO runner.

---

## Plan de test

### Tests unitaires

- [x] `AtelierService` — `setExecutionTarget` appelle `PUT /api/workspaces/{id}/execution-target`.
- [x] `AtelierService` — `getRunnerStatus` appelle `GET /api/workspaces/{id}/runner/status`.
- [x] `AtelierService` — `createRunnerPairingCode` appelle `POST .../runner/pairing-code`.
- [x] `AtelierService` — `downloadRunnerJar` appelle `GET /api/runner/download` en `blob`.
- [x] `AtelierComponent` — bascule vers `RUNNER` : appel effectué, détail remplacé par la réponse.
- [x] `AtelierComponent` — échec de bascule : cible inchangée + snackbar.
- [x] `AtelierComponent` — cible `RUNNER` ⇒ statut relevé ; cible `SANDBOX` ⇒ aucun relevé.
- [x] `AtelierComponent` — échec de relevé de statut ⇒ aucune snackbar.
- [x] `AtelierComponent` — cible `RUNNER` ⇒ mode Terminal refusé ; projet Git + `RUNNER` ⇒ mode
      Assistant autorisé.
- [x] `AtelierComponent` — `stepLabel`/`stepIcon` tolèrent un type d'action inconnu.
- [x] `RunnerPairingDialogComponent` — génération : code affiché, compte à rebours calculé.
- [x] `RunnerPairingDialogComponent` — expiration : code masqué, régénération proposée.
- [x] `RunnerPairingDialogComponent` — 404 de téléchargement ⇒ encart « non publié » + commande de
      build, aucune snackbar d'erreur.
- [x] `RunnerPairingDialogComponent` — autre échec de téléchargement ⇒ snackbar d'erreur.
- [x] `RunnerPairingDialogComponent` — la commande affichée contient l'origine, le chemin et le code.

### Tests d'intégration

Sans objet côté backend (aucun endpoint créé ni modifié). La non-régression du backend est
vérifiée par `./mvnw test` inchangé.

### Isolation workspace

- [x] Applicable — garantie **côté backend** : chaque endpoint consommé est gardé par
      `requireOwned(userId, workspaceId)` / `atelierAccess.requireAccess()`. Le frontend ne porte
      aucun `userId` : il envoie le JWT et l'identifiant du projet affiché. Aucun écran n'expose de
      jeton runner (l'API ne le réexpose jamais).

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-01` — done
- `SF-38-02` — done
- `SF-38-03` — done
- `SF-38-05` — done

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Statut délibérément non temps réel.** Aucun canal poussé vers le navigateur n'est ouvert pour le
  statut runner : un sondage de 15 s suffit et évite d'ajouter un SSE de plus. Le décalage est
  **assumé et écrit à l'écran** (infobulle « jusqu'à 90 s »), plutôt que masqué par une pastille
  verte qui mentirait.
- **404 de téléchargement = état de produit, pas panne.** `app.runner.jar-path` est vide par défaut
  en dev comme en prod ; l'écran l'annonce et donne la commande de construction, ce qui rend le
  parcours utilisable sans jar publié.
- **Pas de valeur optimiste sur la cible d'exécution.** La cible pilote où s'exécutent des écritures
  de fichiers : afficher « ma machine » alors que le backend a refusé serait dangereux.
- **Le chemin du projet reste local.** Il ne sert qu'à composer la commande affichée ; l'envoyer au
  backend divulguerait l'arborescence de la machine sans aucun bénéfice.
