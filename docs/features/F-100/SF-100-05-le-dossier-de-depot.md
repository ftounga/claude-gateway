# Mini-spec — F-100 / SF-100-05 — Le dossier de dépôt

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §9 (« les enregistrements hors Teams : un dossier de dépôt
> sur le poste, relevé par la synchro, transcrit sur la machine par le moteur de F-91 ; seul le texte
> remonte ») et §12 bis SF-100-05. Cadrage validé.

## Identifiant

`F-100 / SF-100-05`

## Feature parente

`F-100` — Le Radar : la synchro du soir

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-100-05-dossier-de-depot`

---

## Objectif

Relever, à chaque synchro, les enregistrements déposés dans `<racine>/radar/depot/` (téléphone, salle,
autre outil de visio), les **transcrire sur la machine** (moteur F-91) et ne faire remonter **que le texte**,
en lots `LOCAL_RECORDING` idempotents, chaque enregistrement une seule fois.

---

## Contexte

Le Radar suit des sujets à travers toutes les sources ; une réunion enregistrée hors de Teams n'a ni fil ni
transcription Microsoft. Le moteur de transcription locale existe (F-91 / SF-91-03, `TranscriptionWorker`,
déjà réutilisé par F-108 / SF-108-05 pour un fichier quelconque). Le dépôt **depuis l'écran** (relais du
fichier vers le runner, date et titre demandés) est F-104 / SF-104-04 : il déposera dans ce même dossier.

---

## Comportement attendu

### Cas nominal

1. Au démarrage du runner, le dossier `<racine>/radar/depot/` est **créé s'il manque** et dit sur la console
   (« Radar : déposez ici les enregistrements hors Teams »).
2. À chaque synchro, **après** la collecte Teams, le collecteur de dépôt liste le dossier (sans descendre dans
   les sous-dossiers) : fichiers audio ou vidéo (`.mp3 .m4a .wav .ogg .aac .flac .mp4 .mov .mkv .webm`),
   non vides, **stables** (non modifiés depuis 2 minutes — une copie en cours n'est pas lue).
3. Chaque enregistrement a une **référence stable** : `depot:` + empreinte (nom, taille, date de modification).
   Les références déjà transcrites (curseurs `DEPOT` envoyés par la gateway) sont **sautées**.
4. **Titre et date** : un fichier compagnon `<nom>.json` (`{"title": "...", "date": "2026-09-13T10:00"}`)
   fait foi ; sinon un nom de la forme `2026-09-13 10h00 - Titre.m4a` ; sinon le nom du fichier et sa date
   de modification (la couverture le dit : « date déduite du fichier »).
5. **Transcription sur la machine** (`TranscriptionWorker.startOrResumeFile`, dossier de travail du volet) ;
   pendant qu'elle dure, la synchro **bat** (« depot », n sur N) ; une synchro close l'arrête.
6. Les répliques deviennent un échange `LOCAL_RECORDING` (`conversationRef` = la référence, titre, répliques
   horodatées depuis la date de l'enregistrement, locuteur s'il est connu) découpé en lots au contrat F-101 ;
   le curseur `RECORDING` part avec le dernier lot. **L'enregistrement, l'audio et les fichiers de travail ne
   quittent jamais la machine** ; le fichier déposé n'est ni déplacé ni supprimé.
7. Au plus **3 enregistrements par synchro** (la transcription est longue) : le reste est **reporté** et dit.
8. Couverture : `depot` = `{ found, transcribed, skipped, deferred, failed, unavailable }` et, dans `threads`,
   les enregistrements non transcrits (`kind = RECORDING`, statut, raison) ; issue `PARTIAL` si un
   enregistrement a échoué ou a été reporté.
9. Gateway : les curseurs `RECORDING` sont rangés sous la source `DEPOT` ; l'entrée de `teams_radar_collect`
   porte `depot_done` (références déjà transcrites, 2 000 au plus) ; la phrase de tête compte « N
   enregistrements déposés non transcrits ».

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Moteur de transcription absent sur le poste | enregistrement compté `unavailable` avec le remède ; rien ne remonte ; retenté à la synchro suivante |
| Transcription échouée (audio illisible, modèle introuvable) | compté `failed` avec la raison ; retenté à la synchro suivante |
| Transcription sans aucune réplique | compté `failed` (« aucune parole reconnue ») |
| Fichier en cours de copie (modifié il y a moins de 2 min) | ignoré à cette synchro, sans bruit |
| Fichier compagnon illisible | repli sur le nom puis la date du fichier, dit |
| Lot refusé par la gateway | compté `failed`, curseur non avancé, retenté |
| Dossier de dépôt illisible | couverture `depot.readable = false`, la synchro continue |
| Synchro close pendant une transcription | arrêt, aucun lot de plus |

---

## Critères d'acceptation

- [ ] Le dossier `<racine>/radar/depot/` est créé et annoncé ; seuls les fichiers audio/vidéo stables, non
      vides, au premier niveau sont relevés.
- [ ] Référence stable ; déjà transcrit (curseur `DEPOT`) → sauté.
- [ ] Titre et date : compagnon JSON, puis nom daté, puis fichier (dit).
- [ ] Transcription locale ; seul le texte remonte en `LOCAL_RECORDING` (lots au contrat) ; fichier déposé
      intact ; battements pendant la transcription.
- [ ] 3 enregistrements par synchro, le reste reporté ; moteur absent → `unavailable` ; échec → `failed` ;
      aucun lot ni curseur dans ces cas.
- [ ] Gateway : curseur `RECORDING` → source `DEPOT` ; `depot_done` dans l'entrée ; phrase de tête.
- [ ] **Isolation** : les références `DEPOT` d'un poste ne partent jamais vers un autre poste.

---

## Périmètre

### Hors scope (explicite)

- Déposer depuis l'écran (F-104 / SF-104-04).
- Supprimer ou déplacer un enregistrement après transcription (le fichier appartient à l'utilisateur).
- Identifier les locuteurs (le moteur F-91 ne le fait pas).
- Toute remontée du fichier, de l'audio ou d'une image.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| enregistrements par synchro | 3 | le reste reporté |
| stabilité d'un fichier | 2 min | depuis la dernière modification |
| attente maximale d'une transcription | 4 h | au-delà : `failed` |
| références `depot_done` transmises | 2 000 | les plus récentes |

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| extension | Oui | liste close ci-dessus |
| compagnon `title` | Non | ≤ 200 caractères |
| compagnon `date` | Non | ISO-8601 local ou avec fuseau |

---

## Technique

### Endpoint(s)

Aucun nouveau (lots et curseurs : `POST /api/runner/radar/syncs/{id}/batches`, SF-100-03).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_sync_cursors` | INSERT / UPDATE / SELECT | source `DEPOT` |

### Migration Liquibase

- [x] Non applicable

### Composants

| Composant | Rôle |
|-----------|------|
| runner `teams/RadarDepositCollector` | relevé, titre et date, transcription locale, lots, couverture |
| runner `teams/RadarCollectors` | enchaîne la collecte Teams puis le dépôt, fusionne les couvertures |
| runner `teams/TeamsTools`, `ToolStack` | dossier de dépôt et moteur de transcription branchés |
| `radar/sync/RadarSyncBatchService` | curseur `RECORDING` → source `DEPOT` |
| `radar/sync/RadarSyncLauncher` | `depot_done` |
| `radar/sync/RadarCoverageSummary` | enregistrements non transcrits comptés (déjà prévu) |

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires

- [ ] runner `RadarDepositCollectorTest` — relevé (extensions, stabilité, vide, sous-dossier ignoré) ;
      référence stable ; déjà transcrit sauté ; compagnon, nom daté, repli ; transcription simulée →
      `LOCAL_RECORDING` avec horodatage depuis la date ; moteur absent → `unavailable` ; échec → `failed` ;
      aucune parole → `failed` ; plafond 3 → reportés ; synchro close → arrêt ; fichier déposé intact.
- [ ] runner `RadarCollectorsTest` — Teams puis dépôt ; couvertures fusionnées ; issue la plus sévère.

### Tests d'intégration

- [ ] `RunnerRadarBatchApiIntegrationTest` — curseur `RECORDING` rangé sous `DEPOT` ; `depot_done` dans
      l'entrée de la synchro suivante, du poste seul.

### Isolation utilisateur

- [x] Applicable — `depot_done` du poste B absent de l'entrée envoyée au poste A.

---

## Dépendances

### Subfeatures bloquantes

- SF-100-03 (lots, curseurs), SF-100-04 (couverture) — prérequis de branche. F-91 / SF-91-03 (transcription
  locale) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants : `RadarSyncBatchService` (périmètre du jeton, inchangé),
  `RadarSyncLauncher` (`depot_done` à `user_id` + `host_id`).
- **Plans / limites : non** (droit Teams déjà vérifié au dépôt de lots).
- **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **Référence par nom, taille et date de modification** plutôt que par empreinte du contenu : hacher des
  centaines de Mo à chaque synchro serait coûteux ; un fichier modifié est un nouvel enregistrement. Réversible.
- **Après la collecte Teams** : la transcription peut durer ; les échanges Teams, plus attendus le matin,
  remontent d'abord.
- **Le fichier déposé reste où il est** : le Radar lit, il ne range pas la machine de l'utilisateur.
