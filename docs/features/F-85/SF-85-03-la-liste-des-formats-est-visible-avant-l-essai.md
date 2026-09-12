# Mini-spec — F-85 / SF-85-03 — La liste des formats est visible avant l'essai

## Identifiant

`F-85 / SF-85-03`

## Feature parente

`F-85` — Un fichier refusé dit pourquoi, et quoi faire

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-85-03-formats-visibles`

---

## Objectif

À côté de chaque bouton d'ajout, ce qui est accepté — en noms courants et **dérivé** de la liste
blanche — pour qu'on l'apprenne **avant** d'essayer, et non en échouant.

---

## Comportement attendu

### Cas nominal

| Écran | Ce qui est affiché à côté du bouton | D'où ça vient |
|---|---|---|
| Bibliothèque (« Choisir un fichier ») | « PDF, images (PNG, JPEG, TIFF) — 20 Mo au maximum » | `app.ocr.allowed-types` + `app.ocr.max-size`, lus au serveur |
| Composeur de conversation (trombone) | « PDF, images (PNG, JPEG, GIF, WebP), texte (TXT, Markdown, CSV) — 32 Mo au maximum » | `app.upload.allowed-types` + `app.upload.max-size`, lus au serveur |
| Fichier d'un projet (« Ajouter un fichier ») | « Texte et code : .txt, .md, .markdown, .js… (56 formats) » | `WORKSPACE_TEXT_EXTENSIONS`, source unique du frontend |
| Import d'archive d'un poste | « Importer une archive .zip » — **déjà visible sur le bouton**, inchangé | `ARCHIVE_EXTENSIONS` |

1. La phrase est **calculée** à partir de la liste : un format ajouté au serveur s'y ajoute sans
   toucher à l'écran. La même fonction que le message de refus (SF-85-02) l'écrit : les deux ne
   peuvent pas se contredire.
2. Le plafond de taille est dit dans la même phrase, en Mo, lui aussi lu au serveur — celui de la
   bibliothèque était **écrit en dur** (« 20 Mo ») dans le gabarit.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Formats du serveur pas encore connus | La mention **n'affiche rien** plutôt qu'une liste fausse ou un « … » qui ne dit rien. Le bouton reste utilisable | — |
| Appel `GET /api/file-formats` en échec | Idem : aucune mention. On ne promet pas ce qu'on ne sait pas | — |
| Liste d'extensions du workspace très longue (56) | Les quatre premières, puis le nombre total : « .txt, .md, .markdown, .js… (56 formats) ». La liste complète reste accessible en infobulle | — |
| Envoi en cours (bibliothèque) | La mention cède la place à « Envoi de <nom>… », comportement existant inchangé | — |

---

## Critères d'acceptation

- [ ] La bibliothèque annonce ses formats **en noms courants**, sans aucun type MIME, et son plafond,
      tous deux venus du serveur.
- [ ] La mention change quand la liste du serveur change — sans toucher à l'écran (même test que
      SF-85-01, appliqué au texte visible).
- [ ] La chaîne `PDF, PNG, JPEG ou TIFF — 20 Mo au maximum`, écrite en dur, **disparaît du code**.
- [ ] Le composeur de conversation annonce ses formats à côté du trombone.
- [ ] L'ajout d'un fichier de projet annonce « texte et code », avec quelques extensions et le
      nombre total — la liste complète en infobulle.
- [ ] Formats inconnus ⇒ aucune mention, aucun écran cassé.
- [ ] Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md` ; aucun registre de couleur ajouté.
- [ ] Suite frontend verte.

---

## Périmètre

### Hors scope (explicite)

- Changer une liste blanche, accepter `.docx`, convertir côté serveur, le pipeline OCR.
- Le bouton d'import d'archive : il **dit déjà** son format (« Importer une archive .zip »). Le
  réécrire n'apporterait rien.
- Toute modification backend : aucune. Les données affichées existent déjà (SF-85-01).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|---|---|---|---|---|---|
| extensions montrées (workspace) | — | 4 | extensions en minuscules | — | préfixées d'un point |
| plafond affiché | — | — | entier ≥ 1, en Mo | — | arrondi à l'entier inférieur |

---

## Technique

### Endpoint(s)

Aucun créé ni modifié.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `shared/file-format-names.ts` — ajout de `maxSizeLabel()` et `extensionsSummary()`, au même endroit
  que le reste du vocabulaire des formats.
- `DocumentsComponent` — mention dérivée (remplace la chaîne en dur).
- `ChatComponent` — mention ajoutée sous le composeur.
- `AtelierFilesComponent` — mention ajoutée à côté d'« Ajouter un fichier ».

---

## Plan de test

### Tests unitaires

- [ ] `file-format-names.spec` — `maxSizeLabel(20971520)` ⇒ `20 Mo`.
- [ ] `file-format-names.spec` — `extensionsSummary` ⇒ quatre extensions, puis le total.

### Tests d'intégration

- [ ] `documents.component.spec` — la mention est celle du serveur, sans type MIME, avec le plafond.
- [ ] `documents.component.spec` — elle change quand le serveur change.
- [ ] `documents.component.spec` — formats inconnus ⇒ aucune mention.
- [ ] `chat.component.spec` — la mention est présente à côté du trombone.
- [ ] `atelier-files.component.spec` — la mention dit « texte et code » et le nombre d'extensions.

### Isolation utilisateur

- [x] Non applicable — aucune donnée d'utilisateur n'est lue ni affichée : la mention est de la
      configuration d'application, déjà servie par l'endpoint authentifié de SF-85-01.

---

## Dépendances

### Subfeatures bloquantes

- `SF-85-01` — statut : `done` (les listes du serveur sont lisibles).
- `SF-85-02` — statut : `done` (la traduction en noms courants existe, à un seul endroit).

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Auth / Principal** | Aucun appel ajouté | traité |
| **Contexte tenant** | Aucune donnée d'utilisateur affichée | traité |
| **Plans / limites** | Le plafond de taille est **affiché**, jamais modifié ; aucun service de quota appelé | traité |
| **Navigation / routing** | Aucune route, aucun guard | traité |

---

## Notes et décisions

- **Arbitrage — rien plutôt qu'un texte d'attente.** Tant que les formats ne sont pas connus, la
  mention est vide. Un « Chargement… » sous un bouton attire l'œil sans rien apprendre, et une liste
  de secours serait une deuxième source de vérité. **Réversible.**
- **Arbitrage — quatre extensions et un compte pour le workspace.** Cinquante-six extensions ne se lisent
  pas ; quatre plus « (56 formats) » disent la nature de ce qui passe, et l'infobulle donne le reste
  à qui la cherche. **Réversible** : une constante.
- **Aucun registre de couleur ajouté.** Les trois mentions réutilisent des classes existantes —
  `documents__filename`, `toolbar-note` — et la couleur `--cg-text-secondary` du design system, celle
  des sous-titres et des labels.
