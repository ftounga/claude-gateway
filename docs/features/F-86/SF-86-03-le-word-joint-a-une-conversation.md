# Mini-spec — F-86 / SF-86-03 — Le Word joint à une conversation

## Identifiant

`F-86 / SF-86-03`

## Feature parente

`F-86` — Les documents Word entrent sans détour

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-86-03-piece-jointe-word`

---

## Objectif

Un `.docx` joint à un message de conversation est **accepté** : la gateway en lit le texte sur la
machine et transmet **ce texte** au fournisseur, parce que le fournisseur ne sait pas lire un
`.docx` — et le format apparaît dans le sélecteur de pièce jointe **sans qu'une ligne de frontend
bouge**.

---

## Pourquoi ce n'est pas qu'une ligne de configuration

Le cadrage nomme `app.upload.allowed-types` comme liste impactée. Ajouter le type y suffirait à
faire **apparaître** `.docx` dans le sélecteur de pièce jointe — mais le fichier partirait alors
**tel quel** chez le fournisseur (`AIProvider.uploadFile`, puis un bloc `document` qui le référence).

Or **le fournisseur ne prend pas les `.docx`** : les blocs `document` acceptent du PDF et du texte,
les blocs `image` des images. Un `.docx` transmis brut serait rejeté par le fournisseur — c'est-à-dire
que l'écran proposerait un format que le tour ferait échouer, plus loin et moins clairement qu'un
refus franc. Ce serait **pire** que le refus que F-86 corrige.

C'est précisément le travail d'une gateway : **relayer ce que le fournisseur sait lire**. Le texte
extrait en est ; le zip XML n'en est pas. Aucune capacité du fournisseur n'est réimplémentée ici —
lire un zip n'est pas une capacité de modèle.

---

## Comportement attendu

### Cas nominal

1. `POST /api/upload` reçoit un `.docx`.
2. Le type est dans `app.upload.allowed-types` — **la seule chose qui change pour l'écran**.
3. La gateway lit le texte avec `DocxTextExtractor` (SF-86-01) : tableaux en Markdown, notes de bas
   de page, images annoncées, en-têtes et pieds écartés.
4. Elle transmet au fournisseur, **via l'interface `AIProvider`**, un fichier `text/plain` nommé
   `<nom d'origine>.txt` — jamais le `.docx`.
5. Elle persiste les métadonnées de **l'attachement de l'utilisateur** : son nom d'origine
   (`contrat.docx`), son type d'origine, sa taille d'origine, et le `user_id` courant.
6. Le message qui la joint porte un bloc `document` référençant ce fichier : le modèle lit le texte
   du contrat, ses tableaux, ses notes — et la réserve sur les images non lues.

### Le type déclaré peut être faux

Même règle qu'en SF-86-02, et le même code de reconnaissance : un type déclaré **vide** ou
`application/octet-stream` fait examiner le contenu ; un type déclaré et faux n'ouvre aucune porte ;
un type déclaré et accepté suit le chemin nominal sans qu'un octet de plus soit lu.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Fichier absent ou vide | Message existant, inchangé | 400 |
| Au-dessus de `app.upload.max-size` | Message existant, inchangé | 413 |
| Type hors liste blanche | Message existant, inchangé | 415 |
| **`.docx` corrompu, renommé, zip-bomb, entité XML externe** | Refus **avant** toute transmission au fournisseur : message utile, jamais de trace technique, **aucune métadonnée persistée, aucun fichier chez le fournisseur** | **422** |
| Fournisseur indisponible | Message existant, inchangé | 503 |

---

## Critères d'acceptation

- [ ] Un `.docx` déposé sur `POST /api/upload` est accepté (`200`, contrat inchangé).
- [ ] Ce qui part chez le fournisseur est un `text/plain` **dont le contenu est le texte extrait** —
      vérifié sur le `ProviderFileUpload` capté : type `text/plain`, contenu contenant le Markdown
      du tableau, et **jamais** les octets du zip.
- [ ] Le nom transmis au fournisseur garde le nom d'origine et gagne `.txt`.
- [ ] Les métadonnées persistées décrivent **l'attachement de l'utilisateur** : nom et type
      d'origine, taille d'origine.
- [ ] Le `user_id` persisté est celui du contexte de sécurité ; un autre utilisateur ne voit pas le
      fichier.
- [ ] Un `.docx` illisible → `422`, **aucun** appel au fournisseur (`verifyNoInteractions`), aucune
      métadonnée en base.
- [ ] Une zip-bomb → `422`, aucun appel au fournisseur.
- [ ] Un `.docx` déclaré `application/octet-stream` est accepté sur son contenu.
- [ ] Les chemins PDF / image / texte sont **inchangés** : le fichier part tel quel, sans lecture
      supplémentaire (tests existants verts, non modifiés).
- [ ] `GET /api/file-formats` contient le type `.docx` dans **`attachments`**, avec la configuration
      par défaut du produit.
- [ ] **Aucun fichier du frontend n'est modifié dans cette PR.**
- [ ] Suite backend verte.

---

## Périmètre

### Hors scope (explicite)

- La bibliothèque de documents (`app.ocr.allowed-types`, `DocumentService`) : c'est SF-86-02, mergée.
- `.doc`, `.odt`, `.rtf`, `.pptx`, `.xlsx`.
- Convertir un `.docx` en PDF ; rendre la mise en forme ; lire les images incluses.
- Persister le texte extrait d'une pièce jointe côté gateway : F-04 reste **transmission au
  fournisseur sans traitement documentaire**, et seules les métadonnées sont conservées. Le texte
  n'existe que le temps de la transmission.
- Toute modification du frontend.

---

## Contraintes de validation

| Champ | Obligatoire | Valeur | Règle |
|---|---|---|---|
| `app.upload.allowed-types` | Oui | + le type `.docx` | liste MIME normalisée (existant) |
| `app.upload.max-size` | Oui | 32 Mo, inchangée | s'applique au `.docx` comme aux autres |
| `uploaded_files.media_type` | Oui | type d'origine | `varchar(128)` ; le type `.docx` en fait 71 |
| `uploaded_files.filename` | Oui | nom d'origine | `varchar(255)`, inchangé |
| Bornes de décompression | Oui | `app.docx.max-*` (SF-86-01) | appliquées dans l'extraction |

---

## Technique

### Endpoint(s)

- `POST /api/upload` — **comportement étendu**, contrat inchangé.
- `GET /api/file-formats` — contenu `attachments` étendu par configuration, code inchangé.

### Tables impactées

`uploaded_files` — aucune colonne ajoutée, aucune contrainte modifiée.

### Migration Liquibase

- [x] Non applicable.

### Classes backend

- `fr.claudegateway.upload.UploadProperties` — le type `.docx` rejoint la liste par défaut.
- `fr.claudegateway.upload.UploadService` — conversion avant transmission, et résolution du type
  par contenu quand le type déclaré ne dit rien.

### Composants Angular

**Aucun.**

---

## Plan de test

### Tests unitaires

- [ ] `UploadServiceTest` — un `.docx` : le `ProviderFileUpload` capté porte `text/plain`, le nom
      `contrat.docx.txt`, et le **texte** (Markdown du tableau) — pas les octets du zip.
- [ ] `UploadServiceTest` — les métadonnées persistées gardent nom, type et taille d'origine.
- [ ] `UploadServiceTest` — un `.docx` illisible : `InvalidDocxException`, `verifyNoInteractions`
      sur le fournisseur **et** sur le dépôt.
- [ ] `UploadServiceTest` — zip-bomb : même refus, aucun appel au fournisseur.
- [ ] `UploadServiceTest` — `application/octet-stream` reconnu sur le contenu ; `image/bmp` refusé.
- [ ] `UploadServiceTest` — PDF et image : transmis **tels quels**, chemin inchangé.

### Tests d'intégration

- [ ] `DocxUploadApiIntegrationTest` — `POST /api/upload` avec le **vrai** `.docx` : réponse portant
      le nom et le type d'origine, **et** un bouchon de fournisseur qui a reçu du `text/plain` nommé
      `contrat.docx.txt` contenant le Markdown du tableau et la note de bas de page — jamais le zip.
- [ ] `DocxUploadApiIntegrationTest` — un PDF part **tel quel** : octets identiques, type identique.
- [ ] `DocxUploadApiIntegrationTest` — pour un `.docx` illisible ou une zip-bomb, le bouchon n'a
      **rien** reçu du tout (`lastUpload == null`).
- [ ] `DocxUploadApiIntegrationTest` — `.docx` corrompu → `422`, message utile sans trace technique,
      aucune ligne en base.
- [ ] `DocxUploadApiIntegrationTest` — **isolation** : le fichier appartient à son déposant.
- [ ] `DocxFileFormatsIntegrationTest` — le type `.docx` est publié dans `attachments`.

### Isolation utilisateur

- [x] Applicable — `UploadService.upload` reçoit le `user_id` du contexte de sécurité (jamais un
      paramètre client) et le porte sur `UploadedFile`. Aucun nouveau chemin de lecture. Vérifié en
      intégration.

---

## Dépendances

### Subfeatures bloquantes

- `SF-86-01` — **Done**. Fournit `DocxTextExtractor`.
- `SF-86-02` — **Done**. Fournit `OcrProperties.DOCX_MEDIA_TYPE` et la forme du reniflage.

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Auth / Principal** | `UploadController` lit `CurrentUser.requireId()` — **inchangé**. Aucun endpoint créé, aucun matcher touché | traité |
| **Contexte tenant** | Un seul composant résout le tenant sur ce chemin : `UploadController` → `UploadService.upload(userId, …)`. La branche `.docx` porte le **même** `userId` sur le même `UploadedFile.builder()`. Les lectures (`ChatService.resolveAttachments`, `ConversationFileResponse`) sont **inchangées** et continuent de filtrer par `user_id` | traité |
| **Plans / limites** | Aucun appel aux services de quota ajouté ni modifié. `app.upload.max-size` s'applique avant lecture, comme pour les autres types | traité |
| **Navigation / routing** | Aucune route Angular, aucun guard | non concerné |

---

## Notes et décisions

- **Arbitrage — transmettre le texte, pas le `.docx`.** Le fournisseur ne lit pas les `.docx` : un
  bloc `document` prend du PDF ou du texte. Laisser partir le zip ferait échouer le tour **après**
  que l'écran a accepté le fichier — un refus déplacé plus loin et rendu plus obscur, c'est-à-dire
  le contraire de ce que F-86 vient corriger. Alternative écartée : laisser le type dans la liste
  blanche sans conversion. **Réversible** : une branche.
- **Arbitrage — les métadonnées décrivent l'attachement de l'utilisateur, pas la copie du
  fournisseur.** `filename` reste `contrat.docx` et `media_type` le type Word : c'est le fichier
  que l'utilisateur a joint et qu'il relit dans sa conversation. Enregistrer `text/plain`
  afficherait « TXT » sous un document qu'il a joint en Word. La copie transmise, elle, est nommée
  `contrat.docx.txt` : le nom dit les deux. Conséquence vérifiée : `AnthropicProvider` choisit le
  type de bloc sur `image/*` — un type Word donne un bloc `document`, ce qui est exact.
  **Réversible** : deux affectations.
- **Arbitrage — rien n'est ajouté au texte transmis.** Pas d'en-tête « ceci est un document Word » :
  la seule ligne ajoutée est celle de SF-86-01 sur les images non lues, qui est une **réserve sur
  l'exactitude**, pas une décoration. Le nom du fichier voyage déjà dans les métadonnées du
  fournisseur. **Réversible.**
- **F-04 n'est pas dénaturée.** La règle « upload = validation + transmission, sans traitement
  documentaire » tient : rien n'est indexé, rien n'est persisté du contenu, aucun chunk, aucune
  table documentaire. Le texte n'existe que le temps de l'appel — exactement comme les octets d'un
  PDF aujourd'hui. Ce qui change est la **forme** de ce qu'on relaie, pour que le fournisseur
  puisse le lire.
