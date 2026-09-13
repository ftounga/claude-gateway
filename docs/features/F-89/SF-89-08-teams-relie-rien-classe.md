# Mini-spec — [F-89 / SF-89-08] Teams relié, rien classé

---

## Identifiant

`F-89 / SF-89-08`

## Feature parente

`F-89` — Le volet Teams : le terminal Teams (mini-specs SF-89-01→07)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-89-08-teams-relie-rien-classe`

---

## Objectif

Quand Teams est relié mais que les outils de lecture rendent zéro, le runner **dit laquelle des deux
pannes** c'est — Teams n'a rien servi (geste humain) ou Teams a servi du contenu que l'adaptateur ne
reconnaît pas (correctif logiciel) —, **remonte l'inventaire complet** des chemins non reconnus, observe
les **mêmes cibles** que le relevé, et rend l'ajout d'une règle de classement **une ligne et un test**.

---

## Contexte

Constat du poste client (`docs/features/F-89/constats/2026-09-13-poste-client-teams-rien-classe.md`,
runner de l'image `staging-87245f3`, qui contient SF-89-05 et SF-89-06) : `teams_status` `LINKED`,
28 réponses observées, **0 classée** ; tous les outils de lecture à zéro avec `NOTHING_OBSERVED — rien
d'observé depuis le rattachement` ; l'agent a fait rouvrir cinq fois des écrans déjà ouverts.

Hypothèse de l'auteur : « l'URL arrive à `classify` sans son hôte ». **Écartée, avec preuves** :

1. **Code** — `NetworkObserver.onResponse` (l. 199-201) passe `ObservedResponse.withoutQuery(response.url)`
   — hôte conservé — à `adapter.classify` ; `NetworkSurvey.record` (l. 272) passe l'URL complète à
   `TeamsUrls.classify`. Seul l'affichage (`SurveyPaths.hostMotif` / `template`) sépare hôte et chemin.
2. **Test sur trames brutes** — `NetworkObserverChromeFramesTest` rejoue des trames Chrome réelles
   (session aplatie, en-têtes, `fromServiceWorker`, requête dans l'adresse) par le **vrai** routage
   `WebSocketCdpConnection.dispatch` : chaque famille de `TeamsPayloadKind` est classée depuis l'onglet,
   un worker et un service worker (27 cas verts **avant** tout correctif).
3. **Le tableau du relevé cité** (47 `UNKNOWN`, 2 `IGNORED`, 2 `MEETING_DETAILS`, lignes `TEAMS_TAB`
   non classées) est **le premier relevé** `docs/features/F-100/releves/releve-teams-2026-09-13-poste-client-macos.md`
   (relevé 18:07Z, commit 49d6814 à 20:15) — **antérieur** à SF-89-05 (7563382, 20:38) qui a justement
   ajouté `CALENDAR_EVENT` et `MEETING_COLLAB_OBJECT` pour ces deux chemins. L'asymétrie « WORKER classé,
   onglet non » venait de l'ancien classifieur, pas d'un trajet différent.
4. **Jar** — `backend/Dockerfile` construit `claude-runner.jar` depuis `runner/src` au commit de l'image ;
   87245f3 contient 7563382.
5. **Mesure ultérieure du même poste** (coordinateur) : 632 réponses, **16 classées**
   (`CONVERSATION_LIST` ×1, `PROFILE` ×15), 0 `MEETING_*`, `unknownMicrosoft` = 110, top 10 seulement.
   Le classement fonctionne ; la première mesure était trop tôt. **Le vrai trou est la reconnaissance**
   des appels de réunion, calendrier, récapitulatif et transcription du Teams v2 réel — et l'inventaire
   remonté (10 chemins) ne permet pas de les apprendre.

**Asymétrie réelle trouvée entre relevé et outils** (prouvée par test rouge) : le relevé
(`NetworkSurvey.onAttached`) redemande `Target.setAutoAttach` sur la session de chaque cible retenue ;
l'observation des outils (`NetworkObserver.onAttached`) ne le fait pas. Or Chrome n'annonce les enfants
d'une cible (worker né d'un worker, worker d'un cadre intégré) que si l'auto-attach a été demandé **sur la
session de ce parent**. Ce que le relevé voit, les outils peuvent donc ne pas le voir. Rien ne prouve que
ce soit la cause sur ce poste ; c'est un écart de code corrigé.

---

## Comportement attendu

### Cas nominal

1. **Mêmes cibles que le relevé** — `NetworkObserver.onAttached`, pour une cible retenue (domaine
   Microsoft autorisé, F-108 §4.8 inchangé), active le réseau **et** redemande `Target.setAutoAttach`
   (mêmes paramètres) sur sa session. Une cible refusée ne reçoit aucune commande : ses enfants ne sont
   jamais annoncés.
2. **Deux manques au lieu d'un** (outils de lecture : `teams_find_conversations`,
   `teams_read_conversation`, `teams_mentions`, `teams_search`, `teams_find_meetings`,
   `teams_meeting_transcript`) — un `NOTHING_OBSERVED` est requalifié d'après le diagnostic et **les
   natures utiles à l'outil** :
   - `NOTHING_CLASSIFIED` — aucune réponse de la nature utile classée **et** des réponses Microsoft non
     reconnues (`unknownMicrosoft > 0`) : « le contenu est arrivé mais n'a pas été reconnu ; cliquer ou
     rouvrir l'écran n'y changera rien » ; le résultat porte `observation` avec ses chemins non reconnus
     (10 premiers) et renvoie à l'inventaire complet de `teams_status` ;
   - `NOTHING_SERVED` — aucune réponse de la nature utile classée **et** aucune réponse Microsoft non
     reconnue (rien, ou seulement statiques / télémétrie / hors Microsoft) : « Teams n'a rien servi depuis
     le rattachement : ouvrez l'écran voulu dans Teams, puis redemandez » ;
   - une réponse de la nature utile a été classée → `NOTHING_OBSERVED` inchangé (servi, reconnu, rien ne
     correspond).
   Natures utiles : conversations → `CONVERSATION_LIST`, `CONVERSATION_MESSAGES` ; fil →
   `CONVERSATION_MESSAGES` ; mentions → `ACTIVITY_FEED` ; recherche → `SEARCH_RESULTS` ; réunions →
   `MEETING_DETAILS`, `CALENDAR_EVENT` ; transcription → `MEETING_TRANSCRIPT`.
3. **Repli écran** — `NOTHING_CLASSIFIED` n'empêche pas le repli de SF-89-06 : les quatre outils qui
   savent lire l'écran le lisent (zéro par le réseau), et le manque n'est requalifié qu'après ce repli.
   `teams_meeting_transcript` sur `NOTHING_CLASSIFIED` **ne dit plus** « Ouvrez la transcription dans
   Teams, puis redemandez ».
4. **Inventaire complet** — `teams_status` porte `diagnostic.observation.unknownPaths` : **tous** les
   chemins non reconnus de la famille Microsoft depuis le rattachement, du plus fréquent au moins
   fréquent, **200 au plus**, chacun avec `host` (motif), `path` (gabarit), `origins`, `mimeTypes`,
   `count` ; au-delà, `unknownPathsNotListed` (chemins distincts non listés) et `unknownPathsDropped`
   (réponses de chemins non retenus). Les résultats d'outils gardent `topUnknownPaths` (10).
5. **Une règle = une ligne** — `TeamsUrls` porte ses règles de classement des hôtes de conversation dans
   une **table ordonnée** (`nature`, fragments contenus, fin de chemin) ; le comportement existant est
   strictement conservé ; `TeamsUrlsTest` porte une table « une ligne par règle ». Aucune règle nouvelle
   n'est devinée (les chemins réels viendront du PO).
6. **Consigne de l'agent** — `TeamsToolCatalog` (gateway) : `teams_status` et les outils de lecture disent
   `NOTHING_SERVED` → proposer d'ouvrir l'écran ; `NOTHING_CLASSIFIED` → **ne pas** demander de rouvrir
   ou cliquer, le dire, et renvoyer à l'inventaire de `teams_status`.
7. **`--releve-teams` sans terminal** — entrée standard qui n'est pas un terminal (`System.console()`
   absent) → échec explicite, code 2 : « le relevé est interactif : il attend qu'un opérateur tape le
   numéro de chaque étape. Lancez-le dans un terminal. » ; l'usage de la commande le dit.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Cible petite-fille sous une cible refusée (hors domaines) | Jamais annoncée, jamais écoutée ; aucune commande envoyée à la cible refusée | — |
| Plus de 200 chemins non reconnus distincts | 200 listés, `unknownPathsNotListed` le dit | — |
| Plus de 500 chemins distincts retenus | Réponses comptées dans `unknownMicrosoft` et `unknownPathsDropped` | — |
| Réponses seulement statiques / télémétrie | `NOTHING_SERVED` (rien d'utile servi), jamais `NOTHING_CLASSIFIED` | — |
| Nature utile classée mais filtre sans correspondance | `NOTHING_OBSERVED` inchangé | — |
| `--releve-teams` lancé sans terminal (tube, service, agent) | Code 2, message et usage ; aucun rapport vide écrit, navigateur non contacté | — |
| Options invalides | Inchangé (code 2 et usage) | — |

---

## Critères d'acceptation

- [ ] CA1 — Trames brutes Chrome, les 9 familles de `TeamsPayloadKind` × onglet / worker / service worker, adresse complète avec requête : chaque réponse est classée sous sa nature, corps demandé sur la bonne session (sauf `MEETING_COLLAB_OBJECT`, jamais lu).
- [ ] CA2 — Un worker né d'un worker et un worker d'un cadre Microsoft sont annoncés et leurs réponses classées par l'observation des outils, comme par le relevé (test rouge avant correctif).
- [ ] CA3 — Une cible hors domaines ne reçoit aucune commande ; ses enfants ne sont jamais annoncés.
- [ ] CA4 — Zéro + réponses Microsoft non reconnues (et aucune de la nature utile) → manque `NOTHING_CLASSIFIED`, texte « n'a pas été reconnu », « n'y changera rien », `observation` porté ; aucun « rouvrez » / « ouvrez … puis redemandez ».
- [ ] CA5 — Zéro + aucune réponse Microsoft non reconnue → `NOTHING_SERVED`, texte qui invite à ouvrir l'écran.
- [ ] CA6 — Réunions : 15 `PROFILE` classés + réponses non reconnues, 0 `MEETING_*` → `NOTHING_CLASSIFIED` (la nature utile compte, pas le total).
- [ ] CA7 — `NOTHING_CLASSIFIED` : le repli écran de SF-89-06 est lu (source `ecran` quand l'écran porte la liste).
- [ ] CA8 — `teams_status` : `unknownPaths` complet, trié, borné à 200, avec hôte motif, gabarit, origines, types MIME, nombre ; aucun tenant, identifiant ni requête ; `unknownPathsNotListed` au-delà.
- [ ] CA9 — `TeamsUrls` : table de règles ordonnée, comportement inchangé (suite existante verte) ; test table « une ligne par règle ».
- [ ] CA10 — Consigne gateway : la règle des deux manques est dans `teams_status` et les outils de lecture.
- [ ] CA11 — `--releve-teams` sans terminal : code 2, message explicite, usage qui le dit, navigateur non contacté.

---

## Périmètre

### Hors scope (explicite)

- **Nouvelles règles de classement** (réunions, calendrier, récapitulatif, transcription du Teams v2
  réel) : attendues du PO sur chemins réels — ne pas deviner.
- Repli écran pour `teams_find_meetings` et `teams_search` (aucune vue écran connue ; sélecteurs non
  relevés).
- Lecture des caches du navigateur, cookies, en-têtes : décision de sécurité **non rouverte**.
- Les autres usages de `NOTHING_OBSERVED` (fichiers, captures, enregistrements, Radar) : inchangés.
- Écran Angular : aucun ; le diagnostic voyage dans le JSON existant.
- Mise à jour du runner sur le poste : F-111.

---

## Valeurs initiales

Compteurs à zéro au rattachement ; inventaire vide.

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `unknownPaths` | Non | 200 entrées ; chemin ≤ 300 caractères | `host` motif + `path` gabarit | par hôte + chemin | tri par nombre décroissant, puis ordre de première vue |
| chemins non reconnus retenus | — | 500 distincts | — | — | au-delà : comptés, non détaillés |
| `mimeTypes` d'un chemin | Non | 5 valeurs | type MIME sans paramètres, minuscules | par chemin | `;charset` retiré |

---

## Technique

### Endpoint(s)

Aucun (runner ; consigne d'outil côté gateway, aucun contrat HTTP modifié).

### Tables impactées

Aucune. **Aucune migration.**

### Composants

- `runner/teams/NetworkObserver` — auto-attach redemandé sur les cibles retenues ; inventaire par chemin (origines, MIME).
- `runner/teams/ObservationDiagnostic` — `unknownPaths` (200), `unknownPathsNotListed`, rendu compact/complet, `nothingKind(...)`, phrases.
- `runner/teams/TeamsGapKind` — `NOTHING_SERVED`, `NOTHING_CLASSIFIED`.
- `runner/teams/TeamsTools` — requalification par nature utile, rendu complet dans `teams_status`, texte de la transcription.
- `runner/teams/TeamsUrls` — table de règles.
- `runner/teams/TeamsSurveyCommand` — refus sans terminal.
- `backend/teams/TeamsToolCatalog` — règle des deux manques dans les descriptions.

### Préoccupations transversales

- [ ] Auth / Principal — non.
- [ ] Contexte tenant — non (runner local ; tenant gabarisé hors de l'inventaire, comme SF-89-05).
- [ ] Plans / limites — non.
- [ ] Navigation / routing — non (aucune route, aucun geste nouveau ; les gestes F-108 et le chargement provoqué de SF-89-05 sont inchangés).

---

## Plan de test

### Tests unitaires

- [ ] `NetworkObserverChromeFramesTest` — CA1 (27 cas), constat rejoué, CA2 petits-enfants (worker de worker, worker de cadre), relevé et outils voient les mêmes cibles, CA3 cible refusée.
- [ ] `TeamsNothingServedOrClassifiedTest` (règle seule) — requalification `NOTHING_SERVED` / `NOTHING_CLASSIFIED` / inchangé par nature utile (D1, D2).
- [ ] `TeamsRadarCollectorTest` — non-régression : l'auto-attach redemandé sur le cadre retenu, jamais sur le cadre étranger.
- [ ] `TeamsUrlsTest` — table « une ligne par règle » (CA9) ; suite existante inchangée.
- [ ] `NetworkSurveyTest` — `--releve-teams` sans terminal : code 2, message, navigateur non contacté (CA11).

### Tests d'intégration (outils, navigateur de papier)

- [ ] `TeamsNothingServedOrClassifiedTest` — CA4 (transcription sans « ouvrez »), CA5, CA6 (réunions : PROFILE classés, 0 MEETING), CA7 (repli écran lu sous `NOTHING_CLASSIFIED` ; liste écran sans correspondance non requalifiée), CA8 (`teams_status` inventaire sans tenant ni identifiant).
- [ ] `TeamsBlindLinkTest`, `TeamsReadingToolsTest`, `TeamsGisementsTest` — CA4 (conversations) et manques requalifiés sur les scénarios existants.
- [ ] `TeamsReadingCatalogTest` (gateway) — CA10.
- [ ] Suites complètes vertes : `cd runner && ./mvnw -q test` ; `cd backend && ./mvnw -q test -Dtest='Teams*'` puis suite complète.

### Isolation workspace

- [ ] Non applicable — runner local mono-utilisateur ; confidentialité couverte par CA8 (aucun tenant,
  identifiant ni requête dans l'inventaire). Consigne gateway : catalogue toujours derrière le droit
  (non-régression `TeamsReadingCatalogTest`).

---

## Dépendances

### Subfeatures bloquantes

SF-89-05, SF-89-06 — Done. SF-89-07 livrée en parallèle (sans recouvrement de fichiers attendu hors
`TeamsToolCatalog` éventuel — rebase avant merge).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **D1 — « Réponses reçues » = réponses Microsoft non reconnues.** 20 statiques d'un service worker ne
  sont pas « du contenu arrivé » : sans `unknownMicrosoft`, le manque est `NOTHING_SERVED`.
- **D2 — La nature utile décide, pas le total.** La seconde mesure (16 classées, toutes `PROFILE` /
  `CONVERSATION_LIST`, 0 réunion) montre qu'un total non nul cache le même trou pour `teams_find_meetings`.
- **D3 — Inventaire complet dans `teams_status`, top 10 dans les outils** : l'inventaire sert à apprendre
  les chemins ; le répéter dans chaque résultat alourdirait le contexte de l'agent pour rien.
- **D4 — La phrase `NOTHING_CLASSIFIED` ne cite plus `--releve-teams`** : le relevé exige un opérateur au
  clavier ; l'inventaire de `teams_status` donne la même table sans lui.
- **D5 — Aucune règle devinée** : consigne du coordinateur ; la table rend l'ajout trivial.
