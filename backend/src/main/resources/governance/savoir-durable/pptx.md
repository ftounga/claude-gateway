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
