# F-108 — Agir dans Microsoft 365 : fichiers, enregistrements, gestes

> Cadrage du 2026-09-13. **Décision de sécurité prise par le PO** : *« Oui j'autorise, cadre ça et
> relance la livraison »*, en réponse à : « autorisez-vous le runner à agir dans le navigateur, limité
> aux domaines Microsoft, avec confirmation de chaque écriture ? ».
> Rouvre explicitement deux décisions de F-87 / F-88 (voir §2). Livrée dans la vague du 2026-09-13.

## 1. Le besoin

Un prospect, prêt à acheter, conditionne l'achat à la partie Teams. Ses consultants font déjà, avec
Codex sur leur PC personnel connecté à Teams : lire conversations et réunions, **accéder aux fichiers
déposés dans Teams, créer des dossiers, modifier les fichiers**, et produire des **comptes rendus
d'enregistrements Teams avec captures et chronologie**.

**Vérifié le 2026-09-13** : Codex n'a pas d'accès plus puissant que la session de l'utilisateur. Son
extension Microsoft passe par Graph et exige le consentement d'un administrateur du tenant client (et
ne rend pas le fichier d'un enregistrement) ; ce que le prospect a montré passe très probablement par
son **extension Chrome** (agir dans le navigateur connecté), son navigateur intégré, ou **Computer
Use** (piloter les applications installées). **La différence avec nous est une décision, pas une
technologie** : notre runner observe sans agir.

## 2. Les décisions rouvertes

| Décision d'origine | Où | Devient |
|---|---|---|
| « **Écrire dans Teams** — répondre, publier, réagir. On lit. » (hors périmètre) | `CADRAGE-volet-teams.md` §10 | **Écrire des fichiers** dans Teams / SharePoint / OneDrive est **dans le périmètre**, chaque écriture confirmée. **Poster un message, répondre, réagir restent hors périmètre.** |
| « Ni `Page.navigate`, ni `Input.dispatch*` […] Cette décision de sécurité ne se rouvre pas pour une commodité » | `PageGestures`, `CdpCommands` | **Rouverte par le PO** : navigation, clic, saisie, dépôt de fichier et téléchargement autorisés, **sur les seuls domaines Microsoft**, sous les gardes du §4 |
| L'enregistrement n'est jamais téléchargé (adresse signée retirée, SF-87-02) | `TeamsTools.meetingRecording` | Le fichier est **téléchargé par Chrome lui-même** ; **l'adresse signée ne passe toujours jamais par notre code** — cette garde-là est **conservée** |

**Ce qui ne bouge pas** : cookies et stockage restent refusés nommément (`Network.getCookies`,
`Storage.*`) ; aucun jeton ni cookie Microsoft ne quitte la machine ; la lecture reste faite **par le
trafic réseau** (rapide, peu coûteuse, résistante aux refontes).

## 3. Le principe : lire par le réseau, agir par le navigateur

- **Lire** : inchangé — observer ce que la page reçoit.
- **Agir** : gestes réels dans l'onglet (naviguer, cliquer, taper, déposer, télécharger), **uniquement
  pour écrire ou télécharger**, et **pour atteindre** ce qu'il faut lire (ouvrir une réunion, sa
  transcription, une bibliothèque de fichiers).
- **Préférer le local quand il existe** : si les fichiers d'une équipe sont **synchronisés par OneDrive**
  sur la machine, les outils fichiers existants du poste font le travail, sans geste.

## 4. Les gardes

1. **Domaines autorisés, liste close** : `teams.microsoft.com`, `teams.cloud.microsoft`,
   `teams.live.com`, `*.sharepoint.com`, `onedrive.live.com`, `*.office.com`, `*.officeapps.live.com`,
   `*.cloud.microsoft`. Toute navigation hors liste est **refusée avant émission** ; un geste sur une
   page qui a quitté la liste (redirection) est refusé.
2. **Jamais sur une page d'identification** (`login.microsoftonline.com`, `login.live.com`,
   `login.microsoft.com`) : aucun clic, aucune saisie. Une session expirée produit le geste « rouvrez
   Teams et reconnectez-vous », jamais une tentative de connexion.
3. **Jamais de saisie dans un champ mot de passe** ni de lecture d'un champ de ce type.
4. **Chaque écriture est confirmée** par l'utilisateur, par le mécanisme d'autorisation existant du
   terminal (charte §12) : créer un dossier, déposer, renommer, déplacer, **supprimer**, remplacer une
   version. La demande nomme l'action et l'emplacement en clair (« Créer le dossier « Livrables » dans
   Équipe Projet IAM › Général › Fichiers »). **Les lectures et téléchargements ne demandent rien.**
5. **Coupe-circuit** (SF-38-08) : arrête aussi les gestes.
6. **Trace** : chaque geste d'action est journalisé (outil, domaine, action, cible nommée, résultat),
   jamais le contenu d'un champ saisi au-delà de ce que l'utilisateur a confirmé.
7. **La vue de l'utilisateur est remise** après un geste dans son onglet, et **ce qui a été fait est
   écrit** dans le résultat de l'outil.
8. **Observation élargie aux cadres et workers de la page**, filtrée sur les mêmes domaines
   (`Target.setAutoAttach`) : lève les angles morts relevés en F-100 (lecteur Stream intégré, service
   worker). Un cadre ou un worker hors liste n'est pas attaché.

`CdpCommands` : la liste blanche passe de cinq à la liste strictement nécessaire (`Page.navigate`,
`Input.dispatchMouseEvent`, `Input.dispatchKeyEvent`, `Input.insertText`, `DOM.getDocument`,
`DOM.querySelector`, `DOM.setFileInputFiles`, `Browser.setDownloadBehavior`, `Target.setAutoAttach`,
et les événements associés) ; **chacune est gardée par la vérification de domaine** ; les refus nommés
cookies et stockage restent, et le test qui garde la liste est mis à jour, pas supprimé.

## 5. Les capacités

### 5.1 Les fichiers Teams, SharePoint, OneDrive

- **Lire** : lister les fichiers d'une équipe, d'un canal, d'une conversation ou de OneDrive ; lire un
  fichier (téléchargé par Chrome dans le dossier de travail du volet, puis lu par les outils du poste).
- **Écrire** (confirmé) : créer un dossier, déposer un fichier, renommer, déplacer, supprimer.
- **Modifier un document** : **télécharger → modifier sur la machine → redéposer comme nouvelle
  version**. SharePoint garde l'historique des versions : c'est le filet, et la confirmation le dit.
  Taper dans Word ou Excel en ligne est écarté : trop fragile.
- **Dossier synchronisé** : le runner reconnaît les dossiers OneDrive / SharePoint synchronisés
  (Windows : `OneDrive - <organisation>` et bibliothèques synchronisées ; macOS : `~/Library/CloudStorage`)
  et **les préfère** ; l'outil dit lequel des deux chemins il a pris.

### 5.2 Les enregistrements Teams

- `teams_meeting_recording` **télécharge** : ouvre l'enregistrement, déclenche le téléchargement dans
  Chrome, vers le dossier de travail du volet ; **le fichier reste sur la machine** (règle F-90).
- La **chaîne existante** s'applique ensuite : scènes, captures, alignement sur la transcription,
  compte rendu illustré (F-90). **Un enregistrement Teams devient une source aussi riche qu'une capture
  locale.**
- **Transcription** : si Teams ne l'a pas servie, elle est **ouverte** (geste) ou **téléchargée**
  (`.vtt` / `.docx`) ; à défaut, transcription locale de l'audio (F-91).
- **Téléchargement bloqué** par l'organisateur ou la politique du tenant : **manque nommé**, jamais un
  silence.

### 5.3 Le Radar en profite

La synchro du soir (F-100) **navigue** vers le calendrier, chaque réunion et sa transcription au lieu
de chercher un élément à cliquer, et observe les cadres et workers de la page. **Aucune écriture dans
la synchro du soir** : elle ne fait que lire, donc elle ne demande aucune confirmation.

## 6. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-108-01 | Les gestes d'action et leurs gardes | Liste blanche CDP étendue, vérification de domaine avant émission, refus des pages d'identification et des champs mot de passe, auto-attach filtré, restauration de la vue, journal des gestes ; tests d'architecture et de refus |
| SF-108-02 | La confirmation des écritures | Classement lecture / écriture par outil ; écritures soumises à l'autorisation du terminal avec action et emplacement en clair ; coupe-circuit ; tests (écriture refusée sans accord, lecture sans demande) |
| SF-108-03 | Lire les fichiers | Outils de liste et de lecture (équipe, canal, conversation, OneDrive), téléchargement par Chrome, dossiers synchronisés préférés |
| SF-108-04 | Écrire les fichiers | Créer un dossier, déposer, renommer, déplacer, supprimer, remplacer une version (télécharger → modifier → redéposer) |
| SF-108-05 | Les enregistrements Teams | Téléchargement par Chrome, chaîne F-90 sur le fichier obtenu, transcription ouverte ou téléchargée, manques nommés |

**Ordre** : 01 → 02 → (03 ∥ 05) → 04. F-100 (synchro du soir) s'appuie sur SF-108-01.

## 7. Préoccupations transversales

- **Sécurité : oui**, c'est l'objet même. Composants : `CdpCommands`, `PageGestures`, `BrowserLink`,
  `BrowserTargets`, `NetworkObserver`, `TeamsTools`, `TeamsToolCatalog` (backend), le mécanisme
  d'autorisation du terminal, le coupe-circuit, le journal d'audit runner.
- **Plans / limites : oui.** Outils gardés par le droit Teams aujourd'hui, par le droit **Vigie** après
  F-107 (`buildTools`).
- **Auth / Principal, tenant : non** (l'identité Microsoft reste celle du navigateur ; côté gateway,
  isolation `user_id` inchangée).

## 8. Hors périmètre

- **Poster un message, répondre, réagir** dans Teams.
- Taper dans Word, Excel ou PowerPoint en ligne.
- Piloter les applications installées (façon Computer Use) : possible plus tard en relayant l'outil
  du fournisseur.
- Toute action sur une page d'identification, les cookies ou le stockage du navigateur.
- Graph et le mode application (consentement administrateur du tenant client).
