---
name: pptx
description: À la demande, produit une vraie présentation PowerPoint (.pptx) sur le terminal avec python-pptx — titres, puces, images, tableaux, notes. Échoue clairement, sans traceback nu, si python-pptx est absente.
---

# /pptx

Produit un **vrai fichier `.pptx`** — ouvrable dans PowerPoint, Keynote, LibreOffice — **quand on te
le demande**. On l'écrit avec la bibliothèque Python `python-pptx` et on l'exécute sur le terminal
(`bash`). Ce n'est pas une image d'un slide ni du HTML : c'est le format PowerPoint natif.

## Avant d'écrire quoi que ce soit : où tournes-tu ?

`python-pptx` est une dépendance Python. Selon l'endroit, elle s'installe ou non :

- **Sandbox** (environnement managé) : `pip install python-pptx` est autorisé. Installe-la si elle
  manque, puis produis.
- **Poste d'un client** (souvent une banque) : `pip install` est **fréquemment bloqué** (proxy
  d'entreprise, politique de sécurité). Deux cas :
  - `python-pptx` est déjà présente → produis normalement.
  - elle est absente **et** l'installation échoue → **ne fais pas semblant**. Dis-le clairement :
    « `python-pptx` n'est pas disponible sur ce poste et son installation est bloquée (proxy/politique
    d'entreprise probable). Je peux produire la présentation depuis le sandbox si tu le souhaites. »
    Ne produis **pas** un `.pptx` vide ni un substitut silencieux.

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
           "python-pptx est absente. Sandbox : `pip install python-pptx`. "
           "Poste : si `pip install` est bloqué (proxy/politique d'entreprise), "
           "produire la présentation depuis le sandbox."
       )
   ```

   Si tu peux installer (sandbox), fais-le d'abord : `pip install python-pptx` (elle tire `Pillow`
   pour les images et `lxml`).

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

Pour un **schéma** — architecture cloud/on-prem, flux, séquence —, ne mets pas une image inventée par
une IA (icônes fausses, texte en charabia). On fait du **diagramme-as-code** : exact, éditable,
déterministe. La chaîne est **diagramme Mermaid → image PNG → `add_picture`**, et elle tourne
**entièrement sur le terminal (sandbox), jamais sur le serveur/cluster** — comme le rendu des slides en
images ci-dessous.

1. **Écris le diagramme en Mermaid** (même langage que dans les pages, cf. F-142/SF-142-01) : `flowchart`
   (flux), `sequenceDiagram` (séquence), `architecture-beta` (architecture cloud/on-prem : group,
   service, edge). **FACTUEL (F-119)** : ne dessine que ce qui est **établi** — jamais un composant ni un
   lien inventé ; ce qui est supposé se marque « (supposé) ».

2. **Rends-le en PNG dans le sandbox** avec `mmdc` (mermaid-cli). Mets l'échec **nommé** si l'outil manque
   (jamais de traceback nu) :

   ```bash
   # Sandbox : installe si besoin (tire chromium/puppeteer).
   npm install -g @mermaid-js/mermaid-cli   # fournit `mmdc`
   mmdc -i archi.mmd -o archi.png -b transparent -w 1600
   ```

   - **Échec nommé** : si `mmdc` (ou son chromium) est absent **et** l'installation est bloquée (poste
     verrouillé, proxy d'entreprise), **dis-le** clairement — par exemple :
     « Le moteur de rendu de diagramme (`mmdc`/chromium) n'est pas disponible ici et son installation est
     bloquée. Je peux (a) rendre ce diagramme dans une **page** (rendu navigateur, zéro installation,
     cf. diagrammes en page) et livrer le deck sans cette image, ou (b) insérer le **code Mermaid en zone
     de texte** dans la slide. » **Ne fabrique pas** de fausse image, ne pose pas d'image cassée.
   - Si le code Mermaid est invalide, `mmdc` échoue : signale CE diagramme en échec, n'insère pas d'image,
     livre le reste du deck.

3. **Insère le PNG dans la slide** avec `python-pptx`, avec un **titre** et, si utile, une **légende** :

   ```python
   s = prs.slides.add_slide(prs.slide_layouts[5])   # disposition « titre seul »
   s.shapes.title.text = "Architecture cible — flux d'ingestion"
   s.shapes.add_picture("archi.png", Inches(0.6), Inches(1.4), width=Inches(9))
   # Légende facultative sous le schéma :
   box = s.shapes.add_textbox(Inches(0.6), Inches(6.6), Inches(9), Inches(0.5))
   box.text_frame.text = "Source : carte d'infra du poste (établi). Le lien (supposé) est marqué."
   ```

   Dimensionne l'image pour tenir dans la slide (largeur ~9 pouces sur un deck 10 pouces) ; garde le
   **code Mermaid** (le `.mmd`) à côté du deck : le diagramme reste **éditable et régénérable**.

4. Le reste est identique : le `.pptx` porte la slide, et l'aperçu in-app (ci-dessous) la rend en PNG
   comme les autres — le diagramme est lisible à l'ouverture **et** dans la visionneuse, sans rien
   ajouter côté application.

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
