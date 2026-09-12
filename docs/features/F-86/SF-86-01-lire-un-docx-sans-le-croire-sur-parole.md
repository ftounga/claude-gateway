# Mini-spec — F-86 / SF-86-01 — Lire un .docx sans le croire sur parole

## Identifiant

`F-86 / SF-86-01`

## Feature parente

`F-86` — Les documents Word entrent sans détour

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-86-01-extraction-docx`

---

## Objectif

Une brique autonome qui lit le texte d'un `.docx` — tableaux en Markdown, notes de bas de page
gardées, images comptées et annoncées — **sans exécuter ni résoudre quoi que ce soit** de ce que
l'archive contient, et qui refuse sur le **contenu** tout fichier qui n'est pas réellement un
document Word.

---

## Pourquoi une subfeature à elle seule

L'extraction est la partie où se trouve le risque (une archive + du XML, donc zip-bomb et entités
XML externes) et la partie où se trouvent les trois décisions de contenu. Le câblage — une branche
de plus dans `DocumentService` (SF-86-02), une conversion avant transmission au fournisseur
(SF-86-03) — est trivial une fois cette brique sûre. Les séparer rend la sécurité testable **sans
base, sans HTTP et sans Spring**, en tests unitaires purs qui fabriquent les archives hostiles.

Cette subfeature **ne branche rien** : aucune liste blanche ne bouge, aucun `.docx` n'est encore
accepté par le produit à la fin de SF-86-01.

---

## La dépendance : aucune. Et c'est le choix, pas un oubli.

Il fallait « choisir une bibliothèque d'extraction Word et la justifier ». La comparaison a rendu un
gagnant qui n'est pas une bibliothèque.

| Candidat | Poids apporté | Licence | Surface de sécurité | Ce qu'il tire avec lui |
|---|---|---|---|---|
| **Apache POI** (`poi-ooxml`) | **~12 Mo** de jars | Apache-2.0 | Très large : modèle objet OOXML complet (Word, Excel, PowerPoint, OLE2, formules, macros), historique de CVE nourri sur ce périmètre | `poi`, `poi-ooxml-lite`, **`xmlbeans`** (~2,5 Mo), `commons-compress`, `commons-io`, `commons-collections4`, `log4j-api`, `SparseBitSet`, `curvesapi` |
| **docx4j** | **~20 Mo** | Apache-2.0 | Encore plus large : ajoute le rendu, la conversion, JAXB | JAXB, `xalan`, `xerces`, Apache FOP en option |
| **JDK seul** (`java.util.zip` + `javax.xml.stream`) | **0** | — (déjà présent) | Réduite à ce qu'on ouvre : un `ZipInputStream` **borné par nous** et un `XMLStreamReader` **durci par nous** | rien |

**Décision : le JDK seul.** Un `.docx` est un zip contenant du XML ; le JDK lit les deux depuis
Java 1.4. Prendre POI pour atteindre `word/document.xml`, c'est embarquer douze mégaoctets et
l'analyseur de trois formats bureautiques pour lire un fichier XML — et c'est exactement le mauvais
échange que le cadrage demandait de nommer. Le point qui tranche n'est pas le poids mais le
**contrôle** : les deux garde-fous exigés (bornes zip-bomb, refus des entités externes) doivent être
**écrits**, pas espérés d'un défaut de bibliothèque. Sur `ZipInputStream` et `XMLInputFactory` ils
tiennent en cinq lignes lisibles en review ; à travers POI ils dépendent de réglages internes
(`ZipSecureFile.setMinInflateRatio`, la configuration XML de `xmlbeans`) qu'un changement de version
peut déplacer sans nous prévenir.

**Le prix à payer, dit clairement** : on ne lit que le sous-ensemble de WordprocessingML dont on a
besoin (paragraphes, sauts, tabulations, tableaux, notes de bas de page, marques d'image). Styles,
numérotation automatique, champs calculés, suivi de modifications ne sont pas rendus. Le hors
périmètre du cadrage dit déjà que c'est du **sens** qu'on extrait, pas une apparence — la limite de
l'implémentation coïncide avec la limite du produit. **Réversible** : `DocxTextExtractor` est
derrière sa propre classe ; si un besoin de rendu apparaissait, POI se substituerait sans toucher au
reste du produit.

---

## Comportement attendu

### Cas nominal

1. `extract(byte[])` reçoit le contenu d'un `.docx`.
2. L'archive est parcourue **une fois**, sous bornes (nombre d'entrées, octets décompressés par
   entrée, octets décompressés au total). Seules deux parties sont retenues :
   `word/document.xml` et `word/footnotes.xml`. Les autres entrées sont traversées, jamais gardées.
3. Chaque partie est analysée par un `XMLStreamReader` **qui refuse les DTD**, donc toute entité —
   interne comme externe.
4. Le texte est rendu **dans l'ordre du document** : un paragraphe par ligne, `w:br` / `w:cr` en
   saut de ligne, `w:tab` en tabulation, les lignes vides consécutives réduites à une seule.
5. Un tableau (`w:tbl`) est rendu en **Markdown** : première ligne = en-tête, séparateur `| --- |`,
   une ligne par `w:tr`. Un `|` présent dans une cellule est échappé, les retours à la ligne d'une
   cellule deviennent des espaces (une cellule Markdown tient sur une ligne). Un tableau imbriqué
   est aplati **dans sa cellule**, jamais perdu.
6. Chaque `w:drawing` / `w:pict` incrémente un compteur d'images ; le contenu binaire n'est jamais lu.
7. Les notes de bas de page sont rendues **à la fin**, sous un titre `Notes de bas de page`, une par
   ligne, préfixées de leur numéro — et un renvoi `[n]` est posé **à l'endroit exact** du texte où
   la note est appelée. Les numéros sont attribués **dans l'ordre de lecture** (1…n), pas repris de
   l'identifiant interne de Word. Les deux notes techniques de Word (`separator`,
   `continuationSeparator`) sont écartées.
8. Si le document contenait des images, le texte **commence** par une ligne qui le dit.
9. Le résultat est un `DocxExtraction(text, ignoredImages)`.

### Ce qui n'est jamais lu

`word/header*.xml` et `word/footer*.xml` ne sont pas retenus : un en-tête répété à chaque page
pollue le contexte sans rien apprendre (décision du cadrage). Ce n'est pas un filtrage a posteriori,
c'est une non-lecture : ces parties ne sont jamais analysées.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Contenu `null` ou vide | `InvalidDocxException` — « Ce fichier est vide. » |
| Les quatre premiers octets ne sont pas ceux d'une archive (`PK\x03\x04`) | `InvalidDocxException` — « Ce fichier n'est pas un document Word (.docx) : son contenu n'est pas une archive Office. » **Refus sur le contenu, jamais sur le nom.** |
| Archive lisible mais sans `word/document.xml` (zip quelconque renommé) | `InvalidDocxException` — « Ce fichier n'est pas un document Word (.docx) valide. » |
| Archive tronquée / corrompue (`ZipException`, flux interrompu) | `InvalidDocxException` — « Ce document Word est illisible : le fichier est corrompu ou incomplet. » |
| XML malformé | `InvalidDocxException` — « Ce document Word est illisible : son contenu interne est corrompu. » |
| **Zip-bomb** : plus de `max-entries` entrées, ou une entrée au-dessus de `max-entry-bytes`, ou un total au-dessus de `max-total-bytes` | `InvalidDocxException` — « Ce document Word est trop volumineux une fois décompressé. » Lecture **interrompue** à la borne : les octets suivants ne sont jamais décompressés. |
| **Entité XML externe** (`<!DOCTYPE … SYSTEM "file:///etc/passwd">`) | `InvalidDocxException` — « Ce document Word est illisible : son contenu interne est corrompu. » Le fichier n'est **pas** lu, aucune connexion n'est ouverte. |

Aucun de ces messages ne porte de trace technique : ni chemin, ni nom de classe, ni message de
l'exception d'origine. La cause technique est journalisée en `debug` côté serveur, jamais rendue.

---

## Critères d'acceptation

- [ ] `extract` d'un `.docx` de texte courant rend les paragraphes **dans l'ordre**, séparés par des
      sauts de ligne.
- [ ] Un tableau est rendu en Markdown : ligne d'en-tête, séparateur, une ligne par rangée ; la
      relation ligne/colonne est reconstituable depuis le texte seul.
- [ ] Un `|` dans une cellule est échappé (`\|`) et ne casse pas la table.
- [ ] Un tableau imbriqué reste dans sa cellule (aucune cellule perdue).
- [ ] Un document contenant *n* images rend `ignoredImages == n` **et** un texte qui l'annonce.
- [ ] Les notes de bas de page sont présentes, numérotées 1…n dans l'ordre de lecture, avec un
      renvoi `[n]` à leur place dans le texte ; les notes `separator` / `continuationSeparator` non.
- [ ] Un **vrai** `.docx` produit par une suite bureautique est lu correctement de bout en bout
      (texte, tableau, note, en-tête et pied absents, image comptée).
- [ ] Un en-tête et un pied de page ne sont **pas** dans le texte rendu.
- [ ] Un fichier qui n'est pas une archive → `InvalidDocxException`, message utile, aucune trace.
- [ ] Un zip valide sans `word/document.xml` → `InvalidDocxException` (refus **sur le contenu**).
- [ ] Un `.docx` tronqué → `InvalidDocxException`, aucune `ZipException` ne remonte.
- [ ] Une archive dont une entrée dépasse `max-entry-bytes` → refusée, et la lecture **s'arrête** à
      la borne (vérifié : le compteur d'octets lus reste sous la borne + un tampon).
- [ ] Une archive de plus de `max-entries` entrées → refusée.
- [ ] Une archive dont le **total** décompressé dépasse `max-total-bytes` → refusée.
- [ ] Un `document.xml` portant `<!DOCTYPE foo SYSTEM "file:///etc/passwd">` avec une entité
      `&xxe;` → refusé, **et** le contenu du fichier local n'apparaît nulle part dans le résultat.
- [ ] Une entité récursive (« milliard de rires ») → refusée sans expansion (le DTD est refusé en
      amont).
- [ ] `looksLikeDocx` rend vrai pour un vrai `.docx`, faux pour un zip quelconque et faux pour
      n'importe quel autre contenu.
- [ ] Suite backend verte.

---

## Périmètre

### Hors scope (explicite)

- **Tout câblage** : aucune liste blanche modifiée, aucun endpoint, aucune branche de service. C'est
  SF-86-02 (bibliothèque de documents) et SF-86-03 (pièce jointe de conversation).
- `.doc`, `.odt`, `.rtf`, `.pptx`, `.xlsx`.
- Convertir un `.docx` en PDF.
- Rendre la **mise en forme** (gras, styles, couleurs, numérotation automatique, champs).
- Lire les images incluses (par OCR ou autrement) — décision du cadrage.
- Les en-têtes et pieds de page — décision du cadrage.
- Les **notes de fin** (`endnotes`) : le cadrage nomme les notes de bas de page. Ajoutables plus
  tard dans la même méthode, sans changement de contrat.
- Le pipeline OCR existant, qui ne bouge pas.

---

## Contraintes de validation

| Champ | Obligatoire | Valeur par défaut | Règle |
|---|---|---|---|
| `app.docx.max-entries` | Oui (défaut) | `2048` | entier > 0 ; nombre d'entrées traversables |
| `app.docx.max-entry-bytes` | Oui (défaut) | `33554432` (32 Mio) | entier > 0 ; octets **décompressés** d'une entrée |
| `app.docx.max-total-bytes` | Oui (défaut) | `268435456` (256 Mio) | entier > 0 ; octets **décompressés** cumulés |

Les trois bornes reprennent la forme des garde-fous zip déjà en place dans le produit
(`app.atelier.max-entries` / `max-file-bytes` / `max-total-bytes`, `WorkspaceService.extract`) :
mesurées sur les octets **réellement lus**, jamais sur `ZipEntry.getSize()` qui est déclaratif et
donc mensonger. Les valeurs sont plus hautes que celles de l'Atelier parce que l'entrée est déjà
bornée en amont par `app.ocr.max-size` (20 Mo) et qu'un document bureautique légitime, très
compressible, peut dépasser dix fois sa taille compressée.

Aucune saisie utilisateur : l'entrée est un tableau d'octets.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Classes backend

- `fr.claudegateway.docx.DocxProperties` — créé (`app.docx.*`, trois bornes).
- `fr.claudegateway.docx.DocxConfig` — créé (`@EnableConfigurationProperties`).
- `fr.claudegateway.docx.DocxExtraction` — créé (record `text`, `ignoredImages`).
- `fr.claudegateway.docx.InvalidDocxException` — créé (message utilisateur, jamais technique).
- `fr.claudegateway.docx.DocxTextExtractor` — créé (`extract`, `looksLikeDocx`).

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `DocxTextExtractorTest` — texte courant dans l'ordre.
- [ ] `DocxTextExtractorTest` — tableau en Markdown, `|` échappé, tableau imbriqué aplati en cellule.
- [ ] `DocxTextExtractorTest` — images comptées et annoncées.
- [ ] `DocxTextExtractorTest` — notes de bas de page rendues, `separator` écartée.
- [ ] `DocxTextExtractorTest` — en-tête / pied de page absents du texte.
- [ ] `DocxTextExtractorTest` — non-archive, zip sans `word/document.xml`, archive tronquée, XML
      malformé → `InvalidDocxException` avec message utile et **sans** trace technique.
- [ ] `DocxSecurityTest` — zip-bomb : entrée trop grosse, trop d'entrées, total trop gros ; la
      lecture s'arrête à la borne.
- [ ] `DocxSecurityTest` — XXE : entité externe pointant un fichier local réel → refus, et le
      contenu du fichier n'apparaît pas ; entité récursive → refus.
- [ ] `DocxPropertiesTest` — valeurs par défaut appliquées quand la configuration est absente ou
      absurde (0, négatif).
- [ ] `RealDocxExtractionTest` — **un vrai `.docx`**, produit par une suite bureautique et versionné
      en ressource de test (`src/test/resources/docx/contrat-reel.docx`, 7 Ko) : vraies déclarations
      de namespaces, vraies propriétés entre paragraphes, vrai `w:tbl`, en-tête et pied dans des
      parties séparées, note de bas de page et image dans `word/media/`. C'est le test qui empêche
      l'extraction de ne marcher que sur des documents écrits par nous.

### Tests d'intégration

Aucun : la brique n'a ni HTTP ni base. Son intégration est testée en SF-86-02 / SF-86-03.

### Isolation utilisateur

- [x] Non applicable — la classe reçoit un tableau d'octets et ne touche ni base, ni contexte de
      sécurité, ni stockage. Aucune donnée d'utilisateur n'y transite autrement qu'en paramètre, et
      rien n'y est persisté. L'isolation est portée par les appelants (SF-86-02 / SF-86-03), où elle
      est testée.

---

## Dépendances

### Subfeatures bloquantes

Aucune.

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Auth / Principal** | Aucun : la classe n'a pas de surface HTTP, ne lit aucun `Principal`, n'ajoute aucun matcher de sécurité | non concerné |
| **Contexte tenant** | Aucun accès aux données, donc aucune résolution de tenant ; rien à filtrer par `user_id` | non concerné |
| **Plans / limites** | Aucun appel aux services de quota. Les trois bornes ajoutées sont des garde-fous de **sécurité**, pas des limites de plan | non concerné |
| **Navigation / routing** | Aucune route Angular, aucun guard | non concerné |

---

## Notes et décisions

- **Arbitrage — aucune bibliothèque (voir le tableau ci-dessus).** Alternative écartée : Apache POI.
  **Réversible** : une classe à remplacer.
- **Arbitrage — tableaux en Markdown** (décision du cadrage, tranchée par défaut en livraison
  autonome). Le texte extrait part chez le fournisseur dans le contexte d'un tour ; un tableau mis à
  plat perd la relation ligne/colonne, justement ce qu'on interroge dans un document professionnel.
  Alternative écartée : cellules séparées par des tabulations — moins coûteux en caractères, mais
  illisible dès qu'une cellule contient une phrase. **Réversible** : une méthode de rendu.
- **Arbitrage — images ignorées, et dites, en tête de texte.** Les passer par Textract rendrait
  l'extraction asynchrone pour tout le monde au profit d'un cas rare. La ligne d'annonce est placée
  **au début** : un modèle qui reçoit le texte dans un contexte de tour doit lire la réserve avant le
  contenu, pas après. Alternative écartée : en fin de texte. **Réversible** : une concaténation.
- **Arbitrage — en-têtes et pieds dehors, notes de bas de page dedans.** Décision du cadrage. Mise
  en œuvre par **non-lecture** des parties `header*.xml` / `footer*.xml` plutôt que par filtrage :
  ce qu'on ne lit pas ne peut pas fuir dans le contexte. **Réversible** : un nom de partie à retenir.
- **Le refus porte sur le contenu.** `looksLikeDocx` et `extract` ne regardent **jamais** le nom du
  fichier : signature d'archive, puis présence réelle de `word/document.xml`. Un `.zip` renommé en
  `.docx` échoue au second test ; un `.exe` renommé échoue au premier.
- **Arbitrage — les notes renumérotées de 1 à n, et rappelées à leur place.** Découvert en lisant la
  sortie d'un vrai document : Word numérote ses notes en interne comme il veut (`-1` et `0` pour ses
  deux séparateurs techniques, puis n'importe quoi), si bien que rendre l'identifiant brut affichait
  « [2] » pour la **première** note du document. Les notes sont donc renumérotées dans l'ordre de
  lecture, et un renvoi `[n]` est posé dans le texte à l'endroit du `w:footnoteReference` — une note
  détachée de sa place perd la moitié de ce qu'elle apporte : dans un contrat, savoir **quelle**
  clause elle nuance est son sens même. **Réversible** : une table de correspondance.
- **`SUPPORT_DTD=false` plutôt qu'une liste de propriétés.** Refuser le DTD ferme d'un coup les
  entités externes (XXE), les entités récursives (« milliard de rires ») et l'inclusion de
  paramètres. Un `XMLResolver` qui lève est posé **en plus**, comme ceinture : si une version future
  du JDK réinterprétait la propriété, la résolution resterait fermée. Les deux réglages sont
  **écrits**, jamais supposés.
