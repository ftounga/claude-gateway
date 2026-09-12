# F-86 — Les documents Word entrent sans détour

> Cadrage du 2026-09-12. **Cette feature corrige une hypothèse que j'ai posée le jour même**, dans le
> cadrage de F-85 : « la conversion en PDF est à un clic dans Word, il vaut mieux la nommer
> aujourd'hui que la faire attendre ». Elle tient mal, et deux essais réels l'ont montrée fausse en
> une heure.

## 1. Ce qui s'est passé

Le 2026-09-12, **deux personnes différentes**, à une heure d'intervalle, ont essayé d'ajouter des
`.docx` et ont buté sur le même refus. La seconde était le PO lui-même.

F-85 a fait son travail : depuis sa livraison, l'écran nomme le format, liste ce qui passe, et
propose l'export PDF. **Le message est bon.** Le problème n'est plus qu'on ne comprend pas le refus —
c'est **le refus lui-même**.

## 2. Pourquoi l'hypothèse de F-85 ne tient pas

**Word est le format du document professionnel.** Un prospect qui essaie un outil documentaire arrive
avec un `.docx` : c'est le **premier** fichier qu'il tentera, systématiquement. Deux sur deux l'ont
fait.

**Le détour se paie au pire moment : la découverte.** Convertir avant d'avoir vu ce que l'outil sait
faire, c'est se demander d'abord pourquoi on devrait.

**Et techniquement, c'est le cas facile qu'on refuse.** Un `.docx` est une archive contenant du XML :
le texte s'en extrait **directement**, sans OCR. Le pipeline actuel fait bien plus compliqué pour un
PDF scanné — il appelle **Textract**, de façon asynchrone, avec un travailleur de relance. Refuser
Word revient à écarter le cas simple tout en traitant le cas difficile.

## 3. Ce que F-86 livre

Une **quatrième voie** d'extraction, à côté de PDF, image et texte : l'extraction directe d'un
`.docx`.

**Elle ne passe pas par le fournisseur OCR.** `OcrProvider` a deux gestes — `extractSync` et
`startAsync` — et tous deux décrivent une reconnaissance de caractères sur une image. Un `.docx`
n'est pas une image : il n'y a rien à reconnaître, seulement à lire. Le faire passer par là
obligerait à mentir sur ce que fait l'interface, ou à ajouter un cas particulier dans un fournisseur
dont le métier est tout autre.

L'extraction Word est donc **synchrone** — comme les images le sont déjà (`app.ocr.sync-types`), et
pour une meilleure raison : elle ne sort pas de la machine.

**Ce qui change à l'écran** : `.docx` apparaît dans la liste des formats acceptés — **sans qu'une
ligne de frontend bouge**, puisque SF-85-01 a fait dériver l'écran de la liste blanche du serveur.
C'est le test anti-divergence qui le prouvera.

## 4. Les trois décisions de contenu

Le texte courant s'extrait proprement. **Le reste demande un choix, et c'est là qu'est le vrai
arbitrage** — pas dans l'extraction.

| | Question | Recommandation |
|---|---|---|
| **Tableaux** | une suite de cellules : les mettre à plat, ou les rendre en Markdown ? | **Markdown.** Le texte extrait part chez le fournisseur dans le contexte d'un tour ; un tableau mis à plat perd la relation ligne/colonne, et c'est justement ce qu'on interroge dans un document professionnel. |
| **Images incluses** | les ignorer, ou les passer par l'OCR existant ? | **Les ignorer, et le dire.** Les faire passer par Textract rendrait l'extraction asynchrone pour tout le monde, au profit d'un cas rare. Le document doit **annoncer** qu'il contenait *n* images non lues plutôt que de faire croire qu'il a tout rendu. |
| **En-têtes, pieds de page, notes** | dedans ou dehors ? | **Dehors**, sauf les **notes de bas de page**, qui portent souvent le sens d'un document juridique ou contractuel. Un en-tête répété à chaque page pollue le contexte sans rien apprendre. |

**À CONFIRMER PAR LE PO** — ces trois-là changent ce que l'agent lira, donc ce qu'il répondra.
Tranchés par défaut comme ci-dessus si la livraison est autonome, et **tracés**.

## 5. Hors périmètre

- **`.doc`** (l'ancien format binaire, avant 2007). Autre format, autre bibliothèque, cas résiduel.
- `.odt`, `.rtf`, `.pptx`, `.xlsx` : à poser séparément si le besoin apparaît.
- **Convertir** un `.docx` en PDF. On extrait le texte, on ne fabrique pas un second document.
- Rendre la **mise en forme** (gras, styles, couleurs) : on extrait du **sens**, pas une apparence.
- Le pipeline OCR existant, qui ne bouge pas.

## 6. Impact transversal

| Préoccupation | Composants |
|---|---|
| Aucune (ni auth, ni tenant, ni plans, ni routing) | — |
| Backend | `DocumentService` (une branche de plus), `OcrProperties` / `app.ocr.allowed-types`, `app.upload.allowed-types`, une nouvelle classe d'extraction |
| Dépendance | une bibliothèque d'extraction Word — **à choisir et à justifier** : poids, licence, surface de sécurité (un `.docx` est une archive : **zip-bomb et entités XML externes** sont les deux risques réels) |
| Frontend | **aucun changement attendu** — c'est le test anti-divergence de SF-85-01 qui doit le démontrer |

## 7. Plan de test minimal

- Un `.docx` de texte courant → le texte est extrait, dans l'ordre du document.
- Un `.docx` avec un tableau → le tableau est lisible et sa structure survit.
- Un `.docx` contenant des images → l'extraction aboutit et **annonce** les images non lues.
- Un `.docx` **corrompu** ou qui n'est pas une archive → refus propre, message utile, **jamais** une
  trace technique rendue à l'utilisateur.
- **Zip-bomb** et **entité XML externe** → refusés, bornés. Ce sont les deux risques réels du format,
  et le produit a déjà des garde-fous zip (`app.atelier.max-*`) dont il faut s'inspirer.
- Un fichier renommé en `.docx` qui n'en est pas un → refusé sur son **contenu**, pas sur son nom.
- `.docx` apparaît dans `GET /api/file-formats` **sans modification du frontend**.
