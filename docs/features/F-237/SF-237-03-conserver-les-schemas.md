# Mini-spec — F-237 / SF-237-03 — Conserver les schémas

## Identifiant
`F-237 / SF-237-03` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-03-schemas-conserves`
Cadrage : §3 (R3), §10. Dépend de SF-237-01/02 (mergées).

## Objectif
> Un schéma rendu par `render_diagram` n'est plus perdu : sa description et ses fichiers sont gardés côté passerelle,
> pour que Jarvis le montre (type ARCHITECTURE ou DIAGRAM) et que F-238 le reprenne en 3D.

## Comportement attendu
- Après un rendu **réussi** et déposé (moteurs `mermaid`, `cloud`, `drawio`), `DiagramRenderService.record` écrit
  une ligne `diagram_renders` + les fichiers en S3 `diagrams/{u}/{ws}/{id}.svg|png|drawio`. **Au mieux** : un échec
  de conservation est journalisé (sans contenu) et ne change pas le résultat de l'outil.
- L'outil `render_diagram`, sa description et son résultat **ne changent pas** (préfixe de cache intact) ; la
  conservation est branchée par mutateur (`setRenders`), sans second constructeur.
- Index : pièces `diag:<id>` — `ARCHITECTURE` (cloud, drawio) ou `DIAGRAM` (mermaid), catégorie PRODUCTION ;
  description = titre de la spec + groupes de premier niveau (`SPEC`), sinon titre (`TITLE`) ; rattachement par
  `call_id` cité dans la trace d'un message de chantier, sinon chantier courant au rendu (WINDOW).
- `GET /api/jarvis/pieces/diag:<id>/image` : SVG (sinon PNG), CSP `default-src 'none'`, `nosniff`.
- `GET /api/jarvis/pieces/diag:<id>/spec` : la spec JSON (point d'extension F-238) ; 404 pour Mermaid.
- Vignette : le PNG s'il existe (route SF-237-02).
- Suppression du sujet : lignes (cascade) et fichiers (préfixe) effacés.

### Cas d'erreur
- Rendu refusé / service muet / dépôt en échec ⇒ **rien** n'est conservé.
- Schéma d'un autre compte ⇒ 404 ; identifiant mal formé ⇒ 404.

## Critères d'acceptation
- [ ] Un rendu cloud réussi crée la ligne et le SVG ; l'index le montre en ARCHITECTURE avec ses groupes.
- [ ] Un rendu refusé ne crée rien.
- [ ] B ne lit ni l'image ni la spec de A (404).
- [ ] Supprimer le sujet efface ligne et fichiers.

## Contraintes de validation
`spec_json` / `code` ≤ 200 000 caractères ; `title` ≤ 200 ; `call_id` ≤ 120.

## Technique
Migration `237-diagram-renders.xml` (PostgreSQL + H2, additive, rollback = drop). `DiagramRender`,
`DiagramRenderRepository`, `DiagramRenderService` (paquet `diagrams`) ; `DiagramToolExecutor` (mutateur, appel après
dépôt réussi) ; `WorkspaceService.delete` (préfixe) ; `JarvisPieceService` / `JarvisPieceContentService` /
`JarvisPieceContentController`. `ARCHITECTURE_CANONIQUE.md` mis à jour.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Contexte tenant | **Oui** | `DiagramRenderService` (écrit avec l'utilisateur et le sujet déjà vérifiés du tour ; lu par (`id`, `user_id`)), `JarvisPieceContentService.diagramImage/diagramSpec`, `JarvisPieceService.diagrams` ; test A/B |
| Préfixe de cache | **Oui** | `DiagramToolCatalog` **inchangé** (vérifié : aucun diff) |
| Auth / Plans / Navigation | Non | — |

## Plan de test
Intégration `DiagramRenderApiIntegrationTest` : rendu cloud conservé + index + image (CSP) + spec + A/B + effacement
avec le sujet ; rendu refusé non conservé ; Mermaid (DIAGRAM, code, pas de spec). Non-régression
`DiagramToolExecutorTest` (sans conservation branchée).

## Hors périmètre
`graph_json` (F-238, sa propre migration additive) ; rendu 3D ; visionneuse SVG (SF-237-07) ; schémas antérieurs
(non rétro-indexés).
