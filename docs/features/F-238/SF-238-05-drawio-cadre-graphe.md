# Mini-spec — [F-238 / SF-238-05] `drawio` cadré + export du graphe

## Identifiant
SF-238-05

## Feature parente
F-238 — Architecture en 3D, itération 0 « Le schéma 2D cadré » (cadrage §4.2 dernier alinéa, §5.1, §5.2)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-238-05-drawio-graphe`

---

## Objectif

Le `.drawio` réouvrable reprend exactement la mise en page cadrée (cadres imbriqués aux conventions AWS, liaisons
orthogonales), et le renderer sait renvoyer le graphe d'architecture `ArchitectureGraph` v1 — sans encore le stocker.

## Comportement attendu

### Cas nominal
1. `engine=drawio` avec `layout: "elk"` (posté par la gateway quand `APP_DIAGRAM_CLOUD_LAYOUT=elk`) :
   - même préparation que `cloud` (`cloud.py --prepare`) ; un nœud **sans type** reste une **boîte nommée** (contrat
     SF-142-13 : ni signalé, ni pointillé rouge) ;
   - `drawio-cadre.js` écrit le XML depuis le graphe : zones = **conteneurs imbriqués** (géométrie relative au
     parent, style §4.3), nœuds = icône officielle + libellé (`shape=label`, image `data:` non recolorée) ou boîte
     nommée, liaisons `orthogonalEdgeStyle` passant par **les coudes calculés**, extrémités aux **ports calculés**
     (`exitX/exitY/entryX/entryY`, `exitPerimeter=0`), bidirectionnel = deux pointes ;
   - aperçu PNG = **le SVG cadré** rastérisé ; réponse `{drawio, png, previewError, width, height, graph}` (champ
     `graph` ajouté, ignoré par la gateway pour l'instant).
2. `engine=cloud` + `layout: "elk"` + `graph: true` ⇒ réponse JSON `{svg (base64), graph}` (sans `graph: true`, le
   SVG comme avant).
3. Schéma JSON du graphe : `diagram-renderer/schemas/architecture-graph-v1.schema.json`.
4. Image : `cadre-composition.js`, `cadre-routage.js`, `drawio-cadre.js` ajoutés au `COPY` (oubli de SF-03/04 corrigé
   avant tout déploiement) ; le build charge les modules cadrés (échec de build si l'un manque) ; un test vérifie que
   chaque module requis par `server.js` est copié.
5. En-tête `X-Cg-Diagram-Notice` : guillemets français rendus en `"` (ASCII lisible).

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Description refusée (imbrication, borne, lien pendant) | Raison rendue | 422 |
| Aperçu en échec | `.drawio` livré, `previewError` dit pourquoi (inchangé) | 200 |
| `.drawio` > 8 Mio | Refus dit | 413 |

## Critères d'acceptation
- [x] `.drawio` : mêmes positions que le SVG (zones, nœuds, coudes, ports) — test de structure du XML.
- [x] Graphe JSON valide contre son schéma (4 schémas).
- [x] Polylignes du graphe = polylignes du SVG.
- [x] Conventions AWS identiques dans le `.drawio` ; libellés échappés ; déterminisme.
- [x] Sans `layout`, le `.drawio` historique est inchangé (`test_drawio.js` vert).

## Périmètre — hors scope
Stockage du graphe (`diagram_renders.graph_json`, SF-238-07) ; consigne de l'agent et bascule (SF-238-06).

## Contraintes de validation
Inchangées.

## Technique
- Endpoints publics : aucun. Contrat interne : `layout`/`graph` sur `POST /render`.
- Tables / migration : aucune.
- Fichiers : `drawio-cadre.js` (nouveau), `schemas/architecture-graph-v1.schema.json` (nouveau), `server.js`,
  `cadre.js` (`neutralizeUntyped`, boîte neutre), `Dockerfile`, `tests/test_cadre_drawio.js` ;
  `backend/.../HttpDiagramRenderer.java` (`layout=elk` pour drawio) + test.

## Plan de test
- node:test : schéma ×4, positions/imbrication/coudes/ports ×4, polylignes SVG = graphe, conventions, nœud sans type,
  échappement, déterminisme, garde du `COPY`.
- Backend : `HttpDiagramRendererTest` (`layout=elk` posté pour drawio, champ `graph` toléré) ; suite complète.
- Fumée locale : `POST /render` drawio+elk ⇒ 200, aperçu PNG, graphe (10 nœuds sur EKS).
- Isolation utilisateur : non applicable.

## Dépendances
SF-238-01 → 04 (mergées).

## Notes et décisions
- **A1** Nœuds draw.io = icône officielle embarquée (`data:`) + libellé : le fichier réouvert ressemble au SVG ;
  l'ancien moteur dessinait des boîtes sans icône. Taille : ≤ 30 Kio par icône distincte, bornée à 8 Mio.
- **A2** Un nœud sans type reste une boîte nommée (contrat drawio d'origine), y compris dans l'aperçu.
- **A3** La gateway ne lit pas encore `graph` (stockage en SF-238-07, après SF-237-03).
- **A4** Le SVG cadré référence ses icônes par `xlink:href` (comme graphviz) plutôt que `href` seul : compatibilité
  avec les lecteurs SVG anciens (convertisseurs, librsvg) ; les navigateurs lisent les deux.
