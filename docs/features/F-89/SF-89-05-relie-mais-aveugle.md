# Mini-spec — [F-89 / SF-89-05] Relié mais aveugle : le dire, et regarder aussi les workers

---

## Identifiant

`F-89 / SF-89-05`

## Feature parente

`F-89` — Le volet Teams : le terminal Teams (mini-specs SF-89-01→04)

## Statut

`in-review`

## Date de création

2026-09-13

## Branche Git

`feat/SF-89-05-relie-mais-aveugle`

---

## Objectif

Quand la liaison au navigateur est établie mais que les outils de lecture Teams ne voient rien, le
runner **regarde aussi les cadres et workers Microsoft de la page**, **chiffre** ce qu'il a observé
depuis le rattachement, **dit** quand Teams a répondu par des chemins non reconnus (et invite au
relevé), et **provoque** lui-même le chargement de la liste des conversations ou du calendrier
avant de conclure à zéro.

---

## Contexte

Constat de production (terminal Teams CAGIP, runner macOS, Chrome 151 lancé avec
`--remote-debugging-port=9222` et un profil dédié, Teams web ouvert et connecté, image antérieure à
F-108/F-100 pour la plupart des tours) : `teams_status` rend `LINKED` (« relié à
Chrome/151.0.7922.138 ») ; `teams_find_meetings`, `teams_find_conversations` (même sans filtre) et
`teams_search` rendent **tous zéro**, avec `NOTHING_OBSERVED` « rien d'observé depuis le
rattachement », `actualFrom/actualTo` nuls — y compris après rafraîchissement et navigation.
L'agent a noté que le DOM contenait les données.

**Ce que le code montre (vérifié)** :

1. `NetworkObserver.observeFrames()` (F-108 §4.8, écoute des cadres et workers sur leur session,
   F-100 / SF-100-03) **n'est appelé que par `TeamsRadarCollector`** (synchro du soir). Les outils
   de lecture interactifs n'écoutent **que l'onglet**.
2. `NetworkObserver.onResponse` **écarte sans compter** toute réponse `UNKNOWN` ou `IGNORED` : un
   trafic Microsoft abondant par des chemins non classés est indiscernable d'une page muette.
3. Le zéro est dit « rien d'observé » sans chiffre : on ne peut distinguer ni « Teams affiche depuis
   son cache », ni « Teams répond ailleurs », ni « Teams répond par des chemins nouveaux ».

**Ce que le premier relevé réel a établi** (`docs/features/F-100/releves/releve-teams-2026-09-13-poste-client-macos.md`,
poste client macOS, Chrome 151) :

1. **Fil** : ouvrir un fil n'a produit **aucun** appel `…/conversations/{id}/messages` — seulement
   `/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons`, `/api/mcps/eu/contents`, `/ups/emea/v1/…`, tous
   depuis un **worker**. Le nouveau Teams sert l'historique d'un fil **depuis son cache local** :
   l'observation réseau **ne peut pas garantir** la lecture d'un fil déjà en cache (repli écran : SF-89-06).
2. **Réunions** : `/api/mt/emea/v1/schedulingService/meetings` (classé `MEETING_DETAILS`) vu **seulement
   dans un worker** → écouter les workers corrige `teams_find_meetings`.
   `/api/mt/emea/v2.0/me/calendars/events/iCalUId/{id}` (onglet) était `UNKNOWN` ;
   `/api/mcps/eu/collab/readcollabobject/V2/{id}/{id}/{id}` (onglet, étape réunion) aussi.
3. **Enregistrement** : `/_api/v2.1/drives/{id}/items/{id}/content` en `application/dash+xml` depuis un
   worker (manifeste de lecture en flux, pas un fichier) et `/personal/{id}/_layouts/15/streamembed.aspx`
   (lecteur intégré, onglet + service worker). Noté pour F-108 (le téléchargement par Chrome reste
   l'approche), sans rien réécrire ici.
4. **Transcription** : aucun chemin nouveau observé. **176 réponses** sur des hôtes hors
   `MicrosoftDomains` ont été comptées sans détail : la liste est trop étroite pour relever et compter.

---

## Comportement attendu

### Cas nominal

1. **Les workers aussi (a)** — `teams_status`, `teams_find_conversations`,
   `teams_read_conversation`, `teams_mentions`, `teams_search`, `teams_find_meetings` et
   `teams_meeting_transcript` appellent `link.observer().observeFrames()` avant de lire (idempotent :
   une fois par liaison). Le filtre de domaine de F-108 §4.8 est inchangé : un cadre ou un worker hors
   `MicrosoftDomains` n'est jamais attaché ni lu. La cible attachée est retenue avec son **origine**
   (`FRAME`, `WORKER`, `SERVICE_WORKER`, d'après `targetInfo.type`, même table que le relevé
   `NetworkSurvey.originOf`).
2. **Le diagnostic chiffré (b)** — `NetworkObserver` compte, depuis le rattachement, **sans jamais
   lire un corps ni un en-tête pour cela** :
   - les réponses observées **par origine** (`TEAMS_TAB`, `FRAME`, `WORKER`, `SERVICE_WORKER`) ;
   - les réponses **classées par nature** (`TeamsPayloadKind`, hors `IGNORED`/`UNKNOWN`) ;
   - les réponses ignorées, et les réponses `UNKNOWN` **sur un hôte Microsoft autorisé** ;
   - les **10 chemins `UNKNOWN` les plus fréquents** : hôte ramené à son motif et chemin gabarisé
     (`SurveyPaths.hostMotif` + `SurveyPaths.template`) — **ni requête, ni ancre, ni nom de tenant, ni
     identifiant**.
   Le comptage des chemins non classés et le relevé portent sur la **famille d'hôtes Microsoft**
   (`MicrosoftDomains.isMicrosoftFamily` : `*.microsoft.com` — donc les sous-domaines de
   `teams.microsoft.com` —, `*.skype.com`, `*.office.net`, `*.cloud.microsoft`, `*.svc.ms`, `*.live.com`,
   `*.sharepoint.com`, `*.office.com`, pages d'identification exclues) — **comptage et relevé
   seulement** : `MicrosoftDomains.isAllowed`, seule garde des gestes et de l'écoute des cadres, est
   **inchangée** (F-108). Les **sockets WebSocket** de cette famille et leurs **trames** sont comptées
   (`Network.webSocketCreated` / `webSocketFrameReceived` : hôte, chemin sans requête, nombre de trames —
   **jamais leur contenu**).
   Ce diagnostic (`observation`) est porté par `teams_status` (sous `diagnostic.observation`) et par
   **tout résultat d'outil de lecture qui porte un manque `NOTHING_OBSERVED`**, avec une phrase :
   - trafic Microsoft non classé et rien de classé → le manque devient « Teams a répondu par des
     chemins que l'adaptateur ne reconnaît pas (N réponses non classées depuis le rattachement) » et le
     texte invite : « lancez le relevé : `java -jar claude-runner.jar --releve-teams` » ;
   - aucune réponse du tout → « aucune réponse réseau observée depuis le rattachement (onglet, cadres,
     workers) : Teams affiche peut-être depuis son cache local » ;
   - des réponses classées existent → les chiffres sont rendus, sans réinterpréter le zéro.
3. **L'adaptateur, sur les faits du relevé** — `TeamsUrls` classe
   `…/calendars/events…` en `CALENDAR_EVENT` (nouveau), lu par `TeamsAdapterV1.meetings` sur la forme
   publique documentée d'un événement Microsoft 365 (`subject`, `start`/`end` `{dateTime, timeZone}`,
   `attendees`, `onlineMeeting.joinUrl`) ; un fuseau non sûr (nom Windows) **n'est pas deviné** (manque
   `MISSING_FIELD`). `…/collab/readcollabobject…` est classé `MEETING_COLLAB_OBJECT` (nouveau) : **nommé
   et compté, jamais lu** — aucune forme modèle n'en est connue.
4. **Le relevé** (`--releve-teams`) détaille la famille d'hôtes Microsoft et ajoute une section
   « Sockets WebSocket » (hôte, chemin gabarisé, origines, ouvertures, trames reçues).
5. **Ne pas prétendre** — `teams_read_conversation` sans message dit : « le nouveau Teams sert l'historique
   d'un fil depuis son cache local : l'observation réseau ne garantit pas la lecture d'un fil déjà
   affiché », avec le diagnostic.
6. **Provoquer le chargement (c)** — liaison établie, et le registre ne contient **aucune**
   conversation (`teams_find_conversations`) ou **aucune** réunion (`teams_find_meetings`) après la
   récolte sur place : l'outil navigue l'onglet par `PageActions` (gardes F-108 : domaine vérifié avant
   émission, jamais une page d'identification, geste journalisé) vers la liste des conversations ou le
   calendrier **sur l'hôte Teams de l'onglet**, récolte (jusqu'à 5 relèves espacées d'une seconde),
   **remet la vue** (`PageActions.restore`), puis conclut. Le résultat le **dit** (`viewport`) :
   « La liste était vide : j'ai ouvert … dans votre fenêtre Teams pour que Teams la charge, puis j'ai
   remis la vue. »

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Cadre / worker hors domaines Microsoft (publicité, tiers, page d'identification) | Jamais attaché, jamais compté par origine | — |
| Chemin UNKNOWN sur hôte non Microsoft | Compté dans le total de l'origine, jamais dans les chemins non classés | — |
| Plus de 500 chemins distincts non classés | Les nouveaux sont comptés dans `unknownMicrosoft` mais non détaillés (`unknownPathsDropped`) | — |
| Onglet hors domaines ou sur une page d'identification au moment du chargement | Aucune navigation ; le résultat dit que le chargement n'a pas pu être provoqué (motif de la garde) | — |
| Navigation faite mais toujours rien | Zéro + manque `NOTHING_OBSERVED` diagnostiqué, et le geste est dit | — |
| Registre déjà non vide (filtre sans correspondance) | Aucune navigation : le zéro est un vrai « aucune correspondance » | — |
| Liaison impossible | Inchangé (succès d'outil portant l'état et le remède) | — |

---

## Critères d'acceptation

- [x] CA1 — Un outil de lecture appelé sur une liaison établie active l'écoute des cadres et workers (une seule demande `Target.setAutoAttach` par liaison) ; une réponse de conversation servie **par un service worker Microsoft** est lue et rendue par `teams_find_conversations`.
- [x] CA2 — `teams_status` porte `diagnostic.observation` : réponses par origine, classées par nature, ignorées, `unknownMicrosoft`, 10 chemins non classés au plus, triés par fréquence.
- [x] CA3 — Chemins non classés : jamais de chaîne de requête, d'ancre, de nom de tenant ni d'identifiant de fil (test avec un hôte `contoso.sharepoint.com` et un identifiant `19:…@thread.v2`).
- [x] CA4 — Zéro conversation + trafic Microsoft non classé : le manque dit « Teams a répondu par des chemins que l'adaptateur ne reconnaît pas », le texte cite `--releve-teams`, et le résultat porte `observation`.
- [x] CA5 — Zéro réponse observée : le texte dit « aucune réponse réseau observée depuis le rattachement ».
- [x] CA6 — Registre vide : `teams_find_conversations` navigue vers la liste des conversations, `teams_find_meetings` vers le calendrier, lit ce que Teams sert alors, remet la vue, et le dit dans `viewport`.
- [x] CA7 — Registre non vide sans correspondance : aucune navigation.
- [x] CA8 — Cadre hors domaines : jamais attaché ni compté (non-régression F-108 §4.8) ; aucune commande hors liste blanche émise.
- [x] CA9 — `CALENDAR_EVENT` lu comme une réunion (sujet, début UTC, fil de réunion tiré du lien, participants) ; fuseau Windows → non deviné ; `MEETING_COLLAB_OBJECT` : corps jamais demandé.
- [x] CA10 — Famille Microsoft : comptée et relevée, jamais autorisée aux gestes (`isAllowed` inchangé) ; sockets et trames comptées sans contenu, dans le diagnostic et le relevé.
- [x] CA11 — `teams_read_conversation` sans message ne prétend pas que le réseau garantit la lecture d'un fil en cache.

---

## Périmètre

### Hors scope (explicite)

- Classement des autres chemins non reconnus du relevé (`consumptionhorizons`, `mcps/contents`,
  `ups/…`, `augloop`, `editor.svc`) : sans forme connue, ils restent `UNKNOWN` et comptés.
- Lecture de l'écran (repli DOM) : **SF-89-06**, décision du PO du 2026-09-13 (cadrage F-87 §9 bis).
- Téléchargement / lecture en flux des enregistrements (`dash+xml`, `streamembed.aspx`) : noté pour F-108.
- Lecture des caches du navigateur (IndexedDB, CacheStorage, localStorage) ou des cookies : décision
  de sécurité **non rouverte**.
- Chargement provoqué pour les mentions (flux d'activité) et la transcription : inchangés.
- Écoute des autres onglets (réservée au relevé).
- Toute modification backend / écran : le diagnostic voyage dans le JSON existant du résultat d'outil.

---

## Valeurs initiales

Compteurs à zéro au rattachement ; ils vivent et repartent avec la liaison (D2 de F-87).

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `topUnknownPaths` | Non | 10 entrées, chemin ≤ 300 caractères | `hostMotif + template` | par chemin | tri par fréquence décroissante |
| chemins distincts retenus | — | 500 | — | — | au-delà : comptés, non détaillés |
| relèves après chargement | — | 5 × 1 s | — | — | arrêt dès qu'un objet est servi |

---

## Technique

### Endpoint(s)

Aucun (runner uniquement).

### Tables impactées

Aucune. **Aucune migration.**

### Composants

- `runner/teams/NetworkObserver` — origine des cibles, compteurs, `diagnostic()`.
- `runner/teams/ObservationDiagnostic` (nouveau) — instantané et rendu JSON / phrase.
- `runner/teams/TeamsTools` — écoute des cadres pour les outils de lecture et `teams_status`,
  diagnostic sur les `NOTHING_OBSERVED`, chargement provoqué.
- `runner/teams/TeamsRoutes` — route de la liste des conversations (hypothèse, comme `CALENDAR`) et
  report sur l'hôte Teams de l'onglet.
- `runner/teams/MicrosoftDomains` — `isMicrosoftFamily` (comptage seulement).
- `runner/teams/TeamsPayloadKind`, `TeamsUrls`, `TeamsAdapterV1`, `TeamsLedger` — `CALENDAR_EVENT`,
  `MEETING_COLLAB_OBJECT`.
- `runner/teams/NetworkSurvey`, `SurveyReport` — famille Microsoft, sockets.

### Préoccupations transversales

- [ ] Auth / Principal — non.
- [ ] Contexte tenant — non (runner local ; le nom du tenant est explicitement gabarisé hors du diagnostic).
- [ ] Plans / limites — non.
- [x] Navigation / routing — **oui, dans l'onglet Teams de l'utilisateur** (pas dans l'application
  Angular). Composants impactés : `PageActions.navigate/restore` (gardes F-108 inchangées),
  `TeamsRoutes.CALENDAR` (déjà utilisé par `TeamsRadarCollector`), nouvelle route conversations ;
  chemins existants vérifiés : `TeamsRadarCollector.meetings` (inchangé), `PageGestures.show/restore`
  (inchangé), outils fichiers (n'appellent pas le chargement provoqué — non-régression
  `TeamsFileGuardsEndToEndTest`).

---

## Plan de test

### Tests unitaires

- [ ] `NetworkObserverTest` — origine worker / service worker ; réponse lue sur la session d'un service worker ; compteurs par origine et par nature ; `UNKNOWN` Microsoft vs hors Microsoft ; top 10 trié, borné, gabarisé (sans requête, tenant, identifiant) ; cadre hors domaines jamais compté.
- [ ] `TeamsRoutesTest` (ou existant) — report de la route sur `teams.cloud.microsoft` ; hôte non Teams → route par défaut.

### Tests d'intégration (outils, navigateur de papier)

- [ ] `TeamsBlindLinkTest` — (7) événement de calendrier lu, objet de collaboration jamais lu, fuseau Windows non deviné ; (8) famille comptée mais jamais autorisée ; (9) relevé : famille détaillée, sockets et trames comptées sans contenu ; (1) service worker Microsoft servant la liste → `teams_find_conversations` la rend ; (2) trafic non classé seul → manque « chemins que l'adaptateur ne reconnaît pas », `--releve-teams`, `observation` ; (3) aucun trafic → phrase « aucune réponse réseau observée » ; (4) registre vide → navigation vers la liste des conversations / le calendrier, lecture, vue remise, `viewport` ; (5) registre non vide → aucune navigation ; (6) `teams_status` porte `diagnostic.observation`.
- [ ] Suite runner complète verte (`cd runner && ./mvnw -q test`), dont `TeamsRadarCollectorTest`, `TeamsFileGuardsEndToEndTest`, `AucunCookieNeRemonteTest`, `CdpCommandsTest`.

### Isolation workspace

- [ ] Non applicable — runner local mono-utilisateur, aucune donnée gateway ; la confidentialité est
  couverte par CA3 (aucun tenant, identifiant ni requête dans le diagnostic).

---

## Dépendances

### Subfeatures bloquantes

F-87, F-88, F-108 (SF-108-01 gestes et gardes), F-100 (SF-100-00 relevé, SF-100-03 écoute des cadres) — Done.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **D1 — Rendre visible plutôt que supposer** : aucun chemin n'est ajouté à l'adaptateur ; le
  diagnostic nomme ce que Teams sert, et le relevé en fait la table.
- **D2 — Écoute des cadres à la première lecture, pas au rattachement** : `BrowserLink.attach` reste
  inchangé (les outils fichiers et la sonde n'en ont pas besoin) ; `teams_status` l'active aussi, car
  c'est le premier outil appelé.
- **D4 — Onglet hors Teams** : si l'onglet est sur une page d'identification ou hors domaines au moment
  du chargement, rien ne part ; si la navigation atterrit sur l'identification, rien n'est récolté et
  c'est dit.
- **D3 — Chargement provoqué seulement sur registre vide** : naviguer quand un filtre ne trouve rien
  déplacerait la fenêtre de l'utilisateur pour rien.
