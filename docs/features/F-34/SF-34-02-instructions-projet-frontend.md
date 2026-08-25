# Mini-spec — [F-34 / SF-34-02] Indication à l'écran des instructions du projet

## Identifiant

`F-34 / SF-34-02`

## Feature parente

`F-34` — Instructions par projet (CLAUDE.md du workspace)

## Statut

`done` — livrée le 2026-08-25 (PR #153)

## Date de création

2026-08-25

## Branche Git

`feat/SF-34-02-instructions-projet-ui`

---

## Objectif

Montrer à l'utilisateur que son projet porte des instructions prises en compte par l'agent, et lui
donner un accès direct au fichier pour les relire ou les modifier.

---

## Comportement attendu

### Cas nominal

1. À l'ouverture d'un projet, le frontend lit `instructionsPath` du détail du workspace
   (**contrat importé de `SF-34-01-instructions-projet-backend.md`**).
2. Si `instructionsPath` n'est pas `null` :
   - la **barre d'outils** de l'Atelier (mode Assistant) et l'**en-tête du terminal** (mode Terminal)
     affichent une pastille « Instructions : `<chemin>` » ;
   - l'infobulle précise que les instructions sont ajoutées au prompt de l'agent **à l'ouverture de
     la session suivante** (D5 du cadrage) ;
   - un clic ouvre l'explorateur de fichiers **sur ce fichier** (`/atelier/{id}/fichiers?path=…`),
     où il est éditable pour un projet d'archive et en lecture seule pour un projet Git (règle
     existante SF-31-03, inchangée).
3. Si `instructionsPath` est `null`, **rien n'est affiché** : aucun écran ne change pour les projets
   qui n'en portent pas.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Détail du workspace en échec | Comportement actuel (message d'erreur, aucune pastille) | 4xx/5xx |
| `instructionsPath` absent de la réponse (backend antérieur) | Traité comme `null` — champ optionnel côté modèle | 200 |
| Fichier d'instructions illisible à l'ouverture dans l'explorateur | Message d'erreur existant de l'explorateur, pastille conservée | 4xx |
| `?path=` désignant un fichier hors arborescence | Aucun fichier ouvert, l'explorateur s'affiche normalement | 200 |

---

## Critères d'acceptation

- [ ] Projet avec `instructionsPath` non nul → pastille visible dans la barre d'outils de l'Atelier.
- [ ] Projet avec `instructionsPath` non nul → pastille visible dans l'en-tête du terminal.
- [ ] Projet sans instructions → aucune pastille, aucun autre changement d'écran.
- [ ] Le clic sur la pastille navigue vers l'explorateur avec `?path=<instructionsPath>`.
- [ ] L'explorateur ouvert avec `?path=` charge le contenu du fichier correspondant s'il existe dans
      l'arborescence, sans erreur s'il n'y est pas.
- [ ] L'infobulle mentionne la prise en compte à la **session suivante**.
- [ ] Couleurs, typographies et composants issus du `DESIGN_SYSTEM.md` (pastille = classe `badge`
      existante, icône Material) — aucune valeur codée en dur hors charte.
- [ ] `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope (explicite)

- Édition assistée / création du fichier d'instructions depuis la pastille.
- Aperçu du contenu dans une infobulle ou un panneau dédié.
- Toute logique de résolution du fichier côté frontend : le chemin vient du backend.
- Signalement d'une troncature du contenu (le backend borne, l'écran ne le commente pas).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `instructionsPath` (modèle) | `null` | Champ optionnel : absent ⇒ aucune pastille |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `instructionsPath` | Non | — | chemin relatif renvoyé par le backend | — | aucune (affiché tel quel) |

Notes :
- Le frontend n'interprète pas le chemin : il l'affiche et le repasse en paramètre de navigation.

---

## Technique

### Contrat consommé (importé de SF-34-01)

`GET /api/workspaces/{id}` → `WorkspaceDetail` avec `instructionsPath: string | null`.
Aucun nouvel appel réseau : le détail est déjà chargé à la sélection du projet.

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/workspaces/{id}` | Oui | Gold |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| — | — | Aucune |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable**

### Composants Angular

- `AtelierComponent` — expose `instructionsPath()` (dérivé de `activeDetail()`) et
  `openInstructions()` (navigation vers l'explorateur avec `?path=`).
- `AtelierTerminalComponent` — nouvelle entrée `instructionsPath` + sortie `openInstructions`.
- `AtelierFilesComponent` — ouvre le fichier désigné par le paramètre de requête `path` au chargement.
- `atelier.models.ts` — champ `instructionsPath` sur `WorkspaceDetail`.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierComponent` — détail avec `instructionsPath` → `instructionsPath()` non nul.
- [ ] `AtelierComponent` — détail sans instructions → `instructionsPath()` nul.
- [ ] `AtelierComponent` — `openInstructions()` navigue vers `/atelier/{id}/fichiers` avec
      `queryParams.path`.
- [ ] `AtelierTerminalComponent` — pastille rendue si l'entrée est fournie, absente sinon.
- [ ] `AtelierTerminalComponent` — clic sur la pastille émet `openInstructions`.
- [ ] `AtelierFilesComponent` — `?path=CLAUDE.md` présent dans l'arborescence → fichier chargé.
- [ ] `AtelierFilesComponent` — `?path=` inconnu → aucun chargement, aucune erreur.

### Tests d'intégration

- [ ] Non applicable (frontend) — les services sont **mockés**, aucun backend requis.

### Isolation workspace

- [x] Non applicable côté frontend — l'isolation `user_id` est garantie côté backend via le JWT ;
      aucun identifiant d'utilisateur n'est manipulé ici.

---

## Dépendances

### Subfeatures bloquantes

- `SF-34-01` — contrat API figé (le champ doit exister côté backend avant le merge frontend).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Arbitrage (réversible)** : la pastille **ouvre l'explorateur de fichiers** sur le fichier plutôt
  que d'ouvrir un éditeur dédié. Alternative écartée : un panneau d'édition propre aux instructions —
  il dupliquerait l'éditeur existant, et ne fonctionnerait pas sur un projet Git en lecture seule.
- **Arbitrage (réversible)** : la pastille est affichée dans les **deux** modes (Assistant et
  Terminal). Alternative écartée : la limiter au Terminal — les instructions valent pour la session
  d'exécution, mais l'utilisateur les édite depuis l'écran Atelier.
