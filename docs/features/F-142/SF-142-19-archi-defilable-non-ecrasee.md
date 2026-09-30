# Mini-spec — [F-142 / SF-142-19] L'archi large : défilable, jamais écrasée

---

## Identifiant

`F-142 / SF-142-19`

## Feature parente

`F-142` — Diagrammes rendus par la gateway (diagram-as-code, icônes officielles)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-142-19-archi-defilable`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Guider le modèle à embarquer une figure d'architecture **large** dans un conteneur **défilable/zoomable à taille naturelle** (même traitement que Mermaid) au lieu de `width:100%` qui l'écrase, et faire déclencher l'avertissement de densité aussi sur un **schéma large** (par ratio d'aspect), pas seulement au-delà de la borne absolue de 4000 px.

---

## Contexte du défaut

SF-142-18 a rendu les archi cloud en **SVG** (net à toute échelle). Deux défauts subsistent :

1. **Écrasement.** Le HTML généré par le modèle affiche l'archi en `width:100%` dans une colonne ~848 px. Une archi large (ratio 3,6:1 → 3313×904) est réduite à ~26 %, texte minuscule. Dans la MÊME page, les blocs Mermaid sont, eux, dans un conteneur **scrollable à taille naturelle** (`.mermaid-wrap{overflow-x:auto}`) : deux poids, deux mesures. Le SVG améliore la netteté mais une archi large reste petite si on l'écrase.
2. **Angle mort du garde-fou.** La note « trop dense » ne se déclenche qu'au-delà de `MAX_DIMENSION` (~4000 px absolus) et **rate** les schémas larges illisibles en dessous (ex. 3313×904, ratio 3,66:1 — chaque côté est sous 4000).

---

## Comportement attendu

### Cas nominal

**Point 1 — doctrine d'embarquement (consigne au modèle, pas de post-traitement HTML).**
La doctrine vit dans deux consignes injectées dans la consigne système quand l'outil est donné :
- `DiagramToolCatalog.GUIDE` (paquet `fr.claudegateway.diagrams`) — le « OÙ L'UTILISER » d'une figure rendue ;
- `PageToolCatalog.DESIGN_GUIDE` (paquet `fr.claudegateway.pages`) — la doctrine de conception d'une page.

Les deux disent désormais explicitement et sans ambiguïté : **une archi (schéma large) va dans un conteneur défilable/zoomable à taille naturelle — même traitement que Mermaid (`overflow-x:auto`, taille naturelle) —, jamais écrasée en `width:100%`** ; un « ouvrir en grand » est un plus. Le message d'insertion rendu à l'agent par `DiagramToolExecutor` (le moment exact où il pose le `<img>` dans une page) est aligné sur la même consigne.

**Point 2 — garde-fou densité par ratio (`diagram-renderer/cloud.py`).**
`density_notice(path)` émet en plus, pour un schéma **large** (largeur dominante) sous la borne absolue :
« Schema large : L x H pixels (ratio R:1). Prefere le scinder en plusieurs vues, ou une disposition verticale (TB) : etale ainsi, il devient minuscule dans la page. »
Le critère : `largeur >= WIDE_MIN_WIDTH` (2000) **et** `largeur >= hauteur * WIDE_ASPECT_RATIO` (3,0). La borne absolue existante (`MAX_DIMENSION`, message « tres dense ») est vérifiée d'abord et **inchangée**. La note transite déjà de bout en bout : `cloud.py` imprime `NOTICE=` → `server.js` pose l'en-tête `X-Cg-Diagram-Notice` → `HttpDiagramRenderer` la lit → `DiagramToolExecutor` la relaie à l'agent. Aucun de ces relais n'est à modifier.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| SVG/PNG illisible ou sans dimensions | `density_notice` rend `""` (comportement actuel conservé) |
| Schéma à la fois large ET au-delà de la borne absolue | Message « tres dense » (la borne absolue prime — l'ordre de test la vérifie en premier) |
| Schéma ordinaire (ratio < 3,0 ou largeur < 2000) | `density_notice` rend `""` |
| Schéma haut et étroit (TB, hauteur dominante) | Pas de note « large » (au `width:100%` un schéma étroit ne s'écrase pas ; la note « large » vise la largeur) |

---

## Critères d'acceptation

- [ ] `DiagramToolCatalog.GUIDE` dit explicitement qu'une archi large se pose en page dans un conteneur défilable/zoomable à taille naturelle (comme Mermaid), jamais en `width:100%`, et cite le « ouvrir en grand » comme un plus.
- [ ] `PageToolCatalog.DESIGN_GUIDE` aligne le traitement de la figure d'archi rendue (`<img>`) sur celui de Mermaid : conteneur propre défilant, taille naturelle, jamais `width:100%`.
- [ ] Le message d'insertion de `DiagramToolExecutor` pour une page mentionne le conteneur défilant à taille naturelle.
- [ ] `cloud.py` : nouvelles constantes `WIDE_ASPECT_RATIO` (3,0) et `WIDE_MIN_WIDTH` (2000), documentées, avec le cas réel 3313×904.
- [ ] `density_notice` émet la note « large » pour un schéma large sous la borne absolue (ex. 3768×820, ratio 4,6) — la note nomme les dimensions, le ratio, et propose « TB ».
- [ ] La note « large » est **ASCII pur** (elle finit en en-tête HTTP).
- [ ] Le garde-fou existant n'est pas cassé : un schéma au-delà de `MAX_DIMENSION` rend toujours « tres dense » ; un petit schéma (PETIT, ratio 2,10) rend toujours `""`.
- [ ] Tests renderer Python verts (`test_cloud*.py`) + tests backend `diagrams/*` + `pages/*` verts + compilation verte.

---

## Périmètre

### Hors scope (explicite)

- **Aucun post-traitement HTML** du rendu du modèle (on privilégie la consigne ; l'app n'ouvre pas le HTML pour y injecter un wrapper autour des `<img>`).
- Pas de changement du CSS `.mermaid-wrap` existant ni du moteur de rendu SVG (SF-142-18).
- Pas de nouveau relais de la note : `server.js` / `HttpDiagramRenderer` / en-tête `X-Cg-Diagram-Notice` inchangés.
- Pas de modification des bornes existantes (`MAX_DIMENSION`, `MAX_NODES`…).
- Pas d'écran Angular nouveau : la doctrine est une consigne texte au modèle.

---

## Contraintes de validation

| Champ | Valeur | Règle |
|-------|--------|-------|
| `WIDE_ASPECT_RATIO` | 3,0 | largeur/hauteur au-delà de laquelle un schéma est « large ». Au-dessus de PETIT (2,10) pour ne pas nagger un schéma ordinaire ; en dessous du cas réel (3,66). |
| `WIDE_MIN_WIDTH` | 2000 px | largeur minimale pour que « large » ait un sens : sous cette largeur, la colonne ~848 px n'écrase pas assez pour gêner. |
| Note « large » | ASCII | finit en en-tête HTTP `X-Cg-Diagram-Notice`. |

---

## Technique

### Fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `backend/.../diagrams/DiagramToolCatalog.java` | MODIF | `GUIDE` : doctrine d'embarquement défilable |
| `backend/.../pages/PageToolCatalog.java` | MODIF | `DESIGN_GUIDE` : figure d'archi alignée sur Mermaid |
| `backend/.../diagrams/DiagramToolExecutor.java` | MODIF | message d'insertion page → conteneur défilant |
| `diagram-renderer/cloud.py` | MODIF | constantes + `density_notice` (critère ratio) |
| `diagram-renderer/tests/test_cloud_placement.py` | MODIF | tests du critère « large » |
| `backend/.../diagrams/DiagramToolExecutorTest.java` | MODIF | lock doctrine GUIDE (défilable) |
| `backend/.../pages/PageToolCatalogTest.java` | MODIF | lock DESIGN_GUIDE (défilable) |

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires (Python renderer)

- [ ] `density_notice` — schéma large sous la borne : note « large » nommant dimensions, ratio, « TB ».
- [ ] `density_notice` — la note « large » est ASCII.
- [ ] `density_notice` — schéma ordinaire (PETIT, 2,10) : `""` (non-régression).
- [ ] `density_notice` — schéma au-delà de la borne absolue : toujours « tres dense » (non-régression) et jamais « large » à la place.

### Tests unitaires (backend)

- [ ] `PageToolCatalogTest` — `DESIGN_GUIDE` contient la doctrine défilable pour une archi.
- [ ] `DiagramToolExecutorTest` — le message d'insertion page mentionne le conteneur défilant ; les tests existants (SVG, densité) restent verts.

### Isolation workspace

- [x] Non applicable — modification de consigne texte et de logique de rendu, aucun accès données. La garde d'ouverture des outils (droit d'espace, `user_id`) est inchangée.

---

## Préoccupations transversales

- Auth / Principal : **non touché**.
- Contexte tenant : **non touché** (la garde `isOpenFor(userId, workspace)` des catalogues est inchangée).
- Plans / limites : **non touché**.
- Navigation / routing : **non touché**.

---

## Dépendances

### Subfeatures bloquantes

- `SF-142-18` — done (archi en SVG ; base de ce correctif).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Consigne plutôt que post-traitement** : le modèle génère la page ; un post-traitement HTML (parser le rendu, envelopper chaque `<img>` d'archi) serait lourd, fragile et hors de la doctrine Gateway-First. La consigne, injectée sous la même garde que l'outil, est le levier juste et déjà en place pour Mermaid.
- **Ratio largeur/hauteur** (et non max/min) : la note « large » vise la **largeur** dominante — c'est elle qui écrase au `width:100%`, et « disposition verticale (TB) » n'a de sens que pour convertir une figure large en figure haute. Un schéma haut et étroit ne s'écrase pas et ne doit pas nagger.
- Seuils calibrés sur mesures réelles : PETIT = 2,10 (silencieux), chaîne LR 7 nœuds = 4,60 (signalée), cas PO 3313×904 = 3,66 (signalé).
