---
name: pptx
description: À la demande, produit une vraie présentation PowerPoint (.pptx) sur le terminal avec python-pptx — titres, puces, images, tableaux, notes. Échoue clairement, sans traceback nu, si python-pptx est absente.
---

# /pptx

Produit un **vrai fichier `.pptx`** — ouvrable dans PowerPoint, Keynote, LibreOffice — **quand on te
le demande**. On l'écrit avec la bibliothèque Python `python-pptx` et on l'exécute sur le terminal
(`bash`). Ce n'est pas une image d'un slide ni du HTML : c'est le format PowerPoint natif.

## La voie recommandée : la gateway construit le deck

**Décris la présentation et appelle `build_presentation`.** La gateway construit le `.pptx`, le dépose
dans le projet et te rend son chemin ; tu le publies ensuite avec `presentation_publish`.

**Tu n'installes RIEN sur la machine du client** (F-129 / SF-129-05). C'est la même règle que pour les
diagrammes : sur un poste d'entreprise, `pip install` est bloqué — le deck ne se produisait pas.

```
build_presentation(filename: "cible-aws", images: {"archi.png": "archi.png"}, spec: {
  "title": "AGENOR — cible AWS",
  "slides": [
    {"type": "title",   "title": "AGENOR — cible AWS", "subtitle": "Étude, septembre 2026"},
    {"type": "bullets", "title": "Ce qui change",
     "bullets": ["Entrée par WAF puis ALB", "Exécution sur EKS", "Données sur RDS"],
     "notes": "Insister sur le chiffrement en transit."},
    {"type": "image",   "title": "Architecture cible", "image": "archi.png",
     "caption": "Flux nominal, hors sauvegardes"},
    {"type": "table",   "title": "Coûts", "rows": [["Service", "Mensuel"], ["EKS", "420 EUR"]]},
    {"type": "text",    "title": "Prochaine étape", "text": "Valider avec le RSSI."}
  ]
})
```

- **Types de slides** : `title`, `bullets`, `text`, `image`, `table` — chacun accepte `notes`.
- **Les images** viennent de fichiers **déjà déposés dans le projet** : un diagramme rendu par
  `render_diagram`, une image décorative de `generate_image`. Donne leur **chemin** dans `images`.
  Une image absente est **refusée** : produis-la d'abord, ou retire la slide.
- **C'est gratuit** : aucun appel fournisseur, aucun jeton.

## Si le poste a déjà `python-pptx` (voie historique)

Elle reste valable, et **seulement si la bibliothèque est déjà là** — ne l'installe jamais. Le script
`python-pptx` te donne des mises en page plus fines que la description ci-dessus ; c'est son seul
avantage, et il ne vaut pas une installation sur le poste d'un client.

Si `build_presentation` est indisponible **et** que `python-pptx` est absente, **dis-le** :
« La construction de présentations est indisponible ici et `python-pptx` n'est pas présente sur ce
poste. » **Ne produis pas** un `.pptx` vide ni un substitut silencieux.

## Comment procéder

1. **Vérifie la disponibilité — échec nommé si absente.** Mets l'import **en tête du script**, gardé,
   pour que l'absence produise un message clair et non un traceback nu :

   ```python
   import sys
   try:
       from pptx import Presentation
       from pptx.util import Inches, Pt
   except ModuleNotFoundError:
       sys.exit(
           "python-pptx est absente sur cette machine. Utilise build_presentation : "
           "la gateway construit le .pptx sans rien installer ici."
       )
   ```

   **Ne l'installe pas.** Si elle manque, la voie est `build_presentation` — c'est exactement le cas
   qu'elle existe pour couvrir.

2. **Écris le script `python-pptx`.** Une présentation, des slides, des espaces réservés. Les briques
   utiles :

   - **Titre + sous-titre** (disposition 0) et **titre + contenu** (disposition 1) :
     ```python
     prs = Presentation()
     s = prs.slides.add_slide(prs.slide_layouts[0])
     s.shapes.title.text = "Onboarding CI/CD"
     s.placeholders[1].text = "Pour un nouveau — 2026"
     ```
   - **Puces** (plusieurs niveaux) :
     ```python
     s = prs.slides.add_slide(prs.slide_layouts[1])
     s.shapes.title.text = "Le build"
     tf = s.placeholders[1].text_frame
     tf.text = "Déclenché à chaque push sur main"
     p = tf.add_paragraph(); p.text = "Empaquetage en image conteneur"; p.level = 1
     ```
   - **Image** (chemin d'un fichier sur le disque) :
     ```python
     s.shapes.add_picture("schema.png", Inches(1), Inches(1.5), width=Inches(8))
     ```
   - **Tableau** :
     ```python
     rows, cols = 3, 2
     t = s.shapes.add_table(rows, cols, Inches(1), Inches(1.5), Inches(8), Inches(2)).table
     t.cell(0, 0).text = "Étape"; t.cell(0, 1).text = "Preuve"
     ```
   - **Notes du présentateur** :
     ```python
     s.notes_slide.notes_text_frame.text = "Insister sur la clé de signature du runner."
     ```
   - **Enregistrer** :
     ```python
     prs.save("presentation.pptx")
     print("OK: presentation.pptx")
     ```

3. **Exécute-le** sur le terminal et **vérifie** que le fichier existe et n'est pas vide
   (`ls -l presentation.pptx`).

4. **Dis où est le fichier** (chemin complet). C'est ce fichier que l'application capturera pour
   l'afficher et le proposer au téléchargement (F-129).

## Insérer un diagramme dans une slide (schéma d'architecture, flux, séquence)

**La gateway rend le diagramme. Tu n'installes RIEN sur la machine.**

C'est la règle depuis F-142 / SF-142-06 : l'ancienne recette faisait installer un moteur de rendu et un
navigateur sur le poste, ce qu'un poste d'entreprise refuse (proxy, droits, 150 Mo à télécharger) — et
le deck partait sans ses diagrammes. Appelle **`render_diagram`** : l'image est rendue côté gateway, **déposée dans le
projet**, et tu reçois son chemin.

**C'est GRATUIT** : aucun appel fournisseur, aucun jeton — contrairement à `generate_image`. Dessine
dès qu'un schéma aide à comprendre.

1. **Écris le diagramme en Mermaid** — `flowchart` (flux), `sequenceDiagram` (séquence),
   `architecture-beta` (architecture, icônes génériques). **FACTUEL (F-119)** : ne dessine que ce qui
   est **établi** — jamais un composant ni un lien inventé ; ce qui est supposé se marque « (supposé) ».

2. **Appelle `render_diagram`** avec ce code et un `filename` parlant :

   ```
   render_diagram(code: "flowchart LR\n  A[Poste] --> B[Gateway] --> C[(RDS)]",
                  filename: "flux-donnees")
   → « Diagramme rendu par la gateway et déposé dans le projet sous « flux-donnees.png » »
   ```

   - **Échec du rendu** : la réponse dit **pourquoi** (erreur du parseur, service indisponible).
     Corrige ton code, ou — si le service est indisponible — livre le diagramme **dans une page**
     (bloc `<pre class="mermaid">`, rendu par le navigateur, sans rien installer) et dis-le.
     **Ne fabrique pas** de fausse image, ne pose pas d'image cassée. **N'installe rien.**

3. **Insère le PNG dans la slide** avec `python-pptx`, avec un **titre** et, si utile, une légende :

   ```python
   s = prs.slides.add_slide(prs.slide_layouts[5])   # disposition « titre seul »
   s.shapes.title.text = "Architecture cible"
   s.shapes.add_picture("flux-donnees.png", Inches(0.8), Inches(1.6), width=Inches(8.4))
   ```

## Icônes cloud officielles (AWS/Azure/GCP/on-prem) : `engine=cloud`

Pour un livrable client soigné, les **vraies icônes** (AWS, Azure, GCP, on-prem) valent mieux que les
formes génériques. Là encore, **la gateway s'en charge** — plus de `pip`, plus de `graphviz`, plus rien
sur la machine du client (F-142 / SF-142-07).

Tu écris une **description**, jamais du code :

```
render_diagram(engine: "cloud", filename: "cible-aws", spec: {
  "title": "Cible AWS",
  "direction": "LR",
  "groups": [{"id": "vpc", "label": "VPC production"}],
  "nodes": [
    {"id": "u",   "type": "onprem.users",   "label": "Agents"},
    {"id": "alb", "type": "aws.alb",        "label": "ALB",            "group": "vpc"},
    {"id": "ecs", "type": "aws.ecs",        "label": "ECS Fargate",    "group": "vpc"},
    {"id": "rds", "type": "aws.rds",        "label": "RDS PostgreSQL", "group": "vpc"}
  ],
  "edges": [{"from": "u", "to": "alb", "label": "HTTPS"},
            {"from": "alb", "to": "ecs"}, {"from": "ecs", "to": "rds"}]
})
```

- **Les types** ont la forme `famille.service` : `aws.alb`, `aws.ecs`, `aws.eks`, `aws.lambda`,
  `aws.rds`, `aws.aurora`, `aws.dynamodb`, `aws.s3`, `aws.sqs`, `aws.sns`, `aws.cloudfront`,
  `aws.apigateway`, `aws.iam`, `aws.secretsmanager`… et de même pour `azure.*`, `gcp.*`, `onprem.*`
  (`onprem.postgresql`, `onprem.kafka`, `onprem.nginx`, `onprem.users`…), plus `k8s.*`.
- **Un type inconnu est refusé** avec la liste des types proches : lis la réponse, elle t'apprend le
  vocabulaire. **N'invente pas** un type, et ne remplace pas un composant par un autre « qui y
  ressemble » — un schéma faux est pire qu'un schéma absent.
- **Un lien vers un nœud non déclaré est refusé** : déclare d'abord, relie ensuite.

**Quand choisir quoi** : `engine=cloud` pour une architecture cloud destinée au client (icônes
officielles) ; Mermaid pour un flux, une séquence, un enchaînement logique, ou une architecture
générique. Dans les deux cas, **rien ne s'installe nulle part**.


## Images décoratives (génération IA) : `generate_image`, ornement seulement

Pour un **ornement** — couverture du deck, visuel d'ambiance d'une slide, bandeau —, tu peux **générer
une image** avec l'outil **`generate_image`** : la gateway relaie un fournisseur d'images, range l'image
et la **dépose dans le projet** ; réfère le chemin rendu en `add_picture` (slide) ou en pièce jointe
(page). C'est un service **payant et borné** (nombre par tour, quota de compte) et il demande l'accord de
l'utilisateur — n'en génère que lorsqu'une illustration sert vraiment.

**FRONTIÈRE ABSOLUE — décoratif uniquement.** N'utilise **JAMAIS** `generate_image` pour un **schéma
d'architecture** ni un diagramme technique : l'IA d'images invente des icônes, du texte en charabia, des
liens absurdes — inutilisable en livrable. Un schéma reste du **diagramme-as-code** : Mermaid (recette
ci-dessus) ou la lib `diagrams` (icônes cloud officielles). Décoratif = `generate_image` ; technique =
diagramme-as-code.

## Pour l'aperçu dans l'application (lisible slide par slide)

Le `.pptx` se **télécharge** toujours. Pour que l'utilisateur **lise le deck entièrement dans l'app**
(slide par slide, sans ouvrir PowerPoint), rends-le aussi en **images PNG**, une par slide.

- **Où ça tourne** : là où tu travailles — de préférence le **sandbox**, où tu peux installer
  LibreOffice. **N'ajoute jamais** de service de conversion sur le serveur/cluster ; la conversion est
  locale à ton terminal.
- **La recette** (standard et robuste) :
  ```bash
  soffice --headless --convert-to pdf presentation.pptx   # -> presentation.pdf
  pdftoppm -png -r 150 presentation.pdf slide             # -> slide-1.png, slide-2.png, ...
  ```
  (Si `pdftoppm` manque, `pdftocairo -png` ou `magick -density 150 presentation.pdf slide.png` font
  l'affaire.) Garde-les **dans l'ordre des slides**.
- **Échec nommé** : si LibreOffice n'est pas disponible et ne peut pas être installé (poste verrouillé),
  **dis-le** — la présentation reste téléchargeable, seul l'aperçu manque. Ne fabrique pas de fausses
  images.

Ensuite, l'application capture le tout (le `.pptx` **et** les PNG dans l'ordre) : le deck devient
lisible en grand avec ses miniatures.

## Ce que ça produit

Un fichier `.pptx` réel, dans le répertoire de travail, avec un chemin annoncé clairement — et, quand
tu as rendu les slides, une image PNG par slide pour l'aperçu in-app.

## Ce qu'il ne faut pas faire

- **Ne réimplémente pas** un moteur de présentation : `python-pptx` fait le format PowerPoint,
  sers-t'en. On **relaie** une capacité, on ne la reconstruit pas.
- **Ne produis pas** un substitut silencieux (HTML, capture d'écran, `.pptx` vide) quand la lib
  manque. Nomme l'échec.
- **N'inclus pas** d'animations/transitions : `python-pptx` les gère mal (hors sujet v1).
- **Ne bloque pas** sur des polices exotiques : reste sur les polices par défaut de la disposition,
  le rendu doit être portable.
