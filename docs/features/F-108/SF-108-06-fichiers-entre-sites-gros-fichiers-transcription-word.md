# Mini-spec — F-108 / SF-108-06 — Fichiers : entre sites, gros fichiers, transcription Word

> Base : `docs/features/RELIQUATS-2026-09-14.md` §B (SF-108-06, **déjà validé par le PO**) et
> `docs/features/F-108/CADRAGE-F-108-agir-dans-microsoft-365.md` (gardes §4 NON négociables, §5.1).
> S'appuie sur SF-108-03 (`SharePointPage`, `SharePointFiles`, `SharePointLocation`, `TeamsFileTools`)
> et SF-108-04 (`TeamsWriteTools`, `SharePointWrites`, `TeamsToolCatalog.isWrite/describeWriteCall`).
> **Même doctrine que F-108** : API REST SharePoint appelée **depuis la page** (`Runtime.evaluate`,
> fetch same-origin, session du navigateur), **Chrome télécharge**, **aucun secret ne remonte**
> (projection sur liste blanche `SharePointProjection`) ; **chaque écriture confirmée** (SF-108-02) ;
> formes **« à confirmer sur poste réel »** ; **échec / incertitude bruyant nommé** si la forme réelle
> diffère (jamais « c'est fait » sans vérification). Tests sur **réponses modèles**.

## Identifiant

`F-108 / SF-108-06`

## Feature parente

`F-108` — Agir dans Microsoft 365 : fichiers, enregistrements, gestes

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-108-06-fichiers-entre-sites-gros-fichiers`

---

## Objectif

Compléter les trois manques relevés à la livraison de F-108 : **déplacer un fichier ou un dossier
entre deux sites** SharePoint (par **copie** puis **corbeille** — deux autorisations), **déposer
au-delà de 250 Mo** par une **session d'envoi découpée** (fragments), et **lire le texte d'un `.docx`
téléchargé** sur la machine (transcription Word).

---

## Comportement attendu

### Cas nominal

1. **`teams_copy` (écriture, confirmée)** — copie un fichier ou un dossier vers un dossier de
   destination, **du même site ou d'un autre site**, **sans jamais écraser** (`overwrite=false`).
   - L'onglet est amené sur l'**origine de la source** (SF-108-01) ; l'existence de la source est
     vérifiée ; l'API REST `SP.MoveCopyUtil.CopyFileByPath` (fichier) ou `CopyFolderByPath` (dossier)
     est appelée **depuis la page**, corps portant les adresses **absolues** source et destination.
   - **Vérification honnête** : si la destination est **sur la même origine** que l'onglet, le nouvel
     emplacement est **revérifié** (`describesItem`/`probe`) → « c'est fait » ; si la destination est
     sur un **autre site** (origine différente, non joignable depuis cet onglet), le résultat dit
     « copié selon Microsoft (réponse OK) — **vérifie à destination avant toute suppression** de
     l'original » (`verifyAtDestination: true`, `done` reste faux), **jamais** « c'est fait ».
   - **Déplacement entre sites = deux autorisations** : `teams_copy` (autorisation 1), puis
     `teams_delete` (autorisation 2, corbeille du site, **restaurable**). La description de `teams_move`
     (même site) renvoie explicitement vers ce chemin pour l'inter-site.
2. **Dépôt au-delà de 250 Mo (session découpée)** — `teams_upload_file` et `teams_replace_version`
   choisissent, selon la taille du fichier local :
   - `≤ 250 Mo` : dépôt simple existant (`Files/AddUsingPath`), inchangé ;
   - `> 250 Mo` : **session d'envoi découpée** — le champ de dépôt est créé par le script et le fichier
     y est posé par `DOM.setFileInputFiles` (**Chrome lit le disque**), puis **un seul script de page**
     crée le fichier vide (`AddUsingPath`, `Blob` vide) et enchaîne `StartUpload` / `ContinueUpload` /
     `FinishUpload` sur des tranches de 10 Mo découpées **dans le navigateur** (`File.slice`) — **les
     octets ne passent jamais par la liaison**. La taille finale est **vérifiée** ; sinon « a
     **peut-être** eu lieu ».
   - Plafond dur relevé à 15 Gio ; au-delà, refus nommé **avant tout geste**.
3. **`teams_read_docx` (lecture, sans confirmation)** — lit le **texte** d'un `.docx` **déjà sur la
   machine** (chemin **absolu**) : le fichier est un ZIP, le texte est extrait de `word/document.xml`
   (paragraphes `w:p`, tabulations `w:tab`, sauts `w:br`), sans réseau ni geste. Rend le texte (borné),
   le nombre de paragraphes, et l'indicateur de troncature. Sert à lire une **transcription Word**
   rapatriée par `teams_read_file` ou `teams_meeting_recording`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `teams_copy` : source / destination manquante ou illisible | `MISSING_FIELD` / `LOCATION_UNKNOWN`, **aucun geste** |
| `teams_copy` : `name` invalide (`/ \ : * ? " < > \|`, `.`/`..`, > 255, espace/point final) | `INVALID_NAME`, aucun geste |
| `teams_copy` : source absente | `NOT_FOUND`, rien n'est copié |
| `teams_copy` (même site) : un élément porte déjà le nom à destination | `ALREADY_EXISTS`, rien n'est écrasé |
| `teams_copy` : réponse OK mais nouvel emplacement introuvable / non vérifiable | `SHAPE_MISMATCH` ou `verifyAtDestination` : « a peut-être eu lieu, vérifie » — jamais « c'est fait » |
| `teams_copy` : 401/403, 404, 423 | `ACCESS_DENIED` / `NOT_FOUND` / `WRITE_FAILED` avec le message Microsoft |
| Dépôt > 250 Mo : un fragment échoue (401/403/423/…) | l'erreur Microsoft est rendue, la session est abandonnée, **jamais** « c'est fait » |
| Fichier local absent, relatif, illisible, ou > 15 Gio | refus nommé, **aucun geste** |
| `teams_read_docx` : chemin manquant / relatif / absent / illisible | `MISSING_FIELD` / `NOT_FOUND`, rien n'est lu |
| `teams_read_docx` : le fichier n'est pas un `.docx` lisible (pas un ZIP, pas de `word/document.xml`) | `BODY_UNAVAILABLE` : « ce n'est pas un .docx lisible » |
| Page d'identification / hors domaine (écritures) | `SIGNED_OUT` / `LOCATION_UNKNOWN`, aucun script |

---

## Critères d'acceptation

- [ ] `teams_copy` est au catalogue **runner** et **gateway**, et `isWrite` le reconnaît (WRITE passe de six à **sept** : `teams_post_message` reste hors périmètre).
- [ ] `teams_read_docx` est au catalogue **runner** et **gateway**, et **n'est pas** une écriture (aucune confirmation).
- [ ] Le libellé de confirmation de `teams_copy` nomme l'action, l'élément et la destination en clair (« Copier « plan.docx » vers ProjetIAM › … »).
- [ ] `teams_copy` **même site** : copie + nouvel emplacement **revérifié** → `done` et « c'est fait » sur réponses modèles.
- [ ] `teams_copy` **autre site** : `done` reste faux, `verifyAtDestination: true`, texte « vérifie à destination avant toute suppression » — jamais « c'est fait ».
- [ ] `teams_copy` : source absente → `NOT_FOUND` sans copie ; élément existant à destination (même site) → `ALREADY_EXISTS` sans écrasement ; `overwrite=false` dans le script.
- [ ] Dépôt d'un fichier **> 250 Mo** : le script porte `AddUsingPath` (création vide), `StartUpload`, `ContinueUpload`, `FinishUpload` ; **aucun octet du fichier** dans les commandes envoyées ; le champ est posé par `DOM.setFileInputFiles` ; taille finale vérifiée → `done`.
- [ ] Dépôt `≤ 250 Mo` : chemin simple **inchangé** (`AddUsingPath`, non découpé).
- [ ] Fichier local > 15 Gio → refus **avant tout geste** ; aucune navigation, aucun script.
- [ ] `teams_read_docx` extrait le texte d'un `.docx` modèle (paragraphes, tabulations) ; un fichier non-docx → `BODY_UNAVAILABLE` ; chemin relatif → refus « ABSOLU ».
- [ ] Sécurité : sur une page qui renverrait tout, ni digest, ni jeton, ni adresse pré-authentifiée ne ressortent d'une copie ni d'un dépôt découpé (test page non filtrée).
- [ ] Les délais gateway sont adaptés (`teams_copy` et dépôt découpé : délai d'envoi ; `teams_read_docx` : délai fichiers).
- [ ] Isolation : les écritures restent confirmées par le seul propriétaire du workspace (`RunnerConfirmationGate`), sous le droit Teams (`toolsFor`) ; la session Microsoft reste celle du navigateur de l'utilisateur.

---

## Périmètre

### Hors scope (explicite)

- **Poster un message, répondre, réagir** ; taper dans Word / Excel / PowerPoint en ligne (inchangé).
- **Déplacement inter-site atomique** : il reste **copie + suppression = deux autorisations** (safe par
  construction : l'original ne part à la corbeille que sur autorisation séparée, et il est restaurable).
- **OCR / RAG / indexation** du `.docx` : `teams_read_docx` **relaie le texte brut**, il n'analyse rien
  (Provider-First : l'analyse du document est fournie par le modèle).
- Autres formats bureautiques (`.xlsx`, `.pptx`, `.pdf`) : hors scope ici (le modèle lit déjà le PDF via
  le fournisseur ; le `.docx` transcription est le seul besoin nommé par le reliquat).
- Reprise d'une session d'envoi découpée interrompue (`CancelUpload` / reprise au fragment) : un envoi
  interrompu est **abandonné et dit**, non repris.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `teams_copy.target` | oui | adresse SharePoint / OneDrive pro (règles SF-108-03) | requête retirée |
| `teams_copy.destination` | oui | adresse d'un dossier (même règles) | requête retirée |
| `teams_copy.name` | non | 1–255 car., règles de nom SharePoint | trim (défaut : nom de la source) |
| `upload/replace.file` | oui | chemin **absolu**, fichier régulier lisible, ≤ 15 Gio | normalisé |
| `teams_read_docx.file` | oui | chemin **absolu**, `.docx` régulier lisible, ≤ 50 Mo | normalisé |
| attente d'une copie / d'un dépôt découpé | — | délai d'envoi allongé ; poll runner borné sous le délai gateway | — |

---

## Technique

### Composants runner impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `TeamsWriteTools` | modifié | `copy(...)` ; `dropAndUpload` aiguille simple / découpé ; `localFile` plafond 15 Gio ; message inter-site de `move` renvoyant vers copie + suppression |
| `SharePointWrites` | modifié | `copyTo(...)` (`SP.MoveCopyUtil.CopyFileByPath` / `CopyFolderByPath`), `uploadChunked(...)` (`StartUpload`/`ContinueUpload`/`FinishUpload`), constantes de taille / de tranche / de délai |
| `TeamsFileTools` | modifié | `readDocx(...)` |
| `DocxText` | **créé** | extraction du texte de `word/document.xml` d'un `.docx` (ZIP) |
| `TeamsTools` | modifié | constantes `COPY`, `READ_DOCX` ; `CATALOG` (21 → 23) ; dispatch |
| tests | modifiés / créés | `TeamsWriteToolsTest`, `DocxTextTest`, `TeamsFileToolsTest`, `TeamsMomentsToolsTest`, `TeamsGisementsTest` (taille du catalogue), échantillons `sharepoint-*` |

### Composants gateway impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `TeamsToolCatalog` | modifié | constantes `COPY`, `READ_DOCX` ; `CATALOG` ; `WRITE` (+ `COPY`) ; `describeWrite` / `describeWriteCall` (copie) ; `fileReadingTools` (+ `READ_DOCX`) ; `fileWriteTools` (+ `COPY`, `move` renvoie vers copie + suppression pour l'inter-site) |
| `RunnerToolGateway` | modifié | `teamsTimeoutFor` (`COPY` → envoi, `READ_DOCX` → fichiers) ; `TEAMS_UPLOAD_TIMEOUT_MS` relevé pour couvrir un dépôt découpé |
| tests | modifiés | `TeamsToolCatalogTest`, `TeamsReadingCatalogTest`, `RunnerToolGatewayTest`, `AtelierChatServiceRunnerGuardTest` |

### Endpoints / tables

- **Aucun endpoint, aucune migration** (audit runner existant, comme SF-108-04).

### Préoccupations transversales

- **Sécurité (§7)** : composants impactés — `TeamsToolCatalog`, `TeamsWriteTools`, `SharePointWrites`,
  `SharePointPage`, `SharePointProjection`, `TeamsFileTools`, `DocxText`, le mécanisme d'autorisation du
  terminal (`AtelierChatService` / `RunnerConfirmationGate`), le coupe-circuit, le journal d'audit
  runner. Gardes : `teams_copy` **confirmée** (hors « tout autoriser ») ; domaine vérifié avant
  émission ; identification refusée ; digest, cookies et octets du fichier **restent dans la page** ;
  vue remise ; trace ; `teams_read_docx` **local pur** (aucun secret réseau).
- **Plans / limites** : composants impactés — `TeamsToolCatalog.toolsFor` / `buildTools`. `teams_copy`
  et `teams_read_docx` sont des `teams_*` sous la **garde du droit Teams** (et de la Vigie, F-106),
  inchangée — test sans droit → aucun outil.
- **Auth / Principal, tenant : non** (identité Microsoft = navigateur ; isolation `user_id` gateway
  inchangée). **Navigation / routing front : non** (pas d'écran ; volet piloté par le modèle).

---

## Plan de test

### Tests unitaires

- [ ] `DocxTextTest` — texte extrait d'un `.docx` fabriqué (paragraphes, tabulation, saut) ; ZIP sans
      `word/document.xml` → vide / erreur ; fichier non-ZIP → erreur ; troncature au plafond.
- [ ] `SharePointWritesTest` (le cas échéant) — le script découpé porte `StartUpload`/`ContinueUpload`/
      `FinishUpload` et `overwrite` ; le script de copie porte `CopyFileByPath` / `CopyFolderByPath`.

### Tests d'intégration (navigateur de papier)

- [ ] `TeamsWriteToolsTest` :
  - `teams_copy` **même site** : source vérifiée, `CopyFileByPath` `overwrite=false`, destination
    revérifiée, `done`, vue remise ;
  - `teams_copy` **autre site** : `done` faux, `verifyAtDestination`, texte « vérifie à destination » ;
  - `teams_copy` source absente → `NOT_FOUND` ; existant même site → `ALREADY_EXISTS` sans écriture ;
  - **dépôt > 250 Mo** (fichier **creux** de 300 Mo) : script découpé, `setFileInputFiles`, taille
    vérifiée, `done` ; **aucun octet** dans les scripts ;
  - dépôt `≤ 250 Mo` : chemin simple inchangé ;
  - fichier > 15 Gio (creux) → refus avant tout geste ;
  - page non filtrée : aucun secret ne sort d'une copie ni d'un dépôt découpé.
- [ ] `TeamsFileToolsTest` — `teams_read_docx` : `.docx` modèle lu (texte, paragraphes) ; non-docx →
      `BODY_UNAVAILABLE` ; chemin relatif → « ABSOLU » ; absent → `NOT_FOUND`.
- [ ] Catalogues : runner `CATALOG.size() == 23` (unicité) ; gateway `EXPECTED` + `WRITE` à sept ;
      `RunnerToolGatewayTest` (délais `COPY`, `READ_DOCX`) ; `AtelierChatServiceRunnerGuardTest`
      (libellé de copie ; copie refusée jamais émise).

### Isolation utilisateur

- Côté gateway : confirmation par le seul propriétaire du workspace (`RunnerConfirmationGate`) ; outils
  donnés sous le droit Teams **et** la Vigie du propriétaire (test sans droit → aucun outil). Côté
  machine : session Microsoft du navigateur de l'utilisateur ; `teams_read_docx` lit un fichier de la
  machine de l'utilisateur, jamais une donnée d'un autre tenant gateway.

---

## Notes et décisions

- **Décision (sécurité, appliquée)** : le **déplacement inter-site reste copie + suppression, deux
  autorisations**. Une copie inter-site ne peut pas être vérifiée depuis l'onglet de la source (autre
  origine) : le résultat le **dit** (`verifyAtDestination`), et l'original ne part à la **corbeille**
  (restaurable) que sur une **seconde** autorisation explicite. Aucun déplacement inter-site silencieux.
- **Décision (réversible)** : le dépôt découpé passe par une **session d'envoi** (`StartUpload`…) menée
  **dans la page** en une seule opération ; les octets sont découpés par `File.slice` dans le
  navigateur et ne franchissent jamais `Runtime.evaluate`. Reprise d'un envoi interrompu **non faite**,
  dite dans le résultat.
- **Décision (Provider-First)** : `teams_read_docx` **relaie le texte** d'un `.docx`, il ne l'analyse
  pas — l'analyse du document est fournie par le modèle (`PROJECT.md` §3.3).
- **Forme éprouvée sur documentation, à confirmer sur poste réel** : `SP.MoveCopyUtil.CopyFileByPath` /
  `CopyFolderByPath` et `StartUpload` / `ContinueUpload` / `FinishUpload` sont documentés publiquement ;
  la copie inter-site cross-collection et la session découpée restent **à confirmer sur poste réel**, et
  chaque adaptateur et résultat le porte.
