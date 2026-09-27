# Mini-spec — F-142 / SF-142-13 — Le schéma est réouvrable dans draw.io

## Identifiant
`F-142 / SF-142-13` — feature parente `F-142`

## Objectif
Qu'un schéma d'architecture produit par la gateway arrive **en double** — une **image** à poser dans
le livrable **et un fichier `.drawio` réouvrable et modifiable** dans draw.io / diagrams.net — sans
que le poste du client installe quoi que ce soit.

## Le défaut
Depuis SF-142-06/07, un schéma sort en **PNG**. Un PNG est un cul-de-sac : le client qui veut déplacer
une boîte, renommer un composant ou ajouter une flèche doit **tout refaire**. Or, en avant-vente, le
schéma d'architecture est précisément ce que l'interlocuteur veut **s'approprier**. Mermaid reste du
code (le client ne l'édite pas), et `diagrams` produit un PNG définitif.

`.drawio` est le format d'échange de fait pour ces schémas : il s'ouvre dans le navigateur
(diagrams.net), dans VS Code (extension), dans Confluence. C'est un **fichier XML** — donc quelque
chose qu'on peut **écrire** sans exécuter draw.io.

## Ce qu'on écarte, et pourquoi
**Faire tourner draw.io (Electron / `drawio-desktop`) dans le service.** Ce serait une seconde
installation lourde dans l'image (Electron + X virtuel) pour produire un XML que l'on sait écrire
directement. Le format `mxGraphModel` est documenté et stable.

**Laisser draw.io fabriquer l'aperçu.** Même raison. L'aperçu est rendu à partir de la **même
géométrie** que le `.drawio`, par le chromium **déjà présent** dans l'image (aperçu des slides,
SF-129-06). Les deux artefacts sont donc **cohérents par construction** : ce qu'on voit dans l'image
est exactement ce qui s'ouvre dans draw.io.

## Contrat technique (gelé avant développement)
| Élément | Décision, figée |
|---|---|
| `diagram-renderer/drawio.js` | module **pur** : une description entre, `{xml, svg, width, height}` sort. **Aucun** appel système, **aucune** dépendance, **aucune** écriture disque — donc testable sans conteneur. |
| Moteur | `engine` de l'outil `render_diagram` **+= `drawio`** (aux côtés de `mermaid` et `cloud`). |
| Description | **la même** que `engine=cloud` : `{title, direction, groups[], nodes[], edges[]}` — l'agent n'apprend pas un second vocabulaire. `type` devient facultatif (pas d'icône officielle ici). |
| Artefacts | **deux fichiers déposés** : `<nom>.drawio` (source éditable) **et** `<nom>.png` (aperçu). |
| Transport | `POST /render` avec `engine=drawio` répond **JSON** `{drawio, png, width, height}` (base64) — un corps binaire ne peut pas porter deux fichiers. |
| Rasterisation | SVG → PNG par le **chromium déjà installé**, jamais par une nouvelle dépendance. |

## Comportement attendu
1. L'agent appelle `render_diagram` avec `engine=drawio` et une **description** (jamais du code).
2. La gateway rend **deux fichiers** et les dépose dans le projet, par le chemin **déjà existant**
   (`ProjectFileDeposit`), puis rend **les deux chemins** à l'agent.
3. L'agent pose le **PNG** dans le livrable (slide, page, document) et **annonce le `.drawio`** comme
   la version modifiable.
4. La disposition est **déterministe** : la même description rend deux fois le même fichier.
5. Le service indisponible est un **échec nommé** avec repli — comme pour les deux autres moteurs.

| Cas d'erreur | Comportement |
|---|---|
| Description sans nœud | refus nommé, avant tout appel : « au moins un nœud ». |
| Lien vers un nœud inexistant | refus nommé qui **cite l'identifiant** ; rien n'est déposé. |
| Description hors bornes (nœuds, liens, taille) | refus borné et dit, avant de fabriquer quoi que ce soit. |
| Aperçu PNG en échec | le `.drawio` **part quand même** ; l'absence d'aperçu est **dite**. L'inverse serait absurde : c'est l'éditable qui a de la valeur. |
| Service injoignable / trop lent | échec nommé + repli (rendu en page) ; aucun fichier fabriqué. |
| Dépôt du `.drawio` impossible | dit en échec, comme pour les images. |

## Critères d'acceptation
- [ ] `engine=drawio` produit **un `.drawio` réouvrable** et **un `.png`**, tous deux déposés, les deux
      chemins rendus à l'agent.
- [ ] Le `.drawio` est un **`mxfile` valide** : `mxfile > diagram > mxGraphModel > root`, `mxCell` `0`
      et `1`, un sommet par nœud, une arête par lien, géométrie absolue.
- [ ] Les **groupes** sont des cadres qui **contiennent visuellement** leurs membres, dans le `.drawio`
      comme dans l'aperçu.
- [ ] Le rendu est **déterministe** : deux appels identiques → deux fichiers **identiques**.
- [ ] Tout ce qui vient du modèle est **échappé** en XML (`&`, `<`, `>`, `"`) — un libellé ne peut pas
      casser le fichier ni y injecter une balise.
- [ ] Un **lien pendant** est refusé **en nommant l'identifiant** ; aucun dépôt.
- [ ] Les **bornes** (nœuds, liens, longueur de libellé, taille des artefacts) sont appliquées et testées.
- [ ] L'**aperçu en échec** ne fait **pas** échouer le `.drawio` — et c'est **dit**.
- [ ] **ISOLATION** : le dépôt passe par le `Workspace` **du tour** ; le nom de fichier venu du modèle
      est nettoyé ; aucun chemin absolu accepté.
- [ ] Les deux moteurs existants (`mermaid`, `cloud`) sont **inchangés** — tests de non-régression verts.

## Hors scope
Une **visionneuse `.drawio` dans l'application** (le fichier se télécharge et s'ouvre dans
diagrams.net) · le **choix automatique du moteur** (SF-142-11) · l'import d'un `.drawio` existant ·
les **icônes officielles** dans le `.drawio` (le moteur `cloud` reste la voie pour cela).

## Technique
| Élément | Changement |
|---|---|
| `diagram-renderer/drawio.js` *(nouveau)* | module pur : mise en page en couches, `mxfile` XML **et** SVG, à partir de la même géométrie |
| `diagram-renderer/server.js` | branche `engine=drawio` sur `/render` : réponse **JSON** `{drawio, png, width, height}` ; rasterisation SVG→PNG par chromium |
| `diagram-renderer/tests/test_drawio.js` *(nouveau)* | `node --test` — le module est pur, il se teste sans conteneur |
| `DiagramRenderer` (interface) + `HttpDiagramRenderer` | `renderEditable(spec)` → `Editable(drawio, png)` ; **Provider Independence** préservée |
| `DiagramRenderer.Format` | `+ DRAWIO("application/vnd.jgraph.mxfile", ".drawio")` |
| `DiagramToolCatalog` | `engine` **+= `drawio`** ; guide : quand choisir l'éditable, et qu'il faut l'**annoncer** |
| `DiagramToolExecutor` | branche `drawio` : deux dépôts, deux chemins rendus, aperçu manquant **dit** |

**Ce qu'on ne construit pas** : le dépôt dans le projet (`ProjectFileDeposit`, SF-142-06) · le
transport HTTP borné (`HttpDiagramRenderer`) · la rasterisation par chromium (`shoot`, SF-129-06).
On les emprunte.

**Bornes** : **60** nœuds · **120** liens · **12** groupes · libellé **120** caractères · description
≤ **20 000** caractères (borne existante) · artefacts ≤ **8 Mio** (borne existante).

**Aucune migration, aucune table, aucun endpoint REST, aucun composant Angular** — l'outil vit dans la
boucle d'agent, et les deux fichiers sont déposés dans le projet.

## Plan de test
### `diagram-renderer/tests/test_drawio.js` (`node --test`)
- [ ] Description nominale → `mxfile` bien formé, un sommet par nœud, une arête par lien.
- [ ] **Déterminisme** : deux appels → chaînes **strictement égales**.
- [ ] **Échappement** : un libellé `A & <b>B</b> "c"` sort échappé, le XML reste analysable.
- [ ] **Groupes** : le cadre du groupe **englobe** la géométrie de ses membres.
- [ ] **Lien pendant** → exception qui **nomme** l'identifiant manquant.
- [ ] **Bornes** : 0 nœud, > 60 nœuds, > 120 liens, libellé trop long → refus nommés.
- [ ] `direction` `LR` et `TB` → deux géométries distinctes, toutes deux cohérentes.
- [ ] Le SVG produit porte **les mêmes coordonnées** que le `.drawio` (cohérence des deux artefacts).

### Gateway — `HttpDiagramRendererTest`
- [ ] `renderEditable` poste `engine=drawio`, lit le JSON, décode les deux base64.
- [ ] Réponse sans `drawio` → indisponibilité **dite** (jamais un fichier vide déposé).
- [ ] `422` du service → `DiagramRejectedException` portant la **raison du moteur**.
- [ ] Artefact au-dessus de la borne → refus **avant** tout dépôt.
- [ ] Service muet → `DiagramRendererUnavailableException`.

### Gateway — `DiagramToolExecutorTest`
- [ ] `engine=drawio` → **deux** dépôts (`.drawio` puis `.png`), **deux** chemins dans la réponse.
- [ ] `engine=drawio` **sans** `spec` → refus nommé, aucun appel au service.
- [ ] Aperçu absent → le `.drawio` est déposé, l'absence d'aperçu est **dite**, ce n'est **pas** une erreur.
- [ ] Dépôt du `.drawio` en échec → erreur nommée.
- [ ] **ISOLATION** : `../../etc/passwd` → `passwd.drawio` + `passwd.png`.
- [ ] Non-régression : `mermaid` et `cloud` se comportent exactement comme avant.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint exposé ; le service reste `ClusterIP` |
| **Contexte tenant** | **oui** | `DiagramToolExecutor` → `ProjectFileDeposit` → `Workspace` **du tour** (déjà isolé `user_id`, SF-142-06) ; **deux** dépôts au lieu d'un, **même** chemin d'isolation, **même** nettoyage de nom. Aucun autre composant ne résout le tenant ici. |
| **Plans / limites** | **oui** | Aucun jeton consommé (aucun appel fournisseur), comme les deux autres moteurs. Le plafond `app.diagrams.max-per-turn` est **inchangé** et compte un appel, pas un fichier. |
| Navigation / routing | non | aucun écran, aucune route |
