# CADRAGE F-238 — Architecture en 3D (précédée du schéma 2D cadré)

**Statut :** cadrage du 2026-11-02, à la demande du PO. Famille « Jarvis » : F-236 (moteur 3D), F-237 (Jarvis montre
les pièces), **F-238**. **Cadrage seul, aucun code applicatif.** Toutes les décisions techniques ont un défaut tranché
(§18). Le PO n'est sollicité que pour **deux questions de produit** (§19) et le **visa des planches** de fin
d'itération.

**Trois itérations livrables seules**, 14 SF de 2 jours au plus (≈ 25 j) :

| Itération | Nom | SF | Jours | Livre seule ? |
|---|---|---|---|---|
| **0** | **Le schéma 2D cadré** (ajout du PO, 2026-11-02) | 01 → 06 | 11 | **Oui** : tous les livrables (pages, slides, documents) en profitent, sans 3D. |
| 1 | L'architecture en 3D | 07 → 11 | 9 | Oui (vue 3D touchable, sans narration ; après SF-237-03/04/08). |
| 2 | Jarvis la raconte | 12 → 14 | 5 | Oui. |

**F-238 s'appuie sur F-237** (`docs/features/F-237/CADRAGE-F-237-jarvis-montre-les-pieces.md`, mergé le
2026-11-02, #1492), en particulier ses **points d'extension réservés** (F-237 §10). Les points d'appui sont listés en
§7 et marqués **« fournis par F-237 »** ; F-238 ne les redéfinit pas.

---

## 1. Demande du PO

### 1.1 La 3D (2026-11-02)

« Est-ce possible d'afficher en graphique 3D des schémas d'architecture AWS ? » Proposition validée : passer du schéma
2D posé sur un panneau (F-237) à une **vraie scène 3D** :

| # | Consigne | Où |
|---|---|---|
| P1 | Les regroupements deviennent des **plateaux translucides empilés** : compte, région, VPC, zones de disponibilité, sous-réseaux publics et privés. | §9 |
| P2 | Les services (EC2, RDS, Lambda, S3, ALB, EKS…) sont des **icônes AWS officielles flottantes** posées sur ces plateaux. | §6.3, §9 |
| P3 | Les flux sont des **filaments lumineux** parcourus de particules **dans le sens du trafic**. | §9.4 |
| P4 | On peut **tourner autour**, **zoomer sur un service**, **replier ou déplier un VPC**. | §12 |
| P5 | **Jarvis allume ce dont il parle** et commente à la voix (« le trafic entre par l'ALB, Monsieur, puis… »). | §11 |
| P6 | L'IA l'affiche **d'elle-même** quand c'est pertinent (« comment c'est déployé chez ce client ? ») et **navigue à la voix** (« zoome sur la base », « montre le flux sortant ») comme au toucher. | §12 |

### 1.2 L'itération 0 « Le schéma 2D cadré » (ajout du PO, même jour, passe AVANT la 3D)

Constat du PO sur les schémas F-142 : « les icônes sont bien, les flèches aussi (améliorables), mais les lignes qui
relient les composants **ne sont pas homogènes** : elles se tordent, elles sont obliques. On n'a pas l'impression d'un
schéma d'architecture AWS. Dans les schémas AWS, les lignes sont **verticales ou horizontales**. Je veux de la
**précision dans les zones** : délimitation des zones, des Availability Zones, des régions. Un schéma vraiment
**CADRÉ**. »

| # | Exigence | Où |
|---|---|---|
| C1 | **Liaisons orthogonales uniquement** (segments horizontaux et verticaux, coudes droits), avec une **garde automatique** qui échoue sur un segment oblique ou courbe. | §4.2, §4.6 |
| C2 | **Zones conformes aux conventions AWS** : AWS Cloud / compte, Région, VPC, Availability Zone (pointillés), sous-réseau public (vert) / privé (bleu), Auto Scaling group, security group… Icône de groupe, couleur, style de bordure, libellé en haut à gauche ; imbrication stricte, marges régulières. | §4.3 |
| C3 | **Alignement sur grille**, espacements constants, **AZ côte à côte et symétriques**, flux principal lisible, flèches homogènes, étiquettes qui ne chevauchent rien. | §4.4 |
| C4 | **Guidage de l'agent** par une consigne **fixe** ou un module d'aide, jamais de contenu volatil dans la consigne système. | §4.5 |
| C5 | Que faire des **schémas existants**. | §4.7 |
| C6 | Une **planche avant/après** pour le PO. | §4.8 — `captures/iteration-0/index.html` |

**La 3D reprend la même mise en page cadrée** : plateaux = zones, liaisons orthogonales en 3D (§9).

### 1.3 Règles qui bornent ce projet (non négociables)

| Règle | Conséquence |
|---|---|
| **Gateway-First / Provider-First** | Le rendu, la mise en page et la scène 3D sont du **dessin** ; aucune « intelligence » n'est réimplémentée. Le modèle décrit l'architecture (comme aujourd'hui), la gateway la dessine. |
| **Zéro coût IA pour le rendu** | Mise en page, 3D, narration et navigation au toucher : **aucun appel modèle**. Seuls les tours que le PO lance déjà coûtent (inchangé). |
| **Isolation `user_id`** | Le graphe conservé (§5.3, dans `diagram_renders` de F-237) est lu **uniquement** par son propriétaire ; test A/B à chaque lecture nouvelle. Les icônes sont publiques (aucune donnée client). |
| **Préfixe stable** | La consigne fixe `DiagramToolCatalog.GUIDE` est modifiée **une fois** (itération 0) puis byte-stable ; l'enum `action` de l'outil `afficher` (F-237) est étendue **une fois** (§7). L'**état de navigation** de la scène va **dans le message** (bloc `## Écran` de F-237), jamais dans la consigne système. |
| **Justesse avant coût** | Un schéma faux est pire qu'un schéma absent (règle F-142 reconduite) : imbrication refusée si incohérente, jamais d'icône approchante, jamais un composant inventé pour la symétrie. |
| **Le 2D reste** | Le SVG F-142 (désormais cadré) est le **repli** de la 3D (WebGL absent, mouvement réduit, mode sobre, perf). |

### 1.4 Décisions antérieures amendées (signalées explicitement)

| Décision | Aujourd'hui | Amendement F-238 | Pourquoi |
|---|---|---|---|
| **SF-142-17** « régler Graphviz, ne pas placer à sa place » ; `splines: "spline"` | Liaisons courbes ou obliques (mesuré : 12/15, 21/23, 4/9 segments non orthogonaux, §4.1) | Le moteur `cloud` passe à une **mise en page ELK + composition des zones** (§4.2) ; Graphviz reste **une version** en retour arrière (`APP_DIAGRAM_CLOUD_LAYOUT=graphviz`). | Exigence C1 ; l'essai montre que Graphviz `ortho` ne tient pas avec des clusters. |
| **SF-142-15** `MAX_GROUP_DEPTH = 4` | AWS Cloud › Région › VPC › AZ › sous-réseau (**5 niveaux**) est **refusé** — vérifié dans l'essai : « Groupes imbriqués trop profondément depuis « puba » » | **6** niveaux (avec Auto Scaling group dans un sous-réseau). | Exigence C2 : la topologie AWS canonique doit passer. |
| **F-142 §8** « le diagramme suit les conventions cloud, pas la charte » | Clusters pastel génériques de `diagrams` | Conventions **officielles des groupes AWS** en 2D (§4.3) ; la charte Jarvis ne s'applique **qu'en 3D** (§10). | C2. |
| **F-236 D6** grammaire des couleurs de Jarvis | Vert = réussi, cyan = attente | **Reconduite en 3D** : les zones ne sont **pas** vertes ou bleues en 3D ; leur nature se lit par la **bordure, l'icône de groupe, le libellé et l'altitude** (§10). Question produit Q1 (§19). | Une couleur n'a qu'un sens. |

---

## 2. Vérifications faites (2026-11-02, `origin/main` `c26d73a4`)

- **Numéro** : F-238 libre dans `docs/PRODUCT_SPEC.md` et `docs/features/` (dernier pris : F-237, cadré le même jour,
  #1492 ; vérifié après `git fetch` et rebase sur `5ef18505`).
- **F-237 conserve déjà les schémas** (SF-237-03, à livrer) : table `diagram_renders` (`spec_json` tel que reçu,
  `svg_key`/`png_key`/`drawio_key` en S3), écrite après un rendu réussi ; pièce `diag:<uuid>`, `kind = ARCHITECTURE`
  (cloud, drawio) ou `DIAGRAM` (mermaid) ; `GET /api/jarvis/pieces/diag:<id>/spec`. F-238 **étend** cette table au lieu
  d'en créer une (§5.3).
- **Qui écrit le diagramme ?** **L'agent n'écrit jamais de code `diagrams`.** Il appelle l'outil `render_diagram`
  (`backend/.../diagrams/DiagramToolCatalog.java`) avec `engine=cloud` et une **description JSON** `spec` :
  `{title, direction, groups[{id,label,parent}], nodes[{id,type,label,group}], edges[{from,to,label}]}`.
  `DiagramToolExecutor` la transmet à `HttpDiagramRenderer` → service `diagram-renderer` (`server.js`, route `/render`)
  → `execFile("python3", ["/app/cloud.py"])`, la description sur l'entrée standard. `cloud.py` **construit lui-même**
  le diagramme avec `diagrams` 0.24.4 (`Cluster`, `Edge`, résolution d'icônes sur plus de mille classes, alias
  SF-142-16), puis Graphviz (12.2.1 dans l'image) produit le SVG ; les icônes PNG sont inlinées en `data:`
  (SF-142-18) et la `viewBox` recadrée (SF-142-20).
- **Les attributs posés** (`cloud.py`) : `GRAPH_ATTR = {pad 0.3, dpi 144, nodesep 0.30, ranksep 0.45, splines
  "spline"}`, `EDGE_ATTR = {fontsize 12}`. **C'est `splines: "spline"` (SF-142-17) qui produit les liaisons
  courbes** : la valeur par défaut de `diagrams` était `ortho`, retirée parce qu'elle faisait de longues traversées.
  Les clusters sont des `Cluster` génériques (fond pastel par profondeur, aucun style AWS, aucune icône de groupe).
- **Bornes** : 60 nœuds, 24 groupes, profondeur 4, 120 liens, libellés 120 caractères.
- **Moteur `drawio`** (`drawio.js`, SF-142-13) : même `spec`, boîtes nommées, cadres frères (ignore `parent`).
- **Mermaid** : rendu dans la page (`PageMermaidRuntime`, SF-142-01) ou par le service ; `CloudArchitectureInMermaid`
  oriente les archis cloud vers `engine=cloud`.
- **Où atterrit le résultat** : `ProjectFileDeposit` dépose le SVG **là où vit le projet** (poste via le runner, ou
  stockage hébergé) ; dans une page F-109, le SVG est une pièce jointe de la version, inlinée en `data:` au service
  (`PageImageInliner`, SF-142-22).
- **Icônes** : celles de `diagrams` (`/usr/lib/python3.14/site-packages/resources/aws/...`), **PNG 300 × 300** RGBA,
  dérivées du paquet officiel **AWS Architecture Icons**. Déjà servies par la route `/icons` du renderer (F-215 /
  SF-215-03, liste blanche de fournisseurs, motif de nom sûr, 512 Kio max). Les **icônes de groupe officielles**
  (AWS Cloud, Region, VPC, sous-réseaux, Auto Scaling group…) ne sont **pas toutes** dans `diagrams` : `vpc.png`,
  `public-subnet.png`, `private-subnet.png`, `aws.png` y sont ; le drapeau « Region » et l'Auto Scaling group « groupe »
  n'y sont pas.
- **Moteur 3D F-236** (`frontend/src/app/jarvis/stage3d/`) : `three@0.186.1`, directeur pur → `StagePlan`, moteur hors
  zone, `jarvis-motion.ts` (`HOLO_COLORS` : normal `#E07B39`, attente `#5FD4FF`, Opus `#9B7BFF`, réussi `#4CF2A0`,
  alerte `#FF5A6E`, éteint `#2A4256` ; courbes `sortie`/`entree`/`glisse`/`souffle`/`rebond`/`ressort`),
  `jarvis-quality.ts` (3 niveaux, repli 2D), `jarvis-render-capability.ts`, banc `?banc=`, mode présentation
  (`hud/jarvis-presentation.ts`, SF-236-23), rapprochement de noms (`scenes/jarvis-name-matcher.ts`), voix phrase par
  phrase (`jarvis.component.ts`, signal `spoken`).
- **Aucune 3D d'architecture existante** ; la carte vivante F-173/F-174 (cytoscape) dessine le dépôt, pas une infra
  cloud. Aucun doublon.

---

## 3. La structure est-elle conservée ?

**Réponse : en partie, et pas de façon exploitable.**

| Où | Ce qui reste | Exploitable pour la 3D ? |
|---|---|---|
| **Entrée de l'outil** (`spec` JSON) | **Toute la structure** : groupes et parents, nœuds typés, liens étiquetés. Pas de Python à réexécuter : c'est **déjà** une donnée. | Oui — mais elle n'est gardée nulle part en tant que telle. |
| `atelier_messages.tool_trace` (F-39) | L'appel `render_diagram` **avec son `input`** — donc la `spec` — dans la trajectoire du tour. | **Non** : donnée de **rejeu** pour le modèle, bornée à 60 000 caractères (les étapes anciennes tombent), compactable ; pas un artefact. |
| SVG déposé | Graphviz écrit `<g class="node">`, `<g class="cluster">`, `<g class="edge">` avec des `<title>` **hachés** par `diagrams`, des libellés en `<text>`, les icônes en `data:` **sans leur type**. | **Non** fiable : type perdu, identifiants hachés, coordonnées 2D Graphviz. |
| Fichier `.drawio` (moteur drawio) | Boîtes nommées, cadres **frères** (pas d'imbrication), sans type. | Non. |
| Code Mermaid | Le source dans la page (`<pre class="mermaid">`) ou l'entrée de l'outil. | Hors périmètre 3D (§13). |

**Options**

| Option | Pour | Contre | Verdict |
|---|---|---|---|
| A. Conserver la `spec` brute | Simple, exacte | La 3D devrait refaire la mise en page ; 2D et 3D divergeraient. | Partiel |
| **B. Exporter au rendu un JSON de graphe (structure normalisée + mise en page cadrée)** | Une seule source pour le SVG et la 3D ; types **résolus** (alias, icône réelle, boîte neutre signalée) ; positions déterministes déjà calculées ; la 3D n'a aucun moteur de mise en page à embarquer. | Une colonne ajoutée à la table F-237. | **Retenue** |
| C. Parser le SVG | Aucune écriture nouvelle | Fragile, type perdu, dépend de Graphviz que l'itération 0 remplace. | Écartée |

**Décision D1** : F-237 (SF-237-03) conserve la `spec` brute (option A) ; F-238 y ajoute l'option B : le renderer rend
**deux** sorties pour le moteur `cloud` : le **SVG** (inchangé pour l'agent) et le **graphe d'architecture**
(`ArchitectureGraph` v1, §5) qui contient la structure **et** la mise en page cadrée. La gateway le range dans une
colonne `graph_json` **ajoutée** à `diagram_renders` (§5.3), filtrée `user_id`. Les schémas antérieurs à SF-237-03
n'ont rien de conservé ; ceux rendus entre SF-237-03 et SF-238-07 ont leur `spec` mais pas de graphe (§4.7).

---

## 4. Itération 0 — Le schéma 2D cadré

### 4.1 Constat mesuré (essai du 2026-11-02)

Trois schémas types (`docs/features/F-238/essai-iteration-0/specs/` : 3-tiers multi-AZ, EKS, serverless) rendus avec
l'image de production `diagram-renderer:staging-a6e9e4a7`, puis comptés par la garde (`garde-orthogonale.py`) :

| Schéma | Aujourd'hui (`spline`) | Graphviz `splines=ortho` (+ `xlabel`) | Prototype ELK |
|---|---|---|---|
| 3-tiers multi-AZ | **12 / 15** segments non orthogonaux | 0 / 19 ; « HTTPS » posé sur le libellé du VPC ; liaisons qui longent les bordures | **0 / 34** |
| EKS | **21 / 23** | 0 / 19 ; « journaux », « images » loin de leur liaison | **0 / 27** |
| Serverless | **4 / 9** | 0 / 13 ; **deux liaisons finissent dans le vide** (« auth », « écrit ») | **0 / 15** |

Constat supplémentaire : la topologie AWS canonique à 5 niveaux est **refusée** aujourd'hui (profondeur 4).

Captures et planche : `docs/features/F-238/captures/iteration-0/index.html`.

### 4.2 Routage : options essayées et décision

| Option | Résultat de l'essai | Verdict |
|---|---|---|
| **Graphviz `splines=ortho`** | Angles droits, mais limites connues **constatées** : les étiquettes d'arêtes ne sont pas gérées (il faut passer en `xlabel`, posé sans égard aux liaisons) ; ports ignorés ; avec des **clusters**, des liaisons traversent les libellés de zone et se terminent **hors du nœud** (serverless). Aucun style de groupe AWS. | **Écartée** |
| **ELK `layered` seul** (elkjs, `edgeRouting=ORTHOGONAL`, `hierarchyHandling=INCLUDE_CHILDREN`) | 0 segment oblique sur les trois schémas ; imbrication native ; étiquettes posées sur leur liaison ; zones AWS stylées par notre SVG. **Mais** les AZ sœurs sont traitées comme des étapes du flux : **empilées** ou **l'une après l'autre** (capture `trois-tiers-elk-lr.png`), jamais parallèles ; le partitionnement ELK est ignoré en mode hiérarchique (essayé). | Base retenue, **insuffisante seule** |
| **D2 (TALA), draw.io, PlantUML** | Non essayés : TALA est propriétaire, draw.io n'a pas de routage serveur déterministe, PlantUML n'a pas de conventions AWS imbriquées. | Écartés |
| **Retenue : ELK + couche de composition des zones + routeur orthogonal inter-zones** | Voir ci-dessous. | **D2** |

**D2 — le moteur « cadre »** (dans `diagram-renderer`, Node, `elkjs` épinglé, EPL-2.0 compatible avec un service
interne) :

1. **Composition des zones** (code maison, pur, déterministe) : l'arbre des zones est posé **avant** les nœuds.
   Règles : les **AZ sœurs sont parallèles** (colonnes côte à côte si le flux va de haut en bas ; bandes empilées si le
   flux va de gauche à droite), de **taille égale** ; deux AZ de **même gabarit** (même multiset de types par
   sous-réseau) reçoivent la **même mise en page interne**, en miroir de position (symétrie, jamais de composant
   ajouté) ; dans une AZ, l'ordre est **sous-réseau public puis privé** dans le sens du flux ; les ressources
   régionales hors VPC (S3, DynamoDB, CloudWatch…) vont dans une **bande régionale** le long du VPC ; les acteurs
   externes (utilisateurs, Internet, on-prem) **hors** de AWS Cloud, à l'entrée du flux.
2. **Placement dans chaque zone feuille** par ELK `layered` (liaisons internes à la zone seulement), **aligné sur la
   grille** (§4.4).
3. **Routage orthogonal des liaisons inter-zones** : routeur maison sur **grille de canaux** (A* avec pénalité de
   coude et de croisement, obstacles = nœuds et libellés de zone, traversée de bordure **perpendiculaire** seulement),
   déterministe, ≤ 120 liens. Les liaisons parallèles entre deux mêmes zones sont **regroupées en faisceau** à pas
   constant.
4. **Étiquettes de flux** posées sur le plus long segment de leur liaison, déplacées sur le segment suivant si elles
   chevauchent quelque chose ; jamais plus d'une étiquette par segment.
5. **Sortie** : le SVG (dessiné par nous, pas par Graphviz) **et** le graphe `ArchitectureGraph` (§5) avec toutes les
   coordonnées.

Le moteur `drawio` reprend **les mêmes coordonnées** (`edgeStyle=orthogonalEdgeStyle`, cadres imbriqués) : le
`.drawio` réouvrable devient cadré lui aussi, pour le même prix.

### 4.3 Zones conformes aux conventions AWS

Nouveau champ **`kind`** sur chaque groupe ; à défaut, **inféré du libellé** (« AWS Cloud », « Région… »,
« VPC… », « Availability Zone… », « … public… », « … privé… / private… ») et l'inférence est **signalée** à l'agent
(« NOTE DU RENDU : zone « X » supposée de type … ; précise `kind` »). Valeurs et styles (paquet officiel *AWS
Architecture Icons*, section « Groups » ; valeurs vérifiées sur le paquet épinglé par SF-238-02) :

| `kind` | Libellé type | Bordure | Trait | Fond | Icône de groupe (haut gauche) | Parent autorisé |
|---|---|---|---|---|---|---|
| `cloud` | AWS Cloud | `#232F3E` | continu | aucun | logo AWS | racine |
| `account` | Compte « … » | `#E7157B` | continu | aucun | compte | racine, `cloud` |
| `region` | Région eu-west-3 | `#00A4A6` | **pointillé** | aucun | drapeau | `cloud`, `account` |
| `vpc` | VPC 10.0.0.0/16 | `#8C4FFF` | continu | aucun | VPC (cadenas nuage) | `region` |
| `az` | Availability Zone A | `#00A4A6` | **pointillé** | aucun | **aucune** (convention) | `vpc` |
| `subnet_public` | Sous-réseau public | `#7AA116` | continu | `#F2F6E8` | cadenas ouvert | `az`, `vpc` |
| `subnet_private` | Sous-réseau privé | `#00A4A6` | continu | `#E6F6F7` | cadenas fermé | `az`, `vpc` |
| `asg` | Auto Scaling group | `#ED7100` | **pointillé** | aucun | Auto Scaling | `subnet_*`, `az`, `vpc` |
| `security_group` | Security group | `#DD344C` | continu | aucun | aucune | `vpc` et descendants |
| `onprem` | Centre de données | `#7D8998` | continu | aucun | immeuble | racine |
| `generic` | tout le reste | `#7D8998` | pointillé | aucun | aucune | partout |

- **Libellé en haut à gauche**, après l'icône de groupe (carré 32 px plein de la couleur de bordure), Arial/Helvetica
  13 px, couleur de la bordure.
- **Imbrication stricte** : un parent non autorisé est **refusé** avec la raison (« une Availability Zone se place
  dans un VPC »), comme les cycles aujourd'hui. Profondeur maximale **6**.
- **Marges régulières** : 24 px intérieur, 48 px sous la ligne de libellé, 24 px entre zones sœurs.
- **Icônes de groupe** : copiées du **paquet officiel** (SVG) téléchargé au build de l'image avec une **empreinte
  épinglée** (patron SF-215-06), car `diagrams` ne les a pas toutes. Licence : les *AWS Architecture Icons* sont
  fournies par AWS pour dessiner des architectures ; usage conforme si elles ne sont **ni modifiées ni recolorées**
  et n'impliquent aucun partenariat. Note de licence jointe dans l'image.

### 4.4 Grille, alignement, flux, flèches

| Règle | Valeur par défaut |
|---|---|
| Pas de grille | 8 px ; nœud = cellule 128 × 112 (icône 64 px + libellé sur 2 lignes) |
| Espacement entre nœuds | 48 px constant ; entre rangs 64 px |
| Alignement | Centres des nœuds d'un même rang alignés au pixel ; nœuds homologues de deux AZ symétriques **sur la même ligne** |
| Flux principal | `direction` (LR par défaut, TB conseillé pour du multi-AZ, §19 Q2) ; l'entrée (utilisateurs, DNS, CDN) toujours au début du flux |
| Liaisons | 1,5 px `#545B64`, **coudes droits** (rayon 0), pointe triangulaire pleine 8 px, une seule par lien, aucune liaison sous une icône |
| Bidirectionnel | `bidirectional: true` ⇒ deux pointes ; jamais deux liaisons superposées |
| Étiquettes de flux | 12 px, fond blanc 90 %, sur leur segment, aucune collision (garde §4.6) |
| Densité | Bornes F-142 inchangées (60 nœuds) ; la note de densité SF-142-17/19 reste |

### 4.5 Guidage de l'agent (consigne fixe, rien de volatil)

- **`DiagramToolCatalog.GUIDE`** (texte **fixe** de la consigne, donné avec l'outil) : un paragraphe remplace celui
  des groupes : « Décris les zones AWS avec `kind` (`cloud`, `region`, `vpc`, `az`, `subnet_public`,
  `subnet_private`, `asg`, `security_group`…) et imbrique-les par `parent` comme dans la réalité : Région › VPC ›
  Availability Zone › sous-réseau. Deux AZ qui portent les mêmes services se décrivent avec les **mêmes types** : le
  rendu les aligne. Ne place jamais un composant dans une zone où il n'est pas. » Modifié **une fois** (SF-238-06),
  test byte-stable du préfixe ensuite (patron F-134).
- **Schéma d'entrée de l'outil** : `groups[].kind` (enum), `edges[].bidirectional`, `direction` documentés.
- **Le module d'aide est le renderer lui-même** : `kind` porte le style (pas de classes Python à connaître) ;
  l'inférence par libellé et le refus d'imbrication **disent** quoi corriger. Aucune bibliothèque à installer sur le
  poste.
- La valeur du `kind`, les noms de zones, le contenu du schéma vont dans l'**appel d'outil** (message), jamais dans
  la consigne système.

### 4.6 Gardes automatiques (au build du renderer et à chaque rendu en test)

| Garde | Échoue si |
|---|---|
| **G1 Orthogonalité** | un segment de liaison du SVG n'est ni horizontal ni vertical (tolérance 0,5 px), ou une liaison contient une courbe (`C`, `Q`, `A` dans un `path`). Prototype : `essai-iteration-0/garde-orthogonale.py` (compte 12/15, 21/23, 4/9 sur l'actuel, 0 sur ELK). |
| G2 Extrémités | une liaison ne commence pas ou ne finit pas à ≤ 2 px du bord de son nœud (le défaut « dans le vide » de Graphviz ortho). |
| G3 Collisions | une étiquette de flux chevauche une icône, un libellé de nœud, un libellé de zone ou une autre étiquette ; une liaison passe sous une icône. |
| G4 Imbrication | un nœud ou une zone déborde de son parent ; une marge intérieure < 24 px. |
| G5 Grille | un centre de nœud hors de la grille de 8 px ; deux nœuds homologues d'AZ symétriques non alignés. |
| G6 Conventions | un `kind` rendu avec une autre couleur ou un autre trait que la table §4.3. |
| G7 Non-régression | les 3 schémas types + les jeux de tests existants (`tests/test_cloud*.py`) rendent sans erreur ; la description plate d'avant donne toujours un schéma valide. |

Les gardes tournent dans `diagram-renderer/tests/` (CI de l'image) ; G1 et G2 tournent aussi **à chaque rendu** en
production : un échec n'empêche pas le livrable (le SVG part), il est **compté** et signalé dans le journal.

### 4.7 Les schémas existants : ne pas les re-rendre d'office

- Ils vivent dans des **livrables déjà remis** (pages, slides, documents, fichiers des postes) : les modifier
  silencieusement changerait un document client.
- Leur `spec` n'est conservée que dans le rejeu borné (§3), ou dans `diagram_renders` pour ceux rendus après
  SF-237-03 : pas de source fiable pour tous.
- **Décision D3** : aucun re-rendu en masse. **À la demande** : « recadre ce schéma » ⇒ l'agent relit la `spec` dans le
  fil (si elle y est) ou la redécrit depuis la page, puis rappelle `render_diagram` ; la page reçoit une **nouvelle
  version** (historique F-109 conservé). Une `spec` déjà dans `diagram_renders` se re-rend **sans l'agent** à
  l'ouverture de la pièce en 3D (le graphe manquant est calculé une fois, puis rangé) ; à partir de SF-238-07, tout
  nouveau schéma a son graphe conservé.

### 4.8 Planche avant/après

`docs/features/F-238/captures/iteration-0/index.html` (essai du 2026-11-02 : actuel, Graphviz ortho, prototype ELK, et
la capture qui justifie la couche de composition). **SF-238-06** la refait avec le moteur final sur les mêmes trois
schémas + un schéma « tout » (compte, 2 régions, 3 AZ, ASG, security group) et la présente au PO : **visa** = bascule
du moteur par défaut.

---

## 5. Le modèle de données du graphe

### 5.1 `ArchitectureGraph` v1 (JSON produit par le renderer)

```json
{
  "version": 1,
  "title": "Application 3-tiers multi-AZ",
  "direction": "TB",
  "bounds": { "w": 1480, "h": 1120 },
  "zones": [
    { "id": "vpc", "kind": "vpc", "label": "VPC 10.0.0.0/16", "parent": "region", "depth": 2,
      "box": { "x": 120, "y": 160, "w": 1200, "h": 860 }, "inferred": false }
  ],
  "nodes": [
    { "id": "rdsa", "type": "aws.rds", "resolved": "aws/database/rds", "label": "RDS primaire",
      "zone": "priva", "family": "database", "unknown": false,
      "box": { "x": 640, "y": 720, "w": 128, "h": 112 } }
  ],
  "edges": [
    { "id": "e4", "from": "ec2a", "to": "rdsa", "label": "SQL", "bidirectional": false,
      "points": [ { "x": 704, "y": 640 }, { "x": 704, "y": 720 } ],
      "labelBox": { "x": 712, "y": 668, "w": 30, "h": 16 } }
  ],
  "notices": [ "zone « Sous-réseau public A » : kind inféré" ]
}
```

- **`resolved`** : chemin d'icône **réellement** utilisé (après alias SF-142-16) ; `unknown: true` ⇒ boîte neutre
  (SF-142-12), jamais une icône approchante.
- **`family`** : famille de service dérivée du chemin (`compute`, `database`, `network`, `storage`, `integration`,
  `security`, `analytics`, `management`, `ml`, `client`…) : sert à la narration (« la base » ⇒ `database`, §11).
- **Coordonnées en pixels du SVG** : la 3D les reprend telles quelles (§9.1). Une liaison est une **polyligne
  orthogonale** (garantie G1).
- Bornes : celles de F-142 (≤ 60 nœuds, ≤ 24 groupes, ≤ 120 liens) ⇒ ≤ 40 Ko de JSON.

### 5.2 Ce qui est produit, par moteur

| Moteur | SVG/PNG | `.drawio` | Graphe conservé |
|---|---|---|---|
| `cloud` | SVG cadré | — | **oui** |
| `drawio` | aperçu PNG cadré | cadré | **oui** (même mise en page) |
| `mermaid` | inchangé | — | non (§13) |

### 5.3 Stockage — colonne ajoutée à `diagram_renders` (F-237) (migration `238-diagram-renders-graph.xml`, additive)

| Colonne ajoutée | Type | Rôle |
|---|---|---|
| `graph_json` | text, nullable | `ArchitectureGraph` v1 ; `null` pour `DIAGRAM` (mermaid) et pour les rendus d'avant SF-238-07 |
| `graph_version` | smallint, nullable | version du format (1) |
| `node_count`, `zone_count`, `edge_count` | int, nullable | listes et règles de lisibilité (§8.2) sans charger le JSON |

Aucune table nouvelle. Écrite au même endroit que la ligne F-237 (après un dépôt réussi : pas de graphe sans
fichier). Rétention : celle de `diagram_renders`. `ARCHITECTURE_CANONIQUE.md` mis à jour à l'étape 6 de SF-238-07.

Lecture (nouvelle, même contrôleur que F-237, filtrée `currentUser.requireId()` + `requireOwned` sur le sujet) :
`GET /api/jarvis/pieces/diag:<id>/graph` — le graphe ; s'il manque mais que `spec_json` existe, la passerelle le fait
calculer **une fois** par le renderer (gratuit) et le range ; **404** si la pièce appartient à un autre utilisateur.

### 5.4 Icônes servies au front

- `GET /api/diagrams/icons/{chemin}.png` : **proxy** de la route `/icons` du renderer (F-215), même liste blanche et
  même motif de nom, réponse `Cache-Control: public, max-age=31536000, immutable` ; aucune donnée utilisateur.
- Le front compose un **atlas** (`CanvasTexture`, cellules 128 px, ≤ 64 icônes ⇒ 1024 × 1024) pour la scène : une
  seule texture, un seul appel de dessin pour toutes les icônes (§14).
- Licence (§4.3) : icônes non modifiées ; la teinte holographique est un **halo et un socle**, jamais une recoloration
  de l'icône.

---

## 6. Principes de la scène 3D

- **Directeur pur** `ArchitectureStageDirector` (aucun import de `three`, testé sous Karma) : (graphe, état de
  navigation, phrase en cours) ⇒ `ArchitecturePlan` déclaratif (caméra, état de chaque zone/nœud/liaison, flux
  animés, panneaux). **Le moteur dessine et interpole**, comme F-236.
- **Aucune mise en page dans le navigateur** : les positions viennent du graphe (§5.1). Pas d'`elkjs` côté front.
- **Déterministe** : même graphe + même état ⇒ même image (banc `?banc=architecture&t=`).

---

## 7. Points d'appui sur F-237 (fournis par F-237, non redéfinis ici)

| Point d'appui (F-237) | Ce que F-237 fournit | Ce que F-238 y ajoute |
|---|---|---|
| **Pièce `ARCHITECTURE`** (§10.1, SF-237-03) | `diag:<uuid>`, `diagram_renders`, `spec_json`, SVG en S3, `GET /api/jarvis/pieces/diag:<id>/spec`. | Colonne `graph_json` et lecture `/graph` (§5.3). |
| **Registre `PIECE_VIEWERS`** (§10.2, SF-237-04) | Une visionneuse par `kind` ; la visionneuse SVG de `ARCHITECTURE` (SF-237-07) est **remplaçable**. | Visionneuse **3D** pour `ARCHITECTURE` quand un graphe existe et que la 3D est possible ; sinon la visionneuse SVG de F-237 = **repli 2D**. |
| **État `PieceNav`** (§5.1, §10.3) | Cible nommée générique `target`, niveau `INSTANT`, `zoom`, `pan`, `history` ; réducteur pur unique pour toucher, voix locale et outil. | Sous-état `arch: {focus, folded[], exploded, flow, view}` porté par la pièce ouverte, mêmes actions de base (`zoom`, `back`, `select` = focus). |
| **Bloc `## Écran`** (§5.2) | Sérialisé dans le **message**, ≤ 1 500 caractères. | Une ligne : « Architecture « 3-tiers » ; focus : RDS primaire ; repliés : zone B ; éclaté : non ; flux : ALB → Web A ». |
| **Grammaire locale** `jarvis-piece-voice.ts` (§5.3) | Liste fermée, phrase entière, sans IA. | Ordres d'architecture exacts quand une pièce `ARCHITECTURE` est ouverte : « éclate », « rassemble », « vue de dessus », « vue d'ensemble », « en 2D », « replie tout », « déplie tout ». Tout ce qui nomme un service ou une zone part au cerveau rapide. |
| **Outil `afficher`** du cerveau rapide (§6.2) | `action`, `cible`, `zoom`… ; description fixe (« (6) architecture : le schéma »). | Valeurs d'`action` ajoutées **une fois** (version de consigne `4`, amendement signalé de F-237) : `focus`, `flux`, `replier`, `deplier`, `eclater`, `vue` ; `cible` = libellé, id, type ou famille (« la base »). Test byte-stable ensuite. |
| **Ligne `🖥 montrer:`** côté Opus (§6.3) | `🖥 montrer: pieces <id>` dans le message d'un tour né à la voix. | Rien : un tour Opus qui veut montrer une archi passe par `pieces diag:<id>` ; la narration (§11) fait le reste. |
| **Montrer d'initiative** (§7, I6) | « Comment c'est déployé chez Edenred ? » ⇒ le schéma le plus récent du client. | La pièce s'ouvre directement en 3D (T-A1) quand le graphe existe ; premier cadrage `afficher {action: flux, cible: "entrée"}`. |

**Décision D11** : étendre l'enum `action` d'`afficher` plutôt que coder des verbes dans `cible` : un texte libre
dans `cible` (« replie le VPC ») serait réinterprété par l'écran, donc deviné ; l'enum est **vérifiable**. Coût : une
réécriture du cache du cerveau rapide, une fois.

## 8. Mise en page 3D automatique

### 8.1 Des coordonnées 2D aux plateaux

- **Plan au sol** : `(x, y)` du graphe ⇒ `(x, z)` en 3D (échelle 1 px = 0,01 unité). Le flux reste lisible en vue de
  dessus : la vue 2D cadrée et la 3D vue d'en haut **coïncident**.
- **Altitude par profondeur** : chaque zone est un **plateau translucide** à l'altitude `h = depth × Δ` ;
  **compact** Δ = 0,25 (terrasses légères) ; **éclaté** Δ = 2,2 (P1 « plateaux empilés » : on voit chaque niveau
  séparé, les nœuds restent sur leur plateau, les liaisons inter-niveaux deviennent des **colonnes verticales**).
- Les **nœuds flottent** à 0,35 au-dessus de leur plateau, l'icône face à la caméra (sprite), un **socle** lumineux et
  une **ombre portée** sur le plateau disent où il est posé.
- **Liaisons orthogonales en 3D** : la polyligne du graphe est posée à l'altitude du plateau commun le plus profond ;
  un changement de plateau se fait par un **segment vertical** à un coude existant. Jamais de diagonale, ni en plan ni
  en hauteur.

### 8.2 Lisibilité au-delà de N nœuds

| Nœuds | Comportement par défaut |
|---|---|
| ≤ 24 | tout affiché, étiquettes de tous les nœuds |
| 25 – 40 | étiquettes des nœuds **du focus et de ses voisins** seulement ; les autres au survol / au toucher |
| > 40 | les **AZ symétriques** se replient en une seule « AZ × n » (jumelles empilées en épaisseur) ; les sous-réseaux hors focus sont **repliés** (capsule) ; étiquettes du focus seulement |

### 8.3 Regroupements repliables

- **Replier une zone** : son contenu se rassemble en une **capsule** au centre du plateau (icône de groupe + « 7
  services ») ; le plateau garde son emprise (pas de nouvelle mise en page, rien ne saute) ; les liaisons vers
  l'intérieur s'ancrent au **bord** du plateau, fusionnées en faisceau avec un compteur.
- **Déplier** : l'inverse. Les états repliés sont transmis dans le message (§7).
- Défaut d'ouverture : tout déplié si ≤ 24 nœuds ; sinon VPC dépliés, sous-réseaux hors flux principal repliés.

---

## 9. La scène

### 9.1 Éléments

| Élément | Rendu | Technique (F-236) |
|---|---|---|
| Plateau | dalle de verre 0,04 d'épaisseur, fond 0,04–0,18 selon l'état, **liseré** émissif (continu ou pointillé selon le `kind`), icône de groupe et libellé en haut à gauche (étiquette CSS2D) | `InstancedMesh` (une dalle), liserés `LineSegments2` (dashed pour `region`/`az`/`asg`) |
| Service | icône officielle (atlas), socle disque lumineux, halo selon l'état | sprites instanciés (un appel de dessin), socles `InstancedMesh` |
| Liaison | **filament** à noyau clair, halo orange, coudes droits | `LineSegments2` par famille d'état |
| Flux | **particules** qui parcourent la polyligne **de `from` vers `to`** (deux sens si bidirectionnel), vitesse 0,8 u/s, espacement 0,6 | pool fixe de F-236 (`Points`, 0 allocation) |
| Étiquette de flux | petit cartouche CSS2D au milieu du plus long segment | CSS2D |
| Panneau de service | fiche holographique : type, libellé, zone, liens entrants/sortants | panneau DOM F-236 (T-P1/T-P2) |

### 9.2 États (5 paliers de transparence F-236 reconduits)

latent 0,04 · contexte 0,18 · actif 0,45 · panneau 0,75 · **allumé** 1. Un seul « héros » à la fois (règle F-236
§7.3-6) : le service ou le flux dont Jarvis parle.

---

## 10. Direction artistique

**Grammaire F-236 reconduite** (DESIGN_SYSTEM §23 ; goût du PO du 2026-11-02) :

| Rôle dans la scène d'architecture | Couleur | Jeton |
|---|---|---|
| Plateaux, liserés, filaments, socles au repos — **dominant** | orange de la marque `#E07B39` (texte `#F0954F`) | `--cg-holo`, `--cg-holo-text` |
| Ce dont Jarvis parle (héros) | blanc chaud `#FFF3E4` au cœur, halo orange renforcé | `--cg-holo-core` |
| Un flux ou un service **attend** une réponse du PO (« lequel voulez-vous ? ») | cyan `#5FD4FF` | `--cg-holo-wait` |
| Opus est en train d'analyser l'architecture (tour délégué en cours) | voile violet `#9B7BFF` sur le plateau racine | `--cg-holo-deep` |
| Vérifié / sain (ex. une vérification du tour a réussi sur ce service) | vert `#4CF2A0` | `--cg-holo-green` |
| **Alerte** (réservé : une veille ou le tour signale un incident sur ce service) | rouge `#FF5A6E` | `--cg-holo-red` |
| Replié / hors focus | gris bleuté `#2A4256` | `--cg-holo-off` |

- **Les zones ne sont pas colorées par leur nature en 3D** (pas de vert « public », pas de bleu « privé ») : leur
  nature se lit par **le trait** (continu / pointillé), **l'icône de groupe officielle**, **le libellé** et
  **l'altitude**. La couleur ne porte jamais seule l'information. (Le 2D, lui, suit les conventions AWS, §4.3.)
- **Icônes AWS non recolorées** (licence) : elles gardent leurs couleurs officielles, posées sur un socle orange.
- **Polices** : Space Grotesk 600 capitales espacées (libellés de zones, titres), JetBrains Mono (CIDR, identifiants),
  Inter (fiches). Aucune police nouvelle.
- **Décor riche** (goût V3) : la scène se pose dans l'univers F-236 (nébuleuse, poussière, sol polaire sous le
  plateau racine) ; le bloom sélectif fait briller liserés, filaments et particules, jamais le texte.

### 10.1 Transitions nommées (courbes de `jarvis-motion.ts`)

| # | Transition | Durée | Courbe | Déroulé |
|---|---|---|---|---|
| **T-A1** | **Entrée de l'architecture** | **2,2 s** | séquence | 0–400 ms le panneau 2D F-237 se **couche** et devient le plateau racine (`glisse`) · 300–1 100 les plateaux montent à leur altitude **du plus large au plus profond** (60 ms d'écart, `sortie`) · 700–1 500 les services **se posent** (échelle 0 → 1, `rebond`, 25 ms d'écart, dans l'ordre du flux) · 1 100–1 900 les filaments **se tracent** dans le sens du flux (`sortie`) · 1 600–2 200 premières particules, étiquettes. Sautée au premier toucher (fondu 300 ms). |
| T-A2 | Sortie | 0,6 s | `entree` | particules éteintes, services rentrés, plateaux qui redescendent et se couchent en panneau. |
| **T-A3** | **Éclater / rassembler** | 1,2 s | `glisse` | Δ 0,25 ↔ 2,2 ; segments verticaux qui s'allongent ; la caméra recule de 20 % pour garder le cadre. |
| **T-A4** | **Replier une zone** | 0,7 s | `entree` | services aspirés vers le centre (40 ms d'écart), capsule qui apparaît (`rebond`), liaisons qui glissent au bord. |
| T-A5 | Déplier | 0,8 s | `sortie` | inverse de T-A4. |
| **T-A6** | **Zoom sur un service** | 1,4 s | `ressort` | reprend T-Z1 (arc, point visé centré, profondeur de champ PC) ; panneau du service (T-P1) à l'arrivée. |
| **T-A7** | **Allumer un flux** | 0,9 s | `sortie` | le chemin se trace de la source à la cible segment par segment (150 ms/segment, borné à 0,9 s), puis les particules s'accélèrent ×2 pendant 2 s ; le reste passe en contexte (T-L3). |
| T-A8 | Allumer un service (narration) | 0,35 s | `sortie` | halo 0,2 → 1, socle qui pulse une fois, étiquette en blanc. Extinction 0,9 s (`souffle`). |
| T-A9 | Vue de dessus / de face / ensemble | 1,6 s | `ressort` | vue de dessus = **le schéma 2D cadré** exactement (zénith, focale fixe) ; ensemble = élévation 32°, azimut 35°. |
| T-A10 | Bascule 3D ↔ 2D (repli, choix) | 0,4 s | `glisse` | fondu croisé avec le SVG, cadré sur le même focus (reprend T-F). |

Règles F-236 §7.3 reconduites : jamais de saut, transitions interruptibles, ≤ 3 transitions majeures simultanées,
anti mal des transports (≤ 1 déplacement de caméra / 2,5 s, aucun roulis), rien ne bouge brusquement pendant que le PO
parle.

---

## 11. Narration synchronisée avec la voix

### 11.1 Quand

Au **début de la lecture** de chaque phrase de Jarvis (signal `spoken` de `jarvis.component.ts`, phrase par phrase,
déjà là) — **pas** à la génération du texte : ce qui s'allume correspond à ce qu'on **entend**. Sans voix (mode texte),
au moment où la phrase s'affiche.

### 11.2 À partir de quoi (rapprochement local, zéro coût)

`architecture-narration.ts` (pur) lit la phrase et le graphe :

1. **Noms** : libellés des nœuds et des zones (« RDS primaire », « VPC de production »), via le rapprochement prudent
   de F-234 (`jarvis-name-matcher.ts` : normalisation, frontière de mot, ≥ 3 caractères, ambiguïté ⇒ rien).
2. **Types et sigles** : « l'ALB », « la Lambda », « le bucket S3 », « EKS » ⇒ nœuds de ce type (table d'alias
   F-142 reprise côté front, **égalités seulement**).
3. **Familles** : « la base » ⇒ `database` ; « le cache » ⇒ ElastiCache ; « la file » ⇒ SQS… — **seulement si un seul
   nœud** de la famille est dans le focus courant, sinon rien (une lueur fausse coûte la confiance).
4. **Flux** : si une phrase cite deux services **reliés** dans l'ordre (« entre par l'ALB, puis va vers les
   instances »), le **chemin** entre eux s'allume (T-A7) ; « flux sortant » / « entrant » ⇒ liaisons sortantes /
   entrantes du focus ; « vers Internet » ⇒ chemin vers les nœuds externes.
5. **Zones** : « dans la zone B », « le sous-réseau privé » ⇒ le plateau s'allume (liseré).

Priorité d'affichage : flux > service > zone ; au plus **3** éléments allumés (règle F-234 D2) ; ce qui est allumé
s'éteint (T-A8 inverse) à la phrase suivante sauf s'il est re-cité.

### 11.3 Quand le rapprochement ne suffit pas

Le cerveau rapide peut appeler `afficher` (F-237, actions §7) **avant** de parler — le geste part au premier appel
d'outil, avant la phrase : la caméra se place, puis la narration allume les détails. Il le fait de lui-même pour le
premier cadrage (« le trafic entre par l'ALB… » ⇒ `afficher {action: flux, cible: "entrée"}`). Aucune consigne
système nouvelle : la doctrine vit dans la description fixe de l'outil (F-237).

### 11.4 Exemple

« Le trafic entre par l'**ALB**, Monsieur, puis se répartit sur les **deux zones**. » ⇒ t0 : ALB allumé (T-A8) +
chemin Utilisateurs → ALB (T-A7) ; « deux zones » ⇒ liserés des deux AZ en actif ; « Les **instances** écrivent dans
**RDS primaire**, répliquée en zone B. » ⇒ chemin EC2 → RDS primaire, puis RDS primaire → RDS secours.

---

## 12. Outils et navigation (extensions de F-237)

| Geste (toucher / souris) | Voix (résolue localement si non ambiguë) | Effet |
|---|---|---|
| Glisser à un doigt / clic-glisser | « tourne », « vue de dessus », « de face » | orbite bornée (élévation 10°–90°), T-A9 |
| Pincer / molette | « zoome », « dézoome », « vue d'ensemble » | dolly borné, T-Z2 |
| Toucher un service | « zoome sur la base », « montre-moi l'ALB » | T-A6 + panneau |
| Toucher long sur un plateau | « replie le VPC », « déplie la zone A » | T-A4 / T-A5 |
| Double toucher sur le vide | « éclate », « rassemble » | T-A3 |
| Toucher une liaison | « montre le flux sortant », « d'où vient le trafic ? » | T-A7 |
| Bouton « 2D » | « en 2D » | T-A10 |

Les ordres exacts de la grammaire locale (§7) ne coûtent rien ; tout ce qui nomme un service ou une zone part au
**cerveau rapide** (F-237), qui reçoit le bloc `## Écran` et répond par `afficher` ; ambigu (« la base » avec deux
bases) ⇒ il pose la question (cyan). **Aucune navigation hors de Jarvis.**

---

## 13. Mermaid et diagrammes non AWS

**Décision D4 : pas de rendu 3D générique en graphe dans F-238.**

- Un `sequenceDiagram` est **temporel** : sa 3D n'apporterait rien et trahirait l'ordre.
- Un `flowchart` en 3D serait un graphe flottant sans zones : l'intérêt du PO (plateaux, conventions AWS) disparaît.
- Les schémas Mermaid restent des **pièces 2D** dans la galerie F-237 (panneau).
- `architecture-beta` Mermaid : déjà orienté vers `engine=cloud` (`CloudArchitectureInMermaid`).
- Suite possible, **sur usage mesuré** (pièces Mermaid ouvertes à J+14) : un rendu « graphe en lévitation » générique
  à partir du même `ArchitectureGraph` (zones absentes). Hors périmètre.

---

## 14. PC, téléphone, repli 2D, performance

| | PC (cockpit) | Téléphone |
|---|---|---|
| Scène | plein écran ou au centre du cockpit F-236 | plein écran, barre et sous-titres seulement |
| Étiquettes | ≤ 48 (CSS2D) | ≤ 16 (focus et voisins) |
| Particules de flux | ≤ 2 000 | ≤ 400 |
| Plateaux éclatés | oui | oui, Δ réduit à 1,4 |
| Panneau de service | latéral, détaillé | volet bas, 4 lignes |
| Profondeur de champ | PC Haut | non |

- **Instanciation** : plateaux, socles = 1 `InstancedMesh` chacun ; icônes = 1 `InstancedMesh` de quads + atlas ;
  liserés et filaments = 1 `LineSegments2` par famille d'état ; particules = pool fixe F-236. **≤ 20 appels de dessin**
  pour une archi de 60 nœuds (hors post-traitement).
- **Budgets F-236 reconduits** : téléphone p50 ≥ 50 i/s, CPU p90 ≤ 4 ms ; PC p50 ≥ 60 i/s ; **0 allocation par
  image** ; onglet caché ⇒ 0 image ; qualité adaptative à 3 niveaux et repli automatique.
- **Repli 2D** = le **SVG cadré** dans la **visionneuse SVG de F-237** (SF-237-07), avec les mêmes interactions minimales
  (toucher un service ⇒ panneau, pas de caméra). Causes : celles de F-236 T10 + graphe absent (schéma antérieur à
  SF-238-07 et sans `spec`).
- **Poids** : aucune dépendance front nouvelle (`three` déjà là, pas d'`elkjs` front) ; garde d'accident F-236
  inchangée.
- **Mode présentation** (SF-236-23) : libellés de nœuds et de zones **conservés** (c'est le contenu montré), mais
  CIDR, identifiants de compte et ARN **masqués** (`10.0.•.•/16`, `compte •••• 4821`).

---

## 15. Sécurité

| Sujet | Mesure |
|---|---|
| Isolation | `diagram_renders.graph_json` lu par le contrôleur des pièces F-237 (`user_id` + `requireOwned`) ; 404 sur l'id d'un autre ; test A/B (SF-238-07). |
| Exécution | Inchangé F-142 : **aucun code** du modèle n'est exécuté ; la `spec` est une donnée validée (bornes, imbrication, libellés ≤ 120). |
| Injection dans le rendu | Libellés **échappés** dans le SVG maison (`&`, `<`, `"`) ; en 3D, étiquettes en `textContent`, jamais `innerHTML`. |
| Icônes | Proxy à liste blanche, motif de nom, taille ≤ 512 Kio, `nosniff`, aucune traversée de chemin (repris de F-215). |
| Données sensibles | CIDR, comptes, ARN masqués en mode présentation ; le graphe ne sort pas de l'appareil (rendu 100 % navigateur) ; aucun appel réseau pendant un tour pour dessiner. |
| Consigne | `GUIDE` et l'enum d'`afficher` modifiés une fois chacun, tests byte-stables ; l'état de navigation dans le bloc `## Écran` du message. |
| Dépendance | `elkjs` épinglé dans l'image du renderer, licence EPL-2.0 jointe ; paquet d'icônes AWS téléchargé par empreinte épinglée. |

---

## 16. Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés et vérification |
|---|---|---|
| **Navigation / routing** | Oui (itér. 1) | Aucune route nouvelle : la scène vit dans `/jarvis` (pièce F-237) ; `?banc=architecture` hors production. Non-régression : `?sujet=`, `?alerte=`, `?presentation=1`, `?cockpit=1`, retour `/atelier`, en 3D et en 2D. |
| **Contexte tenant** | **Oui** | `GET /api/jarvis/pieces/diag:<id>/graph` (contrôleur F-237) : `currentUser.requireId()` + `requireOwned`, test A/B. Écriture avec la ligne `diagram_renders` (userId et `Workspace` du tour, déjà vérifié possédé). Proxy d'icônes sans donnée utilisateur. |
| **Auth / Principal** | Non | Endpoints sous `authGuard` existant ; proxy d'icônes authentifié comme le reste de `/api`. |
| **Plans / limites** | Non | Rendu gratuit (F-142) ; aucun quota touché. |
| **Préfixe de cache / consigne** | **Oui (deux fois, une par outil)** | `DiagramToolCatalog.GUIDE` + schéma de `render_diagram` (SF-238-06, préfixe Opus) ; enum `action` d'`afficher` (SF-238-10, consigne du cerveau rapide version `4`) ; tests byte-stables avant/après ; rien d'autre. |
| **Livrables existants** | **Oui** | Pages F-109 (SVG en pièce jointe, `PageImageInliner` inchangé), slides F-129 (PNG via `add_picture`), documents, `.drawio` : nouveau rendu pour les **nouveaux** schémas seulement (D3) ; tests de non-régression `tests/test_cloud*.py`, `test_drawio.js`. |
| **Gouvernance** | Oui | `review-checklist.md` : « tout rendu de diagramme passe les gardes G1–G6 » ; DESIGN_SYSTEM : § « Conventions AWS des schémas » (2D) et ajout à §23 (scène d'architecture). |

---

## 17. Découpage — SF-238-01 → SF-238-14 (≤ 2 j chacune, ≈ 25 j)

Chaque SF : coût IA **nul** à l'exécution ; captures de référence ; mini-spec et readiness avant code.

### Itération 0 — « Le schéma 2D cadré » (livrable seul) — 11 j

| SF | Titre | j | Contenu | Critères d'acceptation (vérifiables) |
|---|---|---|---|---|
| **01** | Moteur ELK et SVG maison | 2 | `elkjs` épinglé dans `diagram-renderer` ; module `cadre.js` (spec ⇒ ELK ⇒ SVG avec icônes inlinées, liaisons en polylignes, pointes homogènes, étiquettes) ; chaque zone et chaque nœud porte un **identifiant stable** (`id="zone-<id>"`, `id="node-<id>"`), ce dont la visionneuse SVG de F-237 a besoin pour ses cibles nommées ; interrupteur `APP_DIAGRAM_CLOUD_LAYOUT=elk|graphviz` (défaut `graphviz` jusqu'au visa) ; garde **G1** + **G2**. | Sur les 3 schémas types : **0 segment non orthogonal**, 0 extrémité détachée (G1, G2 en test) ; même entrée ⇒ même SVG octet pour octet ; identifiants `zone-*`/`node-*` présents pour toute zone et tout nœud ; `graphviz` rend exactement comme avant (tests existants verts) ; types inconnus toujours signalés (SF-142-09/12). |
| **02** | Zones AWS conformes | 2 | `kind` + inférence signalée ; table §4.3 ; icônes de groupe officielles (paquet épinglé, note de licence) ; imbrication stricte (parents autorisés), profondeur 6 ; marges ; **G4**, **G6**. | Chaque `kind` rendu avec sa bordure, son trait, son fond, son icône et son libellé en haut à gauche (test sur le SVG) ; Cloud › Région › VPC › AZ › sous-réseau › ASG **accepté** ; AZ hors VPC **refusée** avec la raison ; inférence ⇒ note ; G4/G6 verts. |
| **03** | Composition des zones | 2 | AZ sœurs parallèles et de taille égale, gabarits identiques ⇒ même mise en page interne ; public avant privé ; bande régionale ; acteurs externes à l'entrée ; grille 8 px ; **G5**. | 3-tiers : AZ A et B **côte à côte**, homologues **sur la même ligne** (G5) ; aucune AZ dans le sens du flux ; S3 dans la bande régionale ; utilisateurs hors AWS Cloud ; captures. |
| **04** | Routage inter-zones et étiquettes | 2 | Routeur orthogonal sur grille de canaux (A*, pénalités), traversées perpendiculaires, faisceaux à pas constant, bidirectionnel ; placement des étiquettes ; **G3**. | 0 collision d'étiquette (G3) sur les 3 schémas + le schéma « tout » ; aucune liaison sous une icône ; aucune liaison qui longe une bordure sur plus de 16 px ; ≤ 2 s de rendu pour 60 nœuds / 120 liens. |
| **05** | `drawio` cadré + export du graphe | 1,5 | Le moteur `drawio` reprend les coordonnées (cadres imbriqués, `orthogonalEdgeStyle`) ; le renderer renvoie aussi `ArchitectureGraph` v1 (§5.1) — pas encore stocké. | `.drawio` rouvert dans draw.io : mêmes positions que le SVG (test de structure du XML) ; graphe JSON valide contre son schéma ; polylignes = celles du SVG. |
| **06** | Guidage de l'agent, bascule, planche | 1,5 | `GUIDE` et schéma de l'outil (`kind`, `bidirectional`, conseil TB multi-AZ) ; test byte-stable du préfixe ; planche avant/après finale (3 types + « tout ») ; **visa PO** ⇒ défaut `elk` ; Graphviz gardé une version. | Préfixe byte-stable après modification ; planche publiée ; visa ; en production, compteur G1/G2 à 0 échec sur 7 j (journal). |

### Itération 1 — « L'architecture en 3D » — 9 j

| SF | Titre | j | Contenu | Critères d'acceptation |
|---|---|---|---|---|
| **07** | Graphe conservé | 1,5 | Migration `238-diagram-renders-graph.xml` (colonnes §5.3 sur la table F-237) ; écriture avec la ligne `diagram_renders` (cloud, drawio) ; `GET /api/jarvis/pieces/diag:<id>/graph` (calcul unique depuis `spec_json` si absent) ; `ARCHITECTURE_CANONIQUE.md`. **Après SF-237-03.** | **Isolation A/B** : B ne lit pas le graphe de A (404) ; pas de graphe sans dépôt réussi ; ≤ 40 Ko par graphe ; Mermaid (`DIAGRAM`) n'écrit rien ; une `spec` ancienne donne un graphe identique à un rendu neuf. |
| **08** | Icônes servies et atlas | 1,5 | Proxy `/api/diagrams/icons/{chemin}.png` (liste blanche, cache immuable) ; composition de l'atlas `CanvasTexture`. | Nom hors motif ⇒ 400 ; traversée ⇒ 400 ; icône absente ⇒ boîte neutre (jamais une autre icône) ; atlas de 60 icônes en ≤ 300 ms sur le téléphone du PO. |
| **09** | Scène 3D : plateaux, services, filaments, flux | 2 | `ArchitectureStageDirector` (pur) + rendu : plateaux en terrasses, sprites instanciés, liserés (pointillés selon `kind`), filaments orthogonaux, particules dans le sens du flux, étiquettes ; T-A1, T-A2 ; banc `?banc=architecture`. | Vue de dessus (T-A9) **superposable** au SVG (écart ≤ 2 px à 1920 × 1080) ; 0 segment non orthogonal dans le plan (test sur le plan) ; particules dans le sens `from → to` (test) ; ≤ 20 appels de dessin ; budgets i/s ; captures. |
| **10** | Navigation et visionneuse 3D | 2 | Visionneuse 3D inscrite dans `PIECE_VIEWERS` pour `ARCHITECTURE` ; sous-état `arch` de `PieceNav` ; gestes ; ordres exacts dans `jarvis-piece-voice.ts` ; enum `afficher` étendue (version `4`) ; ligne `## Écran` ; T-A3→A6, A9 ; règles §8.2/§8.3. **Après SF-237-04 et 08.** | Replier puis déplier ⇒ état identique (test) ; aucun saut de position (pas maximal F-236) ; ligne d'état dans le bloc `## Écran` et **jamais** dans la consigne système ; consigne + outils du cerveau rapide byte-stables d'un appel à l'autre ; > 40 nœuds ⇒ AZ jumelles fusionnées ; « éclate » ⇒ **sans IA** ; sans graphe ⇒ visionneuse SVG F-237. |
| **11** | Téléphone, repli 2D, qualité, polissage 1 | 2 | Matrice §14 ; repli SVG (causes F-236 + graphe absent) ; T-A10 ; qualité adaptative ; mode présentation (masquage CIDR/comptes/ARN) ; planche itération 1. | Chaque cause de repli ⇒ SVG cadré (table de vérité) ; téléphone p50 ≥ 50 i/s sur 60 nœuds ; présentation : aucun CIDR/ARN dans le DOM ni le plan ; **visa PO**. |

### Itération 2 — « Jarvis la raconte » — 5 j

| SF | Titre | j | Contenu | Critères d'acceptation |
|---|---|---|---|---|
| **12** | Narration synchronisée | 2 | `architecture-narration.ts` (pur) : noms, types, familles, flux, zones (§11.2) ; branchement sur `spoken` ; T-A7, T-A8 ; ≤ 3 allumés. | Jeu de 30 phrases étiquetées : précision ≥ 95 % (une lueur fausse au plus), aucun allumage sur une famille ambiguë ; allumage ≤ 100 ms après le début de la phrase entendue ; aucun appel réseau. |
| **13** | L'IA montre d'elle-même | 1,5 | Situation I6 de F-237 jouée en 3D : pièce `ARCHITECTURE` la plus récente ⇒ T-A1 + premier cadrage `afficher {action: flux}` ; sans schéma ⇒ proposer d'en rendre un (délégation Opus, F-237 §6.4) ; cas ajoutés au banc du cerveau rapide (SF-237-13). | Banc de 10 questions réelles : la pièce est ouverte quand un schéma existe (≥ 9/10) ; jamais quand la question ne porte pas sur l'infra (0/10 faux positifs) ; aucune consigne système modifiée au-delà de la version `4`. |
| **14** | Polissage final, mesure, planche | 1,5 | Rythme narration/caméra, lisibilité ; mesure (`UsageEvent RENDER` étendu : ouvertures, replis, éclatements, flux allumés, repli 2D par cause) ; planche finale. | Planche ; **visa PO** ; lecture J+7. |

**Ordre** : 01 → 06 (itération 0, déployable seule, **aucune dépendance à F-237**), puis 07 → 11, puis 12 → 14.
Dépendances F-237 : SF-238-07 après SF-237-03 ; SF-238-10 après SF-237-04 et SF-237-08 ; SF-238-13 après SF-237-08 ;
SF-238-08, 09 et 12 n'en dépendent pas (banc sur fixture).

### Plan de test commun

| Niveau | Contenu |
|---|---|
| Renderer (node:test / pytest) | G1–G7 sur 3 schémas types + « tout » + tous les jeux existants ; déterminisme octet pour octet ; refus d'imbrication ; `graphviz` inchangé. |
| Unitaires front (Karma, sans WebGL) | Directeur, passage 2D → 3D (coïncidence en vue de dessus), repli/dépli, sens des particules, narration (30 phrases), résolution locale des commandes, masquage présentation. |
| Intégration backend | Migration, écriture après dépôt, lectures, **isolation A/B** (A crée, B liste vide et 404 sur l'id), proxy d'icônes (motif, traversée, taille), préfixe byte-stable. |
| Visuel | Captures headless (`--use-angle=swiftshader`, 390 × 844 et 1920 × 1080), séquences T-A1/A3/A4/A7 ; planches par itération. |
| Performance | Banc `?banc=architecture` 60 s, 60 nœuds, téléphone et PC du PO. |

---

## 18. Décisions prises par défaut (tranchées, modifiables par le PO)

| # | Décision |
|---|---|
| D1 | La structure est conservée **en exportant au rendu** un `ArchitectureGraph` (structure + mise en page), rangé dans `diagram_renders.graph_json` (table F-237) filtrée `user_id`. Ni parse du SVG, ni rejeu de Python. |
| D2 | Routage : **ELK `layered` + composition des zones maison + routeur orthogonal inter-zones**, dans le renderer ; Graphviz `ortho` écarté sur preuve ; Graphviz `spline` gardé une version en retour arrière. |
| D3 | Pas de re-rendu en masse des schémas existants ; recadrage à la demande, nouvelle version de page. |
| D4 | Pas de 3D pour Mermaid (flowchart, séquence) ; pièces 2D dans la galerie F-237. |
| D5 | Conventions AWS **en 2D** ; grammaire Jarvis **en 3D** (zones distinguées par trait, icône, libellé, altitude). |
| D6 | Profondeur d'imbrication 6 ; `kind` explicite, inféré du libellé à défaut (et signalé). |
| D7 | Mise en page calculée **une fois** au rendu ; la 3D ne fait aucune mise en page ; repli = déplacement en capsule sans re-placement. |
| D8 | Narration par **rapprochement local** sur la phrase **entendue** ; outil de navigation F-237 pour le cadrage initial et les ambiguïtés. |
| D9 | Icônes non recolorées, servies par proxy à cache immuable, atlas côté front. |
| D10 | Mode présentation : CIDR, comptes, ARN masqués ; libellés conservés. |
| D11 | Verbes d'architecture par extension **unique** de l'enum `action` d'`afficher` (F-237), pas de texte libre dans `cible`. |

---

## 19. Questions produit pour le PO (défaut appliqué sans réponse)

| # | Question | Défaut | Alternative |
|---|---|---|---|
| **Q1** | En **3D**, les zones gardent-elles les **couleurs AWS** (VPC violet, sous-réseau public vert, privé bleu) ? | **Non** : tout en orange de Jarvis, la nature se lit par le trait, l'icône de groupe, le libellé et l'altitude ; le vert, le cyan, le violet et le rouge gardent leur sens unique (réussi, attente, Opus, alerte). | Couleurs AWS sur les liserés des plateaux en 3D (au prix d'une grammaire à deux sens). |
| **Q2** | Orientation par défaut d'un schéma **multi-AZ** | **De haut en bas** : AZ en **colonnes** côte à côte (la forme des schémas de référence AWS) ; de gauche à droite pour un schéma sans AZ. | Toujours de gauche à droite, AZ en **bandes** horizontales. |

---

## 20. Mesure d'intérêt (J+7, J+14)

Compteurs par jour, sans contenu (`UsageEvent RENDER` étendu) : schémas `cloud` rendus (et échecs G1/G2), graphes
conservés, pièces `architecture` ouvertes (par l'IA / par le PO), secondes en 3D, replis 2D par cause, éclatements,
replis/déplis, zooms, flux allumés par la narration, commandes vocales résolues sans tour / avec tour.

| Signal | Lecture |
|---|---|
| Échecs G1/G2 en production = 0 | Le cadre tient sur les schémas réels. |
| Recadrages demandés sur d'anciens schémas | Mesure de l'intérêt pour un outil de re-rendu en lot (suite possible). |
| Pièces ouvertes par l'IA puis fermées en < 5 s | Faux positif de « montrer d'elle-même » : resserrer. |
| Pièces Mermaid ouvertes à J+14 | Fonde (ou non) la 3D générique (§13). |

---

## 21. Hors périmètre

- 3D générique pour Mermaid (§13) ; Azure/GCP stylés selon **leurs** conventions (les icônes restent, les zones sont
  `generic`) — suite possible avec la même mécanique `kind`.
- Édition de l'architecture en 3D (déplacer un service) : on **montre**, on ne modifie pas ; l'édition passe par la
  description (F-142 §7).
- Import d'une infra réelle (Terraform, CloudFormation, compte AWS) : suite possible, le graphe en est la cible.
- Re-rendu en lot des schémas existants (D3).
- Tout changement du cerveau F-234/F-235 et de la voix F-233.

## 22. Risques

| Risque | Parade |
|---|---|
| **Routeur maison** (SF-238-04) plus dur que prévu | Gardes G1–G3 dès SF-01 ; repli : liaisons inter-zones routées par ELK sur le graphe complet (0 oblique prouvé par l'essai) en acceptant des AZ moins symétriques ; Graphviz en retour arrière. |
| Symétrie qui **ment** (forcer deux AZ différentes à se ressembler) | Symétrie seulement si gabarits **identiques** ; jamais de composant ajouté ou déplacé hors de sa zone. |
| Livrables clients modifiés à leur insu | D3 : aucun re-rendu d'office ; nouvelle version de page à la demande. |
| Licence des icônes | Paquet officiel épinglé, icônes non modifiées, note de licence ; halo et socle seulement. |
| Narration qui allume à tort | Rapprochement prudent, familles seulement si uniques, ≤ 3 allumés, jeu de 30 phrases ≥ 95 %. |
| Lisibilité des grosses archis en 3D | Bornes F-142 (60 nœuds), AZ jumelles fusionnées, capsules, étiquettes du focus, vue de dessus = 2D cadré. |
| Dépendance à F-237 | Itération 0 indépendante ; SF-08, 09, 12 sur fixture ; SF-07, 10, 13 démarrent après SF-237-03/04/08 ; noms repris tels quels du cadrage F-237. |
| Cache du préfixe | Une seule modification de `GUIDE`, test byte-stable. |

---

## Annexe — essai de l'itération 0

- Descriptions : `docs/features/F-238/essai-iteration-0/specs/{trois-tiers,eks,serverless}.json`.
- Prototype **jetable** (pas du code applicatif) : `essai-iteration-0/prototype-elk.js` (elkjs 0.9.3, icônes de
  `diagrams` 0.24.4) ; garde : `essai-iteration-0/garde-orthogonale.py`.
- Rendu « aujourd'hui » et « Graphviz ortho » : `cloud.py` de l'image `diagram-renderer:staging-a6e9e4a7`, profondeur
  portée à 6 pour l'essai, `splines` et `Edge(xlabel=…)` modifiés pour la variante ortho.
- Captures et planche : `docs/features/F-238/captures/iteration-0/`.
