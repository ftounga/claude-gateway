# Mini-spec — [F-121 / SF-121-24] @-mentions de fichiers dans le composer

## Identifiant

`F-121 / SF-121-24`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`in-progress`

## Date de création

2026-09-24

## Branche Git

`feat/SF-121-24-mentions-fichiers`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Permettre, dans le composer de l'Atelier, de **mentionner un fichier du projet avec `@`** (autocomplétion sur l'arborescence) et d'**injecter, de façon ciblée et bornée, le contenu du fichier mentionné dans le message envoyé au modèle** — à l'image des `@-mentions` de Claude Code.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur tape `@` dans le champ de saisie du terminal.
2. Une liste d'autocomplétion s'ouvre, filtrée au fil de la frappe sur les **chemins relatifs du projet** (la liste plate `WorkspaceDetail.files`, déjà chargée par le parent, signal `tree()`).
3. La sélection (clic ou `Entrée`/flèches) insère la référence lisible `@chemin/du/fichier ` à la place du jeton `@…` en cours, et referme la liste.
4. À l'envoi, pour **chaque `@chemin` encore présent dans le brouillon et correspondant à un fichier connu**, la passerelle lit le fichier via l'endpoint existant `GET /api/workspaces/{id}/file?path=` (lecture **bornée** côté runner, isolation `user_id` + hôte via `requireOwned` + cible du workspace) et **appose le contenu** en fin de message, dans un bloc délimité et clairement étiqueté par chemin.
5. Le message (mention `@chemin` visible + contenu apposé) part comme un tour utilisateur normal.

**Provider-First / Gateway-First** : aucune capacité IA n'est réimplémentée. La mention n'est qu'une **injection ciblée de contexte dans le message utilisateur** ; le modèle « lit » comme d'habitude. **Aucun changement du prompt système** (cache de prompt F-134 préservé : le contenu va dans le message, jamais dans le préfixe stable). Discipline F-119 intacte. **Provider Independence** : aucun code ne dépend d'Anthropic (feature 100 % frontend, aucun appel provider ajouté).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Référence `@xxx` ne correspondant à aucun fichier connu | Ignorée : traitée comme texte libre, aucune lecture, aucun bloc apposé | — |
| Référence effacée par l'utilisateur avant l'envoi | Non réinjectée (le champ fait foi, comme les textes collés SF-146-01) | — |
| Fichier illisible (hors ligne / exclu / refus runner) | Le tour part quand même ; un **marqueur** « contenu non lu » remplace le bloc du fichier concerné, les autres fichiers restent injectés | 4xx/5xx capté par fichier |
| Aucune mention dans le brouillon | Flux d'envoi inchangé (synchrone), aucune requête de lecture | — |
| Fichier tronqué par la borne du runner | Le contenu apposé porte le marqueur `… (contenu tronqué)` déjà produit par le backend | 200 |

---

## Critères d'acceptation

- [ ] Taper `@` ouvre une liste d'autocomplétion filtrée sur les chemins du projet ; la frappe la filtre en direct (sous-chaîne, insensible à la casse).
- [ ] Sélectionner une entrée insère `@chemin ` dans le champ à l'emplacement du jeton et referme la liste.
- [ ] `Échap` ferme la liste sans insérer ; les flèches ↑/↓ naviguent, `Entrée`/`Tab` valide l'entrée surlignée.
- [ ] À l'envoi, le contenu de chaque `@chemin` reconnu est apposé au message dans un bloc étiqueté par chemin ; un `@chemin` inconnu est laissé tel quel sans lecture.
- [ ] Une référence effacée n'est pas réinjectée (parité avec SF-146-01).
- [ ] Un fichier illisible n'empêche pas l'envoi : marqueur explicite + autres fichiers injectés.
- [ ] Sécurité : la lecture passe par `GET /api/workspaces/{id}/file` existant → `requireOwned(userId, id)` (isolation utilisateur) + cible runner du workspace (isolation hôte). Aucun nouvel accès aux données sans filtre `user_id`.
- [ ] `ng build` vert (budget de la feuille de style principale du terminal respecté : styles dans une feuille séparée).
- [ ] Tests unitaires du module pur `file-mentions.ts` verts ; tests du composer verts.

---

## Périmètre

### Hors scope (explicite)

- Épinglage persistant de fichiers entre tours (l'audit P3-b cite « épinglage + autocomplétion » ; ici on livre **autocomplétion + injection ciblée**, l'épinglage persistant est hors scope de cette SF).
- `@`-mentions d'autres entités (symboles, agents, URLs).
- Nouveau endpoint backend, nouvelle table, migration — **aucun** (réutilisation de `GET /api/workspaces/{id}/file`).
- Modification du prompt système, de la boucle d'agent, du runner ou d'un provider.
- Slash commands (P3-a, SF distincte).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| jeton mention | — | — | `@` suivi de caractères non-espace / non-`@` | — | — |
| chemin injecté | — | borné par le runner (lecture) | doit exister dans `tree()` pour être injecté | distinct par message | chemin relatif `/` |

Notes :
- Le nombre de suggestions affichées est plafonné (ex. 8) pour rester lisible.
- Les chemins injectés sont dédupliqués par message.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/workspaces/{id}/file?path=` | Oui | propriétaire du workspace (**existant, réutilisé**) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| — | — | Aucune. Feature 100 % frontend. |

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `frontend/src/app/atelier/terminal/file-mentions.ts` — **module pur** (détection du jeton `@` au curseur, filtrage des chemins, insertion, extraction des chemins mentionnés, construction du bloc de contexte). Sans dépendance Angular → testable unitairement.
- `frontend/src/app/atelier/terminal/atelier-terminal.component.ts/.html` — autocomplétion `@` dans le composer (nouvel `@Input() filePaths`, liste de suggestions, navigation clavier).
- `frontend/src/app/atelier/terminal/atelier-terminal-mentions.component.scss` — **feuille séparée** (budget de build de la feuille principale du terminal, cf. SF-146-01/SF-145-02). Aucune couleur nouvelle : `--cg-surface`, `--cg-divider`, `--cg-text-primary/secondary`, `--cg-accent`.
- `frontend/src/app/atelier/atelier.component.ts/.html` — passe `[filePaths]="tree()"` ; le `send()` résout les mentions (lecture via `getFile`, apposition) avant de démarrer/steer le tour.

---

## Plan de test

### Tests unitaires (module pur `file-mentions.ts`)

- [ ] `activeMention` détecte le jeton `@…` immédiatement à gauche du curseur ; renvoie `null` hors jeton / après espace / après un second `@`.
- [ ] `suggestPaths` filtre par sous-chaîne insensible à la casse et plafonne le nombre.
- [ ] `applyMention` remplace le jeton par `@chemin ` et positionne le curseur après.
- [ ] `mentionedPaths` extrait les chemins **connus** distincts et ignore les `@` inconnus / effacés.
- [ ] `buildContextBlock` produit un bloc étiqueté par chemin ; marqueur pour un fichier illisible.

### Tests d'intégration (composant)

- [ ] Frappe `@` → liste ouverte ; sélection → `@chemin ` inséré et liste fermée.
- [ ] `Échap` ferme sans insérer.
- [ ] `send()` du parent : mentions connues → `getFile` appelé par chemin, contenu apposé ; mention inconnue → pas d'appel.
- [ ] Fichier illisible → envoi non bloqué, marqueur présent.

### Isolation workspace/utilisateur

- [x] Applicable — la lecture réutilise `GET /api/workspaces/{id}/file` déjà couvert par `requireOwned(userId, id)` (isolation utilisateur) + cible runner du workspace (isolation hôte). Aucun nouveau chemin d'accès aux données introduit → pas de régression d'isolation. Vérifié : aucun accès direct filesystem ajouté côté frontend.

---

## Préoccupations transversales — analyse d'impact

| Préoccupation | Concernée ? | Composants impactés / vérification |
|--------------|-------------|-----------------------------------|
| **Auth / Principal** | Non | Aucun nouvel endpoint ; réutilisation d'un endpoint déjà authentifié. |
| **Contexte tenant** | Non (réutilisation) | La lecture reste bornée par `requireOwned(userId, id)` + cible du workspace ; aucun nouveau moyen de résoudre le tenant. |
| **Plans / limites** | Non | Aucun nouvel appel modèle ; la lecture de fichier n'ouvre pas de tour et ne consomme pas de jeton avant l'envoi. Le contenu injecté suit les mêmes plafonds de tour (F-118) que tout message. |
| **Navigation / routing** | Non | Aucune route ajoutée/modifiée. Le composer gagne une liste flottante ; les terminaux (local, hébergé, Teams lecture seule) sont vérifiés : la liste ne s'ouvre qu'en édition, `readOnly` inchangé. |

---

## Dépendances

### Subfeatures bloquantes

- Aucune. Réutilise l'endpoint `GET /api/workspaces/{id}/file` (livré F-31) et le signal `tree()` du parent.

### Questions ouvertes impactées

- [ ] Aucune (`docs/OPEN_QUESTIONS.md`).

---

## Notes et décisions

- **Réutilisation de l'existant** (mémoire « vérifier l'existant avant de cadrer ») : l'endpoint de lecture bornée et isolée existe déjà (`AtelierController.readFile`), la liste plate des chemins existe déjà (`tree()` / `WorkspaceDetail.files`). Aucun backend, aucun runner, aucune migration.
- **Cache de prompt (F-134)** : le contenu mentionné est apposé au **message utilisateur**, jamais au préfixe système stable. Aucun régression de cache.
- **Pattern retenu** : symétrique aux « textes collés repliés » (SF-146-01) — référence lisible et effaçable dans le champ, expansion à l'envoi, « le champ fait foi ». Différence : le contenu vient du runner (async, borné) et non du presse-papier.
- **Mise à jour runner** : NON nécessaire (le protocole `readFile` runner existe et est déjà utilisé par l'explorateur de fichiers).
