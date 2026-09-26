# Mini-spec — F-129 / SF-129-06 — L'aperçu des slides est rendu par la gateway

## Identifiant
`F-129 / SF-129-06` — feature parente `F-129`

## Statut
`ready`

## Date de création
2026-09-26

## Branche Git
`feat/SF-129-06-apercu-par-la-gateway`

## Arbitrage préalable — la collision de numéro SF-129-05
Le cadrage réservait **SF-129-05** au `.docx`/`.xlsx` (« plus tard ») ; la livraison du 2026-09-24
(PR #823) a pris ce numéro pour « le deck est construit par la gateway ». **Deux subfeatures
portaient le même numéro.**

**Tranché ici** : le numéro suit **ce qui est livré**, jamais ce qui est prévu — renuméroter une
livraison rendrait faux tout ce qui la cite (commits, PR, historique du PRODUCT_SPEC, en-têtes de
code). Donc :

| Numéro | Sujet | État |
|---|---|---|
| SF-129-05 | Le deck est construit par la gateway | **livrée** (PR #823) — inchangée |
| **SF-129-06** | **L'aperçu des slides est rendu par la gateway** | **cette subfeature** |
| **SF-129-07** | `.docx` / `.xlsx` | **plus tard** (l'ancien « SF-129-05 » du cadrage) |

Le cadrage `CADRAGE-F-129-produire-et-lire-des-pptx.md` est corrigé dans la même PR.

## Objectif
Qu'une présentation soit **lisible entièrement dans l'application** même sur un poste verrouillé —
c'est-à-dire sans LibreOffice ni `pdftoppm` sur la machine du client.

## Le défaut
C'est le **dernier maillon** du défaut traité en SF-129-05 et SF-142-06/07. Aujourd'hui :

- le **fichier** est construit par la gateway (SF-129-05) — le poste n'installe rien ;
- l'**aperçu** (une image PNG par slide, ce qui fait la visionneuse SF-129-03) est toujours produit
  **sur le poste**, par `soffice --convert-to pdf` puis `pdftoppm -png`.

Chez un client où l'installation est bloquée (CAGIP), LibreOffice n'est pas là : le deck se produit,
se publie, se télécharge — et la visionneuse affiche son repli. L'exigence du PO (« le ppt doit être
lisible **entièrement dans l'appli** ») tombe précisément là où la feature devait la tenir.

## Ce qu'on écarte, et pourquoi
- **LibreOffice dans le cluster** (le cadrage D2 d'origine) : plusieurs centaines de Mo d'image pour
  un pod de conversion, sur `legalcase-shared` **à capacité**. Déjà écarté en SF-129-03, et cette
  raison n'a pas changé. Si le rendu **fidèle au fichier** devenait nécessaire, ce serait un worker
  scale-to-zero, borné, **validé par le PO** — hors de ce lot.
- **Exécuter le Python ou le script du modèle sur la gateway** : même refus qu'en SF-142-07 et
  SF-129-05.

**Ce qu'on fait à la place** : la gateway rend l'aperçu **depuis la description** — la même
description qui a produit le fichier —, avec le **chromium déjà présent** dans l'image du service de
rendu (il y est pour Mermaid). Aucune dépendance nouvelle, aucun pod nouveau.

**Ce que cela coûte, dit franchement** : l'aperçu est une **vue de la description**, pas une capture
du `.pptx`. Il porte la même charte, le même texte, les mêmes images, le même ordre — mais une
retouche faite **dans PowerPoint après téléchargement** n'y apparaîtra pas. Le `.pptx` reste la
source ; l'aperçu sert à **lire dans l'app**. Là où LibreOffice **est** présent, l'ancienne voie
reste possible et reste plus fidèle : on ajoute un chemin, on n'en ferme aucun.

## Comportement attendu

### Cas nominal
1. `build_presentation` rend, **en plus** du `.pptx`, **une image PNG par slide** (1280×720),
   produite par le service de rendu à partir de la description.
2. Les PNG sont **déposés dans le projet** à côté du deck, nommés `<deck>-slide-01.png`, … dans
   l'ordre des slides — même dépôt, même isolation que le `.pptx` (`ProjectFileDeposit`).
3. L'outil rend à l'agent **le chemin du deck et ceux des images**, et lui dit de les passer dans
   `slides` à `presentation_publish` — le chemin de publication existant (SF-129-03) ne change pas.
4. L'utilisateur ouvre la visionneuse (SF-129-03) et lit **toutes** les slides, sur n'importe quel
   poste. Le téléchargement du vrai `.pptx` reste offert, inchangé.
5. `preview: false` dans l'appel (ou un deck de plus de **30** slides) ⇒ **pas d'aperçu**, le deck
   est produit comme avant, et l'outil **le dit**.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Le rendu d'aperçu échoue (chromium absent, timeout) | le **deck reste produit et déposé** ; l'outil dit que l'aperçu manque et que le téléchargement reste offert — jamais d'échec du deck pour un aperçu |
| Le dépôt d'une image d'aperçu échoue | on s'arrête à l'image précédente : un aperçu **partiel mais ordonné** vaut mieux qu'une suite trouée ; l'outil dit combien d'images sont posées |
| Plus de 30 slides | aperçu **non produit**, raison **nommée** (borne), deck produit |
| Une image d'aperçu dépasse la borne (2 Mo) | l'aperçu est **refusé** par la gateway, le deck reste produit |
| Le service de construction est muet | inchangé (SF-129-05) : échec **nommé**, repli possible sur `python-pptx` **s'il est déjà présent** |

## Critères d'acceptation
- [ ] Un deck construit par `build_presentation` produit **une image par slide**, dans l'ordre.
- [ ] Les images sont **déposées dans le projet** et l'outil rend **leurs chemins**, prêts pour
      `slides` de `presentation_publish`.
- [ ] La visionneuse SF-129-03 affiche ces slides **sans rien installer sur le poste** (aucun appel
      à `soffice` ni `pdftoppm` dans le chemin nominal).
- [ ] Un **échec d'aperçu ne fait pas échouer le deck** : le fichier est déposé, le message le dit.
- [ ] `preview: false` produit le deck **sans** aperçu (comportement d'avant, à l'octet près côté
      description envoyée).
- [ ] Au-delà de **30 slides**, l'aperçu est **refusé avec sa raison** et le deck est produit.
- [ ] Une image d'aperçu au-dessus de **2 Mo**, ou plus de 30 images rendues, est **refusée** par la
      gateway (borne côté client du service, pas seulement côté service).
- [ ] **Isolation** : les images ne sont écrites que par `ProjectFileDeposit`, sous le `userId` et le
      `Workspace` du tour — aucun chemin ne vient du modèle.

## Plan de test minimal
- **Unitaires (Python, `deck.py`)** : l'aperçu produit **un HTML par slide**, dans l'ordre, avec le
  titre et les puces de la description ; les images de la description y sont **embarquées** ; le
  texte est **échappé** (un `<script>` dans un titre reste du texte) ; sans `preview`, **aucun**
  fichier d'aperçu n'est écrit.
- **Unitaires (Java, `HttpDeckBuilder`)** : une réponse JSON `{pptx, slides[]}` est lue (deck +
  images) ; une réponse **binaire** reste lue comme avant (compatibilité) ; une image au-dessus de la
  borne est **refusée** ; plus de 30 images sont **refusées** ; une réponse 422 reste un refus nommé.
- **Unitaires (Java, `DeckToolExecutor`)** : les images sont déposées **dans l'ordre** et leurs
  chemins rendus ; un dépôt en échec donne un aperçu **partiel** et un message qui le dit ; un deck
  sans aperçu garde exactement son message d'avant ; `preview: false` n'envoie pas de demande
  d'aperçu ; **isolation** — le dépôt passe par `ProjectFileDeposit` avec le `userId`/`Workspace` du
  tour, jamais par un chemin du modèle.
- **Intégration** : aucune nouvelle route ; le chemin de publication (`presentation_publish`,
  `PresentationService.attachSlides`, `GET /api/presentations/{id}/slides/{i}`) est **inchangé** et
  ses tests existants (dont l'isolation cross-user 404) restent verts sans modification.

## Impacts
| Élément | Changement |
|---|---|
| `diagram-renderer/deck.py` | émet un **HTML par slide** quand l'aperçu est demandé (même contenu, même charte) |
| `diagram-renderer/server.js` | rend ces HTML en **PNG** avec le chromium déjà présent ; réponse **JSON** `{pptx, slides[]}` quand l'aperçu est demandé, binaire sinon |
| `backend/.../decks/DeckBuilder.java` | `Deck(byte[] bytes, List<byte[]> slides)` |
| `backend/.../decks/HttpDeckBuilder.java` | lit les deux formes de réponse, **borne** les images |
| `backend/.../decks/DeckToolExecutor.java` | demande l'aperçu, **dépose** les images, rend leurs chemins |
| `backend/.../decks/DeckToolCatalog.java` | `preview` au schéma + doctrine (publier les slides) |
| Tables | **aucune** — aucune migration (`slide_count` existe depuis SF-129-02) |
| Endpoints | **aucun** |
| Composants Angular | **aucun** — la visionneuse SF-129-03 affiche déjà les slides |
| Runner | **aucune** mise à jour (`write_file_bytes`/dépôt hébergé existants) |

## Hors périmètre
- Le rendu **fidèle au fichier** (LibreOffice) : écarté ci-dessus, resterait un worker validé par le PO.
- L'aperçu d'un deck produit **hors** `build_presentation` (script `python-pptx` sur un poste qui
  l'a déjà) : la voie SF-129-03 reste la sienne.
- Le `.docx` / `.xlsx` (**SF-129-07**, plus tard).
- Toute modification de la visionneuse, des endpoints ou du stockage.

## Préoccupations transversales
- **Auth / Principal** : aucune.
- **Contexte tenant** : les images d'aperçu suivent **exactement** le chemin du `.pptx` —
  `ProjectFileDeposit` (poste : `RunnerToolGateway.writeFileBytes` ; hébergé :
  `WorkspaceService.depositHostedFile`), sous le `userId` et le `Workspace` du tour. **Aucun nouveau
  composant ne résout le tenant.** Composants concernés et vérifiés : `DeckToolExecutor`,
  `ProjectFileDeposit`, `PresentationToolExecutor` (inchangé), `PresentationService.attachSlides`
  (inchangé, borné + scellé `user_id`).
- **Plans / limites** : bornes **nouvelles et nommées** — 30 slides d'aperçu, 2 Mo par image ; elles
  s'ajoutent aux bornes existantes (`PresentationLimits` : 100 slides, 5 Mo/slide) sans les changer.
- **Navigation / routing** : aucune route, aucun guard, aucune redirection.

## Drapeau de déploiement
L'aperçu n'apparaît qu'après **reconstruction et déploiement de l'image `diagram-renderer`**.
**NON déployé** dans cette PR (règle du lot).
