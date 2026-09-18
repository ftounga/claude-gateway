# F-129 — Produire des présentations PPTX et les lire entièrement dans l'app

> Cadrage du 2026-09-18, à la demande du PO. **Cadrage seul : la livraison attend le go du PO.**
> Prolonge F-109 (pages HTML générées) au format **PowerPoint**, « exactement comme Claude Code ».

## 1. Le besoin
> « Tu as permis que l'app fasse du HTML ; on peut aussi lui faire faire des **pptx** ? Exactement comme
> Claude Code. Et la présentation CI/CD en plusieurs étapes aurait été un bon candidat. »
> Précision : *« le ppt doit être lisible **entièrement dans l'appli** »* + téléchargement.

L'agent doit pouvoir **produire une vraie présentation `.pptx`** (comme Claude Code le fait via sa skill
pptx / `python-pptx`), l'utilisateur doit pouvoir la **lire en entier dans l'app** (toutes les slides,
pas une vignette) **et** la **télécharger** (le vrai fichier, ouvrable dans PowerPoint/Keynote).

## 2. Ce qui existe déjà (on ne réinvente pas)
« Comme Claude Code » = une **skill** qui écrit du code `python-pptx` et l'**exécute**. Les deux briques
sont déjà là :
- **bash activé par défaut** : le runner exécute du code sur le poste (SF-38-19) ;
- **dépôt de skills** : les paquets de gouvernance posent des `.claude/skills/` (F-51/F-52) ;
- **artefacts générés + visualisation** : F-109 (pages HTML) a déjà le patron « l'agent produit → l'app
  affiche/récupère ».

Donc l'agent peut **déjà** produire un `.pptx` aujourd'hui si on le lui demande. F-129 apporte
l'**expérience de première classe** : une skill packagée, la **capture** du fichier dans l'app, et
surtout une **visionneuse de deck complète**.

## 3. Décisions structurantes (tranchées avec le PO)
| # | Sujet | Décision |
|---|-------|----------|
| D1 | Production | **Skill `pptx` (`python-pptx`)** déposée par le paquet de gouvernance ; l'agent écrit un script et le lance sur le terminal (poste ou sandbox). **Provider-First** : capacité relayée (exécution de code), **aucun** moteur IA réimplémenté. **Gateway-First** : le backend orchestre/stocke/affiche. |
| D2 | Lecture in-app | **Deck lisible entièrement dans l'app** : conversion **serveur** du `.pptx` en **une image par slide** (LibreOffice headless, en **worker** — traitement lourd async) ; l'app rend une **visionneuse** (slide courante grand format + miniatures + navigation clavier/flèches). |
| D3 | Récupération | **Téléchargement du vrai `.pptx`** toujours disponible, à côté de l'aperçu. |
| D4 | Périmètre | **PPTX d'abord** ; `docx`/`xlsx` = même moteur/patron, **plus tard** (SF-129-05). |

## 4. Ce que ça produit (l'artefact « présentation »)
Un artefact **présentation** rattaché à un **projet/sujet** (isolation `user_id` + `host_id`), composé de :
- le **fichier `.pptx`** source (téléchargeable, stocké en objet — patron F-109) ;
- le **rendu par slides** (images), produit par la conversion serveur ;
- des **métadonnées** : titre, nombre de slides, date, sujet/projet.

## 5. Découpage unitaire
| SF | Titre | Contenu | Écran ? |
|---|---|---|---|
| **SF-129-01** | **Skill `pptx` : l'agent produit un vrai .pptx** | Skill `.claude/skills/pptx` (recette `python-pptx` : titres, puces, images, tableaux, notes ; charte optionnelle) déposée par le paquet de gouvernance ; disponibilité/installation de `python-pptx` gérée et **échec nommé** si absente. L'agent produit un `.pptx` sur le terminal. | — |
| **SF-129-02** | **Capturer la présentation dans l'app + téléchargement** | Le `.pptx` produit devient un **artefact** rattaché au projet/sujet (upload → stockage objet, patron F-109), listé, avec **bouton Télécharger** (le vrai fichier). Isolation `user_id`+`host_id`, refus nommés. | ✅ |
| **SF-129-03** | **Visionneuse de deck complète (lisible entièrement)** | Conversion serveur `.pptx` → **images par slide** (LibreOffice headless, **worker** async ; bornes taille/nombre) ; **visionneuse** in-app : slide courante en grand, **miniatures** de toutes les slides, navigation (flèches/clavier), plein écran. C'est l'exigence « lisible entièrement dans l'appli ». | ✅ |
| **SF-129-04** *(option)* | **Charte & gabarits** | Un gabarit de marque (navy/gold `DESIGN_SYSTEM.md`) et quelques dispositions prêtes (titre, section, contenu, comparaison) pour des decks cohérents sans repartir de zéro. | partiel |
| **SF-129-05** *(plus tard)* | **DOCX & XLSX** | Même moteur/patron (`python-docx`, `openpyxl`) + rendu/lecture in-app adaptés (Word : pages ; Excel : feuilles/tableaux). La « suite Office comme Claude Code ». | ✅ |

**Ordre** : SF-129-01 → 02 → 03 (chaîne de valeur v1) → 04 (option) → 05 (plus tard).

## 6. Faisabilité & points durs
- **Conversion fidèle** : `python-pptx` **produit** mais ne **rend** pas. Le rendu fidèle passe par
  **LibreOffice headless** (`soffice --headless --convert-to pdf`, puis PDF→images), standard et robuste.
  **Drapeau/packaging** : LibreOffice est **lourd** (~centaines de Mo) ; décision par défaut = **worker /
  conteneur dédié** à la conversion (ne pas alourdir l'image backend principale). Alternatives notées si
  le coût gêne : conversion sur le poste si LibreOffice présent, ou service de conversion.
- **Async** : la conversion est un **traitement lourd** → worker (règle CLAUDE.md), jamais synchrone.
- **Où l'agent produit** : sur le **poste** (runner) → fichier local puis uploadé ; ou en **sandbox** →
  directement stocké. Les deux couverts (le terminal ouvert décide).

## 7. Sécurité & cloisonnement
- Isolation `user_id` + `host_id` sur l'artefact et le fichier.
- Le `.pptx` et son rendu sont des **livrables**, la génération suit le droit du terminal.
- Bornes taille/nombre de slides pour le coût (stockage + conversion + jetons).

## 8. Hors périmètre (v1)
- DOCX/XLSX (SF-129-05, plus tard).
- Édition du deck **dans** l'app (on **produit** et on **lit** ; l'édition fine se fait dans PowerPoint
  après téléchargement, ou en redemandant à l'agent).
- Animations/transitions PowerPoint (python-pptx les gère mal ; hors scope).

## 9. Préoccupations transversales
- **Navigation / routing** : un espace « Présentations » (ou intégré à l'onglet Pages F-109) — routes à
  lister en mini-spec.
- **Plans / limites** : production + conversion + stockage consomment quota ; bornes.
- **Composants** : paquet de gouvernance (skill pptx), catalogue d'outils/terminal (production),
  stockage objet (patron F-109), **worker de conversion** (LibreOffice), backend (artefact + endpoints),
  frontend (visionneuse de deck + téléchargement).

## 10. Références
- **F-109** (pages HTML générées — patron produire→afficher→récupérer), **F-51/F-52** (skills &
  gouvernance), **SF-38-19** (bash par défaut).
