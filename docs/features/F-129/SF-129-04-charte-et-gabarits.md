# Mini-spec — F-129 / SF-129-04 — Charte & gabarits du deck

## Identifiant
`F-129 / SF-129-04` — feature parente `F-129`

## Statut
`ready`

## Date de création
2026-09-26

## Branche Git
`feat/SF-129-04-charte-gabarits`

## Objectif
Qu'un deck construit par la gateway sorte **à la charte de l'application** (navy / orange, titres et
pied de page cohérents) au lieu du gabarit blanc par défaut de PowerPoint.

## Le défaut
Depuis SF-129-05, la gateway construit le `.pptx` elle-même (`diagram-renderer/deck.py`) à partir
d'une description. Elle le construit avec le **thème Office par défaut** : Calibri noir sur blanc,
aucun repère de marque, aucune numérotation. Un livrable remis à un client ressemble à un brouillon,
alors que la charte est déjà écrite et tenue partout ailleurs (`docs/DESIGN_SYSTEM.md`).

C'est la SF marquée « option » du cadrage. Elle le reste sur le fond (personne n'est bloqué), mais
elle est devenue **bon marché** : le point d'application est unique — un seul constructeur, côté
gateway — alors qu'à l'époque du cadrage le deck était fabriqué sur le poste, où il aurait fallu
faire porter la charte par un script écrit par le modèle.

## Ce qu'on écarte, et pourquoi
- **Un fichier `.potx` de marque** (masques PowerPoint) : il faudrait le maintenir en binaire dans le
  dépôt, invisible en revue, et chaque évolution de charte deviendrait illisible dans un diff. Le
  gabarit est décrit **en code**, comme le reste de la charte.
- **Laisser le modèle choisir les couleurs** : la charte n'est pas une préférence de l'agent. Elle
  s'applique par défaut ; l'agent choisit seulement entre la charte et le thème neutre.

## Comportement attendu

### Cas nominal
1. `build_presentation` accepte un champ facultatif **`theme`** dans la description :
   `"cg"` (la charte, **défaut**) ou `"plain"` (le gabarit Office d'origine, inchangé).
2. Sous `"cg"`, chaque slide sort à la charte : fond clair `#F5F6FA`, titres en navy `#0B1020`,
   **filet d'accent orange** `#E07B39` sous le titre, texte en `#0F172A`, police Arial (substitut
   universel d'Inter, qui n'est pas installée sur un poste client).
3. La slide de **titre** est inversée : fond navy plein, titre blanc, sous-titre orange clair.
4. Chaque slide de contenu porte un **pied de page** : le titre du deck à gauche, le **numéro de
   slide** à droite, en gris `#64748B`.
5. Le reste ne bouge pas : les types de slides, les bornes, les images, les notes, le dépôt dans le
   projet, `presentation_publish`.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `theme` inconnu (`"dark"`, `"corp"`…) | refus **nommé** qui énumère les thèmes connus ; aucun fichier produit |
| `theme` absent ou vide | la charte s'applique — c'est le défaut, pas une erreur |
| Slide sans titre sous la charte | comportement inchangé (titre vide accepté) : le filet et le pied de page s'affichent quand même |

## Critères d'acceptation
- [ ] Un deck construit **sans** `theme` sort à la charte : fond `#F5F6FA`, titre navy, filet orange.
- [ ] La slide `title` sort en **navy plein**, titre blanc.
- [ ] Chaque slide de contenu porte le **numéro de slide** et le titre du deck en pied de page.
- [ ] `theme: "plain"` produit **exactement** l'ancien rendu (aucun fond posé, aucun filet, aucun pied).
- [ ] Un `theme` inconnu est **refusé avec son nom** et la liste des thèmes connus ; rien n'est produit.
- [ ] Aucune couleur hors `docs/DESIGN_SYSTEM.md` n'entre dans le code.
- [ ] Le fichier produit reste un **OOXML ouvrable** (vérifié sur un vrai `.pptx`).

## Plan de test minimal
- **Unitaires (Python, `deck.py`)** : la charte est appliquée par défaut ; `plain` ne pose ni fond ni
  filet ; un thème inconnu lève un refus nommé ; le pied de page numérote les slides ; le fichier
  produit s'ouvre (relecture `Presentation(...)`, nombre de slides, présence des formes).
- **Unitaires (Java)** : `DeckToolCatalog` annonce `theme` dans le schéma et la doctrine ; le guide
  dit que la charte est le défaut. `DeckToolExecutor` transmet `theme` **tel quel** au constructeur
  (la validation appartient au constructeur, un seul endroit).
- **Isolation** : inchangée — aucune donnée nouvelle n'est lue ni écrite ; l'isolation du tour
  (`userId` + `workspace`) portée par `ProjectFileRead`/`ProjectFileDeposit` reste la seule voie.
- **Non-régression** : les tests `DeckToolExecutorTest` existants restent verts sans modification.

## Impacts
| Élément | Changement |
|---|---|
| `diagram-renderer/deck.py` | thème `cg` (défaut) / `plain`, fond, filet, pied de page, polices |
| `diagram-renderer/tests/test_deck.py` *(nouveau)* | la couverture Python de la construction |
| `backend/.../decks/DeckToolCatalog.java` | `theme` au schéma + une ligne de doctrine |
| `backend/.../decks/DeckToolExecutor.java` | transmet `theme` (aucune logique de rendu côté Java) |
| Tables | **aucune** — aucune migration |
| Endpoints | **aucun** |
| Composants Angular | **aucun** (la visionneuse affiche déjà les slides) |
| Runner | **aucune** mise à jour |

## Hors périmètre
- Les **masques et thèmes fins** de PowerPoint (couleurs de tableau héritées, jeux de polices OOXML).
- Un thème **choisi par l'utilisateur** dans l'écran : la charte est celle de l'application.
- Le `.docx` / `.xlsx` (SF-129-07, plus tard).
- L'aperçu par slides rendu par la gateway (SF-129-06).

## Préoccupations transversales
- **Auth / Principal** : aucune. **Contexte tenant** : aucun changement — aucun accès données nouveau.
- **Plans / limites** : aucune borne nouvelle ; les bornes existantes de `deck.py` sont conservées.
- **Navigation / routing** : aucune route.

## Drapeau de déploiement
Le constructeur vit dans l'image `diagram-renderer` : la charte n'apparaît **qu'après reconstruction
et déploiement de cette image**. Aucun déploiement dans cette PR (règle du lot).
