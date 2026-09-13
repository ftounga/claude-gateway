# Mini-spec — F-108 / SF-108-03 — Lire les fichiers

> Base : `docs/features/F-108/CADRAGE-F-108-agir-dans-microsoft-365.md` §3, §4 (gardes NON
> négociables), §5.1 et §6. **Arbitrage du PO du 2026-09-13** sur le blocage « pas de tenant de
> test » : pas de sélecteurs DOM pour les fichiers ; les capacités passent par les **API web
> documentées publiquement** que SharePoint et OneDrive web appellent eux-mêmes, **appelées depuis
> la page** (fetch same-origin, session du navigateur) ; c'est Chrome qui télécharge ; tests contre
> des réponses modèles construites d'après la documentation Microsoft.

## Identifiant

`F-108 / SF-108-03`

## Feature parente

`F-108` — Agir dans Microsoft 365 : fichiers, enregistrements, gestes

## Statut

`done` — PR #512

## Date de création

2026-09-13

## Branche Git

`feat/SF-108-03-lire-les-fichiers`

---

## Objectif

Donner à l'agent deux outils de **lecture** — lister les fichiers d'une bibliothèque Teams /
SharePoint / OneDrive et rapatrier un fichier sur la machine — en préférant un dossier synchronisé
OneDrive quand il existe, sinon en appelant depuis l'onglet l'API REST SharePoint documentée et en
laissant **Chrome** télécharger, sans qu'aucun jeton, cookie, en-tête ni digest ne remonte.

---

## Comportement attendu

### Cas nominal

1. **`teams_list_files`** (lecture, aucune confirmation) :
   - `location` : l'adresse web d'un dossier SharePoint / OneDrive (lien de bibliothèque, lien
     `Forms/AllItems.aspx?id=…`, lien direct `/:f:/r/…`, lien de pièce jointe). Elle est analysée
     en **origine + site + chemin relatif serveur** (`SharePointLocation`).
   - `conversation_id` : les dossiers des **pièces jointes observées** dans cette conversation
     (propriété `files` des messages déjà lue par l'adaptateur) ; `team` / `channel` : le site
     SharePoint **observé** dont le nom correspond, bibliothèque `Shared Documents`, dossier du canal ;
     `onedrive: true` : le OneDrive de l'utilisateur (hôte `-my.sharepoint.com` observé, adresse
     personnelle lue par `SP.UserProfiles.PeopleManager/GetMyProperties`).
   - Sans rien de tout cela : l'outil rend les **emplacements connus** (pièces jointes, sites
     observés, dossiers synchronisés) sans aucun geste.
2. **Dossier synchronisé préféré (§5.1)** : `SyncedLibraries` reconnaît sur la machine les racines
   OneDrive / SharePoint synchronisées (Windows : `OneDriveCommercial`, `OneDrive - <organisation>`,
   `%USERPROFILE%\<organisation>\<Site> - <Bibliothèque>` ; macOS : `~/Library/CloudStorage/OneDrive-*`
   et `OneDrive-SharedLibraries-*`). Si l'emplacement y existe, la liste est lue **sur le disque**,
   sans geste ; le résultat dit `route: SYNCED_FOLDER`.
3. **Sinon, par le navigateur** (`route: BROWSER`) : `SharePointPage` amène l'onglet relié sur
   l'origine du site (`PageActions.navigate`, gardé par domaine), attend d'y être, puis exécute par
   `Runtime.evaluate` un script qui appelle **depuis la page** :
   `GET {site}/_api/web/GetFolderByServerRelativePath(decodedurl='…')/Folders` et `…/Files`
   (`Accept: application/json;odata=nometadata`). Le script **ne rend que** une projection sur
   liste blanche de clés métier (`Name`, `ServerRelativeUrl`, `Length`, `TimeLastModified`,
   `UniqueId`, `UIVersionLabel`, `ItemCount`, `Exists`, `PersonalUrl`, `value`) et un statut.
   La vue de l'utilisateur est **remise** (retour à l'adresse Teams d'avant) et ce qui a été fait
   est écrit dans le résultat (`gestures`, `viewport`).
4. **`teams_read_file`** (lecture, aucune confirmation) : `file` = adresse web du fichier.
   - Synchronisé : rend le chemin local.
   - Sinon : lit les métadonnées (`GetFileByServerRelativePath`), dirige les téléchargements de
     Chrome vers un dossier **fixe** du volet (`<volet>/downloads/<id>-v<version>`,
     `Browser.setDownloadBehavior`), navigue vers
     `{site}/_layouts/15/download.aspx?SourceUrl=<chemin>` — adresse **construite par nous, non
     signée** — puis attend le fichier ; vérifie la taille annoncée ; renomme le fichier au nom
     attendu ; remet le comportement de téléchargement par défaut ; rend `localPath`.
   - Le fichier est ensuite lu par les outils du poste (`read_file`, `bash`).
5. **Provenance** : chaque résultat porte `provenance` = « forme éprouvée sur documentation, à
   confirmer sur poste réel », et le code des adaptateurs le dit.
6. **Diagnostic** : le **relevé réel** du runner (F-100 / SF-100-00, arrivé sur `main` pendant la
   vague) classe les appels SharePoint que l'adaptateur fichiers emprunte (`SHAREPOINT_FOLDER`,
   `SHAREPOINT_FILE`, `SHAREPOINT_CONTEXTINFO`, `SHAREPOINT_DOWNLOAD`, `ONEDRIVE_PERSONAL_URL`) et son
   rapport gagne une section « Fichiers SharePoint et OneDrive » — gabarisée, sans requête, corps ni
   tenant. En complément, `NetworkObserver` retient les chemins SharePoint / OneDrive observés (sans
   requête ni corps, bornés) : ils servent à reconnaître les sites d'une équipe, et `teams_status` les
   rend **gabarisés** dans `diagnostic.observedFilePaths`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `location` hors domaines Microsoft ou non SharePoint (`example.com`, `onedrive.live.com` grand public) | Refus **avant tout geste** : gap `LOCATION_UNKNOWN` nommant la raison, aucune navigation |
| Lien de partage opaque (`/:w:/s/…`, `/:x:/g/…`) | Gap `LOCATION_UNKNOWN` : « lien de partage opaque, donnez l'adresse du dossier » |
| L'onglet atterrit sur une page d'identification | Gap `SIGNED_OUT` + remède « rouvrez Teams et reconnectez-vous » ; aucun script exécuté, jamais de tentative de connexion ; vue remise |
| Réponse réelle non conforme au modèle (`value` absent, élément sans `Name` / `ServerRelativeUrl`) | **Échec bruyant** : zéro élément, gap `SHAPE_MISMATCH` nommant les champs absents — jamais une liste à moitié |
| 401 / 403 | Gap `ACCESS_DENIED` avec le message Microsoft (tronqué) |
| 404 | Gap `NOT_FOUND` |
| Téléchargement qui ne démarre pas / page HTML reçue au lieu du fichier | Gap `DOWNLOAD_BLOCKED`, fichier HTML supprimé, jamais présenté comme le document |
| Téléchargement encore en cours au délai | `downloaded: false`, `inProgress: true`, phrase « redemandez » ; un rappel reprend sans renavigation |
| Taille reçue ≠ taille annoncée | Gap `SHAPE_MISMATCH` (taille), `downloaded: false` |
| Liaison navigateur absente | État + remède (règle de forme n° 1), rien n'est tenté |

---

## Critères d'acceptation

- [ ] `SharePointLocation.parse` reconnaît les formes d'adresse listées (bibliothèque, `AllItems.aspx?id=`,
      `/:f:/r/`, `/sites/`, `/teams/`, `/personal/`, site racine) et refuse les autres en nommant pourquoi.
- [ ] Le script exécuté dans la page ne rend **que** la projection sur liste blanche : un test prouve
      que `FormDigestValue`, `@content.downloadUrl`, jetons et en-têtes glissés dans une réponse
      modèle **ne ressortent pas** du résultat d'outil, et que le script ne touche ni
      `document.cookie`, ni `localStorage` / `sessionStorage`, ni les en-têtes de réponse.
- [ ] `teams_list_files` sur une réponse modèle rend dossiers et fichiers (nom, chemin, taille, date,
      identifiant, version), `route: BROWSER`, la trace des gestes, et la vue remise.
- [ ] Une réponse non conforme au modèle rend **zéro élément** et un gap `SHAPE_MISMATCH` nommé.
- [ ] Un emplacement synchronisé présent sur la machine est lu sur le disque, **sans aucune commande
      CDP**, `route: SYNCED_FOLDER`.
- [ ] `teams_read_file` : Chrome est dirigé vers le dossier du volet, l'adresse de téléchargement
      est `download.aspx?SourceUrl=` (aucune adresse signée), le fichier obtenu est renommé et
      rendu ; téléchargement bloqué → gap `DOWNLOAD_BLOCKED`.
- [ ] **Bout en bout des gardes** : emplacement hors liste → aucune `Page.navigate` ; redirection
      vers la page d'identification → aucun script, gap `SIGNED_OUT` ; `Network.getCookies` jamais
      émis ; la vue est remise.
- [ ] `teams_status` rend `diagnostic.observedFilePaths` sans requête ni corps.
- [ ] Les deux outils sont au catalogue runner **et** gateway (listes verrouillées des deux côtés) ;
      ils ne sont **pas** des écritures ; délai gateway allongé pour ces outils.
- [ ] Chaque résultat porte la mention de provenance « forme éprouvée sur documentation, à confirmer
      sur poste réel ».

---

## Périmètre

### Hors scope (explicite)

- Les **écritures** (créer, déposer, renommer, déplacer, supprimer, remplacer une version) — SF-108-04.
- Les **enregistrements** Teams — SF-108-05.
- OneDrive **grand public** (`onedrive.live.com`) : autre API ; refus nommé.
- Graph et le mode application (consentement administrateur) — hors périmètre F-108.
- Tout sélecteur DOM de l'interface SharePoint / Teams pour les fichiers (arbitrage PO).
- Taper dans Word / Excel en ligne.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `location` / `file` | selon l'outil | `https://*.sharepoint.com/...` (domaine §4.1), pas de lien de partage opaque | requête et ancre retirées (sauf `id` d'`AllItems.aspx`), chemin décodé |
| `conversation_id`, `team`, `channel` | Non | chaîne ≤ 2 000 car. (borne gateway existante) | trim, rapprochement sans accents |
| `onedrive` | Non | booléen | — |
| dossier de téléchargement | — | **fixe** : `<volet>/downloads/<id>-v<version>` (jamais un paramètre d'appel) | caractères sûrs |
| attente d'un téléchargement de document | — | 60 s puis « en cours, redemandez » | — |
| éléments listés | — | 500 au plus par dossier, borne dite | — |

---

## Technique

### Composants runner impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `SharePointLocation` | créé | analyse d'une adresse web en origine / site / chemin relatif serveur |
| `SharePointProjection` | créé | liste blanche des clés métier ; même liste pour le script de page et la relecture Java |
| `SharePointPage` | créé | amène l'onglet sur l'origine, exécute un script (démarrage + relève), remet la vue, trace |
| `SharePointFiles` | créé | lister, métadonnées, validation de forme, manques nommés |
| `ChromeDownloads` | créé | dossier fixe, navigation vers `download.aspx`, attente, contrôle taille/HTML, remise du comportement |
| `SyncedLibraries` | créé | racines OneDrive/SharePoint synchronisées, résolution d'un emplacement |
| `TeamsFileTools` | créé | `teams_list_files`, `teams_read_file` |
| `TeamsTools` | modifié | délégation, catalogue 13 → 15, diagnostic dans `teams_status` |
| `PageActions` | modifié | `evaluateOnPage` (gardé par domaine), `resetDownloads` |
| `NetworkObserver` | modifié | relevé des chemins SharePoint/OneDrive observés (sans requête ni corps) |
| `TeamsGapKind` | modifié | `SHAPE_MISMATCH`, `LOCATION_UNKNOWN`, `SIGNED_OUT`, `ACCESS_DENIED`, `NOT_FOUND`, `DOWNLOAD_BLOCKED` |
| `TeamsWorkFolder` | modifié | `downloadsDir()` fixe |
| `ToolStack` | modifié | montage des outils fichiers |
| `FakeCdpConnection` (test) | modifié | navigation, scripts de fichiers sur réponses modèles, téléchargement de papier |
| `src/test/resources/teams/sharepoint-*.json` | créés | réponses modèles d'après la documentation publique |

### Composants gateway impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `TeamsToolCatalog` | modifié | `LIST_FILES`, `READ_FILE` au `CATALOG`, descriptions (lecture, provenance, synchronisé préféré) |
| `RunnerToolGateway` | modifié | délai des outils fichiers (navigation + attente) |
| `TeamsReadingCatalogTest`, `TeamsToolCatalogTest`, `RunnerToolGatewayTest` | tests | catalogue et délai |

### Endpoints / tables

- Aucun endpoint nouveau (relais `tool_call` existant). **Aucune migration.**

### Préoccupations transversales

- **Sécurité (cadrage §7)** : composants listés ci-dessus — `PageActions`, `NetworkObserver`,
  `TeamsTools`, `TeamsToolCatalog`. Gardes appliquées : domaine avant émission (SF-108-01),
  identification refusée, cookies/stockage refusés (liste blanche CDP **inchangée**), aucune donnée
  d'authentification dans ce qui remonte (test), vue remise, trace des gestes.
- **Plans / limites** : les deux outils sont des `teams_*` donnés par `TeamsToolCatalog.toolsFor`
  sous la garde du droit Teams existante — inchangée ; vérifié par `TeamsToolCatalogTest`.
- **Auth / Principal, tenant** : non (identité Microsoft = navigateur ; isolation `user_id` gateway
  inchangée, le relais passe par le workspace du tour).
- **Navigation / routing (front)** : non.

---

## Plan de test

### Tests unitaires (runner)

- [ ] `SharePointLocationTest` — formes reconnues, refus nommés (hors domaine, grand public, partage opaque).
- [ ] `SharePointProjectionTest` — les clés hors liste disparaissent (digest, downloadUrl, jetons) ; le script ne touche ni cookies ni stockage ni en-têtes.
- [ ] `SharePointFilesTest` — réponse modèle → éléments ; forme non conforme → `SHAPE_MISMATCH`, zéro élément ; 403/404 → manques nommés.
- [ ] `SyncedLibrariesTest` — Windows / macOS : OneDrive personnel et bibliothèque synchronisée résolus ; absent → vide.
- [ ] `ChromeDownloadsTest` — fichier obtenu renommé ; HTML reçu → `DOWNLOAD_BLOCKED` ; taille divergente ; rien ne démarre.

### Tests d'intégration (outil complet, navigateur de papier)

- [ ] `TeamsFileToolsTest` — liste par le navigateur (gestes tracés, vue remise, provenance) ; liste synchronisée sans CDP ; lecture par téléchargement ; emplacements connus sans geste ; conversation → dossier des pièces jointes.
- [ ] `TeamsFileGuardsEndToEndTest` — hors liste : aucune navigation ; identification : aucun script ; aucun secret ne ressort ; `Network.getCookies` jamais émis.
- [ ] `TeamsGisementsTest` / `TeamsMomentsToolsTest` / `TeamsCaptureToolsTest` — catalogue à 15.
- [ ] Gateway : `TeamsReadingCatalogTest`, `TeamsToolCatalogTest` (lecture, pas écriture), `RunnerToolGatewayTest` (délai).

### Isolation utilisateur

- Côté gateway, inchangée : l'appel passe par le workspace du tour et le droit Teams du propriétaire
  (tests existants de `toolsFor` sans droit → aucun outil). Côté machine : la session Microsoft est
  celle du navigateur de l'utilisateur ; les fichiers rapatriés restent dans son dossier de volet.

---

## Notes et décisions

- **Arbitrage PO appliqué** : API REST SharePoint appelée depuis la page, pas de sélecteurs DOM ;
  Chrome télécharge ; réponses modèles fabriquées d'après la documentation (voir `PROVENANCE.md`).
- **Décision (réversible)** : script en deux temps (démarrage puis relève), parce qu'une commande
  CDP est bornée à 10 s et qu'un appel réseau de page peut dépasser ce délai.
- **Décision (réversible)** : le téléchargement passe par `/_layouts/15/download.aspx?SourceUrl=`
  (réponse `attachment`) plutôt que `…/$value`, qui s'afficherait dans l'onglet au lieu d'être
  téléchargé.
- **Décision (réversible)** : le comportement de téléchargement est remis par défaut dès que le
  fichier a démarré, pour ne pas détourner les téléchargements de l'utilisateur.
- **Risque accepté, dit dans le résultat** : forme non éprouvée sur un tenant réel ; toute
  divergence échoue bruyamment (`SHAPE_MISMATCH`).
