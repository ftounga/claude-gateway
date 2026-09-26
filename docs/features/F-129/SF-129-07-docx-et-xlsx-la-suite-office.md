# Mini-spec — F-129 / SF-129-07 — DOCX et XLSX, la suite Office (même moteur que le deck)

## Identifiant
`F-129 / SF-129-07` — feature parente `F-129`

## Statut
`done`

## Date de création
2026-09-26

## Branche Git
`feat/SF-129-07-docx-xlsx`

## Arbitrage de numérotation (rappel)
La demande porte le numéro « SF-129-05 ». Ce numéro est **pris** : il désigne la construction du deck
par la gateway, livrée (PR #823). `PRODUCT_SPEC.md` a déjà tranché le 2026-09-26 — **le `.docx`/`.xlsx`
devient SF-129-07**. On suit ce qui est **livré**, pas ce qui était prévu.

---

## Objectif
Qu'un **document Word** et un **classeur Excel** se produisent **exactement comme le deck** : l'agent
**décrit**, la **gateway construit**, le fichier est **déposé dans le projet** — le poste du client
n'installe rien.

## Le défaut
`build_presentation` (SF-129-05) a fermé le trou pour le `.pptx` : sur un poste où `pip install` est
bloqué — CAGIP, et la plupart des postes de banque — le deck se produit quand même. Le **compte rendu
Word** et le **tableau Excel**, eux, sont restés sur l'ancienne voie : un script que l'agent écrit,
qui commence par `import docx` ou `import openpyxl`, et qui échoue là où le deck réussit. C'est le
même défaut, au même endroit, sur les deux formats que les clients demandent le plus après le deck.

## Ce qu'on écarte, et pourquoi
**Exécuter le Python de l'agent sur la gateway.** Même refus qu'en SF-129-05 et SF-142-07 : c'est du
code écrit par le modèle, notre infrastructure n'est pas son bac à sable. La gateway reçoit une
**description**, et construit le fichier elle-même.

---

## Comportement attendu

### Cas nominal
1. Un outil **`build_document`** prend une description `{title, subtitle, blocks:[…]}` et rend un
   **`.docx`** déposé dans le projet.
2. Un outil **`build_spreadsheet`** prend une description `{title, sheets:[{name, columns, rows}]}`
   et rend un **`.xlsx`** déposé dans le projet.
3. Les deux passent par le **même service** que le deck (`diagram-renderer`), le **même dépôt**
   (`ProjectFileDeposit`) et la **même lecture d'images** (`ProjectFileRead`) — aucun chemin nouveau.
4. Le poste n'exécute **rien** : ni Python, ni installation.
5. Si `python-docx` / `openpyxl` sont **déjà** présents sur le poste, l'ancienne voie reste possible —
   on **ajoute** un chemin, on n'en retire aucun, et on n'installe jamais.

### Blocs d'un document (`.docx`)
`heading` (titre de section, `level` 1-3) · `text` (un ou plusieurs paragraphes) · `bullets` (liste à
puces, `ordered: true` pour une numérotation) · `table` (`rows`, première ligne = en-tête) ·
`image` (par **chemin d'un fichier déjà déposé**, avec `caption`) · `pagebreak`.

### Feuilles d'un classeur (`.xlsx`)
`name` · `columns` (l'en-tête) · `rows` (les lignes ; **un nombre reste un nombre**, un texte reste du
texte) · largeurs de colonnes calculées, en-tête **figé** et **filtrable**.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Description absente / sans `blocks` ni `sheets` | refus **nommé** avant tout appel ; aucun fichier |
| Bloc de type inconnu, feuille sans ligne | refus nommé, avec le type attendu ; rien n'est produit |
| Image référencée absente du projet | refus nommé — un document avec une image manquante est pire qu'un document sans image |
| Chemin d'image **absolu** ou avec **remontée** (`../`) | refus nommé, **sans rien lire** |
| Description démesurée (bornes ci-dessous) | refus borné **avant** production |
| Service indisponible | échec nommé ; l'ancienne voie reste possible **si la lib est déjà là**, jamais installée |

---

## Critères d'acceptation
- [x] **CA1** `build_document` produit un `.docx` **ouvrable** depuis une description, sans rien exécuter sur le poste.
- [x] **CA2** `build_spreadsheet` produit un `.xlsx` **ouvrable**, en-tête figé, **nombres typés en nombres**.
- [x] **CA3** Les blocs couvrent : titre de section, texte, puces (ordonnées ou non), tableau, image, saut de page.
- [x] **CA4** Une image déposée dans le projet s'insère par son **chemin** ; une image absente est **refusée**.
- [x] **CA5** Les deux fichiers sont **déposés dans le projet**, sous un nom dérivé du titre et **nettoyé**.
- [x] **CA6 — SÉCURITÉ / ISOLATION** : un chemin absolu (`/etc/passwd`) ou une remontée (`../../`) est **refusé sans aucune lecture** ; toute lecture et tout dépôt passent par le `Workspace` du **tour**, jamais par un chemin du modèle.
- [x] **CA7** Les refus sont **nommés** (type inconnu, bornes, image absente, service muet), jamais une stacktrace.
- [x] **CA8** **Aucun code fourni par le modèle n'est exécuté** — le service reçoit des **données** sur l'entrée standard.
- [x] **CA9** Sans service configuré, les outils **ne sont pas proposés** (comme le deck).
- [x] **CA10** Le document sort **à la charte** par défaut (navy, filet orange, Arial) ; `theme: "plain"` rend le gabarit Office neutre.

---

## Périmètre

### Hors scope (explicite)
- **La lecture in-app** d'un `.docx`/`.xlsx` (l'équivalent de la visionneuse de deck) : pas de table,
  pas d'écran, pas d'endpoint. Le fichier vit dans le projet et se récupère comme les autres fichiers
  produits (diagrammes, images). *Arbitrage tracé ci-dessous.*
- L'**aperçu** image par image (SF-129-06 ne s'applique qu'aux slides).
- Le **`.pptx`** (déjà livré), le PDF, l'ODF.
- Les **mises en page fines** : styles Word personnalisés, en-têtes/pieds de page riches, sommaire
  automatique, formules Excel, graphiques Excel, mise en forme conditionnelle.
- Le **paquet de gouvernance** (`.claude/skills/pptx.md`) : aucune skill nouvelle déposée sur le poste ;
  la doctrine passe par le **guide** ajouté à la consigne système, comme pour le deck.

---

## Technique

| Élément | Changement |
|---|---|
| `diagram-renderer/office.py` *(nouveau)* | construit le `.docx` (`python-docx`) et le `.xlsx` (`openpyxl`) **à partir de la description** |
| `diagram-renderer/Dockerfile` | + `python-docx==1.1.2`, `openpyxl==3.1.5` |
| `diagram-renderer/server.js` | `POST /document` (`{format, spec}` → binaire) |
| `OfficeBuilder` *(interface)* + `HttpOfficeBuilder` | Provider Independence, comme `DeckBuilder` / `DiagramRenderer` |
| `OfficeToolCatalog` / `OfficeToolExecutor` | les deux outils, et les composants **réutilisés** (`ProjectFileRead`, `ProjectFileDeposit`) |
| `ProjectFileNames` *(nouveau, partagé)* | la validation d'un chemin venu du modèle et le nettoyage d'un nom de fichier, **écrits une fois** ; `DeckToolExecutor` s'y branche au lieu d'en garder sa copie |
| `AtelierChatService` | branchement (outils du tour, guide, aiguillage), sous la même garde que le deck |

### Endpoints
Aucun endpoint d'API nouveau (`POST /document` est **interne au cluster**, service `ClusterIP`,
NetworkPolicy inchangée).

### Tables impactées
Aucune. **Aucune migration Liquibase.**

### Composants Angular
Aucun — cette subfeature n'ajoute aucun écran (voir Hors scope).

### Bornes
**Document** : 300 blocs · 200 lignes par bloc · 4 000 caractères par ligne · 40 lignes × 12 colonnes
par tableau · 20 images · 8 Mio pour le fichier.
**Classeur** : 12 feuilles · 5 000 lignes · 40 colonnes · 1 000 caractères par cellule · 8 Mio.
Tenues **des deux côtés** (service **et** gateway) : une borne tenue d'un seul côté finit par ne plus
être tenue du tout.

### Les images
L'agent donne un **chemin du projet** ; la gateway le **lit** (poste ou hébergé) et l'insère. Aucun
chemin absolu, aucune remontée de dossier.

---

## Plan de test

### Service — `diagram-renderer/tests/test_office.py`, éprouvé sur les vraies librairies
- [x] Une description de document produit un `.docx` **relu** : titres, paragraphes, puces, tableau,
      image embarquée, saut de page.
- [x] Une description de classeur produit un `.xlsx` **relu** : feuilles nommées, en-tête figé,
      **nombres restés nombres**, largeurs posées.
- [x] La charte est le **défaut** ; `plain` rend le gabarit neutre.
- [x] Chaque borne dépassée lève un **refus nommé** (blocs, lignes, feuilles, colonnes, cellules, images).
- [x] Un type de bloc inconnu, une feuille sans ligne, une image inconnue : refus nommés.

### Gateway — `OfficeToolExecutorTest`, `OfficeToolCatalogTest`, `HttpOfficeBuilderTest`
- [x] La gateway construit et dépose ; la réponse nomme le fichier déposé.
- [x] Les images sont **lues dans le projet** (isolation du tour) puis transmises encodées.
- [x] **ISOLATION** : chemin absolu et remontée **refusés sans rien lire ni construire**.
- [x] Image absente → refus nommé qui renvoie à `render_diagram` ; rien n'est construit.
- [x] Service indisponible → l'ancienne voie reste possible **si elle est déjà là**, jamais installée.
- [x] Description invalide (ni `blocks` ni `sheets`) → refus immédiat.
- [x] Sans service configuré → outils non proposés.
- [x] Bornes du client HTTP : fichier vide, fichier trop lourd, 4xx du service → messages distincts.
- [x] `ProjectFileNamesTest` : le nom est nettoyé, l'extension imposée, un chemin ne peut pas s'y glisser.

### Isolation workspace
- [x] **Applicable** : toute lecture et tout dépôt passent le `userId` + le `Workspace` du tour —
      vérifié par `OfficeToolExecutorTest` (captures d'arguments) et par le refus des chemins.

---

## Dépendances
### Subfeatures bloquantes
- `SF-129-05` (la gateway construit le deck) — **done** : le service, l'interface et le dépôt existent.
- `SF-142-06/07` (service de rendu dans le cluster) — **done**.

### Questions ouvertes impactées
Aucune (`docs/OPEN_QUESTIONS.md` inchangé).

---

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | Aucun endpoint nouveau. Les outils sont ouverts sous la **même garde** que `build_presentation` (`isOpenFor(userId, workspace)`), et les fichiers transitent par la gateway, lus sous l'isolation du tour. Composants vérifiés : `AtelierChatService` (catalogue d'outils, guide, aiguillage), `OfficeToolCatalog`, `OfficeToolExecutor`. |
| **Contexte tenant** | **oui** | Lecture des images et dépôt des fichiers : `Workspace` du tour, jamais un chemin du modèle. Composants vérifiés : `ProjectFileRead`, `ProjectFileDeposit` (inchangés, réutilisés), `ProjectFileNames` (nouveau, refuse les chemins), `DeckToolExecutor` (rebranché sur le helper partagé, comportement inchangé — tests existants conservés). |
| Plans / limites | non | aucun appel fournisseur, aucun jeton — comme le rendu de diagrammes, c'est **gratuit** |
| Navigation / routing | non | aucun écran, aucune route |

---

## Notes et décisions (arbitrages)

- **A1 — Numérotation** : le numéro demandé (`SF-129-05`) est pris ; la subfeature est livrée en
  **SF-129-07**, conformément à l'arbitrage déjà inscrit dans `PRODUCT_SPEC.md`. *Réversible.*
- **A2 — Pas de lecture in-app** : le `.docx`/`.xlsx` est **déposé dans le projet** et s'ouvre avec
  l'outil du poste ; aucune visionneuse, aucune table, aucun écran. La visionneuse de deck existait
  parce que le PO l'avait **exigée pour le `.pptx`** ; l'exiger pour Word et Excel ajouterait une
  table, un endpoint et un écran pour un format que le client ouvre déjà chez lui. *Réversible — une
  SF ultérieure peut ajouter le rangement en artefact, sur le patron de SF-129-02.*
- **A3 — Deux outils plutôt qu'un** : `build_document` et `build_spreadsheet` séparés, plutôt qu'un
  `build_office(format)`. Un outil dont le schéma change de sens selon un champ se remplit mal ; deux
  schémas nets se remplissent bien. *Réversible.*
- **A4 — Un seul service** : `POST /document` rejoint `/presentation` et `/render` dans l'image
  `diagram-renderer` — une image à déployer, une NetworkPolicy à tenir. *Réversible.*
- **A5 — Charte par défaut** : même règle que le deck (SF-129-04) ; `plain` reste la sortie neutre.
- **DRAPEAU DE DÉPLOIEMENT** : comme SF-129-04/06, ces outils n'apparaissent qu'après
  **reconstruction et déploiement de l'image `diagram-renderer`** (nouvelles dépendances Python).
