# Mini-spec — [F-89 / SF-89-06] Lire l'écran quand le réseau ne donne rien

---

## Identifiant

`F-89 / SF-89-06`

## Feature parente

`F-89` — Le volet Teams : le terminal Teams (mini-specs SF-89-01→05)

## Statut

`in-review`

## Date de création

2026-09-13

## Branche Git

`feat/SF-89-06-lire-l-ecran`

---

## Objectif

Quand l'observation réseau (onglet + workers, SF-89-05) n'a rien rendu d'utile, `teams_read_conversation`,
`teams_find_conversations`, `teams_mentions` et `teams_meeting_transcript` **lisent le texte affiché**
dans l'onglet Teams relié, en faisant défiler la liste virtualisée, sans doublon ni trou non dit, puis
remettent la vue — et chaque résultat dit sa **source** (réseau ou écran).

---

## Contexte

- **Relevé réel du 2026-09-13** (`docs/features/F-100/releves/releve-teams-2026-09-13-poste-client-macos.md`) :
  ouvrir un fil ne produit aucun appel de messages (cache local) ; ouvrir une transcription ne produit
  aucun échange reconnaissable. L'observation réseau seule ne garantit ni les fils ni les transcriptions.
- **Décision du PO** (cadrage F-87 §9 bis, « Oui je valide ») : repli sur le **texte affiché**, en
  repli seulement ; le réseau reste la source quand il répond. Le stockage (localStorage, IndexedDB,
  CacheStorage) et les cookies restent **refusés**.
- **Règle de conformité** (§9 bis) : transcription au téléchargement bloqué → l'agent peut répondre et
  résumer, **ne recopie jamais la transcription brute** (ni fichier, ni bloc intégral), et **signale** le
  blocage.

---

## Comportement attendu

### Cas nominal

1. **Une couche écran dans l'adaptateur unique** — `TeamsScreen` (paquet `runner.teams`, non publique),
   version `TeamsScreen.VERSION` : la table des **sélecteurs** par vue (fil, liste des conversations,
   flux d'activité, panneau de transcription, bouton de téléchargement, ouverture du panneau), marquée
   « à confirmer sur poste réel ». Le script évalué est **générique** (il applique la table envoyée par
   Java) ; il ne lit que les **champs listés** — texte d'un sous-élément ou un **attribut de la liste
   blanche** (`datetime`, `data-mid`, `data-item-id`, `id`, `aria-label`, `title`, `disabled`,
   `aria-disabled`) —, **jamais** un champ de saisie (`input`, `textarea`, `select`, `contenteditable`,
   `role=textbox`, `type=password`) ni rien qui le contient, **jamais** `document.cookie`, le stockage ou
   les caches. Java **refiltre** : clés hors liste écartées, valeurs non textuelles écartées, 4 000
   caractères au plus, caractères de contrôle retirés, 200 éléments par écran au plus.
2. **Déclenchement (repli seulement)** :
   - `teams_read_conversation` : zéro message par le réseau → le fil demandé est affiché (geste `show`),
     l'écran est lu en **remontant** jusqu'à couvrir `from` (ou le début), puis la position de défilement
     et le fil d'origine sont remis ;
   - `teams_find_conversations` : registre vide après réseau et chargement provoqué → la liste affichée
     est lue (ouverte par la route de SF-89-05 si elle n'est pas à l'écran, puis vue remise) ;
   - `teams_mentions` : zéro mention → le flux d'activité est ouvert (route `#/activity`, hypothèse), les
     éléments qui **disent** une mention (« mentionné », « mentioned ») sont lus, vue remise ;
   - `teams_meeting_transcript` : zéro réplique → le fil de la réunion est affiché s'il est connu, le
     panneau Transcription est ouvert s'il ne l'est pas (clic gardé F-108), les répliques sont lues en
     **descendant** (locuteur, horodatage relatif, texte ; instant absolu = début de réunion + décalage
     quand la réunion est connue), vue remise.
3. **Recollage** : fusion par identifiant d'écran (`data-mid`/`id`, sinon empreinte auteur+heure+texte),
   arrêt quand la fenêtre est couverte, quand la liste ne bouge plus (début/fin), après deux gestes sans
   rien de nouveau (manque `PAGINATION_STOPPED`), ou au plafond de 40 gestes (`CAP_REACHED`). Deux écrans
   successifs **sans recouvrement** → manque nommé « un morceau a pu être sauté ».
4. **Source** : tout résultat des quatre outils porte `source` = `reseau`, `ecran` ou `aucune`, et
   `screenVersion` quand l'écran a été lu ; le texte le dit (« lu à l'écran »).
5. **Téléchargement bloqué** (`teams_meeting_transcript`) : panneau trouvé et bouton de téléchargement
   **absent ou désactivé** → `downloadBlocked: true`, champ `usage` et phrase : « téléchargement bloqué par
   l'organisateur : sers-t'en pour répondre et résumer, ne recopie jamais la transcription brute (ni
   fichier, ni bloc intégral dans le fil), et signale ce blocage à l'utilisateur ». Bouton présent et actif
   → `downloadBlocked: false`. Panneau non trouvé → champ absent. La **consigne de l'outil** côté gateway
   (`TeamsToolCatalog`) porte la même interdiction.
6. **Le Radar aussi** (complément du coordinateur, après la livraison des écrans F-100 #558 / #559) —
   la lecture d'écran est partagée (`TeamsScreenFallback`) :
   - `teams_radar_verify` : la case **conversations** se coche sur la liste affichée quand le réseau n'a
     rien servi ; la case **transcriptions** sur le panneau affiché. Chaque case porte `source`
     (`reseau`/`ecran`/`aucune`) et sa phrase le dit (« à l'écran ») ; la case transcriptions porte
     `downloadBlocked` et le dit. Toujours des compteurs et des états, jamais un nom ni une réplique.
   - `teams_radar_collect` (synchro du soir) : liste des fils lue à l'écran si le réseau n'en sert aucune
     (seuls les fils identifiables sont ouverts) ; chaque fil sans message réseau est **ouvert et lu à
     l'écran** jusqu'au plancher (gestes F-108, vue remise) ; chaque réunion sans réplique réseau voit son
     **panneau Transcription** ouvert et lu. Couverture : `discovery.source`,
     `conversations.readOnScreen`, `meetings.transcribedOnScreen`, `meetings.downloadBlocked`.
   - **Conformité à la synchro** : une transcription au téléchargement bloqué remonte dans un lot dont
     l'échange porte `downloadBlocked: true`. La gateway (`RadarExchangeBatch.Exchange.downloadBlocked`)
     la fait **analyser** (l'en-tête de l'échange demande de n'en citer que de courts extraits ; les
     preuves sont déjà bornées à 280 caractères) et **n'en garde jamais le texte entier** : le brut est
     effacé dès l'analyse (règle F-101 existante) et — nouveau — **dès l'abandon** du lot (FAILED), sans
     attendre l'expiration.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Structure attendue introuvable (conteneur absent après ouverture) | Zéro élément + manque `SCREEN_CHANGED` « Teams a changé d'écran » (version de la lecture d'écran citée) — jamais un résultat partiel inventé | — |
| Élément sans heure (ou sans auteur pour un message) | Écarté et compté (`MISSING_FIELD`) | — |
| Onglet sur une page d'identification / hors domaines | Aucun script, aucun geste (garde `PageActions`) ; manque dit | — |
| Réseau a répondu | Écran **non lu** (`source: reseau`) | — |
| Panneau Transcription impossible à ouvrir | `SCREEN_CHANGED` + « ouvrez la transcription dans Teams, puis redemandez » | — |
| Réponse du script hors liste blanche (clé inconnue, objet imbriqué, texte énorme) | Refiltrée : rien d'autre que les champs listés, bornés | — |

---

## Critères d'acceptation

- [x] CA1 — Fil servi depuis le cache (aucun échange réseau) : `teams_read_conversation` rend les messages lus à l'écran, recollés sans doublon sur plusieurs écrans, dans l'ordre, `source: ecran`, vue et fil d'origine remis.
- [x] CA2 — La lecture remonte jusqu'à couvrir `from` et s'arrête ; début de liste atteint → fenêtre complète ; deux écrans sans recouvrement → manque nommé.
- [x] CA3 — Structure absente → manque `SCREEN_CHANGED` « Teams a changé d'écran », zéro élément.
- [x] CA4 — `teams_find_conversations` et `teams_mentions` : repli écran seulement si le réseau n'a rien rendu ; `source` juste dans les deux cas.
- [x] CA5 — `teams_meeting_transcript` : ouvre le panneau, lit locuteur / décalage / texte en descendant, date les répliques depuis le début de réunion connu ; `downloadBlocked=true` (bouton absent ou désactivé) avec la règle de non-recopie dans `usage` et le texte ; `false` si le bouton est actif.
- [x] CA6 — Gardes : le script ne contient ni `cookie`, ni `localStorage`, ni `sessionStorage`, ni `indexedDB`, ni `caches`, ni lecture de `.value` ; un champ de saisie (même porteur de texte) n'est jamais lu ; Java écarte toute clé hors liste et borne les valeurs.
- [x] CA8 — `teams_radar_verify` coche conversations et transcriptions sur l'écran, avec `source` et, pour la transcription, `downloadBlocked` ; le réseau garde la priorité.
- [x] CA9 — `teams_radar_collect` lit à l'écran un fil servi depuis le cache (jusqu'au plancher, lot remonté, compté) et une transcription (lot `downloadBlocked: true` si bloquée, rien sinon).
- [x] CA10 — Gateway : le drapeau traverse le contrat du lot et la file ; l'analyse le voit ; un lot bloqué abandonné n'a plus de texte brut.
- [x] CA7 — Consigne gateway : la description de `teams_meeting_transcript` interdit la recopie brute quand `downloadBlocked` est vrai et demande de signaler le blocage (test).

---

## Périmètre

### Hors scope (explicite)

- Lecture du stockage du navigateur (localStorage, IndexedDB, CacheStorage) et des cookies : **refusée**.
- Repli écran pour `teams_search` et `teams_find_meetings` (non demandé par la décision).
- Lecture des pièces jointes, réactions, mentions structurées d'un message lu à l'écran (texte seul).
- Confirmation des sélecteurs sur un poste réel : relevé suivant.

---

## Valeurs initiales

Aucune donnée persistée ; `source` = `aucune` quand rien n'est lu.

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| valeur lue | — | 4 000 caractères | texte ; contrôles retirés | — | trim |
| éléments par écran | — | 200 | — | par identifiant d'écran | fusion |
| gestes de défilement | — | 40 | — | — | — |
| attributs lisibles | — | — | `datetime`, `data-mid`, `data-item-id`, `id`, `aria-label`, `title`, `disabled`, `aria-disabled` | — | — |

---

## Technique

### Endpoint(s)

Aucun nouveau. Consigne de l'outil `teams_meeting_transcript` modifiée (`backend/…/teams/TeamsToolCatalog`).

### Tables impactées

Aucune. **Aucune migration.**

### Composants

- Runner : `TeamsScreen` (nouveau, couche écran de l'adaptateur), `TeamsScreenReader` (nouveau, défilement
  et recollage), `TeamsTools` (repli et source), `TeamsGapKind.SCREEN_CHANGED` (nouveau),
  `TeamsRoutes.ACTIVITY` (hypothèse).
- Runner (Radar) : `TeamsScreenFallback` (nouveau, repli partagé), `RadarTools.verify`, `TeamsRadarCollector`.
- Backend : `TeamsToolCatalog` (consigne de `teams_meeting_transcript`) ; `RadarExchangeBatch.Exchange`
  (`downloadBlocked`, champ JSON facultatif, **aucune migration** : il voyage dans le `payload` existant),
  `RadarMaterial` (en-tête), `RadarAnalysisQueue` (brut effacé à l'abandon d'un lot bloqué).
- Tests runner : `jsoup` en **portée test** (déjà présent dans le dépôt Maven local) pour appliquer la même
  table de sélecteurs à des DOM modèles HTML.

### Préoccupations transversales

- [ ] Auth / Principal — non.
- [ ] Contexte tenant — non.
- [ ] Plans / limites — non.
- [x] Navigation / routing — **oui, dans l'onglet Teams de l'utilisateur** : gestes `PageGestures.show/restore`
  (fil), `PageActions.navigate/restore` (liste, activité), `PageActions.click` (panneau Transcription),
  défilement du conteneur puis remise de sa position. Chemins existants vérifiés : `TeamsHarvester.readConversation`
  (inchangé, le repli s'exécute après lui), chargement provoqué SF-89-05 (inchangé), outils fichiers (non concernés).

---

## Plan de test

### Tests unitaires

- [ ] `TeamsScreenTest` — refiltrage (clés hors liste, objets, longueurs, contrôles) ; script sans cookie/stockage/`.value` ; attributs hors liste blanche refusés à la construction ; parsing des décalages (`1:02:03`, `02:03`).

### Tests d'intégration (outils, navigateur de papier + DOM modèles HTML `src/test/resources/teams/ecran/`, « à confirmer sur poste réel »)

- [ ] `TeamsScreenFallbackTest` — fil en cache sur trois écrans (recollage, ordre, `source: ecran`, vue remise) ; fenêtre `from` couverte ; écrans sans recouvrement → manque ; structure absente → `SCREEN_CHANGED` ; champ de saisie jamais lu ; liste des conversations et mentions à l'écran ; réseau présent → écran non lu ; transcription : ouverture du panneau, dates, `downloadBlocked` vrai (absent / désactivé) et faux.
- [ ] `TeamsToolCatalogTest` (backend) — consigne de non-recopie.
- [ ] `TeamsScreenRadarTest` — vérification (conversations et transcriptions à l'écran, source, blocage, réseau prioritaire) ; synchro (fil en cache lu à l'écran, transcription bloquée → lot drapeauté, libre → sans drapeau).
- [ ] `RadarExchangeBatchTest` / `RadarAnalysisQueueIntegrationTest` (backend) — drapeau lu, conservé, vu par l'analyse ; brut effacé à l'abandon.
- [ ] Suite runner complète verte.

### Isolation workspace

- [ ] Non applicable — runner local mono-utilisateur.

---

## Dépendances

### Subfeatures bloquantes

SF-89-05 (Done), F-108 SF-108-01 (gestes gardés, Done).

### Questions ouvertes impactées

Aucune (décision PO du 2026-09-13, cadrage F-87 §9 bis).

---

## Notes et décisions

- **D1 — Script générique, sélecteurs en Java** : la table est l'adaptateur (versionnée, testable contre des
  DOM modèles) ; le script ne sait rien de Teams et ne peut lire que ce que la table nomme.
- **D2 — Structure absente = manque, pas repli silencieux** : « Teams a changé d'écran » plutôt qu'un
  résultat à moitié faux.
- **D3 — Décalage de transcription** : l'instant absolu n'est calculé que si le début de réunion est connu ;
  sinon la réplique porte son décalage et `at` reste vide (on ne date pas au hasard).
- **D4 — Liste des conversations et flux d'activité** : 3 défilements au plus (lecture de ce qui est affiché,
  pas un historique complet).
- **Dépendance de test** : `org.jsoup:jsoup:1.10.2` en portée `test` (disponible dans le dépôt Maven local,
  aucun téléchargement) ; rien n'entre dans le jar livré.
- **Limite assumée des tests** : le script JavaScript n'est pas exécuté en test (aucun moteur JS dans le
  runner) ; la même table de sélecteurs est appliquée aux DOM modèles par `jsoup`. La conformité du script au
  navigateur réel est à confirmer sur poste.
