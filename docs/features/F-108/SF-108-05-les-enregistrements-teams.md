# Mini-spec — F-108 / SF-108-05 — Les enregistrements Teams

> Base : `docs/features/F-108/CADRAGE-F-108-agir-dans-microsoft-365.md` §2 (l'adresse signée ne passe
> jamais par notre code — **conservé**), §4 (gardes), §5.2 et §6. **Arbitrage du PO du 2026-09-13**,
> point 4 : téléchargement par Chrome puis chaîne F-90 existante ; transcription servie par Teams ou
> téléchargée, sinon transcription locale F-91 ; téléchargement bloqué → manque nommé. S'appuie sur
> l'infrastructure livrée en SF-108-03 (`SharePointLocation`, `SharePointPage`, `ChromeDownloads`).

## Identifiant

`F-108 / SF-108-05`

## Feature parente

`F-108` — Agir dans Microsoft 365 : fichiers, enregistrements, gestes

## Statut

`done` — PR #516

## Date de création

2026-09-13

## Branche Git

`feat/SF-108-05-enregistrements-teams`

---

## Objectif

Faire de `teams_meeting_recording` l'outil qui **rapatrie** l'enregistrement d'une réunion sur la
machine — Chrome télécharge, l'adresse signée ne passe jamais par notre code —, trouve sa
transcription (Teams, fichier `.vtt`, sinon transcription locale F-91), et laisse
`teams_meeting_moments` enchaîner la chaîne F-90 sur le fichier obtenu, sans qu'on lui redonne de
chemin.

---

## Comportement attendu

### Cas nominal

1. **Où est l'enregistrement** : `recording_url` si l'agent la donne (adresse web du `.mp4`) ; sinon
   l'adresse **observée** dans les messages du fil de la réunion (message d'enregistrement ou pièce
   jointe : `https://*.sharepoint.com/…/*.mp4`, requête retirée). Rien d'observé → manque nommé et
   remède (« ouvrez le fil de la réunion dans Teams, ou donnez recording_url »).
2. **Téléchargement par Chrome** (lecture, **aucune confirmation**) : `SharePointPage` amène l'onglet
   sur le site, lit les métadonnées du fichier (taille, identifiant, version), `ChromeDownloads`
   dirige Chrome vers le dossier **fixe** `<volet>/downloads/rec-<réunion>` et navigue vers
   `download.aspx?SourceUrl=` ; le démarrage est constaté, le comportement de téléchargement remis
   par défaut, la vue remise. L'outil **rend la main** (traitement lourd = asynchrone) :
   `inProgress: true`, octets reçus / annoncés. Un fichier-témoin `rec-<réunion>.json` (nom, taille,
   adresse web — **aucun jeton**) permet aux rappels de suivre **sans aucun geste**.
3. **Terminé** : `downloaded: true`, `video` = chemin local ; le fichier **reste sur la machine**
   (règle F-90).
4. **Transcription**, dans cet ordre :
   1. **Teams** : les répliques déjà servies par Teams pour cette réunion (`TEAMS`) ;
   2. **Fichier `.vtt`** : un `.vtt` de même nom que l'enregistrement dans son dossier
      (`GetFolderByServerRelativePath(…)/Files`) est **téléchargé par Chrome** et lu (WebVTT, locuteur
      `<v Nom>`) (`VTT_FILE`) ;
   3. **Locale (F-91)** : à défaut, l'audio du fichier est transcrit **sur la machine**, en tâche de
      fond (`LOCAL`), dans un dossier de travail distinct ;
   4. rien de tout cela → manque nommé (`NONE`) et remède.
5. **Chaîne F-90** : `teams_meeting_moments` avec `meeting_id` et **sans** `video` prend
   l'enregistrement téléchargé ; ses répliques viennent de Teams, sinon du `.vtt`, sinon de la
   transcription locale (terminée). Transcription locale encore en cours → refus nommé « attendez ».
6. **Provenance** : chaque résultat porte « forme éprouvée sur documentation, à confirmer sur poste
   réel » ; les gestes et la vue remise sont écrits.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `meeting_id` absent | Manque `MISSING_FIELD`, rien n'est tenté |
| Adresse de l'enregistrement non observée et non donnée | `LOCATION_UNKNOWN`, remède, `downloaded: false`, aucun geste |
| `recording_url` hors domaines / non SharePoint | `LOCATION_UNKNOWN` nommé, aucune navigation |
| Téléchargement qui ne démarre pas (organisateur / politique du tenant) | `DOWNLOAD_BLOCKED` : « téléchargement bloqué par l'organisateur ou la politique du tenant », jamais un silence |
| Page HTML reçue / taille divergente | `DOWNLOAD_BLOCKED` / `SHAPE_MISMATCH`, fichier écarté |
| Page d'identification | `SIGNED_OUT`, aucun script, vue remise |
| Métadonnées non conformes au modèle | `SHAPE_MISMATCH`, rien n'est téléchargé |
| Transcription locale non montée | `transcript.source = NONE` + manque nommé |
| `teams_meeting_moments` avant la fin du téléchargement | refus nommé « téléchargement en cours » |

---

## Critères d'acceptation

- [ ] `teams_meeting_recording` télécharge par Chrome : `Browser.setDownloadBehavior` vers le dossier
      fixe, navigation vers `download.aspx?SourceUrl=`, **aucune adresse signée** émise ni rendue.
- [ ] L'adresse de l'enregistrement est retrouvée dans un message observé du fil de la réunion ; sans
      elle, manque nommé et **aucun geste**.
- [ ] Rappel pendant le téléchargement : état suivi **sans aucun geste d'action** (ni navigation, ni script, ni réglage de téléchargement ; seule la récolte ordinaire de la page a lieu) ; fin → `downloaded: true`.
- [ ] Téléchargement bloqué → `DOWNLOAD_BLOCKED` nommé.
- [ ] Transcription : Teams si servie ; sinon `.vtt` voisin téléchargé et lu (locuteur, horodatage) ;
      sinon transcription locale démarrée ; sinon manque nommé.
- [ ] `teams_meeting_moments` sans `video` démarre la chaîne F-90 sur l'enregistrement téléchargé,
      avec les répliques de la meilleure source disponible.
- [ ] Le fichier-témoin ne contient ni jeton, ni cookie, ni adresse signée (test).
- [ ] Description gateway de `teams_meeting_recording` mise à jour (il télécharge ; il ne fait pas
      croire qu'un fichier existe tant que `downloaded` est faux) ; outil toujours classé **lecture**.

---

## Périmètre

### Hors scope (explicite)

- Lire la transcription de Stream par son API `_api/v2.1/…/media/transcripts` : **non documentée
  publiquement** → non implémentée en aveugle ; le relevé réel (F-100) dira si elle est empruntée.
- Lire une transcription `.docx` (son chemin est rendu, elle n'est pas analysée).
- Ouvrir la page de réunion par son lien de participation (risque de **rejoindre** la réunion).
- Écritures (SF-108-04). Poster un message (hors F-108).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `meeting_id` | Oui | chaîne ≤ 2 000 car. | trim ; nom de dossier réduit aux caractères sûrs |
| `recording_url` | Non | `https://*.sharepoint.com/…` (règles SF-108-03) | requête retirée |
| `download` | Non | booléen, défaut vrai | — |
| dossier de téléchargement | — | **fixe** `<volet>/downloads/rec-<réunion>` | caractères sûrs |
| attente du démarrage | — | 20 s puis `DOWNLOAD_BLOCKED` | — |
| `.vtt` lu | — | ≤ 5 Mo, UTF-8 | — |

---

## Technique

### Composants runner impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `TeamsRecordingTools` | créé | localisation, téléchargement, témoin, transcription, rendu |
| `VttTranscript` | créé | lecture WebVTT → `TeamsTranscriptCue` |
| `TranscriptionWorker` | modifié | transcription d'un **fichier** (hors capture), dossier de travail distinct |
| `TeamsTools` | modifié | `meetingRecording` délégué ; `meetingMoments` prend l'enregistrement téléchargé et ses répliques |
| `FakeCdpConnection` (test) | réutilisé | téléchargement de papier (SF-108-03) |
| `src/test/resources/teams/` | créés | message d'enregistrement, `.vtt`, métadonnées `.mp4` |

### Composants gateway impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `TeamsToolCatalog` | modifié | description et schéma de `teams_meeting_recording` (`recording_url`, `download`) ; `teams_meeting_moments` : `video` facultatif avec `meeting_id` |
| `RunnerToolGateway` | modifié | délai long pour `teams_meeting_recording` (navigation + démarrage) |
| `TeamsReadingCatalogTest`, `RunnerToolGatewayTest` | tests | description et délai |

### Endpoints / tables

- Aucun endpoint, **aucune migration**.

### Préoccupations transversales

- **Sécurité (§7)** : composants `TeamsTools`, `TeamsRecordingTools`, `ChromeDownloads`,
  `SharePointPage`, `TeamsToolCatalog`. Garde §2 conservée (Chrome télécharge, adresse non signée),
  gardes §4.1–4.3 via `PageActions`, vue remise, cookies/stockage refusés.
- **Plans / limites** : outil existant, sous la garde du droit Teams de `toolsFor` — inchangée.
- **Auth / tenant, navigation front** : non.

---

## Plan de test

### Tests unitaires

- [ ] `VttTranscriptTest` — horodatages, locuteur `<v>`, multi-lignes, entrée illisible → vide.
- [ ] `TranscriptionWorkerTest` (étendu) — transcription d'un fichier sans capture : répliques, pas d'écriture dans le magasin des captures.

### Tests d'intégration (navigateur de papier)

- [ ] `TeamsRecordingToolsTest` — adresse observée → téléchargement démarré (dossier fixe, `download.aspx`, rien de signé) ; rappel sans geste → terminé ; téléchargement bloqué nommé ; adresse inconnue → aucun geste ; `.vtt` voisin lu ; transcription locale démarrée à défaut ; témoin sans secret.
- [ ] `TeamsMomentsToolsTest` (étendu) — `meeting_id` seul → travail F-90 démarré sur la vidéo téléchargée.
- [ ] `TeamsGisementsTest` — test « il ne télécharge pas » **remplacé** par le nouveau contrat.
- [ ] Gateway : `TeamsReadingCatalogTest` (description), `RunnerToolGatewayTest` (délai).

### Isolation utilisateur

- Inchangée côté gateway (droit Teams du propriétaire, workspace du tour) ; la vidéo reste dans le
  dossier de volet de la machine de l'utilisateur ; seules les images retenues par F-90 remontent,
  vers le terminal revérifié du propriétaire (existant).

---

## Notes et décisions

- **Décision (réversible)** : le téléchargement d'un enregistrement rend la main dès son démarrage
  (règle async) ; le suivi se fait par rappel, sans geste, grâce au fichier-témoin.
- **Décision (réversible)** : l'API Stream des transcriptions n'est pas appelée (non documentée) ;
  `.vtt` voisin puis transcription locale.
- **Décision (réversible)** : l'origine du temps d'un `.vtt` est le début de réunion observé (ou
  `video_started_at`), comme pour les répliques Teams — même hypothèse que F-90.
- **Constat du relevé réel du 2026-09-13** (noté par F-89 / SF-89-05, rien de réécrit ici) : à
  l'étape « réunion passée », Teams web lit l'enregistrement **en flux** —
  `/_api/v2.1/drives/{id}/items/{id}/content` en `application/dash+xml` depuis un **worker** (un
  manifeste de lecture, pas le fichier) et `/personal/{id}/_layouts/15/streamembed.aspx` (lecteur
  intégré, onglet + service worker). Ni l'un ni l'autre n'est un `.mp4` téléchargeable : le
  téléchargement **par Chrome** reste l'approche de cette sous-feature.
