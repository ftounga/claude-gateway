# SF-108-07 — Confirmer et recaler la lecture transcription + fichiers SharePoint sur poste réel

> Cadrage du 2026-09-15 (PO). **Cadrage seul, dev « tout à l'heure ».** Sous-feature de F-108.

## Objectif (une phrase)
Confirmer sur un poste réel (CAGIP) — et recaler si la forme diffère — la lecture de la **transcription**
et des **fichiers SharePoint/OneDrive** (SF-108-03/05/06 ont été écrites « sur documentation, à confirmer
sur poste réel »), en s'appuyant sur un relevé « forme » des réponses SharePoint, comme SF-89-12/13 l'a
fait pour les réunions.

## Contexte
- La chaîne existe déjà : **SF-108-05** (`teams_meeting_recording` : transcription native Teams → `.vtt`
  voisin → transcription locale F-91), **SF-108-06** (`teams_read_docx` : texte d'un `.docx`), et
  **SF-89-13** localise le fichier par le réseau (`driveId`/`driveItemId` du récapitulatif).
- Mais les adaptateurs SharePoint/OneDrive et la forme des réponses de téléchargement **n'ont jamais été
  validés sur un vrai poste** — mention récurrente « éprouvé sur documentation, à confirmer sur poste réel »
  (SF-108-03/04/05/06). Le relevé du 2026-09-15 montre que les chemins SharePoint (`/_api/v2.1/drives/{id}/items/{id}[/content]`,
  `graph .../items/{id}/preview`, `streamembed.aspx`) restent classés **UNKNOWN** (corps non lus).

## Comportement attendu
1. À partir du récap (driveId/driveItemId, SF-89-13), la chaîne existante localise → télécharge (Chrome,
   adresse non signée) → lit le texte de la transcription (`.vtt`/`.docx`). Ce chemin est **exercé et
   confirmé** sur CAGIP.
2. Si la **forme réelle** des réponses SharePoint/Graph diffère des formes fabriquées sur documentation
   (localisation du drive item, adresse de téléchargement du `.vtt`/`.docx`, métadonnées), **recaler** les
   adaptateurs sur la vraie forme (méthode SF-89-13 : relevé « forme » des corps SharePoint → mapping).
3. **Droits** : suppose les droits d'accès de l'utilisateur (policy Teams `whoCanAccessTranscriptAndRecording`
   + permissions SharePoint). Aucun privilège ajouté, aucun secret rapatrié, adresse signée jamais dans
   notre code (Chrome télécharge). Blocage organisateur/tenant → **manque nommé**, jamais un contournement.

## Cas d'erreur
- Transcription non disponible / non partagée / policy restrictive → message « accès refusé / non
  disponible », pas un silence.
- Forme SharePoint divergente → zéro élément + manque nommé (jamais inventer), et la sonde de santé le dit.

## Critères d'acceptation
- Sur CAGIP, pour une réunion dont l'utilisateur a les droits : la transcription est **lue par le fichier**
  (source réseau/fichier, pas lecture-écran), texte non vide.
- Une réunion sans droit de transcription → échec **nommé**.
- Aucun jeton/cookie/adresse signée ne transite par notre code (test de garde conservé).

## Plan de test
- Unitaires : adaptateurs SharePoint recalés sur fixtures reconstruites du relevé « forme » (noms/types
  réels, valeurs synthétiques) ; garde « rien recopié en aveugle ».
- Poste réel (manuel) : relevé « forme » des réponses SharePoint sur CAGIP, puis lecture transcription
  bout-en-bout dans le terminal Teams.

## Groupement avec SF-89-15 (décision PO 2026-09-16)
Livrée **conjointement avec SF-89-15**, à partir du **même** relevé « forme » complet du catalogue
(SF-89-14) : une seule capture sur CAGIP exerce à la fois les surfaces Teams (calendrier liste, réunion,
récap, conversations/messages) **et** l'étape transcription/fichiers SharePoint. On recale donc en un
bloc : SF-89-15 = les 6 familles Teams ; SF-108-07 = fichiers + transcription SharePoint.

## Prérequis
Un relevé « forme » (SF-89-12/14) exerçant l'étape **transcription/fichiers** sur CAGIP (drives/items,
téléchargement `.vtt`/`.docx`) — **le même** que celui qui alimente SF-89-15.

## Hors périmètre
- Lire une transcription affichée par l'écran (SF-89-06, repli fragile, conservé).
- Nouveaux gestes d'écriture (déjà couverts SF-108-04/06).

---

## Mini-spec finale (complétée le 2026-09-16 — livraison autonome, groupée avec SF-89-15)

### Identifiant & branche
`F-108 / SF-108-07` — feature parente `F-108` (Terminée ; correctif/complément d'une feature livrée).
Branche `feat/SF-108-07-droits-fichiers-poste-reel`. Statut : `in-progress`.

### Objectif (une phrase)
Recaler les adaptateurs SharePoint sur la forme **réelle** de l'API `v2.1` du relevé catalogue CAGIP
2026-09-16 (`docs/features/F-100/releves/releve-teams-catalogue-complet-2026-09-16-cagip.json`) pour
savoir — **avant de tenter** — si un fichier est téléchargeable (`accessViewpoint.canDownload`,
`irmCapabilities.canExtract`), et **nommer** le refus quand il ne l'est pas.

### Formes réelles utilisées (section `unknownShapes` du relevé)
- `/_api/v2.1/drives/{id}/items/{id}` : `{ @odata.context, @odata.etag, webUrl, sharepointIds:{siteUrl},
  expiration:{…}, accessViewpoint:{ canRead, canEdit, canDelete, canComment, canDownload,
  canManagePermissions } }`.
- `/_api/v2.1/drives/{id}/items/{id}/labelPolicies` : `{ @odata.context, @odata.type,
  irmCapabilities:{ canPrint, canExtract, canRead, canWrite } }`.

### Comportement attendu
1. `SharePointItemAccess.parse(item, labelPolicies)` lit **par leur nom** `webUrl` + les booléens
   `accessViewpoint`/`irmCapabilities`. `downloadRefusal()` rend une raison nommée :
   `canDownload=false` → droits/politique ; `irmCapabilities.canExtract=false` → protection IRM.
   **Droits inconnus** (`accessViewpoint` absent) → **on ne bloque pas** (la tentative dira le reste).
2. `SharePointFiles.driveItemAccess(visit, driveId, driveItemId)` interroge l'API `v2.1` **depuis la
   page** ; la décision est prise **dans la page** (elle seule voit `accessViewpoint` — la projection
   sur liste blanche écarte volontairement toute clé « download », garde contre `@content.downloadUrl`),
   et le script ne rend qu'un **code machine** (`canDownload=false`/`canExtract=false`), jamais
   `accessViewpoint`, jamais une adresse signée. Le libellé français vit dans `SharePointItemAccess`
   (source unique).
3. La chaîne d'enregistrement (`TeamsRecordingTools.download`) consulte ces droits **avant** de tenter,
   quand le récapitulatif (SF-89-13) localise le fichier (`driveId`/`driveItemId`) : un refus est
   **nommé** (`DOWNLOAD_BLOCKED`) et **rien n'est tenté**. Garde conservée : aucune adresse signée, aucun
   jeton, aucun cookie dans notre code (c'est Chrome qui télécharge).

### Cas d'erreur
| Situation | Comportement |
|-----------|--------------|
| `accessViewpoint.canDownload = false` | Refus **nommé** `DOWNLOAD_BLOCKED` (droits/politique) ; aucune tentative |
| `irmCapabilities.canExtract = false` | Refus **nommé** `DOWNLOAD_BLOCKED` (IRM) ; aucune tentative |
| `accessViewpoint` absent / corps illisible / fetch impossible | **On ne bloque pas** (droits inconnus) ; la tentative dira le reste — jamais inventer |
| Corps `null` | Aucune exception ; droits inconnus |

### Critères d'acceptation
- [ ] `SharePointItemAccess.parse` lit `webUrl`/`accessViewpoint`/`irmCapabilities` sur la forme réelle.
- [ ] `canDownload=false` → `downloadRefusal()` **nommé** ; `canExtract=false` → refus IRM **nommé**.
- [ ] Droits inconnus → **pas** de refus.
- [ ] « Rien recopié en aveugle » : ni adresse signée (`@content.downloadUrl`), ni jeton, ni champ
      inconnu ne franchit la couche (seuls `webUrl` + booléens sortent).
- [ ] Le script de droits lit le **brut** dans la page (pas de `pick`) et ne rend qu'un **code**.
- [ ] Chaîne d'enregistrement : un fichier `canDownload=false` → `DOWNLOAD_BLOCKED` **avant** toute
      navigation `download.aspx`.
- [ ] `cd runner && ./mvnw -q test` (teams) **vert**.

### Ce qui N'EST PAS re-vérifiable depuis ce relevé (dit, non inventé)
- La lecture **bout-en-bout** sur poste réel (CAGIP) — transcription `.vtt`/`.docx` lue par le fichier,
  texte non vide — reste **à confirmer sur poste réel** (poste manuel). Le relevé donne la **forme** des
  droits, pas un aller-retour de téléchargement exécuté.
- Le corps `v2.1` capté ne portait **pas** `@content.downloadUrl` (GET de métadonnées sans `$expand`) ;
  la garde « rien recopié en aveugle » couvre le cas où une réponse réelle en porterait un.
- Les adaptateurs de **lecture de fichiers** SF-108-03/05/06 (API REST v1, `GetFileByServerRelativePath`)
  ne divergent pas de la forme documentée sur les champs qu'ils lisent (Name/ServerRelativeUrl/Length…) :
  aucun recalage nécessaire de ce côté ; le manque comblé est le **contrôle de droits** (v2.1), absent.

### Préoccupations transversales
Aucune (Auth/Principal, Contexte tenant, Plans/limites, Navigation/routing) : SF **runner-local**
(adaptateurs SharePoint + chaîne enregistrement), sans endpoint, sans base, sans frontend, sans auth.

### Composants impactés
| Composant | Opération |
|-----------|-----------|
| `SharePointItemAccess` (nouveau) | forme réelle des droits `v2.1` ; `downloadRefusal()` ; lecture par nom |
| `SharePointFiles` | `driveItemAccess(visit, driveId, driveItemId)` + script de décision en-page (code machine) |
| `TeamsRecordingTools.download` | consulte les droits avant de tenter, quand le récap localise le fichier ; refus nommé |
| Fixtures | `sharepoint-driveitem-cagip.json`, `sharepoint-driveitem-labelpolicies-cagip.json`, `readcollabobject-fabrique.json` |

### Plan de test
- Unitaires (`SharePointItemAccessTest`) : forme réelle lue ; `canDownload=false` → refus nommé ;
  IRM `canExtract=false` → refus nommé ; droits inconnus → pas de refus ; **non-fuite** (adresse
  signée/jeton/champ inconnu) ; corps `null`.
- Unitaires (`SharePointFilesTest`) : le script de droits vise la forme v2.1, lit le brut, ne projette
  pas, ne rend qu'un code.
- Intégration (`TeamsRecordingToolsTest`) : `canDownload=false` → `DOWNLOAD_BLOCKED` **avant** toute
  tentative de téléchargement (aucune navigation `download.aspx`).
- Non-régression : `SharePointProjectionTest`, `SharePointFilesTest`, `TeamsRecordingToolsTest`
  existants verts ; suite teams verte.

### Isolation workspace / tenant
Non applicable — le runner observe/agit dans le navigateur du poste ; suppose les droits de
l'utilisateur, aucun privilège ajouté ; aucune donnée multi-tenant côté backend.

### Dépendances
- `SF-89-13` (récap → `driveId`/`driveItemId`) et `SF-89-15` (même relevé complet) — **Done**.
- Aucune question ouverte de `docs/OPEN_QUESTIONS.md`.
