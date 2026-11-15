# Mini-spec — [F-238 / SF-238-07] Graphe conservé

## Identifiant
SF-238-07

## Feature parente
F-238 — Architecture en 3D, itération 1 « L'architecture en 3D » (cadrage §5.1, §5.3, §7, §15, §16, §17)

## Statut
done

## Date de création
2026-11-15

## Branche Git
`feat/SF-238-07-graphe-conserve`

---

## Objectif

Le graphe d'architecture (`ArchitectureGraph` v1) que le renderer sait déjà produire (SF-238-05) est rangé avec le
schéma conservé (`diagram_renders`, F-237) et lisible par son seul propriétaire, pour que la scène 3D l'affiche.

## Comportement attendu

### Cas nominal
1. **Rendu `cloud`** (mise en page cadrée `elk`, défaut depuis SF-238-06) : la gateway demande `graph: true` ; le
   renderer répond `{svg (base64), graph}` ; le SVG suit **exactement** le chemin d'avant (dépôt projet, conservation),
   le graphe est rangé dans la **même ligne** `diagram_renders`. Un renderer plus ancien qui renvoie le SVG brut reste
   accepté (graphe absent).
2. **Rendu `drawio`** : le champ `graph` déjà présent dans la réponse (SF-238-05) est lu et rangé de la même façon.
3. **Mermaid** (`DIAGRAM`) : rien n'est écrit (colonnes `null`).
4. Colonnes rangées : `graph_json` (JSON compact), `graph_version` (= `version` du graphe), `node_count`,
   `zone_count`, `edge_count`.
5. **Pas de graphe sans dépôt réussi** : écrit avec la ligne F-237, elle-même écrite après le dépôt (inchangé).
6. **Lecture** `GET /api/jarvis/pieces/diag:<id>/graph` (contrôleur des pièces F-237, `atelierAccess` +
   `currentUser.requireId()` + ligne trouvée par (`id`, `user_id`)) ⇒ `200 application/json` = le graphe.
7. **Calcul unique** : si le graphe manque mais que `spec_json` existe (schémas d'avant SF-238-07, ou rendus Graphviz),
   la passerelle demande **une fois** le graphe au renderer (même appel cadré, gratuit), le range par une mise à jour
   conditionnelle (`graph_json is null`, `user_id`), puis le sert. Les appels suivants lisent la colonne.
8. L'index des pièces (`GET /api/jarvis/pieces`) ajoute à `meta` d'une pièce `ARCHITECTURE` qui a une spec : `graph`
   (le lien de lecture) et, s'ils sont connus, `nodes`/`zones`/`edges` (compteurs, sans charger le JSON côté front).

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Pièce d'un autre utilisateur | Introuvable (indiscernable d'une pièce inexistante) | 404 |
| Id mal formé, préfixe autre que `diag` | Introuvable | 404 |
| Mermaid, ou ni graphe ni spec | Introuvable | 404 |
| Renderer absent/muet ou spec refusée au calcul | Introuvable, **rien n'est rangé** (réessai possible) ; journal sans contenu | 404 |
| Graphe illisible (pas un objet, `version`/`nodes` absents) ou > 64 Kio | Non rangé, journalisé ; le rendu reste livré | — |
| Conservation en échec | Le rendu reste livré (règle F-237 « au mieux », inchangée) | — |

## Critères d'acceptation
- [x] **Isolation A/B** : B reçoit 404 sur `/graph` de la pièce de A (rangée ou à calculer) ; le calcul n'est jamais
      déclenché pour B (test : aucun appel au renderer).
- [x] Pas de graphe sans dépôt réussi : rendu refusé ou dépôt en échec ⇒ aucune ligne, aucun graphe (test).
- [x] Taille : graphes des 3 schémas types + « tout » entre 3,8 et 9,4 Ko (mesuré) ; plafond dur de rangement
      **64 Kio** (voir N1) ; au-delà, non rangé (test).
- [x] Mermaid (`DIAGRAM`) n'écrit rien : colonnes `null`, `/graph` ⇒ 404 (test).
- [x] Une `spec` ancienne (ligne sans graphe) donne, au calcul unique, un graphe **identique** à celui d'un rendu
      neuf de la même spec (même appel renderer, déterministe octet pour octet depuis SF-238-01) ; le second appel ne
      rappelle pas le renderer (test).
- [x] Compteurs `node_count`/`zone_count`/`edge_count` et `graph_version` justes (test).
- [x] Renderer ancien (SVG brut sans JSON) : rendu inchangé, graphe absent (test `HttpDiagramRendererTest`).
- [x] Migration additive `238-diagram-renders-graph.xml`, PostgreSQL + H2, rollback = retrait des colonnes ; contexte
      Spring complet démarre.

## Périmètre — hors scope
- Proxy d'icônes et atlas (SF-238-08) ; scène, visionneuse, navigation (SF-238-09/10).
- Re-rendu d'office des schémas existants (D3) : seul le **graphe** est calculé, à la demande, jamais le SVG.
- Aucune modification du renderer (le contrat `graph: true` existe depuis SF-238-05).
- Aucune consigne système ni outil modifié (préfixe de cache intact).

## Contraintes de validation

| Champ | Règle | Décision |
|---|---|---|
| `graph_json` | objet JSON, `version` entier, `nodes` tableau | sinon non rangé |
| `graph_json` taille | ≤ 64 Kio (UTF-8) | tranché N1 |
| `graph_version` | smallint, = `version` (1) | — |

## Technique
- **Endpoint nouveau** : `GET /api/jarvis/pieces/{pieceId}/graph` (`JarvisPieceContentController`).
- **Endpoint modifié** : `GET /api/jarvis/pieces` — `meta.graph`, `meta.nodes|zones|edges` (ajouts, rien retiré).
- **Table** : `diagram_renders` + 5 colonnes nullables (migration `238-diagram-renders-graph.xml`, additive).
- **Fichiers** : `DiagramRender`, `DiagramRenderRepository` (mise à jour conditionnelle), `DiagramRenderService`
  (`record(..., graph)`, `graph(...)`), `DiagramRenderer` (`Rendered.graph`, `Editable.graph`, `computeGraph`),
  `HttpDiagramRenderer` (`graph: true`, réponse JSON), `DiagramToolExecutor` (passe le graphe),
  `JarvisPieceContentService`/`Controller` (`/graph`), `JarvisPieceService` (meta),
  `docs/ARCHITECTURE_CANONIQUE.md` (étape 6).

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Contexte tenant | **Oui** | `JarvisPieceContentService.diagramGraph` (lecture par `findByIdAndUserId`) ; `DiagramRenderRepository.storeGraph` (`where id and user_id and graph_json is null`) ; écriture dans `DiagramRenderService.record` (userId du tour, inchangé). Test A/B. |
| Auth / Principal | Non | Endpoint sous `/api` authentifié + `atelierAccess.requireAccess()` comme ses voisins. |
| Plans / limites | Non | Rendu gratuit (F-142). |
| Navigation / routing | Non | Aucune route front. |
| Préfixe de cache / consigne | Non | Outil `render_diagram` et `GUIDE` inchangés. |

## Plan de test
- **Unitaires** : `HttpDiagramRendererTest` (cloud cadré ⇒ `graph:true` posté, réponse JSON décodée, SVG brut toléré,
  drawio ⇒ graphe lu, `computeGraph` cloud/drawio) ; `DiagramRenderServiceTest` (compteurs, version, graphe illisible
  ou > 64 Kio non rangé, Mermaid sans graphe) ; `DiagramToolExecutorTest` (graphe transmis pour cloud/drawio).
- **Intégration** (`DiagramRenderGraphIntegrationTest`, Spring complet, H2) : rendu cloud ⇒ graphe rangé et servi ;
  **isolation A/B** (404, renderer jamais appelé pour B) ; ligne ancienne ⇒ calcul unique identique, 2ᵉ lecture sans
  appel ; Mermaid ⇒ 404 ; rendu refusé ⇒ rien ; `meta.graph` dans l'index.
- **Non-régression** : `DiagramRenderApiIntegrationTest`, `JarvisPiece*Test`, démarrage du contexte complet.

## Dépendances
SF-237-03 (mergée, `diagram_renders`), SF-238-05 (mergée, graphe exporté). Aucune avec SF-238-09/12 (frontend).

## Notes et décisions
- **N1 — Plafond 64 Kio au lieu de 40 Ko (écart signalé au cadrage §5.1/§17)** : mesuré le 2026-11-15, un schéma
  aux bornes de F-142 (60 nœuds, 120 liens, 24 zones) fait **53 Ko** de graphe ; le « ≤ 40 Ko » du cadrage était une
  estimation. Les schémas réels mesurés font 3,8 à 9,4 Ko. Ranger jusqu'à 64 Kio garde tout schéma admis par F-142 en
  3D ; au-delà (impossible dans les bornes) le schéma reste en 2D. Format v1 inchangé (consommé par SF-238-09).
- **N2** Le calcul unique passe toujours par le moteur cadré (`elk`), même pour une ligne rendue sous Graphviz : la 3D
  repose sur la mise en page cadrée ; le SVG conservé n'est pas re-rendu (D3).
- **N3** Calcul en échec ⇒ 404 sans rien ranger : le front retombe sur la visionneuse SVG (repli 2D), un appel suivant
  réessaie.
