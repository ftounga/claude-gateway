# Mini-spec — F-86 / SF-86-02 — La quatrième voie du pipeline documentaire

## Identifiant

`F-86 / SF-86-02`

## Feature parente

`F-86` — Les documents Word entrent sans détour

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-86-02-quatrieme-voie`

---

## Objectif

Un `.docx` déposé dans la bibliothèque est **accepté** et son texte extrait **sur la machine**, par
une quatrième voie à côté de PDF (asynchrone), image (synchrone) et texte — et il apparaît dans la
liste des formats **sans qu'une ligne de frontend bouge**.

---

## Comportement attendu

### Cas nominal

1. `POST /api/documents` reçoit un `.docx`
   (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`).
2. Le type est dans `app.ocr.allowed-types` — **la seule chose qui change pour l'écran**.
3. Il est aussi dans `app.ocr.local-types` : le régime retenu est `OcrMode.LOCAL`.
4. `DocumentService` appelle `DocxTextExtractor` (SF-86-01). **Aucun appel à `OcrProvider`, aucune
   sortie de la machine, aucun job asynchrone, aucun octet vers AWS.**
5. Le document est persisté au statut `EXTRACTED` avec son texte, portant le `user_id` courant.
6. Le worker d'ingestion RAG le reprend comme n'importe quel document `EXTRACTED` : le `.docx`
   devient interrogeable sans qu'une ligne du pipeline RAG ait été touchée.
7. `GET /api/file-formats` publie le nouveau type ; l'`accept` du sélecteur et la phrase « formats
   acceptés » en dérivent (SF-85-01), **sans modification du frontend**.

### Pourquoi pas `OcrProvider`

Ses deux gestes — `extractSync`, `startAsync` — décrivent une reconnaissance de caractères sur une
**image**. Un `.docx` n'est pas une image : il n'y a rien à reconnaître, seulement à lire. L'y faire
passer obligerait à mentir sur ce que fait l'interface, ou à glisser un cas particulier dans un
fournisseur dont le métier est tout autre. `OcrProvider`, `TextractOcrProvider`, `StubOcrProvider`
et `OcrPollingWorker` ne sont **pas touchés** par cette subfeature.

### Le type déclaré peut être faux — et c'est le cas de découverte

Le navigateur annonce parfois `application/octet-stream` (ou rien du tout) pour un `.docx` : sur un
poste sans suite bureautique installée, l'extension n'est associée à aucun type MIME. Refuser là
serait refuser exactement la personne que F-86 existe pour servir — celle qui découvre le produit
avec son premier fichier.

Le serveur **complète donc la validation par le contenu**, et seulement dans ce cas précis :

| Type déclaré | Ce que fait le serveur |
|---|---|
| Le type `.docx` | Accepté (liste blanche), comme n'importe quel autre type accepté |
| Un type de la liste blanche | Inchangé, chemin nominal **strictement identique à avant** |
| **Vide** ou `application/octet-stream` | Le contenu est **reniflé** ; si c'est réellement un `.docx` **et** que le type `.docx` est dans la liste blanche, il est accepté comme tel. Sinon, refus habituel |
| Un autre type hors liste blanche (`image/bmp`, `application/x-msdownload`…) | Refusé, **sans reniflage**. Un type déclaré et faux n'ouvre pas de porte |

C'est la symétrie du garde-fou de SF-86-01 : un fichier renommé est refusé **sur son contenu**, donc
un fichier mal déclaré est admis **sur son contenu**. Dans les deux sens, c'est le contenu qui
décide, jamais le nom ni l'étiquette.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Fichier absent ou vide | Message existant, inchangé | 400 |
| Au-dessus de `app.ocr.max-size` | Message existant, inchangé | 413 |
| Type hors liste blanche (et pas un `.docx` renifflable) | Message existant de SF-85-02, inchangé | 415 |
| **`.docx` corrompu, tronqué, ou fichier renommé** | Message de `DocxTextExtractor`, tel quel : une phrase utile, **jamais une trace technique**. **Rien n'est persisté** | **422** |
| **Zip-bomb / entité XML externe** | Même chemin : refus, message utile, rien n'est persisté | **422** |

**Arbitrage — refuser tout de suite plutôt que persister un document `FAILED`.** Les échecs
`FAILED` existants sont des échecs **fournisseur** : ils surviennent après coup, sur un document
valide dont l'utilisateur attend le résultat. Un `.docx` illisible est un échec **de la requête**,
découvert immédiatement, au même titre qu'un type refusé (415) ou une taille excessive (413) — deux
refus qui ne persistent rien. Persister une ligne `FAILED` laisserait dans la bibliothèque un
document fantôme que l'utilisateur devrait supprimer à la main pour un fichier qui n'y est jamais
entré. **Réversible** : un `catch` à déplacer.

---

## Critères d'acceptation

- [ ] Un `.docx` déposé sur `POST /api/documents` est accepté et rendu en `201` avec le statut
      `EXTRACTED` et son texte.
- [ ] `OcrProvider` **n'est jamais appelé** pour un `.docx` (vérifié par `verifyNoInteractions`).
- [ ] Le régime enregistré est `OcrMode.LOCAL`, distinct de `SYNC` et `ASYNC`.
- [ ] Le texte persisté porte le Markdown du tableau et l'annonce des images (chaîne de SF-86-01
      intacte de bout en bout).
- [ ] Le document reste isolé : un autre utilisateur ne le voit ni ne le lit (`user_id`).
- [ ] Un `.docx` corrompu → `422`, message utile, **aucun** document créé en base.
- [ ] Un fichier renommé en `.docx` (déclaré avec le bon type MIME mais dont le contenu n'en est
      pas un) → `422`, refus **sur le contenu**, aucun document créé.
- [ ] Une zip-bomb → `422`, aucun document créé, le service reste disponible.
- [ ] Un `.docx` déclaré `application/octet-stream` est accepté (reniflage du contenu).
- [ ] Un `image/bmp` déclaré reste refusé en `415` : le reniflage ne s'applique pas aux types
      déclarés hors liste.
- [ ] Aucune réponse ne contient de trace technique.
- [ ] `GET /api/file-formats` contient le type `.docx` **avec la configuration par défaut du
      produit** — donc l'`accept` du sélecteur aussi, par dérivation (SF-85-01).
- [ ] **Aucun fichier du frontend n'est modifié dans cette PR** (`git diff --stat` le montre).
- [ ] Le chemin PDF / image / TIFF est inchangé (tests existants verts, non modifiés).
- [ ] Suite backend verte.

---

## Périmètre

### Hors scope (explicite)

- La **pièce jointe de conversation** (`app.upload.allowed-types`, `UploadService`) : c'est SF-86-03.
- `.doc`, `.odt`, `.rtf`, `.pptx`, `.xlsx`.
- Convertir un `.docx` en PDF ; rendre la mise en forme.
- Lire les images incluses par OCR.
- Le pipeline OCR (`OcrProvider`, Textract, worker de polling) : **aucun changement**.
- Le pipeline RAG : aucun changement — un document `EXTRACTED` est ingéré comme les autres.
- Toute modification du frontend.

---

## Contraintes de validation

| Champ | Obligatoire | Valeur | Règle |
|---|---|---|---|
| `app.ocr.allowed-types` | Oui | + `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | liste MIME, normalisée en minuscules, sans doublon (existant) |
| `app.ocr.local-types` | Oui (défaut) | le type `.docx` | sous-ensemble traité **sur la machine** ; le reste suit le routage sync/async existant |
| Taille | Oui | `app.ocr.max-size` (20 Mo), inchangée | s'applique au `.docx` comme aux autres |
| Bornes de décompression | Oui | `app.docx.max-*` (SF-86-01), inchangées | appliquées à l'intérieur de l'extraction |

---

## Technique

### Endpoint(s)

- `POST /api/documents` — **comportement étendu**, contrat inchangé (même requête, même réponse).
- `GET /api/file-formats` — **contenu** étendu par configuration, code inchangé.

### Tables impactées

`documents` — aucune colonne ajoutée. `ocr_mode` est un `varchar(16)` : la valeur `LOCAL` y entre
sans migration.

### Migration Liquibase

- [x] Non applicable — aucun changement de schéma. `ocr_mode` accepte déjà une chaîne de 16
      caractères ; `LOCAL` en fait 5.

### Classes backend

- `fr.claudegateway.ocr.OcrMode` — ajout de la valeur `LOCAL`.
- `fr.claudegateway.ocr.OcrProperties` — ajout de `localTypes` + `isLocalType()`.
- `fr.claudegateway.ocr.DocumentService` — une branche de plus, et la résolution du type par
  contenu quand le type déclaré est vide ou `application/octet-stream`.
- `fr.claudegateway.shared.error.GlobalExceptionHandler` — `InvalidDocxException` → `422`.

### Composants Angular

**Aucun.** C'est le cœur de la démonstration : ajouter un type à la liste blanche du serveur le fait
apparaître à l'écran, parce que SF-85-01 a fait dériver l'`accept` et la phrase « formats acceptés »
de `GET /api/file-formats`.

---

## Plan de test

### Tests unitaires

- [ ] `DocumentServiceTest` — un `.docx` est extrait localement, statut `EXTRACTED`, mode `LOCAL`.
- [ ] `DocumentServiceTest` — `OcrProvider` n'est **jamais** sollicité pour un `.docx`.
- [ ] `DocumentServiceTest` — un `.docx` illisible lève, et **rien n'est sauvegardé**
      (`verifyNoInteractions(documentRepository)`).
- [ ] `DocumentServiceTest` — un `.docx` déclaré `application/octet-stream` est accepté ; un
      `image/bmp` déclaré reste refusé.
- [ ] `DocumentServiceTest` — les chemins image (SYNC) et PDF (ASYNC) sont inchangés (tests
      existants, non modifiés).
- [ ] `OcrPropertiesTest` — `isLocalType` ; le type `.docx` est dans la liste blanche par défaut.

### Tests d'intégration

- [ ] `DocxDocumentApiIntegrationTest` — `POST /api/documents` avec un **vrai** `.docx` → `201`,
      `EXTRACTED`, texte contenant le tableau en Markdown.
- [ ] `DocxDocumentApiIntegrationTest` — **isolation** : le document appartient à son déposant ;
      un autre utilisateur reçoit `404` sur son détail et ne le voit pas dans sa liste.
- [ ] `DocxDocumentApiIntegrationTest` — `.docx` corrompu → `422`, corps sans trace technique,
      **aucune ligne en base**.
- [ ] `DocxDocumentApiIntegrationTest` — zip-bomb → `422`, aucune ligne en base.
- [ ] `DocxFileFormatsIntegrationTest` — avec la **configuration par défaut du produit**, le type
      `.docx` est présent dans `GET /api/file-formats`. C'est le test anti-divergence appliqué à
      F-86 : il démontre que l'écran l'obtient sans qu'on ait touché au frontend.

### Isolation utilisateur

- [x] Applicable — `DocumentService.submit` reçoit le `user_id` du contexte de sécurité (jamais un
      paramètre client) et le porte sur l'entité ; la lecture passe par
      `findByIdAndUserId` / `findByUserIdOrderByCreatedAtDesc`, inchangés. Vérifié par un test
      d'intégration à deux utilisateurs.

---

## Dépendances

### Subfeatures bloquantes

- `SF-86-01` — **Done** (mergée). Fournit `DocxTextExtractor`.

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Auth / Principal** | `DocumentController` lit `CurrentUser.requireId()` — **inchangé**. Aucun matcher de sécurité ajouté, aucun endpoint créé, aucune règle modifiée. Le nouveau handler d'exception ne touche pas l'authentification | traité |
| **Contexte tenant** | Un seul composant résout le tenant sur ce chemin : `DocumentController` → `DocumentService.submit(userId, …)`. La branche `.docx` porte le **même** `userId` sur la même entité, par le même `Document.builder()`. Aucun nouveau chemin d'accès aux données. Vérifié par test à deux utilisateurs | traité |
| **Plans / limites** | Aucun appel aux services de quota ajouté ni modifié. `app.ocr.max-size` s'applique au `.docx` exactement comme aux autres types, avant lecture | traité |
| **Navigation / routing** | Aucune route Angular, aucun guard, aucun endpoint nouveau | non concerné |

---

## Notes et décisions

- **Arbitrage — un régime `LOCAL` nommé plutôt qu'un `SYNC` réutilisé.** `SYNC` veut dire « OCR
  synchrone chez le fournisseur » ; y ranger le `.docx` rendrait le champ `ocr_mode` menteur, et
  rendrait indistinguables en base les documents partis chez Textract et ceux lus sur la machine.
  **Réversible** : une valeur d'énumération, aucune migration (`varchar(16)`).
- **Arbitrage — `app.ocr.local-types` en configuration plutôt qu'un `if` sur le type `.docx`.**
  Même forme que `sync-types` : le routage du pipeline reste entièrement lisible en configuration,
  et le jour où un autre format se lit sur la machine, il s'ajoute sans livraison. **Réversible.**
- **Arbitrage — reniflage du contenu uniquement pour un type déclaré vide ou
  `application/octet-stream`.** Le chemin nominal est **inchangé** : un type déclaré et valide ne
  fait lire aucun octet de plus qu'avant. Alternative écartée : renifler tout fichier refusé — cela
  ferait de la liste blanche une indication plutôt qu'une règle. **Réversible** : une condition.
- **Arbitrage — `422` plutôt que `415` pour un `.docx` illisible.** `415` dit « ce **type** n'est
  pas accepté », ce qui serait faux : le type est accepté, c'est ce fichier-là qui est cassé. `422`
  dit exactement cela, et l'écran affiche le message du serveur tel quel (branche par défaut de
  `submitErrorMessage`, déjà en place) — donc **sans changement de frontend**. **Réversible.**
- **Ce que cette subfeature ne fait pas bouger** : `OcrProvider` et ses deux implémentations, le
  worker de polling, le pipeline RAG, le frontend. Le `.docx` arrive à l'écran par dérivation.
