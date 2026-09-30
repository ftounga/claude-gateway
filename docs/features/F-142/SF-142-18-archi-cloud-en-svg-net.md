# Mini-spec — [F-142 / SF-142-18] L'archi cloud en SVG net (auto-contenu)

## Identifiant

`F-142 / SF-142-18`

## Feature parente

`F-142` — Des diagrammes exacts dans les livrables (diagramme-as-code)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-142-18-cloud-svg`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire produire au moteur **`cloud`** (icônes officielles, lib Python `diagrams` + Graphviz) du **SVG auto-contenu** (icônes embarquées en `data:` URI) au lieu du **PNG** aujourd'hui **forcé**, pour que les archi cloud posées en `width:100%` dans une page restent **nettes à toute échelle**.

---

## Contexte (diagnostic prod)

Les archi AWS des pages générées sont illisibles. Cause confirmée : `cloud.py` ne produit que du **PNG** (résolution fixe) et `DiagramToolExecutor` **force PNG** pour cloud (`Format format = cloud ? Format.PNG : …`). Un PNG large (ex. 3313×904) affiché en `width:100%` dans une colonne ~848 px est réduit à ~26 % → libellés ~6-7 px → flous. L'image est bonne à 100 % ; c'est la **rastérisation + réduction** qui tue. Le SVG, vectoriel, supprime le problème.

---

## Comportement attendu

### Cas nominal

1. L'agent appelle `render_diagram` avec `engine=cloud` + `spec`.
2. La gateway (`HttpDiagramRenderer.renderCloud`) poste `{engine:"cloud", spec}` au service de rendu (`server.js`).
3. `server.js` exécute `cloud.py`, qui **construit le diagramme en SVG** (`outformat="svg"`) puis **post-traite le SVG pour inliner** chaque icône référencée par chemin fichier (`<image xlink:href="/…/icon.png">`) en `data:image/png;base64,…`. Le SVG résultant est **auto-contenu** : plus aucune référence `file://` ni chemin absolu.
4. `server.js` renvoie le SVG avec `Content-Type: image/svg+xml` (les en-têtes `X-Cg-Unknown-Types` / `X-Cg-Diagram-Notice` restent inchangés).
5. La gateway dépose le fichier `.svg` (`image/svg+xml`) dans le projet via `ProjectFileDeposit` (chemin déjà existant) et rend son chemin à l'agent, qui l'insère en `<img src="…">` dans une page.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Description invalide (type/lien/borne) | Raison du moteur rendue, rien de déposé (inchangé) | 422 |
| Service de rendu muet/injoignable | `DiagramRendererUnavailableException` → repli page proposé (inchangé) | — |
| Icône référencée illisible sur le FS du renderer | La référence est **laissée telle quelle** (jamais d'exception) ; le SVG part quand même | 200 |
| SVG (icônes inlinées) au-delà de la borne poids | Refus « image trop lourde » (borne `MAX_IMAGE_BYTES` inchangée) | 413 |
| Schéma trop dense (dimensions au-delà de `MAX_DIMENSION`) | NOTICE de densité conservée (mesurée sur les dimensions du SVG) | 200 |

---

## Critères d'acceptation

- [ ] `cloud.py` produit un fichier **`.svg`** (Graphviz `outformat="svg"`), plus de `.png`.
- [ ] Le SVG est **auto-contenu** : après rendu, **aucune** référence `file://` ni chemin fichier absolu ne subsiste dans les `href`/`xlink:href` ; les icônes officielles sont **inlinées en `data:` URI** (base64).
- [ ] Un schéma tout résolu (icônes officielles) contient au moins une `data:image/png;base64,…` et zéro `<image>` pointant sur un fichier.
- [ ] `server.js` renvoie le SVG cloud avec `Content-Type: image/svg+xml` ; les en-têtes d'avertissement/densité sont préservés.
- [ ] Backend : cloud n'est **plus forcé en PNG** → `Format.SVG` (extension `.svg`, type `image/svg+xml`), aligné avec la description outil (« svg net à tout zoom »).
- [ ] **Compatibilité** : `mermaid` (png/svg) et `drawio` (.drawio + aperçu PNG) inchangés ; les pages existantes (PNG déjà en S3) continuent d'être servies.
- [ ] Le MIME `svg` → `image/svg+xml` est accepté en pièce jointe de page (`PageAttachments`) — **déjà présent**, vérifié.
- [ ] Le garde-fou de densité (`MAX_DIMENSION`) reste fonctionnel (mesuré sur les dimensions du SVG).
- [ ] **Sécurité** : le SVG Graphviz ne contient aucun script ; il est servi/inséré en `<img src>` (jamais inline dans le DOM), la CSP page (`img-src data: blob: 'self'`) l'autorise déjà.

---

## Périmètre

### Hors scope (explicite)

- L'insertion d'un SVG cloud dans une **slide** PPTX (`add_picture` ne gère pas le SVG) : hors sujet ici (le défaut visé est la **page**) ; pour une slide définitive, `engine=drawio` fournit toujours un aperçu PNG.
- Le moteur `mermaid` (déjà PNG **et** SVG) et le moteur `drawio` : non modifiés.
- Toute migration BD, endpoint, écran Angular : aucun.
- La refonte du placement/densité (SF-142-17) : non retouchée, seulement préservée.

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| Format cloud | SVG imposé côté renderer et côté gateway (`Format.SVG`) |
| href inlinés | uniquement les chemins **fichier absolus existants** (ou `file://`) ; jamais `data:`, `http(s):`, `#…` |
| MIME data URI | déduit de l'extension (`.png`→`image/png`, `.jpg/.jpeg`→`image/jpeg`, `.gif`, `.svg`) |
| Bornes | `MAX_IMAGE_BYTES` et `MAX_DIMENSION` inchangées |

---

## Technique

### Endpoints

Aucun nouvel endpoint. Service interne `diagram-renderer` (`POST /render`, `engine=cloud`) — sortie repassée de PNG à SVG.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun (le SVG est servi dans l'iframe bac-à-sable des pages F-109, déjà en place).

### Fichiers impactés

| Fichier | Changement |
|---------|-----------|
| `diagram-renderer/cloud.py` | `outformat="svg"` ; post-traitement d'inlining des icônes en `data:` ; `svg_size` + `density_notice` sur SVG ; `main()` imprime `.svg` |
| `diagram-renderer/server.js` | `renderCloudRequest` : lecture `.svg`, `Content-Type: image/svg+xml` |
| `backend/.../diagrams/DiagramToolExecutor.java` | cloud → `Format.SVG` (plus de PNG forcé) |
| `backend/.../diagrams/HttpDiagramRenderer.java` | `renderCloud` : `call(body, Format.SVG)` |
| `diagram-renderer/tests/test_cloud*.py` | adaptés au SVG + nouveau `test_cloud_svg.py` (auto-contenu) |
| `backend/.../diagrams/*Test.java` | stubs cloud passés en `image/svg+xml`/`.svg` ; test renderCloud→SVG |

---

## Préoccupations transversales

| Préoccupation | Impactée ? | Composants |
|---------------|-----------|------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | le dépôt reste `ProjectFileDeposit(userId, workspace,…)`, inchangé |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

Le rendu de diagrammes ne lit aucune donnée tenant ; l'isolation reste portée par `ProjectFileDeposit` (userId + workspace), non modifié.

---

## Plan de test

### Tests unitaires (renderer — Python)

- [ ] `test_cloud_svg.py` — un schéma tout résolu rend un `.svg` **auto-contenu** : aucun `file://`, aucun `<image>` sur fichier, au moins une `data:image/png;base64,`.
- [ ] `test_cloud_svg.py` — le nombre d'`<image>` = nombre de `data:` (toutes les icônes inlinées).
- [ ] `test_cloud_svg.py` — un nœud inconnu (boîte dessinée, sans icône) rend quand même un SVG valide et auto-contenu.
- [ ] `test_cloud_svg.py` — `svg_size` lit les dimensions ; `density_notice` sur `.svg` dit la densité au-delà de la borne.
- [ ] `test_cloud_svg.py` — `main()` imprime `.svg` en première ligne, et le fichier est auto-contenu.
- [ ] `test_cloud.py` / `test_cloud_groups.py` / `test_cloud_placement.py` — adaptés : mesures pixels via `build(..., outformat="png")` (la mise en page Graphviz est identique quel que soit le format de sortie) ; densité re-testée sur SVG.

### Tests d'intégration (backend — Java)

- [ ] `DiagramToolExecutorTest` — cloud dépose un `.svg` en `image/svg+xml`, chemin rendu à l'agent.
- [ ] `HttpDiagramRendererTest` — `renderCloud` poste `engine=cloud` et renvoie `Format.SVG`.
- [ ] Non-régression : mermaid PNG/SVG et drawio inchangés (tests existants verts).

### Isolation

- [x] Non applicable directement — le rendu ne fait aucun accès BD ; l'isolation du dépôt (`ProjectFileDeposit`, userId+workspace) est inchangée et couverte par ses tests existants.

---

## Dépendances

### Subfeatures bloquantes

- SF-142-07 (moteur cloud) — Done
- SF-142-09/12/15/16/17 (résolution, repli, groupes, vocabulaire, placement) — Done

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non touché).

---

## Notes et décisions

- **Pourquoi inliner les icônes** : la lib `diagrams` embarque les glyphes de service comme PNG sur le FS du renderer ; Graphviz écrit `<image xlink:href="/abs/chemin.png">`. Servi dans le navigateur d'un poste client, ce chemin est inaccessible → icônes cassées. Le post-traitement remplace chaque référence par un `data:` URI base64 : le SVG devient **portable**.
- **Pourquoi PNG dans les tests de pixels** : SF-142-12/15/17 mesurent la **mise en page** (surface, imbrication, longueur d'arêtes), produite par `dot` **indépendamment du format de sortie**. On rend donc un PNG pour lire les pixels via Pillow, tout en gardant SVG comme sortie de production. Le nouveau test `test_cloud_svg.py` couvre spécifiquement le SVG et son auto-suffisance.
- **Densité** : le SVG n'a pas de pixels fixes, mais Graphviz émet `width="…pt" height="…pt"` ; `svg_size` convertit en pixels-équivalents (via le `dpi` de `GRAPH_ATTR`) pour conserver la même sémantique de borne que le PNG.
- **DRAPEAU DÉPLOIEMENT** : l'image `diagram-renderer` doit être **redéployée** pour activer le SVG en prod (comme SF-142-17). La session principale déploie backend + frontend + image `diagram-renderer`.
