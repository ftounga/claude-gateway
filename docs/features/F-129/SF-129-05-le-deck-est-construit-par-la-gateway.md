# Mini-spec — F-129 / SF-129-05 — Le deck est construit par la gateway

## Identifiant
`F-129 / SF-129-05` — feature parente `F-129`

## Objectif
Qu'une présentation `.pptx` puisse être produite **sans que le poste du client installe `python-pptx`**.

## Le défaut
> PO, 2026-09-24, à la suite de F-142 : *« oui cadre et livre ça aussi »*.

Les **diagrammes** sont désormais rendus par la gateway (F-142 / SF-142-06 et 07). Le **fichier**, lui,
est toujours fabriqué **sur le poste** : le skill commence par

```python
from pptx import Presentation   # absente ⇒ échec nommé
```

et propose, quand `pip install` est bloqué, de *« produire la présentation depuis le sandbox »*. Chez
un client comme CAGIP, `pip` **est** bloqué : les diagrammes se rendront très bien, et le deck ne se
produira pas. C'est le même défaut que celui qu'on vient de corriger, déplacé d'un cran.

## Ce qu'on écarte, et pourquoi
**Exécuter le Python de l'agent sur la gateway.** Même refus qu'en SF-142-07, pour la même raison :
c'est du code écrit par le modèle, et notre infrastructure n'est pas son bac à sable. La gateway
reçoit une **description de deck** et construit le fichier elle-même.

## Comportement attendu
1. Un outil **`build_presentation`** prend une **description** (titre, slides typées, puces, images,
   notes) et rend un `.pptx` **déposé dans le projet**.
2. L'agent enchaîne ensuite avec `presentation_publish` — chemin **inchangé**, aucun doublon.
3. Les **images déjà déposées** (diagrammes rendus, images décoratives) s'insèrent par leur **chemin**.
4. Le poste n'exécute **rien** : ni Python, ni installation.
5. Si `python-pptx` est **déjà** présente sur le poste, l'ancienne voie reste possible — on **ajoute**
   un chemin, on n'en retire aucun.

| Cas d'erreur | Comportement |
|---|---|
| Description invalide (slide sans type, image inconnue) | refus **nommé**, avec ce qui manque ; aucun fichier produit |
| Image référencée absente du projet | refus nommé — un deck avec une image manquante est pire qu'un deck sans image |
| Description démesurée | refus borné **avant** production |
| Service indisponible | échec nommé ; l'agent peut retomber sur `python-pptx` **s'il est présent**, sinon il le dit |

## Critères d'acceptation
- [x] `build_presentation` produit un `.pptx` valide depuis une description, sans rien exécuter sur le poste.
- [x] Les slides couvrent l'essentiel : **titre**, **titre + puces**, **image**, **tableau**, **notes**.
- [x] Une image déposée dans le projet (diagramme, visuel) s'insère par son **chemin**.
- [x] Le fichier est **déposé dans le projet** ; `presentation_publish` le range comme aujourd'hui.
- [x] Les refus sont **nommés** (description invalide, image absente, bornes).
- [x] **Aucun code fourni n'est exécuté** — prouvé par test.

## Hors scope
L'**aperçu** slide par slide (SF-129-03) qui convertit via LibreOffice **sur le poste** : même famille
de problème, à traiter séparément · le `.docx` · les mises en page fines (thèmes, masques).

## Technique
| Élément | Changement |
|---|---|
| `diagram-renderer/deck.py` *(nouveau)* | construit le `.pptx` avec `python-pptx` **à partir de la description** |
| `diagram-renderer/Dockerfile` | + `python-pptx` |
| `diagram-renderer/server.js` | `POST /presentation` |
| `DeckBuilder` *(interface)* + `HttpDeckBuilder` | Provider Independence, comme `DiagramRenderer` |
| `DeckToolCatalog` / `DeckToolExecutor` | l'outil, et le dépôt **réutilisé** (`ProjectFileDeposit`) |
| `pptx.md` | la description devient la voie **recommandée** ; `python-pptx` reste documentée pour un poste qui l'a |

**Bornes** : 60 slides · 40 lignes par slide · 400 caractères par ligne · 8 Mio pour le fichier ·
20 images référencées.

**Les images** : l'agent donne un **chemin du projet** ; la gateway le **lit** (poste ou hébergé) et
l'insère. Aucun chemin absolu, aucune remontée de dossier.

## Plan de test
### Service — éprouvé sur l'image réelle
- [x] Une description de 5 slides rend un `.pptx` **ouvrable** : 5 slides, **1 image embarquée**,
      2 notes — vérifié en ouvrant le ZIP OOXML produit.
- [x] Les cinq types de slides produisent le bon contenu.

### Gateway — `DeckToolExecutorTest`, 7 verts
- [x] La gateway construit et dépose ; la réponse renvoie vers `presentation_publish`.
- [x] Les images sont **lues dans le projet** (sous l'isolation du tour) puis transmises encodées.
- [x] **ISOLATION** : chemin absolu (`/etc/passwd`) et remontée (`../../`) **refusés sans rien lire**.
- [x] Image absente → refus nommé qui renvoie à `render_diagram` ; rien n'est construit.
- [x] Service indisponible → l'ancienne voie reste possible **si elle est déjà là**, jamais installée.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | Le service reçoit désormais des **images du projet** : elles transitent par la gateway, qui les lit sous l'isolation du tour. Le pod reste sans sortie (NetworkPolicy de SF-142-07). |
| **Contexte tenant** | **oui** | Lecture des images et dépôt du deck : `Workspace` du tour, jamais un chemin du modèle. |
| Plans / limites | non | aucun appel fournisseur, aucun jeton |
| Navigation / routing | non | aucun écran ; `presentation_publish` inchangé |
