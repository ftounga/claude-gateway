# Mini-spec — [F-142 / SF-142-03] Icônes cloud officielles via `diagrams`

## Identifiant

`F-142 / SF-142-03`

## Feature parente

`F-142` — Des diagrammes exacts dans les livrables (diagramme-as-code, pas d'images IA)

## Statut

`ready`

## Date de création

2026-09-22

## Branche Git

`feat/SF-142-03-icones-cloud-officielles-diagrams`

---

## Objectif

> En une phrase : apprendre à l'agent à produire des **diagrammes d'architecture cloud avec les
> ICÔNES OFFICIELLES** (AWS/Azure/GCP/on-prem) via la lib Python `diagrams` (qui appelle `graphviz`),
> rendus en **PNG dans le sandbox**, et insérés soit dans une **slide** (`add_picture`, chaîne
> SF-142-02) soit dans une **page** (image jointe, F-109) — avec **repli Mermaid nommé** là où
> `diagrams`/graphviz sont absents.

---

## Comportement attendu

### Cas nominal

1. L'agent doit dessiner une **architecture cloud** soignée pour un livrable (slide ou page) et veut les
   **vrais glyphes de service** — S3, Lambda, RDS, API Gateway, VNet, GKE… — pas des boîtes génériques.
2. Il **écrit le diagramme en Python** avec la lib `diagrams` (nœuds officiels `diagrams.aws.*`,
   `diagrams.azure.*`, `diagrams.gcp.*`, `diagrams.onprem.*`), le **rend en PNG dans le sandbox**
   (`diagrams` appelle `graphviz`/`dot`), puis :
   - **slide** : insère le PNG via `python-pptx` `add_picture` (chaîne SF-142-02, aperçu in-app inchangé) ;
   - **page** : joint le PNG à la page (`attachments`) et le référence par `<img src="…">` (F-109).
3. **FACTUEL (F-119)** : il ne dessine que l'architecture **établie** (lue dans le projet/sujet) ; ce qui
   est supposé se marque « (supposé) ». Il n'invente aucun service.
4. Tout tourne **dans le sandbox/terminal, jamais sur le serveur/cluster** — comme SF-142-01/02.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `diagrams` (pip) ou `graphviz` (binaire `dot`) absent **et** installation bloquée (poste banque verrouillé, proxy) | **Échec nommé** (jamais de traceback nu) + **repli explicite** : retomber sur **Mermaid `architecture-beta`** (SF-142-01 en page / SF-142-02 en slide) — zéro installation, icônes génériques — en le **disant clairement** à l'utilisateur (« icônes cloud officielles indisponibles ici, je bascule sur Mermaid »). Jamais de fausse image. |
| Code `diagrams` invalide (le rendu `dot` échoue) | Échec nommé du rendu de CE diagramme ; ne pas insérer d'image cassée ; livrer le reste, signaler le diagramme en échec. |
| `python-pptx` absente (cible slide) | Cas déjà couvert par le skill `pptx` (import gardé, échec nommé) — inchangé. |

---

## Critères d'acceptation

- [ ] CA1 — Le skill `pptx` (déposé par `GovernancePackageSeeder` sous `.claude/skills/pptx.md`)
      contient une **recette « icônes cloud officielles »** : lib `diagrams` (nœuds `diagrams.aws.*` /
      `diagrams.azure.*` / `diagrams.gcp.*` / `diagrams.onprem.*`) → PNG via `graphviz` → insertion
      (`add_picture` en slide / image jointe en page).
- [ ] CA2 — La recette **nomme l'échec** si `diagrams`/`graphviz` sont absents et l'installation bloquée
      (pas de traceback nu) et documente le **repli Mermaid `architecture-beta`** (zéro-install), en le
      disant à l'utilisateur.
- [ ] CA3 — La recette porte la **doctrine du choix d'outil** : `diagrams` = **haut de gamme** (icônes
      officielles, livrable soigné, exige graphviz+python dans le sandbox) ; Mermaid = **rapide,
      zéro-install, portable** (repli). Elle rappelle **factuel** (F-119) et **sandbox uniquement,
      jamais le cluster**.
- [ ] CA4 — `PresentationToolCatalog.GUIDE` mentionne que les **icônes cloud officielles** passent par
      `diagrams` (sandbox) → PNG → `add_picture`, avec Mermaid en repli (renvoi skill `pptx`).
- [ ] CA5 — `PageToolCatalog.DESIGN_GUIDE` explique que, pour des **icônes cloud officielles** dans une
      page, l'agent rend un PNG avec `diagrams` **dans le sandbox** et le **joint** à la page ;
      `architecture-beta` (client-side) reste le repli zéro-install à icônes génériques.
- [ ] CA6 — `GovernancePackageSeederTest`, `PresentationToolCatalogTest`, `PageToolCatalogTest` restent
      **verts** et couvrent le nouveau contenu (diagrams, graphviz, nœuds officiels, repli Mermaid,
      sandbox, factuel).
- [ ] CA7 — Isolation `user_id` (+ `host_id`) **inchangée** : héritée des packages `pages` et
      `presentations` ; aucun accès aux données ajouté ni modifié.

---

## Périmètre

### Hors scope (explicite)

- **Images illustratives IA** (OpenAI) → **SF-142-04** (NON faite ici).
- Tout **rendu de diagramme côté serveur/cluster** : écarté — le rendu vit dans le sandbox.
- Toute **modification backend fonctionnelle** (endpoint, service, entité, migration) : non nécessaire
  (voir Notes D1) — pages (F-109) et slides (F-129) lisent/bornent/rangent/servent déjà les images.
- Un **éditeur** de diagrammes interactif (on produit et on insère ; l'édition passe par le code Python).
- Le chargement des **jeux d'icônes iconify de Mermaid** dans les pages (exige un `fetch` réseau, bloqué
  par `connect-src 'none'`) — inchangé depuis SF-142-01.

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| Moteur d'icônes officielles | lib Python `diagrams` (mingrammer) + binaire `graphviz` (`dot`), dans le **sandbox**. |
| Nœuds officiels | `diagrams.aws.*`, `diagrams.azure.*`, `diagrams.gcp.*`, `diagrams.onprem.*`. |
| Sortie | **PNG** — inséré en slide (`add_picture`, SF-142-02) ou joint à une page (F-109). |
| Repli | **Mermaid `architecture-beta`** (SF-142-01/02), zéro-install, icônes génériques, **nommé**. |
| Échec du moteur | **Nommé**, jamais de traceback nu ; repli Mermaid annoncé à l'utilisateur. |
| Où tourne le rendu | **Sandbox/terminal uniquement** — aucun composant serveur/cluster (Gateway-First). |
| Doctrine du diagramme | Diagram-as-code, **factuel** (F-119) ; pas d'images IA pour un schéma. |
| Choix d'outil | `diagrams` = haut de gamme (icônes officielles) ; Mermaid = rapide/portable (repli). |

---

## Technique

### Endpoint(s)

**Aucun** nouvel endpoint ni modification. Le PNG rendu est soit une image *dans* le `.pptx` (F-129,
`presentation_publish`), soit une **pièce jointe** d'une page (F-109, `page_publish`) — chaînes existantes.

### Tables impactées

**Aucune**. Aucune migration Liquibase.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

**Aucun.** Le PNG s'affiche via les visionneuses existantes : une slide-image de plus (visionneuse F-129,
SF-129-03) ou une image de page servie dans l'`iframe` bac-à-sable (F-109). `ng build` inchangé.

### Nouveaux fichiers / fichiers modifiés (backend)

- **Modifié** `backend/src/main/resources/governance/savoir-durable/pptx.md` — ajout d'une section
  « Icônes cloud officielles (`diagrams`) » : recette Python `diagrams` → PNG (`graphviz`) →
  `add_picture` (slide) / image jointe (page) ; échec nommé + repli Mermaid ; doctrine du choix d'outil ;
  factuel (F-119) ; sandbox uniquement.
- **Modifié** `PresentationToolCatalog.GUIDE` — courte mention « icônes cloud officielles via `diagrams`
  (sandbox) », repli Mermaid, renvoi skill `pptx`.
- **Modifié** `PageToolCatalog.DESIGN_GUIDE` — chemin « icônes cloud officielles = PNG `diagrams` du
  sandbox joint à la page », `architecture-beta` repli zéro-install.
- **Modifié (tests)** `GovernancePackageSeederTest` — assertions sur la recette `diagrams`.
- **Modifié (tests)** `PresentationToolCatalogTest` — assertion sur la mention `diagrams` du guide.
- **Modifié (tests)** `PageToolCatalogTest` — assertion sur la mention `diagrams` du guide.

---

## Plan de test

### Tests unitaires

- [ ] `GovernancePackageSeederTest` — `pptx.md` contient la recette `diagrams` : `diagrams`, `graphviz`,
      un nœud officiel (`diagrams.aws`), le repli Mermaid `architecture-beta`, la règle sandbox et
      factuel.
- [ ] `PresentationToolCatalogTest` — `GUIDE` mentionne `diagrams` + icônes officielles + repli Mermaid.
- [ ] `PageToolCatalogTest` — `DESIGN_GUIDE` mentionne `diagrams` + icônes officielles (sandbox, jointe) ;
      les assertions Mermaid existantes (`class="mermaid"`, `architecture-beta`, `ÉTABLI`, « n'ajoute
      PAS toi-même la bibliothèque mermaid ») restent vertes.

### Tests d'intégration / service

- [ ] Aucun endpoint touché → pas de nouveau test d'intégration ; les suites `pages`, `presentations`
      et `governance` existantes restent **vertes** (non-régression).
- [ ] Démonstration de la chaîne « `diagrams`-code → PNG → page/slide » : **spécifiée** dans le skill
      (recette exécutable dans le sandbox) et **testable** via le contenu seedé ; le PNG résultant est
      servi par les visionneuses **existantes** (pas de code nouveau à tester côté app).

### Isolation utilisateur

- [x] Applicable — **inchangée**. Les gardes `page_publish` / `presentation_publish` restent
      `SpaceEntitlementService` (droit d'espace du terminal possédé, `user_id`/`host_id`) ; aucun accès
      aux données ajouté. Couverte par les tests d'isolation existants des packages `pages`/`presentations`.

---

## Dépendances

### Subfeatures bloquantes

- `F-109` (SF-109-01→06) — **Terminée** : `page_publish`, pièces jointes image, routes de lecture.
- `F-129` (SF-129-01→03) — **Terminée** : skill `pptx`, `presentation_publish`, visionneuse.
- `SF-142-01` / `SF-142-02` — **Terminées** : Mermaid en page / en slide (le repli).

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Préoccupations transversales — analyse d'impact

| Préoccupation | Impactée ? | Composants |
|--------------|-----------|------------|
| Auth / Principal | Non | Aucun changement d'auth ; aucun endpoint touché. |
| Contexte tenant (`user_id`/`host_id`) | Non | Gardes `page_publish` / `presentation_publish` inchangées (`SpaceEntitlementService`) ; aucun accès données ajouté. |
| Plans / limites | Non | Aucun quota/gate touché (`PageLimits` / `PresentationLimits` inchangés). |
| Navigation / routing | Non | Aucune route ajoutée/modifiée (front comme back). |

---

## Notes et décisions

- **D1 — Zéro backend fonctionnel.** Gateway-First : pages (F-109) et slides (F-129) lisent/bornent/
  rangent/servent déjà les images ; un diagramme `diagrams` est un PNG *dans* un `.pptx` ou une pièce
  jointe de page. **Aucun endpoint, service, entité ni migration.** Le cœur de SF-142-03 est
  **doctrinal** (skill `pptx` + guides des deux outils).
- **D2 — Rendu : sandbox uniquement.** `diagrams` appelle `graphviz`/`dot` **sur le terminal**, comme
  `mmdc` (SF-142-02) et LibreOffice (SF-129-03). **Aucun composant cluster** ; `legalcase-shared` est à
  capacité. Si un rendu serveur tentait : **NON** (drapeau).
- **D3 — Dépendances & repli.** `diagrams` (pip) + `graphviz` (apt) : **sandbox → installable → OK** ;
  **poste banque verrouillé → souvent bloqué → repli Mermaid `architecture-beta`** (SF-142-01/02,
  zéro-install, icônes génériques), **nommé** à l'utilisateur. Jamais de traceback nu ni de fausse image.
- **D4 — Doctrine du choix d'outil.** `diagrams` = **haut de gamme** (icônes cloud officielles, livrable
  soigné) là où graphviz est dispo ; **Mermaid** = rapide, zéro-install, portable (le repli / le cœur
  banque). On **étend** les recettes SF-142-01/02, on n'invente pas de parallèle.
- **D5 — Factuel (F-119).** L'agent diagramme l'architecture **réelle** (lue dans le projet/sujet), il
  n'invente aucun service ; le supposé est marqué « (supposé) ».
- **D6 — Runner : NON.** Aucun outil runner, aucune mise à jour du runner (rendu dans le sandbox).
