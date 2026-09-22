# F-142 — Des diagrammes exacts dans les livrables (architecture cloud/on-prem), pas des images hallucinées

> Cadrage du 2026-09-22, à la demande du PO. **Cadrage seul : livraison sur go** (donné : « je suis toutes
> tes recos »). Besoin : des **visuels pour les livrables** (slides, docs) — **diagrammes d'architecture
> AWS/Azure/on-premise**, technique.

## 1. La décision de fond (le « pourquoi pas l'IA d'images »)
Pour un **schéma d'architecture**, la **génération d'images par IA** (OpenAI/DALL·E) est **écartée** :
elle produit des visuels **faux** (icônes cloud inventées, texte en charabia, liens absurdes) —
inutilisables dans un livrable client. **On fait du diagramme-as-code** : exact, **éditable**,
**déterministe** (aucune hallucination), et ça exploite ce que l'app sait déjà faire (**exécuter du code**
+ **produire des pages/slides**).
*(L'IA d'images reste possible plus tard, uniquement pour de l'illustratif/décoratif — hors périmètre ici.)*

## 2. Les voies techniques (du plus léger au plus riche)
| Voie | Icônes cloud | Dépendance | Où ça tourne | Verdict |
|---|---|---|---|---|
| **Mermaid** (`flowchart`, `sequence`, **`architecture-beta`**) | icônes génériques + jeu d'icônes cloud | **rendu dans la page** (lib mermaid) — **rien à installer sur le poste** | navigateur (page) | **Cœur v1** — zéro install côté poste banque |
| **`diagrams` (mingrammer, Python)** | **icônes AWS/Azure/GCP officielles** | **graphviz + python** | sandbox (OK) / poste (souvent bloqué) | **Option** — fidélité maximale, là où graphviz est dispo |
| **D2 / PlantUML** | jeux d'icônes | binaire/serveur | sandbox | non retenu v1 |

**Cœur = Mermaid** (rien à installer sur le poste, exact, éditable). **Option = `diagrams`** pour les icônes
cloud officielles, **dans le sandbox** (le poste CAGIP verrouillé n'a pas forcément graphviz).

## 3. Point d'intégration (à câbler — honnête)
**Mermaid n'est PAS encore rendu dans l'app** (vérifié). Il faut donc **ajouter le rendu Mermaid** là où
vont les livrables :
- **Pages (F-109)** : rendre les blocs Mermaid dans la page. Décision : rendu **côté navigateur** via la lib
  mermaid (si la page autorise un script/CDN) — sinon **pré-rendu serveur → SVG** (plus lourd : nécessite un
  moteur mermaid). **À trancher en mini-spec selon le modèle de rendu réel de F-109** (CSP/scripts).
- **Slides PPTX (F-129)** : un diagramme rendu en **image (PNG/SVG)** inséré dans la slide (python-pptx
  insère une image). Le diagramme est d'abord rendu (mermaid→SVG/PNG, ou `diagrams`→PNG) puis posé.
- **Fichier image** exportable pour insertion manuelle (repli universel).

## 4. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-142-01** | **Diagrammes Mermaid dans les pages (F-109)** | L'agent produit un diagramme **Mermaid** (flux/séquence/`architecture-beta`) depuis une description d'archi ; **rendu dans une page** (F-109), éditable. Câblage du rendu Mermaid (client-side de préférence). C'est le **cœur zéro-install**. |
| **SF-142-02** | **Diagrammes dans les slides (F-129)** | Un diagramme rendu en **image** inséré dans une slide PPTX (feed F-129). Rendu mermaid→image (ou `diagrams`→PNG). |
| **SF-142-03** *(option)* | **Icônes cloud officielles via `diagrams`** | Générer des diagrammes **AWS/Azure/GCP/on-prem** avec les **icônes officielles** (lib `diagrams`, graphviz) — **dans le sandbox** ; sortie image insérable en page/slide. |
| **SF-142-04** *(plus tard)* | **Images illustratives (OpenAI)** | Uniquement décoratif (couverture, illustration) via la clé OpenAI existante — **jamais** pour un schéma d'archi. |

**Ordre** : 142-01 (Mermaid en page, no-regret) → 142-02 (slide) → 142-03 (icônes cloud, option) → 142-04 (plus tard).

## 5. Ce que l'agent doit savoir faire
- Traduire une **description d'architecture** (celle qu'il a de l'infra du poste — cf. carte) en **diagramme
  correct** : composants, zones (VPC/subnets/AZ), flux, on-prem vs cloud.
- Rester **factuel** : ne dessiner que ce qui est **établi** (mêmes règles que F-119 — pas d'invention de
  composants) ; ce qui est supposé est marqué comme tel.
- Produire un artefact **éditable** (le code du diagramme est conservé, pas seulement l'image).

## 6. Sécurité / coût / cloisonnement
- **Mermaid** : rendu local (navigateur) → aucune donnée d'archi ne sort ; **coût nul**. Idéal banque.
- **`diagrams`** : tourne dans le sandbox (pas de sortie tierce) ; coût = compute sandbox.
- **OpenAI images** (142-04) : l'image générée n'est que décorative ; **ne pas** y mettre d'archi sensible.
- Isolation `user_id`+`host_id` ; diagrammes rattachés au projet/sujet.

## 7. Hors périmètre
- Génération d'images IA pour des **schémas d'archi** (écartée — fausse).
- Un éditeur de diagrammes interactif (on **produit** et on **affiche/insère** ; l'édition se fait via le
  code du diagramme, régénéré).

## 8. Préoccupations transversales
- **Composants** : catalogue d'outils (produire un diagramme), rendu Mermaid (front pages F-109 / conversion
  image pour F-129), `diagrams`/graphviz (sandbox, option), stockage/insertion (patron F-109/F-129).
- **Charte** : `DESIGN_SYSTEM.md` pour l'intégration en page (le diagramme lui-même suit les conventions
  cloud, pas la charte de l'app).
- Réf. F-109 (pages), F-129 (pptx), F-119 (ne rien inventer), clé OpenAI existante (STT).
